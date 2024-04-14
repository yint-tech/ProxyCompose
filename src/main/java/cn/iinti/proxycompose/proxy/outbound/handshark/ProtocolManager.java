package cn.iinti.proxycompose.proxy.outbound.handshark;

import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.proxy.Session;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import io.netty.channel.Channel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProtocolManager {

    private static final Map<Protocol, UpstreamHandShakerFactory> upstreamHandShakerMap = new HashMap<Protocol, UpstreamHandShakerFactory>() {
        {
            put(Protocol.HTTP, (turning, outboundChannel, activeProxyIp, callback) ->
                    new AbstractUpstreamHandShaker(turning, outboundChannel, activeProxyIp, callback) {
                        @Override
                        public void doHandShark() {
                            // 请注意，不允许直接在这里操作callback，因为handshake需要统一管理他
                            emitSuccess();
                        }
                    });
            put(Protocol.HTTPS, HandShakerHttps::new);
            put(Protocol.SOCKS5, HandShakerSocks5::new);
        }
    };

    private interface UpstreamHandShakerFactory {
        AbstractUpstreamHandShaker create(Session session, Channel outboundChannel, ActiveProxyIp activeProxyIp, ValueCallback<Boolean> callback);
    }

    public static AbstractUpstreamHandShaker createHandShaker(Protocol protocol, Session turning, Channel outboundChannel,
                                                              ActiveProxyIp activeProxyIp, ValueCallback<Boolean> callback) {
        if (protocol != null) {
            UpstreamHandShakerFactory factory = upstreamHandShakerMap.get(protocol);
            if (factory != null) {
                return factory.create(turning, outboundChannel, activeProxyIp, callback);
            }
        }
        return new AbstractUpstreamHandShaker(turning, outboundChannel, activeProxyIp, callback) {
            @Override
            public void doHandShark() {
                ValueCallback.failed(callback, "unknown protocol: " + protocol);
            }
        };
    }


    public static Protocol chooseUpstreamProtocol(Protocol inboundProtocol, List<Protocol> supportList) {
        if (inboundProtocol == Protocol.HTTP) {
            // 特殊逻辑，如果是http，我们不让上游代理走http，而是尽量走socks
            // 在代理失败判定过程，纯http的判定需要侵入到http协议报文中感知，并且可能因为上游代理服务器的实现导致存在可能的误判
            // 这是因为http的鉴权、连接建立、业务请求发送是来自同一个请求流程。我们可以考虑实现http侵入感知，但是这会带来巨大的系统开销和编程难度
            // 并且在上游代理系统不标准实现的情况下，我们可能存在对代理质量的误判
            for (Protocol candidateProtocol : supportList) {
                if (candidateProtocol == Protocol.HTTP) {
                    continue;
                }
                for (Protocol protocol : candidateProtocol.getSupportOverlayProtocol()) {
                    if (protocol == inboundProtocol) {
                        return candidateProtocol;
                    }
                }
            }
        }

        for (Protocol support : supportList) {
            // 首先使用原生的上下游协议
            if (support == inboundProtocol) {
                return support;
            }
        }

        for (Protocol candidateProtocol : supportList) {
            for (Protocol protocol : candidateProtocol.getSupportOverlayProtocol()) {
                if (protocol == inboundProtocol) {
                    return candidateProtocol;
                }
            }
        }
        return null;
    }


}
