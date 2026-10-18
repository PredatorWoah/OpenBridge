package org.openbridge.protocol;

import java.io.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/** Blocking streaming handler, independent of Android and TLS implementation. */
public final class Session {
    private final Store store;
    private final Trust trust;
    private final byte[] fingerprint;
    private final Pairing.Approver approver;
    private final Clipboard clipboard;
    private final Outbox outbox;
    private final SecureRandom random = new SecureRandom();

    /** A session that only accepts clients that already hold the secret. */
    public Session(Store store, byte[] secret) { this(store, Trust.single(secret), null, null, null); }

    /**
     * A session that can also pair new clients (OBP1): `fingerprint` is the SHA-256 of this server's TLS
     * certificate, and `approver` shows the 6-digit code to the user.
     */
    public Session(Store store, byte[] secret, byte[] fingerprint, Pairing.Approver approver) {
        this(store, Trust.single(secret), fingerprint, approver, null);
    }

    /** Like the pairing constructor, and also shares `clipboard` (null: clipboard requests fail). */
    public Session(Store store, byte[] secret, byte[] fingerprint, Pairing.Approver approver, Clipboard clipboard) {
        this(store, Trust.single(secret), fingerprint, approver, clipboard);
    }

    /** Each paired computer has its own secret, kept by `trust`. */
    public Session(Store store, Trust trust, byte[] fingerprint, Pairing.Approver approver, Clipboard clipboard) {
        this(store, trust, fingerprint, approver, clipboard, null);
    }

    /** Also hands files sent from the phone to computers that ask (null: OUTBOX requests fail). */
    public Session(Store store, Trust trust, byte[] fingerprint, Pairing.Approver approver, Clipboard clipboard, Outbox outbox) {
        this.store = store; this.trust = trust; this.approver = approver; this.clipboard = clipboard; this.outbox = outbox;
        this.fingerprint = fingerprint == null ? null : fingerprint.clone();
    }

    /** How long MEASURE may count before answering with what it has. */
    static final long MEASURE_MILLIS = 5_000;
    /** The longest OUTBOX wait, safely under the 30-second socket timeouts on both sides. */
    static final int MAX_WAIT_MILLIS = 25_000;
    static final int MAX_OUTBOX = 1_000;
    /** How long SEARCH may look before answering with what it found. */
    static final long SEARCH_MILLIS = 8_000;

