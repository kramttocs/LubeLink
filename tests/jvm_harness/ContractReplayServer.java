package com.kramttocs.lubelink.core;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;

/** Local HTTPS server that answers each GET with the response captured from a disposable LubeLogger
 * (tests/contract/<version>, written by scripts/capture-contract.sh). A test can replace one path's answer
 * with another capture or a changed body, to show how the production parsers handle it.
 * Never contacts a real server. */
public final class ContractReplayServer implements AutoCloseable {
    public record Answer(int status, String contentType, byte[] body) { }
    private final Map<String, Answer> captured = new HashMap<>();
    private final Map<String, Answer> byName = new HashMap<>();
    private final Map<String, Answer> overrides = new ConcurrentHashMap<>();
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final HttpsServer server;
    public final String baseUrl;

    public ContractReplayServer(String keyStorePath, String captureDir) throws Exception {
        Path dir = Path.of(captureDir);
        List<String> rows = Files.readAllLines(dir.resolve("INDEX.tsv"), StandardCharsets.UTF_8);
        for (String row : rows.subList(1, rows.size())) {
            String[] c = row.split("\t", -1);
            String name = c[0], method = c[1], path = c[2];
            Path body = dir.resolve(name + ".json");
            if (!Files.exists(body)) continue; // the vehicle image is listed by status only
            Answer answer = new Answer(Integer.parseInt(c[4]), c[5], Files.readAllBytes(body));
            byName.put(name, answer);
            // The normal answer for a path is its successful read; error forms are served only on request.
            if (method.equals("GET") && !name.startsWith("error-") && !name.startsWith("write-"))
                captured.putIfAbsent(path, answer); // the first capture of a path (whoami: the Editor key)
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(keyStorePath))) { store.load(input, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath() +
                (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requested.add(exchange.getRequestMethod() + " " + path);
            Answer answer = exchange.getRequestMethod().equals("GET")
                ? overrides.getOrDefault(path, captured.get(path)) : null;
            if (answer == null) answer = new Answer(599, "text/plain", "not in the captured contract".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", answer.contentType());
            exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(answer.body()); }
        });
        server.start();
        baseUrl = "https://localhost:" + server.getAddress().getPort();
    }

    /** The captured body for [name] (a row of INDEX.tsv), as text. */
    public String body(String name) { return new String(answer(name).body(), StandardCharsets.UTF_8); }
    /** Answer [path] with another captured response, for example an error form. */
    public void serveCapture(String path, String name) { overrides.put(path, answer(name)); }
    /** Answer [path] with a changed copy of its captured body. */
    public void serveBody(String path, String json) {
        Answer base = captured.get(path);
        if (base == null) throw new IllegalArgumentException("No captured read for " + path);
        overrides.put(path, new Answer(base.status(), base.contentType(), json.getBytes(StandardCharsets.UTF_8)));
    }
    /** Answer [path] with any status, content type and body, for failure forms the capture cannot hold. */
    public void serveRaw(String path, int status, String contentType, String body) {
        overrides.put(path, new Answer(status, contentType, body.getBytes(StandardCharsets.UTF_8)));
    }
    public void reset() { overrides.clear(); }
    /** Every captured successful read, so a test can show each was replayed. */
    public Set<String> capturedReads() { return new TreeSet<>(captured.keySet()); }
    public Set<String> requested() { return new TreeSet<>(requested); }
    private Answer answer(String name) {
        Answer a = byName.get(name);
        if (a == null) throw new IllegalArgumentException("No capture named " + name);
        return a;
    }
    @Override public void close() { server.stop(0); }
}
