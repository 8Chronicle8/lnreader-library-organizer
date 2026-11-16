package io.github.chronicle8.lnorganizer.core;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the library part of an LNReader backup (SPEC §3.2–3.4). */
public final class BackupReader {
    private BackupReader() {}

    public static final String CATEGORY_JSON = "Category.json";
    static final String VERSION_JSON = "Version.json";
    static final String NOVEL_DIR = "NovelAndChapters/";
    static final int MAX_JSON_BYTES = 64 << 20;
    private static final Pattern NOVEL_FILE = Pattern.compile("NovelAndChapters/([0-9]+)\\.json");
    private static final Set<String> SKIP_CHAPTERS = Collections.singleton("chapters");

    /** The backup cannot be organized; every problem found is listed. */
    public static final class BackupException extends Exception {
        public final List<String> problems;

        BackupException(List<String> problems) {
            super(problems.size() == 1 ? problems.get(0)
                    : problems.size() + " problems, first: " + problems.get(0));
            this.problems = Collections.unmodifiableList(problems);
        }

        BackupException(String problem) {
            this(Collections.singletonList(problem));
        }
    }

    public interface Progress {
        void novelsRead(int done, int total);
    }

    public static Library read(ZipIndex zip, Progress progress) throws IOException, BackupException {
        List<String> notes = new ArrayList<>();
        String appVersion = null;
        int formatVersion = 1;

        ZipIndex.Entry version = zip.find(VERSION_JSON);
        if (version != null) {
            Object v;
            try {
                v = Json.parse(Json.decodeUtf8(readEntry(zip, version)));
            } catch (Json.JsonException | CharacterCodingException e) {
                // LNReader reads an unparseable Version.json as format 1 (getBackupManifest).
                notes.add(VERSION_JSON + " could not be read (" + e.getMessage() + "); treated as format 1, as LNReader does");
                v = null;
            }
            if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                Object fv = m.get("formatVersion");
                Object sections = m.get("sections");
                if (fv instanceof Json.Num && "2".equals(((Json.Num) fv).raw) && sections instanceof Map) {
                    formatVersion = 2;
                    Object av = m.get("appVersion");
                    if (av == null) av = m.get("version");
                    if (av instanceof String) appVersion = (String) av;
                    if (((Map<?, ?>) sections).get("library") != Boolean.TRUE) {
                        throw new BackupException("this backup was made without the library section, so it has no categories to organize");
                    }
                } else if (m.get("version") instanceof String) {
                    appVersion = (String) m.get("version");
                }
            }
        }

        ZipIndex.Entry catEntry = zip.find(CATEGORY_JSON);
        if (catEntry == null) throw new BackupException("the backup has no " + CATEGORY_JSON + "; is this an LNReader backup?");
        byte[] categoryJson = readEntry(zip, catEntry);

