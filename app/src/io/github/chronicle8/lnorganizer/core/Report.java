package io.github.chronicle8.lnorganizer.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The preview shown before writing: what the new backup will hold and every line that was not used. */
public final class Report {
    private Report() {}

    public static String render(Library lib, Organize.Result r) {
        StringBuilder sb = new StringBuilder();
        Set<Integer> placed = new HashSet<>();
        for (Organize.OutCategory c : r.categories) placed.addAll(c.novelIds);
        int leftOut = r.leftOutCount();

        if (r.unchanged) {
            line(sb, "No changes: the list matches the backup, so the new backup would be identical to it.");
        } else {
            line(sb, "New backup: " + ListExport.plural(placed.size(), "novel") + " in "
                    + ListExport.plural(r.categories.size(), "category", "categories") + ".");
            line(sb, ListExport.plural(r.movedCount, "novel is", "novels are") + " in different categories than before.");
        }
        if (leftOut > 0) {
            line(sb, ListExport.plural(leftOut, "line was", "lines were") + " left out or ignored — see below.");
        }
        if (!r.unplaced.isEmpty()) {
            line(sb, ListExport.plural(r.unplaced.size(), "novel is", "novels are") + " not in the list and will go to "
                    + ListParse.quote(lib.defaultCategory().trimmedName) + ".");
        }

        sb.append('\n');
        line(sb, "Categories (" + r.categories.size() + "):");
        for (Organize.OutCategory c : r.categories) {
            String tag = c.original == null ? "  [new]" : "";
            line(sb, "  " + Text.oneLine(c.trimmedName) + " — " + ListExport.plural(c.novelIds.size(), "novel") + tag);
        }
        if (!r.removed.isEmpty()) {
            sb.append('\n');
            line(sb, "Not in the list, so not in the new backup (" + r.removed.size() + "):");
            for (Library.Category c : r.removed) {
                line(sb, "  " + Text.oneLine(c.trimmedName) + " — had " + ListExport.plural(c.novelIds.size(), "novel"));
            }
        }
        if (!r.unplaced.isEmpty()) {
            sb.append('\n');
            line(sb, "Missing from the list, added to " + ListParse.quote(lib.defaultCategory().trimmedName)
                    + " (" + r.unplaced.size() + "):");
            for (Integer id : r.unplaced) line(sb, "  " + ListExport.novelLine(lib.novels.get(id)));
        }

        List<Issue> problems = new ArrayList<>();
        List<Issue> notes = new ArrayList<>();
        for (Issue i : r.issues) (i.kind.leftOut ? problems : notes).add(i);
        if (!problems.isEmpty()) {
            sb.append('\n');
            line(sb, "Left out or ignored (" + problems.size() + "):");
            for (Issue i : problems) line(sb, "  " + i);
        }
        if (!notes.isEmpty()) {
            sb.append('\n');
            line(sb, "Notes (" + notes.size() + "):");
            for (Issue i : notes) line(sb, "  " + i);
        }
        if (!lib.notes.isEmpty()) {
            sb.append('\n');
            line(sb, "About the backup (" + lib.notes.size() + "):");
            for (String n : lib.notes) line(sb, "  " + n);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
