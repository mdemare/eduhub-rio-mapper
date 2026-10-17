#!/bin/sh
# Verify Redis command dispatch without contacting external services.
set -eu

cd "$(dirname "$0")/.."
sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/bin" "$sandbox/src-v5" "$sandbox/src-v6" "$sandbox/src-common" "$sandbox/resources"
cp Makefile "$sandbox/Makefile"
cat > "$sandbox/bin/clojure" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$REDIS_CALLS"
if [ "$2" = "$REDIS_FAIL_SUITE" ]; then
    exit 23
fi
STUB
chmod +x "$sandbox/bin/clojure"
export PATH="$sandbox/bin:$PATH"
export REDIS_CALLS="$sandbox/calls"
unset MAKEFLAGS MFLAGS

check() {
    export REDIS_FAIL_SUITE=$1
    expected_status=$2
    : > "$REDIS_CALLS"
    status=0
    make --no-print-directory -C "$sandbox" -j2 test-redis > "$sandbox/output" 2>&1 || status=$?
    if [ "$expected_status" = success ]; then
        test "$status" -eq 0
    else
        test "$status" -ne 0
    fi
    diff -u "$sandbox/expected" "$REDIS_CALLS"
}

printf '%s\n' '-M:test-v5 v5-redis' '-M:test-v6 v6-redis' > "$sandbox/expected"
check '' success
# Target-named files must not suppress either suite.
touch "$sandbox/test-redis" "$sandbox/test-v5-redis" "$sandbox/test-v6-redis"
check '' success
check v6-redis failure
printf '%s\n' '-M:test-v5 v5-redis' > "$sandbox/expected"
check v5-redis failure
printf '%s\n' 'Redis command dispatch OK'
