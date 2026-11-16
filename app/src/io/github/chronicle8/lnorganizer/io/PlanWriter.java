package io.github.chronicle8.lnorganizer.io;

import io.github.chronicle8.lnorganizer.core.ByteSource;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;

import java.io.IOException;
import java.io.OutputStream;

/** Writes a {@link ZipRewrite.Plan}: source ranges and new bytes, in order (SPEC §7). */
public final class PlanWriter {
    private PlanWriter() {}

    public interface Progress {
        void update(long written, long total);
    }

    /** Returns the number of bytes written, which always equals plan.totalLength. */
    public static long write(ZipRewrite.Plan plan, ByteSource source, OutputStream out, Progress progress)
            throws IOException {
        byte[] buf = new byte[1 << 20];
        long written = 0;
        for (ZipRewrite.Segment s : plan.segments) {
            if (s instanceof ZipRewrite.Copy) {
                ZipRewrite.Copy c = (ZipRewrite.Copy) s;
                long pos = c.offset;
                long left = c.length;
                while (left > 0) {
                    int n = (int) Math.min(buf.length, left);
                    source.readFully(pos, buf, 0, n);
                    out.write(buf, 0, n);
                    pos += n;
                    left -= n;
                    written += n;
                    if (progress != null) progress.update(written, plan.totalLength);
                }
            } else if (s instanceof ZipRewrite.Bytes) {
                byte[] data = ((ZipRewrite.Bytes) s).data;
                out.write(data);
                written += data.length;
                if (progress != null) progress.update(written, plan.totalLength);
            } else {
                throw new IllegalArgumentException("unknown segment " + s.getClass().getName());
            }
        }
        out.flush();
        if (written != plan.totalLength) {
            throw new IOException("wrote " + written + " bytes, the plan has " + plan.totalLength);
        }
        return written;
    }
}
