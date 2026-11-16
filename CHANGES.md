# Changes

## 1.0.0 — 2026-11-16

First version: open an LNReader backup, export the library as a text list
(plain or with AI instructions), import an edited list, write and verify a new
backup that changes only `Category.json`.

Decisions:
- LNReader 2.1.4's restore was read from its source: categories match by trimmed
  name and links are only added. The app cannot remove old categories, so the
  result screen and README tell the user to clear LNReader's storage first.
  LNReader's backup also omits the plugin repository list (confirmed on device:
  0 repositories after restore), so step 4 of the restore text says to re-add it.
- Novels missing from an edited list go to Default only if they were in a
  category before; novels that were in no category stay in none. (SPEC §6.5 was
  changed to this before the first test run, so an unedited list is a no-op.)
- Build without Gradle: SDK tools only, offline. Shipping code compiles against
  `android.jar` plus `core-lambda-stubs.jar`; the first APK build failed without
  the stubs (lambdas need `LambdaMetafactory`).

Tests corrected on their first run (Rule 4), each because the test was wrong:
- `JsonTests`: expected `"01"` to fail as a bad number; it is the number `0`
  followed by trailing text, which is how JSON.parse refuses it too.
- `ZipTests`: damaged a byte array that an already-open index also read, so a
  second assertion saw the damage. Now damages a copy.
- The fixture-coverage check failed for a real gap (only 3 of 6 list fixtures
  changed the library); fixtures 04 and 08 were added rather than the check
  loosened.

Verified:
- `./check.sh --release --backup <real backup>`: see the log in `logs/`.
- On an API 34 emulator with a real LNReader 2.1.4 backup (246 novels, 20
  categories): the app opened it through the system picker, read an AI-style list
  from a `.txt` (one prose line ignored and reported, one untagged line matched by
  title), and wrote and verified the new backup in Downloads. The file pulled from
  the device was byte-identical to the desktop `Cli organize` output for the same
  list. A fresh LNReader 2.1.4 (official x86_64 APK, GitHub digest checked)
  restored it with every novel in exactly its intended category by
  `(pluginId, path)`, chapters and read state included.
- Fixed after the device run: quotes in the help text were stripped by Android's
  resource compiler (now escaped); the repository setting is under
  More → Settings, not Browse.
