package io.github.chronicle8.lnorganizer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Writes the library as the editable text list (SPEC §4). */
public final class ListExport {
    private ListExport() {}

    public enum Mode { PLAIN, AI }

    /**
     * @param sourceName the backup's file name, shown in the header (may be null)
     * @param wantedCategories for {@link Mode#AI}: category names the user asked for, comma or line separated; may be empty
     */
    public static String export(Library lib, String sourceName, Mode mode, String wantedCategories) {
        StringBuilder sb = new StringBuilder();
        List<Library.Novel> listed = lib.listedNovels();
        line(sb, "// LNReader library: " + plural(listed.size(), "novel") + " in "
                + plural(lib.categories.size(), "category", "categories"));
        String from = sourceName == null ? "backup" : Text.oneLine(sourceName);
        line(sb, "// From " + from + (lib.appVersion == null ? "" : " (LNReader " + Text.oneLine(lib.appVersion) + ")"));
        line(sb, "//");
        switch (mode) {
            case PLAIN:
                howTo(sb);
                break;
            case AI:
                aiInstructions(sb, wantedCategories == null ? "" : wantedCategories);
                break;
            default:
                throw new IllegalArgumentException("unknown mode " + mode);
        }

        for (Library.Category c : lib.categories) {
            sb.append('\n');
            line(sb, "## " + Text.oneLine(c.trimmedName));
            List<Library.Novel> novels = new ArrayList<>();
            for (Integer id : c.novelIds) novels.add(lib.novels.get(id));
            sortByTitle(novels);
            for (Library.Novel n : novels) line(sb, novelLine(n));
        }

        Set<Integer> categorized = lib.categorizedNovelIds();
        List<Library.Novel> loose = new ArrayList<>();
        for (Library.Novel n : listed) if (!categorized.contains(n.id)) loose.add(n);
        if (!loose.isEmpty()) {
            sortByTitle(loose);
            sb.append('\n');
            line(sb, "// In the library but in no category, so LNReader does not show them: "
                    + plural(loose.size(), "novel") + ".");
            line(sb, "// They stay that way unless you move their lines, without the leading //, under a category:");
            for (Library.Novel n : loose) line(sb, "// " + novelLine(n));
        }
        int unlisted = lib.unlistedCount();
        if (unlisted > 0) {
            sb.append('\n');
            line(sb, "// " + plural(unlisted, "novel is", "novels are")
                    + " only in reading history (not in the library); not listed, left unchanged.");
        }
        return sb.toString();
    }

    public static String novelLine(Library.Novel n) {
        return "- " + Text.oneLine(n.name) + " [#" + n.id + " " + Text.oneLine(n.pluginId) + "]";
    }

    private static void howTo(StringBuilder sb) {
        line(sb, "// How to edit:");
        line(sb, "//   ## Name                 a category: rename, add, delete or reorder freely");
        line(sb, "//   - Title [#12 source]    a novel: move its line to move the novel");
        line(sb, "//   Keep the [#number source] tag at the end of every novel line.");
        line(sb, "//   A novel listed under two categories goes in both.");
        line(sb, "//   A novel missing from the list goes to Default.");
        line(sb, "//   Lines starting with // are notes and are ignored.");
    }

    private static void aiInstructions(StringBuilder sb, String wanted) {
        List<String> names = splitNames(wanted);
        line(sb, "// Please organize the novel library below into categories.");
        if (names.isEmpty()) {
            line(sb, "// - Choose about 8 to 15 clear categories by genre or theme.");
        } else {
            line(sb, "// - Use these categories: " + String.join(", ", names) + ".");
            line(sb, "//   Add another category only for novels that fit none of them.");
        }
        line(sb, "// - Write each category as a line \"## Category name\", followed by its novels.");
        line(sb, "// - Copy every novel line exactly as it is, including the [#number source] tag at the end.");
        line(sb, "// - Put each novel under the one category that fits it best. Do not leave any novel out.");
        line(sb, "// - Reply with only the organized list in this same format, without any other text.");
    }

    static List<String> splitNames(String wanted) {
        List<String> out = new ArrayList<>();
        for (String part : wanted.split("[,\\n]")) {
            String t = Text.jsTrim(Text.oneLine(part));
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static void sortByTitle(List<Library.Novel> novels) {
        Collections.sort(novels, new Comparator<Library.Novel>() {
            @Override public int compare(Library.Novel a, Library.Novel b) {
                int c = Text.compareCodePoints(Text.fold(a.name), Text.fold(b.name));
                return c != 0 ? c : Integer.compare(a.id, b.id);
            }
        });
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    static String plural(int n, String one) {
        return plural(n, one, one + "s");
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
