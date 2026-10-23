package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

public class CaptionCandidatesTest {
    private static JSONArray tracks() throws Exception {
        return new JSONArray("[{\"url\":\"https://s.example.com/1\",\"language\":\"en\"},"
        + "{\"url\":\"https://s.example.com/2\",\"language\":\"pt\"},{\"url\":\"https://s.example.com/3\",\"language\":\"pt-br\"},"
        + "{\"url\":\"https://s.example.com/4\",\"language\":\"en\"},{\"url\":\"https://s.example.com/5\",\"language\":\"en\"},"
        + "{\"url\":\"https://s.example.com/6\",\"language\":\"en\"}]");
    }

    @Test public void exactLanguageFirstThenBaseLanguage() throws Exception {
        JSONArray result = PlaybackController.captionCandidates(tracks(), "pt-BR");
        assertEquals(2, result.length());
        assertEquals("https://s.example.com/3", result.getJSONObject(0).getString("url"));
        assertEquals("https://s.example.com/2", result.getJSONObject(1).getString("url"));
        assertEquals(true, result.getJSONObject(0).getBoolean("select"));
    }

    @Test public void atMostThreeAndNothingWithoutALanguage() throws Exception {
        assertEquals(3, PlaybackController.captionCandidates(tracks(), "en").length());
        assertEquals(0, PlaybackController.captionCandidates(tracks(), "").length());
        assertEquals(0, PlaybackController.captionCandidates(tracks(), "he").length());
    }
}
