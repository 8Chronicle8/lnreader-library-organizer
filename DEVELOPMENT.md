# Development

## Layout

| Path | Job |
|---|---|
| `SPEC.md` | The source of truth: backup format, list format, rules |
| `app/src/.../core/` | Pure logic: JSON, zip index and rewrite plan, backup reader, list export/parse, organize, report, output check. No I/O, clock, locale, randomness or Android |
| `app/src/.../io/` | Thin adapters shared by the app and the tests: a file-channel byte source, the plan writer |
| `app/src/.../android/` | The activity and the run log |
| `app/res/`, `app/AndroidManifest.xml` | Resources; the app asks for no permissions |
| `test/src/.../test/` | JDK-only test runner, suites, and `Cli` (desktop driver for the same core) |
| `test/fixtures/` | Fixture library, list cases with hand-written expectations, export goldens, `JSON.stringify` reference |
| `tools/make-json-fixture.js` | Regenerates the `JSON.stringify` reference with node |
| `tools/dump_backup.py` | Independent Python reader used for the cross-check |
| `build.sh` | Offline APK build: aapt2, javac, d8, zipalign, apksigner |
| `check.sh` | Every check, one verdict |

The layering is enforced by `LayeringTests`: core names nothing from `io`,
`android`, the file system, the network, the clock, the locale or threads; `io`
uses only core and the JDK.

## Checks

```sh
./check.sh                                  # tests, reference parity, APK
./check.sh --backup path/to/lnreader_backup.zip
./check.sh --release --backup path/to/backup.zip   # nothing may be skipped
```

`--backup` adds the real-backup round trips (an unedited list must reproduce the
file byte for byte; a reorganized list must write a backup that reads back with
exactly the intended categories), an independent Python cross-check of every
category and novel, and `unzip -t` / Python `zipfile` on the written file. The
backup is personal data: it is never copied into the repository.

The APK step needs `ANDROID_HOME` (build-tools 36.0.0, platform android-34) and
the signing key:

```sh
ORGANIZER_KEYSTORE=/path/organizer-signing-key.jks \
ORGANIZER_KEYSTORE_PASS=... ./build.sh --out build/apk
```

The keystore and its password live outside the repository. Losing the key means
installed copies cannot be updated in place.

Java code that ships is compiled with `-source 8 -bootclasspath android.jar`, so a
JDK-only API cannot slip in. `minSdk` is 26; it was run on API 34 only.

## Device test (not in check.sh)

The end-to-end test drives the real interfaces on an emulator and is recorded in
CHANGES.md rather than scripted, because it goes through the system file picker
and LNReader's own screens:

1. Install the APK and LNReader (official release, digest checked) on an emulator.
2. Push a backup to Downloads; open it in the app through the picker.
3. Open an edited list (`.txt`), *Check list*, *Save organized backup*.
4. Pull the written file: it must equal, byte for byte, what
   `Cli organize BACKUP LIST OUT` writes on the desktop.
5. In a fresh LNReader: restore it, then read `files/SQLite/lnreader.db`
   (root) and compare every novel's categories, by `(pluginId, path)`, with the list.

## Pitfalls

- LNReader restore merges: categories are matched by trimmed name, links are only
  added. Do not "fix" this in the app; say it to the user (SPEC §2.2).
- `Category.json` must stay `JSON.stringify`-compatible so an unedited list is a
  byte-identical no-op; the `JSON.stringify` fixture holds the writer to node.
- The `[#id source]` tag uses the backup's novel ids. A list made from a different
  backup still works only if the ids match; mismatched titles are reported as
  `TITLE_DIFFERS`.
