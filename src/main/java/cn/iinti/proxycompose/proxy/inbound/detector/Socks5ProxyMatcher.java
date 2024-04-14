package cn.iinti.proxycompose.proxy.inbound.detector;


import cn.iinti.proxycompose.proxy.inbound.handlers.ProxySocks5Handler;
import cn.iinti.proxycompose.trace.Recorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socks.SocksInitRequestDecoder;
import io.netty.handler.codec.socks.SocksMessageEncoder;

/**
 * Matcher for socks5 proxy protocol
 */
public class Socks5ProxyMatcher extends ProtocolMatcher {


    @Override
    public int match(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return PENDING;
        }
        byte first = buf.getByte(buf.readerIndex());
        byte second = buf.getByte(buf.readerIndex() + 1);
        if (first == 5) {
            return MATCH;
        }
        return MISMATCH;
    }


    @Override
    protected void handleMatched(Recorder recorder, ChannelHandlerContext ctx) {
        ctx.pipeline().addLast(
                new SocksInitRequestDecoder(),
                new SocksMessageEncoder(),
                new ProxySocks5Handler()
        );
    }

}
