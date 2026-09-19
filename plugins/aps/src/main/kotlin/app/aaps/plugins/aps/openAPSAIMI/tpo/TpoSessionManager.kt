package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import java.util.UUID

internal class TpoSessionManager(
    private val persistence: TpoPersistence,
) {
    companion object {
        const val TTL_MS = 45L * 60L * 1000L
    }

    fun currentSession(): TpoSessionDocument? = persistence.loadSession()

    fun expireIfNeeded(nowMs: Long, preferences: Preferences, historyRepo: AdvisorHistoryRepository?): Boolean {
        val session = persistence.loadSession() ?: return false
        if (session.status != TpoSessionStatus.ACTIVE && session.status != TpoSessionStatus.PENDING_LLM) {
            return false
        }
        if (nowMs < session.expiresAtMs) {
            trackUserOwnedKeys(session, preferences)
            return false
        }
        revertSession(
            session = session.copy(status = TpoSessionStatus.EXPIRED),
            preferences = preferences,
            historyRepo = historyRepo,
            reason = "expired",
        )
        return true
    }

    fun revertNow(
        preferences: Preferences,
        historyRepo: AdvisorHistoryRepository?,
        nowMs: Long,
    ): Boolean {
        val session = persistence.loadSession() ?: return false
        revertSession(
            session = session.copy(status = TpoSessionStatus.REVERTED, lastRevertAtMs = nowMs),
            preferences = preferences,
            historyRepo = historyRepo,
            reason = "manual",
        )
        return true
    }

    fun supersedeActiveSession(
        preferences: Preferences,
        historyRepo: AdvisorHistoryRepository?,
        nowMs: Long,
    ) {
        val session = persistence.loadSession() ?: return
        if (session.status != TpoSessionStatus.ACTIVE && session.status != TpoSessionStatus.PENDING_LLM) return
        revertSession(
            session = session.copy(status = TpoSessionStatus.SUPERSEDED, lastRevertAtMs = nowMs),
            preferences = preferences,
            historyRepo = historyRepo,
            reason = "superseded",
        )
    }

    fun startSession(
        plan: TpoApplyPlan,
        preferences: Preferences,
        nowMs: Long,
        llmResult: TpoLlmResult?,
        historyRepo: AdvisorHistoryRepository?,
    ): TpoSessionDocument {
        val baseline = linkedMapOf<String, Any>()
        plan.changes.forEach { change ->
            baseline[change.key.key] = change.oldValue
        }
        val overlay = linkedMapOf<String, Any>()
        plan.changes.forEach { change ->
            overlay[change.key.key] = change.newValue
        }
        var applied = 0
        plan.changes.forEach { change ->
            if (applyChange(preferences, change)) applied++
        }
        val session = TpoSessionDocument(
            sessionId = UUID.randomUUID().toString(),
            packId = plan.proposal.packId,
            tier = plan.proposal.tier,
            status = TpoSessionStatus.ACTIVE,
            startedAtMs = nowMs,
            expiresAtMs = nowMs + TTL_MS,
            triggerAlgoConfidence = plan.proposal.algoConfidence,
            triggerReasonCodes = plan.proposal.reasonCodes,
            baseline = baseline,
            overlay = overlay,
            llmResult = llmResult,
        )
        persistence.saveSession(session)
        historyRepo?.logAction(
            AdvisorHistoryRepository.ActionType.TPO_SESSION_START,
            plan.proposal.packId.name,
            "TPO ${plan.proposal.packId.name} (${plan.proposal.tier.name}, $applied keys)",
            "bundle",
            applied.toString(),
        )
        return session
    }

    fun savePendingSession(
        plan: TpoApplyPlan,
        nowMs: Long,
    ) {
        val baseline = linkedMapOf<String, Any>()
        plan.changes.forEach { change ->
            baseline[change.key.key] = change.oldValue
        }
        val overlay = linkedMapOf<String, Any>()
        plan.changes.forEach { change ->
            overlay[change.key.key] = change.newValue
        }
        persistence.saveSession(
            TpoSessionDocument(
                sessionId = UUID.randomUUID().toString(),
                packId = plan.proposal.packId,
                tier = plan.proposal.tier,
                status = TpoSessionStatus.PENDING_LLM,
                startedAtMs = nowMs,
                expiresAtMs = nowMs + TTL_MS,
                triggerAlgoConfidence = plan.proposal.algoConfidence,
                triggerReasonCodes = plan.proposal.reasonCodes,
                baseline = baseline,
                overlay = overlay,
            ),
        )
    }

    fun activatePendingSession(
        pending: TpoSessionDocument,
        preferences: Preferences,
        llmResult: TpoLlmResult,
        historyRepo: AdvisorHistoryRepository?,
    ): Boolean {
        if (pending.status != TpoSessionStatus.PENDING_LLM) return false
        var applied = 0
        pending.overlay.forEach { (key, newValue) ->
            val change = pending.toTuningChange(key, newValue) ?: return@forEach
            if (applyChange(preferences, change)) applied++
        }
        persistence.saveSession(
            pending.copy(status = TpoSessionStatus.ACTIVE, llmResult = llmResult),
        )
        historyRepo?.logAction(
            AdvisorHistoryRepository.ActionType.TPO_SESSION_START,
            pending.packId.name,
            "TPO ${pending.packId.name} LLM confirmed ($applied keys)",
            "bundle",
            applied.toString(),
        )
        return applied > 0
    }

    fun cancelPendingSession(reason: String, historyRepo: AdvisorHistoryRepository?) {
        val pending = persistence.loadSession()?.takeIf { it.status == TpoSessionStatus.PENDING_LLM } ?: return
        persistence.saveSession(null)
        historyRepo?.logAction(
            AdvisorHistoryRepository.ActionType.TPO_LLM_VETO,
            pending.packId.name,
            reason,
            pending.packId.name,
            "blocked",
        )
    }

    private fun revertSession(
        session: TpoSessionDocument,
        preferences: Preferences,
        historyRepo: AdvisorHistoryRepository?,
        reason: String,
    ) {
        var restored = 0
        // Counted and reported: a key that is skipped because somebody else owns it now is normal,
        // a key that is refused by the writer is a defect, and the two used to look identical —
        // `restored` simply came out lower, with nothing saying why (see [applyChange]).
        var skipped = 0
        var refused = 0
        session.baseline.forEach { (key, oldValue) ->
            // Decided here, per key, on the value in force right now — see [TpoRevertPolicy]. The
            // old test was `key in session.userOwnedKeys`, a set that only ever grew, so one tick of
            // divergence kept this session's own value in the preferences for good; and a key the
            // tracking had not happened to look at was overwritten even when somebody else had moved
            // it. Comparing against what this session wrote answers both.
            val liveValue = readPreferenceValue(preferences, key)
            val sessionWrote = session.overlay[key]
            if (!TpoRevertPolicy.shouldRestore(liveValue, sessionWrote, key in session.userOwnedKeys)) {
                skipped++
                return@forEach
            }
            if (restoreValue(preferences, key, oldValue)) restored++ else refused++
        }
        val revertMap = persistence.loadLastRevertAtMsByPack().toMutableMap()
        revertMap[session.packId] = session.lastRevertAtMs ?: System.currentTimeMillis()
        persistence.saveLastRevertAtMsByPack(revertMap)
        persistence.saveSession(null)
        historyRepo?.logAction(
            AdvisorHistoryRepository.ActionType.TPO_SESSION_REVERT,
            session.packId.name,
            "TPO revert ($reason, $restored keys, $skipped skipped, $refused refused)",
            session.packId.name,
            restored.toString(),
        )
    }

    private fun trackUserOwnedKeys(session: TpoSessionDocument, preferences: Preferences) {
        val owned = session.userOwnedKeys.toMutableSet()
        var changed = false
        session.overlay.forEach { (key, overlayValue) ->
            val current = readPreferenceValue(preferences, key) ?: return@forEach
            if (valuesDiffer(current, overlayValue)) {
                if (owned.add(key)) changed = true
            }
        }
        if (changed) {
            persistence.saveSession(session.copy(userOwnedKeys = owned))
        }
    }

    private fun restoreValue(preferences: Preferences, key: String, oldValue: Any): Boolean {
        val preferenceKey = TpoPreferenceKeys.fromKey(key) ?: return false
        val change = TuningChange(
            key = preferenceKey,
            labelKey = key,
            oldValue = readPreferenceValue(preferences, key) ?: oldValue,
            newValue = oldValue,
            reason = "TPO revert",
            tier = TuningStepTier.MODERATE,
        )
        return applyChange(preferences, change)
    }

    private fun TpoSessionDocument.toTuningChange(key: String, newValue: Any): TuningChange? {
        val oldValue = baseline[key] ?: return null
        val preferenceKey = TpoPreferenceKeys.fromKey(key) ?: return null
        return TuningChange(
            key = preferenceKey,
            labelKey = key,
            oldValue = oldValue,
            newValue = newValue,
            reason = "TPO overlay",
            tier = tier,
        )
    }

    /**
     * Write one value, or say it could not.
     *
     * Any number is accepted for a `Double` key, not only a `Double`: a value read back from the
     * session file can be an `Int` (JSON drops the decimal point of a whole number), and refusing it
     * here is what silently lost the user's baseline. `TpoSessionDocument` normalises on read too —
     * this is the second line of defence, and the reason a refusal is now logged instead of silent.
     */
    private fun applyChange(preferences: Preferences, change: TuningChange): Boolean {
        val key = change.key
        val newValue = change.newValue
        return when {
            newValue is Number && key is DoublePreferenceKey -> {
                preferences.put(key, newValue.toDouble())
                true
            }
            newValue is Boolean && key is BooleanPreferenceKey -> {
                preferences.put(key, newValue)
                true
            }
            else -> false
        }
    }

    private fun readPreferenceValue(preferences: Preferences, key: String): Any? {
        val preferenceKey = TpoPreferenceKeys.fromKey(key) ?: return null
        return when (preferenceKey) {
            is DoublePreferenceKey -> preferences.get(preferenceKey)
            is BooleanPreferenceKey -> preferences.get(preferenceKey)
            else -> null
        }
    }

    /** One definition of value equality for the whole revert path — see [TpoRevertPolicy.sameValue]. */
    private fun valuesDiffer(current: Any, overlayValue: Any): Boolean =
        !TpoRevertPolicy.sameValue(current, overlayValue)
}
