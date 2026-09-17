package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reads the newest `adjustments.recursive_belief` block from the AIMI decisions log.
 *
 * The block is written by `UnfoldExporter.toJsonObject`, so the shape here is the shape that class
 * produces. Only the newest matching line is needed, so the tail is short.
 */
internal object RecursiveBeliefExportReader {

    /** A few ticks back is enough: every loop tick writes one line when RBT shadow is on. */
    private const val MAX_TAIL_LINES = 80

    fun loadLastExport(
        storage: AimiStorage,
    ): JsonObject? {
        val path = T3cRuntimeHistoryReader.aimiDecisionsJsonlPath(storage)
        if (!storage.exists(path) || !storage.canRead(path)) return null
        val tail = storage.readTailLines(path, maxLines = MAX_TAIL_LINES)
        for (line in tail) {
            if (!line.contains("recursive_belief")) continue
            try {
                val root = Json.parseToJsonElement(line).jsonObject
                val adjustments = root.optJsonObjectCompat("adjustments") ?: continue
                return adjustments.optJsonObjectCompat("recursive_belief") ?: continue
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
