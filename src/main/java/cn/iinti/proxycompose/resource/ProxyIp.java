package cn.iinti.proxycompose.resource;

import cn.iinti.proxycompose.utils.IpUtils;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * 一个真实的代理ip资源
 *
 * @see IpUtils#check(ProxyIp)
 */
@Data
public class ProxyIp implements Comparable<ProxyIp> {

    private String proxyHost;


    private Integer proxyPort;


    // 以下为扩展内容,他不是必须的，用以应对各种特殊的ip供应商
    // (如芝麻代理，他ip有有效期，但是过期不拒绝，反而在http层返回错误内容，导致框架无法感知)

    /**
     * 鉴权用户名称，一般应该使用整个ipsource的鉴权
     */
    private String userName;
    /**
     * 鉴权用户密码，一般应该使用整个ipSource的鉴权
     */
    private String password;

    /**
     * 该ip资源下线时间戳，时区使用东八区
     */
    private Long expireTime;

    /**
     * 出口ip：如果您关闭了ip连通性检查，则证明您的ip供应具备非常高的质量，
     * 此时您应该主动提供出口ip资源（即在ResourceHandler中手动设置本字段）
     * 请注意基于国家/城市的分发、基于经纬度的距离分发两种特性均强依赖出口ip解析。
     * 如果最终缺失本字段，则会导致上诉两种算法策略不生效
     */
    @Nullable
    private String outIp;

    /**
     * 资源唯一ID，可选项，如扩展为空，则默认填充为-> ip:port
     */
    private String resourceId;


    @Override
    public int compareTo(@Nonnull ProxyIp o) {
        int i = Comparator.comparing(ProxyIp::getProxyHost).compare(this, o);
        if (i == 0) {
            i = proxyPort.compareTo(o.proxyPort);
        }
        return i;
    }

    private String getIpPort() {
        return proxyHost + ":" + proxyPort;
    }

    @Override
    public String toString() {
        return getIpPort();
    }

    /**
     * this method will be call by framework
     */
    public ProxyIp resolveId() {
        if (StringUtils.isNotBlank(resourceId)) {
            return this;
        }
        resourceId = getIpPort();
        return this;
    }

    public boolean isValid() {
        return StringUtils.isNotBlank(proxyHost) && proxyPort != null
                && proxyPort > 0 && proxyPort < 65535;
    }
}
