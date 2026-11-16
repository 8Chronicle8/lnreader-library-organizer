package io.github.chronicle8.lnorganizer.core;

import java.io.IOException;

/** Random read access to a file's bytes. Implemented outside the core (a file, a byte array in tests). */
public interface ByteSource {
    long size() throws IOException;

    /** Reads exactly len bytes starting at position, or throws. */
    void readFully(long position, byte[] dst, int off, int len) throws IOException;
}
