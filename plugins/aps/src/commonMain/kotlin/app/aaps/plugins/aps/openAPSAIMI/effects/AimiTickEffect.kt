package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

/**
 * Decision the common core is allowed to return. The Android shell executes these,
 * in this order. Nothing in this type talks to a pump, a preference store, or a learner.
 *
 * See `_docs/kmp/p6-effects-boundary.md`.
 */
internal sealed class AimiTickEffect {
    abstract fun encode(): String

    data class SetTbr(
        val rateUph: Double,
        val durationMin: Int,
        val overrideSafetyLimits: Boolean,
        val forceExact: Boolean,
        val adaptiveMultiplier: Double,
    ) : AimiTickEffect() {
        override fun encode(): String =
            "EFFECT SetTbr rate=${aimiFmt2(rateUph)} dur=$durationMin " +
                "override=$overrideSafetyLimits forceExact=$forceExact adaptive=${aimiFmt2(adaptiveMultiplier)}"
    }

    data class Smb(
        val units: Double,
        val owner: String,
    ) : AimiTickEffect() {
        override fun encode(): String = "EFFECT Smb units=${aimiFmt2(units)} owner=$owner"
    }

    data class WritePref(
        val key: String,
        val value: String,
    ) : AimiTickEffect() {
        override fun encode(): String = "EFFECT WritePref key=$key value=$value"
    }

    data class LearnerUpdate(
        val name: String,
        val detail: String,
    ) : AimiTickEffect() {
        override fun encode(): String = "EFFECT LearnerUpdate name=$name detail=$detail"
    }
}

internal fun aimiTraceRead(key: String, value: String): String = "READ key=$key value=$value"

internal fun aimiTraceWrite(key: String, value: String): String = "WRITE key=$key value=$value"

internal fun aimiTraceLog(line: String): String = "LOG $line"
