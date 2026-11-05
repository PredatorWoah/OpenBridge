package org.openbridge.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.LongSupplier;

/**
 * Pairing by QR code (OBP3), on the phone: reads the code a computer shows, and keeps its key for the one computer that
 * proves it holds it. A key lasts {@link Pairing#KEY_MILLIS}, works once, and is forgotten after
 * {@link Pairing#KEY_FAILURES} wrong proofs; scanning another code replaces it. Thread-safe.
 */
public final class QrPairing implements Pairing.Scanned {
    /** The start of every pairing code, compared ignoring case. */
    public static final String PREFIX = "openbridge://pair?";
    /** The longest code text read: a key, a name of 128 bytes percent-encoded, and the rest. */
    static final int MAX_TEXT = 1_024;

    /** What a pairing code says: the key, and the computer's name as it showed it. */
    public static final class Code {
        public final byte[] key;
        public final String computer;
        Code(byte[] key, String computer) { this.key = key; this.computer = computer; }
    }

    /** What the platform hears: a computer paired with the key, or the key was refused after too many wrong proofs. */
    public interface Listener {
        default void paired(String computer) {}
        default void refused() {}
    }

    /** Whether `text` is meant as an OpenBridge pairing code at all (another app's QR code isn't). */
    public static boolean isPairingCode(String text) {
        return text != null && text.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * Reads `openbridge://pair?k=<K>&n=<name>` strictly: exactly the parameters `k` and `n`, once each (in either
     * order); K exactly 16 bytes of unpadded base64url; the name percent-encoded UTF-8 of 1 to 128 bytes. The name is
     * cleaned for showing like an OBP2 name. Throws IllegalArgumentException (with a reason, for diagnostics) otherwise.
     */
    public static Code parse(String text) {
        if (!isPairingCode(text)) throw new IllegalArgumentException("not a pairing code");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("too long");
        String query = text.substring(PREFIX.length());
        String key = null, name = null;
        for (String part : query.split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals < 0) throw new IllegalArgumentException("parameter without a value");
            String field = part.substring(0, equals), value = part.substring(equals + 1);
            if (field.equals("k") && key == null) key = value;
            else if (field.equals("n") && name == null) name = value;
            else throw new IllegalArgumentException("unexpected parameter");
        }
        if (key == null || name == null) throw new IllegalArgumentException("missing parameter");
        return new Code(key(key), Pairing.displayName(name(name)));
    }

    /** 22 base64url characters, without padding, that are the canonical encoding of 16 bytes. */
    private static byte[] key(String text) {
        if (text.length() != 22 || !text.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_')) throw new IllegalArgumentException("invalid key");
        byte[] key = Base64.getUrlDecoder().decode(text);
        if (key.length != Pairing.KEY || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(text))
            throw new IllegalArgumentException("invalid key");
        return key;
    }

    /** Percent-decodes strictly (a `+` is a plus sign), then decodes UTF-8 strictly. */
    private static String name(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) throw new IllegalArgumentException("invalid name");
                int high = Character.digit(text.charAt(i + 1), 16), low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("invalid name");
                bytes.write(high * 16 + low);
                i += 2;
            } else if (c > 0x20 && c < 0x7F && c != '#' && c != '&' && c != '=') bytes.write(c);
            else throw new IllegalArgumentException("invalid name");
        }
        if (bytes.size() == 0 || bytes.size() > Pairing.NAME_BLOCK) throw new IllegalArgumentException("invalid name length");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("invalid name"); }
    }

    private final LongSupplier clock;
    private volatile Listener listener;
    private byte[] key;
    private String computer;
    private long expires;
    private int failures;

    public QrPairing() { this(System::currentTimeMillis); }

    /** With its own clock, in milliseconds (tests). */
    public QrPairing(LongSupplier clock) { this.clock = clock; }

    public void listen(Listener listener) { this.listener = listener; }

    /** The user scanned `code`: its key now waits for its computer, replacing any earlier one. Returns when it expires. */
    public synchronized long offer(Code code) {
        key = code.key.clone();
        computer = code.computer;
        failures = 0;
        expires = clock.getAsLong() + Pairing.KEY_MILLIS;
        return expires;
    }

    /** Forgets the waiting key (the user cancelled, or sharing stopped). */
    public synchronized void cancel() { forget(); }

    /** The name of the computer whose code is waiting, or null. */
    public synchronized String waitingFor() { return live() ? computer : null; }

    /** When the waiting key expires (milliseconds of the clock), or 0 when none is waiting. */
    public synchronized long expiresAt() { return live() ? expires : 0; }

    private boolean live() {
        if (key != null && clock.getAsLong() >= expires) forget();
        return key != null;
    }

    private void forget() {
        if (key != null) Arrays.fill(key, (byte) 0);
        key = null; computer = null; failures = 0; expires = 0;
    }

    @Override public synchronized byte[] key() { return live() ? key.clone() : null; }

    @Override public synchronized boolean use(byte[] used) {
        if (!live() || !MessageDigest.isEqual(key, used)) return false;
        forget();
        return true;
    }

    @Override public void failed(byte[] used) {
        boolean refused;
        synchronized (this) {
            if (!live() || !MessageDigest.isEqual(key, used)) return;
            refused = ++failures >= Pairing.KEY_FAILURES;
            if (refused) forget();
        }
        Listener heard = listener;
        if (refused && heard != null) heard.refused();
    }

    @Override public void paired(String computer) {
        Listener heard = listener;
        if (heard != null) heard.paired(computer);
    }
}
