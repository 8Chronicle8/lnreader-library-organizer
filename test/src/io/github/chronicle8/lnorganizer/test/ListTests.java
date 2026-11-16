package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.BackupReader;
import io.github.chronicle8.lnorganizer.core.Issue;
import io.github.chronicle8.lnorganizer.core.Library;
import io.github.chronicle8.lnorganizer.core.ListExport;
import io.github.chronicle8.lnorganizer.core.ListParse;
import io.github.chronicle8.lnorganizer.core.Organize;
import io.github.chronicle8.lnorganizer.core.OutputCheck;
import io.github.chronicle8.lnorganizer.core.Report;
import io.github.chronicle8.lnorganizer.core.ZipIndex;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.chronicle8.lnorganizer.test.Check.*;

final class ListTests {
    private ListTests() {}

    static Library fixtureLibrary() throws Exception {
        return BackupReader.read(ZipTests.open(Fixtures.libraryZip()), null);
    }

    static void run() {
        System.out.println("Backup reading");

        test("the fixture library reads as written", () -> {
            Library lib = fixtureLibrary();
            eq(9, lib.novels.size(), "novel files");
            eq(5, lib.categories.size(), "categories");
            eq(8, lib.listedNovels().size(), "listed novels");
            eq(1, lib.unlistedCount(), "unlisted");
            eq("2.1.4", lib.appVersion, "appVersion");
            eq(2, lib.formatVersion, "formatVersion");
            eq("Wtr-Lab ", lib.categories.get(2).name, "raw name kept");
            eq("Wtr-Lab", lib.categories.get(2).trimmedName, "trimmed name");
            eq("[4, 6]", lib.categories.get(3).novelIds.toString(), "dangling id 99 dropped");
            eq(1, lib.notes.size(), "notes: " + lib.notes);
            yes(lib.notes.get(0).contains("novel 99"), "note names the dangling id");
            yes(lib.novels.get(7).isLocal, "isLocal read");
            yes(!lib.novels.get(8).inLibrary, "inLibrary read");
        });

        test("bad backups are refused, with every problem listed", () -> {
            Map<String, byte[]> files = fixtureFiles();
            files.put("Category.json", "[{\"id\":2,\"name\":\"Local\",\"sort\":1,\"novelIds\":[]}]".getBytes(StandardCharsets.UTF_8));
            throwsWith(BackupReader.BackupException.class, "no Default category", () -> read(files));

            Map<String, byte[]> f2 = fixtureFiles();
            f2.put("NovelAndChapters/1.json", "{\"id\":2,\"name\":\"x\",\"pluginId\":\"p\",\"inLibrary\":true,\"isLocal\":false}".getBytes(StandardCharsets.UTF_8));
            f2.put("NovelAndChapters/3.json", "{\"id\":3,".getBytes(StandardCharsets.UTF_8));
            try {
                read(f2);
                throw new Failure("expected a BackupException");
            } catch (BackupReader.BackupException e) {
                eq(2, e.problems.size(), "problems: " + e.problems);
                yes(e.problems.get(0).contains("NovelAndChapters/1.json") && e.problems.get(0).contains("file name says 1"), e.problems.get(0));
                yes(e.problems.get(1).contains("NovelAndChapters/3.json") && e.problems.get(1).contains("not valid JSON"), e.problems.get(1));
            }

            Map<String, byte[]> f3 = fixtureFiles();
            f3.put("Category.json", ("[{\"id\":1,\"name\":\"Default\",\"sort\":1,\"novelIds\":[]},"
                    + "{\"id\":3,\"name\":\"A \",\"sort\":2,\"novelIds\":[]},{\"id\":4,\"name\":\"A\",\"sort\":3,\"novelIds\":[]}]")
                    .getBytes(StandardCharsets.UTF_8));
            throwsWith(BackupReader.BackupException.class, "merge", () -> read(f3));

            Map<String, byte[]> f4 = fixtureFiles();
            f4.put("Version.json", "{\"appVersion\":\"2.1.4\",\"formatVersion\":2,\"sections\":{\"library\":false}}".getBytes(StandardCharsets.UTF_8));
            throwsWith(BackupReader.BackupException.class, "without the library", () -> read(f4));

            Map<String, byte[]> f5 = fixtureFiles();
            f5.remove("Category.json");
            throwsWith(BackupReader.BackupException.class, "no Category.json", () -> read(f5));

            Map<String, byte[]> f6 = fixtureFiles();
            f6.put("NovelAndChapters/5.json", "{\"id\":5,\"name\":\"x\",\"pluginId\":\"p\",\"inLibrary\":\"yes\",\"isLocal\":0}".getBytes(StandardCharsets.UTF_8));
            throwsWith(BackupReader.BackupException.class, "\"inLibrary\"", () -> read(f6));
        });

        test("format 1 manifests and 0/1 flags are read as LNReader reads them", () -> {
            Map<String, byte[]> f = fixtureFiles();
            f.put("Version.json", "{\"version\":\"1.1.0\"}".getBytes(StandardCharsets.UTF_8));
            f.put("NovelAndChapters/5.json", "{\"id\":5,\"name\":\"Gamma\",\"pluginId\":\"novelfull\",\"inLibrary\":1,\"isLocal\":0}".getBytes(StandardCharsets.UTF_8));
            Library lib = read(f);
            eq(1, lib.formatVersion, "format");
            eq("1.1.0", lib.appVersion, "version");
            yes(lib.novels.get(5).inLibrary, "1 is true");
        });

        System.out.println("List export");

        test("plain export matches the golden file", () ->
                eq(Fixtures.text("lists/export-plain.txt"),
                        ListExport.export(fixtureLibrary(), "fixture.zip", ListExport.Mode.PLAIN, ""), "export"));

        test("AI export matches the golden file", () ->
                eq(Fixtures.text("lists/export-ai.txt"),
                        ListExport.export(fixtureLibrary(), "fixture.zip", ListExport.Mode.AI, " Fantasy,Romance ,\nFantasy"), "export"));

        System.out.println("List import (fixtures in test/fixtures/lists)");

        try {
            List<String> cases = caseNames();
            yes(cases.size() >= 6, "only " + cases.size() + " list fixtures loaded");
            Set<Issue.Kind> kinds = EnumSet.noneOf(Issue.Kind.class);
            int changing = 0;
            for (String c : cases) {
                String expected = Fixtures.text("lists/" + c + ".expected");
                for (String line : expected.split("\n")) {
                    if (line.startsWith("issue: ")) kinds.add(Issue.Kind.valueOf(line.split(" ")[2]));
                    if (line.equals("unchanged: false")) changing++;
                }
            }
            final int changingCases = changing;
            test("the list fixtures cover every issue kind and mostly change the library", () -> {
                EnumSet<Issue.Kind> missing = EnumSet.allOf(Issue.Kind.class);
                missing.removeAll(kinds);
                eq(Collections.emptySet(), missing, "issue kinds with no fixture");
                yes(changingCases * 2 > cases.size(), "only " + changingCases + " of " + cases.size() + " fixtures change anything");
            });
            for (final String c : cases) test("fixture " + c, () -> runCase(c));
        } catch (Exception e) {
            test("list fixtures load", () -> { throw e; });
        }

        test("an organized fixture backup restores to the intended categories", () -> {
            byte[] zip = Fixtures.libraryZip();
            ZipIndex in = ZipTests.open(zip);
            Library lib = BackupReader.read(in, null);
            Organize.Result r = Organize.build(lib, ListParse.parse(Fixtures.text("lists/02-reorganize.txt"), lib));
            ZipRewrite.Plan plan = ZipRewrite.replaceEntry(in, BackupReader.CATEGORY_JSON, r.categoryJson, 1 << 20);
            byte[] out = ZipTests.write(plan, zip);
            OutputCheck.verify(in, ZipTests.open(out), BackupReader.CATEGORY_JSON, r.categoryJson, null);
            Library back = BackupReader.read(ZipTests.open(out), null);
            List<String> got = new ArrayList<>();
            for (Library.Category c : back.categories) got.add(c.trimmedName + "=" + c.novelIds);
            eq("[Fantasy=[1, 6, 2], Romance=[3, 2], Local=[7], Default=[5, 10, 4]]", got.toString(), "categories read back");
            eq(lib.novels.keySet(), back.novels.keySet(), "novels unchanged");
        });

        test("the export of an organized library reads back unchanged", () -> {
            Library lib = fixtureLibrary();
            Organize.Result r = Organize.build(lib, ListParse.parse(Fixtures.text("lists/03-problems.txt"), lib));
            byte[] zip = Fixtures.libraryZip();
            ZipIndex in = ZipTests.open(zip);
            byte[] out = ZipTests.write(ZipRewrite.replaceEntry(in, BackupReader.CATEGORY_JSON, r.categoryJson, 1 << 20), zip);
            Library back = BackupReader.read(ZipTests.open(out), null);
            String text = ListExport.export(back, "x.zip", ListExport.Mode.PLAIN, "");
            Organize.Result again = Organize.build(back, ListParse.parse(text, back));
            yes(again.unchanged, "second round should be unchanged");
            yes(again.issues.isEmpty(), "no issues: " + again.issues);
        });
    }

