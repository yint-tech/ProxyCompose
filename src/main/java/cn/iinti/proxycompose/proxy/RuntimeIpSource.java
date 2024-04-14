package cn.iinti.proxycompose.proxy;

import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.resource.IpResourceParser;
import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.proxy.outbound.IpPool;
import cn.iinti.proxycompose.proxy.outbound.downloader.IpDownloader;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.trace.Recorder;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import lombok.Getter;
import lombok.experimental.Delegate;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

public class RuntimeIpSource {
    @Delegate
    private final Settings.IpSource ipSource;

    /**
     * 代理IP池，真正存储IP资源，以及缓存的可用tcp连接
     */
    @Getter
    private final IpPool ipPool;

    /**
     * 从代理源加载ip资源的下载器
     */
    private final IpDownloader ipDownloader;

    /**
     * 绑定ip池的线程，使用单线程模型完成资源分发
     */
    @Getter
    private final Looper looper;

    @Getter
    private final Recorder recorder;
    /**
     * 代理资源文本解析器
     */
    @Getter
    private final IpResourceParser resourceParser;

    public RuntimeIpSource(Settings.IpSource ipSource) {
        this.ipSource = ipSource;
        this.supportProtocolList = Collections.unmodifiableList(parseSupportProtocol(ipSource.supportProtocol.value));
        String sourceKey = ipSource.name;
        String beanId = "IpSource-" + sourceKey;

        looper = new Looper(beanId).startLoop();
        recorder = SubscribeRecorders.IP_SOURCE.acquireRecorder(beanId, Settings.global.debug.value, sourceKey);
        ipPool = new IpPool(RuntimeIpSource.this, looper, recorder, sourceKey);
        ipDownloader = new IpDownloader(RuntimeIpSource.this, recorder, sourceKey);
        resourceParser = IpResourceParser.resolve(ipSource.resourceFormat.value);

        validCheck();

        // 这里会启动下载任务，所以最后执行
        looper.scheduleWithRate(RuntimeIpSource.this::scheduleIpDownload,
                ipSource.reloadInterval.value * 1000);

        looper.scheduleWithRate(RuntimeIpSource.this::scheduleMakeConnCache,
                ipSource.makeConnInterval.value * 1000);

        looper.postDelay(RuntimeIpSource.this::scheduleIpDownload, 500);
    }

    void validCheck() {
        //todo
    }

    /**
     * 本IP池支持的代理协议：http/https/socks5
     */
    @Getter
    private final List<Protocol> supportProtocolList;


    public boolean needAuth() {
        return StringUtils.isNoneBlank(
                ipSource.getUpstreamAuthUser().value,
                ipSource.getUpstreamAuthPassword().value
        );
    }

    private static final Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();

    public static List<Protocol> parseSupportProtocol(String config) {
        List<Protocol> protocols = Lists.newArrayList();
        for (String protocolStr : splitter.split(config)) {
            Protocol protocol = Protocol.get(protocolStr);
            if (protocol == null) {
                throw new IllegalArgumentException("error support protocol config:" + protocolStr);
            }
            protocols.add(protocol);
        }
        protocols.sort((o1, o2) -> Integer.compare(o2.getPriority(), o1.getPriority()));
        return protocols;
    }

    public void recordComposedEvent(Recorder userTraceRecorder, Recorder.MessageGetter messageGetter) {
        userTraceRecorder.recordEvent(messageGetter);
        recorder.recordEvent(messageGetter);
    }

    public void recordComposedMosaicEvent(Recorder userTraceRecorder, Recorder.MessageGetter messageGetter) {
        userTraceRecorder.recordMosaicMsgIfSubscribeRecorder(messageGetter);
        recorder.recordEvent(messageGetter);
    }


    private void scheduleIpDownload() {
        looper.checkLooper();
        recorder.recordEvent("begin download ip");
        ipDownloader.downloadIp();
    }

    public void scheduleMakeConnCache() {
        ipPool.makeCache();
    }

    public boolean poolEmpty() {
        return getIpPool().poolEmpty();
    }
    public double healthScore() {
        return getIpPool().healthScore();
    }

    public void destroy() {
        looper.execute(() -> {
            ipPool.destroy();
            looper.postDelay(looper::close, 30_000);
        });
    }


}
