package io.github.chronicle8.lnorganizer.core;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The central directory of a zip file and checked access to its entries
 * (SPEC §3.1). Nothing is cached beyond the directory itself; entry data is read
 * from the source on demand.
 */
public final class ZipIndex {
    static final int SIG_LOCAL = 0x04034b50;
    static final int SIG_CENTRAL = 0x02014b50;
    static final int SIG_END = 0x06054b50;
    static final int SIG_ZIP64_LOCATOR = 0x07064b50;
    static final int LOCAL_HEADER_SIZE = 30;
    static final int CENTRAL_HEADER_SIZE = 46;
    static final int END_SIZE = 22;
    /** A central directory larger than this is refused rather than loaded into memory. */
    static final long MAX_CENTRAL_DIRECTORY = 64L << 20;

    public interface Progress {
        void update(long done, long total);
    }

    public static final class Entry {
        public final String name;
        /** Position in the central directory, 0-based. */
        public final int index;
        final byte[] nameBytes;
        final int versionNeeded;
        final int flags;
        final int method;
        final int dosTime;
        final int dosDate;
        public final long crc;
        public final long compressedSize;
        public final long size;
        final long localOffset;
        /** Offset of this entry's record inside {@link #centralDirectory}. */
        final int centralPos;
        long dataStart;
        /** Where this entry's bytes end: the next local header, or the central directory. */
        long end;

        Entry(String name, int index, byte[] nameBytes, int versionNeeded, int flags, int method,
              int dosTime, int dosDate, long crc, long compressedSize, long size, long localOffset,
              int centralPos) {
            this.name = name;
            this.index = index;
            this.nameBytes = nameBytes;
            this.versionNeeded = versionNeeded;
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localOffset = localOffset;
            this.centralPos = centralPos;
        }

        public boolean isDirectory() { return name.endsWith("/"); }
    }

    final ByteSource source;
    final long fileSize;
    final long centralOffset;
    final byte[] centralDirectory;
    final long endOffset;
    /** The end-of-central-directory record including its comment. */
    final byte[] endRecord;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ZipIndex(ByteSource source, long fileSize, long centralOffset, byte[] centralDirectory,
                     long endOffset, byte[] endRecord, List<Entry> entries, Map<String, Entry> byName) {
        this.source = source;
        this.fileSize = fileSize;
        this.centralOffset = centralOffset;
        this.centralDirectory = centralDirectory;
        this.endOffset = endOffset;
        this.endRecord = endRecord;
        this.entries = entries;
        this.byName = byName;
    }

    public List<Entry> entries() { return entries; }

    public Entry find(String name) { return byName.get(name); }

    public long fileSize() { return fileSize; }

    /** The raw central directory, for comparing two reads of the same file. */
    public byte[] centralDirectoryBytes() { return centralDirectory.clone(); }