    public void serve(InputStream input, OutputStream output) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        boolean responseStarted = false;
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            boolean named = Arrays.equals(magic, Pairing.MAGIC2);
            if (named || Arrays.equals(magic, Pairing.MAGIC)) {
                if (approver == null || fingerprint == null) throw new IOException("Pairing is not available");
                String computer = named ? Pairing.readName(in) : "Computer";
                responseStarted = true;   // pairing reports its own errors after the exchange begins
                Pairing.serve(in, out, fingerprint, trust, computer, approver, random);
                return;
            }
            byte[] supplied = new byte[32];
            in.readFully(supplied);
            if (!MessageDigest.isEqual(magic, Wire.MAGIC) || trust.computer(supplied) == null) throw new IOException("Pairing rejected");
            int operation = in.readUnsignedByte();
            String id = Wire.readString(in, 128);
            switch (operation) {
                case 1: {
                    List<Store.Entry> entries = store.list(id);
                    if (entries.size() > Wire.MAX_ENTRIES) throw new IOException("Folder has too many entries");
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(entries.size());
                    for (Store.Entry entry : entries) {
                        Wire.writeString(out, entry.id);
                        Wire.writeString(out, entry.name);
                        out.writeBoolean(entry.directory);
                        out.writeLong(entry.size);
                    }
                    break;
                }
                case 2: {
                    try (Store.Read file = store.open(id)) {
                        long size = file.size();
                        if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                        out.writeByte(0); responseStarted = true;
                        out.writeLong(size);
                        Wire.copy(file.stream(), out, size);
                    }
                    break;
                }
                case 3: {
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    long size = in.readLong();
                    if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                    try (Store.Upload upload = store.create(id, name)) {
                        // Ready acknowledgement lets clients report collisions before sending data.
                        out.writeByte(0); out.flush(); responseStarted = true;
                        try (OutputStream destination = upload.stream()) {
                            Wire.copy(in, destination, size);
                        }
                        // Commit failures are sent as the second status, after the file body.
                        try {
                            upload.commit();
                            out.writeByte(0);
                        } catch (IOException e) { error(out, e); }
                    }
                    break;
                }
                case 4: {   // MKDIR: parent ID, then a name; replies with the new folder's ID
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    String created = store.createDirectory(id, name);
                    out.writeByte(0); responseStarted = true;
                    Wire.writeString(out, created);
                    break;
                }
                case 5: {   // THUMB: file ID, then the longest side in pixels; replies with a JPEG
                    int size = in.readInt();
                    if (size < 32 || size > 1024) throw new IOException("Invalid preview size");
                    byte[] jpeg = store.thumbnail(id, size);
                    if (jpeg == null || jpeg.length == 0 || jpeg.length > Wire.MAX_THUMBNAIL) throw new IOException("No preview");
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(jpeg.length);
                    out.write(jpeg);
                    break;
                }
                case 6: {   // GET from an offset, to resume a download: replies with the full size, then the rest
                    long offset = in.readLong();
                    try (Store.Read file = store.open(id)) {
                        long size = file.size();
                        if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                        if (offset < 0 || offset > size) throw new IOException("Invalid resume position");
                        InputStream stream = file.stream();
                        Wire.skip(stream, offset);
                        out.writeByte(0); responseStarted = true;
                        out.writeLong(size);
                        Wire.copy(stream, out, size - offset);
                    }
                    break;
                }
                case 7: {   // CLIPBOARD GET
                    if (clipboard == null) throw new IOException("Clipboard sharing is not available");
                    String text = clipboard.read();
                    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Wire.MAX_CLIPBOARD) throw new IOException("Clipboard is too large");
                    out.writeByte(0); responseStarted = true;
                    Wire.writeString(out, text);
                    break;
                }
                case 8: {   // CLIPBOARD SET
                    if (clipboard == null) throw new IOException("Clipboard sharing is not available");
                    String text = Wire.readString(in, Wire.MAX_CLIPBOARD);
                    clipboard.write(text);
                    out.writeByte(0);
                    break;
                }
                case 9: {   // DELETE: a file, or a folder and everything in it
                    store.delete(id);
                    out.writeByte(0);
                    break;
                }
                case 10: {   // RENAME: a new name; replies with the item's ID afterwards
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    String renamed = store.rename(id, name);
                    out.writeByte(0); responseStarted = true;
                    Wire.writeString(out, renamed);
                    break;
                }
                case 11: {   // MEASURE: total size and file count of a folder, and whether counting finished
                    Store.Size size = store.measure(id, System.currentTimeMillis() + MEASURE_MILLIS);
                    out.writeByte(0); responseStarted = true;
                    out.writeLong(size.bytes);
                    out.writeLong(size.files);
                    out.writeBoolean(size.complete);
                    break;
                }
                case 12: {   // PUT_RESUMABLE: like PUT; the ready status carries the bytes already received earlier
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    long size = in.readLong();
                    if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                    byte[] key = new byte[32];
                    in.readFully(key);
                    try (Store.Upload upload = store.resume(id, name, size, key)) {
                        long have = upload.existing();
                        if (have < 0 || have > size) throw new IOException("Invalid resume position");
                        out.writeByte(0); out.writeLong(have); out.flush(); responseStarted = true;
                        try (OutputStream destination = upload.stream()) {
                            Wire.copy(in, destination, size - have);
                        }
                        try {
                            upload.commit();
                            out.writeByte(0);
                        } catch (IOException e) { error(out, e); }
                    }
                    break;
                }
                case 13: {   // OUTBOX: files sent from the phone, waiting up to the given time for one
                    if (outbox == null) throw new IOException("Sending from the phone is not available");
                    int wait = in.readInt();
                    if (wait < 0 || wait > MAX_WAIT_MILLIS) throw new IOException("Invalid wait");
                    List<Store.Entry> waiting;
                    try { waiting = outbox.waitFor(wait); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Sharing stopped", e); }
                    int count = Math.min(waiting.size(), MAX_OUTBOX);
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(count);
                    for (Store.Entry entry : waiting.subList(0, count)) {
                        Wire.writeString(out, entry.id);
                        Wire.writeString(out, entry.name);
                        out.writeLong(entry.size);
                    }
                    break;
                }
                case 14: {   // OUTBOX GET from an offset: replies with the full size, then the rest
                    if (outbox == null) throw new IOException("Sending from the phone is not available");
                    long offset = in.readLong();
                    try (Store.Read file = outbox.open(id)) {
                        long size = file.size();
                        if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                        if (offset < 0 || offset > size) throw new IOException("Invalid resume position");
                        InputStream stream = file.stream();
                        Wire.skip(stream, offset);
                        out.writeByte(0); responseStarted = true;
                        out.writeLong(size);
                        Wire.copy(stream, out, size - offset);
                    }
                    break;
                }
                case 15: {   // OUTBOX DONE: the computer has the file
                    if (outbox == null) throw new IOException("Sending from the phone is not available");
                    outbox.done(id);
                    out.writeByte(0);
                    break;
                }
                case 16: {   // SEARCH: names containing a query, anywhere below a folder
                    String query = Wire.readString(in, 255).trim();
                    if (query.isEmpty()) throw new IOException("Type something to search for");
                    boolean[] complete = {true};
                    List<Store.Found> found = store.search(id, query, System.currentTimeMillis() + SEARCH_MILLIS, complete);
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(found.size());
                    for (Store.Found hit : found) {
                        Wire.writeString(out, hit.entry.id);
                        Wire.writeString(out, hit.entry.name);
                        out.writeBoolean(hit.entry.directory);
                        out.writeLong(hit.entry.size);
                        Wire.writeString(out, hit.location.length() > 1024 ? hit.location.substring(0, 1024) : hit.location);
                    }
                    out.writeBoolean(complete[0]);
                    break;
                }
                default: throw new IOException("Unsupported operation; update OpenBridge on the phone");
            }
        } catch (IOException | SecurityException e) {
            if (responseStarted) throw new IOException("Transfer interrupted", e);
            error(out, e);
        } finally { out.flush(); }
    }

    private static void error(DataOutputStream out, Exception error) throws IOException {
        out.writeByte(1);
        // Never disclose filesystem/provider paths or other exception details on the wire.
        String message = error instanceof SecurityException ? "Storage permission denied" : error.getMessage();
        if (message == null || message.length() > 120) message = "Operation failed";
        Wire.writeString(out, message);
    }
}
