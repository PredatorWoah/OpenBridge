package org.openbridge.protocol;

import java.io.IOException;

/** The phone's clipboard, shared with a paired Mac only while sharing is on. */
public interface Clipboard {
    /** The current plain-text clipboard, or an empty string. */
    String read() throws IOException;
    void write(String text) throws IOException;
}
