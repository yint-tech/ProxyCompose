package cn.iinti.proxycompose.resource;

import cn.iinti.proxycompose.utils.IpUtils;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.TreeSet;

/**
 * 通用ip格式解析器，同时支持IpPortPlain和PortSpace
 */
public class SmartParser implements IpResourceParser {

    public static SmartParser instance = new SmartParser();

    public static void main(String[] args) {
        instance.parse("182.244.169.248:57114\n" +
                        "113.128.31.3:57114\n" +
                        "36.102.173.123:57114\n" +
                        "182.38.126.190:57114")
                .forEach(System.out::println);
        System.out.println("-------");
        instance.parse("haproxy1.dailiyun.com:20000-20200,haproxy2.dailiyun.com:20200-20500")
                .forEach(System.out::println);
    }

    private static final Splitter smartSplitter = Splitter.on(CharMatcher.anyOf("\n,")).omitEmptyStrings().trimResults();
    private static final Splitter portSplitter = Splitter.on(':').omitEmptyStrings().trimResults();

    @Override
    public List<ProxyIp> parse(String responseText) {
        TreeSet<ProxyIp> treeSet = new TreeSet<>();
        smartSplitter.split(responseText)
                .forEach(pair -> {
                    List<String> ipAndPortSpace = portSplitter.splitToList(pair);
                    if (ipAndPortSpace.size() != 2) {
                        return;
                    }
                    String ip = ipAndPortSpace.get(0);
                    String portSpace = ipAndPortSpace.get(1);
                    if (portSpace.contains("-")) {
                        fillSpace(ip, portSpace, treeSet);
                    } else {
                        treeSet.add(IpUtils.fromIpPort(ip, Integer.parseInt(portSpace)));
                    }
                });
        return Lists.newArrayList(treeSet);
    }

    private static void fillSpace(String ip, String portSpace, TreeSet<ProxyIp> treeSet) {
        int index = portSpace.indexOf("-");
        String startStr = portSpace.substring(0, index);
        String endStr = portSpace.substring(index + 1);
        int start = Integer.parseInt(startStr);
        int end = Integer.parseInt(endStr);
        for (int i = start; i <= end; i++) {
            treeSet.add(IpUtils.fromIpPort(ip, i));
        }
    }

    private SmartParser() {
    }
}
