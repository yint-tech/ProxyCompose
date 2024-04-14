package cn.iinti.proxycompose.proxy.outbound.handshark;

import cn.iinti.proxycompose.resource.IpAndPort;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import cn.iinti.proxycompose.utils.IpUtils;
import cn.iinti.proxycompose.loop.ValueCallback;
import com.google.common.collect.Lists;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socks.*;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

public class HandShakerSocks5 extends AbstractUpstreamHandShaker {
    private final ChannelPipeline pipeline;

    /**
     * @param turning  对应隧道
     * @param callback 回掉函数，请注意他包裹一个Boolean对象，表示是否可以failover
     */
    public HandShakerSocks5(Session turning, Channel outboundChannel, ActiveProxyIp activeProxyIp, ValueCallback<Boolean> callback) {
        super(turning, outboundChannel, activeProxyIp, callback);
        pipeline = outboundChannel.pipeline();
    }

    @Override
    public void doHandShark() {
        recorder.recordEvent(() -> "begin socks5 hand shark");

        pipeline.addLast(new SocksMessageEncoder());
        List<SocksAuthScheme> socksAuthSchemes = Lists.newLinkedList();
        socksAuthSchemes.add(SocksAuthScheme.NO_AUTH);
        if (activeProxyIp.canUserPassAuth()) {
            socksAuthSchemes.add(SocksAuthScheme.AUTH_PASSWORD);
        }
        SocksInitRequest socksInitRequest = new SocksInitRequest(socksAuthSchemes);
        recorder.recordEvent(() -> "write socksInitRequest");

        outboundChannel.writeAndFlush(socksInitRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        recorder.recordEvent(() -> "write socksInitRequest failed");
                        emitFailed(future.cause(), true);
                        return;
                    }
                    recorder.recordEvent(() -> "setup socks InitResponse handler");
                    pipeline.addFirst(new SocksInitResponseDecoder());
                    pipeline.addLast(new SocksResponseHandler());
                });
    }


    private void doConnectToUpstream() {
        // use proxy switch ,this means we just need support ipv4 & tcp
        IpAndPort ipAndPort = session.getConnectTarget();

        SocksCmdRequest socksCmdRequest = new SocksCmdRequest(SocksCmdType.CONNECT,
                IpUtils.isIpV4(ipAndPort.getIp()) ? SocksAddressType.IPv4 : SocksAddressType.DOMAIN,
                ipAndPort.getIp(), ipAndPort.getPort());

        recorder.recordEvent(() -> "send cmd request to upstream");
        pipeline.addFirst(new SocksCmdResponseDecoder());
        outboundChannel.writeAndFlush(socksCmdRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        recorder.recordEvent(() -> "write upstream socksCmdRequest failed");
                        emitFailed(future.cause(), true);
                    } else {
                        recorder.recordEvent(() -> "write upstream socksCmdRequest finished");
                        // now waiting cmd response
                    }
                });
    }

    private void doAuth() {
        if (!activeProxyIp.canUserPassAuth()) {
            emitFailed("the upstream server need auth,but no auth configured for this ip source : "
                    + activeProxyIp.getIpPool().getRuntimeIpSource().getName(), false);
            return;
        }
        recorder.recordMosaicMsg(() -> "send socks5 auth");
        pipeline.addFirst(new SocksAuthResponseDecoder());
        SocksAuthRequest socksAuthRequest = new SocksAuthRequest(
                activeProxyIp.getDownloadProxyIp().getUserName(),
                activeProxyIp.getDownloadProxyIp().getPassword()
        );
        outboundChannel.writeAndFlush(socksAuthRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        emitFailed(future.cause(), true);
                        return;
                    }
                    recorder.recordEvent(() -> "auth request send finish");
                });
    }

    private void handleInitResponse(SocksInitResponse response) {
        SocksAuthScheme socksAuthScheme = response.authScheme();
        switch (socksAuthScheme) {
            case NO_AUTH:
                doConnectToUpstream();
                break;
            case AUTH_PASSWORD:
                doAuth();
                break;
            default:
                emitFailed("no support auth method: " + socksAuthScheme, false);
        }
    }

    private void handleAuthResponse(SocksAuthResponse response) {
        recorder.recordEvent(() -> "socks5 handShark authResponse: " + response.authStatus());
        if (response.authStatus() != SocksAuthStatus.SUCCESS) {
            emitFailed("upstream auth failed", false);
            return;
        }
        doConnectToUpstream();
    }

    private void handleCMDResponse(SocksCmdResponse response) {
        recorder.recordEvent(() -> "upstream CMD Response: " + response);
        if (response.cmdStatus() != SocksCmdStatus.SUCCESS) {
            recorder.recordEvent(() -> "cmd failed: " + response.cmdStatus());
            emitFailed("cmd failed: " + response.cmdStatus(), true);
            return;
        }
        pipeline.remove(SocksMessageEncoder.class);
        pipeline.remove(SocksResponseHandler.class);
        recorder.recordEvent(() -> "upstream HandShark success finally");
        emitSuccess();
    }

    private void handleUpstreamSocksResponse(SocksResponse msg) {
        switch (msg.responseType()) {
            case INIT:
                handleInitResponse((SocksInitResponse) msg);
                break;
            case AUTH:
                handleAuthResponse((SocksAuthResponse) msg);
                break;
            case CMD:
                handleCMDResponse((SocksCmdResponse) msg);
                break;
            default:
                recorder.recordEvent(() -> "unknown socksResponse: " + msg);
                emitFailed("unknown socksResponse:" + msg, false);
        }
    }

    private class SocksResponseHandler extends SimpleChannelInboundHandler<SocksResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SocksResponse msg) throws Exception {
            handleUpstreamSocksResponse(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            try {
                emitFailed(cause, true);
            } finally {
                ReferenceCountUtil.release(cause);
            }
        }
    }
}
