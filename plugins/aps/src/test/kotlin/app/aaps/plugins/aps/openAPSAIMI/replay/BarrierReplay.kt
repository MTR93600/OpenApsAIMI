package app.aaps.plugins.aps.openAPSAIMI.replay

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import kotlin.math.abs
import kotlin.math.max

/**
 * Replays `ControlBarrierShield.enforce` on exported ticks, and compares two coefficients on the
 * same ticks.
 *
 * ## Why this exists
 *
 * Two places in production say the same thing: the coefficient floor
 * ([InsulinActionModel.MIN_FLOOR_ISF_MGDL_PER_U]) can only be removed with *"a replay with a
 * measured before/after, not a one-line edit"*. That replay had no way to run. `ReplayTick` carried
 * no barrier field at all, so the harness could load a day and count what the loop delivered, but
 * could not re-run the barrier that decided it.
 *
 * ## What is reconstructed, and from what
 *
 * `enforce` reads five numbers off the state: `bg`, `iob`, `estimatedRa`, `bgVelocity`, `maxIOB`.
 * The export does not carry the state — but the `control_barrier` block carries the barrier's own
 * terms, and three of the five fall straight out of them:
 *
 * | needed | recovered from |
 * |---|---|
 * | `bg` | `h = bg - 80` |
 * | `iob` | `insulinTerm = -siMetabolic * iob * bg` |
 * | `estimatedRa` | `lfh = -p1 * (bg - 100) + insulinTerm + Ra` |
 *
 * That is deliberately preferred to the baseline block: the state handed to the barrier has been
 * through `AutoDriveState.createSafe` and the estimator, so it is not always the baseline value.
 * The clearest case is IOB — the baseline block reported a small negative IOB on a few ticks and
 * the barrier reasoned with zero.
 *
 * ## The one input that cannot be recovered: `bgVelocity`
 *
 * Nothing in the export carries the velocity the barrier saw. Velocity has exactly one job inside
 * `enforce`: it picks the gamma branch — the acceleration guard that halves gamma, and the
 * meal-rise relaxation that raises it. And **gamma itself is exported**, as `active_gamma`.
 *
 * So the replay does not guess the velocity. It drives the gamma branch to the value the tick
 * recorded, with the smallest synthetic velocity that reaches that branch (see [GammaDriver]), and
 * then checks that the barrier really produced the recorded gamma. A tick whose gamma cannot be
 * reproduced is reported as not reproduced rather than quietly replayed with the wrong stiffness.
 *
 * This is the right thing to isolate for the question being asked: gamma does not depend on the
 * coefficient, so driving it to the recorded value removes a variable that the change under test
 * cannot move anyway.
 *
 * ## Acceleration memory
 *
 * `ControlBarrierShield` keeps `lastBgVelocity` across calls, keyed on `observationId`. A replay
 * that reused one shield would let tick N-1 set the acceleration of tick N, and the reconstructed
 * velocities are synthetic, so that contamination would be pure noise. Every tick therefore gets a
 * **fresh** [ControlBarrierShield]. The acceleration-guard branch needs a previous velocity to
 * exist at all, so those ticks — and only those — run one priming `enforce` first, under a
 * different `observationId`, before the call that counts. `lastDiagnostics` is read immediately
 * after the final call, so the priming call cannot be mistaken for the result.
 *
 * See `docs/adr/0001-replay-harness.md`.
 */
object BarrierReplay {

    /** `nominalGamma` of `ControlBarrierShield`, mirrored here because it is private. */
    const val NOMINAL_GAMMA: Double = 0.04

    /** Relative tolerance for "the replay reproduced production". */
    const val REPRODUCTION_TOLERANCE: Double = 1e-9

    /** Units per hour to units, over one five-minute tick. */
    private const val TBR_TO_UNITS_PER_TICK = 12.0

    private const val PRIMING_OBSERVATION_ID = 1L
    private const val TICK_OBSERVATION_ID = 2L

    /** Falling fast enough that `(v - 0) / 5 < -0.05` and `v < 0`, which arms the guard. */
    private const val ACCEL_GUARD_VELOCITY = -1.0

    /** Lowest rise the meal relaxation accepts. Kept at the threshold so nothing else is armed. */
    private const val MEAL_RISE_VELOCITY = 0.2

    /** Lowest combined delta the meal relaxation accepts, used instead of inventing carbs. */
    private const val MEAL_RISE_COMBINED_DELTA = 2.0

    /** How one tick's gamma branch is reached, without knowing the velocity the barrier saw. */
    private data class GammaDriver(
        val velocity: Double,
        val combinedDelta: Double,
        /** Velocity of a first, throw-away call, or `null` when no acceleration history is needed. */
        val primingVelocity: Double?,
    )

