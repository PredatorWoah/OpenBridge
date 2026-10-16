package org.openbridge.protocol;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
        System.out.println("Protocol tests passed: " + checks + " assertions");
    }
}
