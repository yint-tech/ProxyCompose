package cn.iinti.proxycompose.proxy.outbound.downloader;

import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.proxy.outbound.handshark.Protocol;
import cn.iinti.proxycompose.proxy.RuntimeIpSource;
import cn.iinti.proxycompose.utils.AsyncHttpInvoker;
import cn.iinti.proxycompose.resource.ProxyIp;
import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.trace.Recorder;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import cn.iinti.proxycompose.utils.IpUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.asynchttpclient.Realm;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class IpDownloader {
    private final RuntimeIpSource runtimeIpSource;
    private final Looper workThread;
    private volatile boolean isDownloading = false;
    private final Recorder recorder;


    public IpDownloader(RuntimeIpSource runtimeIpSource, Recorder recorder, String sourceKey) {
        this.runtimeIpSource = runtimeIpSource;
        // downloader里面全是异步，但是可能会调用dns服务，并且可能存在对无效的代理域名进行dns解析，
        // 这可能存在些许耗时，所以这里我新建一个线程来处理
        this.workThread = new Looper("Downloader-" + sourceKey).startLoop();
        this.recorder = recorder;
    }

    private static final String dbName = "GeoLite2-City.mmdb";


    public void downloadIp() {
        workThread.execute(() -> {
            String loadUrl = runtimeIpSource.getLoadURL().value;
            if (!isHTTPLink(loadUrl)) {
                onDownloadResponse(loadUrl);
                return;
            }

            if (isDownloading) {
                return;
            }
            isDownloading = true;

            AsyncHttpInvoker.get(loadUrl, runtimeIpSource.getRecorder(), value -> workThread.post(() -> {
                isDownloading = false;

                if (!value.isSuccess()) {
                    recorder.recordEvent(() -> "ip source download failed", value.e);
                    return;
                }
                onDownloadResponse(value.v);
            }));
        });

    }

    @SuppressWarnings("all")
    private static boolean isHTTPLink(String url) {
        return StringUtils.startsWithAny(url, "http://", "https://");
    }

    private void onDownloadResponse(String response) {
        workThread.execute(() -> {
            recorder.recordEvent(() -> "download ip response:\n" + response + "\n");
            List<ProxyIp> proxyIps = runtimeIpSource.getResourceParser()
                    .parse(response)
                    .stream()
                    .filter(proxyIp -> {
                        if (!proxyIp.isValid()) {
                            recorder.recordEvent(() -> "invalid parsed proxyIp");
                            return false;
                        }
                        return true;
                    }).collect(Collectors.toList());

            if (proxyIps.isEmpty()) {
                return;
            }
            // fill password from ip source config
            String upUserPassword = runtimeIpSource.getUpstreamAuthPassword().value;
            if (StringUtils.isNotBlank(upUserPassword)) {
                proxyIps.forEach(proxyIpResourceItem -> {
                    if (StringUtils.isBlank(proxyIpResourceItem.getPassword())) {
                        proxyIpResourceItem.setPassword(upUserPassword);
                    }
                });
            }
            String upUserName = runtimeIpSource.getUpstreamAuthUser().value;
            if (StringUtils.isNotBlank(upUserName)) {
                proxyIps.forEach(proxyIpResourceItem -> {
                    if (StringUtils.isBlank(proxyIpResourceItem.getUserName())) {
                        proxyIpResourceItem.setUserName(upUserName);
                    }
                });
            }


            Boolean needTest = runtimeIpSource.getNeedTest().value;
            recorder.recordEvent(() -> "this ip source configure test switch: " + needTest);
            Consumer<DownloadProxyIp> action = BooleanUtils.isFalse(needTest) ?
                    this::offerIpResource :
                    this::runIpQualityTest;

            // 2024年03约21日，医药魔方：
            //  公司降本增效，将malenia服务器压缩到4G内存，同时跑malenia+mysql+python服务，导致物理内存不够
            //  最终排查在这里可能并发发出几百个网络情况（1毫秒内），这导致系统极短时间内存快速分配，进而引发oom
            //  fix此问题使用如下策略：ip下载完成，进行分批延时探测入库，对此网络行为进行削峰填谷，10个ip一批并发多次进行ip质量探测
            //////////////////////////////////////////////////////////////////////////////////////////////////////////
            // step =                 ( interval * 1000    * 0.3)  /  size
            // 步长 =    (加载间隔  * 1000毫秒  * 在前30%时间内完成探测)  /  本次加载数量
            long offerStepInMills = runtimeIpSource.getReloadInterval().value * 300 / proxyIps.size();

            new IpOfferStep(workThread, offerStepInMills, proxyIps, action).execute();
        });
    }


    private void offerIpResource(DownloadProxyIp downloadProxyIp) {
        // 请注意，这里必须确保线程正确，因为InetAddress的解析可能比较耗时
        workThread.execute(() -> {
            recorder.recordEvent(() -> "prepare enpool proxy ip: " + downloadProxyIp);
            runtimeIpSource.getIpPool().offerProxy(downloadProxyIp);
        });

    }

    private void runIpQualityTest(DownloadProxyIp downloadProxyIp) {
        recorder.recordEvent(() -> "[QualityTest] begin test proxy quality: " + downloadProxyIp);
        Recorder recorderForTester = SubscribeRecorders.IP_TEST.acquireRecorder(
                downloadProxyIp.getResourceId() + "_" + System.currentTimeMillis(),
                Settings.global.debug.value, runtimeIpSource.getName()
        );
        recorderForTester.recordEvent(() -> "[QualityTest] begin to test proxy:" + downloadProxyIp);

        String url = Settings.global.proxyHttpTestURL.value;
        recorder.recordEvent(() -> "[QualityTest] ip test with url: " + url);
        long startTestTimestamp = System.currentTimeMillis();

        ProxyServer.Builder proxyBuilder = new ProxyServer.Builder(downloadProxyIp.getProxyHost(), downloadProxyIp.getProxyPort());

        if (runtimeIpSource.needAuth()) {
            proxyBuilder.setRealm(new Realm.Builder(
                    runtimeIpSource.getUpstreamAuthUser().value,
                    runtimeIpSource.getUpstreamAuthPassword().value
            ).setScheme(Realm.AuthScheme.BASIC));
        }

        if (runtimeIpSource.getSupportProtocolList().stream().anyMatch(Predicate.isEqual(Protocol.SOCKS5))) {
            // 有代理资源只能支持socks5，所以如果代理支持socks5时，直接使用s5来代理，
            // 默认将会使用http协议族，然后malenia具备自动的协议转换能力
            recorder.recordEvent(() -> "[QualityTest] use test protocol SOCKS_V5");
            proxyBuilder.setProxyType(ProxyType.SOCKS_V5);
        } else {
            recorder.recordEvent(() -> "[QualityTest] use test protocol HTTP");
        }

        AsyncHttpInvoker.get(url, recorderForTester, proxyBuilder, value -> {
            if (value.isSuccess()) {
                if (!IpUtils.isValidIp(value.v)) {
                    // 扭转成功状态，因为响应的内容不是ip，那么认为报文错误
                    value = ValueCallback.Value.failed("response not ip format: " + value.v);
                }
            }

            if (!value.isSuccess()) {
                recorder.recordEvent(() -> "[QualityTest] ip test failed", value.e);
                recorderForTester.recordEvent(() -> "ip test failed", value.e);
                return;
            }
            recorder.recordEvent(() -> "[QualityTest] ip test success");
            downloadProxyIp.setOutIp(value.v);
            downloadProxyIp.setTestCost(System.currentTimeMillis() - startTestTimestamp);
            offerIpResource(downloadProxyIp);
        });
    }
}
