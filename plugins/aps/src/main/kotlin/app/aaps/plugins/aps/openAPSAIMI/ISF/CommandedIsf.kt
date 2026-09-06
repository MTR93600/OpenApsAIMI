package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.plugins.aps.openAPSAIMI.IsfSourceTelemetry

/**
 * The last two steps of the commanded sensitivity, in the order they must happen.
 *
 * `OpenAPSAIMIPlugin` used to write these two steps inline, and it wrote them in the wrong order:
 * the profile-relative floor was applied first, and the shadow witness was then handed the value
 * that had **already** been floored. The witness's own lower bound is the same 0.5 x profile, so it
 * could never fire again. Measured on the night of 2026-09-05:
 * `isf_profile_relative_bound_hit` was `false` on 709 ticks out of 709, while the floor really set
 * the commanded value (`command_isf_mgdl` exactly 0.5 x 120 = 60.000) on 244 night ticks out of 474.
 * The instrument reported "nothing to see" on the very mechanism it was added to measure.
 *
 * Keeping both steps here makes the order part of the code instead of part of a call site, so a test
 * can hold it in place.
 */
internal object CommandedIsf {

    /**
     * The commanded sensitivity as it stood **before** the profile-relative floor, mg/dL per U.
     *
     * Observation only. `null` until a tick has run, and `null` again when the value was not finite.
     * Exported as `baseline_state.isf_pre_floor_mgdl`.
     */
    @Volatile
    var lastPreFloorMgdlPerU: Double? = null
        private set

    /**
     * Records the shadow witness on the pre-floor value, then returns the floored command.
     *
     * The witness must see the value before the floor, otherwise it only ever sees its own bound.
     * The returned value is the one the loop commands, and it is exactly what
     * [DynamicSensitivityPolicy.floorAgainstProfile] returns for the same two inputs.
     *
     * @param preFloorMgdlPerU the commanded sensitivity after every multiplier, before the floor.
     * @param profileIsfMgdlPerU the static profile sensitivity for this time of day, or null.
     */
    fun floorAgainstProfileAndRecordShadow(
        preFloorMgdlPerU: Double,
        profileIsfMgdlPerU: Double?,
    ): Double {
        lastPreFloorMgdlPerU = preFloorMgdlPerU.takeIf { it.isFinite() }
        IsfSourceTelemetry.recordProfileRelativeShadow(
            blendedMgdl = preFloorMgdlPerU,
            profileIsfMgdl = profileIsfMgdlPerU ?: 0.0,
        )
        return DynamicSensitivityPolicy.floorAgainstProfile(
            commandedMgdlPerU = preFloorMgdlPerU,
            profileIsfMgdlPerU = profileIsfMgdlPerU,
        )
    }
}
