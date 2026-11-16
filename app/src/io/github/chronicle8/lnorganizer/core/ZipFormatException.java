package io.github.chronicle8.lnorganizer.core;

/** The zip container cannot be used; the message names the entry or field at fault (SPEC §3.1). */
public final class ZipFormatException extends Exception {
    public ZipFormatException(String message) {
        super(message);
    }
}
