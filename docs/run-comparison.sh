#!/usr/bin/env bash
# Reproducible comparison benchmark: parallel-downloader vs curl, aria2c, wget.
#
# Brings up the project's Jetty comparison fixture (see :jettyFixture in build.gradle.kts),
# generates random test files of the requested sizes, and uses hyperfine to time each tool's
# best-effort parallel download against the fixture. Prints the results as a markdown table
# suitable to paste into docs/COMPARISON.md.
#
# The point of the comparison is to anchor parallel-downloader's MiB/s numbers in something
# a reader recognizes (curl with --parallel-max, aria2c with multi-connection, wget as the
# unparallelized baseline). The Jetty fixture isolates network noise: every tool talks to
# 127.0.0.1 with the same controlled first-byte latency, so differences come from the tools
# themselves rather than the path between them.
#
# Tool versions are pinned in COMPARISON.md's footer once the table is populated. If you
# regenerate the table, update the footer with the versions reported below.
#
# Usage:
#   docs/run-comparison.sh                        # default: sizes 10/100/1024 MiB at 0ms and 20ms
#   docs/run-comparison.sh --sizes 10,100         # smaller sweep
#   docs/run-comparison.sh --latency 0            # one latency tier only
#
# Required tools: hyperfine, curl, aria2c, wget.
# Required artifacts: ./build/install/parallel-downloader (run `./gradlew installDist` first).
set -euo pipefail

# --- defaults --------------------------------------------------------------
SIZES="10,100,1024"
LATENCIES="0,20"
PORT=8090
WARMUP=1
RUNS=5
ROOT="$(mktemp -d -t comparison.XXXXXX)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOWNLOADER_BIN="$PROJECT_ROOT/build/install/parallel-downloader/bin/parallel-downloader"

# --- parse args ------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --sizes) SIZES="$2"; shift 2 ;;
        --latency|--latencies) LATENCIES="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --runs) RUNS="$2"; shift 2 ;;
        --warmup) WARMUP="$2"; shift 2 ;;
        --root) ROOT="$2"; shift 2 ;;
        -h|--help)
            sed -n '3,28p' "$0"
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 64 ;;
    esac
done

# --- preflight -------------------------------------------------------------
missing=0
for tool in hyperfine curl aria2c wget; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing tool: $tool" >&2
        missing=1
    fi
done
if [ ! -x "$DOWNLOADER_BIN" ]; then
    echo "Missing artifact: $DOWNLOADER_BIN" >&2
    echo "  Run: (cd \"$PROJECT_ROOT\" && ./gradlew installDist)" >&2
    missing=1
fi
if [ "$missing" -ne 0 ]; then
    cat >&2 <<'EOF'

Install hints (macOS):
  brew install hyperfine aria2 wget
Install hints (Debian/Ubuntu):
  sudo apt-get install hyperfine aria2 wget   # hyperfine may need a backport / cargo install

Then build the local CLI:
  ./gradlew installDist
EOF
    exit 1
fi

# --- generate test files ---------------------------------------------------
mkdir -p "$ROOT"
trap 'rm -rf "$ROOT"' EXIT
echo "[setup] test files under $ROOT"
IFS=',' read -ra SIZE_ARR <<< "$SIZES"
for sz in "${SIZE_ARR[@]}"; do
    f="$ROOT/file-${sz}MiB.bin"
    if [ ! -f "$f" ]; then
        # dd from /dev/urandom matches the project's existing demo reproducer pattern.
        # bs=1M and the requested count produces an exact-size file.
        dd if=/dev/urandom of="$f" bs=1m count="$sz" 2>/dev/null
    fi
done

# --- run sweeps ------------------------------------------------------------
printf "\n## Comparison results\n\n"
printf "Tool versions:\n"
hyperfine --version | head -1 | sed 's/^/  /'
curl --version | head -1 | sed 's/^/  /'
aria2c --version 2>&1 | head -1 | sed 's/^/  /'
wget --version 2>&1 | head -1 | sed 's/^/  /'
printf "  parallel-downloader (HEAD %s)\n\n" "$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD)"

IFS=',' read -ra LAT_ARR <<< "$LATENCIES"
for lat in "${LAT_ARR[@]}"; do
    echo "[fixture] starting Jetty on :$PORT with firstByteLatency=${lat}ms"
    (cd "$PROJECT_ROOT" && ./gradlew --no-daemon jettyFixture --args="--port $PORT --latency $lat --root $ROOT") \
        > /tmp/jetty-fixture.log 2>&1 &
    FIXPID=$!
    # Wait for "listening" line in the log (poll up to 30s).
    for _ in $(seq 1 30); do
        if grep -q "Jetty fixture listening" /tmp/jetty-fixture.log 2>/dev/null; then break; fi
        sleep 1
    done

    printf "\n### firstByteLatencyMillis = %s\n\n" "$lat"
    printf "| Size | parallel-downloader | curl --parallel-max 8 | aria2c -x 8 -s 8 | wget |\n"
    printf "|------|---------------------|-----------------------|-------------------|------|\n"

    for sz in "${SIZE_ARR[@]}"; do
        f="file-${sz}MiB.bin"
        url="http://127.0.0.1:$PORT/$f"
        out="/tmp/cmp-out-${sz}.bin"

        json=$(hyperfine --warmup "$WARMUP" --runs "$RUNS" --export-json /dev/stdout \
            --command-name parallel-downloader \
                "rm -f \"$out\" && \"$DOWNLOADER_BIN\" --chunk-size 8MiB --parallelism 8 \"$url\" \"$out\"" \
            --command-name curl \
                "rm -f \"$out\" && curl --silent --output \"$out\" --parallel --parallel-max 8 \"$url\"" \
            --command-name aria2c \
                "rm -f \"$out\" && aria2c -q -x 8 -s 8 -d /tmp -o cmp-out-${sz}.bin \"$url\"" \
            --command-name wget \
                "rm -f \"$out\" && wget -q -O \"$out\" \"$url\"" \
            2>/dev/null) || true

        # Extract mean times (in seconds) from hyperfine's JSON.
        # Awk-only to avoid a jq dependency.
        get_mean() {
            local name=$1
            echo "$json" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for r in data['results']:
    if r['command'] == '$name':
        mean = r['mean']
        mib_per_sec = $sz / mean
        print(f'{mib_per_sec:.0f} MiB/s ({mean*1000:.0f}ms)')
        break
"
        }

        printf "| %s MiB | %s | %s | %s | %s |\n" \
            "$sz" "$(get_mean parallel-downloader)" "$(get_mean curl)" \
            "$(get_mean aria2c)" "$(get_mean wget)"
    done

    kill -INT "$FIXPID" 2>/dev/null || true
    wait "$FIXPID" 2>/dev/null || true
done

printf "\nGenerated by docs/run-comparison.sh on %s\n" "$(date)"
