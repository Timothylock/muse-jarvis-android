package dev.timlock.jarvis.core.secure

import java.io.Writer

/** §6.7 export helper: keeps the LAST [maxLines] lines (and at most ~[maxChars] chars), each passed through [Redactor]. */
object LogRedactWriter {
    const val MAX_LINES = 20_000
    const val MAX_CHARS = 4 * 1024 * 1024

    /** Writes redacted lines to [out] (newline-terminated); returns the number of lines written. Nothing unredacted is written. */
    fun write(lines: Sequence<String>, out: Writer, maxLines: Int = MAX_LINES, maxChars: Int = MAX_CHARS): Int {
        val keep = ArrayDeque<String>()
        var chars = 0L
        for (raw in lines) {
            val line = Redactor.redact(raw)
            keep.addLast(line)
            chars += line.length + 1
            while (keep.size > maxLines || (chars > maxChars && keep.size > 1)) {
                chars -= keep.removeFirst().length + 1
            }
        }
        for (l in keep) { out.write(l); out.write("\n") }
        out.flush()
        return keep.size
    }
}
