#!/usr/bin/env bash
# Regenerate docs/demo.cast by running the CLI under asciinema. Mirrors the recipe in
# docs/make_demo_gif.sh; the cast complements the GIF for hi-DPI viewers and gives
# readers copy-pasteable commands. The hand-crafted cast already in the repo is fine
# as a stand-in; running this overwrites it with a freshly captured session.
#
# Prereqs: a 50 MiB file served at http://localhost:8080/my-local-file.txt, exactly the
# setup in docs/DESIGN.md#demo-reproducer (docker httpd container with /tmp/demo-files
# mounted at /). Plus `asciinema` on PATH:
#
#   brew install asciinema    # macOS
#   sudo apt install asciinema # Debian/Ubuntu
#
# Usage:
#   ./gradlew installDist
#   ./docs/make_demo_cast.sh
set -euo pipefail

OUT="${1:-docs/demo.cast}"

if ! command -v asciinema >/dev/null 2>&1; then
    echo "asciinema not on PATH; install it (brew install asciinema) before regenerating." >&2
    exit 1
fi

EXPECTED_SHA="$(shasum -a 256 /tmp/demo-files/my-local-file.txt 2>/dev/null | cut -d' ' -f1)"
if [ -z "$EXPECTED_SHA" ]; then
    echo "Source file /tmp/demo-files/my-local-file.txt not found; see docs/DESIGN.md#demo-reproducer for setup." >&2
    exit 1
fi

# Record a single command and write directly to the cast file. --overwrite replaces any
# existing capture; --idle-time-limit caps the recorded silence between events so
# playback feels brisk.
asciinema rec \
    --overwrite \
    --idle-time-limit 1 \
    --command "./build/install/parallel-downloader/bin/parallel-downloader \
        --chunk-size 4MiB --parallelism 8 --sha256 $EXPECTED_SHA \
        http://localhost:8080/my-local-file.txt /tmp/dl-my-local-file.txt" \
    "$OUT"

echo "wrote $OUT ($(wc -c < "$OUT") bytes)"
