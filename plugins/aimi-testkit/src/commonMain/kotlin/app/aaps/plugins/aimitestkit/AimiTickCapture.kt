package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiTickResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One executable replay tick (`engine-replay-v1` envelope).
 *
 * It carries everything `AimiEngine.evaluate` needs (input, state, models) plus the
 * recorded [expected] result, so a replay can re-run the engine and compare.
 * See `_docs/kmp/annex-8-state-replay-and-extraction-contract.md`, section 6.
 *
 * File storage is a shell concern: the Android/iOS shell reads and writes the JSON
 * (or CBOR) files. This module only defines the in-memory shape and the codec entry
 * points. No `java.io`, no `android.*` here.
 */
@Serializable
data class AimiTickCapture(
    val input: AimiInputSnapshot,
    val state: AimiEngineState,
    val models: AimiModelBundle,
    val expected: AimiTickResult,
) {

    /**
     * Encodes this capture to JSON.
     *
     * Contract types in `:plugins:aimi-contracts` carry `@Serializable`
     * (kotlinx.serialization 1.11.0).
     */
    fun encodeToString(): String = Json.encodeToString(serializer(), this)

    companion object {

        /**
         * Decodes a capture from JSON.
         */
        fun decodeFromString(json: String): AimiTickCapture =
            Json.decodeFromString(serializer(), json)
    }
}
