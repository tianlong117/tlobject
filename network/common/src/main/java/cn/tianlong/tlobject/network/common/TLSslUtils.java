package cn.tianlong.tlobject.network.common;

import javax.net.ssl.*;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * SSL 证书工具类，替代 okhttputils 中的 HttpsUtils
 */
public class TLSslUtils {

    public static class SSLParams {
        public final SSLSocketFactory sSLSocketFactory;
        public final X509TrustManager trustManager;

        public SSLParams(SSLSocketFactory sSLSocketFactory, X509TrustManager trustManager) {
            this.sSLSocketFactory = sSLSocketFactory;
            this.trustManager = trustManager;
        }
    }

    /**
     * 从证书文件加载 SSL 参数
     * @param certificates 证书文件输入流数组
     * @return SSLParams 包含 SSLSocketFactory 和 X509TrustManager
     */
    public static SSLParams getSslSocketFactory(InputStream[] certificates) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            int index = 0;
            for (InputStream certStream : certificates) {
                if (certStream == null) continue;
                X509Certificate cert = (X509Certificate) certificateFactory.generateCertificate(certStream);
                keyStore.setCertificateEntry("alias_" + index++, cert);
                certStream.close();
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            TrustManager[] trustManagers = tmf.getTrustManagers();
            X509TrustManager trustManager = null;
            for (TrustManager tm : trustManagers) {
                if (tm instanceof X509TrustManager) {
                    trustManager = (X509TrustManager) tm;
                    break;
                }
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            return new SSLParams(sslContext.getSocketFactory(), trustManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL socket factory", e);
        }
    }
}
