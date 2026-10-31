import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Synthetic HTTPS-only transport regression. No real host, records, or credentials.
 * This checks JDK HttpURLConnection replay behavior, NOT Android's runtime transport.
 */
public class StreamingReplayProbe {
    private static final String PASS = "changeit";
    private static String header(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int last = -1, c;
        while ((c = input.read()) >= 0) {
            b.write(c);
            if (last == '\r' && c == '\n') {
                byte[] bytes = b.toByteArray();
                int n = bytes.length;
                if (n >= 4 && bytes[n-4] == '\r' && bytes[n-3] == '\n'
                    && bytes[n-2] == '\r' && bytes[n-1] == '\n') break;
            }
            last = c;
            if (b.size() > 16384) throw new IOException("Oversized synthetic request header");
        }
        return b.size() == 0 ? null : b.toString(StandardCharsets.US_ASCII);
    }
    private static void consume(BufferedInputStream in, int count) throws IOException {
        byte[] chunk = new byte[1024];
        while (count > 0) {
            int n = in.read(chunk, 0, Math.min(count, chunk.length));
            if (n < 0) throw new EOFException("Incomplete request body");
            count -= n;
        }
    }
    private static void handle(SSLSocket socket, AtomicInteger writes) {
        try (socket) {
            socket.setSoTimeout(3000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                String text = header(in);
                if (text == null) return;
                String[] lines = text.split("\r?\n");
                if (lines.length == 0) return;
                String verb = lines[0].split(" ")[0];
                int size = 0;
                for (String line : lines) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    size = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                consume(in, size);
                if (verb.equals("GET")) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                               "Content-Length: 2\r\nConnection: keep-alive\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if (verb.equals("POST")) {
                    int nth = writes.incrementAndGet();
                    if (nth == 1) return; // pretend upstream committed, then lost its reply
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                               "Content-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                } else throw new IOException("Unexpected verb: " + verb);
            }
        } catch (IOException ignored) {
            // The client is expected to observe a failed response.
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected temporary keystore path");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
            store.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASS.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        AtomicInteger writes = new AtomicInteger();
        ExecutorService pool = Executors.newCachedThreadPool(task -> {
            Thread worker = new Thread(task, "synthetic-stream-probe");
            worker.setDaemon(true);
            return worker;
        });
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0)) {
            server.setSoTimeout(300);
            int port = server.getLocalPort();
            Future<?> acceptor = pool.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
                while (System.nanoTime() < deadline) {
                    try {
                        SSLSocket incoming = (SSLSocket) server.accept();
                        pool.submit(() -> handle(incoming, writes));
                    }
                    catch (SocketTimeoutException ignored) { }
                    catch (IOException closed) { return; }
                }
            });
            String base = "https://localhost:" + port;
            HttpsURLConnection get = (HttpsURLConnection) new URL(base + "/api/vehicles").openConnection();
            get.setSSLSocketFactory(context.getSocketFactory());
            get.setReadTimeout(3000);
            try (InputStream in = get.getInputStream()) { in.readAllBytes(); }
            get.disconnect();
            HttpsURLConnection post = (HttpsURLConnection) new URL(base + "/api/vehicle/servicerecords/add?vehicleId=1").openConnection();
            post.setSSLSocketFactory(context.getSocketFactory());
            post.setRequestMethod("POST");
            post.setDoOutput(true);
            post.setReadTimeout(3000);
            post.setRequestProperty("Content-Type", "application/json");
            byte[] body = "{\"Cost\":\"12.50\"}".getBytes(StandardCharsets.UTF_8);
            post.setFixedLengthStreamingMode(body.length);
            boolean failed = false;
            try {
                try (OutputStream out = post.getOutputStream()) { out.write(body); }
                int status = post.getResponseCode();
                try (InputStream in = post.getInputStream()) { in.readAllBytes(); }
                System.out.println("Unexpected POST response: " + status);
            } catch (IOException expected) { failed = true; }
            finally { post.disconnect(); }
            Thread.sleep(200);
            if (!failed || writes.get() != 1)
                throw new AssertionError("A dropped write must be uncertain with exactly one POST. failed=" + failed + ", posts=" + writes.get());
            System.out.println("Passed local HTTPS streaming replay probe: 1 POST, lost reply surfaced as I/O failure");
            server.close();
            acceptor.cancel(true);
        } finally { pool.shutdownNow(); }
    }
}
