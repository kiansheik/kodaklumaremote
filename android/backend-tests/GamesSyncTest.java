package dev.framextv.lumaremote;

import android.content.Context;
import android.net.Uri;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class GamesSyncTest {
    static void check(boolean value, String message) { GamesLibraryTest.check(value, message); }
    static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }
    static ConnectionService.Callback callback(CompletableFuture<JSONObject> done) {
        return new ConnectionService.Callback() {
            public void success(JSONObject value) { done.complete(value); }
            public void failure(String code, String message) { done.completeExceptionally(new IOException(code)); }
        };
    }
    static JSONObject request(GamesManager manager, String method) throws Exception {
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        manager.request(method, new JSONObject(), callback(done)); return done.get(15, TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        GamesLibrary phone = new GamesLibrary(new File(root, "games"));
        byte[] local = GamesLibraryTest.rom(8, 4, 11), other = GamesLibraryTest.rom(2, 1, 22);
        String localId = GamesLibrary.sha256(local), otherId = GamesLibrary.sha256(other);
        phone.add(new ByteArrayInputStream(local), "Local", null);
        Map<String, byte[]> remote = new ConcurrentHashMap<>(); remote.put(otherId, other);
        Map<String, ByteArrayOutputStream> uploads = new ConcurrentHashMap<>();
        AtomicInteger puts = new AtomicInteger(), gets = new AtomicInteger(), maxChunk = new AtomicInteger();
        AtomicReference<String> corrupt = new AtomicReference<>(), omit = new AtomicReference<>();
        AtomicBoolean unauthorized = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            int status = 200; JSONObject envelope = new JSONObject();
            try {
                check("POST".equals(exchange.getRequestMethod()), "Unexpected verb");
                check("Bearer private-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "Missing auth");
                JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String method = exchange.getRequestURI().getPath().substring("/api/v1/".length());
                JSONObject result = new JSONObject();
                if (unauthorized.get()) { status = 401; envelope.put("ok", false).put("error", new JSONObject().put("code", "UNAUTHORIZED")); }
                else {
                    if ("games.list".equals(method)) {
                        JSONArray games = new JSONArray();
                        for (Map.Entry<String, byte[]> entry : remote.entrySet()) if (!entry.getKey().equals(omit.get())) games.put(new JSONObject()
                                .put("id", entry.getKey()).put("name", "Remote game").put("system", "nes").put("sizeBytes", entry.getValue().length));
                        result.put("games", games);
                    } else if ("games.put".equals(method)) {
                        puts.incrementAndGet(); String id = body.getString("id"); int offset = body.getInt("offset"), size = body.getInt("totalSize");
                        byte[] chunk = Base64.getDecoder().decode(body.getString("data")); maxChunk.accumulateAndGet(chunk.length, Math::max);
                        check(chunk.length <= 131072, "Oversized chunk");
                        if (remote.containsKey(id)) result.put("received", size).put("complete", true);
                        else {
                            if (offset == 0) uploads.put(id, new ByteArrayOutputStream());
                            ByteArrayOutputStream upload = uploads.get(id); check(upload != null && upload.size() == offset, "Upload offset"); upload.write(chunk);
                            boolean complete = upload.size() == size;
                            if (complete) { check(id.equals(GamesLibrary.sha256(upload.toByteArray())), "Upload hash"); remote.put(id, upload.toByteArray()); uploads.remove(id); }
                            result.put("received", upload.size()).put("complete", complete);
                        }
                    } else if ("games.get".equals(method)) {
                        gets.incrementAndGet(); String id = body.getString("id"); byte[] bytes = remote.get(id);
                        int offset = body.getInt("offset"), length = body.getInt("length");
                        byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + length);
                        if (id.equals(corrupt.get()) && offset == 0) chunk[20]++;
                        result.put("id", id).put("offset", offset).put("totalSize", bytes.length).put("data", Base64.getEncoder().encodeToString(chunk));
                    } else result.put("accepted", true);
                    envelope.put("ok", true).put("result", result);
                }
            } catch (Throwable error) {
                status = 500;
                try { envelope.put("ok", false).put("error", new JSONObject().put("code", "TEST_FAILED")); } catch (Exception ignored) { }
                error.printStackTrace();
            }
            byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        AtomicReference<JSONObject> observed = new AtomicReference<>(new JSONObject());
        NativeBackend.Endpoint endpoint = new NativeBackend.Endpoint("127.0.0.1", server.getAddress().getPort(), "private-test-token");
        GamesManager manager = new GamesManager(new Context(root), () -> endpoint, observed::set);
        try {
            manager.connectionChanged(true);
            waitFor(() -> "ready".equals(observed.get().optString("status")), "Initial sync failed");
            check(Arrays.equals(remote.get(localId), local), "Phone upload changed bytes");
            check(Arrays.equals(phone.bytes(otherId), other), "Projector download changed bytes");
            check(puts.get() == 2 && gets.get() == 1 && maxChunk.get() == 131072, "Chunk boundaries incorrect");
            int previousPuts = puts.get(), previousGets = gets.get(); request(manager, "sync");
            check(previousPuts == puts.get() && previousGets == gets.get(), "Existing ROMs were recopied");
            byte[] damaged = other.clone(); damaged[21]++;
            Files.write(new File(root, "games/" + otherId + ".nes").toPath(), damaged);
            request(manager, "sync"); check(Arrays.equals(phone.bytes(otherId), other), "Corrupt local copy was not repaired from projector");
            previousGets = gets.get();
            omit.set(localId); request(manager, "sync"); omit.set(null);
            check(puts.get() == previousPuts + 1, "Concurrent identical upload did not short-circuit");
            byte[] fresh = GamesLibraryTest.rom(1, 1, 33); String freshId = GamesLibrary.sha256(fresh);
            remote.put(freshId, fresh); corrupt.set(freshId);
            try { request(manager, "sync"); throw new AssertionError("Corrupt download accepted"); }
            catch (ExecutionException error) { check(error.getCause().getMessage().equals("GAME_HASH_MISMATCH"), "Wrong hash error"); }
            check(phone.list().length() == 2, "Partial/corrupt ROM visible");
            corrupt.set(null); request(manager, "sync"); check(Arrays.equals(phone.bytes(freshId), fresh), "Retry failed");
            byte[] shared = GamesLibraryTest.rom(1, 1, 44); File sharedFile = new File(root, "shared.nes"); Files.write(sharedFile.toPath(), shared);
            CompletableFuture<JSONObject> imported = new CompletableFuture<>();
            manager.importRom(Uri.parse(sharedFile.toURI().toString()), callback(imported)); imported.get(10, TimeUnit.SECONDS);
            String sharedId = GamesLibrary.sha256(shared); waitFor(() -> remote.containsKey(sharedId), "Import did not trigger sync");
            waitFor(() -> !observed.get().optBoolean("syncing"), "Import sync did not finish");
            unauthorized.set(true);
            try { request(manager, "sync"); throw new AssertionError("Auth failure hidden"); }
            catch (ExecutionException error) { check(error.getCause().getMessage().equals("RECEIVER_AUTH"), "Auth code changed"); }
            unauthorized.set(false); manager.connectionChanged(false);
            check(!observed.get().optBoolean("connected"), "Disconnect retained connected state");
            JSONArray disconnectedGames = observed.get().getJSONArray("games");
            for (int i = 0; i < disconnectedGames.length(); i++) check(!disconnectedGames.getJSONObject(i).optBoolean("onProjector"), "Disconnect retained remote confirmation");
            manager.connectionChanged(true);
            waitFor(() -> "ready".equals(observed.get().optString("status")), "Immediate reconnect did not sync");
            check(phone.list().length() == 4, "Sync removed games");
            System.out.println("Games sync: authenticated chunk transfer both ways, dedup, corrupt-transfer retry, import sync and reconnect passed.");
        } finally { manager.close(); server.stop(0); }
    }
}
