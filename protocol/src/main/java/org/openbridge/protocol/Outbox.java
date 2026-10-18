package org.openbridge.protocol;

import java.io.IOException;
import java.util.List;

/**
 * Files the user sent from the phone (Share › OpenBridge), waiting for a paired computer to collect them.
 * The computer asks; the phone never connects out, so no firewall on the computer is involved.
 */
public interface Outbox {
    /** The waiting files, waiting up to `millis` for one to arrive when there are none. */
    List<Store.Entry> waitFor(long millis) throws IOException, InterruptedException;

    Store.Read open(String id) throws IOException;

    /** The computer has the file: the phone drops its copy. */
    void done(String id) throws IOException;
}
