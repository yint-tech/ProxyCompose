package cn.iinti.proxycompose.proxy.inbound.detector;


import cn.iinti.proxycompose.utils.NettyUtil;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import static io.netty.handler.codec.ByteToMessageDecoder.MERGE_CUMULATOR;

/**
 * Switcher to distinguish different protocols
 */
@Slf4j
public class ProtocolDetector extends ChannelInboundHandlerAdapter {

    private final ByteToMessageDecoder.Cumulator cumulator = MERGE_CUMULATOR;
    private final ProtocolMatcher[] matcherList;

    private ByteBuf buf;

    private final MatchMissHandler missHandler;

    private final Recorder recorder;
    private boolean hasData = false;


    public ProtocolDetector( Recorder recorder, MatchMissHandler missHandler, ProtocolMatcher... matchers) {
        this.recorder = recorder;
        this.missHandler = missHandler;
        if (matchers.length == 0) {
            throw new IllegalArgumentException("No matcher for ProtocolDetector");
        }
        this.matcherList = matchers;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ReferenceCountUtil.release(msg);
            recorder.recordEvent("unexpected message type for ProtocolDetector: " + msg.getClass());
            NettyUtil.closeOnFlush(ctx.channel());
            return;
        }
        hasData = true;

        ByteBuf in = (ByteBuf) msg;
        if (buf == null) {
            buf = in;
        } else {
            buf = cumulator.cumulate(ctx.alloc(), buf, in);
        }
        // the buf maybe null after recorder acquire variable
        ByteBuf tmpBuf = buf;
        recorder.recordEvent(() -> "begin protocol detect with header: " + tmpBuf.readableBytes());
        boolean hasPending = false;
        for (ProtocolMatcher matcher : matcherList) {
            int match = matcher.match(buf.duplicate());
            if (match == ProtocolMatcher.MATCH) {
                recorder.recordEvent("matched by " + matcher.getClass().getName());
                matcher.handleMatched(  recorder, ctx);
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(buf);
                return;
            }

            if (match == ProtocolMatcher.PENDING) {
                recorder.recordEvent("match " + matcher.getClass() + " pending..");
                hasPending = true;
            }
        }
        if (hasPending) {
            return;
        }

        // all miss
        missHandler.onAllMatchMiss(ctx, buf);
        buf = null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (buf != null) {
            buf.release();
            buf = null;
        }
        if (!hasData && cause instanceof IOException) {
            // 有LBS负载均衡的服务，通过探测端口是否开启来判断服务是否存活，
            // 他们只开启端口，然后就会关闭隧道，此时这里就会有IOException: java.io.IOException: Connection reset by peer
            recorder.recordEvent(() -> "exception: " + cause.getClass() + " ->" + cause.getMessage() + " before any data receive");
        } else {
            recorder.recordEvent("protocol detect error", cause);
        }
        NettyUtil.closeOnFlush(ctx.channel());
    }

    public interface MatchMissHandler {
        void onAllMatchMiss(ChannelHandlerContext ctx, ByteBuf buf);
    }

}
