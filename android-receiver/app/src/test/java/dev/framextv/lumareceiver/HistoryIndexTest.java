package dev.framextv.lumareceiver;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class HistoryIndexTest {
    private JSONObject row(String kind,String id,int season,int episode,long started,long updated) throws Exception {
        return new JSONObject().put("kind",kind).put("contentId",id).put("season","tv".equals(kind)?(Object)season:JSONObject.NULL)
            .put("episode","tv".equals(kind)?(Object)episode:JSONObject.NULL).put("title","Sample · S"+season+" E"+episode)
            .put("lastStartedAt",started).put("updatedAt",updated);
    }
    @Test public void groupsTitlesByLatestViewingNotBackgroundSyncTime() throws Exception {
        JSONObject older=row("tv","42",1,1,100,900), recent=row("tv","42",1,2,300,300), movie=row("movie","42",0,0,200,800);
        List<HistoryIndex.Title> titles=HistoryIndex.group(Arrays.asList(older,movie,recent));
        assertEquals(2,titles.size()); assertEquals("tv",titles.get(0).kind);
        assertEquals("Sample",titles.get(0).name); assertSame(recent,titles.get(0).latest);
        assertSame(older,titles.get(0).episode(1,1)); assertNull(titles.get(0).episode(1,3));
    }
    @Test public void invalidIdentityNeverBecomesFetchTarget() throws Exception {
        JSONObject invalid=row("tv","42",1,1,1,1).put("key","framextv:tv:99:1:1");
        JSONObject injected=row("tv","42&other=1",1,1,1,1);
        assertTrue(HistoryIndex.group(Arrays.asList(invalid,injected)).isEmpty());
    }
    @Test public void resumeRespectsCompletionAndBounds() throws Exception {
        JSONObject item=new JSONObject().put("resumeSeconds",150).put("durationSeconds",100);
        assertEquals(100,HistoryIndex.resume(item),0);
        item.put("completed",true); assertEquals(0,HistoryIndex.resume(item),0);
        item.put("completed",false).put("resumeSeconds",-5); assertEquals(0,HistoryIndex.resume(item),0);
        assertEquals(0,HistoryIndex.resume(null),0);
    }
}