    /** One coefficient to run the barrier with. */
    class Scenario(val label: String, val coefficient: (ReplayTick) -> Double?)

    /** What the barrier used in production on that tick. */
    val PRODUCTION: Scenario = Scenario("production") { it.cbfCoefficientUsed ?: it.cbfSiMetabolic }

    /** The same tick with [InsulinActionModel.MIN_FLOOR_ISF_MGDL_PER_U] removed. */
    val UNFLOORED: Scenario = Scenario("unfloored") { it.cbfCoefficientUnfloored }

    /**
     * The floor kept but moved to another sensitivity, in mg/dL per unit.
     *
     * Lets the replay answer "how low can the floor go" instead of only "floor or no floor".
     */
    fun flooredAtIsf(isfMgdlPerU: Double): Scenario = Scenario("floor at ISF $isfMgdlPerU") { tick ->
        val isf = tick.cbfProfileIsfMgdl ?: return@Scenario null
        val proportional = InsulinActionModel.metabolicCoefficient(isf, InsulinActionModel.MPC_TAU_MIN)
        val floored = InsulinActionModel.metabolicCoefficient(isfMgdlPerU, InsulinActionModel.MPC_TAU_MIN)
        max(proportional, floored)
    }

    /** One tick replayed under one coefficient. */
    data class TickOutcome(
        val offsetMinutes: Double?,
        val commandIsfMgdl: Double?,
        val coefficient: Double,
        /** Gamma the barrier applied during the replay. */
        val activeGamma: Double,
        /** `true` when [activeGamma] equals the gamma the production tick recorded. */
        val gammaReproduced: Boolean,
        /** Dose the barrier allowed, or `null` when it did not have to intervene. */
        val safeU: Double?,
        /** Total the barrier let through, in units. */
        val permittedU: Double,
        /** `true` when the barrier zeroed the whole dose. */
        val fullySuspended: Boolean,
    )

    /** One scenario over a whole corpus. */
    data class ScenarioResult(
        val label: String,
        val outcomes: List<TickOutcome>,
    ) {

        val ticks: Int get() = outcomes.size
        val totalPermittedU: Double get() = outcomes.sumOf { it.permittedU }
        val fullSuspensions: Int get() = outcomes.count { it.fullySuspended }
    }

    /** How faithfully the replay reproduced what production recorded. */
    data class Reproduction(
        val ticks: Int,
        val safeUReproduced: Int,
        val gammaReproduced: Int,
        val permittedReproduced: Int,
    ) {

        val safeURate: Double get() = if (ticks == 0) 0.0 else safeUReproduced * 100.0 / ticks
        val gammaRate: Double get() = if (ticks == 0) 0.0 else gammaReproduced * 100.0 / ticks
        val permittedRate: Double get() = if (ticks == 0) 0.0 else permittedReproduced * 100.0 / ticks

        fun format(): String = buildString {
            appendLine("replay fidelity on $ticks barrier ticks")
            appendLine("  gamma branch reproduced      $gammaReproduced (${"%.1f".format(gammaRate)} %)")
            appendLine("  safe_u reproduced            $safeUReproduced (${"%.1f".format(safeURate)} %)")
            append("  permitted total reproduced   $permittedReproduced (${"%.1f".format(permittedRate)} %)")
        }
    }

    /**
     * Production against a candidate, on the ticks the replay reproduces.
     *
     * @property unlockedTicks ticks where the candidate permits something and production permits
     *   nothing. This is the number the floor decision turns on.
     * @property unlockedAtLowCommandIsf the same count, restricted to ticks whose commanded
     *   sensitivity is below [LOW_COMMAND_ISF_MGDL]. A very low ISF means the barrier believes
     *   insulin is very strong, so it is the case where loosening costs the most — the risk
     *   scenario `docs/adr/0002-sensitivity-three-levels.md` documents.
     */
    data class Comparison(
        val reproduction: Reproduction,
        val production: ScenarioResult,
        val candidate: ScenarioResult,
        val unlockedTicks: Int,
        val lowCommandIsfTicks: Int,
        val unlockedAtLowCommandIsf: Int,
    ) {

        fun format(): String = buildString {
            appendLine(reproduction.format())
            appendLine()
            appendLine("scenario                     ticks   permitted U   full suspensions")
            appendLine(line(production))
            appendLine(line(candidate))
            appendLine()
            appendLine("candidate permits, production suspends   $unlockedTicks tick(s)")
            append("  of which command ISF < $LOW_COMMAND_ISF_MGDL mg/dL/U    $unlockedAtLowCommandIsf of $lowCommandIsfTicks such tick(s)")
        }

        private fun line(result: ScenarioResult): String =
            "  %-26s %5d   %11.3f   %16d".format(
                result.label, result.ticks, result.totalPermittedU, result.fullSuspensions
            )
    }

