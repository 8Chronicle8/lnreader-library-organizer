package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.BackupReader;
import io.github.chronicle8.lnorganizer.core.Library;
import io.github.chronicle8.lnorganizer.core.ListExport;
import io.github.chronicle8.lnorganizer.core.ListParse;
import io.github.chronicle8.lnorganizer.core.Organize;
import io.github.chronicle8.lnorganizer.core.OutputCheck;
import io.github.chronicle8.lnorganizer.core.Text;
import io.github.chronicle8.lnorganizer.core.ZipIndex;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;
import io.github.chronicle8.lnorganizer.io.ChannelSource;
import io.github.chronicle8.lnorganizer.io.PlanWriter;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.github.chronicle8.lnorganizer.test.Check.*;

/** The real target data: an actual LNReader backup, given with -Dbackup=FILE and -Dscratch=DIR. */
final class RealBackupTests {
    private RealBackupTests() {}

    static void run() {
        System.out.println("Real backup");
        String backup = System.getProperty("backup");
        String scratch = System.getProperty("scratch");
        if (backup == null || scratch == null) {
            skip("real backup round trips", "no -Dbackup/-Dscratch given");
            return;
        }
        Path in = Paths.get(backup);
        Path dir = Paths.get(scratch);

        test("an unedited list writes a byte-identical backup", () -> {
            try (FileChannel ch = FileChannel.open(in, StandardOpenOption.READ)) {
                long t0 = System.nanoTime();
                ZipIndex zip = ZipIndex.open(new ChannelSource(ch));
                Library lib = BackupReader.read(zip, null);
                long t1 = System.nanoTime();
                System.out.println("        read " + lib.novels.size() + " novels, " + lib.categories.size()
                        + " categories in " + (t1 - t0) / 1_000_000 + " ms; notes: " + lib.notes);
                yes(lib.novels.size() > 0 && lib.categories.size() > 0, "library is empty");
                String text = ListExport.export(lib, in.getFileName().toString(), ListExport.Mode.PLAIN, "");
                Organize.Result r = Organize.build(lib, ListParse.parse(text, lib));
                yes(r.unchanged, "unedited list should be unchanged; issues: " + r.issues);
                yes(r.issues.isEmpty(), "issues: " + r.issues);
                ZipRewrite.Plan plan = ZipRewrite.replaceEntry(zip, BackupReader.CATEGORY_JSON, r.categoryJson, 64 << 20);
                yes(plan.identical, "plan should copy the file whole");
                Path out = fresh(dir.resolve("identity.zip"));
                try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                    PlanWriter.write(plan, new ChannelSource(ch), os, null);
                }
                eq(sha256(in), sha256(out), "sha256 of input and output");
                Files.delete(out);
            }
        });

        test("a reorganized list writes a backup with exactly the new categories", () -> {
            try (FileChannel ch = FileChannel.open(in, StandardOpenOption.READ)) {
                ZipIndex zip = ZipIndex.open(new ChannelSource(ch));
                Library lib = BackupReader.read(zip, null);
                Map<String, List<Library.Novel>> buckets = new TreeMap<>();
                for (Integer id : new java.util.TreeSet<>(lib.categorizedNovelIds())) {
                    Library.Novel n = lib.novels.get(id);
                    buckets.computeIfAbsent(bucket(n.name), k -> new ArrayList<>()).add(n);
                }
                StringBuilder text = new StringBuilder("// reorganized by first letter\n");
                Map<Integer, String> intended = new LinkedHashMap<>();
                for (Map.Entry<String, List<Library.Novel>> b : buckets.entrySet()) {
                    text.append("\n## ").append(b.getKey()).append('\n');
                    for (Library.Novel n : b.getValue()) {
                        text.append(ListExport.novelLine(n)).append('\n');
                        intended.put(n.id, b.getKey());
                    }
                }
                Organize.Result r = Organize.build(lib, ListParse.parse(text.toString(), lib));
                yes(!r.unchanged, "should change");
                yes(r.issues.isEmpty(), "issues: " + r.issues);
                yes(r.unplaced.isEmpty(), "unplaced: " + r.unplaced);
                ZipRewrite.Plan plan = ZipRewrite.replaceEntry(zip, BackupReader.CATEGORY_JSON, r.categoryJson, 64 << 20);
                Path out = fresh(dir.resolve("organized-by-letter.zip"));
                long t0 = System.nanoTime();
                try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                    PlanWriter.write(plan, new ChannelSource(ch), os, null);
                }
                long t1 = System.nanoTime();
                try (FileChannel oc = FileChannel.open(out, StandardOpenOption.READ)) {
                    ZipIndex o = ZipIndex.open(new ChannelSource(oc));
                    OutputCheck.verify(zip, o, BackupReader.CATEGORY_JSON, r.categoryJson, null);
                    long t2 = System.nanoTime();
                    System.out.println("        wrote " + plan.totalLength + " bytes in " + (t1 - t0) / 1_000_000
                            + " ms, verified in " + (t2 - t1) / 1_000_000 + " ms");
                    Library back = BackupReader.read(o, null);
                    Set<String> names = new HashSet<>();
                    for (Library.Category c : back.categories) {
                        names.add(c.trimmedName);
                        for (Integer id : c.novelIds) {
                            eq(intended.get(id), c.trimmedName, "category of novel " + id);
                        }
                    }
                    Set<String> want = new HashSet<>(buckets.keySet());
                    want.add(lib.defaultCategory().trimmedName);
                    eq(want, names, "category names");
                    eq(lib.novels.keySet(), back.novels.keySet(), "novels");
                }
                System.out.println("        kept for inspection: " + out);
            }
        });
    }

    static String bucket(String title) {
        String f = Text.fold(title);
        char c = f.isEmpty() ? '?' : f.charAt(0);
        if (c >= 'a' && c <= 'f') return "Titles A-F";
        if (c >= 'g' && c <= 'm') return "Titles G-M";
        if (c >= 'n' && c <= 's') return "Titles N-S";
        if (c >= 't' && c <= 'z') return "Titles T-Z";
        return "Titles 0-9 and other";
    }

    static Path fresh(Path p) throws Exception {
        if (Files.exists(p)) throw new Failure(p + " already exists; remove it or use another scratch directory");
        return p;
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
