package io.github.chronicle8.lnorganizer.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.chronicle8.lnorganizer.test.Check.*;

/** Enforces the layering (Rule 2): core is pure, io sees only core, nothing sees android. */
final class LayeringTests {
    private LayeringTests() {}

    static final String PKG = "io.github.chronicle8.lnorganizer.";

    /** Things a core file must not name: I/O, clock, randomness, locale, threads, environment, UI. */
    static final String[] CORE_FORBIDDEN = {
        "android.", "java.io.File", "java.io.RandomAccessFile", "java.io.PrintStream", "java.nio.file",
        "java.nio.channels", "java.net", "java.util.Random", "java.util.Locale", "java.time",
        "java.util.Date", "java.util.Calendar", "java.util.concurrent", "System.currentTimeMillis",
        "System.nanoTime", "System.getenv", "System.getProperty", "System.out", "System.err",
        "Math.random", "Thread", "Locale.", "toLowerCase()", "toUpperCase()", "String.format",
        PKG + "io.", PKG + "android.",
    };

    static final String[] IO_FORBIDDEN = {"android.", PKG + "android."};

    static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    static void run() {
        System.out.println("Layering");
        Path src = Paths.get(System.getProperty("appsrc", "app/src")).resolve("io/github/chronicle8/lnorganizer");

        test("core files touch no I/O, clock, randomness, locale or UI", () -> {
            List<Path> files = javaFiles(src.resolve("core"));
            yes(files.size() >= 12, "only " + files.size() + " core files found");
            for (Path f : files) {
                String code = stripComments(read(f));
                for (String bad : CORE_FORBIDDEN) {
                    yes(!code.contains(bad), f.getFileName() + " uses " + bad);
                }
                for (String imp : imports(code)) {
                    yes(imp.startsWith("java.") || imp.startsWith(PKG + "core."), f.getFileName() + " imports " + imp);
                }
            }
        });

        test("io files use only core and the JDK", () -> {
            List<Path> files = javaFiles(src.resolve("io"));
            yes(files.size() >= 2, "only " + files.size() + " io files found");
            for (Path f : files) {
                String code = stripComments(read(f));
                for (String bad : IO_FORBIDDEN) yes(!code.contains(bad), f.getFileName() + " uses " + bad);
                for (String imp : imports(code)) {
                    yes(imp.startsWith("java.") || imp.startsWith(PKG + "core.") || imp.startsWith(PKG + "io."),
                            f.getFileName() + " imports " + imp);
                }
            }
        });

        test("the layering check fails on a forbidden import (the check can fail)", () -> {
            String bad = "package x;\nimport java.io.File;\nimport android.net.Uri;\nclass A {}\n";
            List<String> imps = imports(bad);
            eq(2, imps.size(), "imports found");
            yes(stripComments(bad).contains("java.io.File"), "forbidden name visible after comment stripping");
            yes(!stripComments("// java.io.File\nclass A {}").contains("java.io.File"), "comments are ignored");
        });
    }

    static List<Path> javaFiles(Path dir) throws Exception {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static List<String> imports(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(code);
        while (m.find()) out.add(m.group(2));
        return out;
    }

    /** Removes comments so that a rule cited in prose is not taken for code. String literals are kept. */
    static String stripComments(String code) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != '"') j += code.charAt(j) == '\\' ? 2 : 1;
                sb.append(code, i, Math.min(j + 1, code.length()));
                i = j + 1;
            } else if (c == '\'' ) {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != '\'') j += code.charAt(j) == '\\' ? 2 : 1;
                sb.append(code, i, Math.min(j + 1, code.length()));
                i = j + 1;
            } else if (code.startsWith("//", i)) {
                int j = code.indexOf('\n', i);
                i = j < 0 ? code.length() : j;
            } else if (code.startsWith("/*", i)) {
                int j = code.indexOf("*/", i + 2);
                i = j < 0 ? code.length() : j + 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
