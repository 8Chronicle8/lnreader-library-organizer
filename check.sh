#!/usr/bin/env bash
# Every check, one verdict (Rule 14).
#
#   ./check.sh                    unit and fixture tests, JSON reference parity, APK build
#   ./check.sh --backup FILE      also the real-backup round trips and an independent cross-check
#   ./check.sh --release --backup FILE   nothing may be skipped
#
# Missing optional tools (node, the Android SDK, the signing key) skip their step by
# name; --release turns every skip into a failure. The log goes to logs/.
set -uo pipefail
cd "$(dirname "$0")"

BACKUP=""
RELEASE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP="$2"; shift 2 ;;
    --release) RELEASE=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

mkdir -p logs
LOG="logs/check-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1
echo "check.sh $* — $(date -Is)"
echo "code: $(git rev-parse --short HEAD 2>/dev/null || echo 'no commit') $(git status --porcelain 2>/dev/null | wc -l) changed files"
echo "java: $(java -version 2>&1 | head -1)"

PASSED=(); FAILED=(); SKIPPED=()
pass() { PASSED+=("$1"); echo "== PASS $1"; }
fail() { FAILED+=("$1"); echo "== FAIL $1"; }
skip() { SKIPPED+=("$1 ($2)"); echo "== SKIP $1: $2"; }

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
JVM="build/jvm"

echo; echo "== compile (JVM)"
rm -rf "$JVM"; mkdir -p "$JVM"
if javac --release 11 -Xlint:all,-serial -Werror -encoding UTF-8 -d "$JVM" \
     $(find app/src/io/github/chronicle8/lnorganizer/core app/src/io/github/chronicle8/lnorganizer/io test/src -name '*.java'); then
  pass "compile"
else
  fail "compile"
fi

echo; echo "== tests"
TEST_ARGS=(-Dfixtures=test/fixtures -Dappsrc=app/src)
if [ -n "$BACKUP" ]; then TEST_ARGS+=("-Dbackup=$BACKUP" "-Dscratch=$SCRATCH"); fi
NO_SKIP=(); [ "$RELEASE" = 1 ] && NO_SKIP=(--no-skip)
java "${TEST_ARGS[@]}" -cp "$JVM" io.github.chronicle8.lnorganizer.test.TestMain "${NO_SKIP[@]}"
case $? in
  0) pass "tests" ;;
  2) fail "tests (a test was skipped in --release)" ;;
  *) fail "tests" ;;
esac

echo; echo "== JSON.stringify reference parity"
if command -v node >/dev/null; then
  if node tools/make-json-fixture.js | diff -q - test/fixtures/json-stringify.txt; then pass "json reference"; else fail "json reference"; fi
else
  skip "json reference" "node not installed"
fi

echo; echo "== real backup: independent cross-check and zip tools"
if [ -n "$BACKUP" ]; then
  if java -cp "$JVM" io.github.chronicle8.lnorganizer.test.Cli dump "$BACKUP" > "$SCRATCH/java.txt" \
     && python3 -I tools/dump_backup.py "$BACKUP" > "$SCRATCH/python.txt" \
     && diff "$SCRATCH/python.txt" "$SCRATCH/java.txt" > "$SCRATCH/diff.txt"; then
    echo "$(grep -c '^category' "$SCRATCH/java.txt") categories and $(grep -c '^novel' "$SCRATCH/java.txt") novels agree"
    pass "cross-check with Python reader"
  else
    head -20 "$SCRATCH/diff.txt"; fail "cross-check with Python reader"
  fi
  ORG="$SCRATCH/organized-by-letter.zip"
  if [ -f "$ORG" ] && unzip -tq "$ORG" && python3 -I -m zipfile -t "$ORG" >/dev/null; then
    pass "organized backup passes unzip -t and Python zipfile"
  else
    fail "organized backup passes unzip -t and Python zipfile"
  fi
else
  skip "real backup" "no --backup given"
fi

echo; echo "== APK"
if [ -z "${ANDROID_HOME:-}" ]; then
  skip "apk" "ANDROID_HOME not set"
elif [ -z "${ORGANIZER_KEYSTORE:-}" ] || [ -z "${ORGANIZER_KEYSTORE_PASS:-}" ]; then
  skip "apk" "ORGANIZER_KEYSTORE / ORGANIZER_KEYSTORE_PASS not set"
elif ./build.sh --out build/apk; then
  pass "apk"
else
  fail "apk"
fi

echo
echo "passed ${#PASSED[@]}, failed ${#FAILED[@]}, skipped ${#SKIPPED[@]}"
for f in "${FAILED[@]}"; do echo "  failed:  $f"; done
for s in "${SKIPPED[@]}"; do echo "  skipped: $s"; done
echo "log: $LOG"
if [ ${#FAILED[@]} -gt 0 ]; then echo "VERDICT: FAIL"; exit 1; fi
if [ "$RELEASE" = 1 ] && [ ${#SKIPPED[@]} -gt 0 ]; then echo "VERDICT: FAIL (skips are not allowed with --release)"; exit 1; fi
echo "VERDICT: PASS"
