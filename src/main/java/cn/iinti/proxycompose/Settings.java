package cn.iinti.proxycompose;

import cn.iinti.proxycompose.auth.AuthRules;
import cn.iinti.proxycompose.utils.IniConfig;
import cn.iinti.proxycompose.utils.IpUtils;
import cn.iinti.proxycompose.utils.ResourceUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.ini4j.ConfigParser;

import java.io.InputStream;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件解析和配置内容定义
 */
public class Settings {

    /**
     * 全局配置
     */
    public static Global global;
    /**
     * IP资源定义，系统至少包含1个IP资源
     */
    public static List<IpSource> ipSourceList = new ArrayList<>();

    static {
        load();
    }

    @SneakyThrows
    private static void load() {
        InputStream stream = ResourceUtil.openResource("config.ini");

        ConfigParser config = new ConfigParser();
        config.read(stream);

        global = new Global(config);

        List<String> sections = config.sections();
        for (String sectionName : sections) {
            if (sectionName.startsWith("source:")) {
                ipSourceList.add(new IpSource(config, sectionName));
            }
        }
    }

    @Getter
    public static class IpSource extends IniConfig {

        public IpSource(ConfigParser config, String section) {
            super(config, section);
            name = section.substring("source:".length());
        }

        public final String name;

        public final StringConfigValue loadURL = new StringConfigValue(
                "loadURL", "");

        /**
         * 资源格式，目前支持两种
         * plain：文本分割，如 proxy.iinti.cn:8000-9000,proxy1.iinti.cn:5817,proxy2.iinti.cn:5817
         * json: json格式，满足 {@link cn.iinti.proxycompose.resource.ProxyIp}的json对象
         */
        public final StringConfigValue resourceFormat = new StringConfigValue(
                "resourceFormat", "plain"
        );

        public final BooleanConfigValue enable = new BooleanConfigValue(
                "enable", false
        );

        public final StringConfigValue upstreamAuthUser = new StringConfigValue(
                "upstreamAuthUser", "");

        public final StringConfigValue upstreamAuthPassword = new StringConfigValue(
                "upstreamAuthPassword", "");

        public final IntegerConfigValue poolSize = new IntegerConfigValue(
                "poolSize", 0
        );

        public final BooleanConfigValue needTest = new BooleanConfigValue(
                "needTest", true
        );
        public final IntegerConfigValue reloadInterval = new IntegerConfigValue(
                "reloadInterval", 60);

        public IntegerConfigValue maxAlive = new IntegerConfigValue(
                "maxAlive", 1200
        );

        public final StringConfigValue supportProtocol = new StringConfigValue(
                "supportProtocol", "socks5");

        public final IntegerConfigValue connIdleSeconds = new IntegerConfigValue(
                "connIdleSeconds", 5);

        public final IntegerConfigValue makeConnInterval = new IntegerConfigValue(
                "makeConnInterval", 500);

        public final IntegerConfigValue ratio = new IntegerConfigValue(
                "ratio", 1
        );

    }

    public static class Global extends IniConfig {

        public BooleanConfigValue debug = new BooleanConfigValue(
                "debug", false
        );

        /**
         * 后端探测接口，探测代理ip是否可用以及解析出口ip地址
         */
        public StringConfigValue proxyHttpTestURL = new StringConfigValue(
                "proxyHttpTestURL", "https://iinti.cn/conn/getPublicIp?scene=proxy_compose");


        public StringConfigValue mappingSpace = new StringConfigValue(
                "mappingSpace", "36000-36010"
        );

        public BooleanConfigValue randomTurning = new BooleanConfigValue(
                "randomTurning", false
        );

        public BooleanConfigValue enableFloatIpSourceRatio = new BooleanConfigValue(
                "enableFloatIpSourceRatio", true
        );

        public IntegerConfigValue maxFailoverCount = new IntegerConfigValue(
                "maxFailoverCount", 3
        );

        public IntegerConfigValue handleSharkConnectionTimeout = new IntegerConfigValue(
                "handleSharkConnectionTimeout", 5_000
        );

        public final AuthRules authRules = new AuthRules();

        public String listenIp = "0.0.0.0";

        public Global(ConfigParser config) {
            super(config, "global");
            acceptConfig("auth_username", authRules::setAuthAccount);
            acceptConfig("auth_password", authRules::setAuthPassword);
            acceptConfig("auth_white_ips", s -> {
                for (String str : StringUtils.split(s, ",")) {
                    if (StringUtils.isBlank(str)) {
                        continue;
                    }
                    authRules.addCidrIpConfig(str.trim());
                }
            });
            acceptConfig("listen_type", listenType -> listenIp = parseListenType(listenType));
        }
    }

    private static String parseListenType(String listenType) {
        listenType = StringUtils.trimToEmpty(listenType);
        if (IpUtils.isIpV4(listenType)) {
            return listenType;
        }
        switch (listenType) {
            //lo,private,public,all
            case "lo":
                return "127.0.0.1";

            case "all":
                return "0.0.0.0";
            default:
                try {
                    return IpUtils.fetchIp(listenType);
                } catch (SocketException e) {
                    //ignore
                }
        }
        return "0.0.0.0";
    }
}
