package cn.iinti.proxycompose.proxy.switcher;


import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.proxy.ProxyCompose;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import cn.iinti.proxycompose.proxy.outbound.OutboundOperator;
import cn.iinti.proxycompose.proxy.outbound.handshark.AbstractUpstreamHandShaker;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.proxy.outbound.handshark.ProtocolManager;
import cn.iinti.proxycompose.utils.ConsistentHashUtil;
import cn.iinti.proxycompose.loop.ParallelExecutor;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.channel.Channel;

import java.util.List;

/**
 * 这是本系统最核心的心脏部位，他决定了整个ip池的上下游连接选择过程
 */
public class OutboundConnectTask implements ActiveProxyIp.ActivityProxyIpBindObserver {
    private final Session session;
    private final UpstreamHandSharkCallback callback;
    private final Recorder recorder;
    // 失败重试次数
    public int failoverCount = 0;
    private String resolvedIpSource = "unknown";

    public static void startConnectOutbound(Session session, UpstreamHandSharkCallback callback) {
        new OutboundConnectTask(session, callback).doStart();
    }

    private OutboundConnectTask(Session session, UpstreamHandSharkCallback callback) {
        this.session = session;
        this.recorder = session.getRecorder();
        this.callback = new EatErrorFilter(callback, recorder);
    }

    private void doStart() {
        proxyForward(null);
    }


    private void proxyForward(Throwable throwable) {
        if (failoverCount >= Settings.global.maxFailoverCount.value) {
            recorder.recordEvent(() -> "failoverCount count: " + failoverCount + " maxCount:" + Settings.global.maxFailoverCount.value);
            if (throwable == null) {
                throwable = new RuntimeException("get upstream failed");
            }
            callback.onHandSharkError(throwable);
            return;
        }
        failoverCount++;

        ValueCallback<Channel> channelCallback = makeChannelFinishedEvent();

        long sessionHash = session.getSessionHash();
        ProxyCompose proxyCompose = session.getProxyServer().getProxyCompose();

        if (failoverCount == 1) {
            // 如果是第一次尝试创建连接，则使用单并发，并且尝试记录曾经的隧道规则
            // 尽可能保证隧道映射不变
            proxyCompose.fetchCachedSession(session, value -> {
                if (value.isSuccess()) {
                    recorder.recordEvent(() -> "this request session hold, reuse cached ip");
                    value.v.borrowConnect(recorder, "CachedIp", this, channelCallback);
                } else {
                    recorder.recordEvent(() -> "fist choose ip resource");
                    OutboundOperator.connectToOutbound(session, sessionHash, "first create-> ", this, channelCallback);
                }
            });
            return;
        }
        // 否则使用并发的方式，连续创建三个连接，并且记忆成功的隧道
        recorder.recordEvent(() -> "retry index: " + failoverCount);
        failoverConnect(channelCallback, sessionHash, proxyCompose);
    }

    @Override
    public void onBind(ActiveProxyIp activeProxyIp) {
        resolvedIpSource = activeProxyIp.getIpPool()
                .getRuntimeIpSource().getName();
    }


    private static class FailoverTaskHolder {
        final long hash;
        final Channel channel;
        final int index;
        final String tag;

        public FailoverTaskHolder(long hash, int index, Channel value, String tag) {
            this.hash = hash;
            this.index = index;
            this.channel = value;
            this.tag = tag;
        }
    }


    private class FailoverMsgEvent implements ParallelExecutor.ParallelConnectEvent<FailoverTaskHolder> {
        private final ValueCallback<Channel> channelCallback;

        public FailoverMsgEvent(ValueCallback<Channel> channelCallback) {
            this.channelCallback = channelCallback;
        }

        @Override
        public void firstSuccess(ValueCallback.Value<FailoverTaskHolder> value) {
            FailoverTaskHolder holder = value.v;
            recorder.recordEvent(holder.tag + "create connection success :");
            recorder.recordMosaicMsgIfSubscribeRecorder(() -> holder.tag + holder.channel);
            ValueCallback.success(channelCallback, holder.channel);
        }

        @Override
        public void secondSuccess(ValueCallback.Value<FailoverTaskHolder> value) {
            FailoverTaskHolder holder = value.v;
            recorder.recordEvent(() -> holder.tag + "create channel secondSuccess, restore cache channel");
            ActiveProxyIp.restoreCache(holder.channel);
        }

        @Override
        public void finalFailed(Throwable throwable) {
            recorder.recordEvent(() -> "all ip create connection failed", throwable);
            ValueCallback.failed(channelCallback, throwable);
        }
    }

    private static final int parallelSize = 3;

    private void failoverConnect(ValueCallback<Channel> channelCallback, long sessionHash, ProxyCompose proxyCompose) {
        // 不是第一次，那么同时使用多个资源创建ip，谁成功以谁为准
        ParallelExecutor<FailoverTaskHolder> executor = new ParallelExecutor<>(proxyCompose.getComposeWorkThead(), parallelSize,
                new FailoverMsgEvent(channelCallback));

        for (int i = 0; i < parallelSize; i++) {
            long newHash = ConsistentHashUtil.murHash(String.valueOf(sessionHash) + i + failoverCount);
            String tag = Math.abs(newHash) + "_" + i + " -> ";
            recorder.recordEvent(() -> tag + "parallel create connection: ");
            int index = i;
            OutboundOperator.connectToOutbound(session, newHash, tag, this,
                    value -> {
                        if (value.isSuccess()) {
                            executor.onReceiveValue(
                                    ValueCallback.Value.success(new FailoverTaskHolder(newHash, index, value.v, tag))
                            );
                        } else {
                            executor.onReceiveValue(value.errorTransfer());
                        }
                    }
            );
        }
    }

    private ValueCallback<Channel> makeChannelFinishedEvent() {
        return channelValue -> {
            if (!channelValue.isSuccess()) {
                // retry
                proxyForward(channelValue.e);
                return;
            }
            Channel channel = channelValue.v;

            // 成功之后，和上游ip进行鉴权
            ActiveProxyIp activeProxyIp = ActiveProxyIp.getBinding(channel);
            List<Protocol> supportProtocolList = activeProxyIp.getIpPool().getRuntimeIpSource().getSupportProtocolList();

            Protocol outboundProtocol = ProtocolManager.chooseUpstreamProtocol(session.getInboundProtocol(),
                    supportProtocolList);

            if (outboundProtocol == null) {
                callback.onHandSharkError(new RuntimeException("no support protocol from upstream"));
                channel.close();
                return;
            }

            AbstractUpstreamHandShaker handShaker = ProtocolManager.createHandShaker(
                    outboundProtocol, session, channel, activeProxyIp,
                    value -> {
                        if (value.isSuccess()) {
                            recorder.recordEvent(() -> "HandShark success");
                            callback.onHandSharkFinished(channel, outboundProtocol);

                            session.getProxyServer().getProxyCompose().markSessionUse(session, activeProxyIp);
                            return;
                        }
                        channel.close();
                        if (value.v) {
                            // 可以被重试，那么走重试逻辑
                            proxyForward(value.e);
                            return;
                        }
                        callback.onHandSharkError(value.e);
                    });

            recorder.recordMosaicMsgIfSubscribeRecorder(() -> "begin HandShark with channel: " + channel);
            handShaker.doHandShark();
        };
    }
}
