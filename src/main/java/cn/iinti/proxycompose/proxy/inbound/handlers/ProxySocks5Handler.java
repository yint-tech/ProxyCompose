package cn.iinti.proxycompose.proxy.inbound.handlers;


import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.resource.IpAndPort;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.proxy.switcher.OutboundConnectTask;
import cn.iinti.proxycompose.proxy.switcher.UpstreamHandSharkCallback;
import cn.iinti.proxycompose.utils.NettyUtil;
import cn.iinti.proxycompose.utils.IpUtils;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.channel.*;
import io.netty.handler.codec.socks.*;

import java.util.List;

public class ProxySocks5Handler extends SimpleChannelInboundHandler<SocksRequest>
        implements UpstreamHandSharkCallback {
    private ChannelHandlerContext ctx;
    private SocksCmdRequest req;
    private Session session;
    private Recorder recorder;


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SocksRequest socksRequest) {
        this.ctx = ctx;
        if (this.session == null) {
            this.session = Session.get(ctx.channel());
            this.recorder = session.getRecorder();
        }
        recorder.recordEvent(() -> "inbound handle socks request：" + socksRequest.requestType());
        switch (socksRequest.requestType()) {
            case INIT:
                handleInit((SocksInitRequest) socksRequest);
                break;
            case AUTH:
                handleAuth(ctx, (SocksAuthRequest) socksRequest);
                break;
            case CMD:
                handleCmd(ctx, socksRequest);
                break;
            case UNKNOWN:
            default:
                ctx.close();
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, SocksAuthRequest socksRequest) {
        if (session.isAuthed()) {
            ctx.pipeline().addFirst(new SocksCmdRequestDecoder());
            ctx.writeAndFlush(new SocksAuthResponse(SocksAuthStatus.SUCCESS));
            return;
        }

        String username = socksRequest.username();
        String password = socksRequest.password();
        session.setAuthed(Settings.global.authRules.doAuth(username, password));

        if (session.isAuthed()) {
            ctx.pipeline().addFirst(new SocksCmdRequestDecoder());
            ctx.writeAndFlush(new SocksAuthResponse(SocksAuthStatus.SUCCESS));
            return;
        }

        ctx.pipeline().addFirst(new SocksAuthRequestDecoder());
        ctx.writeAndFlush(new SocksAuthResponse(SocksAuthStatus.FAILURE));
    }


    private void handleInit(SocksInitRequest socksInitRequest) {
        // 对于socks代理来说，ip鉴权和密码鉴权是两个步骤，所以需要提前判定,先鉴权IP，然后鉴权密码
        if (session.isAuthed()) {
            recorder.recordEvent(() -> "has been authed");
            ctx.pipeline().addFirst(new SocksCmdRequestDecoder());
            ctx.writeAndFlush(new SocksInitResponse(SocksAuthScheme.NO_AUTH));
            return;
        }

        List<SocksAuthScheme> socksAuthSchemes = socksInitRequest.authSchemes();
        for (SocksAuthScheme socksAuthScheme : socksAuthSchemes) {
            if (socksAuthScheme == SocksAuthScheme.AUTH_PASSWORD) {
                // 如果客户端明确说明支持密码鉴权，那么要求提供密码
                ctx.pipeline().addFirst(new SocksAuthRequestDecoder());
                ctx.writeAndFlush(new SocksInitResponse(SocksAuthScheme.AUTH_PASSWORD));
                return;
            }

        }

        recorder.recordEvent(() -> "require password auth");
        ctx.pipeline().addFirst(new SocksAuthRequestDecoder());
        ctx.writeAndFlush(new SocksInitResponse(SocksAuthScheme.AUTH_PASSWORD));

    }

    private void handleCmd(ChannelHandlerContext ctx, SocksRequest socksRequest) {
        req = (SocksCmdRequest) socksRequest;
        //TODO 实现UDP转发
        if (req.cmdType() != SocksCmdType.CONNECT) {
            recorder.recordEvent(() -> "not support s5 cmd: " + req.cmdType());
            ctx.close();
            return;
        }
        session.onProxyTargetResolved(new IpAndPort(req.host(), req.port()), Protocol.SOCKS5);

        OutboundConnectTask.startConnectOutbound(session, this);
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        recorder.recordEvent(() -> "Socks5InboundHandler handler error", cause);
    }

    @Override
    public void onHandSharkFinished(Channel upstreamChannel, Protocol outboundProtocol) {
        NettyUtil.loveOther(session.getInboundChannel(), upstreamChannel);
        ctx.channel().eventLoop().execute(() -> {
            IpAndPort connectTarget = session.getConnectTarget();
            SocksCmdResponse socksCmdResponse = new SocksCmdResponse(SocksCmdStatus.SUCCESS,
                    IpUtils.isIpV4(connectTarget.getIp()) ? SocksAddressType.IPv4 : SocksAddressType.DOMAIN,
                    connectTarget.getIp(), connectTarget.getPort()
            );
            ctx.channel().writeAndFlush(socksCmdResponse)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            recorder.recordEvent(() -> "socket closed when write socks success", future.cause());
                            return;
                        }
                        ChannelPipeline pipeline = ctx.pipeline();
                        pipeline.remove(SocksMessageEncoder.class);
                        pipeline.remove(ProxySocks5Handler.class);
                        recorder.recordEvent(() -> "start socks5 replay tuning");
                        session.replay(upstreamChannel);
                    });
        });

    }

    @Override
    public void onHandSharkError(Throwable e) {
        ctx.channel().writeAndFlush(new SocksCmdResponse(SocksCmdStatus.FAILURE, req.addressType()))
                .addListener(ChannelFutureListener.CLOSE);
    }

}