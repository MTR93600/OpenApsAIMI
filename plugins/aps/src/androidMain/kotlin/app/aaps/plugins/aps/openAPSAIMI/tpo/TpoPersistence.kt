package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class TpoPersistence(
    private val storage: AimiStorage,
) {
    private val directoryName = "tpo"
    private val sessionFileName = "tpo_session.json"
    private val ledgerFileName = "tpo_episode_ledger.json"
    private val metaFileName = "tpo_meta.json"

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun loadSession(): TpoSessionDocument? {
        val path = sessionFile()
        if (!storage.exists(path)) return null
        return runCatching {
            TpoSessionDocument.fromJsonObject(Json.parseToJsonElement(storage.readText(path).orEmpty()).jsonObject)
        }.getOrNull()
    }

    fun saveSession(document: TpoSessionDocument?) {
        val path = sessionFile()
        if (document == null) {
            if (storage.exists(path)) storage.delete(path)
            return
        }
        storage.replaceText(path, prettyJson.encodeToString(serializer<JsonElement>(), document.toJsonObject()))
    }

    fun loadLedger(): TpoEpisodeLedger {
        val path = ledgerFile()
        if (!storage.exists(path)) return TpoEpisodeLedger()
        return runCatching {
            TpoEpisodeLedger.fromJsonObject(Json.parseToJsonElement(storage.readText(path).orEmpty()).jsonObject)
        }.getOrDefault(TpoEpisodeLedger())
    }

    fun saveLedger(ledger: TpoEpisodeLedger) {
        storage.replaceText(ledgerFile(), prettyJson.encodeToString(serializer<JsonElement>(), ledger.toJsonObject()))
    }

    fun loadLastRevertAtMsByPack(): Map<TpoPackId, Long> {
        val path = metaFile()
        if (!storage.exists(path)) return emptyMap()
        val json = runCatching { Json.parseToJsonElement(storage.readText(path).orEmpty()).jsonObject }.getOrNull() ?: return emptyMap()
        val revertObj = json.optJsonObjectCompat("last_revert_at_ms_by_pack") ?: return emptyMap()
        return buildMap {
            TpoPackId.entries.forEach { pack ->
                if (revertObj.hasCompat(pack.name)) {
                    put(pack, revertObj.optLongCompat(pack.name, 0L))
                }
            }
        }
    }

    fun saveLastRevertAtMsByPack(map: Map<TpoPackId, Long>) {
        val json = buildJsonObject {
            put(
                "last_revert_at_ms_by_pack",
                buildJsonObject {
                    map.forEach { (pack, value) -> put(pack.name, value) }
                },
            )
        }
        storage.replaceText(metaFile(), prettyJson.encodeToString(serializer<JsonElement>(), json))
    }

    private fun sessionFile(): AimiPath = storage.file(directoryName, sessionFileName)

    private fun ledgerFile(): AimiPath = storage.file(directoryName, ledgerFileName)

    private fun metaFile(): AimiPath = storage.file(directoryName, metaFileName)
}
