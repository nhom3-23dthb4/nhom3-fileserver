package vn.edu.fs.server;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** Tao SSLContext TLS 1.3 cho server (va client - D dung lai). */
public final class TlsConfig {
    private TlsConfig() {}

    public static final String[] PROTOCOLS = {"TLSv1.3"};

    public static SSLContext serverContext(Path keystore, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ks = load(keystore, password);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Client tin cay dung chung chi trong truststore (KHONG dung TrustManager "tin tat ca"). */
    public static SSLContext clientContext(Path truststore, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ts = load(truststore, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore load(Path file, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) { ks.load(in, password); }
        return ks;
    }
}
