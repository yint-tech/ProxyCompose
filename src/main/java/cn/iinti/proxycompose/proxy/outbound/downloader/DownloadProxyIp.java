package cn.iinti.proxycompose.proxy.outbound.downloader;

import cn.iinti.proxycompose.resource.ProxyIp;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

import javax.annotation.Nullable;

@Setter
@Getter
public class DownloadProxyIp {

    /**
     * 测试耗时
     */
    @Nullable
    private Long testCost;

    /**
     * 加入队列时间
     */
    private long enQueueTime;


    @Delegate
    private final ProxyIp proxyIp;

    public DownloadProxyIp(ProxyIp proxyIp) {
        this.proxyIp = proxyIp;
    }

    @Override
    public String toString() {
        return "DownloadProxyIp{" +
                "testCost=" + testCost +
                ", enQueueTime=" + enQueueTime +
                ", proxyIp=" + JSONObject.toJSONString(proxyIp) +
                '}';
    }
}