    static Library read(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return BackupReader.read(ZipTests.open(bos.toByteArray()), null);
    }

    static Map<String, byte[]> fixtureFiles() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (String name : Fixtures.libraryEntryNames()) {
            if (!name.startsWith("Covers/")) m.put(name, Fixtures.bytes("library/" + name));
        }
        return m;
    }

    static List<String> caseNames() throws Exception {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(Fixtures.root().resolve("lists"))) {
            s.forEach(p -> {
                String f = p.getFileName().toString();
                if (f.endsWith(".expected")) names.add(f.substring(0, f.length() - ".expected".length()));
            });
        }
        Collections.sort(names);
        return names;
    }

    static void runCase(String name) throws Exception {
        Library lib = fixtureLibrary();
        String text = Fixtures.text("lists/" + name + ".txt");
        Map<String, String> exp = new LinkedHashMap<>();
        List<String> expIssues = new ArrayList<>();
        for (String line : Fixtures.text("lists/" + name + ".expected").split("\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(": ");
            String key = colon < 0 ? line.substring(0, line.length() - 1) : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 2);
            if (key.equals("issue")) expIssues.add(value);
            else exp.put(key, value);
        }
        ListParse.Result parsed = ListParse.parse(text, lib);
        if (exp.containsKey("refused")) {
            eq("NothingToImport", exp.get("refused"), "fixture refusal kind");
            throwsWith(Organize.NothingToImport.class, "No novel lines", () -> Organize.build(lib, parsed));
            return;
        }
        Organize.Result r = Organize.build(lib, parsed);
        eq(Boolean.valueOf(exp.get("unchanged")), r.unchanged, "unchanged");
        String json = new String(r.categoryJson, StandardCharsets.UTF_8);
        if (exp.get("categoryJson").equals("SAME")) {
            bytesEq(Fixtures.bytes("library/Category.json"), r.categoryJson, "categoryJson is the backup's own bytes");
        } else {
            eq(exp.get("categoryJson"), json, "categoryJson");
        }
        eq(exp.get("unplaced"), join(r.unplaced), "unplaced");
        eq(exp.get("moved"), String.valueOf(r.movedCount), "moved");
        List<String> removed = new ArrayList<>();
        for (Library.Category c : r.removed) removed.add(c.trimmedName);
        eq(exp.get("removed"), String.join(", ", removed), "removed");
        List<String> issues = new ArrayList<>();
        for (Issue i : r.issues) issues.add(i.line + " " + i.kind);
        eq(expIssues, issues, "issues");
        String report = Report.render(lib, r);
        yes(!report.isEmpty(), "report rendered");
        for (Issue i : r.issues) yes(report.contains("line " + i.line + ": "), "report names line " + i.line);
    }

    static String join(List<Integer> ids) {
        List<String> s = new ArrayList<>();
        for (Integer i : ids) s.add(String.valueOf(i));
        return String.join(", ", s);
    }
}
