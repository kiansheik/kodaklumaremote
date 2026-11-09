package dev.framextv.lumareceiver;
import org.json.JSONObject;

/** Short-lived, canonical request for a paired phone to open a failed episode's page. */
final class StreamRecovery {
    private final java.util.function.LongSupplier clock;
    StreamRecovery(){this(android.os.SystemClock::elapsedRealtime);}
    StreamRecovery(java.util.function.LongSupplier clock){this.clock=clock;}
    private JSONObject request;
    private long expiresAt;
    synchronized void request(JSONObject content,double resume) {
        try {
            JSONObject safe=MediaRequest.canonicalContent(content);
            request=new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("content",safe)
                .put("resumeSeconds",Double.isFinite(resume)?Math.max(0,Math.min(17_280_000,resume)):0);
            expiresAt=clock.getAsLong()+300_000;
        } catch(Exception invalid) { clear(); }
    }
    synchronized void clear() { request=null;expiresAt=0; }
    synchronized JSONObject snapshot() throws Exception {
        if(clock.getAsLong()>=expiresAt)clear();
        return request==null?new JSONObject():new JSONObject(request.toString());
    }
}
