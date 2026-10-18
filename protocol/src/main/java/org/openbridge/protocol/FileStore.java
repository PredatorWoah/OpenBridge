package org.openbridge.protocol;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Whole-storage sharing over the filesystem (Android "All files access"). IDs are opaque and session scoped;
 * clients never send paths. Symbolic links are not followed, and every resolved file must stay inside a root.
 * Uploads go to a temporary sibling and are published with a move that fails if the name exists.
 */
public final class FileStore implements Store {
    /** A top-level storage area, such as internal storage or an SD card. */
    public static final class Root {
        final String name;
        final File directory;
        public Root(String name, File directory) { this.name = name; this.directory = directory; }
    }

    /** Makes previews of photos and videos; supplied by the platform (Android has the decoders). */
    public interface Thumbnails {
        byte[] make(File file, int size) throws IOException;
    }

    private static final int MAX_DOCUMENTS = 50_000;
    private final Thumbnails thumbnails;
    private final List<Root> roots = new ArrayList<>();
    private final List<Path> rootPaths = new ArrayList<>();
    private final Map<String, Path> documents = new HashMap<>();
    private final Map<Path, String> ids = new HashMap<>();
    private final Set<String> reserved = new HashSet<>();

    public FileStore(List<Root> roots) throws IOException { this(roots, null); }

    public FileStore(List<Root> roots, Thumbnails thumbnails) throws IOException {
        this.thumbnails = thumbnails;
        if (roots.isEmpty()) throw new IOException("No storage is available");
        for (Root root : roots) {
            this.roots.add(root);
            rootPaths.add(root.directory.toPath().toRealPath());
        }
    }

