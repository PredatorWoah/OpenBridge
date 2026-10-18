package org.openbridge.protocol;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.HexFormat;

/** Dependency-free executable tests. Run with scripts/test-protocol.sh. */
public final class ProtocolTest {
    static final byte[] SECRET = new byte[32];
    static { Arrays.fill(SECRET, (byte) 0x5a); }
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] request(int operation, String id, String name, long length, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(Wire.MAGIC); out.write(SECRET); out.writeByte(operation); Wire.writeString(out, id);
        if (name != null) { Wire.writeString(out, name); out.writeLong(length); out.write(body); }
        return bytes.toByteArray();
    }
    private static DataInputStream serve(MemoryStore store, byte[] request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Session(store, SECRET).serve(new ByteArrayInputStream(request), output);
        return new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
    }
    private static void rejected(MemoryStore store, byte[] request) throws IOException {
        DataInputStream result = serve(store, request);
        check(result.readByte() == 1, "Expected error status");
        check(!Wire.readString(result, 512).isEmpty(), "Expected error text");
    }
    public static void main(String[] args) throws Exception {
        MemoryStore store = new MemoryStore();
        DataInputStream listing = serve(store, request(1, "", null, 0, null));
        check(listing.readByte() == 0 && listing.readInt() == 2, "List root");
        check(Wire.readString(listing, 128).equals("folder-id"), "Opaque folder ID");
        check(Wire.readString(listing, 255).equals("Nested") && listing.readBoolean(), "Directory metadata");
        listing.readLong();
        check(Wire.readString(listing, 128).equals("file:hello.txt"), "File ID");
        check(Wire.readString(listing, 255).equals("hello.txt") && !listing.readBoolean(), "File metadata");
        check(listing.readLong() == store.files.get("hello.txt").length, "File size");
        DataInputStream nested = serve(store, request(1, "folder-id", null, 0, null));
        check(nested.readByte() == 0 && nested.readInt() == 0, "Browse nested folder");

        byte[] body = new byte[200_003]; new Random(17).nextBytes(body);
        DataInputStream uploaded = serve(store, request(3, "", "résumé.bin", body.length, body));
        check(uploaded.readByte() == 0 && uploaded.readByte() == 0, "Upload ready and committed");
        check(Arrays.equals(store.files.get("résumé.bin"), body), "Upload bytes exact across chunks");
        DataInputStream downloaded = serve(store, request(2, "file:résumé.bin", null, 0, null));
        check(downloaded.readByte() == 0 && downloaded.readLong() == body.length, "Download header");
        check(Arrays.equals(downloaded.readAllBytes(), body), "Download bytes exact");
        DataInputStream zero = serve(store, request(3, "", "empty", 0, new byte[0]));
        check(zero.readByte() == 0 && zero.readByte() == 0 && store.files.get("empty").length == 0, "Empty file");
        rejected(store, request(3, "", "hello.txt", 0, new byte[0]));
        check(new String(store.files.get("hello.txt"), StandardCharsets.UTF_8).equals("Hello from Android\n"), "Collision preserves original");
        for (String name : new String[] {"", ".", "..", "../outside", "/etc/passwd", "a/b", "a\\b", "a\u0000b", "a\nb", "x".repeat(256)})
            rejected(store, request(3, "", name, 0, new byte[0]));
        for (String id : new String[] {"..", "../../etc", "/etc/passwd", "file:hello.txt"})
            rejected(store, request(1, id, null, 0, null));
        rejected(store, request(2, "../hello.txt", null, 0, null));
        rejected(store, request(3, "", "large", Wire.MAX_FILE + 1, new byte[0]));
        rejected(store, request(3, "", "negative", -1, new byte[0]));
        rejected(store, request(255, "", null, 0, null));

        byte[] badSecret = request(1, "", null, 0, null); badSecret[4] ^= 1;
        rejected(store, badSecret);
        byte[] badMagic = request(1, "", null, 0, null); badMagic[0] = 'X';
        rejected(store, badMagic);
        for (int length : new int[] {0, 1, 4, 16, 35, 36})
            rejected(store, Arrays.copyOf(request(1, "", null, 0, null), length));
        byte[] invalidLength = request(1, "", null, 0, null);
        Arrays.fill(invalidLength, 37, 41, (byte) 0xff);
        rejected(store, invalidLength);
        byte[] invalidUtf8 = request(1, "a", null, 0, null); invalidUtf8[41] = (byte) 0xff;
        rejected(store, invalidUtf8);
        try {
            serve(store, request(3, "", "partial", 10, new byte[] {1, 2}));
            throw new AssertionError("Truncation should close the stream");
        } catch (IOException expected) { check(!store.files.containsKey("partial") && store.aborted == 1, "Abort deletes partial upload"); }
        store.failCommit = true;
        DataInputStream failed = serve(store, request(3, "", "full-disk", 0, new byte[0]));
        check(failed.readByte() == 0 && failed.readByte() == 1, "Commit failure has second error status");
        check(Wire.readString(failed, 512).equals("Storage full") && !store.files.containsKey("full-disk"), "Commit failure not published");
        Names.requireStorable("résumé (final) #2.txt");
        for (String name : new String[] {"what?.pdf", "a:b", "a*b", "a\"b", "a<b", "a>b", "a|b", ".openbridge-part-x", "", "a/b"}) {
            try { Names.requireStorable(name); throw new AssertionError("Unstorable name accepted: " + name); }
            catch (IOException expected) { check(expected.getMessage().length() <= 120, "Storable-name error fits the wire"); }
        }
        check(Names.collisionKey("README.TXT").equals(Names.collisionKey("readme.txt")), "Case-insensitive collision key");
        check(Names.isTemporary(".openbridge-part-123") && !Names.isTemporary("openbridge-part-123"), "Temporary prefix");
        for (String address : new String[] {"10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1"})
            check(Wire.isPrivateIPv4(InetAddress.getByName(address)), "Private IP " + address);
        for (String address : new String[] {"0.0.0.0", "127.0.0.1", "8.8.8.8", "172.32.0.1", "169.254.1.1", "100.64.0.1", "::1", "fd00::1"})
            check(!Wire.isPrivateIPv4(InetAddress.getByName(address)), "Reject non-RFC1918 " + address);
        for (String name : new String[] {"rndis0", "usb0", "ncm0", "ncm12"})
            check(Wire.isUsbTetherInterface(name), "USB tether interface " + name);
        for (String name : new String[] {"wlan0", "rmnet_data0", "tun0", "lo", "ncm", "usb", "rndis0x", "eth0", null})
            check(!Wire.isUsbTetherInterface(name), "Not a USB tether interface " + name);
        pairingTests(store);
        extensionTests();
        fileStoreTests();
        trustTests();
        editingTests();
        resumeAndOutboxTests();
        System.out.println("Protocol tests passed: " + checks + " assertions");
    }

    private static DataOutputStream header(ByteArrayOutputStream bytes, int operation, String id) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(Wire.MAGIC); out.write(SECRET); out.writeByte(operation); Wire.writeString(out, id);
        return out;
    }
    private static DataInputStream serve(Session session, ByteArrayOutputStream request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        session.serve(new ByteArrayInputStream(request.toByteArray()), output);
        return new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
    }

    /** MKDIR, THUMB, ranged GET and the clipboard (operations 4 to 8). */
    private static void extensionTests() throws Exception {
        MemoryStore store = new MemoryStore();
        String[] clip = {"from the phone"};
        Clipboard clipboard = new Clipboard() {
            public String read() { return clip[0]; }
            public void write(String text) { clip[0] = text; }
        };
        Session session = new Session(store, SECRET, null, null, clipboard);
        byte[] hello = store.files.get("hello.txt");

        for (long offset : new long[] {0, 6, hello.length}) {
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            header(request, 6, "file:hello.txt").writeLong(offset);
            DataInputStream reply = serve(session, request);
            check(reply.readByte() == 0 && reply.readLong() == hello.length, "Resume reports the full size");
            check(Arrays.equals(reply.readAllBytes(), Arrays.copyOfRange(hello, (int) offset, hello.length)), "Resume sends the rest from " + offset);
        }
        for (long offset : new long[] {-1, hello.length + 1}) {
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            header(request, 6, "file:hello.txt").writeLong(offset);
            DataInputStream reply = serve(session, request);
            check(reply.readByte() == 1 && Wire.readString(reply, 512).contains("resume"), "Bad resume position rejected: " + offset);
        }

        ByteArrayOutputStream mkdir = new ByteArrayOutputStream();
        Wire.writeString(header(mkdir, 4, ""), "Holiday");
        DataInputStream made = serve(session, mkdir);
        check(made.readByte() == 0 && Wire.readString(made, 128).equals("folder:Holiday") && store.folders.contains("Holiday"), "Create folder");
        DataInputStream again = serve(session, mkdir);
        check(again.readByte() == 1 && Wire.readString(again, 512).contains("already exists"), "Existing folder name refused");
        ByteArrayOutputStream badName = new ByteArrayOutputStream();
        Wire.writeString(header(badName, 4, ""), "../up");
        check(serve(session, badName).readByte() == 1, "Folder name validated");

        ByteArrayOutputStream thumb = new ByteArrayOutputStream();
        header(thumb, 5, "file:hello.txt").writeInt(256);
        DataInputStream preview = serve(session, thumb);
        check(preview.readByte() == 0 && preview.readInt() == 5, "Preview length");
        byte[] jpeg = new byte[5]; preview.readFully(jpeg);
        check(jpeg[0] == (byte) 0xFF && jpeg[2] == (byte) 256, "Preview bytes");
        for (int size : new int[] {0, 31, 1025}) {
            ByteArrayOutputStream bad = new ByteArrayOutputStream();
            header(bad, 5, "file:hello.txt").writeInt(size);
            check(serve(session, bad).readByte() == 1, "Preview size bounded: " + size);
        }
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        header(none, 5, "folder-id").writeInt(256);
        check(serve(session, none).readByte() == 1, "No preview for folders");

        ByteArrayOutputStream get = new ByteArrayOutputStream();
        header(get, 7, "");
        DataInputStream read = serve(session, get);
        check(read.readByte() == 0 && Wire.readString(read, Wire.MAX_CLIPBOARD).equals("from the phone"), "Read the phone clipboard");
        ByteArrayOutputStream set = new ByteArrayOutputStream();
        Wire.writeString(header(set, 8, ""), "from the Mac ✓");
        check(serve(session, set).readByte() == 0 && clip[0].equals("from the Mac ✓"), "Write the phone clipboard");
        ByteArrayOutputStream huge = new ByteArrayOutputStream();
        DataOutputStream hugeOut = header(huge, 8, "");
        hugeOut.writeInt(Wire.MAX_CLIPBOARD + 1);
        check(serve(session, huge).readByte() == 1 && clip[0].equals("from the Mac ✓"), "Oversized clipboard refused");
        check(serve(new Session(store, SECRET), get).readByte() == 1, "No clipboard unless shared");

        ByteArrayOutputStream unknown = new ByteArrayOutputStream();
        header(unknown, 99, "");
        DataInputStream refused = serve(session, unknown);
        check(refused.readByte() == 1 && Wire.readString(refused, 512).contains("update"), "Unknown operation asks for an update");

        ByteArrayOutputStream wrongSecret = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(wrongSecret);
        w.write(Wire.MAGIC); w.write(new byte[32]); w.writeByte(7); Wire.writeString(w, "");
        check(serve(session, wrongSecret).readByte() == 1 && clip[0].equals("from the Mac ✓"), "Clipboard needs the pairing secret");
    }

    private static void pairingTests(MemoryStore store) throws Exception {
        byte[] fingerprint = new byte[32]; Arrays.fill(fingerprint, (byte) 1);
        byte[] macNonce = new byte[32]; Arrays.fill(macNonce, (byte) 2);
        byte[] phoneNonce = new byte[32]; Arrays.fill(phoneNonce, (byte) 3);
        // Reference vector shared with the Swift and Python implementations.
        check(Pairing.code(fingerprint, macNonce, phoneNonce).equals("520380"), "Pairing code vector");
        check(HexFormat.of().formatHex(Pairing.commitment(fingerprint, phoneNonce))
            .equals("8ef8a25a4293066adbb289a0cff4a91b7e65d0db9811cd15d30f14d2c0a9b356"), "Commitment vector");
        byte[] otherFingerprint = fingerprint.clone(); otherFingerprint[0] ^= 1;
        check(!Pairing.code(otherFingerprint, macNonce, phoneNonce).equals("520380"), "Code depends on the certificate");
        for (int round = 0; round < 50; round++) {
            byte[] n = new byte[32]; new Random(round).nextBytes(n);
            check(Pairing.code(fingerprint, n, phoneNonce).matches("[0-9]{6}"), "Six digits");
        }

        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(Pairing.MAGIC); request.write(macNonce);
        for (boolean approve : new boolean[] {true, false}) {
            String[] shown = new String[1];
            Session pairing = new Session(store, SECRET, fingerprint, code -> { shown[0] = code; return approve; });
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            pairing.serve(new ByteArrayInputStream(request.toByteArray()), output);
            DataInputStream reply = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            check(reply.readByte() == 0, "Pairing starts");
            byte[] commitment = new byte[32]; reply.readFully(commitment);
            byte[] revealed = new byte[32]; reply.readFully(revealed);
            check(Arrays.equals(commitment, Pairing.commitment(fingerprint, revealed)), "Phone nonce matches its commitment");
            check(Pairing.code(fingerprint, macNonce, revealed).equals(shown[0]), "Phone shows the code the Mac computes");
            if (approve) {
                check(reply.readByte() == 0, "Approved");
                byte[] secret = new byte[32]; reply.readFully(secret);
                check(Arrays.equals(secret, SECRET), "Secret released only after approval");
            } else {
                check(reply.readByte() == 1 && Wire.readString(reply, 512).contains("declined"), "Declined pairing reports it");
                check(reply.available() == 0, "No secret after a decline");
            }
        }
        // A server without pairing refuses before revealing anything.
        DataInputStream refused = serve(store, request.toByteArray());
        check(refused.readByte() == 1 && Wire.readString(refused, 512).contains("not available"), "Pairing unavailable");
        check(refused.available() == 0, "Nothing else sent");
    }

    /** Remembered pairing: OBP2 names the computer, each computer gets its own secret, and only those work. */
    private static void trustTests() throws Exception {
        byte[] fingerprint = new byte[32]; Arrays.fill(fingerprint, (byte) 7);
        Map<String, byte[]> given = new LinkedHashMap<>();
        Trust trust = new Trust() {
            public String computer(byte[] secret) {
                for (Map.Entry<String, byte[]> entry : given.entrySet())
                    if (java.security.MessageDigest.isEqual(entry.getValue(), secret)) return entry.getKey();
                return null;
            }
            public byte[] admit(String computer) {
                byte[] secret = new byte[32]; new Random(computer.hashCode()).nextBytes(secret);
                given.put(computer, secret);
                return secret;
            }
        };
        String[] asked = new String[1];
        Pairing.Approver approver = new Pairing.Approver() {
            public boolean approve(String code) { return false; }
            @Override public boolean approve(String code, String computer) { asked[0] = computer; return true; }
        };
        Session session = new Session(new MemoryStore(), trust, fingerprint, approver, null);
        byte[][] secrets = new byte[2][];
        String[] names = {"Aditya\u2019s MacBook Air\n", "\u0007"};
        for (int i = 0; i < 2; i++) {
            ByteArrayOutputStream pairing = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(pairing);
            out.write(Pairing.MAGIC2); out.write(Pairing.nameBlock(names[i])); out.write(new byte[32]);
            DataInputStream reply = serve(session, pairing.toByteArray());
            check(reply.readByte() == 0, "OBP2 pairing starts");
            reply.readFully(new byte[64]);
            check(reply.readByte() == 0, "OBP2 approved");
            secrets[i] = new byte[32]; reply.readFully(secrets[i]);
        }
        check(given.containsKey("Aditya\u2019s MacBook Air") && given.containsKey("Computer"), "Names cleaned before showing: " + given.keySet());
        check(asked[0].equals("Computer"), "The phone is asked with the computer's name");
        check(!Arrays.equals(secrets[0], secrets[1]), "Each computer gets its own secret");
        for (byte[] secret : secrets) {
            ByteArrayOutputStream list = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(list);
            out.write(Wire.MAGIC); out.write(secret); out.writeByte(1); Wire.writeString(out, "");
            check(serve(session, list.toByteArray()).readByte() == 0, "A remembered computer is let in");
        }
        given.remove("Computer");   // forgotten on the phone
        ByteArrayOutputStream forgotten = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(forgotten);
        out.write(Wire.MAGIC); out.write(secrets[1]); out.writeByte(1); Wire.writeString(out, "");
        DataInputStream refused = serve(session, forgotten.toByteArray());
        check(refused.readByte() == 1 && Wire.readString(refused, 512).equals("Pairing rejected"), "A forgotten computer is refused");
        byte[] broken = Pairing.nameBlock("ok"); broken[2] = (byte) 0xC3;   // a cut UTF-8 sequence
        DataInputStream invalid = serve(session, concat(Pairing.MAGIC2, broken));
        check(invalid.readByte() == 1, "Invalid computer name refused");
        String long_ = "\u00e9".repeat(100);   // 200 bytes: cut at a character boundary
        check(new String(Pairing.nameBlock(long_), StandardCharsets.UTF_8).replace("\u0000", "").equals("\u00e9".repeat(64)), "Long names cut cleanly");

        // A phone that predates OBP2 reads the name block as a wrong secret and answers at once.
        // (Before 0.11.0 the phone handled any magic but OBP1 with Wire.authenticate, which reads 32 bytes.)
        try {
            Wire.authenticate(new DataInputStream(new ByteArrayInputStream(Pairing.nameBlock("Mac"))), Pairing.MAGIC2, SECRET);
            check(false, "OBP2 accepted by an old phone");
        } catch (IOException expected) { check(expected.getMessage().equals("Pairing rejected"), "Old phones answer OBP2 straight away"); }
    }

    private static byte[] concat(byte[] a, byte[] b) { byte[] c = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, c, a.length, b.length); return c; }

    /** DELETE, RENAME and MEASURE, through the handler and on the filesystem store. */
    private static void editingTests() throws Exception {
        MemoryStore store = new MemoryStore();
        store.files.put("old.txt", new byte[] {1, 2, 3});
        DataInputStream renamed = serve(store, simple(10, "file:old.txt", out -> Wire.writeString(out, "new.txt")));
        check(renamed.readByte() == 0 && Wire.readString(renamed, 128).equals("file:new.txt"), "Rename replies with the new ID");
        check(store.files.containsKey("new.txt") && !store.files.containsKey("old.txt"), "Renamed");
        check(serve(store, simple(10, "file:new.txt", out -> Wire.writeString(out, "hello.txt"))).readByte() == 1, "Rename never takes an existing name");
        for (String bad : new String[] {"", "..", "a/b", "x".repeat(256)})
            check(serve(store, simple(10, "file:new.txt", out -> Wire.writeString(out, bad))).readByte() == 1, "Rename rejects " + bad);
        check(serve(store, simple(9, "file:new.txt", out -> {})).readByte() == 0 && !store.files.containsKey("new.txt"), "Delete");
        check(serve(store, simple(9, "file:new.txt", out -> {})).readByte() == 1, "Deleting twice fails");
        DataInputStream measured = serve(store, simple(11, "", out -> {}));
        check(measured.readByte() == 0, "Measure");
        long bytes = measured.readLong(), count = measured.readLong();
        check(bytes == store.files.get("hello.txt").length && count == 1 && measured.readBoolean(), "Measure through the listing: " + bytes + "/" + count);

        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("openbridge-edit");
        try {
            java.nio.file.Path internal = java.nio.file.Files.createDirectories(base.resolve("internal"));
            java.nio.file.Path camera = java.nio.file.Files.createDirectories(internal.resolve("DCIM/Camera"));
            java.nio.file.Files.write(camera.resolve("a.jpg"), new byte[1000]);
            java.nio.file.Files.write(camera.resolve("b.jpg"), new byte[2500]);
            java.nio.file.Files.writeString(internal.resolve("Photo.JPG"), "x");
            java.nio.file.Path outside = java.nio.file.Files.createDirectories(base.resolve("outside"));
            java.nio.file.Files.writeString(outside.resolve("keep.txt"), "keep");
            try { java.nio.file.Files.createSymbolicLink(camera.resolve("link"), outside); } catch (Exception ignored) {}
            FileStore files = new FileStore(List.of(new FileStore.Root("Internal storage", internal.toFile())));
            Map<String, Store.Entry> top = byName(files.list(""));
            Store.Size size = files.measure(top.get("DCIM").id, System.currentTimeMillis() + 5_000);
            check(size.bytes == 3500 && size.files == 2 && size.complete, "Folder size counts files below, not links: " + size.bytes);
            check(!files.measure(top.get("DCIM").id, 0).complete, "Measuring stops at the deadline");

            String renamedId = files.rename(top.get("Photo.JPG").id, "photo.jpg");
            check(byName(files.list("")).containsKey("photo.jpg") && !byName(files.list("")).containsKey("Photo.JPG"), "Case-only rename");
            try { files.rename(renamedId, "DCIM"); check(false, "Rename onto a folder"); } catch (IOException expected) { check(true, "Rename never replaces"); }
            String trips = files.rename(top.get("DCIM").id, "Trips");
            check(java.nio.file.Files.isDirectory(internal.resolve("Trips/Camera")), "Folder renamed with its contents");
            try { files.list(top.get("DCIM").id); } catch (IOException stale) { check(true, "Old ID forgotten after a rename"); }
            files.delete(trips);
            check(!java.nio.file.Files.exists(internal.resolve("Trips")), "Folder deleted with its contents");
            check(java.nio.file.Files.readString(outside.resolve("keep.txt")).equals("keep"), "Deleting never follows a link out of storage");
            try { files.delete(""); check(false, "Deleted the storage"); } catch (IOException expected) { check(true, "Storage can't be deleted"); }
        } finally {
            try (var walk = java.nio.file.Files.walk(base)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /** PUT_RESUMABLE continues an interrupted upload; OUTBOX hands over files sent from the phone. */
    private static void resumeAndOutboxTests() throws Exception {
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("openbridge-resume");
        try {
            java.nio.file.Path internal = java.nio.file.Files.createDirectories(base.resolve("internal"));
            FileStore files = new FileStore(List.of(new FileStore.Root("Internal storage", internal.toFile())));
            Session session = new Session(files, SECRET);
            byte[] body = new byte[150_000]; new Random(3).nextBytes(body);
            byte[] key = new byte[32]; Arrays.fill(key, (byte) 9);
            // The connection drops after 60,000 bytes: the phone keeps them.
            try {
                serve(session, simple(12, "", out -> { Wire.writeString(out, "video.mp4"); out.writeLong(body.length); out.write(key); out.write(body, 0, 60_000); }));
                check(false, "A cut upload must fail");
            } catch (IOException expected) { check(true, "Cut upload fails"); }
            check(!java.nio.file.Files.exists(internal.resolve("video.mp4")), "Nothing published from a cut upload");
            check(!byName(files.list("")).containsKey(Names.resumable("video.mp4", body.length, key)), "The kept part is hidden");
            // The same file continues from there.
            DataInputStream resumed = serve(session, simple(12, "", out -> {
                Wire.writeString(out, "video.mp4"); out.writeLong(body.length); out.write(key); out.write(body, 60_000, body.length - 60_000); }));
            check(resumed.readByte() == 0 && resumed.readLong() == 60_000 && resumed.readByte() == 0, "Upload continues from the kept bytes");
            check(Arrays.equals(java.nio.file.Files.readAllBytes(internal.resolve("video.mp4")), body), "Continued upload is exact");
            // A changed file (same name and size, different contents) never continues an old part.
            byte[] otherKey = key.clone(); otherKey[0] ^= 1;
            try { serve(session, simple(12, "", out -> { Wire.writeString(out, "clip.mp4"); out.writeLong(body.length); out.write(key); out.write(body, 0, 1000); })); }
            catch (IOException expected) { check(true, "Second cut upload"); }
            DataInputStream fresh = serve(session, simple(12, "", out -> {
                Wire.writeString(out, "clip.mp4"); out.writeLong(body.length); out.write(otherKey); out.write(body); }));
            check(fresh.readByte() == 0 && fresh.readLong() == 0 && fresh.readByte() == 0, "A different file starts over");
            check(Arrays.equals(java.nio.file.Files.readAllBytes(internal.resolve("clip.mp4")), body), "Started-over upload is exact");
            check(serve(session, simple(12, "", out -> { Wire.writeString(out, "video.mp4"); out.writeLong(1); out.write(key); out.write(1); })).readByte() == 1,
                "Resumable uploads never replace a file");
        } finally {
            try (var walk = java.nio.file.Files.walk(base)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        // A store that can't keep parts starts over: the ready status says 0.
        MemoryStore memory = new MemoryStore();
        DataInputStream plain = serve(memory, simple(12, "", out -> { Wire.writeString(out, "new.bin"); out.writeLong(3); out.write(new byte[32]); out.write(new byte[] {1, 2, 3}); }));
        check(plain.readByte() == 0 && plain.readLong() == 0 && plain.readByte() == 0 && memory.files.get("new.bin").length == 3, "Resumable PUT on a simple store");

        // OUTBOX
        Map<String, byte[]> sent = new LinkedHashMap<>();
        sent.put("out-1", "photo bytes".getBytes(StandardCharsets.UTF_8));
        Outbox outbox = new Outbox() {
            public List<Store.Entry> waitFor(long millis) {
                List<Store.Entry> entries = new ArrayList<>();
                sent.forEach((id, data) -> entries.add(new Store.Entry(id, "IMG_" + id + ".jpg", false, data.length)));
                return entries;
            }
            public Store.Read open(String id) throws IOException {
                byte[] data = sent.get(id);
                if (data == null) throw new IOException("Already collected");
                InputStream stream = new ByteArrayInputStream(data);
                return new Store.Read() { public long size() { return data.length; } public InputStream stream() { return stream; } public void close() {} };
            }
            public void done(String id) { sent.remove(id); }
        };
        Session phone = new Session(new MemoryStore(), Trust.single(SECRET), null, null, null, outbox);
        DataInputStream waiting = serve(phone, simple(13, "", out -> out.writeInt(1000)));
        check(waiting.readByte() == 0 && waiting.readInt() == 1, "One file waiting");
        check(Wire.readString(waiting, 128).equals("out-1") && Wire.readString(waiting, 255).equals("IMG_out-1.jpg") && waiting.readLong() == 11, "Outbox entry");
        DataInputStream got = serve(phone, simple(14, "out-1", out -> out.writeLong(6)));
        check(got.readByte() == 0 && got.readLong() == 11 && new String(got.readAllBytes(), StandardCharsets.UTF_8).equals("bytes"), "Outbox file from an offset");
        check(serve(phone, simple(15, "out-1", out -> {})).readByte() == 0 && sent.isEmpty(), "Collected files are dropped");
        check(serve(phone, simple(14, "out-1", out -> out.writeLong(0))).readByte() == 1, "A collected file is gone");
        check(serve(phone, simple(13, "", out -> out.writeInt(60_000))).readByte() == 1, "Waits are capped");
        check(serve(memory, simple(13, "", out -> out.writeInt(0))).readByte() == 1, "No outbox unless shared");
    }

    interface Body { void write(DataOutputStream out) throws IOException; }
    private static byte[] simple(int operation, String id, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(Wire.MAGIC); out.write(SECRET); out.writeByte(operation); Wire.writeString(out, id);
        body.write(out);
        return bytes.toByteArray();
    }

    private static Map<String, Store.Entry> byName(List<Store.Entry> entries) {
        Map<String, Store.Entry> result = new LinkedHashMap<>();
        for (Store.Entry entry : entries) result.put(entry.name, entry);
        return result;
    }

    private static void fileStoreTests() throws Exception {
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("openbridge-files");
        try {
            java.nio.file.Path internal = java.nio.file.Files.createDirectories(base.resolve("internal"));
            java.nio.file.Path sd = java.nio.file.Files.createDirectories(base.resolve("sd"));
            java.nio.file.Path outside = java.nio.file.Files.createDirectories(base.resolve("outside"));
            java.nio.file.Files.writeString(outside.resolve("secret.txt"), "private");
            java.nio.file.Path dcim = java.nio.file.Files.createDirectories(internal.resolve("DCIM"));
            byte[] photo = new byte[70_001]; new Random(5).nextBytes(photo);
            java.nio.file.Files.write(dcim.resolve("photo.jpg"), photo);
            java.nio.file.Files.writeString(internal.resolve("notes.txt"), "hello");
            java.nio.file.Files.writeString(internal.resolve(Names.TEMPORARY_PREFIX + "left-over"), "partial");
            boolean linked = true;
            try { java.nio.file.Files.createSymbolicLink(internal.resolve("escape"), outside); } catch (Exception e) { linked = false; }

            FileStore single = new FileStore(List.of(new FileStore.Root("Internal storage", internal.toFile())),
                (file, size) -> ("thumb:" + file.getName() + ":" + size).getBytes(StandardCharsets.UTF_8));
            String made = single.createDirectory("", "Trips");
            check(java.nio.file.Files.isDirectory(internal.resolve("Trips")) && single.list(made).isEmpty(), "Create a folder in whole-phone mode");
            for (String clash : new String[] {"Trips", "trips", "notes.txt", "NOTES.TXT"}) {
                try { single.createDirectory("", clash); check(false, "Folder name clash allowed: " + clash); }
                catch (IOException expected) { check(expected.getMessage().contains("already exists"), "Folder clash reported: " + clash); }
            }
            String notesId = single.list("").stream().filter(e -> e.name.equals("notes.txt")).findFirst().get().id;
            check(new String(single.thumbnail(notesId, 200), StandardCharsets.UTF_8).equals("thumb:notes.txt:200"), "Thumbnail from the platform");
            try { single.thumbnail(made, 200); check(false, "Folder thumbnail"); } catch (IOException expected) { check(true, "No folder thumbnail"); }
            java.nio.file.Files.delete(internal.resolve("Trips"));

            FileStore two = new FileStore(List.of(new FileStore.Root("Internal storage", internal.toFile()), new FileStore.Root("SD card", sd.toFile())));
            List<Store.Entry> top = two.list("");
            check(top.size() == 2 && top.get(0).name.equals("Internal storage") && top.get(1).name.equals("SD card") && top.get(0).directory, "Storage areas at the top");
            Map<String, Store.Entry> inside = byName(two.list(top.get(0).id));
            check(inside.keySet().equals(new LinkedHashSet<>(List.of("DCIM", "notes.txt"))), "Folders first; temporary files and symlinks hidden: " + inside.keySet());
            check(!linked || !inside.containsKey("escape"), "Symlink out of storage is not listed");
            Map<String, Store.Entry> photos = byName(two.list(inside.get("DCIM").id));
            check(photos.get("photo.jpg").size == photo.length, "File size");

            // End to end through the protocol handler: download, upload, collisions, aborted upload.
            Session session = new Session(two, SECRET);
            DataInputStream got = serve(session, request(2, photos.get("photo.jpg").id, null, 0, null));
            check(got.readByte() == 0 && got.readLong() == photo.length && Arrays.equals(got.readAllBytes(), photo), "Download from anywhere in storage");
            byte[] body = new byte[123_457]; new Random(9).nextBytes(body);
            DataInputStream put = serve(session, request(3, inside.get("DCIM").id, "upload.bin", body.length, body));
            check(put.readByte() == 0 && put.readByte() == 0, "Upload into any folder");
            check(Arrays.equals(java.nio.file.Files.readAllBytes(dcim.resolve("upload.bin")), body), "Uploaded bytes");
            for (String name : new String[] {"UPLOAD.BIN", "Photo.JPG", "what?.txt", Names.TEMPORARY_PREFIX + "x"}) {
                DataInputStream refused = serve(session, request(3, inside.get("DCIM").id, name, 1, new byte[] {1}));
                check(refused.readByte() == 1, "Rejected upload name " + name);
            }
            check(serve(session, request(3, "", "top.bin", 1, new byte[] {1})).readByte() == 1, "No uploads above the storage areas");
            check(serve(session, request(1, "not-an-id", null, 0, null)).readByte() == 1, "Unknown IDs rejected");
            try {
                serve(session, request(3, inside.get("DCIM").id, "cut.bin", 1000, new byte[] {1, 2, 3}));
                throw new AssertionError("Truncated upload must fail");
            } catch (IOException expected) {
                check(!java.nio.file.Files.exists(dcim.resolve("cut.bin")), "Aborted upload not published");
                try (var left = java.nio.file.Files.list(dcim)) {
                    check(left.noneMatch(p -> p.getFileName().toString().startsWith(Names.TEMPORARY_PREFIX)), "Temporary file removed");
                }
            }
            // One storage area: its contents are the top level.
            FileStore one = new FileStore(List.of(new FileStore.Root("Internal storage", internal.toFile())));
            check(byName(one.list("")).containsKey("DCIM"), "Single storage area listed directly");
        } finally {
            try (var walk = java.nio.file.Files.walk(base)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static DataInputStream serve(Session session, byte[] request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        session.serve(new ByteArrayInputStream(request), output);
        return new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
    }
}
