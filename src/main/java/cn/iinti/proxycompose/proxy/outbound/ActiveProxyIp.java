package cn.iinti.proxycompose.proxy.outbound;

import cn.iinti.proxycompose.proxy.outbound.downloader.DownloadProxyIp;
import cn.iinti.proxycompose.utils.ConsistentHashUtil;
import cn.iinti.proxycompose.utils.NettyUtil;
import cn.iinti.proxycompose.resource.DropReason;
import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.trace.Recorder;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveProxyIp {

    @Getter
    private final DownloadProxyIp downloadProxyIp;

    private final Looper workThread;
    private final Recorder recorder;
    @Getter
    private final IpPool ipPool;

    @Getter
    private final long murHash;
    @Getter
    private final long seq;

    @Getter
    private volatile ActiveStatus activeStatus;


    private final Set<Channel> usedChannels = ConcurrentHashMap.newKeySet();

    private final CacheHandle cachedHandle = new CacheHandle();

    private final Set<Long> usedHash = new HashSet<>();


    private Runnable destroyFun;

    public ActiveProxyIp(IpPool ipPool, DownloadProxyIp downloadProxyIp, long seq) {
        this.ipPool = ipPool;
        this.seq = seq;
        this.downloadProxyIp = downloadProxyIp;
        this.workThread = ipPool.getRuntimeIpSource().getLooper();
        this.recorder = ipPool.getRuntimeIpSource().getRecorder();
        this.activeStatus = ActiveStatus.ONLINE;
        this.murHash = ConsistentHashUtil.murHash(downloadProxyIp.getResourceId());
    }

    public void destroy(DropReason dropReason) {
        if (activeStatus == ActiveStatus.DESTROY) {
            return;
        }
        workThread.execute(() -> {
            if (activeStatus == ActiveStatus.DESTROY) {
                return;
            }
            if (activeStatus == ActiveStatus.ONLINE) {
                activeStatus = ActiveStatus.OFFLINE;
            }

            destroyFun = () -> {
                workThread.checkLooper();
                if (activeStatus == ActiveStatus.DESTROY) {
                    return;
                }
                activeStatus = ActiveStatus.DESTROY;
                destroyFun = null;
                cachedHandle.destroy();
            };
            if (usedHash.isEmpty() || dropReason == DropReason.IP_SERVER_UNAVAILABLE) {
                // 如果当前没有用户占用本ip，则立即销毁
                destroyFun.run();
                return;
            }
            // 否则给一个60s的延时时间，给业务继续使用
            // 请注意整个60s的延时不是确定的，如果业务提前判断全部离开本ip，则清理动作可以提前
            workThread.postDelay(destroyFun, 60_000);
        });


    }


    public void borrowConnect(Recorder recorder, String tag,
                              ActivityProxyIpBindObserver observer,
                              ValueCallback<Channel> valueCallback) {
        observer.onBind(this);
        workThread.execute(() -> {
            cachedHandle.onConnBorrowed();

            // 尝试使用缓存的ip资源
            while (true) {
                Channel one = cachedHandle.cachedChannels.poll();
                if (one == null) {
                    break;
                }
                if (one.isActive()) {
                    recorder.recordEvent(() -> tag + "conn cache pool hinted");
                    ValueCallback.success(valueCallback, one);
                    return;
                }
            }
            recorder.recordEvent(() -> tag + "begin to create connection immediately");

            createUpstreamConnection(valueCallback, recorder);

        });

    }

    public boolean isIdle() {
        return usedChannels.isEmpty();
    }

    private static final AttributeKey<ActiveProxyIp> ACTIVITY_PROXY_IP_KEY = AttributeKey.newInstance("ACTIVITY_PROXY_IP");

    private void createUpstreamConnection(ValueCallback<Channel> valueCallback, Recorder userRecorder) {
        OutboundOperator.connectToServer(downloadProxyIp.getProxyHost(), downloadProxyIp.getProxyPort(), value -> {
            if (!value.isSuccess()) {
                // 这里失败我们我们不再执行立即替换出口ip的逻辑，这是因为在并发极高的情况下
                // 失败可能是我们自己的网络不通畅导致的，我们不能以链接失败就判定ip存在问题
                ipPool.onCreateConnectionFailed(ActiveProxyIp.this, value.e, userRecorder);
                ValueCallback.failed(valueCallback, value.e);
                return;
            }

            // setup meta info
            Channel channel = value.v;
            channel.attr(ACTIVITY_PROXY_IP_KEY).set(this);
            channel.closeFuture().addListener(it -> usedChannels.remove(channel));
            cachedHandle.scheduleCleanIdleCache(channel, ipPool.getRuntimeIpSource().getConnIdleSeconds().value);

            ValueCallback.success(valueCallback, channel);
        });
    }

    public static void restoreCache(Channel channel) {
        ActiveProxyIp activeProxyIp = getBinding(channel);
        if (activeProxyIp == null) {
            channel.close();
            return;
        }
        activeProxyIp.cachedHandle.restoreCache(channel);
    }

    public static void offlineBindingProxy(Channel channel, IpPool.OfflineLevel level, Recorder userRecorder) {
        ActiveProxyIp activeProxyIp = getBinding(channel);
        if (activeProxyIp == null) {
            return;
        }
        activeProxyIp.getIpPool().offlineProxy(activeProxyIp, level, userRecorder);
    }

    public static ActiveProxyIp getBinding(Channel channel) {
        return channel.attr(ACTIVITY_PROXY_IP_KEY).get();
    }

    public void makeCache() {
        if (activeStatus != ActiveStatus.ONLINE) {
            return;
        }
        cachedHandle.doCreateCacheTask();
    }

    private class CacheHandle {
        private final LinkedList<Channel> cachedChannels = Lists.newLinkedList();
        /**
         * 用户请求的平均时间间隔，代表这个ip资源处理请求的qps，但是我们使用一个高效计算方案评估这个指标<br>
         * 这个值将会约等于最近10次请求时间间隔的平均数
         */
        private double avgInterval = 1000;

        private long lastRequestConnection = 0;

        private long lastCreateCacheConnection = 0;

        private void destroy() {
            NettyUtil.closeAll(cachedChannels);
            cachedChannels.clear();
        }

        public void restoreCache(Channel channel) {
            workThread.execute(() -> {
                if (activeStatus == ActiveStatus.DESTROY) {
                    channel.close();
                    return;
                }
                cachedChannels.addFirst(channel);
            });
        }

        public void onConnBorrowed() {
            long now = System.currentTimeMillis();
            if (lastRequestConnection == 0L) {
                lastRequestConnection = now;
                return;
            }

            long thisInterval = now - lastRequestConnection;
            lastRequestConnection = now;
            avgInterval = (thisInterval * 9 + avgInterval) / 10;
        }

        void scheduleCleanIdleCache(Channel cacheChannel, Integer idleSeconds) {
            // 为了避免gc hold，所有延时任务里面不允许直接访问channel对象，而使用WeakReference
            WeakReference<Channel> channelRef = new WeakReference<>(cacheChannel);
            workThread.postDelay(() -> {
                Channel gcChannel = channelRef.get();
                if (gcChannel == null || !gcChannel.isActive()) {
                    return;
                }
                for (Channel ch : cachedChannels) {
                    if (ch.equals(gcChannel)) {
                        gcChannel.close();
                        return;
                    }
                }
            }, idleSeconds * 1000);
        }


        public void doCreateCacheTask() {
            if (cachedChannels.size() > 3) {
                recorder.recordEvent(() -> "skip make conn cache because of cache overflow:" + cachedChannels.size());
                //理论上真实流量一半的速率，cachedChannels.size()应该为0或者1
                return;
            }

            long now = System.currentTimeMillis();
            if (lastCreateCacheConnection != 0 && (now - lastRequestConnection) < avgInterval / 2) {
                // 流量为真实速率的一半
                return;
            }
            lastCreateCacheConnection = now;

            recorder.recordEvent(() -> "fire conn cache make task");
            createUpstreamConnection(value -> {
                if (value.isSuccess()) {
                    workThread.execute(() -> cachedChannels.addFirst(value.v));
                }
            }, Recorder.nop);
        }

    }

    public boolean canUserPassAuth() {
        return StringUtils.isNoneBlank(
                downloadProxyIp.getUserName(),
                downloadProxyIp.getPassword()
        );
    }


    public String buildHttpAuthenticationInfo() {
        if (canUserPassAuth()) {
            String authorizationBody = downloadProxyIp.getUserName() + ":" + downloadProxyIp.getPassword();
            return "Basic " + BaseEncoding.base64().encode(authorizationBody.getBytes(StandardCharsets.UTF_8));
        }

        return null;
    }

    public void refreshRefSessionHash(long sessionHash, boolean add) {
        workThread.execute(() -> {
            if (add) {
                usedHash.add(sessionHash);
                return;
            }
            usedHash.remove(sessionHash);
            if (usedHash.isEmpty() && destroyFun != null) {
                destroyFun.run();
            }
        });
    }



    public enum ActiveStatus {
        ONLINE,
        OFFLINE,
        DESTROY
    }

    public interface ActivityProxyIpBindObserver {
        void onBind(ActiveProxyIp activeProxyIp);
    }


}
