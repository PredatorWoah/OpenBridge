package org.openbridge.protocol;

import java.io.IOException;
import java.util.Locale;

/** Filename rules for Android's local storage provider, which is stricter than the wire format. */
public final class Names {
    private Names() {}
    /** Prefix of in-progress upload documents. Hidden from listings and reserved for uploads. */
    public static final String TEMPORARY_PREFIX = ".openbridge-part-";
    // The provider silently replaces these (FAT rules) on create/rename, which would only be detected at commit.
    private static final String PROVIDER_REPLACED = "\"*:<>?|";

    /** Rejects names the provider would publish under a different name, before any body is sent. */
    public static void requireStorable(String name) throws IOException {
        Wire.validateName(name);
        for (int i = 0; i < PROVIDER_REPLACED.length(); i++) {
            char c = PROVIDER_REPLACED.charAt(i);
            if (name.indexOf(c) >= 0) throw new IOException("Android storage does not allow " + c + " in filenames");
        }
        if (isTemporary(name)) throw new IOException("Filename is reserved for uploads in progress");
    }

    public static boolean isTemporary(String name) { return name.startsWith(TEMPORARY_PREFIX); }

    /** Prefix of interrupted uploads kept so they can continue. */
    public static final String RESUMABLE_PREFIX = TEMPORARY_PREFIX + "resume-";

    /**
     * The hidden file an upload collects into, so an interrupted upload of the same file to the same folder can
     * continue. `key` is the computer's 32-byte summary of the file's contents, so a changed file never continues an
     * old one. It's hidden from listings like any upload in progress.
     */
    public static String resumable(String name, long size, byte[] key) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update((collisionKey(name) + "\u0000" + size + "\u0000").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] hash = digest.digest(key);
            StringBuilder hex = new StringBuilder(RESUMABLE_PREFIX);
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Shared storage is case-insensitive, so collisions are compared using this key. */
    public static String collisionKey(String name) { return name.toLowerCase(Locale.ROOT); }
}
