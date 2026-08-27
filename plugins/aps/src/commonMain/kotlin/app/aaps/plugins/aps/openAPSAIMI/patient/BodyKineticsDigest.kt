package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1

import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState

/**
 * Wave3 F4 — thin body-kinetics digest for Tree evidence + Harmonia consciousness.
 * Does not own dosing; classifies PEAK/TAIL pressure from already-resolved tick fields.
 */
data class BodyKineticsDigest(
    val effectiveDiaHours: Double?,
    val effectivePeakMinutes: Double?,
    val activityStage: ActivityStage?,
    val peakHeavy: Boolean,
    val tailHeavy: Boolean,
    val residualEffect: Double?,
    val reason: String,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            effectiveDiaHours?.let { put("effective_dia_h", it) }
            effectivePeakMinutes?.let { put("effective_peak_min", it) }
            put("activity_stage", activityStage?.name ?: "UNKNOWN")
            put("peak_heavy", peakHeavy)
            put("tail_heavy", tailHeavy)
            residualEffect?.let { put("residual_effect", it) }
            put("reason", reason)
        }

    companion object {
        val EMPTY = BodyKineticsDigest(
            effectiveDiaHours = null,
            effectivePeakMinutes = null,
            activityStage = null,
            peakHeavy = false,
            tailHeavy = false,
            residualEffect = null,
            reason = "empty",
        )

        fun fromTick(
            effectiveDiaHours: Double?,
            effectivePeakMinutes: Double?,
            insulinActionState: InsulinActionState?,
        ): BodyKineticsDigest {
            val stage = insulinActionState?.activityStage
            val residual = insulinActionState?.residualEffect
            val peakHeavy = stage == ActivityStage.PEAK ||
                (stage == ActivityStage.RISING && (insulinActionState.timeToPeakMin in 1..20))
            val tailHeavy = stage == ActivityStage.TAIL ||
                (residual != null && residual >= 0.55 && stage == ActivityStage.FALLING)
            val reason = buildString {
                append(stage?.name ?: "NO_STAGE")
                effectiveDiaHours?.let { append(" dia=").append(aimiFmt1(it)) }
                effectivePeakMinutes?.let { append(" peak=").append(aimiFmt0(it)) }
                if (peakHeavy) append(" peakHeavy")
                if (tailHeavy) append(" tailHeavy")
            }
            return BodyKineticsDigest(
                effectiveDiaHours = effectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 },
                effectivePeakMinutes = effectivePeakMinutes?.takeIf { it.isFinite() && it > 0.0 },
                activityStage = stage,
                peakHeavy = peakHeavy,
                tailHeavy = tailHeavy,
                residualEffect = residual,
                reason = reason,
            )
        }
    }
}
