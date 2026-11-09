package dev.framextv.lumareceiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable state files: publish the new save before evicting any old one. */
final class CheckpointHistory {
    static final int LIMIT = 5;
    private final File directory;

    static final class Entry {
        final long id, savedAt;
        final File file;
        Entry(long id, File file) { this.id = id; this.file = file; savedAt = file.lastModified(); }
    }

    CheckpointHistory(File saves) { directory = new File(saves, "checkpoints"); }

    synchronized List<Entry> list() throws IOException {
        List<Entry> entries = all();
        return new ArrayList<>(entries.subList(0, Math.min(LIMIT, entries.size())));
    }

    private List<Entry> all() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!directory.exists()) return entries;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot read checkpoint history.");
        for (File file : files) {
            if (!file.isFile() || !file.getName().matches("checkpoint-[0-9]{19}\\.state")) continue;
            try { entries.add(new Entry(Long.parseLong(file.getName().substring(11, 30)), file)); }
            catch (NumberFormatException ignored) { }
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.id).reversed());
        return entries;
    }

    synchronized Entry save(byte[] bytes) throws IOException { return save(bytes, System.currentTimeMillis()); }

    synchronized Entry save(byte[] bytes, long savedAt) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024)
            throw new IOException("Invalid checkpoint size.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create checkpoint history.");
        List<Entry> existing = all();
        long id = Math.max(1, System.currentTimeMillis());
        if (!existing.isEmpty()) id = Math.max(id, Math.addExact(existing.get(0).id, 1));
        File destination = new File(directory, String.format(java.util.Locale.ROOT, "checkpoint-%019d.state", id));
        File pending = File.createTempFile("pending-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(bytes); output.getFD().sync();
            }
            if (!pending.setLastModified(savedAt)) throw new IOException("Cannot date checkpoint.");
            if (!pending.renameTo(destination)) throw new IOException("Cannot publish checkpoint.");
        } finally { if (pending.exists()) pending.delete(); }
        // A failed cleanup must never make the successfully committed save disappear.
        List<Entry> committed = all();
        for (int i = LIMIT; i < committed.size(); i++) committed.get(i).file.delete();
        return new Entry(id, destination);
    }

    synchronized File file(long id) throws IOException {
        for (Entry entry : list()) if (entry.id == id) return entry.file;
        throw new IOException("That checkpoint is no longer available.");
    }
}
