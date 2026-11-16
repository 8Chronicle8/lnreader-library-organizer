package io.github.chronicle8.lnorganizer.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Plans a copy of a zip file with one entry's content replaced (SPEC §7). The plan
 * is a list of segments — byte ranges of the source and new bytes — that an
 * outer layer writes in order; nothing here touches a file.
 */
public final class ZipRewrite {
    private ZipRewrite() {}

    public abstract static class Segment {
        public abstract long length();
    }

    /** Copy length bytes of the source starting at offset. */
    public static final class Copy extends Segment {
        public final long offset;
        public final long length;

        Copy(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }

        @Override public long length() { return length; }
    }

    /** Write these bytes. */
    public static final class Bytes extends Segment {
        public final byte[] data;

        Bytes(byte[] data) { this.data = data; }

        @Override public long length() { return data.length; }
    }

    public static final class Plan {
        public final List<Segment> segments;
        public final long totalLength;
        /** True when the replacement equals the current content, so the plan copies the source whole. */
        public final boolean identical;

        Plan(List<Segment> segments, boolean identical) {
            this.segments = Collections.unmodifiableList(segments);
            long t = 0;
            for (Segment s : segments) t += s.length();
            this.totalLength = t;
            this.identical = identical;
        }
    }

    public static Plan replaceEntry(ZipIndex zip, String name, byte[] content, int maxCurrentSize)
            throws IOException, ZipFormatException {
        ZipIndex.Entry e = zip.find(name);
        if (e == null) throw new ZipFormatException("the backup has no " + name);
        byte[] current = zip.read(e, maxCurrentSize);
        if (Arrays.equals(current, content)) {
            List<Segment> whole = new ArrayList<>();
            whole.add(new Copy(0, zip.fileSize));
            return new Plan(whole, true);
        }

        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        byte[] compressed = deflate(content);
        int flags = e.flags & ~0x0008;

        byte[] local = new byte[ZipIndex.LOCAL_HEADER_SIZE + e.nameBytes.length + compressed.length];
        ZipIndex.put32(local, 0, ZipIndex.SIG_LOCAL);
        ZipIndex.put16(local, 4, Math.max(20, e.versionNeeded));
        ZipIndex.put16(local, 6, flags);
        ZipIndex.put16(local, 8, 8);
        ZipIndex.put16(local, 10, e.dosTime);
        ZipIndex.put16(local, 12, e.dosDate);
        ZipIndex.put32(local, 14, crc.getValue());
        ZipIndex.put32(local, 18, compressed.length);
        ZipIndex.put32(local, 22, content.length);
        ZipIndex.put16(local, 26, e.nameBytes.length);
        ZipIndex.put16(local, 28, 0);
        System.arraycopy(e.nameBytes, 0, local, ZipIndex.LOCAL_HEADER_SIZE, e.nameBytes.length);
        System.arraycopy(compressed, 0, local, ZipIndex.LOCAL_HEADER_SIZE + e.nameBytes.length, compressed.length);

        long start = e.localOffset;
        long oldLength = e.end - start;
        long delta = local.length - oldLength;
        long newCentralOffset = zip.centralOffset + delta;
        long newFileSize = zip.fileSize + delta;
        if (newCentralOffset + zip.centralDirectory.length >= 0xFFFFFFFFL || newFileSize >= 0xFFFFFFFFL) {
            throw new ZipFormatException("the new backup would need Zip64, which is not supported");
        }

        byte[] cd = zip.centralDirectory.clone();
        for (ZipIndex.Entry x : zip.entries()) {
            if (x.localOffset > start) ZipIndex.put32(cd, x.centralPos + 42, x.localOffset + delta);
        }
        int p = e.centralPos;
        ZipIndex.put16(cd, p + 6, Math.max(20, e.versionNeeded));
        ZipIndex.put16(cd, p + 8, flags);
        ZipIndex.put16(cd, p + 10, 8);
        ZipIndex.put32(cd, p + 16, crc.getValue());
        ZipIndex.put32(cd, p + 20, compressed.length);
        ZipIndex.put32(cd, p + 24, content.length);

        byte[] end = zip.endRecord.clone();
        ZipIndex.put32(end, 16, newCentralOffset);

        List<Segment> segs = new ArrayList<>();
        if (start > 0) segs.add(new Copy(0, start));
        segs.add(new Bytes(local));
        if (zip.centralOffset > e.end) segs.add(new Copy(e.end, zip.centralOffset - e.end));
        segs.add(new Bytes(cd));
        segs.add(new Bytes(end));
        Plan plan = new Plan(segs, false);
        if (plan.totalLength != newFileSize) {
            throw new IllegalStateException("plan length " + plan.totalLength + " != expected " + newFileSize);
        }
        return plan;
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            d.setInput(data);
            d.finish();
            byte[] buf = new byte[Math.max(64, data.length + data.length / 8 + 64)];
            int n = 0;
            while (!d.finished()) {
                if (n == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                n += d.deflate(buf, n, buf.length - n);
            }
            return Arrays.copyOf(buf, n);
        } finally {
            d.end();
        }
    }
}
