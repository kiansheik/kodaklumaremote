package dev.framextv.lumareceiver;

import android.app.Activity;
import android.graphics.Rect;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.WeakHashMap;

/** Focus fallback for app-translated controller events, which never reach ViewRootImpl. */
final class ControllerNavigation {
    private ControllerNavigation() { }
    private static final WeakHashMap<Activity, JSONObject> recent = new WeakHashMap<>();

    static boolean handle(Activity activity, int key, int action, int repeat) {
        String before = label(activity.getCurrentFocus());
        boolean handled = dispatch(activity, (ViewGroup) activity.getWindow().getDecorView(), key, action, repeat);
        if (handled && action == KeyEvent.ACTION_DOWN) try {
            recent.put(activity, new JSONObject().put("key", KeyEvent.keyCodeToString(key))
                .put("repeat", repeat).put("before", before).put("after", label(activity.getCurrentFocus())));
        } catch (Exception ignored) { }
        return handled;
    }

    static JSONObject snapshot(Activity activity) {
        JSONObject result = new JSONObject();
        try {
            View focus = activity.getCurrentFocus();
            result.put("focused", label(focus)).put("focusClass", focus == null ? "" : focus.getClass().getSimpleName())
                .put("touchMode", activity.getWindow().getDecorView().isInTouchMode());
            JSONObject last = recent.get(activity);
            if (last != null) result.put("lastNavigation", last);
        } catch (Exception ignored) { }
        return result;
    }

    /** Call after the screen/widget has had a chance to handle its own key semantics. */
    static boolean dispatch(Activity activity, ViewGroup scope, int key, int action, int repeat) {
        int direction = direction(key);
        boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (direction == 0 && !confirm) return false;
        if (action != KeyEvent.ACTION_DOWN) return true;
        View focused = activity.getCurrentFocus();
        if (!inside(scope, focused) || !usable(focused) || focused instanceof ViewGroup && !focused.isClickable()) focused = null;
        if (focused == null) {
            focused = firstFocusable(scope);
            if (focused == null || !focus(focused)) return true;
            // A direction establishes the first focus; confirmation also activates it.
            if (!confirm) return true;
        }
        if (confirm) {
            if (repeat == 0 && focused.isClickable()) focused.performClick();
            return true;
        }
        View next = focused.focusSearch(direction);
        if (!inside(scope, next) || !usable(next)) next = FocusFinder.getInstance().findNextFocus(scope, focused, direction);
        if (next instanceof ViewGroup && !next.isClickable()) {
            View child = firstFocusable((ViewGroup) next);
            if (child != null) next = child;
        }
        if (next != null && next != focused && inside(scope, next) && usable(next)) {
            focus(next);
            return true;
        }
        // Text-heavy help/license pages may have no next focus target but still scroll.
        for (ViewParent parent = focused.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof ScrollView && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                ((ScrollView) parent).arrowScroll(direction); break;
            }
            if (parent instanceof HorizontalScrollView && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
                ((HorizontalScrollView) parent).arrowScroll(direction); break;
            }
            if (parent == scope) break;
        }
        return true;
    }

    static int direction(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }
    private static boolean focus(View view) {
        if (!view.requestFocusFromTouch()) return false;
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        view.requestRectangleOnScreen(bounds, false);
        return true;
    }
    private static View firstFocusable(ViewGroup scope) {
        for (View candidate : scope.getFocusables(View.FOCUS_FORWARD))
            if (usable(candidate) && candidate.isClickable()) return candidate;
        for (View candidate : scope.getFocusables(View.FOCUS_FORWARD))
            if (usable(candidate) && (!(candidate instanceof ViewGroup) || candidate.isClickable())) return candidate;
        return null;
    }
    private static String label(View view) {
        if (view == null) return "";
        CharSequence value = view.getContentDescription();
        if ((value == null || value.length() == 0) && view instanceof TextView) value = ((TextView) view).getText();
        if ((value == null || value.length() == 0) && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String child = label(group.getChildAt(i));
                if (!child.isEmpty()) return child;
            }
        }
        String text = value == null ? "" : value.toString();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
    private static boolean usable(View view) {
        return view != null && view.isShown() && view.isEnabled() && view.isFocusable();
    }
    private static boolean inside(ViewGroup scope, View candidate) {
        if (candidate == null) return false;
        for (View view = candidate; view != null;) {
            if (view == scope) return true;
            ViewParent parent = view.getParent(); view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
}
