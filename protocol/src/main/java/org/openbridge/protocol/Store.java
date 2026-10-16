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
        /** Publish only after the entire body was received and the output stream closed. */
        void commit() throws IOException;
        /** Abort deletes partial content. Must also be safe after commit. */
        @Override void close() throws IOException;
    }
    List<Entry> list(String directoryId) throws IOException;
    Read open(String fileId) throws IOException;
    Upload create(String directoryId, String name) throws IOException;
}
