package cn.iinti.proxycompose.utils;

import cn.iinti.proxycompose.resource.ProxyIp;
import com.google.common.base.Splitter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.net.*;
import java.util.Enumeration;
import java.util.List;

public class IpUtils {

    public static ProxyIp fromIpPort(String ip, int port) {
        ProxyIp proxyIp = new ProxyIp();
        proxyIp.setProxyHost(ip);
        proxyIp.setProxyPort(port);
        return proxyIp;
    }


    public static String check(ProxyIp proxyIp) {
        String ip = proxyIp.getProxyHost();
        if (StringUtils.isBlank(ip)) {
            return "ip can not empty";
        }
        ip = ip.trim();
        proxyIp.setProxyHost(ip);
        if (!isIpV4(ip)) {
            try {
                InetAddress byName = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                return "error domain:" + e.getMessage();
            }
        }

        Integer port = proxyIp.getProxyPort();
        if (port == null || port <= 0 || port > 65535) {
            return "port range error";
        }

        return null;
    }

    private static final Splitter dotSplitter = Splitter.on('.');

    public static boolean isIpV4(String input) {
        if (StringUtils.isBlank(input)) {
            return false;
        }
        // 3 * 4 + 3 = 15
        // 1 * 4 + 3 = 7
        if (input.length() > 15 || input.length() < 7) {
            return false;
        }

        List<String> split = dotSplitter.splitToList(input);
        if (split.size() != 4) {
            return false;
        }
        for (String segment : split) {
            int i = NumberUtils.toInt(segment, -1);
            if (i < 0 || i > 255) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidIp(String ip) {
        return StringUtils.isNotBlank(ip) && isIpV4(ip);
    }


    public static String fetchIp(String type) throws SocketException {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            if (networkInterface.isLoopback()) {
                continue;
            }

            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();
                if (inetAddress instanceof Inet6Address) {
                    continue;
                }
                Inet4Address inet4Address = (Inet4Address) inetAddress;
                byte[] address = inet4Address.getAddress();
                if (address.length != 4) {
                    continue;
                }
                int firstByte = address[0] & 0xFF;
                boolean isPrivate = (firstByte == 192 || firstByte == 10 || firstByte == 172);
                if (type.equals("private")) {
                    if (isPrivate) {
                        return inet4Address.getHostAddress();
                    }
                } else {
                    if (!isPrivate) {
                        return inet4Address.getHostAddress();
                    }
                }
            }
        }
        return null;
    }
}
