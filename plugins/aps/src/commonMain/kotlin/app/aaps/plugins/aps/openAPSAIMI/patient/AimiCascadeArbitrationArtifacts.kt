package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Same-tick cascade artifacts for Auditor payload (patterns + Harmonia SMB authority).
 *
 * ⚠️ ASYNC IMPACT: External Auditor reads these after the tick. They are advisory context only —
 * never used to authorize a same-tick lift. CONFIRM/SOFTEN only.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object AimiCascadeArbitrationArtifacts {

    private val physiologicalPatternsJson = AtomicReference<JsonObject?>(null)
    private val harmoniaSmbAuthorityJson = AtomicReference<JsonObject?>(null)

    fun publish(
        physiologicalPatterns: JsonObject?,
        harmoniaSmbAuthority: JsonObject?,
    ) {
        physiologicalPatternsJson.store(physiologicalPatterns)
        harmoniaSmbAuthorityJson.store(harmoniaSmbAuthority)
    }

    fun physiologicalPatterns(): JsonObject? = physiologicalPatternsJson.load()

    fun harmoniaSmbAuthority(): JsonObject? = harmoniaSmbAuthorityJson.load()

    fun clear() {
        physiologicalPatternsJson.store(null)
        harmoniaSmbAuthorityJson.store(null)
    }
}
