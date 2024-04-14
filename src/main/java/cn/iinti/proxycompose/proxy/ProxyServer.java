package cn.iinti.proxycompose.proxy;

import cn.iinti.proxycompose.proxy.inbound.detector.HttpProxyMatcher;
import cn.iinti.proxycompose.proxy.inbound.detector.HttpsProxyMatcher;
import cn.iinti.proxycompose.proxy.inbound.detector.ProtocolDetector;
import cn.iinti.proxycompose.proxy.inbound.detector.Socks5ProxyMatcher;
import cn.iinti.proxycompose.proxy.inbound.handlers.ProxyHttpHandler;
import cn.iinti.proxycompose.proxy.inbound.handlers.ProxySocks5Handler;
import cn.iinti.proxycompose.utils.NettyThreadPools;
import cn.iinti.proxycompose.utils.NettyUtil;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;



/**
 * 描述一个代理服务器
 */
@Slf4j
public class ProxyServer {
    public static final byte[] UN_SUPPORT_PROTOCOL_MSG = "unknown protocol".getBytes();

    @Getter
    public int port;


    private Channel serverChannel;

    @Getter
    private final ProxyCompose proxyCompose;

    public ProxyServer(int port, ProxyCompose proxyCompose) {
        this.port = port;
        this.proxyCompose = proxyCompose;
        startProxy(buildProxyServerConfig(), 20);
    }

    private void startProxy(ServerBootstrap serverBootstrap, int leftRetry) {
        if (leftRetry < 0) {
            log.error("the proxy server start failed finally!!:{}", port);
            return;
        }
        serverBootstrap.bind(port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("proxy server start success: {}", port);
                serverChannel = future.channel();
                return;
            }
            log.info("proxy server start failed, will be retry after 5s", future.cause());
            future.channel().eventLoop().schedule(() -> startProxy(
                    serverBootstrap, leftRetry - 1), 5, TimeUnit.SECONDS
            );
        });
    }

    public boolean enable() {
        return serverChannel != null && serverChannel.isActive();
    }


    private ServerBootstrap buildProxyServerConfig() {
        return new ServerBootstrap()
                .group(NettyThreadPools.proxyServerBossGroup, NettyThreadPools.proxyServerWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Session session = Session.touch(ch, ProxyServer.this);
                        ch.pipeline().addLast(
                                buildProtocolDetector(session.getRecorder())
                        );
                        setupProtocolTimeoutCheck(ch, session.getRecorder());
                    }
                });
    }

    private static void setupProtocolTimeoutCheck(Channel channel, Recorder recorder) {
        WeakReference<Channel> ref = new WeakReference<>(channel);
        EventLoop eventLoop = channel.eventLoop();

        eventLoop.schedule(() ->
                        doHandlerTimeoutCheck(ref, ProtocolDetector.class, recorder, "protocol detect timeout, close this channel"),
                5, TimeUnit.SECONDS);

        eventLoop.schedule(() -> {
            doHandlerTimeoutCheck(ref, ProxyHttpHandler.class, recorder, "http proxy init timeout");
            doHandlerTimeoutCheck(ref, ProxySocks5Handler.class, recorder, "socks5 proxy init timeout");
        }, 2, TimeUnit.MINUTES);

    }

    private static <T extends ChannelHandler> void doHandlerTimeoutCheck(
            WeakReference<Channel> ref, Class<T> handlerClazz, Recorder recorder, String msgHit
    ) {
        Channel ch = ref.get();
        if (ch == null) {
            return;
        }
        T handler = ch.pipeline().get(handlerClazz);
        if (handler != null) {
            recorder.recordEvent(msgHit);
            ch.close();
        }
    }

    public static ProtocolDetector buildProtocolDetector(Recorder recorder) {
        return new ProtocolDetector(
                recorder,
                (ctx, buf) -> {
                    recorder.recordEvent("unsupported protocol:" + NettyUtil.formatByteBuf(ctx, "detect", buf));
                    buf.release();
                    ctx.channel().writeAndFlush(Unpooled.wrappedBuffer(UN_SUPPORT_PROTOCOL_MSG))
                            .addListener(ChannelFutureListener.CLOSE);
                },
                new HttpProxyMatcher(),
                new HttpsProxyMatcher(),
                new Socks5ProxyMatcher()
        );
    }

    public void destroy() {
        NettyUtil.closeIfActive(serverChannel);
    }
}
