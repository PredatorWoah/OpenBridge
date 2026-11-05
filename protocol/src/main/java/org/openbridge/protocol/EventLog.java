package org.openbridge.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What's happening on the phone, for EVENTS (19): the phone's notifications, battery and what's playing, kept in memory only, with a
 * cursor computers send back to get what happened since. The platform feeds it; computers read it with
 * {@link #read}. A cursor from another instance (the app restarted) or one too far behind starts over with a RESET
 * and the current state. Thread-safe.
 */
public final class EventLog {
    public static final int RESET = 0, NOTIFICATION = 1, REMOVED = 2, BATTERY = 3, MEDIA = 4;
    /** The most events in one reply. */
    public static final int MAX_EVENTS = 200;
    /** The longest event body. */
    public static final int MAX_BODY = 65_536;
    /** How many recent events are kept for computers that are behind. */
    public static final int KEEP = 500;
    /** Limits of the NOTIFICATION fields, in UTF-8 bytes. */
    public static final int MAX_KEY = 256, MAX_APP = 128, MAX_PACKAGE = 128, MAX_TITLE = 512, MAX_TEXT = 4_096, MAX_CODE = 16;
    /** NOTIFICATION flags. */
    public static final int CAN_REPLY = 1, CAN_DISMISS = 2;
    /** Limits of the MEDIA fields, in UTF-8 bytes. */
    public static final int MAX_MEDIA_APP = 128, MAX_MEDIA_TITLE = 512, MAX_ARTIST = 512;
    /** MEDIA states. */
    public static final int NOTHING = 0, PLAYING = 1, PAUSED = 2;
    /** MEDIA actions: what the playing app offers, as MEDIA CONTROL can ask for it. */
    public static final int CAN_PLAY_PAUSE = 1, CAN_NEXT = 2, CAN_PREVIOUS = 4, CAN_SEEK = 8;

    public static final class Event {
        public final int kind;
        public final byte[] body;
        public Event(int kind, byte[] body) {
            if (kind < 0 || kind > 255 || body.length > MAX_BODY) throw new IllegalArgumentException("Invalid event");
            this.kind = kind; this.body = body;
        }
    }

    /** One reply: the cursor to send next time, and the events after the one sent. */
    public static final class Batch {
        public final long cursor;
        public final List<Event> events;
        public Batch(long cursor, List<Event> events) { this.cursor = cursor; this.events = events; }
    }

    private static final long SEQUENCE_MASK = 0xFFFF_FFFFL;
    /** Random per instance, in the cursor's upper half, so a cursor from an earlier run of the app is never recognized. */
    private final long epoch;
    /** The number of the newest event; events are numbered from 1. */
    private long sequence;
    /** The latest events, oldest first, each with its number. */
    private final ArrayDeque<Numbered> recent = new ArrayDeque<>();
    private static final class Numbered {
        final long number; final Event event;
        Numbered(long number, Event event) { this.number = number; this.event = event; }
    }
    /** The notifications on the phone now, by key, oldest update first: what a RESET is followed by. */
    private final LinkedHashMap<String, byte[]> notifications = new LinkedHashMap<>();
    private byte[] battery;
    /** What's playing, as last told (null: nothing ever was), and when its position was true (the clock's milliseconds). */
    private Media media;
    private final java.util.function.LongSupplier clock;

    private static final class Media {
        final String app, title, artist;
        final long duration, position, at;
        final int state, actions;
        Media(String app, String title, String artist, long duration, long position, int state, int actions, long at) {
            this.app = app; this.title = title; this.artist = artist; this.duration = duration; this.position = position;
            this.state = state; this.actions = actions; this.at = at;
        }
        /** The same as `other` apart from the position (which moves by itself while playing). */
        boolean same(Media other) {
            return other != null && app.equals(other.app) && title.equals(other.title) && artist.equals(other.artist)
                && duration == other.duration && state == other.state && actions == other.actions;
        }
        /** The body, with the position where it is at `now` while playing. */
        byte[] body(long now) {
            long shown = position;
            if (state == PLAYING && position >= 0 && now > at) {
                shown = position + (now - at);
                if (duration > 0) shown = Math.min(shown, duration);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            try {
                Wire.writeString(out, app);
                Wire.writeString(out, title);
                Wire.writeString(out, artist);
                out.writeLong(duration);
                out.writeLong(shown);
                out.writeByte(state);
                out.writeByte(actions);
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
            return bytes.toByteArray();
        }
    }

    public EventLog() { this(new SecureRandom(), System::currentTimeMillis); }

    EventLog(java.util.Random random) { this(random, System::currentTimeMillis); }

    EventLog(java.util.Random random, java.util.function.LongSupplier clock) {
        epoch = 1 + random.nextInt(Integer.MAX_VALUE - 1);
        this.clock = clock;
    }

    /** A notification was posted or updated. Strings longer than their limit are cut at a character boundary. */
    public synchronized void notification(String key, String app, String appPackage, String title, String text, long time, int flags, String code) {
        if (key == null || key.isEmpty() || utf8(key) > MAX_KEY) throw new IllegalArgumentException("Invalid notification key");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            Wire.writeString(out, key);
            Wire.writeString(out, cut(app, MAX_APP));
            Wire.writeString(out, cut(appPackage, MAX_PACKAGE));
            Wire.writeString(out, cut(title, MAX_TITLE));
            Wire.writeString(out, cut(text, MAX_TEXT));
            out.writeLong(time);
            out.writeByte(flags & (CAN_REPLY | CAN_DISMISS));
            Wire.writeString(out, cut(code, MAX_CODE));
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        byte[] body = bytes.toByteArray();
        notifications.remove(key);
        notifications.put(key, body);
        add(new Event(NOTIFICATION, body));
    }

    /** A notification is gone. Nothing happens for one that wasn't sent. */
    public synchronized void removed(String key) {
        if (key == null || notifications.remove(key) == null) return;
        add(new Event(REMOVED, stringBody(key)));
    }

    /** Every notification is gone from computers (Notifications on computers was turned off). */
    public synchronized void removeAll() {
        for (String key : new ArrayList<>(notifications.keySet())) removed(key);
    }

    /** Whether a notification with this key was sent and is still there. */
    public synchronized boolean has(String key) { return notifications.containsKey(key); }

    /** The keys of the notifications sent and still there. */
    public synchronized List<String> keys() { return new ArrayList<>(notifications.keySet()); }

    /** The battery level (0 to 100) and whether it's charging. Only a change is an event; returns whether it was one. */
    public synchronized boolean battery(int level, boolean charging) {
        byte[] body = {(byte) Math.max(0, Math.min(100, level)), (byte) (charging ? 1 : 0)};
        if (battery != null && java.util.Arrays.equals(battery, body)) return false;
        battery = body;
        add(new Event(BATTERY, body));
        return true;
    }

    /**
     * What's playing: the app's name, title and artist (cut to their limits), the duration and position in milliseconds
     * (-1 when unknown), the state ({@link #NOTHING}, {@link #PLAYING} or {@link #PAUSED}) and the actions the app offers
     * ({@link #CAN_PLAY_PAUSE} and the rest). Only a change is an event, not the position moving by itself, unless
     * `seeked`. With nothing playing, the strings, durations and actions are dropped. Returns whether it was an event.
     */
    public synchronized boolean media(String app, String title, String artist, long duration, long position, int state, int actions, boolean seeked) {
        if (state != PLAYING && state != PAUSED) state = NOTHING;
        Media now = state == NOTHING ? new Media("", "", "", -1, -1, NOTHING, 0, clock.getAsLong())
            : new Media(cut(app, MAX_MEDIA_APP), cut(title, MAX_MEDIA_TITLE), cut(artist, MAX_ARTIST), duration < 0 ? -1 : duration,
                position < 0 ? -1 : position, state, actions & (CAN_PLAY_PAUSE | CAN_NEXT | CAN_PREVIOUS | CAN_SEEK), clock.getAsLong());
        Media before = media;
        media = now;
        // Nothing playing is what a computer assumes after RESET, so it's news only after something was.
        boolean changed = before == null ? state != NOTHING : !now.same(before) || (seeked && state != NOTHING);
        if (changed) add(new Event(MEDIA, now.body(now.at)));
        return changed;
    }

    /** The state of what's playing ({@link #NOTHING} when nothing is) and the actions it offers, as last told. */
    public synchronized int[] media() { return media == null ? new int[] {NOTHING, 0} : new int[] {media.state, media.actions}; }

    private void add(Event event) {
        sequence++;
        recent.addLast(new Numbered(sequence, event));
        while (recent.size() > KEEP) recent.removeFirst();
        notifyAll();
    }

    private long cursorOf(long number) { return (epoch << 32) | (number & SEQUENCE_MASK); }

    /** Whether `cursor` is one this log handed out and still has every event after. */
    private boolean known(long cursor) {
        if ((cursor >>> 32) != epoch) return false;
        long number = cursor & SEQUENCE_MASK;
        long oldest = recent.isEmpty() ? sequence + 1 : recent.peekFirst().number;
        return number <= sequence && number >= oldest - 1;
    }

    /**
     * The events after `cursor`, waiting up to `millis` for one when there are none. A cursor this log doesn't know
     * (0, another run of the app, or one too far behind) gets RESET and the current state at once.
     */
    public synchronized Batch read(long cursor, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, millis);
        while (true) {
            if (!known(cursor)) return snapshot();
            long number = cursor & SEQUENCE_MASK;
            if (number < sequence) {
                List<Event> events = new ArrayList<>();
                long last = number;
                for (Numbered item : recent) {
                    if (events.size() == MAX_EVENTS) break;
                    if (item.number <= number) continue;
                    events.add(item.event);
                    last = item.number;
                }
                return new Batch(cursorOf(last), events);
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return new Batch(cursor, new ArrayList<>());
            wait(left);
        }
    }

    /**
     * RESET, the battery, what's playing (when something is, with its position now), and the notifications on the phone
     * now (the newest, when there are too many for one reply).
     */
    private Batch snapshot() {
        List<Event> events = new ArrayList<>();
        events.add(new Event(RESET, new byte[0]));
        if (battery != null) events.add(new Event(BATTERY, battery.clone()));
        if (media != null && media.state != NOTHING) events.add(new Event(MEDIA, media.body(clock.getAsLong())));
        int room = MAX_EVENTS - events.size();
        int skip = Math.max(0, notifications.size() - room);
        for (Map.Entry<String, byte[]> entry : notifications.entrySet()) {
            if (skip > 0) { skip--; continue; }
            events.add(new Event(NOTIFICATION, entry.getValue()));
        }
        return new Batch(cursorOf(sequence), events);
    }

    private static byte[] stringBody(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { Wire.writeString(new DataOutputStream(bytes), value); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return bytes.toByteArray();
    }

    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    /** `value` cut to at most `limit` UTF-8 bytes, at a character boundary (never half a surrogate pair). Null is empty. */
    public static String cut(String value, int limit) {
        if (value == null) return "";
        if (utf8(value) <= limit) return value;
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); ) {
            int point = value.codePointAt(i);
            int size = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
            if (used + size > limit) break;
            kept.appendCodePoint(point);
            used += size;
            i += Character.charCount(point);
        }
        // A lone surrogate is counted as 3 bytes but written as one "?", so the result never exceeds the limit.
        return kept.toString();
    }
}
