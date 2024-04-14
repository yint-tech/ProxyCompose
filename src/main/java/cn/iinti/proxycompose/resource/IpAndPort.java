package cn.iinti.proxycompose.resource;

import lombok.Getter;

@Getter
public class IpAndPort {
    private final String ip;
    private final Integer port;
    private final String ipPort;


    public IpAndPort(String ip, Integer port) {
        this.ip = ip;
        this.port = port;
        this.ipPort = ip + ":" + port;
    }

    @Override
    public String toString() {
        return ipPort;
    }
}
