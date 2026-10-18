package org.openbridge.protocol;

import java.security.MessageDigest;

/** Which computers may use this phone: each paired computer gets its own secret. */
public interface Trust {
    /** The name of the computer this secret was given to, or null. Must compare in constant time. */
    String computer(byte[] secret);

    /** Remembers a newly paired computer and returns the 32-byte secret it will send from now on. */
    byte[] admit(String computer);

    /** One secret for everyone, as before pairing was remembered (tests and older callers). */
    static Trust single(byte[] secret) {
        if (secret.length != 32) throw new IllegalArgumentException("Secret must be 32 bytes");
        byte[] kept = secret.clone();
        return new Trust() {
            public String computer(byte[] supplied) { return MessageDigest.isEqual(supplied, kept) ? "Computer" : null; }
            public byte[] admit(String computer) { return kept.clone(); }
        };
    }
}