    /**
     * Commanded sensitivity below which loosening the barrier is worth counting on its own.
     *
     * An ISF this low says one unit is expected to drop glucose by less than 15 mg/dL, so the
     * barrier's own model of insulin is at its weakest and a wider barrier permits the most.
     */
    const val LOW_COMMAND_ISF_MGDL: Double = 15.0

    /** Ticks that carry everything [replay] needs. Everything else is out of scope, not a failure. */
    fun replayable(ticks: List<ReplayTick>): List<ReplayTick> = ticks.filter { tick ->
        tick.hasBarrierBlock &&
            tick.cbfIobU != null &&
            tick.cbfEstimatedRaMgdlPerMin != null &&
            tick.cbfSafetySi != null &&
            tick.profileBasalUph != null &&
            tick.maxIobU != null &&
            tick.cbfMpcRawSmbU != null &&
            tick.cbfMpcRawTbrUph != null
    }

    /**
     * Runs one tick through a fresh barrier.
     *
     * @return `null` when the tick does not carry what the barrier needs, or when the scenario has
     *   no coefficient for it. Never a partial result: a barrier replayed on half a state would
     *   report a permitted dose that means nothing.
     */
    fun replay(tick: ReplayTick, scenario: Scenario): TickOutcome? {
        val bg = tick.cbfBgMgdl ?: return null
        val iob = tick.cbfIobU ?: return null
        val ra = tick.cbfEstimatedRaMgdlPerMin ?: return null
        val recordedGamma = tick.cbfActiveGamma ?: return null
        val safetySi = tick.cbfSafetySi ?: return null
        val profileBasal = tick.profileBasalUph ?: return null
        val maxIob = tick.maxIobU ?: return null
        val rawSmb = tick.cbfMpcRawSmbU ?: return null
        val rawTbr = tick.cbfMpcRawTbrUph ?: return null
        val coefficient = scenario.coefficient(tick)?.takeIf { it.isFinite() && it > 0.0 } ?: return null

        val rawCommand = AutoDriveCommand(
            scheduledMicroBolus = rawSmb,
            temporaryBasalRate = rawTbr,
            reason = "replay",
        )
        val driver = gammaDriver(recordedGamma)
        // Fresh shield: no acceleration memory can cross over from the previous tick.
        val shield = ControlBarrierShield(SilentLogger)
        driver.primingVelocity?.let { priming ->
            shield.enforce(
                rawCommand,
                state(bg, priming, iob, ra, maxIob, safetySi, driver.combinedDelta),
                profileBasal,
                safetySi,
                PRIMING_OBSERVATION_ID,
                siMetabolicOverride = coefficient,
            )
        }
        val command = shield.enforce(
            rawCommand,
            state(bg, driver.velocity, iob, ra, maxIob, safetySi, driver.combinedDelta),
            profileBasal,
            safetySi,
            TICK_OBSERVATION_ID,
            siMetabolicOverride = coefficient,
        )
        val diagnostics = shield.lastDiagnostics ?: return null
        return TickOutcome(
            offsetMinutes = tick.offsetMinutes,
            commandIsfMgdl = tick.commandIsfMgdl,
            coefficient = coefficient,
            activeGamma = diagnostics.activeGamma,
            gammaReproduced = abs(diagnostics.activeGamma - recordedGamma) <= 1e-12,
            safeU = diagnostics.safeU,
            permittedU = command.scheduledMicroBolus + command.temporaryBasalRate / TBR_TO_UNITS_PER_TICK,
            fullySuspended = diagnostics.fullySuspended,
        )
    }

    /** Runs a whole corpus under one coefficient, skipping ticks the barrier never saw. */
    fun run(ticks: List<ReplayTick>, scenario: Scenario): ScenarioResult =
        ScenarioResult(scenario.label, replayable(ticks).mapNotNull { replay(it, scenario) })

    /**
     * The entry lock: how often the replay lands exactly on what production recorded.
     *
     * Everything else in this file is worth nothing until this is high. A candidate scenario read
     * off a replay that cannot reproduce production is a number about the replay, not about the
     * barrier.
     */
    fun reproduction(ticks: List<ReplayTick>): Reproduction {
        val candidates = replayable(ticks)
        var safeUOk = 0
        var gammaOk = 0
        var permittedOk = 0
        var total = 0
        candidates.forEach { tick ->
            val outcome = replay(tick, PRODUCTION) ?: return@forEach
            total++
            if (outcome.gammaReproduced) gammaOk++
            if (sameSafeU(outcome.safeU, tick.cbfSafeU)) safeUOk++
            val recordedPermitted = tick.cbfPermittedU
            if (recordedPermitted != null && close(outcome.permittedU, recordedPermitted)) permittedOk++
        }
        return Reproduction(total, safeUOk, gammaOk, permittedOk)
    }

