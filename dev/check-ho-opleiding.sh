#!/bin/sh
# Check Makefile isolation without Clojure, credentials, or external services.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
cp "$root/Makefile" "$sandbox/Makefile"
mkdir "$sandbox/bin" "$sandbox/src-v5" "$sandbox/src-v6" "$sandbox/src-common" "$sandbox/resources"
cat > "$sandbox/bin/clojure" <<'STUB'
#!/bin/sh
printf '%s %s\n' "${VCR_RECORD:-unset}" "$*" >> "$COMMAND_LOG"
exit "${COMMAND_EXIT:-0}"
STUB
chmod +x "$sandbox/bin/clojure"
export PATH="$sandbox/bin:$PATH"
export COMMAND_LOG="$sandbox/commands"
cd "$sandbox"
for version in v5 v6; do
  mkdir -p "test-$version/fixtures/vcr"
  printf 'legacy cassette\n' > "test-$version/fixtures/vcr/mapping.edn"
  for mode in record playback; do
    target="$mode-ho-opleiding-$version"
    touch "$target" # The target must be phony.
    : > "$COMMAND_LOG"
    VCR_RECORD=true make -s "$target"
    if [ "$mode" = record ]; then expected=true; else expected=false; fi
    printf '%s -M:test-%s ho-opleiding-%s\n' "$expected" "$version" "$version" > expected
    diff -u expected "$COMMAND_LOG"
    if COMMAND_EXIT=17 make -s "$target" > /dev/null 2>&1; then
      echo "Target swallowed failure: $target" >&2
      exit 1
    fi
    printf 'legacy cassette\n' > expected
    diff -u expected "test-$version/fixtures/vcr/mapping.edn"
  done
done
echo 'All four HoOpleiding targets preserve mappings, select only lookup suites, and propagate failures.'
