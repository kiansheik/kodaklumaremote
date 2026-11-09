package dev.framextv.lumareceiver;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class StreamRecoveryTest {
    @Test public void onlyCanonicalEpisodeIsRequestedAndRequestExpires() throws Exception {
        long[] now={0};StreamRecovery recovery=new StreamRecovery(()->now[0]);
        JSONObject content=new JSONObject().put("kind","tv").put("contentId","42").put("season",4).put("episode",2);
        recovery.request(content,123);
        assertEquals("https://framextv.cc/watch/42/4/2",recovery.snapshot().getJSONObject("content").getString("watchUrl"));
        assertEquals(123,recovery.snapshot().getDouble("resumeSeconds"),0);
        now[0]=300001;assertEquals(0,recovery.snapshot().length());
        content.put("watchUrl","https://other.example/");recovery.request(content,0);
        assertEquals(0,recovery.snapshot().length());
    }
    @Test public void cancellationClearsPhoneRequest() throws Exception {
        StreamRecovery recovery=new StreamRecovery(()->0);
        recovery.request(new JSONObject().put("kind","movie").put("contentId","42"),0);
        recovery.clear();assertEquals(0,recovery.snapshot().length());
    }
    @Test public void emptyPlaylistsAndErrorPagesDoNotPassProbe() {
        assertNull(StreamProbe.firstResource("#EXTM3U\n#EXT-X-ENDLIST\n"));
        assertNull(StreamProbe.firstResource("<html>not a playlist</html>"));
        assertEquals("segment.ts",StreamProbe.firstResource("#EXTM3U\n#EXTINF:6,\nsegment.ts\n"));
        assertFalse(StreamProbe.mediaBytes("<html>Access denied</html>".getBytes()));
        assertFalse(StreamProbe.mediaBytes("{\"error\":\"no media\"}".getBytes()));
    }
}
