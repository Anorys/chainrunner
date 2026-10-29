package com.anorys.chainrunner.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TlsTest {

    @Test
    void trustsJavasCertificates() {
        Tls.Trust trust = Tls.load("Linux");

        assertThat(trust.description()).matches("TLS trust: Java \\(\\d+ certificates\\)");
        assertThat(trust.context().getProtocol()).isEqualTo("TLS");
    }

    @Test
    void withoutTheWindowsStoreKeepsJavasCertificatesAndSaysWhy() {
        Tls.Trust trust = Tls.load(System.getProperty("os.name"));

        if (System.getProperty("os.name").startsWith("Windows")) {
            assertThat(trust.description()).contains(" + Windows (");
        } else {
            assertThat(Tls.load("Windows 11").description())
                    .startsWith("TLS trust: Java (").contains("the Windows certificates cannot be read");
        }
    }
}
