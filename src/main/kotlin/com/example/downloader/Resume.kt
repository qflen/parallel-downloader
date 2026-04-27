package com.example.downloader

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicReference

/**
 * Side-state persisted alongside a partial download so a future invocation can pick up where
 * a previous one left off. Co-located with the destination as `<destination>.partial`, and
 * deleted on successful completion.
 *
 * The recorded [entityValidator] is the gating safety check: if a future probe sees a different
 * ETag/Last-Modified, the resume is refused and the partial file is discarded — splicing two
 * different file versions on disk would silently corrupt the result.
 */
internal data class ResumeState(
    val totalBytes: Long,
    val chunkSize: Long,
    val entityValidator: String?,
    val completedChunks: Set<Int>,
)

/**
 * Plain-text serialization for [ResumeState]. Format is intentionally simple (no JSON dep) so
 * a curious user can `cat` the sidecar to see what's resumable. `version=1` lets future
 * versions detect and discard incompatible state rather than misinterpret it.
 */
internal object ResumeSidecar {
    private const val VERSION = "1"

    fun pathFor(destination: Path): Path =
        destination.resolveSibling("${destination.fileName}.partial")

    fun load(destination: Path): ResumeState? {
        val sidecar = pathFor(destination)
        if (!Files.isRegularFile(sidecar)) return null
        return try {
            parse(Files.readString(sidecar))
        } catch (_: IOException) {
            null
        }
    }

    fun save(destination: Path, state: ResumeState) {
        val text = buildString {
            appendLine("version=$VERSION")
            appendLine("total=${state.totalBytes}")
            appendLine("chunkSize=${state.chunkSize}")
            appendLine("validator=${state.entityValidator.orEmpty()}")
            appendLine("completed=${state.completedChunks.sorted().joinToString(",")}")
        }
        Files.writeString(pathFor(destination), text, CREATE, TRUNCATE_EXISTING, WRITE)
    }

    fun delete(destination: Path) {
        Files.deleteIfExists(pathFor(destination))
    }

    private fun parse(text: String): ResumeState? {
        val map = text.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .toMap()
        val total = map["total"]?.toLongOrNull()
        val chunkSize = map["chunkSize"]?.toLongOrNull()
        val versionOk = map["version"] == VERSION
        val validator = map["validator"]?.takeIf { it.isNotEmpty() }
        val completed = map["completed"]?.takeIf { it.isNotEmpty() }
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        return if (versionOk && total != null && chunkSize != null) {
            ResumeState(total, chunkSize, validator, completed)
        } else {
            null
        }
    }
}

/**
 * In-memory tracker that batches sidecar writes. The download orchestrator calls
 * [recordChunkComplete] from each chunk's coroutine; this class serializes the writes through
 * an atomic-set + synchronized flush so concurrent chunk completions can't corrupt the file.
 *
 * The flush is best-effort — a sidecar write failure doesn't fail the download (we'd lose
 * resume capability, but the bytes already on disk are still correct).
 */
internal class ResumeTracker(
    private val destination: Path,
    private val totalBytes: Long,
    private val chunkSize: Long,
    private val entityValidator: String?,
    initialCompleted: Set<Int>,
) {
    private val completed = AtomicReference<Set<Int>>(initialCompleted)
    private val writeLock = Any()

    fun completedChunks(): Set<Int> = completed.get()

    fun recordChunkComplete(chunkIndex: Int) {
        completed.updateAndGet { it + chunkIndex }
        synchronized(writeLock) {
            // Sidecar write is best-effort — a failure here doesn't fail the download (we'd
            // lose resume capability for this run, but the bytes already on disk stay correct).
            runCatching {
                ResumeSidecar.save(
                    destination,
                    ResumeState(totalBytes, chunkSize, entityValidator, completed.get()),
                )
            }
        }
    }

    fun delete() {
        synchronized(writeLock) {
            runCatching { ResumeSidecar.delete(destination) }
        }
    }
}
