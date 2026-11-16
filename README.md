# Library Organizer for LNReader backups

An Android app that turns an [LNReader](https://github.com/LNReader/lnreader)
backup into a plain-text list of your categories and novels, lets you reorganize
that list (by hand, or by pasting it into an AI chat), and writes a new backup
with the new categories. Restore it in LNReader and your library is organized.

Only the categories change. Novels, reading progress, settings, plugins, covers
and downloaded chapters are copied byte for byte.

## Use

1. **In LNReader:** More → Settings → Backup → *Create backup*.
2. **In Library Organizer:** *Open backup…* and pick that `.zip`.
   The list appears in the editor:

   ```
   ## Source A
   - The Sword Saint's Second Life [#12 sourceA]
   - Tea Shop at the End of the World [#31 sourceA]

   ## Source B
   - Diary of a Lazy Wizard [#57 sourceB]
   ```

3. **Organize it**, either way:
   - **By hand:** move novel lines under other `## Category` lines; add, rename,
     delete or reorder categories. A novel under two categories goes in both.
   - **With an AI:** optionally type the categories you want, then *Copy for AI*
     (or *Share to AI app*). The copied text carries instructions for the
     assistant. Copy its answer back and tap *Paste* (or share the answer to
     Library Organizer).

   Keep the `[#number source]` tag at the end of every novel line — that is how a
   line is matched to its novel. A line that lost its tag is matched by its exact
   title when only one novel has that title.
4. *Check list* shows what the new backup will hold, and every line that was
   ignored or left out, with its line number.
5. *Save organized backup…* writes the new `.zip`, reads it back and checks it.

## Restoring: read this first

LNReader's restore **adds** categories and **never removes** them. Restoring on top
of your current library keeps all your old categories too. To get exactly the
organized library:

1. Keep your original backup file — it is your way back.
2. Phone Settings → Apps → LNReader → Storage (or Storage & cache) → *Clear storage*.
3. LNReader → More → Settings → Backup → *Restore backup* → pick the organized file.
4. Re-add your plugin repositories (More → Settings → Repositories). LNReader's
   backup restores installed plugins but not the repository list.

LNReader's built-in *Local* category always exists; if your list has no `## Local`,
it comes back empty and can be reordered in LNReader.

## What it does not do

- It does not change anything but categories.
- It does not talk to LNReader directly; the backup is the only supported way in.
- It does not contact any AI service and has no network access; you copy or share
  the list yourself. The app requests no permissions.
- It cannot remove categories from an LNReader that still has them (see above).
- Backups over 4 GB (Zip64) are refused.

The full rules are in [SPEC.md](SPEC.md).

## Build

See [DEVELOPMENT.md](DEVELOPMENT.md). In short: `./check.sh` runs every test and
builds the APK with the Android SDK's own tools — no Gradle, no network.

## Disclaimer

An independent project, not affiliated with or endorsed by LNReader. It reads and
writes only the backup files you choose, on your device.
