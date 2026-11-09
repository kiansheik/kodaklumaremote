package dev.framextv.lumareceiver;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public final class LauncherCatalogTest {
    private static LauncherCatalog.Entry app(String pkg, String activity, String label, boolean leanback) {
        return new LauncherCatalog.Entry(pkg, activity, label, leanback);
    }

    @Test public void leanbackWinsPerPackageAndTheReceiverIsExcluded() {
        List<LauncherCatalog.Entry> result = LauncherCatalog.select(Arrays.asList(
            app("example.video", "example.video.Phone", "Video", false),
            app("example.music", "example.music.Main", "Audio", false),
            app("example.video", "example.video.Tv", "Video TV", true),
            app("dev.framextv.lumareceiver", "dev.framextv.lumareceiver.MainActivity", "Receiver", true)
        ), "dev.framextv.lumareceiver");
        assertEquals(2, result.size());
        assertEquals("example.music", result.get(0).packageName);
        assertEquals("example.video", result.get(1).packageName);
        assertEquals("example.video.Tv", result.get(1).activityName);
        assertTrue(result.get(1).leanback);
    }

    @Test public void inventoryIsSortedBoundedAndRejectsUnsafePackages() {
        List<LauncherCatalog.Entry> candidates = new ArrayList<>();
        candidates.add(app(null, "Missing", "Missing", false));
        candidates.add(app("bad", "Bad", "Bad", false));
        candidates.add(app("example.inject\ncommand", "Bad", "Bad", false));
        for (int index = LauncherCatalog.MAX_APPS + 4; index >= 0; index--)
            candidates.add(app("example.app" + index, "example.Activity" + index, String.format("App %03d", index), false));
        List<LauncherCatalog.Entry> result = LauncherCatalog.select(candidates, "example.self");
        assertEquals(LauncherCatalog.MAX_APPS, result.size());
        assertEquals("App 000", result.get(0).label);
        assertEquals("App 199", result.get(result.size() - 1).label);
        assertFalse(LauncherCatalog.validPackage("unqualified"));
        assertFalse(LauncherCatalog.validPackage("example.app\r\ncommand"));
        assertTrue(LauncherCatalog.validPackage("tv.crunchyroll.app"));
    }

    @Test public void labelsDropControlsAndStayBounded() {
        assertEquals("Crunchy roll", LauncherCatalog.safeLabel("  Crunchy\u0000 roll  "));
        assertEquals("Unnamed app", LauncherCatalog.safeLabel("\n\r\t"));
        assertEquals(100, LauncherCatalog.safeLabel("x".repeat(140)).length());
    }
}
