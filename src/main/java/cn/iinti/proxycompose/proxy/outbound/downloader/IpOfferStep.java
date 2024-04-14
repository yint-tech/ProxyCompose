package cn.iinti.proxycompose.proxy.outbound.downloader;

import cn.iinti.proxycompose.resource.ProxyIp;
import cn.iinti.proxycompose.loop.Looper;

import java.util.List;
import java.util.function.Consumer;

public class IpOfferStep {
    private static final int offerBatch = 10;
    private int nowIndex;
    private final Looper workThread;
    private final long offerStepInMills;
    private final List<ProxyIp> downloadIps;
    private final Consumer<DownloadProxyIp> offerAction;

    public IpOfferStep(Looper workThread, long offerStepInMills, List<ProxyIp> downloadIps,
                       Consumer<DownloadProxyIp> offerAction) {
        this.workThread = workThread;
        this.offerStepInMills = offerStepInMills;
        this.downloadIps = downloadIps;
        this.offerAction = offerAction;
        this.nowIndex = 0;
    }

    void execute() {
        int maxIndex = downloadIps.size() - 1;
        for (int i = 0; i < offerBatch; i++) {
            ProxyIp proxyIp = downloadIps.get(this.nowIndex).resolveId();
            offerAction.accept(new DownloadProxyIp(proxyIp));
            this.nowIndex++;
            if (this.nowIndex > maxIndex) {
                return;
            }
        }
        workThread.postDelay(this::execute, offerStepInMills * offerBatch);
    }
}
