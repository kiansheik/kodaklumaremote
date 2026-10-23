package dev.framextv.lumareceiver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One caption track the receiver downloads and renders itself. Selecting or
 * switching tracks never touches the media pipeline, so playback does not
 * rebuffer. Loading runs off the main thread; state fields are volatile.
 */
final class ExternalCaptions {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    final String url, language, label, mimeType;
    final Map<String, String> headers;
    final boolean selectOnLoad;
    private final CountDownLatch done = new CountDownLatch(1);
    volatile List<CaptionParser.Cue> cues;
    volatile String error;

    ExternalCaptions(MediaRequest.Subtitle subtitle, Map<String, String> headers) {
        url = subtitle.url; language = subtitle.language; label = subtitle.label; mimeType = subtitle.mimeType;
        selectOnLoad = subtitle.select; this.headers = headers;
    }

    String name(int ordinal) { return !label.isEmpty() ? label : !language.isEmpty() ? language : "Subtitles " + ordinal; }
    boolean loading() { return done.getCount() > 0; }
    boolean await(long millis) throws InterruptedException { return done.await(millis, TimeUnit.MILLISECONDS); }

    void load() {
        try {
            String contentType;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            URL target = new URL(url);
            for (int redirects = 0; ; redirects++) {
                HttpURLConnection connection = (HttpURLConnection) target.openConnection();
                connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", headers.containsKey("User-Agent") ? headers.get("User-Agent") : "LumaReceiver/1");
                for (Map.Entry<String, String> header : headers.entrySet()) if (!header.getKey().equals("User-Agent")) connection.setRequestProperty(header.getKey(), header.getValue());
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400 && redirects < 5 && connection.getHeaderField("Location") != null) {
                        target = new URL(target, connection.getHeaderField("Location"));
                        if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol())) throw new ApiException("SUBTITLE_DOWNLOAD", 422);
                        continue;
                    }
                    if (status != 200) throw new ApiException("SUBTITLE_DOWNLOAD", 422);
                    if (connection.getContentLengthLong() > MAX_BYTES) throw new ApiException("SUBTITLE_TOO_LARGE", 422);
                    contentType = connection.getContentType();
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[16 * 1024]; int count;
                        while ((count = in.read(buffer)) >= 0) {
                            if (body.size() + count > MAX_BYTES) throw new ApiException("SUBTITLE_TOO_LARGE", 422);
                            body.write(buffer, 0, count);
                        }
                    }
                    break;
                } finally { connection.disconnect(); }
            }
            String hint = mimeType.isEmpty() ? contentType : mimeType + (contentType != null && contentType.contains("charset") ? "; " + contentType : "");
            cues = CaptionParser.parse(body.toByteArray(), hint, language);
        } catch (ApiException failure) { error = failure.code; }
        catch (Exception failure) { error = "SUBTITLE_DOWNLOAD"; }
        finally {
            if (error != null) android.util.Log.w("LumaCaptions", "LOAD_FAILED " + error);
            done.countDown();
        }
    }

    String textAt(long positionMs) {
        List<CaptionParser.Cue> loaded = cues;
        return loaded == null ? "" : CaptionParser.textAt(loaded, positionMs);
    }
}
