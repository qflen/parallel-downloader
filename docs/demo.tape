# VHS script for the README demo GIF.
# Render with:  vhs docs/demo.tape
# Prereq:       a 50 MiB file served at http://127.0.0.1:8888/medium.bin
#               (see docs/DESIGN.md or the manual-demo block in README.md)

Output docs/demo.gif

Set Theme "Aardvark Blue"
Set FontSize 14
Set Width 1100
Set Height 460
Set Padding 18
Set TypingSpeed 30ms
Set PlaybackSpeed 1.0

Type "# parallel-downloader: 50 MiB across 8 ranged GETs"
Sleep 600ms
Enter
Sleep 400ms

Type "./build/install/parallel-downloader/bin/parallel-downloader \"
Enter
Type "    --chunk-size 4MiB --parallelism 8 \"
Enter
Type "    http://127.0.0.1:8888/medium.bin /tmp/dl-medium.bin"
Sleep 500ms
Enter
Sleep 1500ms

Type "shasum -a 256 /tmp/demo-files/medium.bin /tmp/dl-medium.bin"
Sleep 400ms
Enter
Sleep 2500ms
