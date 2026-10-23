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
    }

    @Test public void movieHasNoEpisodeAndContentIsOptional() throws Exception {
        assertNull(MediaRequest.parse(new JSONObject("{\"url\":\"https://x.example/v.mp4\"}")).content);
        JSONObject movie = MediaRequest.canonicalContent(new JSONObject("{\"kind\":\"movie\",\"contentId\":\"7\",\"season\":null,\"episode\":null}"));
        assertEquals("framextv:movie:7", movie.getString("key"));
    }

    @Test public void rejectsUnsafeInput() throws Exception {
        rejects("{\"url\":\"file:///sdcard/x.mp4\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://user:pw@x.example/v.mp4\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4#frag\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"contentType\":\"text/html\"}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"headers\":{\"Cookie\":\"a\"}}", "INVALID_MEDIA");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"content\":{\"kind\":\"movie\",\"contentId\":\"7\",\"key\":\"framextv:movie:8\"}}", "INVALID_CONTENT");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"content\":{\"kind\":\"tv\",\"contentId\":\"7\",\"season\":1,\"episode\":0}}", "INVALID_CONTENT");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"subtitles\":[{\"url\":\"file:///s.ass\"}]}", "INVALID_SUBTITLE");
        rejects("{\"url\":\"https://x.example/v.mp4\",\"title\":\"bad\\u202Etitle\"}", "INVALID_INPUT");
    }
}
