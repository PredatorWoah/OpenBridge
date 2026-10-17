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
 */
public final class Pairing {
    private Pairing() {}
    public static final byte[] MAGIC = {'O', 'B', 'P', '1'};
    public static final int NONCE = 32;

    /** Shows the code to the user and waits for a decision. Must not block for more than about a minute. */
    public interface Approver {
        boolean approve(String code) throws InterruptedException;
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
    static void serve(DataInputStream in, DataOutputStream out, byte[] fingerprint, byte[] secret, Approver approver,
                      SecureRandom random) throws IOException {
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
            approved = approver.approve(code(fingerprint, macNonce, phoneNonce));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Pairing cancelled", e);
        }
        if (!approved) {
            out.writeByte(1);
            Wire.writeString(out, "Pairing was declined on the phone");
            return;
        }
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
