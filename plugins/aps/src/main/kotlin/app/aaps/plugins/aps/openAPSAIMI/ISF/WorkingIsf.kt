package app.aaps.plugins.aps.openAPSAIMI.ISF

/**
 * The last steps of the **dose-facing** sensitivity, in the order they must happen.
 *
 * ## Why this object exists
 *
 * The loop commands one sensitivity in `OpenAPSAIMIPlugin` (`OapsProfileAimi.sens`, exported as
 * `command_isf_mgdl`) and sizes its doses with another one, the `variableSensitivity` member of
 * `DetermineBasalAIMI2`. That member is rebuilt inside the engine as
 * `min(PKPD fused ISF, profile.variable_sens)` and then multiplied by several factors. Measured on
 * three support packages, only 16 to 42 % of a lift put on the commanded value ever reached the
 * value the dose used, so a protection written on the commanded value was almost inert.
 *
 * The stress floor is applied **here**, at the very last step of that member, for one reason: a
 * second heart-rate gesture, [HeartRateTrendIsf], multiplies the same member by 0.9 on nearly the
 * same signature and pulls the other way. Both of them run before this point in a tick — the trend
 * multiplier, then the endocrine and activity factors, then the bounds and the physiological factor
 * below. Applying the floor last is what makes the protective gesture win when the two fire
 * together, and keeping the three steps inside one function is what lets a test hold that order in
 * place, the same way [CommandedIsf] holds its own two steps.
 *
 * It is **also** applied once earlier, where the member is assembled, because the AutodriveV3 stage
 * reads it in between and can fall back to it when the PKPD runtime is missing. The floor is a
 * `max`, so applying it twice is the same as applying it once — see [raiseToStressFloor].
 *
 * ## What "the floor can only make a dose smaller" means, and where it stops being true
 *
 * The floor may only RAISE the sensitivity. On every path that DIVIDES by it — the correction line,
 * the MPC proportional term, the prediction stage, the meal-window basal boost — a higher
 * sensitivity means a smaller dose, and that is arithmetic, not a hope.
 *
 * It is **not** a guarantee on the legacy neural refinement path. There the same number is an input
 * FEATURE: `insulinEffect = iob * variableSensitivity / insulinDivisor` feeds `trendValue`, which
 * feeds the trend indicator of the SMB refinement network (`DetermineBasalAIMI2.calculateInsulinEffect`
 * and `calculateTrendIndicator`, read by `neuralnetwork5`). Raising the sensitivity raises that
 * feature and can flip the indicator to "strong rise", and the network's answer is not monotone in
 * it, so on that path the floor can in principle ask for a slightly larger SMB. The exposure is
 * bounded twice: the path is bypassed today, and the refinement itself may move the dose by at most
 * `min(0.05 U, 25 % of the dose)` in either direction. Nothing here changes that behaviour; the
 * point is that the protective claim is arithmetic, not universal.
 */
internal object WorkingIsf {

    /** Lowest sensitivity the engine will work with, mg/dL per U. */
    const val MIN_MGDL_PER_U: Double = 5.0

    /** Highest sensitivity the engine will work with, mg/dL per U. */
    const val MAX_MGDL_PER_U: Double = 300.0

    /**
     * What the floor did on the last [finalize] call.
     *
     * Observation only, and the whole reason the wiring can be checked from a support package next
     * week: the exported `variable_sens_mgdl` is read after every multiplier, so on its own it cannot
     * say whether the floor moved anything.
     *
     * It is a process-global, like the rest of this file's observation. Two loop runs interleaved —
     * an explicit advisor run next to a normal tick — would cross their observations. The dose is
     * unaffected either way, because the floor itself is passed in per tick on `OapsProfileAimi`.
     *
     * [raiseToStressFloor] deliberately does NOT write it: the early application only guards the
     * AutodriveV3 fallback, and the tick's honest before/after pair is the one measured here.
     *
     * @param beforeMgdlPerU the dose-facing sensitivity after the bounds and the physiological
     *   factor, before the floor.
     * @param afterMgdlPerU the same value after the floor.
     * @param floorMgdlPerU the floor that was offered, or null when there was none.
     * @param raised true when the floor really moved the value.
     */
    data class Applied(
        val beforeMgdlPerU: Double,
        val afterMgdlPerU: Double,
        val floorMgdlPerU: Double?,
        val raised: Boolean,
    )

    @Volatile
    var lastApplied: Applied? = null
        private set

