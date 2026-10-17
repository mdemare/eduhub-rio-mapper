#!/bin/sh
# Verify E2E command dispatch without contacting external services.
set -eu

cd "$(dirname "$0")/.."
sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/bin" "$sandbox/src-v5" "$sandbox/src-v6" "$sandbox/src-common" "$sandbox/resources"
cp Makefile "$sandbox/Makefile"
cat > "$sandbox/bin/clojure" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$E2E_CALLS"
if [ "$2" = "$E2E_FAIL_SUITE" ]; then
    exit 23
fi
STUB
chmod +x "$sandbox/bin/clojure"
export PATH="$sandbox/bin:$PATH"
export E2E_CALLS="$sandbox/calls"
unset MAKEFLAGS MFLAGS

check() {
    export E2E_FAIL_SUITE=$1
    expected_status=$2
    : > "$E2E_CALLS"
    status=0
    make --no-print-directory -C "$sandbox" -j2 test-e2e > "$sandbox/output" 2>&1 || status=$?
    if [ "$expected_status" = success ]; then
        test "$status" -eq 0
    else
        test "$status" -ne 0
    fi
    diff -u "$sandbox/expected" "$E2E_CALLS"
}

printf '%s\n' '-M:test-v5 v5-e2e --no-capture-output' '-M:test-v6 v6-e2e --no-capture-output' > "$sandbox/expected"
check '' success
# Target-named files must not suppress either suite.
touch "$sandbox/test-e2e" "$sandbox/test-v5-e2e" "$sandbox/test-v6-e2e"
check '' success
check v6-e2e failure
printf '%s\n' '-M:test-v5 v5-e2e --no-capture-output' > "$sandbox/expected"
check v5-e2e failure
printf '%s\n' 'E2E command dispatch OK'
