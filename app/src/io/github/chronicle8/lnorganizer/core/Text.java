package io.github.chronicle8.lnorganizer.core;

/** String helpers shared by the list reader and writer. No locale is involved anywhere. */
public final class Text {
    private Text() {}

    /**
     * JavaScript String.prototype.trim(): LNReader trims category names with it, so
     * names must be compared after the same trim (SPEC §2.2).
     */
    public static String jsTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isJsSpace(s.charAt(a))) a++;
        while (b > a && isJsSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** ECMAScript WhiteSpace and LineTerminator code points. */
    static boolean isJsSpace(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ':
            case 0x00A0: case 0xFEFF: case 0x2028: case 0x2029:
                return true;
            default:
                return Character.getType(c) == Character.SPACE_SEPARATOR;
        }
    }

    /** Lower-cases each code point on its own (Character.toLowerCase(int)), independent of the device locale. */
    public static String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** Compares by code point (not UTF-16 unit), so astral characters sort by value. */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Replaces CR, LF and TAB with a space so a title stays on one line (SPEC §4). */
    public static String oneLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    /** Title normalization for matching a line without a tag (SPEC §5). */
    public static String normalizeTitle(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String mapped;
            switch (c) {
                case '’': case '‘': case '‛': case '´': case '`':
                    mapped = "'"; break;
                case '“': case '”': case '„':
                    mapped = "\""; break;
                case '–': case '—': case '‒': case '−':
                    mapped = "-"; break;
                case '…':
                    mapped = "..."; break;
                default:
                    mapped = null;
            }
            if (mapped == null && isJsSpace(c)) {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) sb.append(' ');
            space = false;
            if (mapped != null) sb.append(mapped);
            else sb.append(c);
        }
        return fold(sb.toString());
    }
}
