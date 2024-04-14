package cn.iinti.proxycompose.utils;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

public class NettyThreadPools {

    public static final NioEventLoopGroup outboundGroup = newDefaultEventLoop("outbound");

    public static final NioEventLoopGroup proxyServerBossGroup = newDefaultEventLoop("Proxy-boss-group");
    public static final NioEventLoopGroup proxyServerWorkerGroup = newDefaultEventLoop("Proxy-worker-group");

    // 下载代理，通知外部等业务http调用
    public static final NioEventLoopGroup asyncHttpWorkGroup =
            new NioEventLoopGroup(1, createThreadFactory("async-http-invoker"));
    //newDefaultEventLoop("async-http-invoker");


    private static NioEventLoopGroup newDefaultEventLoop(String name) {
        return new NioEventLoopGroup(0, createThreadFactory(name));
    }

    private static DefaultThreadFactory createThreadFactory(String name) {
        return new DefaultThreadFactory(name + "-" + DefaultThreadFactory.toPoolName(NioEventLoopGroup.class));
    }
}
