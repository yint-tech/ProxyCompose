package cn.iinti.proxycompose.proxy.inbound.detector;


import cn.iinti.proxycompose.trace.Recorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * Matcher for protocol.
 */
public abstract class ProtocolMatcher {

    static int MATCH = 1;
    static int MISMATCH = -1;
    static int PENDING = 0;

    /**
     * If match the protocol.
     *
     * @return 1:match, -1:not match, 0:still can not judge now
     */
    protected abstract int match(ByteBuf buf);

    /**
     * Deal with the pipeline when matched
     */
    protected void handleMatched(Recorder recorder, ChannelHandlerContext ctx) {
    }
}
