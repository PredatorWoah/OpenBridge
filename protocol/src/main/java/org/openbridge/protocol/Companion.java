package org.openbridge.protocol;

import java.io.IOException;

/**
 * What the phone does for paired computers beyond files (operations 19 to 23): its events (notifications and battery),
 * answering or dismissing a notification, ringing, showing a link, and telling the user about a file a computer sent.
 * The platform implements it; without one those operations answer "Unsupported operation; update OpenBridge on the
 * phone". Errors are IOExceptions with a short message for the computer (at most 120 characters).
 */
public interface Companion {
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
}
