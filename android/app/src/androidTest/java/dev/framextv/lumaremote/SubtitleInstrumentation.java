package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Actual Android XML/charset providers and backend tickets; explicit emulator fixtures only. */
public final class SubtitleInstrumentation {
    private static final String SOURCE = "https://media.example/first.m3u8";
    private static final String ORIGINAL = "https://captions.example/subtitle";
    private static final String ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SRT = "1\n00:00:01,125 --> 00:00:03,500\nOlá, coração!\nSecond 😀\n";
    private final Instrumentation runner;
    private final Bundle args;
    private int checks;
    private String phase = "setup";
    private NativeBackend backend;
    private interface Action { void run() throws Exception; }

    private SubtitleInstrumentation(Instrumentation runner, Bundle args) { this.runner = runner; this.args = args; }
    public static void run(Instrumentation runner, Bundle args) { new SubtitleInstrumentation(runner, args).execute(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject object(Object... fields) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private void check(String name, Action action) throws Exception {
        phase = name; action.run(); checks++;
        Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", checks); runner.sendStatus(0, status);
    }
    private static SubtitleNormalizer.Result normalize(String value, String contentType) throws Exception {
        return SubtitleNormalizer.normalize(value.getBytes(StandardCharsets.UTF_8), contentType);
    }
    private static String srt(SubtitleNormalizer.Result result) { return new String(result.srt, StandardCharsets.UTF_8); }
    private static void rejectsFormat(String code, String text) throws Exception {
        try { normalize(text, null); throw new AssertionError("Normalization rejection missing: " + code); }
        catch (SubtitleNormalizer.NormalizeException error) { require(code.equals(error.code), "Normalization code: expected " + code + ", actual " + error.code); }
    }
    private JSONObject call(String method, Object... fields) throws Exception { return backend.handle(method, object(fields)); }
    private void rejectsBackend(String code, String method, Object... fields) throws Exception {
        try { call(method, fields); throw new AssertionError("Expected backend rejection"); }
        catch (NativeBackend.BackendException error) { require(code.equals(error.code), "Unexpected backend code: " + error.code); }
    }
    private void trustedResolver(String preparedUrl) {
        backend.setPreparedSubtitleResolver((id, source, original, peer) -> {
            // Like SubtitleDelivery: a ticket bound to another source/subtitle/peer never resolves.
            if (!SOURCE.equals(source)) throw NativeBackend.fail("SUBTITLE_EXPIRED");
            require(ID.equals(id) && ORIGINAL.equals(original) && args.getString("targetHost").equals(peer), "Prepared identity was not preserved");
            return preparedUrl;
        });
    }
    private void execute() {
        Bundle result = new Bundle();
        try {
            require(runner.getTargetContext().getPackageName().endsWith(".receiverfixture"), "Use the isolated receiver fixture APK");
            require("10.0.2.2".equals(args.getString("targetHost")), "Use the explicit emulator host bridge");
            check("Android sniffs actual SRT and WebVTT bodies", () -> {
                SubtitleNormalizer.Result mislabeled = normalize(SRT, "text/vtt");
                require("srt".equals(mislabeled.format) && mislabeled.cueCount == 1 && srt(mislabeled).equals(SRT + "\n"), "Mislabeled SRT changed Unicode or lines");
                SubtitleNormalizer.Result vtt = normalize("WEBVTT\n\nNOTE ignored\ncomment\n\ncue-id\n00:01.125 --> 00:03.500 align:start\n<v Ana><c.green>Olá</c>, coração!<00:02.000>\nSecond 😀\n", "application/x-subrip");
                require("vtt".equals(vtt.format) && srt(vtt).equals(SRT + "\n"), "WebVTT metadata/timing/text normalization failed");
            });
            check("Android decodes UTF16 BOM and declared legacy accents", () -> {
                for (String encoding : new String[] {"UTF-16LE", "UTF-16BE"}) {
                    byte[] encoded = SRT.getBytes(Charset.forName(encoding));
                    byte[] bytes = new byte[encoded.length + 2]; bytes[0] = (byte) (encoding.endsWith("LE") ? 0xff : 0xfe); bytes[1] = (byte) (encoding.endsWith("LE") ? 0xfe : 0xff);
                    System.arraycopy(encoded, 0, bytes, 2, encoded.length);
                    SubtitleNormalizer.Result normalized = SubtitleNormalizer.normalize(bytes, "text/vtt");
                    require(encoding.equals(normalized.encoding) && srt(normalized).equals(SRT + "\n"), "UTF16 BOM was not preserved correctly");
                }
                String legacy = "1\n00:00:01,000 --> 00:00:02,000\n“Coração” — café\n";
                SubtitleNormalizer.Result normalized = SubtitleNormalizer.normalize(legacy.getBytes(Charset.forName("windows-1252")), "text/plain; charset=windows-1252");
                require(srt(normalized).contains("“Coração” — café"), "Legacy accents were corrupted");
            });
            check("Android converts ASS dialogue with commas and multiline text", () -> {
                SubtitleNormalizer.Result normalized = normalize("[Script Info]\nScriptType: v4.00+\n[Events]\nFormat: End, Start, Text\nDialogue: 0:00:03.50,0:00:01.12,{\\an8}Olá, coração!\\NSecond 😀", "text/plain");
                require("ass".equals(normalized.format) && normalized.cueCount == 1 && srt(normalized).contains("00:00:01,120 --> 00:00:03,500\nOlá, coração!\nSecond 😀"), "ASS dialogue was not converted");
            });
            check("Android TTML SAX provider supports safe namespaced media time", () -> {
                String ttml = "<?xml version='1.0'?><t:tt xmlns:t='http://www.w3.org/ns/ttml' xmlns:ttp='http://www.w3.org/ns/ttml#parameter' ttp:timeBase='media'><t:body><t:div><t:p begin='1.125s' dur='2.375s'>Olá, <t:span>coração!</t:span><t:br/>Second &#x1F600;</t:p></t:div></t:body></t:tt>";
                String actual = srt(normalize(ttml, "application/ttml+xml"));
                require(actual.equals(SRT + "\n"), "TTML synthetic output: " + actual.replace("\n", "|").substring(0, Math.min(180, actual.length())));
                rejectsFormat("SUBTITLE_FORMAT", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///not-a-real-file'>]><tt><body><p begin='1s' end='2s'>&x;</p></body></tt>");
                rejectsFormat("SUBTITLE_FORMAT", "<tt><body><p begin='10f' end='20f'>Unsupported frame timing</p></body></tt>");
            });
            check("Android rejects error pages malformed captions and input overflow", () -> {
                rejectsFormat("SUBTITLE_FORMAT", "<!DOCTYPE html><html>Provider error</html>");
                rejectsFormat("SUBTITLE_FORMAT", "#EXTM3U\npart.vtt");
                rejectsFormat("SUBTITLE_INVALID", SRT + "\n2\nwrong timing\nHidden invalid cue\n");
                try { SubtitleNormalizer.normalize(new byte[SubtitleNormalizer.MAX_BYTES + 1], null); throw new AssertionError("Oversized input was accepted"); }
                catch (SubtitleNormalizer.NormalizeException error) { require("SUBTITLE_TOO_LARGE".equals(error.code), "Wrong size rejection"); }
            });
            check("Android salvages a subtitle with one isolated malformed provider cue", () -> {
                StringBuilder input = new StringBuilder("WEBVTT\n\n");
                for (int i = 0; i < 273; i++) input.append("00:00:01.000 --> 00:00:02.000\nGood ").append(i).append("\n\n");
                input.append("00:23:06.900 --> 00:00:00.000\nMalformed provider cue\n");
                SubtitleNormalizer.Result normalized = normalize(input.toString(), "text/vtt");
                require(normalized.cueCount == 273 && normalized.discardedCueCount == 1, "Isolated malformed cue was not bounded and reported");
            });
            backend = new NativeBackend(runner.getTargetContext()); backend.setSourceGuard(token -> "current".equals(token));
            int port = Integer.parseInt(args.getString("receiverPort"));
            String prepared = "http://10.0.2.2:" + port + "/prepared/first.srt";
            check("captured LAN URLs cannot bypass the public subtitle boundary", () -> {
                require(call("device.connect", "host", args.getString("targetHost")).getBoolean("connected"), "Fixture receiver connection failed");
                call("receiver.configure", "token", "fixture_receiver_token_0123456789", "port", port);
                rejectsBackend("INVALID_MEDIA", "receiver.addSubtitle", "track", object("url", prepared), "expectedSourceUrl", SOURCE);
                rejectsBackend("INVALID_MEDIA", "receiver.addSubtitle", "track", object("url", prepared), "preparedId", ID, "expectedSourceUrl", SOURCE);
            });
            check("only a trusted source and receiver bound ticket supplies a private prepared URL", () -> {
                trustedResolver(prepared);
                require(call("receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID, "expectedSourceUrl", SOURCE, "sourceToken", "current").getBoolean("accepted"), "Prepared subtitle Add was not accepted");
                rejectsBackend("INVALID_SUBTITLE", "receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", "short", "expectedSourceUrl", SOURCE);
                rejectsBackend("INVALID_SUBTITLE", "receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID);
                rejectsBackend("SUBTITLE_EXPIRED", "receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID, "expectedSourceUrl", "https://media.example/other.m3u8");
                // The receiver applies the forwarded source guard atomically with the write.
                rejectsBackend("SOURCE_CHANGED", "receiver.addSubtitle", "track", object("url", ORIGINAL), "expectedSourceUrl", "https://media.example/other.m3u8", "sourceToken", "current");
            });
            check("expired prepared ticket is a prewrite rejection and an explicit fresh request can succeed", () -> {
                backend.setPreparedSubtitleResolver((id, source, original, peer) -> { throw NativeBackend.fail("SUBTITLE_EXPIRED"); });
                rejectsBackend("SUBTITLE_EXPIRED", "receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID, "expectedSourceUrl", SOURCE, "sourceToken", "current");
                trustedResolver("http://10.0.2.2:" + port + "/prepared/second.srt");
                rejectsBackend("SOURCE_CHANGED", "receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID, "expectedSourceUrl", SOURCE, "sourceToken", "stale");
                require(call("receiver.addSubtitle", "track", object("url", ORIGINAL), "preparedId", ID, "expectedSourceUrl", SOURCE, "sourceToken", "current").getBoolean("accepted"), "Fresh explicit Add failed after expiry");
            });
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1);
            result.putString("failure", phase + ": " + (error instanceof NativeBackend.BackendException ? ((NativeBackend.BackendException) error).code : error.getClass().getSimpleName()));
            if (error instanceof AssertionError && error.getMessage() != null) result.putString("detail", error.getMessage().substring(0, Math.min(200, error.getMessage().length())));
            runner.finish(Activity.RESULT_CANCELED, result);
        } finally { if (backend != null) backend.close(); }
    }
}