        List<String> problems = new ArrayList<>();
        Map<Integer, Library.Novel> novels = readNovels(zip, problems, notes, progress);
        List<Library.Category> categories = readCategories(categoryJson, novels, problems, notes);
        if (!problems.isEmpty()) throw new BackupException(problems);
        return new Library(appVersion, formatVersion, categories, novels, categoryJson, notes);
    }

    private static byte[] readEntry(ZipIndex zip, ZipIndex.Entry e) throws IOException, BackupException {
        try {
            return zip.read(e, MAX_JSON_BYTES);
        } catch (ZipFormatException ex) {
            throw new BackupException(ex.getMessage());
        }
    }

    private static Map<Integer, Library.Novel> readNovels(ZipIndex zip, List<String> problems, List<String> notes,
                                                         Progress progress) throws IOException {
        List<ZipIndex.Entry> files = new ArrayList<>();
        for (ZipIndex.Entry e : zip.entries()) {
            if (!e.name.startsWith(NOVEL_DIR) || e.isDirectory()) continue;
            if (NOVEL_FILE.matcher(e.name).matches()) files.add(e);
            else notes.add(e.name + " is not a novel file; ignored (LNReader's restore would fail on it)");
        }
        Map<Integer, Library.Novel> novels = new TreeMap<>();
        int done = 0;
        for (ZipIndex.Entry e : files) {
            Matcher m = NOVEL_FILE.matcher(e.name);
            if (!m.matches()) throw new IllegalStateException("filtered above: " + e.name);
            Integer fileId = parseId(m.group(1));
            try {
                Library.Novel n = readNovel(zip, e, fileId);
                if (novels.containsKey(n.id)) problems.add(e.name + ": a second novel with id " + n.id);
                else novels.put(n.id, n);
            } catch (BackupException ex) {
                problems.add(ex.getMessage());
            }
            done++;
            if (progress != null) progress.novelsRead(done, files.size());
        }
        return novels;
    }

    private static Library.Novel readNovel(ZipIndex zip, ZipIndex.Entry e, Integer fileId)
            throws IOException, BackupException {
        Object v;
        try {
            v = Json.parse(Json.decodeUtf8(zip.read(e, MAX_JSON_BYTES)), SKIP_CHAPTERS);
        } catch (ZipFormatException ex) {
            throw new BackupException(ex.getMessage());
        } catch (Json.JsonException ex) {
            throw new BackupException(e.name + ": not valid JSON (" + ex.getMessage() + ")");
        } catch (CharacterCodingException ex) {
            throw new BackupException(e.name + ": not valid UTF-8");
        }
        if (!(v instanceof Map)) throw new BackupException(e.name + ": not a JSON object");
        Map<?, ?> m = (Map<?, ?>) v;
        Integer id = m.get("id") instanceof Json.Num ? ((Json.Num) m.get("id")).intValue() : null;
        if (id == null) throw new BackupException(e.name + ": \"id\" is missing or not an integer");
        if (!id.equals(fileId)) throw new BackupException(e.name + ": \"id\" is " + id + ", the file name says " + fileId);
        String name = requireString(m, "name", e.name);
        String pluginId = requireString(m, "pluginId", e.name);
        boolean inLibrary = requireFlag(m, "inLibrary", e.name);
        boolean isLocal = requireFlag(m, "isLocal", e.name);
        return new Library.Novel(id, name, pluginId, inLibrary, isLocal);
    }

    private static String requireString(Map<?, ?> m, String key, String where) throws BackupException {
        Object v = m.get(key);
        if (!(v instanceof String)) throw new BackupException(where + ": \"" + key + "\" is missing or not a string");
        return (String) v;
    }

    /** true/false, or 1/0 (SPEC §3.4). */
    private static boolean requireFlag(Map<?, ?> m, String key, String where) throws BackupException {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Json.Num) {
            String raw = ((Json.Num) v).raw;
            if (raw.equals("1")) return true;
            if (raw.equals("0")) return false;
        }
        throw new BackupException(where + ": \"" + key + "\" is missing or not true/false");
    }

    private static List<Library.Category> readCategories(byte[] bytes, Map<Integer, Library.Novel> novels,
                                                        List<String> problems, List<String> notes) {
        Object v;
        try {
            v = Json.parse(Json.decodeUtf8(bytes));
        } catch (Json.JsonException e) {
            problems.add(CATEGORY_JSON + ": not valid JSON (" + e.getMessage() + ")");
            return new ArrayList<>();
        } catch (CharacterCodingException e) {
            problems.add(CATEGORY_JSON + ": not valid UTF-8");
            return new ArrayList<>();
        }
        if (!(v instanceof List)) {
            problems.add(CATEGORY_JSON + ": not a JSON array");
            return new ArrayList<>();
        }
        List<Library.Category> out = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Map<String, String> trimmedNames = new HashMap<>();
        int index = 0;
        for (Object o : (List<?>) v) {
            index++;
            String where = CATEGORY_JSON + " category " + index;
            if (!(o instanceof Map)) {
                problems.add(where + ": not a JSON object");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Integer id = m.get("id") instanceof Json.Num ? ((Json.Num) m.get("id")).intValue() : null;
            if (id == null) {
                problems.add(where + ": \"id\" is missing or not an integer");
                continue;
            }
            if (!(m.get("name") instanceof String)) {
                problems.add(where + " (id " + id + "): \"name\" is missing or not a string");
                continue;
            }
            String name = (String) m.get("name");
            where = CATEGORY_JSON + " category \"" + name + "\" (id " + id + ")";
            if (!(m.get("novelIds") instanceof List)) {
                problems.add(where + ": \"novelIds\" is missing or not an array");
                continue;
            }
            LinkedHashSet<Integer> novelIds = new LinkedHashSet<>();
            boolean bad = false;
            for (Object n : (List<?>) m.get("novelIds")) {
                Integer nid = n instanceof Json.Num ? ((Json.Num) n).intValue() : null;
                if (nid == null) {
                    problems.add(where + ": \"novelIds\" holds " + Json.stringify(n) + ", not an integer");
                    bad = true;
                    break;
                }
                if (!novelIds.add(nid)) {
                    notes.add(where + " lists novel " + nid + " twice; kept once");
                } else if (!novels.containsKey(nid)) {
                    notes.add(where + " lists novel " + nid + ", which has no file in the backup; LNReader drops this link on restore");
                }
            }
            if (bad) continue;
            if (!ids.add(id)) {
                problems.add(where + ": a second category with id " + id);
                continue;
            }
            String trimmed = Text.jsTrim(name);
            if (trimmed.isEmpty()) {
                problems.add(where + ": the name is empty");
                continue;
            }
            if (trimmedNames.containsKey(trimmed)) {
                problems.add(where + ": same name as category \"" + trimmedNames.get(trimmed)
                        + "\" once spaces are trimmed; LNReader would merge them — rename one in LNReader first");
                continue;
            }
            trimmedNames.put(trimmed, name);
            List<Integer> kept = new ArrayList<>();
            for (Integer nid : novelIds) if (novels.containsKey(nid)) kept.add(nid);
            out.add(new Library.Category(id, name, kept, m));
        }
        if (problems.isEmpty() && !ids.contains(Library.DEFAULT_CATEGORY_ID)) {
            problems.add(CATEGORY_JSON + ": no Default category (id 1)");
        }
        return out;
    }

    private static Integer parseId(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            // A number past int range cannot equal any novel id; the id check reports it.
            return null;
        }
    }
}
