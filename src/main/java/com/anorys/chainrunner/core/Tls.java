package com.anorys.chainrunner.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The certificates HTTPS trusts: Java's, and on Windows also the root certificates of Windows (the
 * {@code Windows-ROOT} store, where a company installs the certificate of its proxy). A server is
 * trusted when either accepts it.
 */
public final class Tls {

    private static volatile Trust trust;

    private Tls() {
    }

    /** The context every HTTPS client uses, unless {@code insecureTls}. */
    public static SSLContext context() {
        return trust().context;
    }

    /** {@code chainrunner --check-tls}: where the trusted certificates come from. */
    public static String describe() {
        return trust().description;
    }

    private static Trust trust() {
        Trust t = trust;
        if (t == null) {
            synchronized (Tls.class) {
                if (trust == null) {
                    trust = load(System.getProperty("os.name", ""));
                }
                t = trust;
            }
        }
        return t;
    }

    record Trust(SSLContext context, String description) {
    }

    static Trust load(String osName) {
        try {
            List<X509ExtendedTrustManager> managers = new ArrayList<>();
            X509ExtendedTrustManager java = manager(null);
            managers.add(java);
            String description = "TLS trust: Java (" + java.getAcceptedIssuers().length + " certificates)";
            if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
                try {
                    KeyStore windows = KeyStore.getInstance("Windows-ROOT");
                    windows.load(null, null);
                    managers.add(manager(windows));
                    description += " + Windows (" + windows.size() + " certificates)";
                } catch (Exception | LinkageError e) {
                    description += "; the Windows certificates cannot be read: " + e;
                }
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{managers.size() == 1 ? java : new Either(managers)}, null);
            return new Trust(context, description);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot set up TLS: " + e.getMessage(), e);
        }
    }

    private static X509ExtendedTrustManager manager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast)
                .findFirst().orElseThrow(() -> new GeneralSecurityException("no X.509 trust manager"));
    }

    /** Trusts a server that any of its managers trusts; when none does, says why the first one refused. */
    private static final class Either extends X509ExtendedTrustManager {

        private final List<X509ExtendedTrustManager> managers;

        Either(List<X509ExtendedTrustManager> managers) {
            this.managers = managers;
        }

        private interface Check {
            void run(X509ExtendedTrustManager manager) throws CertificateException;
        }

        private void any(Check check) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager manager : managers) {
                try {
                    check.run(manager);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(m -> m.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(m -> m.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(m -> m.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(m -> m.checkClientTrusted(chain, authType, engine));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(m -> m.checkClientTrusted(chain, authType, socket));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(m -> m.checkClientTrusted(chain, authType));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return managers.stream().flatMap(m -> Arrays.stream(m.getAcceptedIssuers())).toArray(X509Certificate[]::new);
        }
    }
}
