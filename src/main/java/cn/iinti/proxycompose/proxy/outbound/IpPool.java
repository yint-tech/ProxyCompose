package cn.iinti.proxycompose.proxy.outbound;

import cn.iinti.proxycompose.resource.DropReason;
import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.proxy.outbound.downloader.DownloadProxyIp;
import cn.iinti.proxycompose.proxy.RuntimeIpSource;
import cn.iinti.proxycompose.utils.ConsistentHashUtil;
import cn.iinti.proxycompose.trace.Recorder;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class IpPool {
    @Getter
    private final RuntimeIpSource runtimeIpSource;
    private final Looper workThread;
    private final Recorder recorder;

    /**
     * 一致性哈希ip池,提供在池中随机（固定session）的能力
     */
    private final TreeMap<Long, ActiveProxyIp> poolWithMurHash = new TreeMap<>();


    /**
     * 非ip池，一个独立的，用于track代理资源入库时间的存储域
     */
    private final TreeMap<Long, ActiveProxyIp> poolWithCreateSequence = new TreeMap<>();
    private final AtomicLong outboundInc = new AtomicLong(0);
    /**
     * 备用ip资源，溢出的额外代理ip资源
     */
    private final LinkedHashMap<String, DownloadProxyIp> cachedProxies = new LinkedHashMap<String, DownloadProxyIp>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            // 自动删除超量ip，曾经因为没有处理这个变量，导致内存有两百万的ip资源
            // 范围为 10 - 1024之间的，配置ip池化大小的3倍容量
            Integer configPoolSize = runtimeIpSource.getPoolSize().value;
            int maxCacheSize = Math.min(configPoolSize * 3, 1024);
            maxCacheSize = Math.max(10, maxCacheSize);
            return size() > maxCacheSize;
        }
    };

    public IpPool(RuntimeIpSource runtimeIpSource, Looper workThread, Recorder recorder, String sourceKey) {
        this.runtimeIpSource = runtimeIpSource;
        this.workThread = workThread;
        this.recorder = recorder;
    }

    public void allocateIp(long hash, Recorder userRecorder,
                           ValueCallback<ActiveProxyIp> valueCallback) {
        workThread.execute(() -> {
            if (poolWithMurHash.isEmpty()) {
                ValueCallback.failed(valueCallback, "no ip in pool");
                return;
            }
            runtimeIpSource.recordComposedEvent(userRecorder, () -> "allocateOutbound request" +
                    " hash: " + hash +
                    " pool size:" + poolWithMurHash.size()
            );

            ActiveProxyIp activeProxyIp;

            runtimeIpSource.recordComposedEvent(userRecorder, () -> "default session hash plan");
            // 默认策略，在当前ip池中进行一致性哈希绑定
            activeProxyIp = ConsistentHashUtil.fetchConsistentRing(poolWithMurHash, hash);

            if (activeProxyIp == null) {
                ValueCallback.failed(valueCallback, "no suitable ip in the pool");
            } else {
                ValueCallback.success(valueCallback, activeProxyIp);
            }
        });
    }


    public void onCreateConnectionFailed(ActiveProxyIp activeProxyIp, Throwable cause, Recorder userRecorder) {
        runtimeIpSource.recordComposedEvent(userRecorder, () ->
                "连接创建失败->,进入IP资源下线决策阶段,"
                        + "当前系统缓存池大小:" + cachedProxies.size()
        );
        runtimeIpSource.recordComposedMosaicEvent(userRecorder, cause::getMessage);


        String message = cause.getMessage();
        OfflineLevel offlineLevel;
        String offlineReason = "未知原因";
        if (message.contains("Connection refused") || message.contains("拒绝连接")) {
            // 明确这个ip资源无法使用了,强制下线
            offlineLevel = OfflineLevel.MUST;
            offlineReason = "拒绝连接";
        } else if (message.contains("connection timed out") || message.contains("连接超时")) {
            // 连接超时，可能是防火墙拦截，也可能是对方服务器负载高无法处理我们的请求
            // 此时根据ip池的容量大小决定是否要下线
            offlineLevel = OfflineLevel.STRONG;
            offlineReason = "连接超时";
        } else {
            // 其他失败原因，在ip池非常富裕的条件下执行下线
            offlineLevel = OfflineLevel.SUGGEST;
        }


        String finalOfflineReason = offlineReason;
        runtimeIpSource.recordComposedEvent(userRecorder, () -> "资产下线触发强度：" + offlineLevel + " 下线原因:" + finalOfflineReason);

        offlineProxy(activeProxyIp, offlineLevel, userRecorder);
    }

    public boolean poolEmpty() {
        return poolWithMurHash.isEmpty();
    }

    public double healthScore() {
        return (poolWithMurHash.size() + cachedProxies.size()) * 100.0
                / runtimeIpSource.getPoolSize().value;
    }

    public enum OfflineLevel {
        MUST("MUST"),// 必须下线，不关心什么原因
        STRONG("STRONG"),// 强烈要求下线，除非系统状态bad
        SUGGEST("SUGGEST")// 建议下线，轻微请求，除非系统非常富裕
        ;
        // 加一个name是为了避免混淆修改class的名字，毕竟添加keep很容易导致keep范围被放大
        public final String name;

        OfflineLevel(String name) {
            this.name = name;
        }
    }

    public void offlineProxy(ActiveProxyIp activeProxyIp, OfflineLevel level, Recorder userRecorder) {

        runtimeIpSource.recordComposedEvent(userRecorder, () -> "offline proxy by pool status: " + level.name);
        runtimeIpSource.recordComposedMosaicEvent(userRecorder, () -> " proxy: " + activeProxyIp.getDownloadProxyIp().getResourceId());
        if (poolWithMurHash.size() > runtimeIpSource.getPoolSize().value) {
            // ip池大小变更，直接下线
            offlineProxy(activeProxyIp, level == OfflineLevel.MUST ? DropReason.IP_SERVER_UNAVAILABLE : DropReason.IP_IDLE_POOL_OVERFLOW, userRecorder);
            return;
        }
        switch (level) {
            case MUST:
                offlineProxy(activeProxyIp, DropReason.IP_SERVER_UNAVAILABLE, userRecorder);
                return;
            case STRONG:
                if (!cachedProxies.isEmpty()) {
                    offlineProxy(activeProxyIp, DropReason.IP_QUALITY_BAD, userRecorder);
                    return;
                }
                break;
            case SUGGEST:
                if (cachedProxies.size() > runtimeIpSource.getPoolSize().value / 3) {
                    offlineProxy(activeProxyIp, DropReason.IP_IDLE_POOL_OVERFLOW, userRecorder);
                    return;
                }
                break;
        }
        runtimeIpSource.recordComposedEvent(userRecorder, () -> "do not offline proxy finally");
    }

    private void offlineProxy(ActiveProxyIp activeProxyIp, DropReason dropReason, Recorder userRecorder) {
        if (activeProxyIp.getActiveStatus() != ActiveProxyIp.ActiveStatus.ONLINE) {
            return;
        }
        workThread.post(() -> {
            if (activeProxyIp.getActiveStatus() != ActiveProxyIp.ActiveStatus.ONLINE) {
                return;
            }
            DownloadProxyIp remove = cachedProxies.remove(activeProxyIp.getDownloadProxyIp().getResourceId());
            runtimeIpSource.recordComposedEvent(userRecorder, () -> "cache existed: " + (remove != null));

            removeIfEq(activeProxyIp, ActiveProxyIp::getSeq, poolWithCreateSequence);
            removeIfEq(activeProxyIp, ActiveProxyIp::getMurHash, poolWithMurHash);
//            removeIfEq(activeProxyIp, ActiveProxyIp::getGeoHashCode, poolWithGeoLocation);
//            removeIfEq(activeProxyIp, ActiveProxyIp::getAdminCode, poolWithAdmin);

            activeProxyIp.destroy(dropReason);

            if (poolWithMurHash.size() > runtimeIpSource.getPoolSize().value) {
                return;
            }
            DownloadProxyIp newProxy = detachCacheCachedProxy();
            if (newProxy == null) {
                runtimeIpSource.recordComposedEvent(userRecorder, () -> "no cache proxy resource exist, pool size decreased!!");
                return;
            }

            runtimeIpSource.recordComposedEvent(userRecorder, () -> "online new proxy: ");
            runtimeIpSource.recordComposedMosaicEvent(userRecorder, () -> JSONObject.toJSONString(newProxy));

            onlineProxyResource(newProxy);
        }, true);
        // IP下线代码需要立即执行，保证其他任务不会使用到被下线了的ip，特别是ip重试的时候
    }

    private <T> void removeIfEq(ActiveProxyIp activeProxyIp, Function<ActiveProxyIp, T> kSupplier, Map<T, ActiveProxyIp> map) {
        T k = kSupplier.apply(activeProxyIp);
        if (k == null) {
            return;
        }
        ActiveProxyIp remove = map.remove(k);
        if (remove == null) {
            return;
        }
        if (remove != activeProxyIp) {
            map.put(k, remove);
        }
    }

    private DownloadProxyIp detachCacheCachedProxy() {
        Iterator<Map.Entry<String, DownloadProxyIp>> iterator = cachedProxies.entrySet().iterator();
        DownloadProxyIp newProxy = null;
        if (iterator.hasNext()) {
            Map.Entry<String, DownloadProxyIp> firstEntry = iterator.next();
            newProxy = firstEntry.getValue();
            iterator.remove();
        }
        return newProxy;
    }

    public void offerProxy(DownloadProxyIp downloadProxyIp) {
        if (downloadProxyIp.getExpireTime() != null
                && downloadProxyIp.getExpireTime() < System.currentTimeMillis()
        ) {
            // 加入的时候已经过期了
            recorder.recordEvent("offer an expired proxy item");
            return;
        }
        workThread.post(() -> {
            if (duplicate(downloadProxyIp)) {
                return;
            }

            Integer configPoolSize = runtimeIpSource.getPoolSize().value;
            if (poolWithMurHash.size() < configPoolSize) {
                onlineProxyResource(downloadProxyIp);
                return;
            }

            cachedProxies.put(downloadProxyIp.getResourceId(), downloadProxyIp);

            // 当缓存的ip资源比较大，超过了ip池的一半，那么我们直接插入替换
            // 最早加入的，当前没有为业务服务的资源，并把他替换到二级资源池
            if (cachedProxies.size() > Math.max(configPoolSize * 0.5, 1)) {
                ActiveProxyIp toReplaceActiveProxyIp = null;
                int count = 0;
                for (ActiveProxyIp activeProxyIp : poolWithCreateSequence.values()) {
                    if (activeProxyIp.isIdle()) {
                        toReplaceActiveProxyIp = activeProxyIp;
                        break;
                    }
                    count++;
                    if (count > 50) {
                        break;
                    }
                }
                if (toReplaceActiveProxyIp != null) {
                    recorder.recordEvent("proxy cache overflow , offline old proxy");
                    offlineProxy(toReplaceActiveProxyIp, DropReason.IP_IDLE_POOL_OVERFLOW, Recorder.nop);
                }
            }
        });
    }

    private boolean duplicate(DownloadProxyIp downloadProxyIp) {
        if (poolWithMurHash.containsKey(ConsistentHashUtil.murHash(downloadProxyIp.getResourceId()))) {
            return true;
        }
        return cachedProxies.containsKey(downloadProxyIp.getResourceId());
    }

    private void onlineProxyResource(DownloadProxyIp downloadProxyIp) {
        long lifeTime;
        if (downloadProxyIp.getExpireTime() != null) {
            lifeTime = downloadProxyIp.getExpireTime() - System.currentTimeMillis();
        } else {
            Integer maxAlive = runtimeIpSource.getMaxAlive().value;
            if (maxAlive == null) {
                maxAlive = 300;
            }
            lifeTime = maxAlive * 1000L;
        }
        if (lifeTime <= 0) {
            return;
        }


        ActiveProxyIp activeProxyIp = new ActiveProxyIp(this, downloadProxyIp, outboundInc.incrementAndGet());

        // register in multiple function ip pool
        poolWithMurHash.put(activeProxyIp.getMurHash(), activeProxyIp);
        poolWithCreateSequence.put(activeProxyIp.getSeq(), activeProxyIp);

        // 该ip配置了有效时间，且代理系统无法感知ip失效，
        // 故手动完成ip资源的下线
        WeakReference<ActiveProxyIp> weakReference = new WeakReference<>(activeProxyIp);
        workThread.postDelay(() -> {
            // 可能已经提前下线了，所以这里用软引用
            ActiveProxyIp proxyNotGc = weakReference.get();
            if (proxyNotGc != null) {
                offlineProxy(proxyNotGc, DropReason.IP_ALIVE_TIME_REACHED, Recorder.nop);
            }
        }, lifeTime);
    }

    public void makeCache() {
        LinkedList<ActiveProxyIp> outbounds = new LinkedList<>(poolWithMurHash.values());
        for (ActiveProxyIp outbound : outbounds) {
            outbound.makeCache();
        }
    }

    public void destroy() {
        poolWithMurHash.values().forEach(activeProxyIp -> activeProxyIp.destroy(DropReason.IP_RESOURCE_CLOSE));
    }
}
