# Library Organizer — specification

This file is the source of truth (ENGINEERING-RULES Rule 1). Code and fixtures
follow it; when they disagree, the spec is fixed first and the change is noted in
CHANGES.md.

## 1. Purpose

The app reads an LNReader backup (`.zip`), writes its library as a plain-text
list of categories and novels, reads an edited list back, and writes a new backup
whose **only** change is `Category.json`. Restoring that backup in LNReader gives
the library the categories of the list.

## 2. The LNReader backup, as LNReader 2.1.4 writes and restores it

Read from LNReader's source at tag `v2.1.4`: `src/services/backup/utils.ts`,
`src/services/backup/types.ts`, `src/services/backup/local/index.ts`,
`src/database/queries/CategoryQueries.ts`, `src/database/queries/NovelQueries.ts`,
`src/database/schema/category.ts`, `src/database/constants.ts`.

### 2.1 Entries

| Entry | Content |
|---|---|
| `Version.json` | format 2: `{"appVersion":"2.1.4","formatVersion":2,"sections":{"library":…,"settings":…,"plugins":…,"downloadedFiles":…}}`; format 1: `{"version":"…"}` or absent |
| `Category.json` | JSON array, one object per category in `sort` order: `{"id":int,"name":string,"sort":int,"novelIds":[int,…]}` |
| `NovelAndChapters/<id>.json` | one novel: `id`, `name`, `pluginId`, `path`, `inLibrary`, `isLocal`, … and `chapters` |
| `Covers/<id>`, `Setting.json`, `Plugins.json`, `plugins.zip`, `novel-files.zip` | not read; copied unchanged |

All JSON is written by JavaScript `JSON.stringify` (no whitespace, UTF-8).
Entries are deflated with a data descriptor (flag bit 3).

### 2.2 Restore (what the output relies on)

1. Every `NovelAndChapters/*.json` is upserted by `(pluginId, path)`; its chapters
   are replaced. The backup's novel id is mapped to the id the novel gets.
2. Each `Category.json` object, in file order:
   - `id == 1` is the built-in Default category, whatever its name.
   - Any other category is found by its **trimmed name**
     (exact, case-sensitive: the `name` column has a unique index with binary
     collation). If none exists it is created with the backup's `sort`.
   - Each `novelIds` entry whose novel was restored is linked to the category;
     a link that already exists is left alone (`onConflictDoNothing`). Ids with
     no restored novel are dropped.
3. Restore never deletes a category and never removes a link.

**Consequence:** restoring onto an LNReader that already has categories *adds*
the organized categories next to the old ones, and novels keep their old links.
The organized library comes out exactly only when LNReader starts empty
(cleared storage or a fresh install). The app says so on its result screen.

## 3. Reading a backup

The backup is read in place (random access); it is never modified (Rule 25).

### 3.1 Zip container

Refused with a named error: not a zip (no end-of-central-directory record);
Zip64 (a Zip64 locator, or any count/size/offset field at its 16/32-bit
maximum); multi-disk; central directory not immediately followed by the end
record; an encrypted entry; two entries with the same name; a local header that
is missing or whose name differs from the central directory; entries whose data
overlap. Data before the first entry is allowed and kept.

Reading an entry: method 0 (stored) or 8 (deflate) only; its CRC-32 and size must
match the central directory. Entries that are only copied may use any method.

### 3.2 Manifest

`Version.json` is resolved as LNReader does: `formatVersion == 2` with a
`sections` object is format 2; anything else (or a missing/unparseable file) is
format 1. A format-2 backup whose `sections.library` is not `true` is refused:
it has no library to organize.

### 3.3 Categories

`Category.json` must be an array of objects, each with an integer `id`, a string
`name` and an integer array `novelIds`. Other keys are kept as they are.
Refused: missing file, wrong shapes, a duplicate `id`, a name that is empty after
trimming, two names equal after trimming (LNReader would merge them), no
category with `id == 1`.

Noted, not refused (each counted in the load notes):
- a `novelIds` entry with no novel file — dropped from the model, as restore drops it;
- the same id twice in one category — kept once.

### 3.4 Novels

Every entry named `NovelAndChapters/<digits>.json` is a novel. Other files under
`NovelAndChapters/` are noted and ignored; directory entries (ending in `/`) are
skipped. Each novel file must parse and carry `id` (integer equal to the file's
number), `name` (string), `pluginId` (string), `inLibrary` and `isLocal`
(`true`/`false`, or `1`/`0`). Two files with the same id are refused.
All problems in all files are collected and reported together; if there is any,
the backup is refused (a partial library would lose category links).

A novel is **listed** if it is in at least one category or `inLibrary` is true.
Other novels (history only) are not listed and never touched.

## 4. The list (export)

UTF-8 text, `\n` line ends. Three kinds of lines:

```
// a note — ignored on import
## Category name
- Novel title [#123 pluginId]
```

1. Header notes: library size, source file name and LNReader version, and how to
   edit (plain export) **or** the AI instructions (§4.1).
2. For each category in `Category.json` order: a blank line, `## <trimmed name>`,
   then one line per novel in it: `- <title> [#<id> <pluginId>]`, ordered by
   title (code-point order of the case-folded title), then id.
3. Listed novels in no category (LNReader does not show them) as notes:
   `// - <title> [#<id> <pluginId>]` after a note saying they stay in no
   category unless their lines are moved, without the `//`, under a heading.
4. A note with the number of unlisted (history-only) novels, if any.

