package com.arcu.attnd.data.log

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogRecord
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The diagnostics ring buffer (logging spec §2): JSON lines in [dir], about [maxBytes] in total, newest
 * kept. Two halves, `current.jsonl` and `previous.jsonl`; when the current one passes half the budget it
 * becomes the previous one (the older half is dropped). Records get their `seq` here, in write order,
 * continuing from the last stored record across restarts. Plain JVM file code (unit-tested); callers
 * use it from one writer thread ([AndroidLogSink]) and read it from IO coroutines.
 */
class LogStore(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val current = File(dir, "current.jsonl")
    private val previous = File(dir, "previous.jsonl")
    private var nextSeq: Long = -1

    /** Assigns `seq` to [records], appends them and returns the lines written. Never throws. */
    @Synchronized
    fun append(records: List<LogRecord>): List<String> {
        if (records.isEmpty()) return emptyList()
        if (nextSeq < 0) nextSeq = lastSeq() + 1
        val lines = records.map { LogJson.encode(it.copy(seq = nextSeq++)) }
        try {
            dir.mkdirs()
            current.appendText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (current.length() > maxBytes / 2) {
                val dropped = previous.length()
                previous.delete()
                if (!current.renameTo(previous)) current.delete()
                // Queued by the sink for the next write (never re-enters this call).
                AppLog.trace(LogCategory.LOGGING, LogEvent.RING_TRIMMED, "Log ring buffer trimmed", mapOf("bytes" to dropped))
            }
        } catch (_: IOException) {
            // The ring buffer is best effort (disk full).
        }
        return lines
    }

    /** Every stored line, oldest first. */
    @Synchronized
    fun lines(): List<String> = listOf(previous, current).flatMap { file ->
        try {
            if (file.exists()) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun records(): List<LogRecord> = lines().mapNotNull(LogJson::decode)

    /** Writes [lines] to [file] as `.jsonl` (export / share). */
    fun export(file: File, lines: List<String> = lines()) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        current.delete()
        previous.delete()
    }

    /** The highest stored seq, from the end of the newest file (0 when empty). */
    private fun lastSeq(): Long {
        for (file in listOf(current, previous)) {
            val line = lastLine(file) ?: continue
            LogJson.decode(line)?.let { return it.seq }
        }
        return 0
    }

    private fun lastLine(file: File): String? = try {
        if (!file.exists() || file.length() == 0L) {
            null
        } else {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val start = (size - TAIL_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((size - start).toInt())
                raf.readFully(bytes)
                bytes.toString(Charsets.UTF_8).trimEnd('\n').substringAfterLast('\n')
            }
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        private const val TAIL_BYTES = 16 * 1024L
    }
}
