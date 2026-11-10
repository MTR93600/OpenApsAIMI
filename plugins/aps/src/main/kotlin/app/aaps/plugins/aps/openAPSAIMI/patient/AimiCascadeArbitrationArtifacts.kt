package app.aaps.plugins.aps.openAPSAIMI.patient

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Same-tick cascade artifacts for Auditor payload (patterns + Harmonia SMB authority).
 *
 * ⚠️ ASYNC IMPACT: External Auditor reads these after the tick. They are advisory context only —
 * never used to authorize a same-tick lift. CONFIRM/SOFTEN only.
 */
internal object AimiCascadeArbitrationArtifacts {

    private val physiologicalPatternsJson = AtomicReference<JSONObject?>(null)
    private val harmoniaSmbAuthorityJson = AtomicReference<JSONObject?>(null)

    fun publish(
        physiologicalPatterns: JSONObject?,
        harmoniaSmbAuthority: JSONObject?,
    ) {
        physiologicalPatternsJson.set(physiologicalPatterns)
        harmoniaSmbAuthorityJson.set(harmoniaSmbAuthority)
    }

    fun physiologicalPatterns(): JSONObject? = physiologicalPatternsJson.get()

    fun harmoniaSmbAuthority(): JSONObject? = harmoniaSmbAuthorityJson.get()

    fun clear() {
        physiologicalPatternsJson.set(null)
        harmoniaSmbAuthorityJson.set(null)
    }
}
