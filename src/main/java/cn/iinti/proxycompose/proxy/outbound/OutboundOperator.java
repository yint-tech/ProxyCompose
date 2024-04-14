package cn.iinti.proxycompose.proxy.outbound;

import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.utils.NettyThreadPools;
import cn.iinti.proxycompose.proxy.Session;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;

public class OutboundOperator {
    private static final Bootstrap outboundBootstrap = buildOutboundBootstrap();

    private static Bootstrap buildOutboundBootstrap() {
        return new Bootstrap()
                .group(NettyThreadPools.outboundGroup)
                .channelFactory(NioSocketChannel::new)
                //上游链接在不确定协议的时候，无法确定处理器，我们使用一个插桩替代，在获取链接成功之后我们再手动构造
                .handler(new StubHandler())
                // 到代理ip的连接时间，设置短一些，设置为5s，如果不成功那么通过其他代理重试
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
    }

    @ChannelHandler.Sharable
    public static class StubHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) {
            // just make netty framework happy
            ctx.pipeline().remove(this);
        }
    }

    public static void connectToServer(String host, int port, ValueCallback<Channel> callback) {
        // 这里切换线程池的原因是，connect过程会有socket warming up,实践看来他在有些时候会有一定时间的延迟
        outboundBootstrap.config().group().execute(() -> outboundBootstrap.connect(
                host, port
        ).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                ValueCallback.failed(callback, future.cause());
            } else {
                ValueCallback.success(callback, future.channel());
            }
        }));
    }

    public static void connectToOutbound(
            Session session, long sessionHash, String tag,
            ActiveProxyIp.ActivityProxyIpBindObserver observer,
            ValueCallback<Channel> callback) {
        session.getProxyServer().getProxyCompose()
                .connectToOutbound(session, sessionHash, tag, observer, callback);
    }
}
