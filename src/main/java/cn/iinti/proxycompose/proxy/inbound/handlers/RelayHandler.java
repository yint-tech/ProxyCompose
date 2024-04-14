package cn.iinti.proxycompose.proxy.inbound.handlers;


import cn.iinti.proxycompose.trace.Recorder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public final class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel nextChannel;
    private final String TAG;

    private final Recorder recorder;

    public RelayHandler(Channel relayChannel, String tag, Recorder recorder) {
        this.nextChannel = relayChannel;
        this.TAG = tag;
        this.recorder = recorder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        recorder.recordEvent("channel channelInactive");
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        recorder.recordEvent(TAG + ": receive message: " + msg);
        if (nextChannel.isActive()) {
            nextChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        recorder.recordEvent(() -> TAG + ": replay exception", cause);
        if (nextChannel.isActive()) {
            nextChannel.write(Unpooled.EMPTY_BUFFER)
                    .addListener(future -> nextChannel.close());
        } else {
            ctx.close();
        }

    }
}

