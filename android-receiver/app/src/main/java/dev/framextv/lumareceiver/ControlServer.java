package dev.framextv.lumareceiver;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;

import androidx.media3.common.C;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LAN control API: `GET /api/v1/info`, `POST /api/v1/<method>` with a JSON
 * object body. Everything except info and pairing needs `Authorization:
 * Bearer <token>`. Only private/loopback peers are served.
 */
final class ControlServer {
    static final int PORT = 4191, PROTOCOL = 1;
    static final long MAX_WAIT_MS = 25_000;
    private final ReceiverApp app;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private volatile ServerSocket socket;
    volatile long listeningAt;
    private int bindFailures;
    private NsdManager.RegistrationListener nsd;

    ControlServer(ReceiverApp app) { this.app = app; }

    synchronized void start() {
        if (socket != null) return;
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(PORT), 16);
            socket = server;
            listeningAt = SystemClock.elapsedRealtime();
        } catch (IOException failure) {
            android.util.Log.e("LumaServer", "BIND_FAILED");
            if (++bindFailures <= 12) ReceiverApp.main().postDelayed(this::start, 1000);
            return;
        }
        Thread accept = new Thread(this::acceptLoop, "luma-accept");
        accept.setDaemon(true);
        accept.start();
        advertise();
    }

    private void acceptLoop() {
        ServerSocket server = socket;
        while (server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                workers.execute(() -> serve(client));
            } catch (IOException closed) { if (server.isClosed()) return; }
        }
    }

    private void serve(Socket client) {
        try (Socket owned = client) {
            owned.setSoTimeout(10_000);
            InetAddress peer = owned.getInetAddress();
            if (!(peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress())) {
                reply(owned, 403, error("FORBIDDEN_PEER"));
                return;
            }
            HttpMessage request;
            try { request = HttpMessage.read(owned.getInputStream()); }
            catch (HttpMessage.HttpException bad) { reply(owned, bad.status, error(bad.code)); return; }
            JSONObject response;
            int status = 200;
            try { response = new JSONObject().put("ok", true).put("result", route(request)); }
            catch (ApiException failure) { status = failure.status; response = error(failure.code); }
            catch (Exception failure) {
                android.util.Log.w("LumaServer", "INTERNAL " + failure.getClass().getSimpleName());
                status = 500; response = error("INTERNAL");
            }
            reply(owned, status, response);
        } catch (Exception ignored) { }
    }

    private Object route(HttpMessage request) throws Exception {
        if ("GET".equals(request.method) && ("/api/v1/info".equals(request.path) || "/".equals(request.path))) return info();
        if (!"POST".equals(request.method) || !request.path.startsWith("/api/v1/")) throw new ApiException("NOT_FOUND", 404);
        String method = request.path.substring("/api/v1/".length());
        JSONObject params = body(request);
        if ("pair.begin".equals(method)) return app.pairing.begin(MediaRequest.label(params.optString("clientName", "Unnamed client"), 60));
        if ("pair.complete".equals(method)) return app.pairing.complete(params.optString("pairingId"), params.optString("code"));
        String client = app.pairing.authorize(request.header("Authorization"));
        if (client == null) throw new ApiException("UNAUTHORIZED", 401);
        return call(method, params, client);
    }

    private static final java.util.Set<String> INTERACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
        "player.open", "player.pause", "player.resume", "player.stop", "player.seek", "player.addSubtitle",
        "player.setSubtitle", "player.setAudioTrack", "player.next"));

    private Object call(String method, JSONObject params, String client) throws Exception {
        PlaybackController playback = app.playback;
        if (INTERACTIONS.contains(method) && !"player.open".equals(method)) ReceiverApp.onMain(() -> { playback.noteInteraction(); return null; });
        switch (method) {
            case "player.open": {
                MediaRequest media = MediaRequest.parse(params.optJSONObject("media"));
                if (params.has("onlyIfInactive") && !(params.opt("onlyIfInactive") instanceof Boolean)) throw new ApiException("INVALID_INPUT");
                boolean hasUrl = params.has("expectedCurrentSourceUrl"), hasKey = params.has("expectedCurrentContentKey");
                if (hasUrl != hasKey || hasUrl && params.optBoolean("onlyIfInactive")) throw new ApiException("INVALID_INPUT");
                String currentUrl = hasUrl ? MediaRequest.httpUrl(params.opt("expectedCurrentSourceUrl")) : null;
                String currentKey = hasKey ? MediaRequest.label(params.opt("expectedCurrentContentKey"), 200) : null;
                return ReceiverApp.onMain(() -> { JSONObject opened = playback.open(media, params.optBoolean("onlyIfInactive"), currentUrl, currentKey); app.closeGames(); playback.noteInteraction(); return opened; });
            }
            case "player.next": {
                // Validate and enqueue from one main-thread turn. Otherwise a
                // second controller can replace the title between the guard
                // and startNext's snapshot, authorizing Next for the wrong item.
                ReceiverApp.onMain(() -> {
                    if (params.has("expectedContentKey")) playback.expectContent(params.opt("expectedContentKey"));
                    playback.startNext(false);
                    return null;
                });
                return accepted();
            }
            case "settings.get": return new JSONObject().put("autoNext", playback.autoNext());
            case "settings.set": {
                if (!(params.opt("autoNext") instanceof Boolean)) throw new ApiException("INVALID_INPUT");
                ReceiverApp.onMain(() -> { playback.setAutoNext(params.getBoolean("autoNext")); return null; });
                return new JSONObject().put("autoNext", params.getBoolean("autoNext"));
            }
            case "player.status": {
                if (params.has("afterRevision")) {
                    long known = (long) MediaRequest.integer(params.opt("afterRevision"), 0, Integer.MAX_VALUE);
                    long wait = params.has("waitMs") ? MediaRequest.integer(params.opt("waitMs"), 0, (int) MAX_WAIT_MS) : MAX_WAIT_MS;
                    playback.awaitChange(known, wait);
                }
                return ReceiverApp.onMain(() -> playback.status().put("catMode",
                    app.catMode.summary(SystemClock.elapsedRealtime())));
            }
            case "player.pause": case "player.resume":
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.setPlaying("player.resume".equals(method)); return accepted(); });
            case "player.stop":
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.stop(); app.updater.installIfIdle(); return accepted(); });
            case "player.seek": {
                double position = MediaRequest.seconds(params.opt("position"));
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.seek(position); return accepted(); });
            }
            case "player.rate": {
                // Small speed nudges keep two screens in step without a visible seek.
                Object raw = params.opt("value");
                if (!(raw instanceof Number) || ((Number) raw).doubleValue() < 0.5 || ((Number) raw).doubleValue() > 2) throw new ApiException("INVALID_INPUT");
                float rate = ((Number) raw).floatValue();
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.setRate(rate); return accepted(); });
            }
            case "player.subtitles":
                return ReceiverApp.onMain(() -> { if (playback.title() != null) playback.expect(params); return playback.tracks(C.TRACK_TYPE_TEXT); });
            case "player.audioTracks":
                return ReceiverApp.onMain(() -> playback.tracks(C.TRACK_TYPE_AUDIO));
            case "player.addSubtitle": {
                MediaRequest.Subtitle subtitle = MediaRequest.subtitle(params.optJSONObject("track"));
                ExternalCaptions captions = ReceiverApp.onMain(() -> { playback.expect(params); return playback.addExternal(subtitle); });
                // Stay within the phone's 5 s call budget; a slow file keeps loading in the background.
                boolean loaded = captions.await(4000);
                if (loaded && captions.error != null) {
                    ReceiverApp.onMain(() -> { playback.discardExternal(captions); return null; });
                    throw new ApiException(captions.error, 422);
                }
                int index = ReceiverApp.onMain(() -> playback.externalIndex(captions));
                return new JSONObject().put("accepted", true).put("index", index).put("loaded", loaded);
            }
            case "player.setSubtitle":
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.selectTrack(C.TRACK_TYPE_TEXT, params.opt("index")); return accepted(); });
            case "player.setAudioTrack":
                return ReceiverApp.onMain(() -> { playback.expect(params); playback.selectTrack(C.TRACK_TYPE_AUDIO, params.opt("index")); return accepted(); });
            case "display.get": return app.display.toJson();
            case "display.set": return ReceiverApp.onMain(() -> {
                if (params.has("expectedSourceUrl") || params.has("expectedContentKey")) playback.expect(params);
                app.display.update(params);
                playback.applyDisplay();
                return app.display.toJson();
            });
            case "input.key": return ReceiverApp.onMain(() -> RemoteInput.send(app, params.optString("key", "")));
            case "volume.get": return volume();
            case "volume.set": {
                AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, MediaRequest.integer(params.opt("level"), 0, max), 0);
                return volume();
            }
            case "volume.mute": {
                if (!(params.opt("muted") instanceof Boolean)) throw new ApiException("INVALID_INPUT");
                AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, params.getBoolean("muted") ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                return volume();
            }
            case "library.pull": {
                long since = params.has("since") ? MediaRequest.integer(params.opt("since"), 0, Integer.MAX_VALUE) : 0;
                int limit = params.has("limit") ? MediaRequest.integer(params.opt("limit"), 1, LibraryStore.MAX_PULL) : 200;
                return app.library.pull(since, limit);
            }
            case "library.push": {
                JSONArray records = params.optJSONArray("records");
                if (records == null || records.length() > LibraryStore.MAX_PULL) throw new ApiException("INVALID_INPUT");
                List<SyncRecord> parsed = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (int i = 0; i < records.length(); i++) parsed.add(SyncRecord.parse(records.optJSONObject(i), now));
                int applied = app.library.merge(parsed);
                return new JSONObject().put("applied", applied).put("cursor", app.library.cursor());
            }
            case "library.get": {
                String key = params.optString("key");
                if (!SyncRecord.KEY.matcher(key).matches()) throw new ApiException("INVALID_INPUT");
                JSONObject value = app.library.get(key);
                return new JSONObject().put("record", value == null ? JSONObject.NULL : value);
            }
            case "update.status": return app.updater.status();
            case "update.check": app.updater.check(); return accepted();
            case "update.setPolicy": app.updater.setPolicy(params.optString("policy")); return app.updater.status();
            case "update.install": ReceiverApp.main().post(app.updater::install); return accepted();
            case "update.skip": app.updater.skipAvailable(); return accepted();
            case "update.installFrom": {
                String url = MediaRequest.httpUrl(params.opt("url"));
                long size = params.has("sizeBytes") ? MediaRequest.integer(params.opt("sizeBytes"), 1, (int) Updater.MAX_APK) : 0;
                app.updater.installFrom(url, params.optString("sha256"), size);
                return accepted();
            }
            case "pair.clients": return new JSONObject().put("clients", app.pairing.publicClients()).put("self", client);
            case "pair.revoke": return new JSONObject().put("revoked", app.pairing.revoke(params.optString("clientId")));
            case "apps.list": return app.launcher.json();
            case "recovery.status": return app.streamRecovery.snapshot();
            case "history.show": return ReceiverApp.onMain(() -> { HistoryActivity.show(app); return accepted(); });
            case "history.status": return ReceiverApp.onMain(() -> app.historyUi == null ? new JSONObject().put("active", false) : app.historyUi.snapshot());
            case "games.list": return app.games.list();
            case "games.get": {
                String id = GameLibrary.id(params.optString("id"));
                int offset = MediaRequest.integer(params.opt("offset"), 0, GameLibrary.MAX_ROM);
                int length = MediaRequest.integer(params.opt("length"), 1, GameLibrary.CHUNK);
                byte[] bytes = app.games.chunk(id, offset, length);
                return new JSONObject().put("id", id).put("offset", offset).put("totalSize", app.games.file(id).length())
                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
            }
            case "games.put": {
                String id = GameLibrary.id(params.optString("id"));
                int offset = MediaRequest.integer(params.opt("offset"), 0, GameLibrary.MAX_ROM);
                int total = MediaRequest.integer(params.opt("totalSize"), 16, GameLibrary.MAX_ROM);
                Object encoded = params.opt("data");
                if (!(encoded instanceof String) || ((String) encoded).length() > 4 * ((GameLibrary.CHUNK + 2) / 3))
                    throw new ApiException("INVALID_GAME_DATA");
                byte[] bytes;
                try { bytes = android.util.Base64.decode((String) encoded, android.util.Base64.NO_WRAP); }
                catch (IllegalArgumentException invalid) { throw new ApiException("INVALID_GAME_DATA"); }
                JSONObject result = app.games.put(id, params.optString("name"), client, offset, total, bytes);
                if (result.optBoolean("complete")) ReceiverApp.main().post(app::refreshGames);
                return result;
            }
            case "games.play": {
                String id = GameLibrary.id(params.optString("id"));
                app.games.file(id);
                return ReceiverApp.onMain(() -> { GamesActivity.show(app, id); return accepted(); });
            }
            case "games.show": return ReceiverApp.onMain(() -> { GamesActivity.show(app, null); return accepted(); });
            case "games.stop": return ReceiverApp.onMain(() -> { app.stopGame(); return accepted(); });
            case "games.status": return ReceiverApp.onMain(app::gameStatus);
            case "games.controllers": return new JSONObject().put("devices", ControllerInput.devices());
            case "controllers.show": return ReceiverApp.onMain(() -> { ControllerSetupActivity.show(app); return accepted(); });
            case "controllers.status": return ReceiverApp.onMain(app::controllerSetupStatus);
            case "controllers.players": return ReceiverApp.onMain(() -> new ControllerPlayerStore(app).snapshot());
            case "controllers.navigation": return ReceiverApp.onMain(app::controllerNavigationStatus);
            case "controllers.system": return ReceiverApp.onMain(() -> ControllerSystemService.status(app));
            case "controllers.settingsPanels": return AccessibilityPanel.diagnostics(app);
            case "controllers.accessibility": return ReceiverApp.onMain(() -> {
                String destination = ControllerSystemService.openSettings(app);
                return new JSONObject().put("accepted", !"unavailable".equals(destination)).put("destination", destination);
            });
            case "controllers.lights": return ReceiverApp.onMain(() -> app.controllerLeds.status());
            case "controllers.setLights": {
                int id = MediaRequest.integer(params.opt("deviceId"), 0, Integer.MAX_VALUE);
                return ReceiverApp.onMain(() -> {
                    android.view.InputDevice device = android.view.InputDevice.getDevice(id);
                    if (device == null) throw new ApiException("INVALID_CONTROLLER");
                    return app.controllerLeds.request(device, new ControllerPlayerStore(app).player(device));
                });
            }
            case "controllers.snapshot": {
                String png = ReceiverApp.onMain(() -> {
                    android.graphics.Bitmap bitmap = app.controllerSetupSnapshot();
                    if (bitmap == null) return null;
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle();
                    return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
                });
                if (png == null) throw new ApiException("NO_UI", 409);
                return new JSONObject().put("png", png);
            }
            case "apps.launch": {
                app.launcher.launch(params.optString("packageName", ""));
                return accepted();
            }
            case "cat.start": {
                if (params.has("sound") && !(params.opt("sound") instanceof Boolean)) throw new ApiException("INVALID_INPUT");
                boolean sound = params.optBoolean("sound", true);
                return ReceiverApp.onMain(() -> {
                    app.closeGames();
                    playback.noteInteraction();
                    if (playback.active()) playback.setPlaying(false);
                    app.catMode.start(sound, SystemClock.elapsedRealtime());
                    MainActivity.bringToFront(app);
                    return app.catMode.json(SystemClock.elapsedRealtime());
                });
            }
            case "cat.status": return app.catMode.json(SystemClock.elapsedRealtime());
            case "cat.audioStatus": return CatSoundPlayer.diagnostics();
            case "cat.pointer": {
                double x = normalized(params, "x"), y = normalized(params, "y");
                return ReceiverApp.onMain(() -> {
                    long now = SystemClock.elapsedRealtime();
                    if (!app.catMode.pointer(x, y, now)) throw new ApiException("CAT_MODE_INACTIVE", 409);
                    return app.catMode.json(now);
                });
            }
            case "cat.release": return ReceiverApp.onMain(() -> {
                long now = SystemClock.elapsedRealtime();
                if (!app.catMode.release(now)) throw new ApiException("CAT_MODE_INACTIVE", 409);
                return app.catMode.json(now);
            });
            case "cat.pounce": {
                boolean hasX = params.has("x"), hasY = params.has("y");
                if (hasX != hasY) throw new ApiException("INVALID_INPUT");
                Double x = hasX ? normalized(params, "x") : null;
                Double y = hasY ? normalized(params, "y") : null;
                return ReceiverApp.onMain(() -> {
                    long now = SystemClock.elapsedRealtime();
                    if (x != null && !app.catMode.pointer(x, y, now)) throw new ApiException("CAT_MODE_INACTIVE", 409);
                    if (!app.catMode.pounce(now)) throw new ApiException("CAT_MODE_INACTIVE", 409);
                    return app.catMode.json(now);
                });
            }
            case "cat.stop": return ReceiverApp.onMain(() -> {
                app.catMode.stop();
                return app.catMode.json(SystemClock.elapsedRealtime());
            });
            case "player.scrub": {
                // Mirrors a phone-side scrub: position plus the same preview frame the phone shows.
                if (!(params.opt("active") instanceof Boolean)) throw new ApiException("INVALID_INPUT");
                boolean active = params.getBoolean("active");
                double position = active ? MediaRequest.seconds(params.opt("positionSeconds")) : 0;
                double duration = params.has("durationSeconds") ? MediaRequest.seconds(params.opt("durationSeconds")) : 0;
                android.graphics.Bitmap frame = null;
                if (active && params.has("image")) {
                    String image = MediaRequest.label(params.opt("image"), 200_000);
                    if (image.startsWith("data:image/jpeg;base64,")) image = image.substring(23);
                    byte[] bytes;
                    try { bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT); }
                    catch (IllegalArgumentException invalid) { throw new ApiException("INVALID_INPUT"); }
                    if (bytes.length > 150_000) throw new ApiException("INVALID_INPUT");
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outWidth > 1280 || bounds.outHeight <= 0 || bounds.outHeight > 720) throw new ApiException("INVALID_INPUT");
                    frame = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
                if (active && frame == null) {
                    // Normal path: phones share frames ahead of time, so only positions arrive here.
                    String key = playback.contentKey();
                    byte[] stored = key == null ? null : app.thumbnails.nearest(key, (long) position);
                    if (stored != null) frame = android.graphics.BitmapFactory.decodeByteArray(stored, 0, stored.length);
                }
                return new JSONObject().put("shown", app.scrub(active, position, duration, frame));
            }
            case "thumbs.list": return app.thumbnails.list(thumbKey(params));
            case "thumbs.get": return app.thumbnails.get(thumbKey(params), params.optJSONArray("positions"));
            case "thumbs.put": return app.thumbnails.put(thumbKey(params), params.optJSONArray("frames"));
            case "app.diagnostics":
                return new JSONObject().put("current", app.diagnostics.tail(false, 40)).put("previous", app.diagnostics.tail(true, 40));
            case "app.snapshot": {
                String png = ReceiverApp.onMain(() -> {
                    android.graphics.Bitmap bitmap = app.snapshot();
                    if (bitmap == null) return null;
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    bitmap.recycle();
                    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
                });
                if (png == null) throw new ApiException("NO_UI", 409);
                return new JSONObject().put("png", png);
            }
            case "app.notice": {
                String text = MediaRequest.label(params.opt("text"), 160);
                int duration = params.has("durationMs") ? MediaRequest.integer(params.opt("durationMs"), 500, 60_000) : 3000;
                return new JSONObject().put("shown", app.notice(text, duration));
            }
            case "app.show":
                ReceiverApp.main().post(() -> { app.closeGames(); MainActivity.bringToFront(app); });
                return accepted();
            default: throw new ApiException("UNKNOWN_METHOD", 404);
        }
    }

    private static String thumbKey(JSONObject params) throws ApiException {
        String key = params.optString("contentKey");
        if (!SyncRecord.KEY.matcher(key).matches() && !key.matches("local:[a-f0-9]{32,64}")) throw new ApiException("INVALID_INPUT");
        return key;
    }

    private JSONObject info() throws Exception {
        PackageInfo pkg = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        return new JSONObject().put("app", "lumareceiver").put("protocol", PROTOCOL)
            .put("receiverId", app.receiverId()).put("name", Build.MODEL)
            .put("versionName", pkg.versionName).put("versionCode", Updater.versionCode(pkg))
            .put("installedAt", pkg.lastUpdateTime)
            .put("device", new JSONObject().put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("hardware", Build.HARDWARE)
                .put("abis", new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS))))
            .put("pairingOpen", app.pairing.activeCode() != null)
            .put("startup", app.startupStatus())
            .put("capabilities", new JSONArray(java.util.Arrays.asList("hls", "dash", "progressive", "subtitles.sideload",
                "tracks.audio", "tracks.text", "status.longpoll", "library.sync", "update.release", "update.dev", "volume", "display.picture", "display.subtitles", "scrub.mirror", "open.atomic", "thumbs.share", "player.next", "autoNext", "startup.diagnostics", "apps.launcher", "cat.mode", "games.nes", "games.sync", "input.gamepad", "input.calibrate", "input.remote")));
    }

    /** API coordinates are strict milli-normalized integers; drawing uses 0..1. */
    static double normalized(JSONObject params, String key) throws ApiException {
        Object raw = params.opt(key);
        if (!(raw instanceof Number)) throw new ApiException("INVALID_INPUT");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)
                || value < 0 || value > 1_000) throw new ApiException("INVALID_INPUT");
        return value / 1_000.0;
    }

    private JSONObject volume() throws Exception {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean muted = Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        return new JSONObject().put("current", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("muted", muted);
    }

    private static JSONObject body(HttpMessage request) throws ApiException {
        if (request.body.length == 0) return new JSONObject();
        try {
            JSONTokener tokens = new JSONTokener(new String(request.body, StandardCharsets.UTF_8));
            Object value = tokens.nextValue();
            if (!(value instanceof JSONObject) || tokens.nextClean() != 0) throw new ApiException("INVALID_JSON");
            return (JSONObject) value;
        } catch (ApiException rethrow) { throw rethrow; }
        catch (Exception invalid) { throw new ApiException("INVALID_JSON"); }
    }

    private static JSONObject accepted() throws Exception { return new JSONObject().put("accepted", true); }

    private static JSONObject error(String code) {
        try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code)); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    private static void reply(Socket socket, int status, JSONObject body) throws IOException {
        HttpMessage.write(socket.getOutputStream(), status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** mDNS `_lumareceiver._tcp` so clients can find the receiver without EShare. */
    private void advertise() {
        try {
            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName("LUMA Receiver " + app.receiverId().substring(5, 9));
            service.setServiceType("_lumareceiver._tcp");
            service.setPort(PORT);
            service.setAttribute("id", app.receiverId());
            service.setAttribute("protocol", Integer.toString(PROTOCOL));
            nsd = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo info, int code) { android.util.Log.w("LumaServer", "NSD_FAILED " + code); }
                @Override public void onUnregistrationFailed(NsdServiceInfo info, int code) { }
                @Override public void onServiceRegistered(NsdServiceInfo info) { }
                @Override public void onServiceUnregistered(NsdServiceInfo info) { }
            };
            ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).registerService(service, NsdManager.PROTOCOL_DNS_SD, nsd);
        } catch (Exception failure) { android.util.Log.w("LumaServer", "NSD_UNAVAILABLE"); }
    }
}
