package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.File
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class TpoPersistence(
    private val storageHelper: AimiStorageHelper,
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
        val file = sessionFile()
        if (!file.exists()) return null
        return runCatching {
            TpoSessionDocument.fromJsonObject(Json.parseToJsonElement(file.readText()).jsonObject)
        }.getOrNull()
    }

    fun saveSession(document: TpoSessionDocument?) {
        val file = sessionFile()
        if (document == null) {
            if (file.exists()) file.delete()
            return
        }
        storageHelper.saveFileSafe(file, prettyJson.encodeToString(serializer<JsonElement>(), document.toJsonObject()))
    }

    fun loadLedger(): TpoEpisodeLedger {
        val file = ledgerFile()
        if (!file.exists()) return TpoEpisodeLedger()
        return runCatching {
            TpoEpisodeLedger.fromJsonObject(Json.parseToJsonElement(file.readText()).jsonObject)
        }.getOrDefault(TpoEpisodeLedger())
    }

    fun saveLedger(ledger: TpoEpisodeLedger) {
        storageHelper.saveFileSafe(ledgerFile(), prettyJson.encodeToString(serializer<JsonElement>(), ledger.toJsonObject()))
    }

    fun loadLastRevertAtMsByPack(): Map<TpoPackId, Long> {
        val file = metaFile()
        if (!file.exists()) return emptyMap()
        val json = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return emptyMap()
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
        storageHelper.saveFileSafe(metaFile(), prettyJson.encodeToString(serializer<JsonElement>(), json))
    }

    private fun sessionFile(): File = storageHelper.getAimiFile(directoryName, sessionFileName)

    private fun ledgerFile(): File = storageHelper.getAimiFile(directoryName, ledgerFileName)

    private fun metaFile(): File = storageHelper.getAimiFile(directoryName, metaFileName)
}
