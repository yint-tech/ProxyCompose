package cn.iinti.proxycompose.utils;

import cn.iinti.proxycompose.loop.ValueCallback;
import cn.iinti.proxycompose.trace.Recorder;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.proxy.ProxyServer;

import java.nio.charset.StandardCharsets;

import static org.asynchttpclient.Dsl.asyncHttpClient;

/**
 * 请注意，为了避免日志刷屏，本模块强制要求使用recorder记录日志
 * 另外由于他是异步的，也要求使用recorder
 */
public class AsyncHttpInvoker {
    public static final AsyncHttpClient httpclient = asyncHttpClient(
            new DefaultAsyncHttpClientConfig.Builder()
                    .setKeepAlive(true)
                    .setConnectTimeout(10000)
                    .setReadTimeout(8000)
                    .setPooledConnectionIdleTimeout(20000)
                    .setEventLoopGroup(NettyThreadPools.asyncHttpWorkGroup)
                    .build());



    public static void get(String url, Recorder recorder, ValueCallback<String> callback) {
        get(url, recorder, null, callback);
    }

    public static void get(String url, Recorder recorder, ProxyServer.Builder proxyBuilder, ValueCallback<String> callback) {
        recorder.recordEvent(() -> "begin async invoker: " + url);
        BoundRequestBuilder getRequest = httpclient.prepareGet(url);
        if (proxyBuilder != null) {
            getRequest.setProxyServer(proxyBuilder);
        }

        execute(getRequest, recorder, callback);
    }

    private static void execute(BoundRequestBuilder requestBuilder, Recorder recorder, ValueCallback<String> callback) {
        requestBuilder.execute().toCompletableFuture()
                .whenCompleteAsync((response, throwable) -> {
                    if (throwable != null) {
                        ValueCallback.failed(callback, throwable);
                        return;
                    }
                    try {
                        String responseBody = response.getResponseBody(StandardCharsets.UTF_8).trim();
                        recorder.recordEvent(() -> "async response:" + responseBody);
                        ValueCallback.success(callback, responseBody);
                    } catch (Exception e) {
                        recorder.recordEvent(() -> "read response failed", e);
                        ValueCallback.failed(callback, e);
                    }
                });
    }
}
