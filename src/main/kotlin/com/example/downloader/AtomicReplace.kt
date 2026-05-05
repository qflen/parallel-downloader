package com.example.downloader

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Pattern: tiny shared helper. Renames [src] to [dst] atomically when the underlying
 * filesystem supports it, falling back to a non-atomic replace when it does not (some
 * network filesystems, FAT volumes, and bind mounts surface
 * [AtomicMoveNotSupportedException] from `Files.move(... ATOMIC_MOVE)`).
 *
 * Used by:
 *   - the downloader's `.part` to destination assembly (so a kill-9 mid-write cannot
 *     leave a half-written destination indistinguishable from a complete one); and
 *   - the resume sidecar's `.partial.tmp` to `.partial` flush (so a torn-write cannot
 *     defeat resume on the next run).
 *
 * Deliberately silent: PRIVACY.md forbids logging path components, so we don't log the
 * fallback. The fallback's correctness comes from `REPLACE_EXISTING` plus the writer's
 * commit ordering (write to a side path, then move), not from atomicity at the rename
 * step itself.
 */
internal fun atomicReplace(src: Path, dst: Path) {
    atomicReplace(src, dst, ::moveAtomic, ::moveReplace)
}

internal fun moveAtomic(src: Path, dst: Path) {
    Files.move(src, dst, ATOMIC_MOVE, REPLACE_EXISTING)
}

internal fun moveReplace(src: Path, dst: Path) {
    Files.move(src, dst, REPLACE_EXISTING)
}

/**
 * Test seam: the production [atomicReplace] is a thin wrapper over this. Tests can substitute
 * the move strategies to exercise the fallback branch on filesystems where
 * [AtomicMoveNotSupportedException] does not naturally fire.
 */
internal fun atomicReplace(
    src: Path,
    dst: Path,
    atomic: (Path, Path) -> Unit,
    fallback: (Path, Path) -> Unit,
) {
    try {
        atomic(src, dst)
    } catch (_: AtomicMoveNotSupportedException) {
        fallback(src, dst)
    }
}