    /** Production against [candidate] on the same ticks, with the fidelity figure attached. */
    fun compare(ticks: List<ReplayTick>, candidate: Scenario): Comparison {
        val usable = replayable(ticks)
        val productionOutcomes = mutableListOf<TickOutcome>()
        val candidateOutcomes = mutableListOf<TickOutcome>()
        var unlocked = 0
        var lowIsf = 0
        var unlockedLowIsf = 0
        usable.forEach { tick ->
            val before = replay(tick, PRODUCTION) ?: return@forEach
            val after = replay(tick, candidate) ?: return@forEach
            productionOutcomes += before
            candidateOutcomes += after
            val isLowIsf = (tick.commandIsfMgdl ?: Double.MAX_VALUE) < LOW_COMMAND_ISF_MGDL
            if (isLowIsf) lowIsf++
            if (before.permittedU <= 0.0 && after.permittedU > 0.0) {
                unlocked++
                if (isLowIsf) unlockedLowIsf++
            }
        }
        return Comparison(
            reproduction = reproduction(ticks),
            production = ScenarioResult(PRODUCTION.label, productionOutcomes),
            candidate = ScenarioResult(candidate.label, candidateOutcomes),
            unlockedTicks = unlocked,
            lowCommandIsfTicks = lowIsf,
            unlockedAtLowCommandIsf = unlockedLowIsf,
        )
    }

    /**
     * Gamma is exported but not the velocity behind it, so the branch is reached on purpose.
     *
     * Only three gammas are reachable: half the nominal value under the acceleration guard, the
     * nominal value, and the meal-rise relaxation above it. The velocities below are the smallest
     * ones that reach each branch, so nothing else in `enforce` is armed as a side effect — in
     * particular `isHighRiseMeal`, which needs a rise of 0.6 mg/dL/min, stays off.
     */
    private fun gammaDriver(recordedGamma: Double): GammaDriver = when {
        recordedGamma < NOMINAL_GAMMA - 1e-12 -> GammaDriver(
            velocity = ACCEL_GUARD_VELOCITY,
            combinedDelta = 0.0,
            // The guard reads `(v - previous) / 5`, and there is no previous velocity on a fresh
            // shield. One flat priming call gives it one, without touching anything else.
            primingVelocity = 0.0,
        )

        recordedGamma > NOMINAL_GAMMA + 1e-12 -> GammaDriver(
            velocity = MEAL_RISE_VELOCITY,
            combinedDelta = MEAL_RISE_COMBINED_DELTA,
            primingVelocity = null,
        )

        else                                  -> GammaDriver(velocity = 0.0, combinedDelta = 0.0, primingVelocity = null)
    }

    private fun state(
        bg: Double,
        velocity: Double,
        iob: Double,
        ra: Double,
        maxIob: Double,
        safetySi: Double,
        combinedDelta: Double,
    ): AutoDriveState = AutoDriveState.createSafe(
        bg = bg,
        bgVelocity = velocity,
        iob = iob,
        // Left at zero on purpose. Carbs are one of three ways into the meal relaxation, and the
        // replay uses the combined delta instead, so a fixture's COB cannot change the branch.
        cob = 0.0,
        estimatedSI = safetySi,
        estimatedRa = ra,
        maxIOB = maxIob,
        combinedDelta = combinedDelta,
    )

    /** `null` means "the barrier did not intervene", which is not the same as a zero. */
    private fun sameSafeU(replayed: Double?, recorded: Double?): Boolean = when {
        replayed == null && recorded == null -> true
        replayed == null || recorded == null -> false
        else                                 -> close(replayed, recorded)
    }

    private fun close(a: Double, b: Double): Boolean =
        abs(a - b) <= REPRODUCTION_TOLERANCE * max(1.0, abs(b))

    /**
     * The barrier logs a line per intervention. A replay makes hundreds of them and none is a test
     * result, so they are dropped rather than printed.
     */
    private object SilentLogger : AAPSLogger {

        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(tag: LTag, message: String) {}
        override fun error(tag: LTag, message: String, throwable: Throwable) {}
        override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(message: String) {}
        override fun error(message: String, throwable: Throwable) {}
        override fun error(format: String, vararg arguments: Any?) {}
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    }
}
