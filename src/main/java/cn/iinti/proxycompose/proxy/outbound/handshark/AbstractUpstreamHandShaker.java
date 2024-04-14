package cn.iinti.proxycompose.proxy.outbound.handshark;


import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import cn.iinti.proxycompose.proxy.outbound.IpPool;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import io.netty.channel.Channel;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractUpstreamHandShaker {
    protected Session session;
    protected Channel outboundChannel;
    private final ValueCallback<Boolean> callback;
    protected final ActiveProxyIp activeProxyIp;

    protected SubscribeRecorders.SubscribeRecorder recorder;
    private final AtomicBoolean hasEmitResult = new AtomicBoolean(false);

    /**
     * @param session  对应隧道
     * @param callback 回掉函数，请注意他包裹一个Boolean对象，表示是否可以failover
     */
    public AbstractUpstreamHandShaker(Session session, Channel outboundChannel, ActiveProxyIp activeProxyIp, ValueCallback<Boolean> callback) {
        this.session = session;
        this.activeProxyIp = activeProxyIp;
        this.callback = value -> {
            if (!value.isSuccess()) {
                // 握手失败的链接，统一计数为失败
                outboundChannel.close();
            }
            callback.onReceiveValue(value);
        };
        this.recorder = session.getRecorder();
        this.outboundChannel = outboundChannel;
        setupHandSharkTimeout();

    }

    private void setupHandSharkTimeout() {
        // 看追踪日志，部分请求出现在发送了connect之后没有回应，所以这里我们做一次检测
        // 设定一定timeout之后，主动阻断请求，然后执行failover
        // 猜想出现这个的愿意如下：
        // 1. 代理服务器bug，导致代理服务器接受了connect但是不给响应
        // 2. 代理服务器在我们发送connect之后发生了重播，或者发生了线路无法通畅连接的问题
        // 3. 代理服务器到真实服务器的线路bug，或代理服务器本身代理socket创建过程timeout过长，或者代理服务器连接真实服务器失败了，但是代理服务器没有较好的处理这个问题
        //
        // 实际测试发现这个值普遍在2秒左右，我们默认值设置为5s
        // 请注意，这个逻辑是必要的，这是因为如果我们不设置超时。上游ip资源不关闭链接，将会导致链接泄漏，影响GC同时消耗FD资源
        // 第一代的malenia系统socks代理缺失了time-out检查，导致出现了200w FD资源无法回收的情况
        Integer connectTimeout = Settings.global.handleSharkConnectionTimeout.value;
        // min:1s max: 60s default: 5s
        if (connectTimeout == null || connectTimeout > 60 * 1000) {
            connectTimeout = 5000;
        }
        if (connectTimeout < 1000) {
            connectTimeout = 1000;
        }
        recorder.recordEvent("this connection timeout is: " + connectTimeout);
        WeakReference<AbstractUpstreamHandShaker> ref = new WeakReference<>(this);
        outboundChannel.eventLoop().schedule(() -> {
            AbstractUpstreamHandShaker upstreamHandShaker = ref.get();
            if (upstreamHandShaker == null) {
                return;
            }
            if (upstreamHandShaker.hasEmitResult.get()) {
                return;
            }
            upstreamHandShaker.recorder.recordEvent(() -> "timeout");
            upstreamHandShaker.emitFailed("upstream hand shark timeout, abort this connection", true);
        }, connectTimeout, TimeUnit.MILLISECONDS);

    }

    protected void emitSuccess() {
        if (hasEmitResult.compareAndSet(false, true)) {
            ValueCallback.success(callback, true);
        }
    }

    private static final Set<String> notOfflineProxyMsgs = new HashSet<String>() {
        {
            add("NETWORK_UNREACHABLE");
        }
    };

    protected void emitFailed(Object msg, boolean canRetry) {
        if (hasEmitResult.compareAndSet(false, true)) {
            ValueCallback.Value<Boolean> value;
            if (msg instanceof Throwable) {
                value = ValueCallback.Value.failed((Throwable) msg);
            } else {
                value = ValueCallback.Value.failed(msg.toString());
            }
            value.v = canRetry;
            String errorMsg = value.e.getMessage();
            recorder.recordEvent(() -> "HandShark failed", value.e);
            try {
                callback.onReceiveValue(value);
            } finally {
                boolean needOfflineProxy = true;
                for (String notOfflineProxyMsg : notOfflineProxyMsgs) {
                    if (errorMsg.contains(notOfflineProxyMsg)) {
                        // java.lang.RuntimeException: cmd failed: NETWORK_UNREACHABLE
                        // 这个时候其实就是目标服务器连不上，和代理服务器没有关系
                        needOfflineProxy = false;
                        break;
                    }
                }
                if (needOfflineProxy) {
                    // 代理云ip过期之后，可能允许连接，但是鉴权失败，这是我们建议建议下线ip
                    ActiveProxyIp.offlineBindingProxy(
                            outboundChannel,
                            IpPool.OfflineLevel.SUGGEST,
                            recorder);
                }
            }


        }
    }


    public abstract void doHandShark();
}