    /**
     * True when the EARLY application ([raiseToStressFloor]) really raised the sensitivity this tick.
     *
     * The early call is the one that guards the AutodriveV3 fallback, and it runs on ticks that may
     * never reach [finalize]. Without this flag the exported "did the floor move the dose" field
     * would read false on exactly those ticks, which is the one case where the answer matters most.
     * Reset per tick by [resetLastApplied].
     */
    @Volatile
    var raisedEarly: Boolean = false
        private set

    /**
     * Raises a sensitivity to the stress floor, and to nothing else.
     *
     * Applied at the point where the dose-facing sensitivity is ASSEMBLED, which is early in the
     * tick. The late [finalize] applies the same floor again, and that is on purpose: the AutodriveV3
     * stage runs between the two and falls back to this member when the PKPD runtime is missing or
     * failed to build, so without this call a stress signature would leave that fallback unfloored.
     * The floor is a `max` against a value that does not change inside a tick, so applying it twice
     * gives exactly what applying it once gives — it is idempotent, and neither call can lower
     * anything.
     *
     * Fails open: a null, non-finite or non-positive floor returns the input untouched.
     *
     * @param workingIsfMgdlPerU the dose-facing sensitivity as just assembled.
     * @param stressFloorIsfMgdlPerU `OapsProfileAimi.stress_floor_isf_mgdl` of this tick, or null.
     */
    fun raiseToStressFloor(workingIsfMgdlPerU: Double, stressFloorIsfMgdlPerU: Double?): Double {
        if (!workingIsfMgdlPerU.isFinite()) return workingIsfMgdlPerU
        val floor = stressFloorIsfMgdlPerU?.takeIf { it.isFinite() && it > 0.0 } ?: return workingIsfMgdlPerU
        val raised = maxOf(workingIsfMgdlPerU, floor)
        if (raised > workingIsfMgdlPerU) raisedEarly = true
        return raised
    }

    /**
     * Bounds the working sensitivity, applies the physiological factor, then the stress floor, then
     * the same bounds again so the result cannot leave them.
     *
     * Fails open on every input: a floor that is null, not a number or not positive leaves the value
     * exactly where the two historical steps left it, so with the opt-in key off this function is
     * bit-for-bit what the engine did before. The second bound is applied **only** when a floor was
     * really offered, for the same reason: with no floor there is nothing new to bound, and the old
     * result must not move.
     *
     * @param workingIsfMgdlPerU the dose-facing sensitivity as the tick has built it so far.
     * @param physioIsfFactor the physiological ISF factor of the tick, bounds [0.85, 1.15].
     * @param stressFloorIsfMgdlPerU `OapsProfileAimi.stress_floor_isf_mgdl` of this tick, or null.
     * @return the sensitivity the dose paths must use for the rest of the tick. With a floor it is
     *   never below [MIN_MGDL_PER_U] and never above [MAX_MGDL_PER_U].
     */
    fun finalize(
        workingIsfMgdlPerU: Double,
        physioIsfFactor: Double,
        stressFloorIsfMgdlPerU: Double?,
    ): Double {
        val bounded = workingIsfMgdlPerU.coerceIn(MIN_MGDL_PER_U, MAX_MGDL_PER_U)
        val withPhysio = bounded * physioIsfFactor
        val floor = stressFloorIsfMgdlPerU?.takeIf { it.isFinite() && it > 0.0 }
        val floored = raiseToStressFloor(withPhysio, floor)
        // The bound is re-applied after the floor, so the value the dose paths read stays inside the
        // domain the engine works in, whatever floor was offered. Only when there IS a floor: with
        // none, the result must be exactly what the two historical steps produced.
        //
        // `maxOf` with the unfloored value keeps the one promise this object makes: the floor may
        // only ever RAISE the sensitivity. Without it, a working sensitivity above [MAX_MGDL_PER_U]
        // after the physiological factor (up to 300 x 1.15 = 345) would be pulled back to 300 by the
        // re-bound, which is a SMALLER sensitivity than the key-off result, and a smaller
        // sensitivity means a bigger dose. This patient never reaches such a value, but a gesture
        // that exists to withhold insulin must not add any at the edge of its own domain.
        val result =
            if (floor != null && floored.isFinite()) maxOf(withPhysio, floored.coerceIn(MIN_MGDL_PER_U, MAX_MGDL_PER_U))
            else floored
        lastApplied = Applied(
            beforeMgdlPerU = withPhysio,
            afterMgdlPerU = result,
            floorMgdlPerU = floor,
            raised = floor != null && result > withPhysio,
        )
        return result
    }

    /** Forgets the last observation. For tests, and for a tick that never reached [finalize]. */
    fun resetLastApplied() {
        lastApplied = null
        raisedEarly = false
    }
}
