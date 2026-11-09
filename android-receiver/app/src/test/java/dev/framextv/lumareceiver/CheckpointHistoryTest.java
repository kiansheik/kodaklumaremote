package dev.framextv.lumareceiver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class CheckpointHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void retainsFiveNewestAndSurvivesRestart() throws Exception {
        File root = temp.newFolder();
        CheckpointHistory history = new CheckpointHistory(root);
        long oldest = history.save(new byte[]{0}).id;
        for (int i = 1; i <= 6; i++) history.save(new byte[]{(byte)i});
        history = new CheckpointHistory(root);
        List<CheckpointHistory.Entry> entries = history.list();
        assertEquals(5, entries.size());
        assertEquals(5, new File(root, "checkpoints").listFiles().length);
        for (int i = 0; i < 5; i++) assertArrayEquals(new byte[]{(byte)(6-i)}, Files.readAllBytes(history.file(entries.get(i).id).toPath()));
        try { history.file(oldest); fail("Evicted entry must not load"); } catch (IOException expected) { }
    }

    @Test public void selectingOlderSaveDoesNotChangeNewestOrDestroyOthers() throws Exception {
        CheckpointHistory history = new CheckpointHistory(temp.newFolder());
        long first = history.save(new byte[]{1}).id, second = history.save(new byte[]{2}).id;
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(history.file(first).toPath()));
        assertEquals(second, history.list().get(0).id);
        assertEquals(2, history.list().size());
    }

    @Test public void interruptedWriteIsNotListedOrLoaded() throws Exception {
        File root = temp.newFolder(); CheckpointHistory history = new CheckpointHistory(root);
        long id = history.save(new byte[]{7}).id;
        Files.write(new File(root, "checkpoints/pending-interrupted.tmp").toPath(), new byte[]{8});
        assertEquals(1, new CheckpointHistory(root).list().size());
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(history.file(id).toPath()));
    }

    @Test public void rejectedSavePreservesAllExistingCheckpoints() throws Exception {
        CheckpointHistory history = new CheckpointHistory(temp.newFolder());
        for (int i = 0; i < 5; i++) history.save(new byte[]{(byte)i});
        long latest = history.list().get(0).id;
        try { history.save(new byte[0]); fail(); } catch (IOException expected) { }
        try { history.save(new byte[1024 * 1024 + 1]); fail(); } catch (IOException expected) { }
        assertEquals(5, history.list().size()); assertEquals(latest, history.list().get(0).id);
    }

    @Test public void archiveKeepsOriginalSaveDateAndBytes() throws Exception {
        CheckpointHistory history = new CheckpointHistory(temp.newFolder());
        long saved = 1_600_000_000_000L;
        long archived = history.save(new byte[]{3, 4}, saved).id;
        history.save(new byte[]{5});
        assertEquals(saved, history.list().get(1).savedAt);
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(history.file(archived).toPath()));
    }

    @Test public void separateGamesDoNotShareHistory() throws Exception {
        CheckpointHistory first = new CheckpointHistory(temp.newFolder());
        CheckpointHistory second = new CheckpointHistory(temp.newFolder());
        first.save(new byte[]{1}); assertTrue(second.list().isEmpty());
    }
}
