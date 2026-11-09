package dev.framextv.lumareceiver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Controller-first watch library. Stored progress is offline; playable URLs are freshly resolved. */
public final class HistoryActivity extends Activity {
    private ReceiverApp app;
    private ControllerInput input;
    private LinearLayout root, rows;
    private TextView status;
    private Runnable back;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private Future<?> pending;
    private long generation;
    private boolean resumed, busy;
    private int titleCount, episodeCount;
    private String screen = "history", error = "";
    private final Map<String, JSONArray> episodeCache = new ConcurrentHashMap<>();

    static void show(Context context) {
        context.startActivity(new Intent(context, HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app = ReceiverApp.get(this); app.historyUi = this;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        input = new ControllerInput(this, new ControllerInput.Listener() {
            public void onButtonsChanged(int buttons) { }
            public void onNavigationKey(int key, int action, int repeat) {
                ControllerInput.prepareNavigation(HistoryActivity.this, action);
                if (key == KeyEvent.KEYCODE_BACK) { if (action == KeyEvent.ACTION_DOWN && repeat == 0) goBack(); return; }
                ControllerNavigation.handle(HistoryActivity.this,
                    key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? KeyEvent.KEYCODE_DPAD_CENTER : key, action, repeat);
            }
        });
    }
    @Override protected void onResume() { super.onResume(); resumed = true; input.start(); if (root == null || busy) showHistory(); }
    @Override protected void onPause() { resumed = false; cancel(); input.stop(); super.onPause(); }
    @Override protected void onDestroy() { if (app.historyUi == this) app.historyUi = null; input.close(); io.shutdownNow(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (!focused && input != null) input.clear(); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return input != null && input.onMotion(event) || super.dispatchGenericMotionEvent(event); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (input != null && input.onKey(event)) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) goBack(); return true; }
        if (ControllerNavigation.direction(key) != 0 || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER)
            return ControllerNavigation.handle(this, key, event.getAction(), event.getRepeatCount());
        return super.dispatchKeyEvent(event);
    }
    private void goBack() { cancel(); if (back != null) back.run(); else finish(); }
    private void cancel() { app.streamRecovery.clear(); generation++; if (pending != null) { pending.cancel(true); pending = null; } }
    private void page(String name, String title, String hint, Runnable backAction) {
        cancel(); busy = false; error = ""; screen = name; back = backAction; episodeCount = 0;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(32),dp(22),dp(32),dp(18)); root.setBackgroundColor(Color.rgb(8,19,27));
        root.addView(text(title, 28, Color.WHITE));
        status = text(hint, 16, Color.rgb(174,205,196)); root.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(0,dp(12),0,dp(12));
        scroll.addView(rows); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer = text("Stick / D-pad moves  ·  A selects  ·  B goes back  ·  Home returns to launcher",14,Color.LTGRAY);
        root.addView(footer); setContentView(root);
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0,dp(4),0,dp(4)); return view;
    }
    private Button button(String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setTextSize(18); view.setAllCaps(false);
        view.setTextColor(Color.WHITE); view.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        view.setPadding(dp(18),dp(12),dp(18),dp(12)); view.setBackgroundResource(R.drawable.launcher_tile);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(8); rows.addView(view,params);
        view.setOnClickListener(v -> action.run());
        if (rows.getChildCount()==1) view.requestFocusFromTouch(); return view;
    }
    private <T> void task(Callable<T> work, java.util.function.Consumer<T> done) { task(work,done,null); }
    private <T> void task(Callable<T> work, java.util.function.Consumer<T> done, java.util.function.Consumer<Exception> failure) {
        busy=true; long request=++generation;
        pending=io.submit(() -> {
            try { T result=work.call(); runOnUiThread(() -> { if (!resumed || request!=generation) return; busy=false; done.accept(result); }); }
            catch (Exception failed) { runOnUiThread(() -> {
                if (!resumed || request!=generation) return; busy=false;
                error=failed instanceof ApiException ? ((ApiException)failed).code : "CATALOG_UNAVAILABLE";
                if(failure!=null)failure.accept(failed);
                else status.setText("Couldn't load this right now. Your saved history is unchanged. Press B to try again.");
            }); }
        });
    }
    private void showHistory() {
        page("history","Watch History","Loading saved history…",this::finish);
        task(() -> HistoryIndex.group(app.library.historyProgress()), titles -> {
            titleCount=titles.size(); status.setText(titles.isEmpty() ? "No saved history yet. Open the phone app to sync your library."
                : "Recently watched · Choose a title to resume or browse episodes");
            for (HistoryIndex.Title title:titles) {
                JSONObject latest=title.latest;
                String episode="tv".equals(title.kind) ? " · S"+latest.optInt("season")+" E"+latest.optInt("episode") : "";
                button(title.name+episode+"\n"+HistoryIndex.progressLabel(latest),()->showTitle(title));
            }
            button("Refresh history",this::showHistory);
            button("Back to Home",this::finish);
        });
    }
    private void showTitle(HistoryIndex.Title title) {
        page("title",title.name,"Saved progress · "+title.progress.size()+" watched item(s)",this::showHistory);
        JSONObject latest=title.latest;
        String episode="tv".equals(title.kind) ? "S"+latest.optInt("season")+" E"+latest.optInt("episode") : "movie";
        button((HistoryIndex.resume(latest)>5 ? "Resume " : "Play ")+episode+" · "+HistoryIndex.progressLabel(latest),()->play(title,latest,false,false,()->showTitle(title)));
        if ("tv".equals(title.kind)) {
            button("Play next episode after "+episode,()->play(title,latest,true,false,()->showTitle(title)));
            button("Browse seasons and episodes",()->showSeasons(title));
        }
        button("Play "+episode+" from beginning",()->play(title,latest,false,true,()->showTitle(title)));
        button("Back to Watch History",this::showHistory);
    }
    private void showSeasons(HistoryIndex.Title title) {
        page("seasons",title.name,"Loading seasons…",()->showTitle(title));
        final boolean[] savedOnly = {false};
        task(() -> {
            Set<Integer> seasons=new TreeSet<>();
            for(JSONObject row:title.progress) seasons.add(row.optInt("season"));
            try { seasons.addAll(new Catalog().seasons(title.id)); }
            catch(Exception unavailable) { if(seasons.isEmpty()) throw unavailable; savedOnly[0]=true; }
            return seasons;
        }, seasons -> {
            status.setText(savedOnly[0] ? "Catalog unavailable · Showing seasons from saved history" : "Choose a season");
            for(int season:seasons) button(season==0 ? "Specials" : "Season "+season,()->showEpisodes(title,season));
            button("Back to title",()->showTitle(title));
        });
    }
    private void showEpisodes(HistoryIndex.Title title,int season) {
        page("episodes",title.name+" · Season "+season,"Loading episodes…",()->showSeasons(title));
        task(() -> {
            String key=title.id+":"+season; JSONArray list=episodeCache.get(key);
            if(list!=null) return list;
            try { list=new Catalog().historyEpisodes(title.id,season); episodeCache.put(key,list); return list; }
            catch(Exception unavailable) {
                list=new JSONArray();
                for(JSONObject row:title.progress) if(row.optInt("season")==season) list.put(new JSONObject()
                    .put("number",row.optInt("episode")).put("title","Episode "+row.optInt("episode")).put("savedOnly",true));
                if(list.length()==0) throw unavailable; return list;
            }
        }, episodes -> {
            status.setText(episodes.length()>0 && episodes.optJSONObject(0).optBoolean("savedOnly")
                ? "Catalog unavailable · Showing saved episodes" : "Choose an episode · Watched and resume positions come from your synced library");
            TreeMap<Integer,String> entries=new TreeMap<>();
            for(int i=0;i<episodes.length();i++) { JSONObject row=episodes.optJSONObject(i); if(row!=null) entries.put(row.optInt("number"),row.optString("title")); }
            episodeCount=entries.size();
            for(Map.Entry<Integer,String> entry:entries.entrySet()) {
                int number=entry.getKey(); JSONObject saved=title.episode(season,number);
                button("E"+number+" · "+entry.getValue()+"\n"+HistoryIndex.progressLabel(saved),()->showEpisode(title,season,number,entry.getValue()));
            }
            if(entries.isEmpty()) status.setText("No episodes are available in this season.");
            button("Back to seasons",()->showSeasons(title));
        });
    }
    private void showEpisode(HistoryIndex.Title title,int season,int episode,String label) {
        try {
            JSONObject saved=title.episode(season,episode);
            JSONObject target=saved==null ? new JSONObject(title.latest.toString()) : new JSONObject(saved.toString());
            target.remove("key"); target.remove("watchUrl"); target.put("season",season).put("episode",episode);
            if(saved==null) { target.remove("resumeSeconds"); target.remove("completed"); target.remove("durationSeconds"); }
            page("episode",title.name+" · S"+season+" E"+episode,label+" · "+HistoryIndex.progressLabel(saved),()->showEpisodes(title,season));
            button(HistoryIndex.resume(saved)>5 ? "Resume at "+HistoryIndex.time(HistoryIndex.resume(saved)) : "Play episode",
                ()->play(title,target,false,false,()->showEpisode(title,season,episode,label)));
            if(HistoryIndex.resume(saved)>5) button("Play from beginning",()->play(title,target,false,true,()->showEpisode(title,season,episode,label)));
            button("Back to episodes",()->showEpisodes(title,season));
        } catch(Exception invalid) { status.setText("This saved episode is unavailable."); }
    }
    private void play(HistoryIndex.Title title,JSONObject target,boolean next,boolean restart,Runnable returnTo) {
        long source=app.playback.sourceGeneration();
        app.streamRecovery.clear();
        java.util.concurrent.atomic.AtomicReference<JSONObject> recoveryTarget=new java.util.concurrent.atomic.AtomicReference<>();
        page("opening",title.name,next ? "Finding the next episode…" : "Finding a fresh playable source…",returnTo);
        button("Cancel",returnTo);
        task(() -> {
            JSONObject media;
            JSONObject selected=MediaRequest.canonicalContent(target);
            if(next) {
                Catalog.Episode episode=new Catalog().next(selected);
                if(episode==null)return null;
                selected.remove("key");selected.remove("watchUrl");selected.put("season",episode.season).put("episode",episode.episode);
            }
            recoveryTarget.set(selected);
            {
                media=new Catalog().resolveContent(selected);
                JSONObject progress=next?app.library.get(MediaRequest.canonicalContent(selected).getString("key")):target;
                if(!restart) media.put("resumeSeconds",HistoryIndex.resume(progress));
                JSONArray captions=PlaybackController.captionCandidates(media.optJSONArray("tracks"),app.preferences.getString("captionLanguage",""));
                if(captions.length()>0) media.put("subtitles",captions);
                media.remove("tracks");
            }
            return media==null ? null : MediaRequest.parse(media);
        }, request -> {
            if(request==null) { status.setText("There are no further episodes available."); return; }
            if(source!=app.playback.sourceGeneration()) { status.setText("Playback changed while loading. Go back and select again."); return; }
            try {
                app.playback.noteInteraction(); app.catMode.stop(); app.stopGame(); app.closeGames();
                app.playback.open(request,false,null,null); finish();
            } catch(Exception failed) { error="PLAYBACK_FAILED"; status.setText("Couldn't start playback. Press B to try again."); }
        }, failed -> {
            JSONObject selected=recoveryTarget.get();
            if(selected!=null&&source==app.playback.sourceGeneration()) {
                app.streamRecovery.request(selected,restart||next?0:HistoryIndex.resume(target));
                status.setText("No working stream responded. Open LUMA on your connected phone and choose Open episode to try its video page, then Cast.");
                button("Retry on projector",()->play(title,target,next,restart,returnTo));
            } else status.setText("Couldn't find the next episode. Press B to go back.");
        });
    }
    JSONObject snapshot() throws Exception {
        return ControllerNavigation.snapshot(this).put("active",true).put("screen",screen).put("busy",busy)
            .put("titleCount",titleCount).put("episodeCount",episodeCount).put("error",error);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
