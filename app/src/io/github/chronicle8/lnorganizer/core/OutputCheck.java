package io.github.chronicle8.lnorganizer.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Reads a written backup back and proves it is the one intended (SPEC §7.4). */
public final class OutputCheck {
    private OutputCheck() {}

    /**
     * @throws ZipFormatException naming the first difference found
     */
    public static void verify(ZipIndex input, ZipIndex output, String replacedName, byte[] intended,
                              ZipIndex.Progress progress) throws IOException, ZipFormatException {
        List<ZipIndex.Entry> a = input.entries();
        List<ZipIndex.Entry> b = output.entries();
        if (a.size() != b.size()) {
            throw new ZipFormatException("the new backup has " + b.size() + " entries, the original " + a.size());
        }
        long total = 0;
        for (ZipIndex.Entry e : b) total += e.compressedSize;
        long done = 0;
        for (int i = 0; i < a.size(); i++) {
            ZipIndex.Entry x = a.get(i);
            ZipIndex.Entry y = b.get(i);
            if (!x.name.equals(y.name)) {
                throw new ZipFormatException("entry " + (i + 1) + " is " + y.name + " in the new backup, " + x.name + " in the original");
            }
            if (y.name.equals(replacedName)) {
                byte[] got = output.read(y, Math.max(intended.length, 1));
                if (!Arrays.equals(got, intended)) {
                    throw new ZipFormatException(replacedName + " in the new backup is not the intended content");
                }
            } else if (x.crc != y.crc || x.size != y.size || x.compressedSize != y.compressedSize) {
                throw new ZipFormatException("entry " + y.name + " differs from the original");
            }
            output.check(y, progress, done, total);
            done += y.compressedSize;
        }
    }
}
