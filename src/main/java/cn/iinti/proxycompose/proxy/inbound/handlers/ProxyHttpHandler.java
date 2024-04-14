package cn.iinti.proxycompose.proxy.inbound.handlers;


import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.resource.IpAndPort;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.proxy.switcher.OutboundConnectTask;
import cn.iinti.proxycompose.proxy.switcher.UpstreamHandSharkCallback;
import cn.iinti.proxycompose.utils.NettyUtil;
import cn.iinti.proxycompose.trace.Recorder;
import com.google.common.io.BaseEncoding;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Queue;

public class ProxyHttpHandler extends ChannelInboundHandlerAdapter
        implements UpstreamHandSharkCallback {
    protected final Recorder recorder;
    protected final boolean isHttps;

    protected ChannelHandlerContext ctx = null;
    protected HttpRequest httpRequest;

    protected Queue<HttpObject> httpObjects;

    private Session session;

    public ProxyHttpHandler(Recorder recorder, boolean isHttps) {
        this.isHttps = isHttps;
        this.recorder = recorder;
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        this.ctx = ctx;
        if (!initRequest(msg)) {
            return;
        }
        afterInitRequest();
    }


    private boolean initRequest(Object msg) {
        if (msg instanceof HttpRequest) {
            // 客户端重新发起鉴权，所以之前的请求就需要清空
            releaseHttpObjects();
            httpRequest = (HttpRequest) msg;
            httpObjects = new ArrayDeque<>();
            recorder.recordEvent(() -> "http request");
            if (!isHttps) {
                httpObjects.add(httpRequest);
            }
        } else if (msg instanceof HttpObject) {
            // 会有 DefaultLastHttpContent , DefaultHttpContent 进来
            // 即使我们设置了不允许读，所以我们吧已经解析到内存的数据保存下，
            // 等到我们卸载当然handler的时候一并写到上游
            httpObjects.add((HttpObject) msg);
            recorder.recordEvent(() -> "httpObject:" + msg.getClass().getName());
            return false;
        } else {
            recorder.recordEvent(() -> "not handle http message:" + msg);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return false;
        }
        recorder.recordEvent(() ->
                "Received raw request from ip:" + ctx.channel().remoteAddress()
                        + " local:" + ctx.channel().localAddress() + ": request: "
                        + StringUtils.left(String.valueOf(httpRequest), 128));
        return true;
    }


    protected void releaseHttpObjects() {
        if (httpObjects != null) {
            for (HttpObject httpObject : httpObjects) {
                ReferenceCountUtil.release(httpObject);
            }
        }
        httpObjects = null;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (httpObjects != null) {
            HttpObject b;
            while ((b = httpObjects.poll()) != null) {
                ctx.fireChannelRead(b);
            }
        }
        ctx.flush();
        httpObjects = null;
    }

    private static final byte[] authenticationRequiredData = ("<!DOCTYPE HTML \"-//IETF//DTD HTML 2.0//EN\">\n"
            + "<html><head>\n"
            + "<title>407 Proxy Authentication Required</title>\n"
            + "</head><body>\n"
            + "<h1>Proxy Authentication Required</h1>\n"
            + "<p>This server could not verify that you\n"
            + "are authorized to access the document\n"
            + "requested.  Either you supplied the wrong\n"
            + "credentials (e.g., bad password), or your\n"
            + "browser doesn't understand how to supply\n"
            + "the credentials required.</p>\n" + "</body></html>\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final HttpResponseStatus CONNECTION_ESTABLISHED = new HttpResponseStatus(200,
            "Connection established");


    void afterInitRequest() {
        session = Session.get(ctx.channel());

        if (!prepare()) {
            return;
        }
        // 暂停读
        ctx.channel().config().setAutoRead(false);
        OutboundConnectTask.startConnectOutbound(session, this);
    }


    private boolean prepare() {
        if (!session.isAuthed()) {
            Pair<String, String> userPwd = extractUserPwd(httpRequest);
            // remove PROXY_AUTHORIZATION after extract
            httpRequest.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
            session.setAuthed(Settings.global.authRules.doAuth(userPwd.getLeft(), userPwd.getRight()));
        }

        if (!session.isAuthed()) {
            recorder.recordEvent("auth failed, write 407");
            writeAuthenticationRequired();
            return false;
        }


        IpAndPort proxyTarget = NettyUtil.parseProxyTarget(httpRequest, isHttps);
        if (proxyTarget == null) {
            recorder.recordEvent(() -> "can not parse proxy target");
            NettyUtil.httpResponseText(ctx.channel(), HttpResponseStatus.BAD_REQUEST, "can not parse proxy target");
            return false;
        }
        session.onProxyTargetResolved(proxyTarget, isHttps ? Protocol.HTTPS : Protocol.HTTP);
        return true;
    }


    private Pair<String, String> extractUserPwd(HttpRequest httpRequest) {
        List<String> values = httpRequest.headers().getAll(
                HttpHeaderNames.PROXY_AUTHORIZATION);
        if (values.isEmpty()) {
            return Pair.of(null, null);
        }
        String fullValue = values.iterator().next();

        String value = StringUtils.substringAfter(fullValue, "Basic ").trim();

        if (value.equals("*")) {
            //  Proxy-Authorization: Basic *
            //  被扫描，有客户端发过来的是*
            return Pair.of(null, null);
        }

        try {
            byte[] decodedValue = BaseEncoding.base64().decode(value);
            String decodedString = new String(decodedValue, StandardCharsets.UTF_8);
            if (!decodedString.contains(":") || decodedString.trim().equals(":")) {
                //  Proxy-Authorization: Basic *
                //  被扫描，有客户端发过来的是 *
                //  Proxy-Authorization: Basic Og==
                //  Og== <----> :
                return Pair.of(null, null);
            }

            String userName = StringUtils.substringBefore(decodedString, ":");
            String password = StringUtils.substringAfter(decodedString, ":");
            return Pair.of(userName, password);

        } catch (Exception e) {
            recorder.recordEvent(() -> "auth error for  " + HttpHeaderNames.PROXY_AUTHORIZATION + ":" + value, e);
            ctx.close();
            // recorder.recordEvent(TAG, "auth error for: " + e.getClass().getName() + ":" + e.getMessage());
            return Pair.of(null, null);
        }
    }

    private void writeAuthenticationRequired() {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED,
                Unpooled.copiedBuffer(authenticationRequiredData)
        );
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, authenticationRequiredData.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.DATE, new Date());
        response.headers().set("Proxy-Authenticate",
                "Basic realm=\"Basic\"");
        NettyUtil.sendHttpResponse(ctx, httpRequest, response);
    }

    @Override
    public void onHandSharkFinished(Channel upstreamChannel, @Nullable Protocol outboundProtocol) {
        NettyUtil.loveOther(session.getInboundChannel(), upstreamChannel);

        ctx.channel().eventLoop().execute(() -> {
            if (!session.getInboundChannel().isActive()) {
                recorder.recordEvent("session closed after handShark finished");
                return;
            }

            ChannelFuture future = isHttps ? onHttpsHandShark(upstreamChannel) :
                    onHttpHandleShark(upstreamChannel, outboundProtocol);
            future.addListener((ChannelFutureListener) setupFuture -> {
                if (!setupFuture.isSuccess()) {
                    return;
                }
                afterWriteResponse();
            });
        });

    }

    private void afterWriteResponse() {
        Channel channel = ctx.channel();
        channel.eventLoop().execute(() -> {
            if (!channel.isActive()) {
                return;
            }
            // 需要切换一下线程，否则：
            // [2024-03-08 11:07:35,281 [outbound-oOOooOoOOo-4-6]  WARN cn.iinti.malenia2.0O.OooOoOOooOoOOo.oOOoOoOo:581]  An exception was thrown by cn.iinti.malenia2.0O.OoOoOoOo.oOoOOo$$Lambda$1274/0x00000008409d6440.operationComplete()
            // java.util.NoSuchElementException: cn.iinti.malenia2.0O.OoOoOoOo.oOoOOo
            // at cn.iinti.malenia2.0O.OoOoOoOo.oOoOOo.oO(ProxyHttpHandler.java:223)
            channel.config().setAutoRead(true);
            ctx.pipeline().remove(ProxyHttpHandler.class);
        });
    }

    private void attachUpstreamAuth(Channel upstreamChannel, Protocol outboundProtocol, HttpRequest httpRequest) {
        if (outboundProtocol != Protocol.HTTP) {
            // http代理，但是上游是直连、socks5等其他协议，此时不再需要增加鉴权，否则鉴权明文会被穿透
            recorder.recordEvent("outbound not use http, not need authentication");
            return;
        }

        String httpAuthenticationHeader = ActiveProxyIp.getBinding(upstreamChannel)
                .buildHttpAuthenticationInfo();
        if (httpAuthenticationHeader == null) {
            recorder.recordEvent(() -> "this ipSource do not need authentication");
            return;
        }
        httpRequest.headers().add(HttpHeaderNames.PROXY_AUTHORIZATION, httpAuthenticationHeader);
        recorder.recordEvent(() -> "fill authorizationContent: " + httpAuthenticationHeader);

    }

    private ChannelFuture onHttpHandleShark(Channel upstreamChannel, @Nullable Protocol outboundProtocol) {
        // http 会马上回掉到这里来
        ChannelPipeline outboundPipeline = upstreamChannel.pipeline();
        outboundPipeline.addLast(new HttpClientCodec());

        // 没有脚本的时候在这里填充上游密码，有脚本的时候不能在这里填充密码
        // 因为脚本可以访问这个结构体，所以有脚本的时候，我们在需要在脚本执行之后再刷新鉴权的header
        attachUpstreamAuth(upstreamChannel, outboundProtocol, httpRequest);
        recorder.recordEvent(() -> "start http tuning");
        session.replay(upstreamChannel);
        return upstreamChannel.newSucceededFuture();
    }

    private ChannelFuture onHttpsHandShark(Channel upstreamChannel) {
        recorder.recordEvent(() -> "do https forward");
        DefaultFullHttpResponse connectEstablishResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, CONNECTION_ESTABLISHED);
        connectEstablishResponse.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        NettyUtil.addVia(connectEstablishResponse, "virjar-spider-ha-proxy");
        Queue<HttpObject> httpObjects = this.httpObjects;
        if (httpObjects != null) {
            httpObjects.clear();
        }

        ChannelPromise channelPromise = upstreamChannel.newPromise();

        session.getInboundChannel()
                .writeAndFlush(connectEstablishResponse)
                .addListener(future -> {
                    try {
                        releaseHttpObjects();
                        if (!future.isSuccess()) {
                            channelPromise.tryFailure(future.cause());
                            recorder.recordEvent(() -> "socket closed before write connect success message");
                            return;
                        }
                        ChannelPipeline inboundPipeline = session.getInboundChannel().pipeline();
                        inboundPipeline.remove(HttpServerCodec.class);
                        recorder.recordEvent(() -> "start https replay tuning");
                        session.replay(upstreamChannel);
                        channelPromise.trySuccess();
                    } catch (Throwable e) {
                        recorder.recordEvent(() -> "setup onHttpsHandShark error", e);
                        ctx.fireExceptionCaught(e);
                    }
                });

        return channelPromise;
    }


    @Override
    public void onHandSharkError(Throwable e) {
        recorder.recordEvent(() -> "onHandSharkError error", e);
        NettyUtil.httpResponseText(ctx.channel(),
                HttpResponseStatus.BAD_GATEWAY,
                "http proxy system error\n " + NettyUtil.throwableMsg(e));
    }
}
