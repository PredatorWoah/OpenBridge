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
        fileStoreTests();
        System.out.println("Protocol tests passed: " + checks + " assertions");
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
