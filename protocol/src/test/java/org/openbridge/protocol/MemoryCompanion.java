package org.openbridge.protocol;

import java.io.IOException;
import java.util.*;

/**
 * An in-memory phone for operations 19 to 24, for tests here and in the desktop apps: a real {@link EventLog}, and
 * lists of what computers asked for. Post notifications with {@link #post}; set {@link #notificationsOn} false to act as
 * a phone with Notifications on computers turned off.
 */
public final class MemoryCompanion implements Companion {
    public final EventLog log = new EventLog();
    /** Notification keys that can be answered. */
    public final Set<String> answerable = Collections.synchronizedSet(new HashSet<>());
    public final List<String> dismissed = Collections.synchronizedList(new ArrayList<>());
    /** Each reply as key, then text. */
    public final List<String[]> replies = Collections.synchronizedList(new ArrayList<>());
    /** Each ring request: true to ring, false to stop. */
    public final List<Boolean> rings = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean ringing;
    /** Each link shown, as URL, then the computer's name. */
    public final List<String[]> links = Collections.synchronizedList(new ArrayList<>());
    /** Each file received, as saved name, location, then the computer's name. */
    public final List<String[]> received = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean notificationsOn = true;
    /** Media controls on computers (needs notifications on too, as on a phone). */
    public volatile boolean mediaOn = true;
    /** Each MEDIA CONTROL request, as the action and the position (-1 except for seek). */
    public final List<long[]> mediaRequests = Collections.synchronizedList(new ArrayList<>());
    /** The longest EVENTS wait honoured, so tests (and fuzzing) never sit through 25 seconds. */
    public volatile long maxWaitMillis = Long.MAX_VALUE;

    /** A notification appears on the phone (ignored while notifications on computers are off, as on a phone). */
    public void post(String key, String app, String title, String text, boolean canReply, String code) {
        if (!notificationsOn) return;
        if (canReply) answerable.add(key); else answerable.remove(key);
        log.notification(key, app, "com.example." + app.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""), title, text,
            System.currentTimeMillis(), (canReply ? EventLog.CAN_REPLY : 0) | EventLog.CAN_DISMISS, code);
    }

    @Override public EventLog.Batch events(long cursor, long millis) throws InterruptedException {
        return log.read(cursor, Math.min(millis, maxWaitMillis));
    }

    private void present(String key) throws IOException {
        if (!notificationsOn) throw new IOException("Notifications on computers are off on the phone");
        if (!log.has(key)) throw new IOException("That notification is gone");
    }

    @Override public void dismiss(String key) throws IOException {
        present(key);
        dismissed.add(key);
        answerable.remove(key);
        log.removed(key);
    }

    @Override public void reply(String key, String text) throws IOException {
        present(key);
        if (!answerable.contains(key)) throw new IOException("That notification can't be answered");
        replies.add(new String[] {key, text});
    }

    /** Something starts playing, or changes (ignored while media controls are off, as on a phone). */
    public void play(String app, String title, String artist, long duration, long position, int state, int actions) {
        if (!notificationsOn || !mediaOn) return;
        log.media(app, title, artist, duration, position, state, actions, false);
    }

    @Override public void media(int action, long position) throws IOException {
        if (!notificationsOn || !mediaOn) throw new IOException("Media controls on computers are off on the phone");
        int[] now = log.media();
        if (now[0] == EventLog.NOTHING) throw new IOException("Nothing is playing on the phone");
        int needed = action == SEEK ? EventLog.CAN_SEEK : action == NEXT ? EventLog.CAN_NEXT : action == PREVIOUS ? EventLog.CAN_PREVIOUS : EventLog.CAN_PLAY_PAUSE;
        if ((now[1] & needed) == 0) throw new IOException("The playing app doesn't offer that");
        mediaRequests.add(new long[] {action, position});
    }

    @Override public void ring(boolean on) { rings.add(on); ringing = on; }

    @Override public void openUrl(String url, String computer) { links.add(new String[] {url, computer}); }

    @Override public void received(String name, String location, String computer) { received.add(new String[] {name, location, computer}); }
}
