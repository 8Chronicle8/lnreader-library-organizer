package io.github.chronicle8.lnorganizer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads an edited list against the library it was made from (SPEC §5). */
public final class ListParse {
    private ListParse() {}

    public static final class Heading {
        public final int line;
        /** Trimmed name. */
        public final String name;

        Heading(int line, String name) {
            this.line = line;
            this.name = name;
        }
    }

    public static final class Item {
        public final int line;
        public final int novelId;
        public final Heading heading;

        Item(int line, int novelId, Heading heading) {
            this.line = line;
            this.novelId = novelId;
            this.heading = heading;
        }
    }

    public static final class Result {
        public final List<Heading> headings;
        public final List<Item> items;
        public final List<Issue> issues;

        Result(List<Heading> headings, List<Item> items, List<Issue> issues) {
            this.headings = Collections.unmodifiableList(headings);
            this.items = Collections.unmodifiableList(items);
            this.issues = Collections.unmodifiableList(issues);
        }
    }

    static final Pattern TAG = Pattern.compile("\\[#([0-9]+)(?:\\s[^\\]]*)?\\]");
    static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+•·]|[0-9]+[.)])\\s+");

    public static Result parse(String text, Library lib) {
        Map<Integer, Library.Novel> listed = new HashMap<>();
        Map<String, List<Library.Novel>> byTitle = new HashMap<>();
        for (Library.Novel n : lib.listedNovels()) {
            listed.put(n.id, n);
            String key = Text.normalizeTitle(n.name);
            List<Library.Novel> l = byTitle.get(key);
            if (l == null) byTitle.put(key, l = new ArrayList<>());
            l.add(n);
        }

        List<Heading> headings = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Heading current = null;
        int emptyHeadingLine = 0;

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String s = Text.jsTrim(lines[i]);
            if (s.isEmpty() || s.startsWith("//")) continue;

            if (s.startsWith("#")) {
                int k = 0;
                while (k < s.length() && s.charAt(k) == '#') k++;
                String name = Text.jsTrim(s.substring(k));
                if (name.isEmpty()) {
                    issues.add(new Issue(lineNo, Issue.Kind.EMPTY_CATEGORY_NAME,
                            "category line without a name; novels below it are left out until the next category"));
                    current = null;
                    emptyHeadingLine = lineNo;
                } else {
                    current = new Heading(lineNo, name);
                    headings.add(current);
                }
                continue;
            }

            Matcher m = TAG.matcher(s);
            List<String> ids = new ArrayList<>();
            while (m.find()) ids.add(m.group(1));
            Library.Novel novel;
            if (ids.size() > 1) {
                issues.add(new Issue(lineNo, Issue.Kind.TWO_TAGS, "the line has " + ids.size()
                        + " [#number] tags; left out: " + quote(s)));
                continue;
            } else if (ids.size() == 1) {
                novel = parseId(ids.get(0)) == null ? null : listed.get(parseId(ids.get(0)));
                if (novel == null) {
                    issues.add(new Issue(lineNo, Issue.Kind.UNKNOWN_NOVEL, "no novel #" + ids.get(0)
                            + " in this backup's library; left out: " + quote(s)));
                    continue;
                }
                String lineTitle = Text.jsTrim(stripMarker(TAG.matcher(s).replaceAll("")));
                if (!lineTitle.isEmpty() && !Text.normalizeTitle(lineTitle).equals(Text.normalizeTitle(novel.name))) {
                    issues.add(new Issue(lineNo, Issue.Kind.TITLE_DIFFERS, "#" + novel.id + " is " + quote(novel.name)
                            + " in this backup, the line says " + quote(lineTitle) + "; placed by number"));
                }
            } else {
                String title = Text.jsTrim(stripMarker(s));
                List<Library.Novel> found = byTitle.get(Text.normalizeTitle(title));
                if (found == null) {
                    issues.add(new Issue(lineNo, Issue.Kind.UNRECOGNIZED,
                            "not a category, a novel or a note; ignored: " + quote(s)));
                    continue;
                }
                if (found.size() > 1) {
                    StringBuilder which = new StringBuilder();
                    for (Library.Novel n : found) {
                        if (which.length() > 0) which.append(", ");
                        which.append("#").append(n.id).append(" ").append(n.pluginId);
                    }
                    issues.add(new Issue(lineNo, Issue.Kind.AMBIGUOUS_TITLE, quote(title) + " fits "
                            + found.size() + " novels (" + which + "); add the [#number] tag; left out"));
                    continue;
                }
                novel = found.get(0);
                issues.add(new Issue(lineNo, Issue.Kind.MATCHED_BY_TITLE, "no tag; matched " + quote(novel.name)
                        + " [#" + novel.id + " " + novel.pluginId + "] by its title"));
            }

            if (current == null) {
                String why = emptyHeadingLine > 0 ? "after the nameless category on line " + emptyHeadingLine
                        : "before the first category";
                issues.add(new Issue(lineNo, Issue.Kind.NO_CATEGORY, quote(novel.name) + " is " + why + "; left out"));
                continue;
            }
            items.add(new Item(lineNo, novel.id, current));
        }
        return new Result(headings, items, issues);
    }

    static String stripMarker(String s) {
        return LIST_MARKER.matcher(s).replaceFirst("");
    }

    private static Integer parseId(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            // Past int range: no novel can have this id; reported as UNKNOWN_NOVEL by the caller.
            return null;
        }
    }

    static String quote(String s) {
        String one = Text.oneLine(s);
        if (one.codePointCount(0, one.length()) > 120) one = one.substring(0, one.offsetByCodePoints(0, 117)) + "...";
        return "\"" + one + "\"";
    }
}
