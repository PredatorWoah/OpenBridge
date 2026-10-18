package org.openbridge.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * OBP1 pairing by numeric comparison, as in Bluetooth: the user confirms on the phone that both screens show
 * the same 6-digit code. The phone commits to its nonce before seeing the Mac's, so a man in the middle, who
 * sees a different certificate fingerprint on each side, gets matching codes with probability 1 in 10^6.
 * Only after confirmation does the phone release the session secret; the Mac then pins the certificate.
 * OBP2 is the same exchange, preceded by the computer's name so the phone can list the computers it trusts.
 */
public final class Pairing {
    private Pairing() {}
    public static final byte[] MAGIC = {'O', 'B', 'P', '1'};
    public static final byte[] MAGIC2 = {'O', 'B', 'P', '2'};
    /**
     * OBP2 sends the computer's name as exactly this many bytes: UTF-8, padded with zeros. A fixed block, at
     * least as long as a secret, makes a phone that predates OBP2 answer "Pairing rejected" straight away
     * instead of waiting for more bytes, so the computer can fall back to OBP1.
     */
    public static final int NAME_BLOCK = 128;

    /** Reads an OBP2 name block. */
    static String readName(DataInputStream in) throws IOException {
        byte[] block = new byte[NAME_BLOCK];
        in.readFully(block);
        int length = NAME_BLOCK;
        while (length > 0 && block[length - 1] == 0) length--;
        try {
            return displayName(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(block, 0, length)).toString());
        } catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid computer name", e); }
    }

    /** Encodes a name for OBP2, cutting it at a character boundary to fit. */
    public static byte[] nameBlock(String name) {
        byte[] block = new byte[NAME_BLOCK];
        int used = 0;
        for (int i = 0; i < name.length(); ) {
            int point = name.codePointAt(i);
            byte[] bytes = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8);
            if (point == 0 || used + bytes.length > NAME_BLOCK) break;
            System.arraycopy(bytes, 0, block, used, bytes.length);
            used += bytes.length;
            i += Character.charCount(point);
        }
        return block;
    }
    public static final int NONCE = 32;

    /** Shows the code to the user and waits for a decision. Must not block for more than about a minute. */
    public interface Approver {
        boolean approve(String code) throws InterruptedException;

        /** Like {@link #approve(String)}, naming the computer asking to pair. */
        default boolean approve(String code, String computer) throws InterruptedException { return approve(code); }
    }

    /** A computer name fit to show: control characters removed, never empty. */
    static String displayName(String name) {
        String clean = name.codePoints().filter(c -> !Character.isISOControl(c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().trim();
        return clean.isEmpty() ? "Computer" : clean;
    }

    public static byte[] commitment(byte[] fingerprint, byte[] phoneNonce) {
        return sha256("OpenBridge pairing commit", fingerprint, phoneNonce);
    }

    /** Six decimal digits derived from the certificate fingerprint and both nonces. */
    public static String code(byte[] fingerprint, byte[] macNonce, byte[] phoneNonce) {
        byte[] hash = sha256("OpenBridge pairing code", fingerprint, macNonce, phoneNonce);
        long value = ((hash[0] & 0xFFL) << 24) | ((hash[1] & 0xFFL) << 16) | ((hash[2] & 0xFFL) << 8) | (hash[3] & 0xFFL);
        return String.format("%06d", value % 1_000_000);
    }

    /**
     * Phone side, after the 4-byte magic was read. Writes: status 0 and the commitment; reads the Mac's nonce;
     * writes the phone's nonce; asks the approver; then writes status 0 and the secret, or an error status.
     */
    static void serve(DataInputStream in, DataOutputStream out, byte[] fingerprint, Trust trust, String computer,
                      Approver approver, SecureRandom random) throws IOException {
        if (approver == null || fingerprint == null) throw new IOException("Pairing is not available");
        byte[] phoneNonce = new byte[NONCE];
        random.nextBytes(phoneNonce);
        out.writeByte(0);
        out.write(commitment(fingerprint, phoneNonce));
        out.flush();
        byte[] macNonce = new byte[NONCE];
        in.readFully(macNonce);
        out.write(phoneNonce);
        out.flush();
        boolean approved;
        try {
            approved = approver.approve(code(fingerprint, macNonce, phoneNonce), computer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Pairing cancelled", e);
        }
        if (!approved) {
            out.writeByte(1);
            Wire.writeString(out, "Pairing was declined on the phone");
            return;
        }
        byte[] secret = trust.admit(computer);
        out.writeByte(0);
        out.write(secret);
    }

    private static byte[] sha256(String label, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(label.getBytes(StandardCharsets.US_ASCII));
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
