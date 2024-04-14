package cn.iinti.proxycompose.proxy.outbound.handshark;

import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

/**
 * 完成https和上级代理的鉴权和握手
 */
public class HandShakerHttps extends AbstractUpstreamHandShaker {
    private boolean hasSendAuthentication = false;


    /**
     * @param session  对应隧道
     * @param callback 回掉函数，请注意他包裹一个Boolean对象，表示是否可以failover
     */
    public HandShakerHttps(Session session, Channel outboundChannel, ActiveProxyIp activeProxyIp, ValueCallback<Boolean> callback) {
        super(session, outboundChannel, activeProxyIp, callback);
    }

    @Override
    public void doHandShark() {
        recorder.recordEvent(() -> "begin https upstream hand shark");

        ChannelPipeline pipeline = outboundChannel.pipeline();
        pipeline.addFirst(new HttpRequestEncoder());
        pipeline.addFirst(new HttpResponseDecoder());

        DefaultFullHttpRequest connectRequest = createConnectRequest();
        outboundChannel.writeAndFlush(connectRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        recorder.recordEvent(() -> "write https connect request failed");
                        emitFailed("write https connect request failed", true);
                        return;
                    }
                    recorder.recordEvent(() -> "request write finish ,setupResponseHandler");
                    outboundChannel.pipeline().addLast(new SimpleChannelInboundHandler<HttpResponse>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, HttpResponse msg) {
                            handleResponse(msg, this);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            // 发connect报文过程发现对方关闭了链接，这个时候可以重试
                            recorder.recordEvent(() -> "exception: ", cause);
                            emitFailed(cause, true);
                        }
                    });
                });

    }


    private void handleResponse(HttpResponse httpResponse, SimpleChannelInboundHandler<HttpResponse> mHandler) {
        int code = httpResponse.status().code();
        recorder.recordEvent(() -> "ConnectHttpResponse:" + code);
        if (code == HttpResponseStatus.OK.code()) {
            recorder.recordEvent(() -> "hand shark success, remove upstream http netty handler");
            ChannelPipeline pipeline = outboundChannel.pipeline();
            pipeline.remove(HttpRequestEncoder.class);
            pipeline.remove(HttpResponseDecoder.class);
            pipeline.remove(mHandler);
            emitSuccess();
            return;
        }

        if (code >= 500) {
            recorder.recordEvent(() -> "receive 5xx from upstream" + httpResponse);
            emitFailed(httpResponse.status().reasonPhrase(), true);
            return;
        }

        if (code == 401) {
            // 401 芝麻代理 节点失效错误,这是在瞎搞
            recorder.recordEvent(() -> "401 on  hand shark");
            emitFailed(httpResponse.status().reasonPhrase(), true);
            return;
        }
        if (code != HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code()) {
            recorder.recordEvent(() -> "not 407 response,hand shark failed");
            emitFailed(httpResponse.status().reasonPhrase(), false);
            return;
        }

        if (hasSendAuthentication) {
            recorder.recordEvent(() -> "upstream password error");
            emitFailed("upstream password error", false);
            return;
        }

        // 有部分代理服务器首次发送了代理鉴权内容但是他不认，依然返回407
        // 然后有一些网络库一旦首次发送过密码，即使407也不会重新发送带鉴权请求，而是直接报告失败
        // 所以我们这里屏蔽这个问题，如果407我们再发送一次报文.
        recorder.recordEvent(() -> "407 response, send user pass again");
        DefaultFullHttpRequest connectRequest = createConnectRequest();

        outboundChannel.writeAndFlush(connectRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        recorder.recordEvent(() -> "write https connect request failed");
                        emitFailed("write https connect request failed", true);
                        return;
                    }
                    recorder.recordEvent(() -> "second auth request write finish");
                    hasSendAuthentication = true;
                });
    }


    private DefaultFullHttpRequest createConnectRequest() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.CONNECT, session.getConnectTarget().getIpPort());

        String header = activeProxyIp.buildHttpAuthenticationInfo();

        if (header == null) {
            recorder.recordEvent(() -> "this ipSource do not need authentication");
        } else {
            request.headers().add(HttpHeaderNames.PROXY_AUTHORIZATION, header);
            recorder.recordMosaicMsg(() -> "fill authorizationContent: " + header);
        }
        return request;
    }


}
