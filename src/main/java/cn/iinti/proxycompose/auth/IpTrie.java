package cn.iinti.proxycompose.auth;

import org.apache.commons.lang3.StringUtils;

public class IpTrie {
    private IpTrie left;
    private IpTrie right;
    private boolean has = false;
    private static final String localhostStr = "localhost";
    private static final String localhost = "127.0.0.1";


    public void insert(String ipConfig) {
        String ip;
        int cidr = 32;
        if (ipConfig.contains("/")) {
            String[] split = ipConfig.split("/");
            ip = StringUtils.trim(split[0]);
            cidr = Integer.parseInt(StringUtils.trim(split[1]));
        } else {
            ip = ipConfig.trim();
        }

        insert(0, ip2Int(ip), cidr);
    }

    private void insert(int deep, long ip, int cidr) {
        if (deep >= cidr) {
            has = true;
            return;
        }

        int bit = (int) ((ip >>> (32 - deep)) & 0x01);
        if (bit == 0) {
            if (left == null) {
                left = new IpTrie();
            }
            left.insert(deep + 1, ip, cidr);
        } else {
            if (right == null) {
                right = new IpTrie();
            }
            right.insert(deep + 1, ip, cidr);
        }
    }

    private static long ip2Int(String ip) {
        if (localhostStr.equals(ip)) {
            ip = localhost;
        }
        String[] split = ip.split("\\.");
        return ((Long.parseLong(split[0]) << 24
                | Long.parseLong(split[1]) << 16
                | Long.parseLong(split[2]) << 8
                | Long.parseLong(split[3])));
    }


    public boolean has(String ip) {
        if (ip.contains("/")) {
            // 这里可能输入了一个cidr的ip规则
            ip = ip.substring(0, ip.indexOf('/'));
        }

        return has(ip2Int(ip), 0);
    }


    private boolean has(long ip, int deep) {
        if (has) {
            return true;
        }
        int bit = (int) ((ip >>> (32 - deep)) & 0x01);
        if (bit == 0) {
            if (left == null) {
                return false;
            }
            return left.has(ip, deep + 1);
        } else {
            if (right == null) {
                return false;
            }
            return right.has(ip, deep + 1);
        }
    }

    public void remove(String ipConfig) {
        String ip;
        int cidr = 32;
        if (ipConfig.contains("/")) {
            String[] split = ipConfig.split("/");
            cidr = Integer.parseInt(StringUtils.trim(split[1]));
            ip = StringUtils.trim(split[0]);
        } else {
            ip = ipConfig.trim();
        }
        remove(0, ip2Int(ip), cidr);
    }

    private void remove(int deep, long ip, int cidr) {
        if (deep >= cidr) {
            has = false;
            return;
        }

        int bit = (int) ((ip >>> (32 - deep)) & 0x01);
        if (bit == 0) {
            if (left == null) {
                return;
            }
            left.remove(deep + 1, ip, cidr);
        } else {
            if (right == null) {
                return;
            }
            right.remove(deep + 1, ip, cidr);
        }
    }
}
