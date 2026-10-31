package com.kramttocs.lubelink.core;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

/** Test-only HTTPS front for the disposable plain-HTTP LubeLogger container: the production clients
 * accept HTTPS only. Terminates TLS on localhost with a throwaway certificate, which it also installs
 * as the JVM's default trust so HttpsURLConnection accepts it. Bytes are relayed unchanged. */
public final class TlsProxy implements AutoCloseable {
    private final SSLServerSocket server;
    public final String baseUrl;

    public TlsProxy(String keystorePath, int backendPort) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystorePath)) { store.load(in, "changeit".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        server = (SSLServerSocket) context.getServerSocketFactory()
            .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        baseUrl = "https://localhost:" + server.getLocalPort();
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort);
                    pump(client.getInputStream(), backend.getOutputStream(), backend);
                    pump(backend.getInputStream(), client.getOutputStream(), client);
                } catch (Exception closed) { /* server closed or one connection failed */ }
            }
        }, "tls-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void pump(InputStream from, OutputStream to, Socket toClose) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[16384];
            try {
                for (int n; (n = from.read(buffer)) >= 0; ) { to.write(buffer, 0, n); to.flush(); }
            } catch (Exception ignored) { }
            try { toClose.close(); } catch (Exception ignored) { }
        }, "tls-proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    @Override public void close() throws Exception { server.close(); }
}
