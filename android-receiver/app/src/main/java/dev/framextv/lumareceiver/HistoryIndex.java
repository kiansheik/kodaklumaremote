package dev.framextv.lumareceiver;

import org.json.JSONObject;
import java.util.*;

/** Read-only grouping of canonical progress; the sync database remains authoritative. */
final class HistoryIndex {
    static final class Title {
        final String kind, id, name;
        final JSONObject latest;
        final List<JSONObject> progress = new ArrayList<>();
        Title(JSONObject row) {
            latest = row; kind = row.optString("kind"); id = row.optString("contentId");
            String title = row.optString("title").replaceFirst("\\s*·\\s*S[0-9]+\\s*E[0-9]+.*$", "").trim();
            name = title.isEmpty() ? ("tv".equals(kind) ? "Series " : "Movie ") + id : title;
        }
        JSONObject episode(int season, int episode) {
            for (JSONObject row : progress) if (row.optInt("season", -1) == season && row.optInt("episode", -1) == episode) return row;
            return null;
        }
    }
    static List<Title> group(List<JSONObject> rows) {
        List<JSONObject> valid = new ArrayList<>();
        for (JSONObject row : rows) try {
            MediaRequest.canonicalContent(row); valid.add(row);
        } catch (ApiException invalid) { /* Invalid sync data must not become a fetch target. */ }
        valid.sort((a,b) -> {
            int order = Long.compare(b.optLong("lastStartedAt", b.optLong("updatedAt")), a.optLong("lastStartedAt", a.optLong("updatedAt")));
            return order != 0 ? order : Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt"));
        });
        Map<String,Title> titles = new LinkedHashMap<>();
        for (JSONObject row : valid) {
            String key = row.optString("kind") + ":" + row.optString("contentId");
            Title title = titles.get(key);
            if (title == null) { title = new Title(row); titles.put(key, title); }
            title.progress.add(row);
        }
        return new ArrayList<>(titles.values());
    }
    static double resume(JSONObject row) {
        if (row == null || row.optBoolean("completed")) return 0;
        double position = row.optDouble("resumeSeconds", 0), duration = row.optDouble("durationSeconds", 0);
        if (!Double.isFinite(position) || position < 0 || position > 17_280_000) return 0;
        return duration > 0 && Double.isFinite(duration) ? Math.min(position, duration) : position;
    }
    static String progressLabel(JSONObject row) {
        if (row == null) return "Not watched";
        if (row.optBoolean("completed")) return "Watched";
        return resume(row) < 5 ? "Started" : "Resume at " + time(resume(row));
    }
    static String time(double seconds) {
        long value = (long)Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", value / 60, value % 60);
    }
}
