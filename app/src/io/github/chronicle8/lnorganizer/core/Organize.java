package io.github.chronicle8.lnorganizer.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Turns a parsed list into the new Category.json (SPEC §6). */
public final class Organize {
    private Organize() {}

    /** The list cannot be used at all. */
    public static final class NothingToImport extends Exception {
        NothingToImport(String message) { super(message); }
    }

    public static final class OutCategory {
        public final int id;
        /** The name written to Category.json: the backup's own string for an existing category. */
        public final String name;
        public final String trimmedName;
        /** The backup category this one keeps, or null for a new one. */
        public final Library.Category original;
        /** Line of the first heading for it, or 0 when it was added (Default only). */
        public final int line;
        public final List<Integer> novelIds;

        OutCategory(int id, String name, String trimmedName, Library.Category original, int line, List<Integer> novelIds) {
            this.id = id;
            this.name = name;
            this.trimmedName = trimmedName;
            this.original = original;
            this.line = line;
            this.novelIds = Collections.unmodifiableList(novelIds);
        }
    }

    public static final class Result {
        public final List<OutCategory> categories;
        /** Novels that were in a backup category but are missing from the list, added to Default, in id order. */
        public final List<Integer> unplaced;
        /** Backup categories that are not in the list. */
        public final List<Library.Category> removed;
        /** Parse issues and organize notes, by line. */
        public final List<Issue> issues;
        /** Listed novels whose set of categories differs from the backup's. */
        public final int movedCount;
        /** True when nothing changes: categoryJson is the backup's own bytes (SPEC §6.9). */
        public final boolean unchanged;
        public final byte[] categoryJson;

        Result(List<OutCategory> categories, List<Integer> unplaced, List<Library.Category> removed,
               List<Issue> issues, int movedCount, boolean unchanged, byte[] categoryJson) {
            this.categories = Collections.unmodifiableList(categories);
            this.unplaced = Collections.unmodifiableList(unplaced);
            this.removed = Collections.unmodifiableList(removed);
            this.issues = Collections.unmodifiableList(issues);
            this.movedCount = movedCount;
            this.unchanged = unchanged;
            this.categoryJson = categoryJson;
        }

        public int leftOutCount() {
            int n = 0;
            for (Issue i : issues) if (i.kind.leftOut) n++;
            return n;
        }
    }

    private static final class Builder {
        final String name;
        final int line;
        final LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        final Map<Integer, Integer> lineOf = new HashMap<>();

