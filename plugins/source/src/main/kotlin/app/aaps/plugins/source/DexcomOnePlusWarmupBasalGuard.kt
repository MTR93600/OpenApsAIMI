package app.aaps.plugins.source

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.objects.extensions.convertedToAbsolute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * ⚠️ SAFETY-CRITICAL — **DRAFT** pending project-maintainer + clinician review and on-device
 * validation. Do NOT rely on this in care until signed off. This describes software behaviour only;
 * it is not medical advice.
 *
 * Closes the "no glucose during Dexcom ONE+ warm-up" gap. While the native driver is warming up /
 * (re)connecting and NO glucose is available, the APS plugin bails out on missing glucose and the
 * loop enacts nothing (it returns on a null APS result), so a residual temp basal keeps running
 * blind until its own duration expires. This guard reverts the pump to its **profile** basal by
 * cancelling that residual temp — under strict conditions only.
 *
 * **Option (b)** (chosen in review): cancel ONLY when the running temp is **above** profile basal
 * (a *high* temp). A low/zero temp is left untouched, so warm-up can never *raise* delivery while
 * blind (avoids over-delivery if a reduction temp was running, e.g. after a predicted low).
 *
 * Hard guards ([shouldCancelResidualTemp]) — any false → do nothing:
 *  - this ONE+ plugin is the **active BG source** (never act on another source's behalf);
 *  - loop **enabled**, running mode **CLOSED_LOOP / CLOSED_LOOP_LGS** — `isClosedLoopOrLgs()`
 *    deliberately excludes OPEN_LOOP, DISABLED_LOOP and **every paused mode** (suspend / disconnect /
 *    super-bolus), where the reconciler holds a protective zero-temp we must never lift;
 *  - **no fresh glucose** (last reading older than [GLUCOSE_FRESHNESS_MIN]) — otherwise the loop may
 *    have legitimately set the temp on that reading, and we must not fight it. Addresses the
 *    "pre-warm-up glucose still valid" review finding.
 *
 * Cancelling reverts to the profile schedule (≤ maxBasal by construction, no constraint bypassed) and
 * is idempotent, so re-affirming on each warm-up emission is safe.
 */
@Singleton
class DexcomOnePlusWarmupBasalGuard @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val loopProvider: Provider<Loop>,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val persistenceLayer: PersistenceLayer,
) {

    /**
     * Evaluate the guards and, if warranted, revert the pump to profile basal. The pump command is
     * launched fire-and-forget on [scope] (per the CommandQueue deadlock guidance — never await a
     * queue command from a queue/BLE execution context).
     */
    suspend fun ensureProfileBasalDuringWarmup(
        owner: BgSource,
        scope: CoroutineScope,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (shouldCancelResidualTemp(owner, nowMs)) {
            scope.launch { commandQueue.cancelTempBasal(enforceNew = false, autoForced = true) }
        }
    }

    /**
     * Pure decision (all guards) — extracted for unit testing. Returns true only when a residual
     * temp **above** profile basal should be cancelled under safe conditions.
     */
    internal suspend fun shouldCancelResidualTemp(owner: BgSource, nowMs: Long): Boolean {
        if (activePlugin.activeBgSource !== owner) return false

        val loop = loopProvider.get()
        if (!loop.isEnabled()) return false

        val mode = loop.runningMode()
        if (!mode.isClosedLoopOrLgs()) {
            aapsLogger.debug(LTag.APS, "ONEPLUS_WARMUP_BASAL: skip — mode=$mode (not closed-loop/LGS)")
            return false
        }

        // Recent-glucose guard: if the loop still has fresh glucose it may have set this temp
        // legitimately — do not fight it. Act only once glucose is as stale as the APS considers it.
        val lastGv = persistenceLayer.getLastGlucoseValue()
        if (lastGv != null && nowMs - lastGv.timestamp < GLUCOSE_FRESHNESS_MIN * 60_000L) {
            aapsLogger.debug(LTag.APS, "ONEPLUS_WARMUP_BASAL: skip — recent glucose present, loop owns dosing")
            return false
        }

        val tb = persistenceLayer.getTemporaryBasalActiveAt(nowMs)
        if (tb == null) {
            aapsLogger.debug(LTag.APS, "ONEPLUS_WARMUP_BASAL: no active temp — pump already on profile")
            return false
        }

        val profile = profileFunction.getProfile()
        if (profile == null) {
            aapsLogger.debug(LTag.APS, "ONEPLUS_WARMUP_BASAL: no profile — skip")
            return false
        }

        val profileBasal = profile.getBasal()
        val tempAbsolute = tb.convertedToAbsolute(nowMs, profile)

        // Option (b): never raise delivery while blind — only cancel a temp ABOVE profile.
        if (tempAbsolute <= profileBasal + RATE_EPSILON_U_PER_H) {
            aapsLogger.info(
                LTag.APS,
                "ONEPLUS_WARMUP_BASAL: keep temp %.3fU/h (<= profile %.3fU/h) — not raising delivery during warm-up"
                    .format(tempAbsolute, profileBasal),
            )
            return false
        }

        aapsLogger.info(
            LTag.APS,
            "ONEPLUS_WARMUP_BASAL: cancel high temp %.3fU/h (> profile %.3fU/h) → revert to profile basal (warm-up, no glucose)"
                .format(tempAbsolute, profileBasal),
        )
        return true
    }

    companion object {

        /** Margin so a temp equal to (or within noise of) profile basal is treated as "not above". */
        private const val RATE_EPSILON_U_PER_H = 0.005

        /**
         * Glucose older than this counts as "no fresh glucose", so this guard engages only once the
         * APS itself has no usable reading left. Kept local on purpose: the APS freshness cutoffs
         * live in `:plugins:aps` and this module must not depend on it. A reviewer changing the APS
         * cutoff should revisit this value.
         */
        private const val GLUCOSE_FRESHNESS_MIN = 7L
    }
}
