package cn.iinti.proxycompose;


import cn.iinti.proxycompose.proxy.ProxyCompose;
import cn.iinti.proxycompose.proxy.RuntimeIpSource;
import cn.iinti.proxycompose.utils.PortSpaceParser;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Slf4j
public class Bootstrap {
    private static final String IntMessage =
            "welcome use ProxyComposed framework, for more support please visit our website: https://iinti.cn/";

    public static void main(String[] args) {
        List<Settings.IpSource> sourceList = Settings.ipSourceList;
        if (sourceList.isEmpty()) {
            System.err.println("no ipSource defined");
            return;
        }

        List<RuntimeIpSource> runtimeIpSources = sourceList.stream()
                .map(RuntimeIpSource::new)
                .collect(Collectors.toList());

        TreeSet<Integer> ports = PortSpaceParser.parsePortSpace(Settings.global.mappingSpace.value);
        if (ports.isEmpty()) {
            System.err.println("proxy server port not config");
            return;
        }
        new ProxyCompose(runtimeIpSources, ports);
        log.info(IntMessage);
        System.out.println(IntMessage);
    }
}