        Builder(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    public static Result build(Library lib, ListParse.Result list) throws NothingToImport {
        if (list.items.isEmpty()) {
            throw new NothingToImport("No novel lines were recognized in the list. Each novel line needs its "
                    + "[#number source] tag, and must come after a \"## Category\" line.");
        }
        List<Issue> issues = new ArrayList<>(list.issues);

        LinkedHashMap<String, Builder> builders = new LinkedHashMap<>();
        Map<ListParse.Heading, Builder> byHeading = new HashMap<>();
        for (ListParse.Heading h : list.headings) {
            Builder b = builders.get(h.name);
            if (b == null) {
                b = new Builder(h.name, h.line);
                builders.put(h.name, b);
            } else {
                issues.add(new Issue(h.line, Issue.Kind.DUPLICATE_CATEGORY, "category " + ListParse.quote(h.name)
                        + " already started on line " + b.line + "; merged into it"));
            }
            byHeading.put(h, b);
        }
        for (ListParse.Item item : list.items) {
            Builder b = byHeading.get(item.heading);
            if (b.ids.add(item.novelId)) {
                b.lineOf.put(item.novelId, item.line);
            } else {
                issues.add(new Issue(item.line, Issue.Kind.DUPLICATE_NOVEL, ListParse.quote(lib.novels.get(item.novelId).name)
                        + " is already in " + ListParse.quote(b.name) + " (line " + b.lineOf.get(item.novelId) + "); kept once"));
            }
        }

        Library.Category def = lib.defaultCategory();
        List<Builder> order = new ArrayList<>(builders.values());
        Builder defBuilder = builders.get(def.trimmedName);
        if (defBuilder == null) {
            defBuilder = new Builder(def.trimmedName, 0);
            order.add(0, defBuilder);
        }

        Set<Integer> placed = new HashSet<>();
        for (Builder b : order) placed.addAll(b.ids);
        List<Integer> unplaced = new ArrayList<>();
        for (Integer id : new TreeSet<>(lib.categorizedNovelIds())) {
            if (!placed.contains(id)) {
                unplaced.add(id);
                defBuilder.ids.add(id);
            }
        }

        Map<String, Library.Category> originals = new HashMap<>();
        long nextId = 0;
        for (Library.Category c : lib.categories) {
            originals.put(c.trimmedName, c);
            nextId = Math.max(nextId, c.id);
        }
        nextId++;
        List<OutCategory> out = new ArrayList<>();
        for (Builder b : order) {
            Library.Category orig = originals.get(b.name);
            int id;
            if (orig != null) {
                id = orig.id;
            } else {
                if (nextId > Integer.MAX_VALUE) throw new IllegalStateException("category ids exhausted");
                id = (int) nextId++;
            }
            out.add(new OutCategory(id, orig != null ? orig.name : b.name, b.name, orig, b.line, new ArrayList<>(b.ids)));
        }

        Set<String> kept = new HashSet<>();
        for (OutCategory c : out) kept.add(c.trimmedName);
        List<Library.Category> removed = new ArrayList<>();
        for (Library.Category c : lib.categories) if (!kept.contains(c.trimmedName)) removed.add(c);

        int moved = countMoved(lib, out);
        boolean unchanged = unplaced.isEmpty() && sameAsBackup(lib, out);
        byte[] json = unchanged ? lib.categoryJson.clone() : serialize(out);

        Collections.sort(issues, new Comparator<Issue>() {
            @Override public int compare(Issue a, Issue b) { return Integer.compare(a.line, b.line); }
        });
        return new Result(out, unplaced, removed, issues, moved, unchanged, json);
    }

    private static boolean sameAsBackup(Library lib, List<OutCategory> out) {
        if (out.size() != lib.categories.size()) return false;
        for (int i = 0; i < out.size(); i++) {
            OutCategory o = out.get(i);
            Library.Category c = lib.categories.get(i);
            if (!o.trimmedName.equals(c.trimmedName)) return false;
            if (!new HashSet<>(o.novelIds).equals(new HashSet<>(c.novelIds))) return false;
        }
        return true;
    }

    private static int countMoved(Library lib, List<OutCategory> out) {
        Map<Integer, Set<String>> before = new HashMap<>();
        Map<Integer, Set<String>> after = new HashMap<>();
        for (Library.Category c : lib.categories) for (Integer id : c.novelIds) add(before, id, c.trimmedName);
        for (OutCategory c : out) for (Integer id : c.novelIds) add(after, id, c.trimmedName);
        int moved = 0;
        for (Library.Novel n : lib.listedNovels()) {
            Set<String> b = before.containsKey(n.id) ? before.get(n.id) : Collections.<String>emptySet();
            Set<String> a = after.containsKey(n.id) ? after.get(n.id) : Collections.<String>emptySet();
            if (!a.equals(b)) moved++;
        }
        return moved;
    }

    private static void add(Map<Integer, Set<String>> m, Integer id, String name) {
        Set<String> s = m.get(id);
        if (s == null) m.put(id, s = new HashSet<>());
        s.add(name);
    }

    static byte[] serialize(List<OutCategory> out) {
        List<Object> arr = new ArrayList<>();
        int sort = 1;
        for (OutCategory c : out) {
            List<Object> ids = new ArrayList<>();
            for (Integer id : c.novelIds) ids.add(Json.Num.of(id));
            Map<String, Object> m;
            if (c.original != null) {
                m = new LinkedHashMap<>(c.original.raw);
                m.put("sort", Json.Num.of(sort));
                m.put("novelIds", ids);
            } else {
                m = new LinkedHashMap<>();
                m.put("id", Json.Num.of(c.id));
                m.put("name", c.name);
                m.put("sort", Json.Num.of(sort));
                m.put("novelIds", ids);
            }
            arr.add(m);
            sort++;
        }
        return Json.stringify(arr).getBytes(StandardCharsets.UTF_8);
    }
}
