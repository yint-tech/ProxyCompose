package cn.iinti.proxycompose.utils;

import cn.iinti.proxycompose.resource.IpAndPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.apache.commons.lang3.math.NumberUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static io.netty.buffer.ByteBufUtil.appendPrettyHexDump;
import static io.netty.util.internal.StringUtil.NEWLINE;

public class NettyUtil {

    public static String throwableMsg(Throwable throwable) {
        String msg = throwable.getClass().getName() + ":" + throwable.getMessage();
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace.length > 0) {
            msg += "\n" + stackTrace[0].toString();
        }
        return msg;
    }

    public static void loveOther(Channel girl, Channel boy) {
        girl.closeFuture().addListener((ChannelFutureListener) future -> boy.close());
        boy.closeFuture().addListener((ChannelFutureListener) future -> girl.close());
    }


    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    public static void closeOnFlush(Channel channel) {
        if (channel.isActive()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public static void closeIfActive(Channel channel) {
        if (channel == null) {
            return;
        }
        channel.close();
    }

    public static void closeAll(Collection<Channel> channels) {
        if (channels == null) {
            return;
        }
        for (Channel channel : channels) {
            closeIfActive(channel);
        }
    }

    public static void httpResponseText(Channel httpChannel, HttpResponseStatus status, String body) {
        DefaultFullHttpResponse response;
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ByteBuf content = Unpooled.copiedBuffer(bytes);
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=utf-8");
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        }
        HttpUtil.setKeepAlive(response, false);
        httpChannel.writeAndFlush(response)
                .addListener(ChannelFutureListener.CLOSE);
    }

    public static void addVia(HttpMessage httpMessage, String alias) {
        String newViaHeader = String.valueOf(httpMessage.protocolVersion().majorVersion()) +
                '.' +
                httpMessage.protocolVersion().minorVersion() +
                ' ' +
                alias;

        List<String> vias;
        if (httpMessage.headers().contains(HttpHeaderNames.VIA)) {
            List<String> existingViaHeaders = httpMessage.headers().getAll(HttpHeaderNames.VIA);
            vias = new ArrayList<>(existingViaHeaders);
            vias.add(newViaHeader);
        } else {
            vias = Collections.singletonList(newViaHeader);
        }

        httpMessage.headers().set(HttpHeaderNames.VIA, vias);
    }

    public static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, DefaultFullHttpResponse res) {
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }



    /**
     * 打印ByteBuf的好工具
     */
    public static String formatByteBuf(ChannelHandlerContext ctx, String eventName, ByteBuf msg) {
        String chStr = ctx == null ? "debug" : ctx.channel().toString();
        if (msg == null) {
            msg = Unpooled.EMPTY_BUFFER;
        }
        int length = msg.readableBytes();
        if (length == 0) {
            return chStr + ' ' + eventName + ": 0B";
        } else {
            int outputLength = chStr.length() + 1 + eventName.length() + 2 + 10 + 1;

            int rows = length / 16 + (length % 15 == 0 ? 0 : 1) + 4;
            int hexDumpLength = 2 + rows * 80;
            outputLength += hexDumpLength;

            StringBuilder buf = new StringBuilder(outputLength);
            buf.append(chStr).append(' ').append(eventName).append(": ").append(length).append('B');

            buf.append(NEWLINE);
            appendPrettyHexDump(buf, msg);
            return buf.toString();
        }
    }


    public static IpAndPort parseProxyTarget(HttpRequest httpRequest,boolean isHttps) {
        String host = httpRequest.headers().get(HttpHeaderNames.HOST);
        if (host == null && isHttps) {
            host = httpRequest.uri();
        }

        if (host == null) {
            return null;
        }

        if (!host.contains(":")) {
            host += isHttps ? ":443" : ":80";
        }

        String[] split = host.split(":");

        int port = NumberUtils.toInt(split[1].trim(), -1);
        boolean illegal = port > 0 && port <= 65535;

        if (illegal) {
            return new IpAndPort(split[0], port);
        }
        return null;
    }
}
