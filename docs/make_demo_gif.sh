#!/bin/bash
# Build a terminal-style demo GIF of the parallel-downloader CLI using ImageMagick.

set -euo pipefail

OUT_GIF="${1:-docs/demo.gif}"
FRAMES_DIR="$(mktemp -d)"
trap "rm -rf $FRAMES_DIR" EXIT

W=1100
H=300
FONT=/System/Library/Fonts/Menlo.ttc
BG='#1d1f21'
FG='#c5c8c6'
PROMPT='#8abeb7'
OK='#b5bd68'
LINE_HEIGHT=22
LEFT_MARGIN=18
TOP_MARGIN=24

# render_frame OUT_PNG ; reads "color|line" pairs from stdin (one per row).
# Lines may not contain backslashes (ImageMagick -draw treats them as escapes).
render_frame() {
    local out="$1"
    local args=()
    local y=$TOP_MARGIN
    while IFS='|' read -r color line; do
        # Escape single quotes (no backslash escaping needed: they're absent by convention)
        local safe="${line//\'/\\\'}"
        args+=(-fill "$color" -draw "text $LEFT_MARGIN,$y '$safe'")
        y=$((y + LINE_HEIGHT))
    done
    magick -size ${W}x${H} xc:"$BG" \
        -font "$FONT" \
        -pointsize 15 \
        -gravity NorthWest \
        "${args[@]}" \
        "$out"
}

# Multi-line CLI command. Indentation alone implies continuation; trailing backslashes
# are dropped because ImageMagick's -draw text parser treats them as escapes. The URL
# matches the docker-httpd recipe in docs/DESIGN.md#demo-reproducer (host port 8080,
# directory mount serving /tmp/demo-files at /).
CMD_L1='$ parallel-downloader --chunk-size 4MiB --parallelism 8'
CMD_L2='    --sha256 c18c0796ed8575c484490bb2df2f4f4a1097eb1e33038c91c36cb8cb0916f54c'
CMD_L3='    http://localhost:8080/my-local-file.txt /tmp/dl-my-local-file.txt'

P_HEAD='downloading...'
P1="$P_HEAD     4.0 /   50.0 MiB    8.0%   30.09 MiB/s    0.13s"
P2="$P_HEAD    14.0 /   50.0 MiB   28.0%   43.37 MiB/s    0.32s"
P3="$P_HEAD    32.0 /   50.0 MiB   64.0%   85.50 MiB/s    0.34s"
P4="$P_HEAD    50.0 /   50.0 MiB  100.0%  138.71 MiB/s    0.36s"

SAVED='✓ saved 50.0 MiB to /tmp/dl-my-local-file.txt in 394.986083ms'
SHA_OK='✓ sha256 matches: c18c0796ed8575c484490bb2df2f4f4a1097eb1e33038c91c36cb8cb0916f54c'

# All three command lines share the prompt color so the wrapped command reads as one
# coherent block: in a real terminal, the lines after the leading "$" are the same input,
# just visually wrapped. The progress / saved / sha-match lines below are the program's
# own output, rendered in FG / OK colors.

# Frame 1: command typed
render_frame "$FRAMES_DIR/01.png" <<EOF
${PROMPT}|${CMD_L1}
${PROMPT}|${CMD_L2}
${PROMPT}|${CMD_L3}
EOF

# Frames 2-5: progress evolves
for entry in "02 $P1" "03 $P2" "04 $P3" "05 $P4"; do
    idx=${entry%% *}
    line=${entry#* }
    render_frame "$FRAMES_DIR/$idx.png" <<EOF
${PROMPT}|${CMD_L1}
${PROMPT}|${CMD_L2}
${PROMPT}|${CMD_L3}
${FG}|${line}
EOF
done

# Frame 6: success line
render_frame "$FRAMES_DIR/06.png" <<EOF
${PROMPT}|${CMD_L1}
${PROMPT}|${CMD_L2}
${PROMPT}|${CMD_L3}
${OK}|${SAVED}
EOF

# Frame 7: SHA-256 verification line printed inline by --sha256
render_frame "$FRAMES_DIR/07.png" <<EOF
${PROMPT}|${CMD_L1}
${PROMPT}|${CMD_L2}
${PROMPT}|${CMD_L3}
${OK}|${SAVED}
${OK}|${SHA_OK}
EOF

# Compose GIF
magick -loop 0 \
    -delay 110 "$FRAMES_DIR/01.png" \
    -delay 25  "$FRAMES_DIR/02.png" \
    -delay 25  "$FRAMES_DIR/03.png" \
    -delay 25  "$FRAMES_DIR/04.png" \
    -delay 30  "$FRAMES_DIR/05.png" \
    -delay 90  "$FRAMES_DIR/06.png" \
    -delay 320 "$FRAMES_DIR/07.png" \
    -layers Optimize \
    "$OUT_GIF"

echo "wrote $OUT_GIF ($(wc -c < "$OUT_GIF") bytes)"
