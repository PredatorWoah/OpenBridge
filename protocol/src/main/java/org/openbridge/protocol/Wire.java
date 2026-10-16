package org.openbridge.protocol;

import java.io.*;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;

/** OB01: one authenticated operation per TLS connection. All integers are big endian. */
public final class Wire {
    private Wire() {}
    public static final byte[] MAGIC = {'O', 'B', '0', '1'};
    public static final long MAX_FILE = 64L * 1024 * 1024 * 1024;
    public static final int MAX_ENTRIES = 10_000;

    public static String readString(DataInputStream in, int limit) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > limit) throw new IOException("Invalid string length");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.getBytes(StandardCharsets.UTF_8).length > 255 ||
            name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 ||
            name.codePoints().anyMatch(c -> Character.isISOControl(c))) {
            throw new IOException("Invalid filename");
        }
    }

    /** Intentionally only RFC1918 IPv4. Do not bind wildcard, cellular, VPN, or public IPs. */
    public static boolean isPrivateIPv4(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != 4) return false;
        int x = a[0] & 255, y = a[1] & 255;
        return x == 10 || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168);
    }

    public static void authenticate(DataInputStream in, byte[] secret) throws IOException {
        if (secret.length != 32) throw new IllegalArgumentException("Secret must be 32 bytes");
        byte[] magic = new byte[4], supplied = new byte[32];
        in.readFully(magic);
        in.readFully(supplied);
        if (!MessageDigest.isEqual(magic, MAGIC) || !MessageDigest.isEqual(supplied, secret))
            throw new IOException("Pairing rejected");
    }

    public static void copy(InputStream in, OutputStream out, long length) throws IOException {
        if (length < 0 || length > MAX_FILE) throw new IOException("Invalid file length");
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("Transfer disconnected before completion");
            if (read == 0) continue;
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