    public static ZipIndex open(ByteSource source) throws IOException, ZipFormatException {
        long size = source.size();
        if (size < END_SIZE) throw new ZipFormatException("not a zip file: only " + size + " bytes");
        int tailLen = (int) Math.min(size, END_SIZE + 0xFFFFL);
        byte[] tail = new byte[tailLen];
        source.readFully(size - tailLen, tail, 0, tailLen);
        int at = -1;
        for (int i = tailLen - END_SIZE; i >= 0; i--) {
            if (u32(tail, i) == SIG_END && u16(tail, i + 20) == tailLen - i - END_SIZE) {
                at = i;
                break;
            }
        }
        if (at < 0) throw new ZipFormatException("not a zip file: no end-of-central-directory record");
        long endOffset = size - tailLen + at;
        byte[] endRecord = Arrays.copyOfRange(tail, at, tailLen);

        if (endOffset >= 20) {
            byte[] loc = new byte[4];
            source.readFully(endOffset - 20, loc, 0, 4);
            if (u32(loc, 0) == SIG_ZIP64_LOCATOR) {
                throw new ZipFormatException("Zip64 backups (over 4 GB or 65,535 entries) are not supported");
            }
        }
        int disk = u16(endRecord, 4);
        int centralDisk = u16(endRecord, 6);
        int entriesHere = u16(endRecord, 8);
        int entriesTotal = u16(endRecord, 10);
        long centralSize = u32(endRecord, 12);
        long centralOffset = u32(endRecord, 16);
        if (entriesTotal == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            throw new ZipFormatException("Zip64 backups (over 4 GB or 65,535 entries) are not supported");
        }
        if (disk != 0 || centralDisk != 0 || entriesHere != entriesTotal) {
            throw new ZipFormatException("multi-part zip files are not supported");
        }
        if (centralOffset + centralSize != endOffset) {
            throw new ZipFormatException("central directory (offset " + centralOffset + ", " + centralSize
                    + " bytes) does not end at the end record (offset " + endOffset + ")");
        }
        if (centralSize > MAX_CENTRAL_DIRECTORY) {
            throw new ZipFormatException("central directory too large: " + centralSize + " bytes");
        }
        byte[] cd = new byte[(int) centralSize];
        source.readFully(centralOffset, cd, 0, cd.length);

        List<Entry> list = new ArrayList<>(entriesTotal);
        Map<String, Entry> byName = new HashMap<>();
        int p = 0;
        for (int i = 0; i < entriesTotal; i++) {
            if (p + CENTRAL_HEADER_SIZE > cd.length || u32(cd, p) != SIG_CENTRAL) {
                throw new ZipFormatException("central directory record " + (i + 1) + " is damaged");
            }
            int versionNeeded = u16(cd, p + 6);
            int flags = u16(cd, p + 8);
            int method = u16(cd, p + 10);
            int time = u16(cd, p + 12);
            int date = u16(cd, p + 14);
            long crc = u32(cd, p + 16);
            long csize = u32(cd, p + 20);
            long usize = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28);
            int extraLen = u16(cd, p + 30);
            int commentLen = u16(cd, p + 32);
            int diskStart = u16(cd, p + 34);
            long localOffset = u32(cd, p + 42);
            int recordLen = CENTRAL_HEADER_SIZE + nameLen + extraLen + commentLen;
            if (p + recordLen > cd.length) {
                throw new ZipFormatException("central directory record " + (i + 1) + " runs past the directory");
            }
            byte[] nameBytes = Arrays.copyOfRange(cd, p + CENTRAL_HEADER_SIZE, p + CENTRAL_HEADER_SIZE + nameLen);
            String name = decodeName(nameBytes, flags, i + 1);
            if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                throw new ZipFormatException("entry " + name + " uses Zip64 sizes, which are not supported");
            }
            if (diskStart != 0) throw new ZipFormatException("multi-part zip files are not supported");
            if ((flags & 0x0001) != 0 || (flags & 0x0040) != 0) {
                throw new ZipFormatException("entry " + name + " is encrypted");
            }
            if (byName.containsKey(name)) throw new ZipFormatException("two entries are named " + name);
            Entry e = new Entry(name, i, nameBytes, versionNeeded, flags, method, time, date, crc, csize,
                    usize, localOffset, p);
            list.add(e);
            byName.put(name, e);
            p += recordLen;
        }
        if (p != cd.length) {
            throw new ZipFormatException("central directory has " + (cd.length - p) + " unexpected trailing bytes");
        }

        byte[] lh = new byte[LOCAL_HEADER_SIZE];
        for (Entry e : list) {
            if (e.localOffset + LOCAL_HEADER_SIZE + e.nameBytes.length > centralOffset) {
                throw new ZipFormatException("entry " + e.name + " points past the data area");
            }
            source.readFully(e.localOffset, lh, 0, LOCAL_HEADER_SIZE);
            if (u32(lh, 0) != SIG_LOCAL) throw new ZipFormatException("entry " + e.name + ": local header missing");
            if (u16(lh, 8) != e.method) {
                throw new ZipFormatException("entry " + e.name + ": local and central compression methods differ");
            }
            int nameLen = u16(lh, 26);
            int extraLen = u16(lh, 28);
            byte[] localName = new byte[nameLen];
            source.readFully(e.localOffset + LOCAL_HEADER_SIZE, localName, 0, nameLen);
            if (!Arrays.equals(localName, e.nameBytes)) {
                throw new ZipFormatException("entry " + e.name + ": local header has a different name");
            }
            e.dataStart = e.localOffset + LOCAL_HEADER_SIZE + nameLen + extraLen;
        }
        List<Entry> byOffset = new ArrayList<>(list);
        Collections.sort(byOffset, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(a.localOffset, b.localOffset); }
        });
        for (int i = 0; i < byOffset.size(); i++) {
            Entry e = byOffset.get(i);
            long next = i + 1 < byOffset.size() ? byOffset.get(i + 1).localOffset : centralOffset;
            if (e.dataStart + e.compressedSize > next) {
                throw new ZipFormatException("entry " + e.name + " overlaps the next entry");
            }
            e.end = next;
        }
        return new ZipIndex(source, size, centralOffset, cd, endOffset, endRecord,
                Collections.unmodifiableList(list), byName);
    }

    private static String decodeName(byte[] b, int flags, int recordNo) throws ZipFormatException {
        if ((flags & 0x0800) != 0) {
            try {
                return Json.decodeUtf8(b);
            } catch (CharacterCodingException e) {
                throw new ZipFormatException("central directory record " + recordNo + " has a malformed UTF-8 name");
            }
        }
        // Names without the UTF-8 flag are CP437 by the zip spec; LNReader's are ASCII,
        // and ISO-8859-1 keeps every byte distinct, which is all the lookups need.
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** Reads an entry's content, checking its size and CRC-32. */
    public byte[] read(Entry e, int maxSize) throws IOException, ZipFormatException {
        if (e.size > maxSize) {
            throw new ZipFormatException("entry " + e.name + " is too large to read (" + e.size + " bytes)");
        }
        byte[] out = new byte[(int) e.size];
        inflate(e, out, null, 0, 0);
        return out;
    }

    /** Checks an entry's size and CRC-32 without keeping its content. */
    public void check(Entry e, Progress progress, long doneBefore, long total) throws IOException, ZipFormatException {
        inflate(e, null, progress, doneBefore, total);
    }

    private void inflate(Entry e, byte[] out, Progress progress, long doneBefore, long total)
            throws IOException, ZipFormatException {
        CRC32 crc = new CRC32();
        byte[] in = new byte[64 * 1024];
        long produced = 0;
        if (e.method == 0) {
            if (e.compressedSize != e.size) {
                throw new ZipFormatException("entry " + e.name + " is stored but its sizes differ");
            }
            long pos = e.dataStart;
            long left = e.size;
            while (left > 0) {
                int n = (int) Math.min(in.length, left);
                source.readFully(pos, in, 0, n);
                crc.update(in, 0, n);
                if (out != null) System.arraycopy(in, 0, out, (int) produced, n);
                pos += n;
                left -= n;
                produced += n;
                if (progress != null) progress.update(doneBefore + (e.compressedSize - left), total);
            }
        } else if (e.method == 8) {
            Inflater inf = new Inflater(true);
            try {
                byte[] buf = out == null ? new byte[64 * 1024] : null;
                long pos = e.dataStart;
                long left = e.compressedSize;
                boolean dummyFed = false;
                while (!inf.finished()) {
                    if (inf.needsInput()) {
                        if (left > 0) {
                            int n = (int) Math.min(in.length, left);
                            source.readFully(pos, in, 0, n);
                            inf.setInput(in, 0, n);
                            pos += n;
                            left -= n;
                            if (progress != null) progress.update(doneBefore + (e.compressedSize - left), total);
                        } else if (!dummyFed) {
                            // A raw ("nowrap") inflater may need one byte past the end of the
                            // data before it reports the end of the stream (java.util.zip.Inflater docs).
                            inf.setInput(new byte[] {0}, 0, 1);
                            dummyFed = true;
                        } else {
                            throw new ZipFormatException("entry " + e.name + ": compressed data ends early");
                        }
                    }
                    int n;
                    if (out != null) {
                        if (produced >= out.length) {
                            // Room for one more byte would show the data is longer than stated.
                            byte[] probe = new byte[1];
                            n = inf.inflate(probe);
                            if (n > 0) throw new ZipFormatException("entry " + e.name + " is larger than its stated size");
                            if (inf.needsDictionary()) throw new ZipFormatException("entry " + e.name + " needs a preset dictionary");
                            continue;
                        }
                        n = inf.inflate(out, (int) produced, out.length - (int) produced);
                        if (n > 0) crc.update(out, (int) produced, n);
                    } else {
                        n = inf.inflate(buf);
                        if (n > 0) crc.update(buf, 0, n);
                    }
                    if (n == 0 && inf.needsDictionary()) {
                        throw new ZipFormatException("entry " + e.name + " needs a preset dictionary");
                    }
                    if (n == 0 && !inf.needsInput() && !inf.finished()) {
                        throw new ZipFormatException("entry " + e.name + ": the decompressor made no progress");
                    }
                    produced += n;
                    if (produced > e.size) throw new ZipFormatException("entry " + e.name + " is larger than its stated size");
                }
                long consumed = inf.getBytesRead() - (dummyFed ? 1 : 0);
                if (consumed != e.compressedSize) {
                    throw new ZipFormatException("entry " + e.name + ": compressed size is " + consumed
                            + " bytes, the directory says " + e.compressedSize);
                }
            } catch (DataFormatException ex) {
                throw new ZipFormatException("entry " + e.name + ": damaged compressed data (" + ex.getMessage() + ")");
            } finally {
                inf.end();
            }
        } else {
            throw new ZipFormatException("entry " + e.name + " uses compression method " + e.method
                    + "; only stored (0) and deflate (8) can be read");
        }
        if (produced != e.size) {
            throw new ZipFormatException("entry " + e.name + " has " + produced + " bytes, the directory says " + e.size);
        }
        if (crc.getValue() != e.crc) {
            throw new ZipFormatException("entry " + e.name + " fails its CRC-32 check");
        }
    }

    static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }

    static void put16(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
    }

    static void put32(byte[] b, int i, long v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
        b[i + 2] = (byte) (v >>> 16);
        b[i + 3] = (byte) (v >>> 24);
    }
}
