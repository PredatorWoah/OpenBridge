package org.openbridge.protocol;

import java.io.IOException;

/**
 * What the phone does for paired computers beyond files (operations 19 to 24): its events (notifications, battery and
 * what's playing), answering or dismissing a notification, ringing, showing a link, telling the user about a file a
 * computer sent, and controlling what's playing. The platform implements it; without one those operations answer
 * "Unsupported operation; update OpenBridge on the phone", as MEDIA CONTROL does for one that doesn't implement
 * {@link #media}. Errors are IOExceptions with a short message for the computer (at most 120 characters).
 */
public interface Companion {
    /** MEDIA CONTROL (24) actions. */
    int PLAY = 1, PAUSE = 2, PLAY_PAUSE = 3, NEXT = 4, PREVIOUS = 5, SEEK = 6;

    /** EVENTS: what happened after `cursor`, waiting up to `millis` for something (see {@link EventLog#read}). */
    EventLog.Batch events(long cursor, long millis) throws IOException, InterruptedException;

    /** Dismisses the notification with this key (from EVENTS). Fails when it's gone or can't be dismissed. */
    void dismiss(String key) throws IOException;

    /** Answers the notification with this key, with 1 to 4,096 bytes of text. Fails when it's gone or can't be answered. */
    void reply(String key, String text) throws IOException;

    /** Starts ringing (at most 60 seconds), or stops. */
    void ring(boolean on) throws IOException;

    /** Shows `url` (http or https, already checked) as a notification from `computer`; tapping it opens it. */
    void openUrl(String url, String computer) throws IOException;

    /**
     * SEND finished: a file from `computer` was saved as `name` in the inbox. `location` is the store's handle on it
     * ({@link Store.Upload#savedLocation}: a path or a document URI). Called after the computer got its reply.
     */
    void received(String name, String location, String computer);

    /**
     * MEDIA CONTROL: `action` ({@link #PLAY} to {@link #SEEK}) on what's playing; for SEEK, `position` in milliseconds
     * (0 or more), otherwise -1. Fails when nothing is playing, the app doesn't offer that action, or media controls are
     * off. Companions from before 1.3.0 don't implement it, and answer as phones that don't know the operation.
     */
    default void media(int action, long position) throws IOException {
        throw new IOException("Unsupported operation; update OpenBridge on the phone");
    }
}
