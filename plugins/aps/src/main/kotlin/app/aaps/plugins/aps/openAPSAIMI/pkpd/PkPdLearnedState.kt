package app.aaps.plugins.aps.openAPSAIMI.pkpd

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the learned PK/PD state for the whole process.
 *
 * Two `@Singleton` consumers build their own [PkPdIntegration]: the loop plugin, which only reads
 * the learned kinetics, and the AIMI determine-basal engine, which is the only one allowed to
 * learn. Without a shared owner the reader keeps the values it read at start up for ever, so the
 * prediction kinetics never follow what the learner found.
 *
 * Only the learned state is shared. Everything else stays per consumer on purpose:
 * - the ISF fusion holds a per call slew limiter, so sharing it would let the dosing ISF move
 *   about three times faster per loop tick;
 * - the recent bolus samples change `pkpdScale`, so sharing them would change the ISF that doses.
 */
@Singleton
class PkPdLearnedState @Inject constructor() {
    // Each consumer guards its own PkPdIntegration with its own lock, so the two locks do not
    // order these writes against each other. The loop can also run two ticks on two threads of
    // the same pool. @Volatile is what makes a value written by one consumer visible to the
    // other one. It is enough here: every field holds one reference and is written inside a
    // single tick, so there is no read-modify-write to make atomic.
    @Volatile
    internal var estimator: AdaptivePkPdEstimator? = null

    @Volatile
    internal var lastBounds: PkPdBounds? = null

    @Volatile
    internal var lastLearningCfg: PkPdLearningConfig? = null

    @Volatile
    internal var lastPersisted: PkPdParams? = null

    @Volatile
    internal var seenLearnedStateGeneration: Long? = null

    /**
     * Drops the learned values so the next tick seeds again from prefs.
     * [seenLearnedStateGeneration] is kept on purpose: turning PK/PD off and on again must not
     * look like an external reset on the next tick.
     */
    @Synchronized
    fun clearLearned() {
        estimator = null
        lastBounds = null
        lastLearningCfg = null
        lastPersisted = null
    }
}
