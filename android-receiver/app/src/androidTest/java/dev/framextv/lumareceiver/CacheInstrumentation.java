package dev.framextv.lumareceiver;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** Isolated-emulator fixture: never run against a person's active receiver. */
public final class CacheInstrumentation extends Instrumentation {
    private ReceiverApp app;
    private String base;
    @Override public void onCreate(Bundle args) {base=args.getString("fixture","http://10.0.2.2:18765");start();}
    private void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private JSONObject status() throws Exception {return ReceiverApp.onMain(()->app.playback.status());}
    private void play(String path) throws Exception {
        MediaRequest request=MediaRequest.parse(new JSONObject().put("url",base+path).put("title","Cache fixture"));
        ReceiverApp.onMain(()->{app.playback.open(request,false,null,null);app.playback.setPlaying(false);return null;});
    }
    private void control(String path) throws Exception {
        java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(base+path).openConnection();
        try{require(c.getResponseCode()==200,"Fixture control failed");}finally{c.disconnect();}
    }
    private void test(String path,boolean adaptive) throws Exception {
        control("/control/allow");play(path);
        long until=SystemClock.elapsedRealtime()+90000;JSONObject p=null;
        while(SystemClock.elapsedRealtime()<until) {
            p=status();
            if(adaptive?p.optDouble("diskCacheAheadSeconds")>=120:"complete".equals(p.optString("diskCacheState")))break;
            SystemClock.sleep(500);
        }
        require(p.optBoolean("paused"),"Playback must remain paused");
        require(p.optLong("diskCachedBytes")>0,"Disk cache is empty");
        require(adaptive?p.optDouble("diskCacheAheadSeconds")>=120:"complete".equals(p.optString("diskCacheState")),"Paused prefetch did not advance: "+p.optString("diskCacheState")+" ahead="+p.optDouble("diskCacheAheadSeconds"));
        require(p.optInt("videoHeight")==1080,"High quality was not selected: "+p.optInt("videoHeight"));
        require(p.optLong("diskCachedBytes")<=p.optLong("diskCacheLimitBytes"),"Cache exceeded storage bound");
        // Disable upstream media, but leave manifests reachable. A seek well
        // outside the 20-second RAM window must read the prefetched disk data.
        control("/control/block");
        ReceiverApp.onMain(()->{app.playback.seek(90);app.playback.setPlaying(true);return null;});
        until=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<until){p=status();if(p.optDouble("positionSeconds")>92)break;SystemClock.sleep(500);}
        require(p.optDouble("positionSeconds")>92,"Cached seek could not play without upstream media");
        ReceiverApp.onMain(()->{app.playback.stop();return null;});
        until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until&&"clearing".equals(status().optString("diskCacheState")))SystemClock.sleep(100);
        require("off".equals(status().optString("diskCacheState")),"Cache did not clear on stop");
        Bundle report=new Bundle();report.putString("test",path);report.putString("result","paused preload, 1080p, offline media seek, cleanup passed");sendStatus(0,report);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"Emulator only");
            app=ReceiverApp.get(getTargetContext());
            test("/hls/index.m3u8",true);test("/dash/index.mpd",true);test("/movie.mp4",false);
            result.putString("result","PASS 3 cache formats");finish(-1,result);
        } catch(Throwable failure){result.putString("failure",failure.toString());finish(0,result);}
    }
}
