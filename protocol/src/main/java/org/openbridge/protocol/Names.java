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

    /** Shared storage is case-insensitive, so collisions are compared using this key. */
    public static String collisionKey(String name) { return name.toLowerCase(Locale.ROOT); }
}
