package cn.iinti.proxycompose.proxy.inbound.detector;


import cn.iinti.proxycompose.proxy.inbound.handlers.ProxyHttpHandler;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpServerCodec;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Matcher for http proxy connect tunnel.
 */
public class HttpsProxyMatcher extends ProtocolMatcher {

    @Override
    public int match(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return PENDING;
        }

        String method = buf.toString(0, 8, US_ASCII);
        if (!"CONNECT ".equalsIgnoreCase(method)) {
            return MISMATCH;
        }

        return MATCH;
    }


    @Override
    protected void handleMatched(Recorder recorder, ChannelHandlerContext ctx) {
        ctx.pipeline().addLast(
                new HttpServerCodec(),
                new ProxyHttpHandler(recorder, true)
        );
    }
}
