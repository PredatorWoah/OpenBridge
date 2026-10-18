package org.openbridge.protocol;

import java.io.*;
import java.util.List;

/** Storage capability boundary. IDs are opaque and scoped to this sharing session. */
public interface Store {
    final class Entry {
        public final String id, name;
        public final boolean directory;
        public final long size;
        public Entry(String id, String name, boolean directory, long size) {
            this.id = id; this.name = name; this.directory = directory; this.size = size;
        }
    }
    interface Read extends AutoCloseable {
        long size();
        InputStream stream();
        @Override void close() throws IOException;
    }
    interface Upload extends AutoCloseable {
        OutputStream stream();
        /** Bytes already received by an earlier, interrupted upload of this file; the stream continues after them. */
        default long existing() { return 0; }
        /** Publish only after the entire body was received and the output stream closed. */
        void commit() throws IOException;
        /** Abort deletes partial content. Must also be safe after commit. */
        @Override void close() throws IOException;
    }
    List<Entry> list(String directoryId) throws IOException;
    Read open(String fileId) throws IOException;
    Upload create(String directoryId, String name) throws IOException;

    /**
     * Like {@link #create}, but an interrupted upload is kept so the same file (same name and size, same folder) can
     * continue later from where it stopped. Stores that can't keep one start over every time.
     */
    default Upload resume(String directoryId, String name, long size, byte[] key) throws IOException { return create(directoryId, name); }

    /** Interrupted uploads nobody continued are removed after this long. */
    long ABANDONED_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** Creates a new, empty folder and returns its ID. Never reuses or replaces an existing name. */
    default String createDirectory(String parentId, String name) throws IOException {
        throw new IOException("This phone can't create folders");
    }

    /** A small JPEG preview of a photo or video, at most `size` pixels on its longer side. */
    default byte[] thumbnail(String fileId, int size) throws IOException {
        throw new IOException("No preview");
    }

    /** Deletes a file, or a folder with everything in it. The shared storage itself can't be deleted. */
    default void delete(String id) throws IOException {
        throw new IOException("This phone can't delete files; update OpenBridge on the phone");
    }

    /** Renames a file or folder where it is, never to a name that exists. Returns its ID afterwards. */
    default String rename(String id, String name) throws IOException {
        throw new IOException("This phone can't rename files; update OpenBridge on the phone");
    }

    /** What a folder holds, counted until `deadline` (System.currentTimeMillis) or MEASURE_LIMIT items. */
    final class Size {
        public final long bytes, files;
        public final boolean complete;
        public Size(long bytes, long files, boolean complete) { this.bytes = bytes; this.files = files; this.complete = complete; }
    }
    int MEASURE_LIMIT = 50_000;

    /** A search hit: the item, and the folder it's in by name relative to where the search started ("DCIM/Camera"). */
    final class Found {
        public final Entry entry;
        public final String location;
        public Found(Entry entry, String location) { this.entry = entry; this.location = location; }
    }
    int SEARCH_LIMIT = 500;

    /**
     * Files and folders below `directoryId` whose names contain `query` (ignoring case), up to SEARCH_LIMIT, until
     * `deadline`. `complete` is set false when it stopped early. Walks through {@link #list}; stores may do better.
     */
    default List<Found> search(String directoryId, String query, long deadline, boolean[] complete) throws IOException {
        List<Found> found = new java.util.ArrayList<>();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        java.util.ArrayDeque<String[]> pending = new java.util.ArrayDeque<>();
        pending.add(new String[] {directoryId, ""});
        long seen = 0;
        complete[0] = true;
        while (!pending.isEmpty()) {
            String[] folder = pending.removeFirst();
            for (Entry entry : list(folder[0])) {
                if (++seen > MEASURE_LIMIT || System.currentTimeMillis() > deadline || found.size() >= SEARCH_LIMIT) { complete[0] = false; return found; }
                if (entry.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) found.add(new Found(entry, folder[1]));
                if (entry.directory) pending.add(new String[] {entry.id, folder[1].isEmpty() ? entry.name : folder[1] + "/" + entry.name});
            }
        }
        return found;
    }

    /** Adds up a folder through {@link #list}; stores override it to count without handing out IDs. */
    default Size measure(String directoryId, long deadline) throws IOException {
        long[] totals = new long[3];   // bytes, files, items seen
        boolean complete = measure(this, directoryId, deadline, totals);
        return new Size(totals[0], totals[1], complete);
    }

    static boolean measure(Store store, String directoryId, long deadline, long[] totals) throws IOException {
        for (Entry entry : store.list(directoryId)) {
            if (++totals[2] > MEASURE_LIMIT || System.currentTimeMillis() > deadline) return false;
            if (entry.directory) { if (!measure(store, entry.id, deadline, totals)) return false; }
            else { totals[0] += entry.size; totals[1]++; }
        }
        return true;
    }
}
