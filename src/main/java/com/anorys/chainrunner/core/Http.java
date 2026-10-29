package com.anorys.chainrunner.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Executors;

/** The JDK HTTP client, as every call uses it: system proxy, redirects, and optionally no TLS check. */
public final class Http {

    private Http() {
    }

    public static HttpClient client(Duration connectTimeout, boolean insecureTls) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                // Platform threads: virtual threads crash the Windows native image.
                .executor(Executors.newCachedThreadPool(Thread.ofPlatform().name("chainrunner-http-", 1).daemon().factory()));
        if (insecureTls) {
            builder.sslContext(trustAll());
        }
        return builder.build();
    }

    /** For test servers with self-signed certificates only: {@code options.insecureTls: true}. */
    private static SSLContext trustAll() {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
