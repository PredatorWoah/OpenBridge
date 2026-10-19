package org.openbridge.protocol;

import java.io.IOException;
import java.util.List;

/**
 * Files and texts the user sent from the phone (Share › OpenBridge, Send clipboard), waiting for a paired computer to
 * collect them. The computer asks; the phone never connects out, so no firewall on the computer is involved.
 */
public interface Outbox {
    /** A waiting file. */
    int FILE = 0;
    /** A waiting text: its content is UTF-8, at most {@link Wire#MAX_CLIPBOARD} bytes, for the computer's clipboard. */
    int TEXT = 1;

    final class Item {
        public final String id, name;
        public final long size;
        public final int kind;
        public Item(String id, String name, long size, int kind) { this.id = id; this.name = name; this.size = size; this.kind = kind; }
    }

    /**
     * The waiting items, waiting up to `millis` for one to arrive when there are none. With `texts` false only files
     * count, both for the answer and for the wait (computers from before 0.13 collect only files).
     */
    List<Item> waitFor(long millis, boolean texts) throws IOException, InterruptedException;

    Store.Read open(String id) throws IOException;

    /** The computer has the item: the phone drops its copy. */
    void done(String id) throws IOException;
}