In titles, CR, LF and TAB become a space. An empty category still gets its
heading, so it survives the round trip.

### 4.1 AI instructions

The AI variant replaces the how-to notes with notes asking an assistant to
return the same list sorted into categories: keep every novel line exactly,
including its tag; use `## Name` lines; put each novel under the one category
that fits best; reply with only the list. If the user typed wanted category
names, the notes ask for those (adding others only for novels that fit none);
otherwise they ask for about 8–15 categories by genre or theme.

## 5. Reading an edited list (import)

Lines are split on `\n`; a trailing `\r` and surrounding whitespace are removed.
Line numbers in messages are 1-based, as the user sees them in the editor.

| Line (after trimming) | Meaning |
|---|---|
| empty | ignored |
| starts with `//` | note, ignored |
| starts with `#` | category heading; name = text after the leading `#`s, trimmed; empty name → problem `EMPTY_CATEGORY_NAME`, and novels after it are left out until the next valid heading |
| contains exactly one tag `[#<digits>…]` | novel by id (the text before/after the tag is not used) |
| contains two or more tags | problem `TWO_TAGS`, left out |
| anything else | title match (below); no unique match → `UNRECOGNIZED` (ignored) |

A tag is `[#` + digits + optional (whitespace + any text without `]`) + `]`.

- An id that is not a listed novel of this backup → `UNKNOWN_NOVEL`, left out.
- Digits that do not fit a 32-bit id → `UNKNOWN_NOVEL`, left out.
- A novel line before the first valid heading → `NO_CATEGORY`, left out.
- A tagged line whose text (list marker and tag removed) differs from the
  backup's title after normalization → note `TITLE_DIFFERS` (the novel is still
  placed by id). This catches a list made from a different backup.

**Title match** (for lines without a tag): remove a leading list marker
(`-`, `*`, `+`, `•`, `·`, or digits followed by `.` or `)`), then normalize both
sides: map `’ ‘ ‛ ´ \`` to `'`, `“ ” „` to `"`, `– — ‒ −` to `-`, `…` to `...`,
collapse runs of whitespace to one space, case-fold each code point
(`Character.toLowerCase(int)`; no locale). Exactly one listed novel with that
normalized title → placed, with note `MATCHED_BY_TITLE`. More than one →
problem `AMBIGUOUS_TITLE`, left out. None → `UNRECOGNIZED`.

## 6. Building the organized categories

1. Categories in the order of their first heading. A heading whose trimmed name
   repeats an earlier one merges into it (note `DUPLICATE_CATEGORY`).
2. A novel listed twice in one category is kept once (note `DUPLICATE_NOVEL`).
   A novel under several categories is linked to each.
3. A heading whose trimmed name equals the trimmed name of a backup category
   keeps that category's `id`, its original `name` string and every other key.
   Other headings are new categories with ids `max(backup ids) + 1, + 2, …` in
   order.
4. The Default category (backup `id == 1`) is always written. If no heading
   names it, it is written first.
5. Novels that are in at least one backup category but appear under no
   heading are **unplaced**: they are added to Default (after Default's own
   novels, by id) and listed in the preview, so a novel the editor (or an AI)
   forgot does not vanish from the library. Novels in no backup category stay
   in none unless the list places them; this keeps an unedited list a no-op.
6. `sort` is the 1-based position in the output order.
7. Novel order inside a category is the order of first appearance in the list;
   unplaced novels follow. (LNReader orders the library view itself.)
8. Refused: a list in which no novel line was recognized.
9. **Unchanged**: if the output has the same categories (trimmed names, same
   order) with the same sets of novels as the backup, and nothing is unplaced,
   the backup's `Category.json` bytes are reused, so the written backup is
   byte-identical to the input (Rule 10).

`Category.json` is serialized like `JSON.stringify`: no whitespace, keys in
order, numbers as written, strings with `\"`, `\\`, `\b \f \n \r \t`, other
code units below U+0020 and lone surrogates as lowercase `\uXXXX`, everything
else as UTF-8.

## 7. Writing the organized backup

1. Every byte of the input is copied unchanged except the `Category.json`
   entry, which is replaced by a new local header (no data descriptor; flag
   bit 3 cleared, other flags kept; method deflate; same name bytes and
   modification time; no extra field) and the deflated content. Central
   directory records are copied with offsets shifted; the `Category.json`
   record gets the new flags, method, CRC and sizes; the end record gets the
   new central directory offset. Entry order and names are unchanged.
2. If the new `Category.json` equals the old one byte for byte, the input is
   copied whole.
3. Before writing, the input is re-read and its central directory compared with
   the one read at load time; a difference refuses the write ("the backup
   changed since it was opened").
4. After writing, the output is read back: same entry names in the same order,
   `Category.json` equal to the intended bytes, and the CRC-32 of every entry
   checked. Any failure deletes the output file and reports why.
5. The output is a new file chosen by the user; the input is never written.

## 8. Non-goals (Rule 12)

- **Only categories change.** Novels, chapters, reading progress, settings,
  plugins, covers and downloads are copied untouched.
- **No direct access to LNReader.** LNReader has no interface for this; its
  backup is the supported way in. The app needs no permissions.
- **No AI service is called.** The list is copied or shared; the app has no
  network access.
- **Cannot remove existing categories in LNReader** — restore only adds (§2.2).
- **Zip64 backups** (over 4 GB or 65 535 entries) are refused, not handled.
- **Format-1 backups** are read by the same rules, but no real format-1 backup
  was available to test against.
