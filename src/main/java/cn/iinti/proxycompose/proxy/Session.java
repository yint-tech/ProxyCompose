package cn.iinti.proxycompose.proxy;

import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.proxy.inbound.handlers.RelayHandler;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.resource.IpAndPort;
import cn.iinti.proxycompose.utils.ConsistentHashUtil;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

public class Session {

    @Getter
    private final String sessionId = UUID.randomUUID().toString();

    @Getter
    private final SubscribeRecorders.SubscribeRecorder recorder = SubscribeRecorders.USER_SESSION
            .acquireRecorder(sessionId, Settings.global.debug.value, "default");

    @Getter
    private final Channel inboundChannel;

    @Getter
    private final ProxyServer proxyServer;

    @Getter
    @Setter
    private boolean authed = false;


    public static Session touch(Channel inboundChannel, ProxyServer proxyServer) {
        return new Session(inboundChannel, proxyServer);
    }

    private Session(Channel inboundChannel, ProxyServer proxyServer) {
        this.inboundChannel = inboundChannel;
        this.proxyServer = proxyServer;

        recorder.recordEvent("new request from: " + inboundChannel);
        attach(inboundChannel);
        inboundChannel.closeFuture().addListener(future -> recorder.recordEvent("user connection closed"));
    }

    private static final AttributeKey<Session> SESSION_ATTRIBUTE_KEY = AttributeKey.newInstance("SESSION_ATTRIBUTE_KEY");

    private void attach(Channel channel) {
        channel.attr(SESSION_ATTRIBUTE_KEY).set(this);
    }

    public static Session get(Channel channel) {
        return channel.attr(SESSION_ATTRIBUTE_KEY).get();
    }


    public void replay(Channel upstreamChannel) {
        upstreamChannel.pipeline()
                .addLast(new RelayHandler(inboundChannel, "replay-outbound:", recorder));

        inboundChannel.pipeline()
                .addLast(new RelayHandler(upstreamChannel, "replay-inbound:", recorder));
    }


    // 代理的最终目标，从代理请求中提取，可以理解为一定存在（只要是合法的代理请求一定能解析到）
    @Getter
    private IpAndPort connectTarget;

    @Getter
    private Long sessionHash;
    @Getter
    private Protocol inboundProtocol;


    public void onProxyTargetResolved(IpAndPort ipAndPort, Protocol inboundProtocol) {
        String sessionIdKey = Settings.global.randomTurning.value ?
                String.valueOf(System.currentTimeMillis()) + getProxyServer().getPort() :
                String.valueOf(getProxyServer().getPort());

        this.sessionHash = ConsistentHashUtil.murHash(sessionIdKey);

        this.connectTarget = ipAndPort;
        this.inboundProtocol = inboundProtocol;


        recorder.recordEvent(() -> "proxy target resolved ->  \nsessionHash: " + sessionHash
                + "\ntarget:" + ipAndPort
                + "\ninbound protocol: " + inboundProtocol
                + "\n"
        );
    }


}
