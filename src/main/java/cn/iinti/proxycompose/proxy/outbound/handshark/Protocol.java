package cn.iinti.proxycompose.proxy.outbound.handshark;

import lombok.Getter;

public enum Protocol {
    HTTP(0),
    // 这里的https实际上指代connect，实践中都是作为https使用。但是实际上也是可以跑http为加密流量的
    HTTPS(1),
    SOCKS5(2);

    static {
        HTTP.supportOverlayProtocol = new Protocol[]{};
        // 理论上https也是可以支持转发到socks上面的，但是他只能转发socks的tcp部分，
        // 为了避免未来支持udp的时候出现问题，我们不配置socks流量跑到https信道上面来
        HTTPS.supportOverlayProtocol = new Protocol[]{HTTP};
        // socks4和sock5可以相互转换，如有有上游供应商只支持socks4，我们也可以提供全套的代理服务
        SOCKS5.supportOverlayProtocol = new Protocol[]{HTTP, HTTPS};
    }

    @Getter
    private Protocol[] supportOverlayProtocol;

    @Getter
    private final int priority;

    Protocol(int priority) {
        this.priority = priority;
    }

    public static Protocol get(String name) {
        for (Protocol protocol : Protocol.values()) {
            if (protocol.name().equalsIgnoreCase(name)) {
                return protocol;
            }
        }
        return null;
    }


}
