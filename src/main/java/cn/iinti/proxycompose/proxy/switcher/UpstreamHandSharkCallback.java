package cn.iinti.proxycompose.proxy.switcher;

import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import io.netty.channel.Channel;

import javax.annotation.Nullable;

public interface UpstreamHandSharkCallback {
    void onHandSharkFinished(Channel upstreamChannel, @Nullable Protocol outboundProtocol);

    void onHandSharkError(Throwable e);
}