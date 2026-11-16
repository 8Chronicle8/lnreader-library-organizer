package io.github.chronicle8.lnorganizer.core;

/** Something noticed in an edited list, tied to the line the user sees (SPEC §5–6). */
public final class Issue {
    public enum Kind {
        /** A heading with no name; novels after it are left out until the next heading. */
        EMPTY_CATEGORY_NAME(true),
        /** A novel line before any (valid) heading; left out. */
        NO_CATEGORY(true),
        /** A tag whose id is not a listed novel of this backup; left out. */
        UNKNOWN_NOVEL(true),
        /** A line without a tag whose title fits several novels; left out. */
        AMBIGUOUS_TITLE(true),
        /** A line with more than one tag; left out. */
        TWO_TAGS(true),
        /** Not a heading, a note or a novel; ignored. */
        UNRECOGNIZED(true),
        /** The line's text differs from the tagged novel's title; placed by id anyway. */
        TITLE_DIFFERS(false),
        /** A line without a tag placed by its title. */
        MATCHED_BY_TITLE(false),
        /** A heading repeating an earlier one; merged into it. */
        DUPLICATE_CATEGORY(false),
        /** A novel listed twice in one category; kept once. */
        DUPLICATE_NOVEL(false);

        /** True when the line's content was left out or ignored. */
        public final boolean leftOut;

        Kind(boolean leftOut) { this.leftOut = leftOut; }
    }

    /** 1-based line number in the edited text. */
    public final int line;
    public final Kind kind;
    public final String message;

    public Issue(int line, Kind kind, String message) {
        this.line = line;
        this.kind = kind;
        this.message = message;
    }

    @Override public String toString() {
        return "line " + line + ": " + message;
    }
}
