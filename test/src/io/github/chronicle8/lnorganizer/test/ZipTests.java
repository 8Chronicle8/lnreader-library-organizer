package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.OutputCheck;
import io.github.chronicle8.lnorganizer.core.ZipFormatException;
import io.github.chronicle8.lnorganizer.core.ZipIndex;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;
import io.github.chronicle8.lnorganizer.io.PlanWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.github.chronicle8.lnorganizer.test.Check.*;

final class ZipTests {
    private ZipTests() {}

    static ZipIndex open(byte[] zip) throws Exception {
        return ZipIndex.open(new Fixtures.ArraySource(zip));
    }

    static byte[] write(ZipRewrite.Plan plan, byte[] source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanWriter.write(plan, new Fixtures.ArraySource(source), out, null);
        return out.toByteArray();
    }

    /** Every entry's content, read by the JDK's own zip reader (an independent implementation). */
    static Map<String, byte[]> jdkRead(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) b.write(buf, 0, n);
                out.put(e.getName(), b.toByteArray());
            }
        }
        return out;
    }

    static void run() {
        System.out.println("Zip");

        test("reads every fixture entry, deflated and stored", () -> {
            byte[] zip = Fixtures.libraryZip();
            ZipIndex z = open(zip);
            List<String> names = new ArrayList<>();
            for (ZipIndex.Entry e : z.entries()) names.add(e.name);
            eq(Fixtures.libraryEntryNames(), names, "entry names in order");
            Map<String, byte[]> jdk = jdkRead(zip);
            for (ZipIndex.Entry e : z.entries()) bytesEq(jdk.get(e.name), z.read(e, 1 << 20), e.name);
        });

        for (final String target : new String[] {"Category.json", "Version.json", "NovelAndChapters/5.json"}) {
            test("replacing " + target + " changes only that entry", () -> {
                byte[] zip = Fixtures.libraryZip();
                ZipIndex in = open(zip);
                byte[] content = ("[{\"id\":1,\"name\":\"Default — new\",\"sort\":1,\"novelIds\":[1,2,3]}]")
                        .getBytes(StandardCharsets.UTF_8);
                ZipRewrite.Plan plan = ZipRewrite.replaceEntry(in, target, content, 1 << 20);
                yes(!plan.identical, "plan should not be identical");
                byte[] out = write(plan, zip);
                eq(plan.totalLength, (long) out.length, "plan length");
                ZipIndex o = open(out);
                OutputCheck.verify(in, o, target, content, null);
                Map<String, byte[]> before = jdkRead(zip);
                Map<String, byte[]> after = jdkRead(out);
                eq(before.keySet(), after.keySet(), "entry names");
                for (String name : before.keySet()) {
                    bytesEq(name.equals(target) ? content : before.get(name), after.get(name), name);
                }
            });
        }

        test("an identical replacement copies the file byte for byte", () -> {
            byte[] zip = Fixtures.libraryZip();
            ZipIndex in = open(zip);
            ZipRewrite.Plan plan = ZipRewrite.replaceEntry(in, "Category.json",
                    Fixtures.bytes("library/Category.json"), 1 << 20);
            yes(plan.identical, "plan should be identical");
            bytesEq(zip, write(plan, zip), "output");
        });

        test("data before the first entry is kept", () -> {
            byte[] zip = withPrefix(Fixtures.libraryZip(), 77);
            ZipIndex in = open(zip);
            byte[] content = "[]".getBytes(StandardCharsets.UTF_8);
            byte[] out = write(ZipRewrite.replaceEntry(in, "Category.json", content, 1 << 20), zip);
            bytesEq(Arrays.copyOf(zip, 77), Arrays.copyOf(out, 77), "prefix");
            OutputCheck.verify(in, open(out), "Category.json", content, null);
        });

        test("the output check catches a damaged copy", () -> {
            byte[] zip = Fixtures.libraryZip();
            ZipIndex in = open(zip);
            byte[] content = "[]".getBytes(StandardCharsets.UTF_8);
            byte[] out = write(ZipRewrite.replaceEntry(in, "Category.json", content, 1 << 20), zip);
            byte[] other = "[1]".getBytes(StandardCharsets.UTF_8);
            throwsWith(ZipFormatException.class, "not the intended content",
                    () -> OutputCheck.verify(in, open(out), "Category.json", other, null));
            // A separate copy: an ArraySource reads the array it was given, so damaging
            // `out` itself would also damage every index already opened on it.
            byte[] damaged = out.clone();
            damaged[(int) dataStart(damaged, "NovelAndChapters/2.json") + 3] ^= 0x55;
            throwsWith(ZipFormatException.class, "NovelAndChapters/2.json",
                    () -> OutputCheck.verify(in, open(damaged), "Category.json", content, null));
            OutputCheck.verify(in, open(out), "Category.json", content, null);
        });

        test("damaged or unsupported zips are refused with a reason", () -> {
            byte[] zip = Fixtures.libraryZip();
            throwsWith(ZipFormatException.class, "not a zip", () -> open(Arrays.copyOf(zip, zip.length - 1)));
            throwsWith(ZipFormatException.class, "not a zip", () -> open(new byte[10]));

            byte[] crc = zip.clone();
            crc[(int) dataStart(crc, "Covers/1")] ^= 1;
            ZipIndex z = open(crc);
            throwsWith(ZipFormatException.class, "CRC-32", () -> z.read(z.find("Covers/1"), 1 << 20));

            byte[] enc = zip.clone();
            int cd = centralRecord(enc, "Version.json");
            enc[cd + 8] |= 1;
            throwsWith(ZipFormatException.class, "encrypted", () -> open(enc));

            byte[] dup = zip.clone();
            byte[] a = "NovelAndChapters/1.json".getBytes(StandardCharsets.US_ASCII);
            byte[] b = "NovelAndChapters/2.json".getBytes(StandardCharsets.US_ASCII);
            int p = centralRecord(dup, "NovelAndChapters/2.json");
            System.arraycopy(a, 0, dup, p + 46, a.length);
            yes(b.length == a.length, "same length names");
            throwsWith(ZipFormatException.class, "two entries", () -> open(dup));

            byte[] z64 = zip.clone();
            int ofs = z64.length - 22 - 20;
            z64[ofs] = 0x50; z64[ofs + 1] = 0x4b; z64[ofs + 2] = 0x06; z64[ofs + 3] = 0x07;
            throwsWith(ZipFormatException.class, "Zip64", () -> open(z64));

            byte[] big = zip.clone();
            int end = big.length - 22;
            big[end + 16] = (byte) 0xFF; big[end + 17] = (byte) 0xFF; big[end + 18] = (byte) 0xFF; big[end + 19] = (byte) 0xFF;
            throwsWith(ZipFormatException.class, "Zip64", () -> open(big));

            byte[] moved = zip.clone();
            int q = centralRecord(moved, "Version.json");
            moved[q + 42] = 5;
            throwsWith(ZipFormatException.class, "local header", () -> open(moved));
        });

        test("a missing entry cannot be replaced", () -> {
            ZipIndex z = open(Fixtures.libraryZip());
            throwsWith(ZipFormatException.class, "no Nope.json", () -> ZipRewrite.replaceEntry(z, "Nope.json", new byte[0], 10));
        });
    }

    static int u16(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }

    static long u32(byte[] b, int i) { return u16(b, i) | ((long) u16(b, i + 2) << 16); }

    static void put32(byte[] b, int i, long v) {
        for (int k = 0; k < 4; k++) b[i + k] = (byte) (v >>> (8 * k));
    }

    /** Offset of the central directory record for name (the test zips have no comment). */
    static int centralRecord(byte[] zip, String name) {
        int end = zip.length - 22;
        int p = (int) u32(zip, end + 16);
        int count = u16(zip, end + 10);
        for (int i = 0; i < count; i++) {
            int n = u16(zip, p + 28), m = u16(zip, p + 30), k = u16(zip, p + 32);
            String s = new String(zip, p + 46, n, StandardCharsets.UTF_8);
            if (s.equals(name)) return p;
            p += 46 + n + m + k;
        }
        throw new Failure("no entry " + name);
    }

    static long dataStart(byte[] zip, String name) {
        int lh = (int) u32(zip, centralRecord(zip, name) + 42);
        return lh + 30 + u16(zip, lh + 26) + u16(zip, lh + 28);
    }

    /** Prepends n bytes and shifts every offset, as `zip -A` does for a self-extracting archive. */
    static byte[] withPrefix(byte[] zip, int n) {
        byte[] out = new byte[zip.length + n];
        for (int i = 0; i < n; i++) out[i] = (byte) ('#' + i % 10);
        System.arraycopy(zip, 0, out, n, zip.length);
        int end = out.length - 22;
        int p = (int) u32(out, end + 16) + n;
        int count = u16(out, end + 10);
        for (int i = 0; i < count; i++) {
            put32(out, p + 42, u32(out, p + 42) + n);
            p += 46 + u16(out, p + 28) + u16(out, p + 30) + u16(out, p + 32);
        }
        put32(out, end + 16, u32(out, end + 16) + n);
        return out;
    }
}
