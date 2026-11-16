package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.ByteSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared fixture access: the fixture library as a zip built the way LNReader builds one. */
final class Fixtures {
    private Fixtures() {}

    static Path root() {
        return Paths.get(System.getProperty("fixtures", "test/fixtures"));
    }

    static String text(String rel) throws IOException {
        return new String(Files.readAllBytes(root().resolve(rel)), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String rel) throws IOException {
        return Files.readAllBytes(root().resolve(rel));
    }

    /** Entry names of the fixture backup, in the order LNReader writes them. */
    static List<String> libraryEntryNames() throws IOException {
        List<String> novels = new ArrayList<>();
        try (Stream<Path> s = Files.list(root().resolve("library/NovelAndChapters"))) {
            s.forEach(p -> novels.add("NovelAndChapters/" + p.getFileName()));
        }
        Collections.sort(novels);
        List<String> names = new ArrayList<>();
        names.add("Version.json");
        names.addAll(novels);
        names.add("Covers/1");
        names.add("Category.json");
        return names;
    }

    /**
     * The fixture library zipped with deflate and data descriptors (as LNReader's
     * backups are), plus one stored entry so both methods are covered.
     */
    static byte[] libraryZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String name : libraryEntryNames()) {
                if (name.equals("Covers/1")) {
                    byte[] data = "not really a png".getBytes(StandardCharsets.US_ASCII);
                    ZipEntry e = new ZipEntry(name);
                    e.setMethod(ZipEntry.STORED);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    e.setCrc(crc.getValue());
                    zos.putNextEntry(e);
                    zos.write(data);
                } else {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(bytes("library/" + name));
                }
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static final class ArraySource implements ByteSource {
        final byte[] data;

        ArraySource(byte[] data) { this.data = data; }

        @Override public long size() { return data.length; }

        @Override public void readFully(long position, byte[] dst, int off, int len) throws IOException {
            if (position < 0 || position + len > data.length) {
                throw new IOException("read past the end: " + position + "+" + len + " of " + data.length);
            }
            System.arraycopy(data, (int) position, dst, off, len);
        }
    }
}
