package io.github.chronicle8.lnorganizer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a backup says about the library: its categories and novels (SPEC §3). Immutable. */
public final class Library {
    public static final class Novel {
        public final int id;
        public final String name;
        public final String pluginId;
        public final boolean inLibrary;
        public final boolean isLocal;

        public Novel(int id, String name, String pluginId, boolean inLibrary, boolean isLocal) {
            this.id = id;
            this.name = name;
            this.pluginId = pluginId;
            this.inLibrary = inLibrary;
            this.isLocal = isLocal;
        }
    }

    public static final class Category {
        public final int id;
        /** The name exactly as stored; restore trims it (SPEC §2.2). */
        public final String name;
        public final String trimmedName;
        /** Novel ids in file order, without duplicates or ids that have no novel file. */
        public final List<Integer> novelIds;
        /** The category's JSON object as read, for keeping keys this app does not know. */
        final Map<String, Object> raw;

        Category(int id, String name, List<Integer> novelIds, Map<String, Object> raw) {
            this.id = id;
            this.name = name;
            this.trimmedName = Text.jsTrim(name);
            this.novelIds = Collections.unmodifiableList(novelIds);
            this.raw = raw;
        }
    }

    public static final int DEFAULT_CATEGORY_ID = 1;

    /** Format 2 manifest's appVersion, format 1's version, or null when absent. */
    public final String appVersion;
    public final int formatVersion;
    public final List<Category> categories;
    /** Every novel file in the backup, by id. */
    public final Map<Integer, Novel> novels;
    /** Category.json exactly as stored. */
    final byte[] categoryJson;
    /** Things noticed while reading that did not stop it (SPEC §3.3–3.4), one line each. */
    public final List<String> notes;

    Library(String appVersion, int formatVersion, List<Category> categories, Map<Integer, Novel> novels,
            byte[] categoryJson, List<String> notes) {
        this.appVersion = appVersion;
        this.formatVersion = formatVersion;
        this.categories = Collections.unmodifiableList(categories);
        this.novels = Collections.unmodifiableMap(novels);
        this.categoryJson = categoryJson;
        this.notes = Collections.unmodifiableList(notes);
    }

    public Category defaultCategory() {
        for (Category c : categories) if (c.id == DEFAULT_CATEGORY_ID) return c;
        throw new IllegalStateException("library without a Default category");
    }

    public Set<Integer> categorizedNovelIds() {
        Set<Integer> s = new HashSet<>();
        for (Category c : categories) s.addAll(c.novelIds);
        return s;
    }

    /** A novel is listed when it is in a category or in the library (SPEC §3.4). */
    public boolean isListed(Novel n) {
        return n.inLibrary || categorizedNovelIds().contains(n.id);
    }

    /** Listed novels in id order. */
    public List<Novel> listedNovels() {
        Set<Integer> categorized = categorizedNovelIds();
        List<Novel> out = new ArrayList<>();
        for (Novel n : novels.values()) if (n.inLibrary || categorized.contains(n.id)) out.add(n);
        return out;
    }

    public int unlistedCount() {
        return novels.size() - listedNovels().size();
    }
}
