package org.openbridge.protocol;

import java.io.*;
import java.util.*;

/** Capability-based test store; never uses host filesystem paths. */
public final class MemoryStore implements Store {
    public final Map<String, byte[]> files = new LinkedHashMap<>();
    public int aborted;
    public boolean failCommit;
    public MemoryStore() { files.put("hello.txt", "Hello from Android\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Override public synchronized List<Entry> list(String id) throws IOException {
        if (!id.isEmpty() && !id.equals("folder-id")) throw new IOException("Unknown directory");
        if (id.equals("folder-id")) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("folder-id", "Nested", true, 0));
        files.forEach((name, data) -> entries.add(new Entry("file:" + name, name, false, data.length)));
        return entries;
    }
    @Override public synchronized Read open(String id) throws IOException {
        if (!id.startsWith("file:") || !files.containsKey(id.substring(5))) throw new IOException("Unknown file");
        byte[] content = files.get(id.substring(5)).clone();
        return new Read() {
            private final InputStream stream = new ByteArrayInputStream(content);
            public long size() { return content.length; }
            public InputStream stream() { return stream; }
            public void close() throws IOException { stream.close(); }
        };
    }
    public final List<String> folders = new ArrayList<>();
    @Override public synchronized String createDirectory(String parentId, String name) throws IOException {
        if (!parentId.isEmpty()) throw new IOException("Unknown directory");
        if (folders.contains(name) || files.containsKey(name) || name.equals("Nested")) throw new IOException("A file or folder with that name already exists");
        folders.add(name);
        return "folder:" + name;
    }
    @Override public byte[] thumbnail(String id, int size) throws IOException {
        if (!id.equals("file:hello.txt")) throw new IOException("No preview");
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) size, (byte) 0xFF, (byte) 0xD9};
    }
    @Override public synchronized void delete(String id) throws IOException {
        if (!id.startsWith("file:") || files.remove(id.substring(5)) == null) throw new IOException("Unknown file");
    }
    @Override public synchronized String rename(String id, String name) throws IOException {
        if (!id.startsWith("file:") || !files.containsKey(id.substring(5))) throw new IOException("Unknown file");
        if (files.containsKey(name)) throw new IOException("A file or folder with that name already exists");
        files.put(name, files.remove(id.substring(5)));
        return "file:" + name;
    }
    @Override public synchronized Upload create(String id, String name) throws IOException {
        if (!id.isEmpty()) throw new IOException("Unknown directory");
        if (files.containsKey(name)) throw new IOException("Filename already exists");
        return new Upload() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            private boolean committed;
            public OutputStream stream() { return buffer; }
            public void commit() throws IOException {
                synchronized (MemoryStore.this) {
                    if (failCommit) throw new IOException("Storage full");
                    if (files.containsKey(name)) throw new IOException("Filename already exists");
                    files.put(name, buffer.toByteArray()); committed = true;
                }
            }
            public void close() { if (!committed) aborted++; }
        };
    }
}
