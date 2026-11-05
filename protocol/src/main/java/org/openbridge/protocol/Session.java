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
    private final Companion companion;
    private volatile Pairing.Scanned scanned;
    private final SecureRandom random = new SecureRandom();
    private String version = "unknown", device = "Phone";

    /** The highest operation number this phone answers, reported by INFO. */
    public static final int REVISION = 24;

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
        this(store, trust, fingerprint, approver, clipboard, outbox, null);
    }

    /**
     * Also answers EVENTS, NOTIFICATION ACTION, RING, OPEN URL, SEND and MEDIA CONTROL (operations 19 to 24) through
     * `companion` (null: they answer "Unsupported operation; update OpenBridge on the phone").
     */
    public Session(Store store, Trust trust, byte[] fingerprint, Pairing.Approver approver, Clipboard clipboard, Outbox outbox, Companion companion) {
        this.store = store; this.trust = trust; this.approver = approver; this.clipboard = clipboard; this.outbox = outbox; this.companion = companion;
        this.fingerprint = fingerprint == null ? null : fingerprint.clone();
    }

    /** What INFO reports: the phone app's version (like "1.0.0") and the phone's name. Returns this session. */
    public Session describe(String appVersion, String deviceName) {
        this.version = appVersion.length() > 64 ? appVersion.substring(0, 64) : appVersion;
        this.device = deviceName.length() > 128 ? deviceName.substring(0, 128) : deviceName;
        return this;
    }

    /**
     * Also pairs by QR code (OBP3) with the key the user scanned, held by `scanned` (null, the default: OBP3 answers
     * "Pairing is not available", and the computer falls back to the 6-digit code). Needs the certificate fingerprint
     * given to the constructor. Returns this session.
     */
    public Session scanning(Pairing.Scanned scanned) {
        this.scanned = scanned;
        return this;
    }

    /** How long MEASURE may count before answering with what it has. */
    static final long MEASURE_MILLIS = 5_000;
    /** The longest OUTBOX wait, safely under the 30-second socket timeouts on both sides. */
    static final int MAX_WAIT_MILLIS = 25_000;
    static final int MAX_OUTBOX = 1_000;
    /** How long SEARCH may look before answering with what it found. */
    static final long SEARCH_MILLIS = 8_000;
    /** The longest notification key NOTIFICATION ACTION takes as its ID (EVENTS keys); other IDs stay at 128 bytes. */
    static final int MAX_KEY = EventLog.MAX_KEY;
    static final int MAX_REPLY = 4_096;
    static final int MAX_URL = 2_048;
    private static final String UNSUPPORTED = "Unsupported operation; update OpenBridge on the phone";

    public void serve(InputStream input, OutputStream output) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        boolean responseStarted = false;
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (Arrays.equals(magic, Pairing.MAGIC3)) {
                Pairing.Scanned qr = scanned;
                if (qr == null || fingerprint == null) throw new IOException("Pairing is not available");
                String computer = Pairing.readName(in);
                byte[] nonce = new byte[Pairing.NONCE];
                in.readFully(nonce);
                byte[] key = qr.key();
                if (key == null || key.length != Pairing.KEY) throw new IOException("Scan the code on the computer first");
                responseStarted = true;   // pairing reports its own errors after the exchange begins
                Pairing.serveScanned(in, out, fingerprint, trust, computer, nonce, key, qr, random);
                return;
            }
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
            String computer = MessageDigest.isEqual(magic, Wire.MAGIC) ? trust.computer(supplied) : null;
            if (computer == null) throw new IOException("Pairing rejected");
            int operation = in.readUnsignedByte();
            String id = Wire.readString(in, operation == 20 ? MAX_KEY : 128);
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
                case 13:     // OUTBOX: files sent from the phone, waiting up to the given time for one
                case 17: {   // OUTBOX ALL: the same with texts, each item with its kind
                    if (outbox == null) throw new IOException("Sending from the phone is not available");
                    boolean texts = operation == 17;
                    int wait = in.readInt();
                    if (wait < 0 || wait > MAX_WAIT_MILLIS) throw new IOException("Invalid wait");
                    List<Outbox.Item> waiting;
                    try { waiting = outbox.waitFor(wait, texts); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Sharing stopped", e); }
                    List<Outbox.Item> items = new java.util.ArrayList<>();
                    for (Outbox.Item item : waiting)
                        if (items.size() < MAX_OUTBOX && (item.kind == Outbox.FILE || (texts && item.kind == Outbox.TEXT))) items.add(item);
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(items.size());
                    for (Outbox.Item item : items) {
                        Wire.writeString(out, item.id);
                        Wire.writeString(out, item.name);
                        if (texts) out.writeByte(item.kind);
                        out.writeLong(item.size);
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
                case 18: {   // INFO: the phone app's version, the highest operation it answers, and the phone's name
                    out.writeByte(0); responseStarted = true;
                    Wire.writeString(out, version);
                    out.writeInt(REVISION);
                    Wire.writeString(out, device);
                    break;
                }
                case 19: {   // EVENTS: what happened after the cursor, waiting up to the given time for something
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (!id.isEmpty()) throw new IOException("Invalid request");
                    long cursor = in.readLong();
                    int wait = in.readInt();
                    if (wait < 0 || wait > MAX_WAIT_MILLIS) throw new IOException("Invalid wait");
                    EventLog.Batch batch;
                    try { batch = companion.events(cursor, wait); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Sharing stopped", e); }
                    if (batch == null || batch.events.size() > EventLog.MAX_EVENTS) throw new IOException("Operation failed");
                    for (EventLog.Event event : batch.events)
                        if (event.kind < 0 || event.kind > 255 || event.body.length > EventLog.MAX_BODY) throw new IOException("Operation failed");
                    out.writeByte(0); responseStarted = true;
                    out.writeLong(batch.cursor);
                    out.writeInt(batch.events.size());
                    for (EventLog.Event event : batch.events) {
                        out.writeByte(event.kind);
                        out.writeInt(event.body.length);
                        out.write(event.body);
                    }
                    break;
                }
                case 20: {   // NOTIFICATION ACTION: dismiss, or reply with text
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (id.isEmpty()) throw new IOException("Notification is gone");
                    int action = in.readUnsignedByte();
                    if (action == 1) companion.dismiss(id);
                    else if (action == 2) {
                        String text = Wire.readString(in, MAX_REPLY);
                        if (text.isEmpty()) throw new IOException("Type a reply first");
                        companion.reply(id, text);
                    } else throw new IOException("Invalid notification action");
                    out.writeByte(0);
                    break;
                }
                case 21: {   // RING: 1 rings, 0 stops
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (!id.isEmpty()) throw new IOException("Invalid request");
                    int ring = in.readUnsignedByte();
                    if (ring > 1) throw new IOException("Invalid request");
                    companion.ring(ring == 1);
                    out.writeByte(0);
                    break;
                }
                case 22: {   // OPEN URL: a notification with the link and the computer's name
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (!id.isEmpty()) throw new IOException("Invalid request");
                    String url = Wire.readString(in, MAX_URL);
                    if (!webLink(url)) throw new IOException("Only http and https links can be opened");
                    companion.openUrl(url, computer);
                    out.writeByte(0);
                    break;
                }
                case 23: {   // SEND: like PUT_RESUMABLE into the inbox, under a free name; replies with the name it was saved under
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (!id.isEmpty()) throw new IOException("Invalid request");
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    long size = in.readLong();
                    if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                    byte[] key = new byte[32];
                    in.readFully(key);
                    String saved = null, location = null;
                    try (Store.Upload upload = store.receive(name, size, key)) {
                        long have = upload.existing();
                        if (have < 0 || have > size) throw new IOException("Invalid resume position");
                        out.writeByte(0); out.writeLong(have); out.flush(); responseStarted = true;
                        try (OutputStream destination = upload.stream()) {
                            Wire.copy(in, destination, size - have);
                        }
                        try {
                            upload.commit();
                            saved = upload.savedName();
                            location = upload.savedLocation();
                            if (saved == null) saved = name;
                            out.writeByte(0);
                            Wire.writeString(out, EventLog.cut(saved, 255));
                        } catch (IOException e) { saved = null; error(out, e); }
                    }
                    if (saved != null) {
                        out.flush();
                        try { companion.received(saved, location, computer); } catch (RuntimeException ignored) { /* the file is saved */ }
                    }
                    break;
                }
                case 24: {   // MEDIA CONTROL: play, pause, play/pause, next, previous, or seek to a position
                    if (companion == null) throw new IOException(UNSUPPORTED);
                    if (!id.isEmpty()) throw new IOException("Invalid request");
                    int action = in.readUnsignedByte();
                    if (action < Companion.PLAY || action > Companion.SEEK) throw new IOException("Invalid media action");
                    long position = -1;
                    if (action == Companion.SEEK) {
                        position = in.readLong();
                        if (position < 0) throw new IOException("Invalid position");
                    }
                    companion.media(action, position);
                    out.writeByte(0);
                    break;
                }
                default: throw new IOException(UNSUPPORTED);
            }
        } catch (IOException | SecurityException e) {
            if (responseStarted) throw new IOException("Transfer interrupted", e);
            error(out, e);
        } finally { out.flush(); }
    }

    /** An absolute http or https URL with a host, and nothing that could hide what it is (spaces or control characters). */
    static boolean webLink(String url) {
        if (url.isEmpty() || url.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.getType(c) == Character.FORMAT)) return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return false;
            // A name before "@" ("https://bank.com@example.net") is refused: it only serves to disguise the real host.
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0) return false;
            // International names (not punycode) leave no host for java.net.URI; the authority is then the host.
            return uri.getHost() != null ? !uri.getHost().isEmpty() : !authority.startsWith(":");
        } catch (java.net.URISyntaxException e) { return false; }
    }

    private static void error(DataOutputStream out, Exception error) throws IOException {
        out.writeByte(1);
        // Never disclose filesystem/provider paths or other exception details on the wire.
        String message = error instanceof SecurityException ? "Storage permission denied" : error.getMessage();
        if (message == null || message.length() > 120) message = "Operation failed";
        Wire.writeString(out, message);
    }
}
