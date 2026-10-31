package com.kramttocs.lubelink.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Synthetic local HTTPS server for production Kotlin Editor/Manager client tests.
 * Does not contact a real server and never sees user credentials. */
public final class SyntheticServer implements AutoCloseable {
    public enum Mode { POST_DROP, PUT_DROP, POST_408, POST_429, PUT_408, PUT_429,
        POST_SUCCESS, POST_MISMATCH,
        // The create reply's record ID is not a whole number (15.5); record 15 is there to be found by mistake.
        POST_FRACTIONAL_ID, PUT_SUCCESS, PUT_LITERAL_NULL, NOOP, FUEL_NULL_EXTRA, FUEL_POST_SUCCESS, FUEL_PUT_SUCCESS,
        DELETE_400_ABSENT, DELETE_404_ABSENT, DELETE_400_PRESENT, DELETE_400_VERIFY_FAIL,
        DELETE_204_ABSENT, DELETE_204_PRESENT, DELETE_403_PRESENT,
        // After DELETE the record is still there, but the check answers malformed or foreign data.
        DELETE_204_VERIFY_EMPTY_OBJECT, DELETE_204_VERIFY_NUMBER, DELETE_204_VERIFY_FOREIGN, DELETE_204_VERIFY_NO_VEHICLE,
        DELETE_204_VERIFY_DUPLICATE, DELETE_404_VERIFY_EMPTY_OBJECT, DELETE_REJECTED_VERIFY_EMPTY_OBJECT,
        // Related cases: Add and Edit read-backs that are duplicated or lack a field.
        POST_READBACK_DUPLICATE, POST_READBACK_NO_NOTES, PUT_READBACK_NO_NOTES,
        // Read-backs whose value has the right printed form but the wrong JSON type.
        // The DELETE is committed and the connection closes before the reply.
        DELETE_DROP,
        POST_READBACK_NUMBER_NOTES, POST_READBACK_BOOLEAN_TAGS, PUT_READBACK_NUMBER_NOTES, FUEL_PUT_READBACK_NUMBER_STATION,
        // A blank-station Fuel Add whose read-back has no usable custom-field list, or an entry without its
        // value member; the two controls are a valid empty list and present JSON-null values.
        FUEL_POST_EXTRAS_OBJECT, FUEL_POST_EXTRAS_NULL, FUEL_POST_EXTRAS_NUMBER, FUEL_POST_EXTRAS_ABSENT,
        FUEL_POST_EXTRAS_NO_VALUE, FUEL_POST_EXTRAS_EMPTY, FUEL_POST_EXTRAS_NULL_VALUE,
        // An Edit snapshot, and an Edit read-back, with an entry that lacks its value member.
        FUEL_EDIT_NO_VALUE, FUEL_PUT_READBACK_NO_VALUE }
    private final Mode mode;
    private final SSLServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
        Thread worker = new Thread(task, "synthetic-https"); worker.setDaemon(true); return worker;
    });
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private volatile boolean present = true;
    private volatile boolean updated = false;
    private volatile String lastPostBody = "";
    private volatile String lastPutBody = "";
    private volatile boolean deleteSeen = false;
    private volatile boolean running = true;
    private final Future<?> acceptLoop;
    public final String baseUrl;
    private static final String CAR = "[{\"id\":1,\"imageLocation\":\"/defaults/noimage.png\",\"year\":2015,\"make\":\"Example\",\"model\":\"Sedan\",\"isElectric\":false,\"useHours\":false}]";
    private static final String RECORD = "{\"id\":15,\"vehicleId\":1,\"date\":\"2026-10-12\",\"odometer\":\"1200\",\"cost\":\"10.00\",\"description\":\"Inspection\",\"notes\":\"\",\"tags\":\"\",\"files\":[],\"extraFields\":[]}";
    private static final String LITERAL_NULL = RECORD.replace("\"notes\":\"\"", "\"notes\":\"null\"");
    private static final String FILES = "[{\"name\":\"synthetic.pdf\",\"location\":\"/uploads/test\"}]";
    private static final String UNRELATED = "{\"name\":\"Unrelated Field\",\"value\":\"keep\"}";
    private static final String SERVICE_PRESERVED = RECORD.replace("\"files\":[]", "\"files\":" + FILES)
        .replace("\"extraFields\":[]", "\"extraFields\":[" + UNRELATED + "]");
    private static final String FUEL_BASE = "{\"id\":15,\"vehicleId\":1,\"date\":\"2026-10-12\",\"odometer\":\"1200\",\"cost\":\"45.67\",\"notes\":\"\",\"tags\":\"\",\"fuelConsumed\":\"12.5\",\"fuelEconomy\":0,\"isFillToFull\":true,\"missedFuelUp\":false,\"startingSoc\":0,\"endingSoc\":0,\"files\":" + FILES + ",\"extraFields\":[" + UNRELATED + ",{\"name\":\"LubeLink Gas Station\",\"value\":\"Old Station\"},{\"name\":\"LubeLink Station Address\",\"value\":\"123 Test Rd\"}]}";
    private static final String FUEL_CREATED = FUEL_BASE.replace("Old Station", "New Station")
        .replace("[" + UNRELATED + ",", "[");
    /** [FUEL_BASE] with its custom-field member replaced by [extras] (",\"extraFields\":…", or "" for none). */
    private static String fuelWith(String extras) {
        return FUEL_BASE.substring(0, FUEL_BASE.indexOf(",\"extraFields\"")) + extras + "}";
    }
    private static final String NULL_UNRELATED = "{\"name\":\"Unrelated Field\",\"value\":null}";
    private static final String NO_VALUE_UNRELATED = "{\"name\":\"Unrelated Field\"}";
    private String fuelAddReadBack() {
        return switch (mode) {
            case FUEL_POST_EXTRAS_OBJECT -> fuelWith(",\"extraFields\":{}");
            case FUEL_POST_EXTRAS_NULL -> fuelWith(",\"extraFields\":null");
            case FUEL_POST_EXTRAS_NUMBER -> fuelWith(",\"extraFields\":7");
            case FUEL_POST_EXTRAS_ABSENT -> fuelWith("");
            case FUEL_POST_EXTRAS_NO_VALUE -> fuelWith(",\"extraFields\":[{\"name\":\"LubeLink Gas Station\"}]");
            case FUEL_POST_EXTRAS_EMPTY -> fuelWith(",\"extraFields\":[]");
            case FUEL_POST_EXTRAS_NULL_VALUE -> fuelWith(",\"extraFields\":[{\"name\":\"LubeLink Gas Station\",\"value\":null}," +
                "{\"name\":\"LubeLink Station Address\",\"value\":null}]");
            default -> null;
        };
    }
    private boolean fuelAddMode() { return mode.name().startsWith("FUEL_POST_EXTRAS_"); }
    public SyntheticServer(String keyStorePath, String mode) throws Exception {
        this.mode = Mode.valueOf(mode);
        if (this.mode == Mode.POST_SUCCESS || this.mode == Mode.POST_MISMATCH || this.mode == Mode.POST_FRACTIONAL_ID ||
            this.mode == Mode.FUEL_POST_SUCCESS || this.mode == Mode.POST_READBACK_DUPLICATE ||
            this.mode == Mode.POST_READBACK_NO_NOTES || this.mode == Mode.POST_READBACK_NUMBER_NOTES ||
            this.mode == Mode.POST_READBACK_BOOLEAN_TAGS || this.mode.name().startsWith("FUEL_POST_EXTRAS_")) present = false;
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(keyStorePath))) { store.load(input, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 25, InetAddress.getLoopbackAddress());
        server.setSoTimeout(250);
        baseUrl = "https://localhost:" + server.getLocalPort();
        acceptLoop = threads.submit(() -> {
            while (running) {
                try { SSLSocket client = (SSLSocket) server.accept(); threads.submit(() -> handle(client)); }
                catch (SocketTimeoutException ignored) { }
                catch (IOException e) { if (running) throw new UncheckedIOException(e); }
            }
        });
    }
    private static String line(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return out.toString(StandardCharsets.UTF_8).replace("\r", "");
            out.write(c);
            if (out.size() > 16_384) throw new IOException("Mock request too big");
        }
        return out.size() == 0 ? null : out.toString(StandardCharsets.UTF_8);
    }
    private static String readBody(BufferedInputStream in, int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (count-- > 0) { int c = in.read(); if (c < 0) throw new EOFException(); out.write(c); }
        return out.toString(StandardCharsets.UTF_8);
    }
    private static void respond(OutputStream out, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String phrase = status == 200 ? "OK" : status == 204 ? "No Content" : "Rejected";
        out.write(("HTTP/1.1 " + status + " " + phrase + "\r\n" +
            "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
    private void handle(SSLSocket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (running) {
                String start = line(in); if (start == null) return;
                if (start.isBlank()) continue;
                String[] request = start.split(" ");
                if (request.length < 2) throw new IOException("Invalid mock request");
                String method = request[0], path = request[1];
                int length = 0;
                String header;
                while ((header = line(in)) != null && !header.isEmpty()) {
                    if (header.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                        length = Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                }
                String requestBody = readBody(in, length);
                if (method.equals("GET") && path.equals("/api/vehicles")) { respond(out, 200, CAR); continue; }
                if (method.equals("GET") && path.equals("/api/extrafields")) {
                    respond(out, 200, "[{\"recordType\":\"GasRecord\",\"extraFields\":[{\"name\":\"LubeLink Gas Station\",\"isRequired\":\"False\",\"fieldType\":\"Text\"},{\"name\":\"LubeLink Station Address\",\"isRequired\":\"False\",\"fieldType\":\"Text\"}]}]"); continue;
                }
                if (method.equals("GET") && path.equals("/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false")) {
                    respond(out, 200, "[" + (mode == Mode.FUEL_POST_SUCCESS ? FUEL_CREATED :
                        mode == Mode.FUEL_NULL_EXTRA ? FUEL_BASE.replace("\"value\":\"Old Station\"", "\"value\":null") : FUEL_BASE) + "]"); continue;
                }
                if (method.equals("GET") && (path.startsWith("/api/vehicle/servicerecords?") ||
                        path.startsWith("/api/vehicle/repairrecords?") ||
                        path.startsWith("/api/vehicle/upgraderecords?") ||
                        path.startsWith("/api/vehicle/gasrecords?"))) {
                    if (deleteSeen && mode == Mode.DELETE_400_VERIFY_FAIL) { respond(out, 500, "{}"); continue; }
                    String malformed = deleteSeen ? switch (mode) {
                        case DELETE_204_VERIFY_EMPTY_OBJECT, DELETE_404_VERIFY_EMPTY_OBJECT,
                             DELETE_REJECTED_VERIFY_EMPTY_OBJECT -> "[{}]";
                        case DELETE_204_VERIFY_NUMBER -> "[7]";
                        case DELETE_204_VERIFY_FOREIGN -> "[" + RECORD.replace("\"vehicleId\":1", "\"vehicleId\":2") + "]";
                        case DELETE_204_VERIFY_NO_VEHICLE -> "[" + RECORD.replace("\"vehicleId\":1,", "") + "]";
                        case DELETE_204_VERIFY_DUPLICATE -> "[" + RECORD + "," + RECORD + "]";
                        default -> null;
                    } : null;
                    if (malformed != null) { respond(out, 200, malformed); continue; }
                    if (present && mode == Mode.POST_READBACK_DUPLICATE) { respond(out, 200, "[" + RECORD + "," + RECORD + "]"); continue; }
                    if (present && mode == Mode.POST_READBACK_NO_NOTES) { respond(out, 200, "[" + RECORD.replace("\"notes\":\"\",", "") + "]"); continue; }
                    if (present && mode == Mode.POST_READBACK_NUMBER_NOTES) { respond(out, 200, "[" + RECORD.replace("\"notes\":\"\"", "\"notes\":123") + "]"); continue; }
                    if (present && mode == Mode.POST_READBACK_BOOLEAN_TAGS) { respond(out, 200, "[" + RECORD.replace("\"tags\":\"\"", "\"tags\":true") + "]"); continue; }
                    if (updated && mode == Mode.PUT_READBACK_NUMBER_NOTES) {
                        respond(out, 200, "[" + RECORD.replace("\"notes\":\"\"", "\"notes\":123") + "]"); continue;
                    }
                    if (updated && mode == Mode.FUEL_PUT_READBACK_NUMBER_STATION && path.startsWith("/api/vehicle/gasrecords?")) {
                        respond(out, 200, "[" + FUEL_BASE.replace("\"value\":\"Old Station\"", "\"value\":123") + "]"); continue;
                    }
                    if (present && fuelAddMode() && path.startsWith("/api/vehicle/gasrecords?")) {
                        respond(out, 200, "[" + fuelAddReadBack() + "]"); continue;
                    }
                    if (mode == Mode.FUEL_EDIT_NO_VALUE && path.startsWith("/api/vehicle/gasrecords?")) {
                        respond(out, 200, "[" + FUEL_BASE.replace("{\"name\":\"LubeLink Gas Station\",\"value\":\"Old Station\"}",
                            "{\"name\":\"LubeLink Gas Station\"}") + "]"); continue;
                    }
                    if (mode == Mode.FUEL_PUT_READBACK_NO_VALUE && path.startsWith("/api/vehicle/gasrecords?")) {
                        // Before the edit the unrelated field's value is JSON null; afterwards its value member is gone.
                        respond(out, 200, "[" + (updated
                            ? FUEL_BASE.replace(UNRELATED, NO_VALUE_UNRELATED).replace("Old Station", "New Station")
                            : FUEL_BASE.replace(UNRELATED, NULL_UNRELATED)) + "]"); continue;
                    }
                    if (updated && mode == Mode.PUT_READBACK_NO_NOTES) {
                        respond(out, 200, "[" + RECORD.replace("Inspection", "Inspected").replace("\"notes\":\"\",", "") + "]"); continue;
                    }
                    String raw = switch (mode) {
                        case NOOP -> path.startsWith("/api/vehicle/gasrecords?") ? FUEL_BASE : RECORD;
                        case POST_MISMATCH -> RECORD.replace("\"10.00\"", "\"99.00\"");
                        case PUT_SUCCESS -> updated ? SERVICE_PRESERVED.replace("Inspection", "Inspected") : SERVICE_PRESERVED;
                        case PUT_LITERAL_NULL -> updated ? LITERAL_NULL.replace("\"cost\":\"10.00\"", "\"cost\":\"11.00\"") : LITERAL_NULL;
                        case FUEL_POST_SUCCESS -> FUEL_CREATED;
                        case FUEL_NULL_EXTRA -> path.startsWith("/api/vehicle/gasrecords?")
                            ? FUEL_BASE.replace("\"value\":\"Old Station\"", "\"value\":null") : RECORD;
                        case FUEL_PUT_SUCCESS -> updated ? FUEL_BASE.replace("Old Station", "New Station") : FUEL_BASE;
                        case FUEL_PUT_READBACK_NUMBER_STATION -> FUEL_BASE;
                        default -> RECORD;
                    };
                    respond(out, 200, present ? "[" + raw + "]" : "[]"); continue;
                }
                if (method.equals("POST") && (path.equals("/api/vehicle/servicerecords/add?vehicleId=1") ||
                        path.equals("/api/vehicle/gasrecords/add?vehicleId=1"))) {
                    posts.incrementAndGet(); lastPostBody = requestBody;
                    if (mode == Mode.POST_DROP) return; // committed but response lost
                    if (mode == Mode.POST_408 || mode == Mode.POST_429) {
                        respond(out, mode == Mode.POST_408 ? 408 : 429, "{}"); continue;
                    }
                    if (mode == Mode.POST_FRACTIONAL_ID) {
                        present = true;
                        respond(out, 200, "{\"success\":true,\"additionalData\":{\"recordId\":15.5}}"); continue;
                    }
                    if (mode == Mode.POST_SUCCESS || mode == Mode.POST_MISMATCH || mode == Mode.FUEL_POST_SUCCESS ||
                        mode == Mode.POST_READBACK_DUPLICATE || mode == Mode.POST_READBACK_NO_NOTES ||
                        mode == Mode.POST_READBACK_NUMBER_NOTES || mode == Mode.POST_READBACK_BOOLEAN_TAGS || fuelAddMode()) {
                        present = true; // Synthetic server commits this record before the read-back.
                        respond(out, 200, "{\"success\":true,\"additionalData\":{\"recordId\":15}}"); continue;
                    }
                    throw new IOException("Unexpected POST in " + mode);
                }
                if (method.equals("PUT") && (path.equals("/api/vehicle/servicerecords/update") ||
                        path.equals("/api/vehicle/gasrecords/update"))) {
                    puts.incrementAndGet(); lastPutBody = requestBody;
                    if (mode == Mode.PUT_DROP) return; // committed but response lost
                    if (mode == Mode.PUT_408 || mode == Mode.PUT_429) {
                        respond(out, mode == Mode.PUT_408 ? 408 : 429, "{}"); continue;
                    }
                    if (mode == Mode.PUT_SUCCESS || mode == Mode.FUEL_PUT_SUCCESS || mode == Mode.PUT_LITERAL_NULL ||
                        mode == Mode.PUT_READBACK_NO_NOTES || mode == Mode.PUT_READBACK_NUMBER_NOTES ||
                        mode == Mode.FUEL_PUT_READBACK_NUMBER_STATION || mode == Mode.FUEL_PUT_READBACK_NO_VALUE) {
                        updated = true; respond(out, 200, "{\"success\":true}"); continue;
                    }
                    throw new IOException("Unexpected PUT in " + mode);
                }
                if (method.equals("DELETE") && path.equals("/api/vehicle/servicerecords/delete?id=15")) {
                    deletes.incrementAndGet(); deleteSeen = true;
                    if (mode == Mode.DELETE_DROP) { present = false; return; } // committed, reply lost
                    int code = switch (mode) {
                        case DELETE_400_ABSENT, DELETE_400_PRESENT, DELETE_400_VERIFY_FAIL -> 400;
                        case DELETE_404_ABSENT -> 404;
                        case DELETE_204_ABSENT, DELETE_204_PRESENT, DELETE_204_VERIFY_EMPTY_OBJECT, DELETE_204_VERIFY_NUMBER,
                             DELETE_204_VERIFY_FOREIGN, DELETE_204_VERIFY_NO_VEHICLE, DELETE_204_VERIFY_DUPLICATE -> 204;
                        case DELETE_404_VERIFY_EMPTY_OBJECT -> 404;
                        case DELETE_REJECTED_VERIFY_EMPTY_OBJECT -> 200;
                        case DELETE_403_PRESENT -> 403;
                        default -> throw new IOException("Unexpected DELETE in " + mode);
                    };
                    if (mode == Mode.DELETE_400_ABSENT || mode == Mode.DELETE_404_ABSENT ||
                        mode == Mode.DELETE_204_ABSENT || mode == Mode.DELETE_400_VERIFY_FAIL) present = false;
                    respond(out, code, code == 204 ? "" : "{\"success\":false}"); continue;
                }
                respond(out, 404, "{}");
            }
        } catch (IOException ignored) { /* Expected for intentionally dropped replies. */ }
    }
    public String lastPostBody() { return lastPostBody; }
    public String lastPutBody() { return lastPutBody; }
    public int posts() { return posts.get(); }
    public int puts() { return puts.get(); }
    public int deletes() { return deletes.get(); }
    @Override public void close() throws Exception {
        running = false; server.close(); acceptLoop.cancel(true);
        threads.shutdownNow();
        // Daemon workers will exit after the sockets are closed; do not make tests
        // wait on a keep-alive read when the client has already finished. 
    }
    public static String recordJson() { return RECORD; }
}