    @Override public synchronized List<Entry> list(String directoryId) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (directoryId.isEmpty() && roots.size() > 1) {
            // Several storage areas: show each as a folder at the top level.
            for (int i = 0; i < roots.size(); i++) entries.add(new Entry(id(rootPaths.get(i)), safeName(roots.get(i).name, i), true, 0));
            return entries;
        }
        Path directory = directory(directoryId);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (Names.isTemporary(name) || Files.isSymbolicLink(child)) continue;
                try { Wire.validateName(name); } catch (IOException unsupported) { continue; }
                boolean folder = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                if (!folder && !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue;
                if (entries.size() >= Wire.MAX_ENTRIES) throw new IOException("Folder has too many entries");
                entries.add(new Entry(id(child), name, folder, folder ? 0 : Files.size(child)));
            }
        } catch (AccessDeniedException e) {
            throw new IOException("Android does not allow access to this folder");
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.directory).thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return entries;
    }

    @Override public synchronized Read open(String fileId) throws IOException {
        Path file = resolve(fileId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Document is unavailable");
        long size = Files.size(file);
        if (size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
        InputStream input = Files.newInputStream(file);
        return new Read() {
            public long size() { return size; }
            public InputStream stream() { return input; }
            public void close() throws IOException { input.close(); }
        };
    }

    @Override public synchronized Upload create(String directoryId, String name) throws IOException {
        Names.requireStorable(name);
        if (directoryId.isEmpty() && roots.size() > 1) throw new IOException("Open a storage area first, then upload");
        Path directory = directory(directoryId);
        String reservation = directory + "/" + Names.collisionKey(name);
        if (reserved.contains(reservation) || exists(directory, name)) throw new IOException("Filename already exists");
        if (!Files.isWritable(directory)) throw new IOException("Folder is read-only");
        Path part = directory.resolve(Names.TEMPORARY_PREFIX + UUID.randomUUID());
        OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        reserved.add(reservation);
        return upload(directory, name, part, output, reservation, 0, false);
    }

    @Override public synchronized Upload resume(String directoryId, String name, long size, byte[] key) throws IOException {
        Names.requireStorable(name);
        if (directoryId.isEmpty() && roots.size() > 1) throw new IOException("Open a storage area first, then upload");
        Path directory = directory(directoryId);
        String reservation = directory + "/" + Names.collisionKey(name);
        if (reserved.contains(reservation) || exists(directory, name)) throw new IOException("Filename already exists");
        if (!Files.isWritable(directory)) throw new IOException("Folder is read-only");
        removeAbandoned(directory);
        Path part = directory.resolve(Names.resumable(name, size, key));
        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Upload is unavailable");
        long have = Files.exists(part, LinkOption.NOFOLLOW_LINKS) ? Files.size(part) : 0;
        if (have > size) { Files.delete(part); have = 0; }
        OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        reserved.add(reservation);
        return upload(directory, name, part, output, reservation, have, true);
    }

    /** Interrupted uploads in this folder that nobody continued for a week. */
    private static void removeAbandoned(Path directory) {
        long cutoff = System.currentTimeMillis() - ABANDONED_MILLIS;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory, Names.RESUMABLE_PREFIX + "*")) {
            for (Path child : children) {
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) Files.deleteIfExists(child);
            }
        } catch (IOException ignored) {}
    }

    /** Collects into `part`, then publishes it as `name` without ever replacing a file. `keep`: an interrupted part stays. */
    private Upload upload(Path directory, String name, Path part, OutputStream output, String reservation, long existing, boolean keep) {
        Path target = directory.resolve(name);
        return new Upload() {
            private boolean committed;
            public OutputStream stream() { return output; }
            public long existing() { return existing; }
            public void commit() throws IOException {
                synchronized (FileStore.this) {
                    if (exists(directory, name)) throw new IOException("Filename already exists");
                    try { Files.move(part, target); }   // without REPLACE_EXISTING: never overwrites
                    catch (FileAlreadyExistsException e) { throw new IOException("Filename already exists"); }
                    committed = true;
                }
            }
            public void close() throws IOException {
                synchronized (FileStore.this) {
                    try { output.close(); } finally {
                        if (!committed && !keep) Files.deleteIfExists(part);
                        reserved.remove(reservation);
                    }
                }
            }
        };
    }

    @Override public synchronized String createDirectory(String parentId, String name) throws IOException {
        Names.requireStorable(name);
        if (parentId.isEmpty() && roots.size() > 1) throw new IOException("Open a storage area first, then create a folder");
        Path directory = directory(parentId);
        if (exists(directory, name) || reserved.contains(directory + "/" + Names.collisionKey(name)))
            throw new IOException("A file or folder with that name already exists");
        if (!Files.isWritable(directory)) throw new IOException("Folder is read-only");
        try { return id(Files.createDirectory(directory.resolve(name))); }   // fails rather than reuse an existing name
        catch (FileAlreadyExistsException e) { throw new IOException("A file or folder with that name already exists"); }
    }

    @Override public synchronized byte[] thumbnail(String fileId, int size) throws IOException {
        Path file = resolve(fileId);
        if (thumbnails == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No preview");
        return thumbnails.make(file.toFile(), size);
    }

    @Override public synchronized void delete(String id) throws IOException {
        Path target = resolve(id);
        if (rootPaths.contains(target.toRealPath())) throw new IOException("The storage itself can't be deleted");
        // Deletes links themselves and never follows them, so nothing outside the folder is touched.
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
        forget(target);
    }

    @Override public synchronized String rename(String id, String name) throws IOException {
        Names.requireStorable(name);
        Path source = resolve(id);
        if (rootPaths.contains(source.toRealPath())) throw new IOException("The storage itself can't be renamed");
        String current = source.getFileName().toString();
        if (current.equals(name)) return id;
        Path directory = source.getParent();
        Path target = directory.resolve(name);
        boolean caseOnly = Names.collisionKey(current).equals(Names.collisionKey(name));
        if (!caseOnly && (exists(directory, name) || reserved.contains(directory + "/" + Names.collisionKey(name))))
            throw new IOException("A file or folder with that name already exists");
        if (caseOnly) {
            // Shared storage ignores case: go through a temporary name so "photo.JPG" can become "photo.jpg".
            Path step = directory.resolve(Names.TEMPORARY_PREFIX + UUID.randomUUID());
            Files.move(source, step);
            Files.move(step, target);
        } else {
            try { Files.move(source, target); }   // without REPLACE_EXISTING: never overwrites
            catch (FileAlreadyExistsException e) { throw new IOException("A file or folder with that name already exists"); }
        }
        forget(source);
        return id(target);
    }

    @Override public Store.Size measure(String directoryId, long deadline) throws IOException {
        Path directory;
        synchronized (this) {
            if (directoryId.isEmpty() && roots.size() > 1) return Store.super.measure(directoryId, deadline);
            directory = directory(directoryId);
        }
        // Walks the filesystem directly (no IDs handed out), skipping links, like the listing does.
        long[] totals = new long[3];
        boolean[] complete = {true};
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (++totals[2] > MEASURE_LIMIT || System.currentTimeMillis() > deadline) { complete[0] = false; return FileVisitResult.TERMINATE; }
                if (attributes.isRegularFile() && !Names.isTemporary(file.getFileName().toString())) { totals[0] += attributes.size(); totals[1]++; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
        });
        return new Store.Size(totals[0], totals[1], complete[0]);
    }

    /** Drops IDs for a path and everything below it, after it was deleted or moved. */
    private void forget(Path path) {
        ids.keySet().removeIf(known -> {
            boolean gone = known.startsWith(path);
            if (gone) documents.values().remove(known);
            return gone;
        });
    }

    private static boolean exists(Path directory, String name) throws IOException {
        // Shared storage ignores letter case, so compare names that way.
        String key = Names.collisionKey(name);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) if (Names.collisionKey(child.getFileName().toString()).equals(key)) return true;
        }
        return false;
    }

    private Path directory(String id) throws IOException {
        Path path = id.isEmpty() ? rootPaths.get(0) : resolve(id);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Document is unavailable");
        return path;
    }

    private Path resolve(String id) throws IOException {
        Path path = documents.get(id);
        if (path == null) throw new IOException("Unknown document; refresh the folder");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Document is unavailable");
        Path real = path.toRealPath();
        for (Path root : rootPaths) if (real.startsWith(root)) return path;
        throw new IOException("Document is unavailable");
    }

    private String id(Path path) throws IOException {
        String existing = ids.get(path);
        if (existing != null) return existing;
        if (documents.size() >= MAX_DOCUMENTS) throw new IOException("Restart sharing to refresh document IDs");
        String id = UUID.randomUUID().toString();
        ids.put(path, id);
        documents.put(id, path);
        return id;
    }

    private static String safeName(String name, int index) {
        try { Wire.validateName(name); return name; } catch (IOException e) { return "Storage " + (index + 1); }
    }
}
