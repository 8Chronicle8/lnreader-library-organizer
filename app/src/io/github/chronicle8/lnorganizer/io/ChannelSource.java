package io.github.chronicle8.lnorganizer.io;

import io.github.chronicle8.lnorganizer.core.ByteSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** A {@link ByteSource} over a file channel, read with positional reads (the channel's position is not used). */
public final class ChannelSource implements ByteSource {
    private final FileChannel channel;

    public ChannelSource(FileChannel channel) {
        this.channel = channel;
    }

    @Override public long size() throws IOException {
        return channel.size();
    }

    @Override public void readFully(long position, byte[] dst, int off, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(dst, off, len);
        long pos = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n < 0) throw new EOFException("file ends at " + pos + ", needed " + (position + len));
            pos += n;
        }
    }
}
