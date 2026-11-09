package dev.framextv.lumareceiver;

import android.app.Activity;
import android.media.AudioManager;
import android.view.KeyEvent;
import org.json.JSONObject;

/** Paired LAN remote. No arbitrary keycodes or shell commands are accepted. */
final class RemoteInput {
    static int key(String name) {
        switch (name) {
            case "up": return 19; case "down": return 20; case "left": return 21; case "right": return 22;
            case "enter": return 23; case "back": return 4; case "home": return 3; case "menu": return 82;
            case "volume-up": return 24; case "volume-down": return 25; case "mute": return 164;
            default: return 0;
        }
    }
    static JSONObject send(ReceiverApp app, String name) throws Exception {
        int key = key(name);
        if (key == 0) throw new ApiException("INVALID_INPUT");
        String route;
        if (key == 24 || key == 25 || key == 164) {
            AudioManager audio = (AudioManager) app.getSystemService(android.content.Context.AUDIO_SERVICE);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, key == 24 ? AudioManager.ADJUST_RAISE
                : key == 25 ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            route = "receiver.audio";
        } else if (key == 3) {
            app.stopGame(); app.closeGames(); app.catMode.stop(); app.playback.stop();
            MainActivity.bringToFront(app); route = "receiver.home";
        } else {
            Activity activity = app.foregroundActivity;
            if (activity != null && activity.hasWindowFocus()) {
                long now = android.os.SystemClock.uptimeMillis();
                boolean handled = activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
                if (!handled) ControllerNavigation.handle(activity, key, KeyEvent.ACTION_DOWN, 0);
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
                route = "receiver.ui";
            } else if (ControllerSystemService.remoteKey(key)) route = "accessibility";
            else if (app.projectorKeys.ready() && app.projectorKeys.send(key, null)) route = "vendor.fallback";
            else throw new ApiException("REMOTE_ACTION_UNAVAILABLE");
        }
        return new JSONObject().put("accepted", true).put("route", route);
    }
}
