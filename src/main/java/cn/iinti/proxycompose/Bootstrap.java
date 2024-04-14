package cn.iinti.proxycompose;


import cn.iinti.proxycompose.proxy.ProxyCompose;
import cn.iinti.proxycompose.proxy.RuntimeIpSource;
import cn.iinti.proxycompose.utils.PortSpaceParser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Bootstrap {
    private static final String IntMessage =
            "welcome use ProxyComposed framework, for more support please visit our website: https://iinti.cn/";

    static {
        URL configURL = Bootstrap.class.getClassLoader().getResource("config.ini");
        if (configURL != null && configURL.getProtocol().equals("file")) {
            File classPathDir = new File(configURL.getFile()).getParentFile();
            String absolutePath = classPathDir.getAbsolutePath();
            if (absolutePath.endsWith("target/classes") || absolutePath.endsWith("conf")) {
                System.setProperty("LOG_DIR", new File(classPathDir.getParentFile(), "logs").getAbsolutePath());
            }
        }
    }

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
        System.out.println(IntMessage);
    }
}
