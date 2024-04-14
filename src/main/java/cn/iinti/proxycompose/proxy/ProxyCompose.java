package cn.iinti.proxycompose.proxy;

import cn.iinti.proxycompose.Settings;
import cn.iinti.proxycompose.proxy.outbound.ActiveProxyIp;
import cn.iinti.proxycompose.utils.ConsistentHashUtil;
import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.trace.Recorder;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import com.alibaba.fastjson.JSONObject;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyCompose {
    /**
     * 所有的代理IP池，IP池代表malenia的上有资源池，他来自各种代理ip供应商。如代理云、芝麻代理、快代理等
     * malenia通过对IpSource的抽象，完成对所有代理供应商的透明屏蔽
     */
    private final Map<String, RuntimeIpSource> ipSources = Maps.newHashMap();

    /**
     * 本产品下的所有代理服务器
     */
    private final TreeMap<Integer, ProxyServer> proxyServerTreeMap = Maps.newTreeMap();

    private TreeMap<Long, String> ipSourceKeyMap = new TreeMap<>();
    private List<String> ipSourceKeyList = Lists.newArrayList();
    /**
     * 路由缓存，当一个隧道访问过程使用过某个ip资源，那么系统优先尝试使用曾今的隧道，如此尽可能保证ip出口不变<br>
     * ps：需要做这个缓存更重要的原因是，malenia在运作过程会有failover，failover过程会有不可预期的隧道映射关系重置
     * 此时无法根据固定的规则进行mapping计算
     */
    private final Cache<Long, ActiveProxyIp> routeCache = CacheBuilder.newBuilder()
            .removalListener((RemovalListener<Long, ActiveProxyIp>) notification -> {
                Long sessionHash = notification.getKey();
                ActiveProxyIp activeProxyIp = notification.getValue();
                // auto decrease refCount of proxyIp
                if (sessionHash != null && activeProxyIp != null) {
                    activeProxyIp.refreshRefSessionHash(sessionHash, false);
                }
            })
            .expireAfterAccess(1, TimeUnit.MINUTES).build();


    @Getter
    private final Looper composeWorkThead;

    public ProxyCompose(List<RuntimeIpSource> ipSources, Set<Integer> proxyServePorts) {
        composeWorkThead = new Looper("compose").startLoop();
        for (Integer port : proxyServePorts) {
            ProxyServer proxyServer = new ProxyServer(port, ProxyCompose.this);
            log.info("start proxy server for port: {}", port);
            proxyServerTreeMap.put(port, proxyServer);
        }

        // config ipSource
        Map<String, Integer> ipSourceWithRatio = Maps.newHashMap();
        for (RuntimeIpSource runtimeIpSource : ipSources) {
            String sourceKey = runtimeIpSource.getName();
            this.ipSources.put(sourceKey, runtimeIpSource);
            ipSourceWithRatio.put(sourceKey, runtimeIpSource.getRatio().value);
        }
        Map<String, Integer> ratioConfig = Collections.unmodifiableMap(ipSourceWithRatio);
        reloadIpSourceRatio(ratioConfig);
        composeWorkThead.scheduleWithRate(() -> reloadIpSourceRatio(ratioConfig), 300_000);
    }


    private Map<String, Integer> floatRatio(Map<String, Integer> configRule) {
        configRule = new HashMap<>(configRule);

        boolean hasVerySmallConfig = configRule.values().stream().anyMatch(it -> it.equals(1) || it.equals(2));
        if (hasVerySmallConfig) {
            // expand ratio, because the float ratio component maybe decease config
            for (String ipSourceKey : Lists.newArrayList(configRule.keySet())) {
                configRule.put(ipSourceKey, configRule.get(ipSourceKey) * 3);
            }
        }

        // scale ratio config by health score
        for (String ipSourceKey : Lists.newArrayList(configRule.keySet())) {
            //IP池健康指数，正常值为100，最大值一般不超过150，小于100认为不健康，最低为0（代表此IP资源已经完全挂了）
            RuntimeIpSource runtimeIpSource = ipSources.get(ipSourceKey);
            if (runtimeIpSource == null) {
                continue;
            }
            Double healthScore = runtimeIpSource.healthScore();

            Integer configuredRatio = configRule.get(ipSourceKey);
            configuredRatio = (int) (configuredRatio * healthScore / 100);
            if (configuredRatio <= 1) {
                configuredRatio = 1;
            }
            configRule.put(ipSourceKey, configuredRatio);
        }
        return configRule;
    }

    public void reloadIpSourceRatio(Map<String, Integer> ratio) {
        composeWorkThead.post(() -> {
            Map<String, Integer> configRule = Settings.global.enableFloatIpSourceRatio.value ?
                    floatRatio(ratio) : ratio;

            TreeMap<Long, String> newIpSources = new TreeMap<>();
            List<String> newIpSourceLists = Lists.newArrayList(new TreeSet<>(configRule.keySet()));

            for (String ipSourceKey : configRule.keySet()) {
                int ratio1 = configRule.get(ipSourceKey);
                if (ratio1 == 0) {
                    continue;
                }
                for (int i = 1; i <= ratio1; i++) {
                    long murHash = ConsistentHashUtil.murHash(ipSourceKey + "_##_" + i);
                    newIpSources.put(murHash, ipSourceKey);
                }
            }
            ipSourceKeyMap = newIpSources;
            ipSourceKeyList = newIpSourceLists;
        });
    }


    public void destroy() {
        composeWorkThead.post(() -> {
            proxyServerTreeMap.values().forEach(ProxyServer::destroy);
            composeWorkThead.close();
        });
    }

    public void connectToOutbound(
            Session session, long sessionHash, String tag,
            ActiveProxyIp.ActivityProxyIpBindObserver observer,
            ValueCallback<Channel> callback) {
        SubscribeRecorders.SubscribeRecorder recorder = session.getRecorder();

        allocateIpSource(sessionHash, tag, session, ipSourceValue -> {
            if (!ipSourceValue.isSuccess()) {
                recorder.recordEvent(() -> tag + "allocate IpSource failed", ipSourceValue.e);
                ValueCallback.failed(callback, ipSourceValue.e);
                return;
            }

            RuntimeIpSource ipSource = ipSourceValue.v;
            // 拿到IP源，此时ip源是根据分流比例控制的
            recorder.recordMosaicMsg(() -> tag + "allocate IpSource success: " + ipSource.getName());
            // 在ip源上分配代理
            ipSource.getIpPool().allocateIp(sessionHash, session.getRecorder(), ipWrapper -> {
                if (!ipWrapper.isSuccess()) {
                    recorder.recordEvent(() -> tag + "allocate IP failed");
                    ValueCallback.failed(callback, ipWrapper.e);
                    return;
                }

                ActiveProxyIp activeProxyIp = ipWrapper.v;
                recorder.recordMosaicMsg(() -> tag + "allocate IP success:" +
                        JSONObject.toJSONString(activeProxyIp.getDownloadProxyIp())
                        + " begin to borrow connection"
                );
                activeProxyIp.borrowConnect(recorder, tag, observer, callback);
            });
        });
    }

    public void allocateIpSource(long sessionHash, String tag, Session session, ValueCallback<RuntimeIpSource> valueCallback) {
        composeWorkThead.post(() -> {
            // 如果用户有指定了代理ip源，那么使用特定的代理ip
            String ipSourceKey = ConsistentHashUtil.fetchConsistentRing(ipSourceKeyMap, sessionHash);
            if (ipSourceKey == null) {
                // not happen
                valueCallback.onReceiveValue(ValueCallback.Value.failed("no ipSources mapping for "));
                return;
            }
            session.getRecorder().recordMosaicMsg(() -> tag + "map route to ipSource: " + ipSourceKey);
            allocateIpSource0(session.getRecorder(), tag, ipSourceKey, ipSourceKeyList, valueCallback);
        });
    }

    private void allocateIpSource0(Recorder recorder, String tag, String prefer, List<String> candidate,
                                   ValueCallback<RuntimeIpSource> valueCallback) {
        RuntimeIpSource runtimeIpSource = ipSources.get(prefer);

        if (runtimeIpSource == null) {
            valueCallback.onReceiveValue(ValueCallback.Value.failed("no ipSources: " + prefer + " defined"));
            return;
        }
        if (isValidIpSource(runtimeIpSource)) {
            ValueCallback.success(valueCallback, runtimeIpSource);
            return;
        }
        if (ipSources.size() > 1) {
            recorder.recordEvent(() -> tag + "IpSourceManager this ipSource not have ip resource, begin ratio to next ip source");
            int length = candidate.size();
            int start = Math.abs(prefer.hashCode()) + candidate.size();
            for (int i = 0; i < length; i++) {
                String newSourceKey = candidate.get((start + i) % candidate.size());
                if (newSourceKey.equals(prefer)) {
                    continue;
                }
                runtimeIpSource = ipSources.get(newSourceKey);
                if (isValidIpSource(runtimeIpSource)) {
                    ValueCallback.success(valueCallback, runtimeIpSource);
                    return;
                }
            }
        }

        ValueCallback.failed(valueCallback, "can not find online IpSourceKey");
    }

    public static boolean isValidIpSource(RuntimeIpSource runtimeIpSource) {
        return runtimeIpSource != null && !runtimeIpSource.poolEmpty();
    }

    public void fetchCachedSession(Session session, ValueCallback<ActiveProxyIp> callback) {
        composeWorkThead.post(() -> {
            ActiveProxyIp sessionIp = routeCache.getIfPresent(session.getSessionHash());
            if (sessionIp == null) {
                ValueCallback.failed(callback, "not exist");
                return;
            }

            if (sessionIp.getActiveStatus() != ActiveProxyIp.ActiveStatus.DESTROY) {
                ValueCallback.success(callback, sessionIp);
                return;
            }
            routeCache.invalidate(session.getSessionHash());
            ValueCallback.failed(callback, "not exist");
        });

    }

    public void markSessionUse(Session session, ActiveProxyIp activeProxyIp) {
        if (Settings.global.randomTurning.value) {
            return;
        }
        session.getRecorder().recordEvent(() -> "add sessionId route mapping ");
        activeProxyIp.refreshRefSessionHash(session.getSessionHash(), true);
        composeWorkThead.post(() -> routeCache.put(session.getSessionHash(), activeProxyIp));
    }
}