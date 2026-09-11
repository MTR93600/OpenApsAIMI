package app.aaps.aimi_viewer

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

internal data class DecisionIndexIdentity(
    val uri: String,
    val documentId: String,
    val zoneId: String,
    val sourceSize: Long,
    val lastModifiedMs: Long,
)

internal data class DecisionDaySegment(
    val dayKey: String,
    val startOffset: Long,
    val endOffset: Long,
    val minTimestampMs: Long,
    val maxTimestampMs: Long,
)

internal data class DecisionWindowIndex(
    val identity: DecisionIndexIdentity,
    val checkpointOffset: Long,
    val segments: List<DecisionDaySegment>,
) {
    val coverageStartMs: Long? get() = segments.minOfOrNull { it.minTimestampMs }
    val coverageEndMs: Long? get() = segments.maxOfOrNull { it.maxTimestampMs }?.plus(1L)

    fun isExact(identity: DecisionIndexIdentity): Boolean =
        this.identity == identity && checkpointOffset <= identity.sourceSize

    fun canAppend(identity: DecisionIndexIdentity): Boolean =
        this.identity.uri == identity.uri &&
            this.identity.documentId == identity.documentId &&
            this.identity.zoneId == identity.zoneId &&
            identity.sourceSize > this.identity.sourceSize &&
            identity.sourceSize >= checkpointOffset &&
            identity.lastModifiedMs >= this.identity.lastModifiedMs

    fun byteRange(startMs: Long, endMs: Long): LongRange? {
        val selected = segments.filter {
            it.maxTimestampMs >= startMs && it.minTimestampMs < endMs
        }
        if (selected.isEmpty()) return null
        return selected.minOf { it.startOffset } until selected.maxOf { it.endOffset }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("uri", identity.uri)
        put("document_id", identity.documentId)
        put("zone_id", identity.zoneId)
        put("source_size", identity.sourceSize)
        put("last_modified_ms", identity.lastModifiedMs)
        put("checkpoint_offset", checkpointOffset)
        put("segments", JSONArray().apply {
            segments.forEach { segment ->
                put(JSONObject().apply {
                    put("day", segment.dayKey)
                    put("start", segment.startOffset)
                    put("end", segment.endOffset)
                    put("min_ts", segment.minTimestampMs)
                    put("max_ts", segment.maxTimestampMs)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): DecisionWindowIndex? = runCatching {
            if (json.optInt("version") != 1) return null
            val identity = DecisionIndexIdentity(
                uri = json.getString("uri"),
                documentId = json.getString("document_id"),
                zoneId = json.getString("zone_id"),
                sourceSize = json.getLong("source_size"),
                lastModifiedMs = json.getLong("last_modified_ms"),
            )
            val rawSegments = json.getJSONArray("segments")
            val segments = buildList {
                for (index in 0 until rawSegments.length()) {
                    val item = rawSegments.getJSONObject(index)
                    add(
                        DecisionDaySegment(
                            dayKey = item.getString("day"),
                            startOffset = item.getLong("start"),
                            endOffset = item.getLong("end"),
                            minTimestampMs = item.getLong("min_ts"),
                            maxTimestampMs = item.getLong("max_ts"),
                        ),
                    )
                }
            }
            DecisionWindowIndex(
                identity = identity,
                checkpointOffset = json.getLong("checkpoint_offset"),
                segments = segments,
            )
        }.getOrNull()
    }
}

internal class DecisionIndexStore(private val target: File) {
    fun load(): DecisionWindowIndex? = runCatching {
        if (!target.isFile) return null
        DecisionWindowIndex.fromJson(JSONObject(target.readText()))
    }.getOrNull()

    fun save(index: DecisionWindowIndex) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(index.toJson().toString())
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

internal data class Utf8Line(
    val startOffset: Long,
    val endOffset: Long,
    val text: String,
)

internal data class LineScanResult(
    val lastCompleteOffset: Long,
    val skippedOversizedLines: Int,
)

/** Streams one line at a time and caps the memory used by a malformed record. */
internal object BoundedUtf8LineScanner {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_LINE_BYTES = 16 * 1024 * 1024

    fun scan(
        input: InputStream,
        startOffset: Long = 0L,
        endExclusive: Long? = null,
        emitUnterminatedTail: Boolean = false,
        onLine: (Utf8Line) -> Unit,
    ): LineScanResult {
        val buffer = ByteArray(BUFFER_SIZE)
        val line = ByteArrayOutputStream(4096)
        var absolute = startOffset
        var lineStart = startOffset
        var lastComplete = startOffset
        var overflow = false
        var oversized = 0
        while (endExclusive == null || absolute < endExclusive) {
            val remaining = endExclusive?.minus(absolute)
            val requested = if (remaining == null) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
            if (requested <= 0) break
            val read = input.read(buffer, 0, requested)
            if (read <= 0) break
            for (index in 0 until read) {
                val value = buffer[index]
                absolute++
                if (value == '\n'.code.toByte()) {
                    if (!overflow) {
                        onLine(
                            Utf8Line(
                                startOffset = lineStart,
                                endOffset = absolute,
                                text = line.toByteArray().toString(Charsets.UTF_8).trimEnd('\r'),
                            ),
                        )
                    } else {
                        oversized++
                    }
                    line.reset()
                    overflow = false
                    lineStart = absolute
                    lastComplete = absolute
                } else if (!overflow) {
                    if (line.size() < MAX_LINE_BYTES) {
                        line.write(value.toInt())
                    } else {
                        line.reset()
                        overflow = true
                    }
                }
            }
        }
        if (emitUnterminatedTail && line.size() > 0 && !overflow) {
            onLine(
                Utf8Line(
                    startOffset = lineStart,
                    endOffset = absolute,
                    text = line.toByteArray().toString(Charsets.UTF_8).trimEnd('\r'),
                ),
            )
        }
        return LineScanResult(lastCompleteOffset = lastComplete, skippedOversizedLines = oversized)
    }
}

internal object DecisionIndexBuilder {
    fun build(
        identity: DecisionIndexIdentity,
        input: InputStream,
        previous: DecisionWindowIndex? = null,
    ): DecisionWindowIndex {
        val append = previous?.takeIf { it.canAppend(identity) }
        val startOffset = append?.checkpointOffset ?: 0L
        val segments = append?.segments?.toMutableList() ?: mutableListOf()
        val scan = BoundedUtf8LineScanner.scan(input, startOffset = startOffset) { line ->
            val timestamp = timestampOfDecision(line.text) ?: return@scan
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(identity.zoneId)).toLocalDate().toString()
            val last = segments.lastOrNull()
            if (last != null && last.dayKey == day && last.endOffset <= line.startOffset) {
                segments[segments.lastIndex] = last.copy(
                    endOffset = line.endOffset,
                    minTimestampMs = minOf(last.minTimestampMs, timestamp),
                    maxTimestampMs = maxOf(last.maxTimestampMs, timestamp),
                )
            } else {
                segments += DecisionDaySegment(
                    dayKey = day,
                    startOffset = line.startOffset,
                    endOffset = line.endOffset,
                    minTimestampMs = timestamp,
                    maxTimestampMs = timestamp,
                )
            }
        }
        return DecisionWindowIndex(
            identity = identity,
            checkpointOffset = scan.lastCompleteOffset,
            segments = segments,
        )
    }

    fun timestampOfDecision(text: String): Long? = runCatching {
        parseTimestamp(JSONObject(text).opt("timestamp"))
    }.getOrNull()
}

internal fun parseTimestamp(value: Any?): Long? {
    if (value == null || value == JSONObject.NULL) return null
    if (value is Number) {
        val numeric = value.toDouble()
        if (!numeric.isFinite() || numeric <= 0) return null
        return if (numeric < 100_000_000_000.0) (numeric * 1000.0).toLong() else numeric.toLong()
    }
    val text = value.toString().trim()
    text.toDoubleOrNull()?.let { numeric ->
        if (!numeric.isFinite() || numeric <= 0) return null
        return if (numeric < 100_000_000_000.0) (numeric * 1000.0).toLong() else numeric.toLong()
    }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
