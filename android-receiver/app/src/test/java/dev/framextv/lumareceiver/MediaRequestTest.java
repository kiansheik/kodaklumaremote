package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class MediaRequestTest {
    private static void rejects(String json, String code) throws Exception {
        try { MediaRequest.parse(new JSONObject(json)); fail("accepted"); }
        catch (ApiException expected) { assertEquals(code, expected.code); }
    }

    @Test public void infersTypesAndCanonicalEpisode() throws Exception {
        MediaRequest request = MediaRequest.parse(new JSONObject("{\"url\":\"https://cdn.example.com/a/master.m3u8?t=1\","
            + "\"content\":{\"kind\":\"tv\",\"contentId\":\"42\",\"season\":1,\"episode\":3,\"title\":\"Show · S1 E3\"},"
            + "\"resumeSeconds\":12.5,\"subtitles\":[{\"url\":\"http://192.168.1.5:4178/s/x\",\"language\":\"pt-BR\",\"select\":true}]}"));
        assertEquals(MediaRequest.HLS, request.contentType);
        assertEquals("framextv:tv:42:1:3", request.content.getString("key"));
        assertEquals("https://framextv.cc/watch/42/1/3", request.content.getString("watchUrl"));
        assertEquals("Show · S1 E3", request.title);
        assertEquals(12.5, request.resumeSeconds, 0);
        assertEquals("", request.subtitles.get(0).mimeType); // only a hint; the receiver sniffs the body
        assertTrue(request.subtitles.get(0).select);
        assertEquals("application/dash+xml", MediaRequest.contentType("", "https://x.example/m.mpd"));
        assertEquals(MediaRequest.HLS, MediaRequest.contentType("application/x-mpegURL; charset=x", "https://x.example/v"));
        assertEquals("video/mp4", MediaRequest.contentType("video/mp4; charset=binary", "https://x.example/signed-token"));
        assertEquals("video/webm", MediaRequest.contentType("video/webm", "https://x.example/signed-token"));
    }

    @Test public void movieHasNoEpisodeAndContentIsOptional() throws Exception {
        assertNull(MediaRequest.parse(new JSONObject("{\"url\":\"https://x.example/v.mp4\"}")).content);
        assertEquals("local:0123456789abcdef0123456789abcdef", MediaRequest.parse(new JSONObject(
            "{\"url\":\"http://192.168.1.5/v.mp4\",\"mediaId\":\"local:0123456789abcdef0123456789abcdef\"}")).mediaId);
        assertEquals("http://192.168.1.5/a.m4a", MediaRequest.parse(new JSONObject(
            "{\"url\":\"http://192.168.1.5/v.mkv\",\"audioUrl\":\"http://192.168.1.5/a.m4a\",\"mediaId\":\"local:0123456789abcdef0123456789abcdef\"}")).audioUrl);
        JSONObject movie = MediaRequest.canonicalContent(new JSONObject("{\"kind\":\"movie\",\"contentId\":\"7\",\"season\":null,\"episode\":null}"));
        assertEquals("framextv:movie:7", movie.getString("key"));
    }

    @Test public void animeProviderAndAudioEditionRemainCanonical() throws Exception {
        JSONObject content = MediaRequest.canonicalContent(new JSONObject("{\"kind\":\"tv\",\"contentId\":\"321518\",\"season\":1,\"episode\":3,\"animeId\":\"211778\",\"animeAudio\":\"dub\"}"));
        assertEquals("framextv:tv:321518:1:3", content.getString("key"));
        assertEquals("211778", content.getString("animeId"));
        assertEquals("dub", content.getString("animeAudio"));
        try { MediaRequest.canonicalContent(new JSONObject("{\"kind\":\"tv\",\"contentId\":\"321518\",\"season\":1,\"episode\":3,\"animeId\":\"211778\",\"animeAudio\":\"dual\"}")); fail("accepted invalid anime audio"); }
        catch (ApiException expected) { assertEquals("INVALID_CONTENT", expected.code); }
    }

    @Test public void rejectsUnsafeInput() throws Exception {
        rejects("{\"url\":\"file:///sdcard/x.mp4\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://user:pw@x.example/v.mp4\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4#frag\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"contentType\":\"text/html\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/embed/signed-token?expires=123\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/watch.html\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"headers\":{\"Cookie\":\"a\"}}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"content\":{\"kind\":\"movie\",\"contentId\":\"7\",\"key\":\"framextv:movie:8\"}}", "INVALID_CONTENT");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"content\":{\"kind\":\"tv\",\"contentId\":\"7\",\"season\":1,\"episode\":0}}", "INVALID_CONTENT");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"subtitles\":[{\"url\":\"file:///s.ass\"}]}", "INVALID_SUBTITLE");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"title\":\"bad\\u202Etitle\"}", "INVALID_INPUT");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"mediaId\":\"local:../../movie\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"http://192.168.1.5/v.mkv\",\"audioUrl\":\"http://192.168.1.6/a.m4a\",\"mediaId\":\"local:0123456789abcdef0123456789abcdef\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"http://192.168.1.5/v.mkv\",\"audioUrl\":\"http://192.168.1.5/a.m4a\"}", "INVALID_MEDIA");
    }
}
