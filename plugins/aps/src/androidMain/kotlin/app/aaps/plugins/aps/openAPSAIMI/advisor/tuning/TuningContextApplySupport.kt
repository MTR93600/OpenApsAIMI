package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import android.content.Context
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import java.util.Locale

object TuningPreferenceLabels {

    fun shortLabel(key: app.aaps.core.keys.interfaces.PreferenceKey): String =
        when (key) {
            DoubleKey.OApsAIMIMaxSMB -> "Max SMB"
            DoubleKey.OApsAIMIHighBGMaxSMB -> "High BG Max SMB"
            DoubleKey.OApsAIMILunchFactor -> "Lunch factor"
            DoubleKey.OApsAIMIDinnerFactor -> "Dinner factor"
            DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor -> "PKPD relief min factor"
            DoubleKey.OApsAIMIRedCarpetRestoreThreshold -> "Red Carpet restore"
            DoubleKey.OApsAIMIPriorityMaxIobFactor -> "Priority MaxIOB factor"
            DoubleKey.OApsAIMIPriorityMaxIobExtraU -> "Priority MaxIOB extra U"
            DoubleKey.OApsAIMISmbTailDamping -> "SMB tail damping"
            DoubleKey.AimiTubeAggressiveness -> "Tube aggressiveness"
            DoubleKey.AimiTubeHypoFloorMgdl -> "Tube hypo floor"
            BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled -> "PKPD pragmatic relief"
            BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled -> "Straight-line tube"
            else -> key.key
        }

    fun formatValue(value: Any): String = when (value) {
        is Double -> String.format(Locale.US, "%.3f", value)
        is Boolean -> if (value) "on" else "off"
        else -> value.toString()
    }
}

object TuningContextApplySupport {

    fun applyTuningPlan(
        plan: TuningPlan,
        preferences: Preferences,
        historyRepo: AdvisorHistoryRepository,
    ): TuningApplyResult {
        if (!plan.isActionable) {
            return TuningApplyResult(
                plan = plan,
                appliedCount = 0,
                summaryLines = emptyList(),
                exportStatus = TuningExportStatus.SKIPPED_DISABLED,
            )
        }
        val summaryLines = mutableListOf<String>()
        var applied = 0
        plan.changes.forEach { change ->
            if (applyChange(preferences, change)) {
                applied++
                historyRepo.logAction(
                    AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                    change.key.key,
                    change.reason,
                    change.oldValue,
                    change.newValue,
                )
                summaryLines += formatChangeLine(change)
            }
        }
        if (applied > 0) {
            preferences.put(StringKey.AimiTuningContextSelection, plan.requestedContext.name)
            historyRepo.logAction(
                AdvisorHistoryRepository.ActionType.TUNING_BUNDLE,
                plan.effectiveContext.name,
                "Tuning context ${plan.effectiveContext.name} (${plan.dominantTier.name}, $applied keys)",
                "bundle",
                applied.toString(),
            )
        }
        return TuningApplyResult(
            plan = plan,
            appliedCount = applied,
            summaryLines = summaryLines,
            exportStatus = TuningExportStatus.SKIPPED_DISABLED,
        )
    }

    fun formatChangeLine(change: TuningChange): String {
        val label = TuningPreferenceLabels.shortLabel(change.key)
        val oldS = TuningPreferenceLabels.formatValue(change.oldValue)
        val newS = TuningPreferenceLabels.formatValue(change.newValue)
        return "$label: $oldS → $newS (${change.tier.name.lowercase(Locale.US)} step)"
    }

    private fun formatEffectiveContext(context: AimiTuningContext): String = when (context) {
        AimiTuningContext.MEAL_RISE -> "Meal rise"
        AimiTuningContext.HYPO_GUARD -> "Hypo guard"
        AimiTuningContext.HYPER_STABLE -> "Hyper control"
        AimiTuningContext.MIXED_BALANCE -> "Mixed (hypo + hyper)"
        AimiTuningContext.AUTO_BALANCE -> "Auto"
    }

    fun formatPlanPreview(plan: TuningPlan): String {
        val sb = StringBuilder()
        sb.append("Effective context: ${formatEffectiveContext(plan.effectiveContext)}\n")
        if (plan.blockedReason != null) {
            sb.append(plan.blockedReason)
            return sb.toString()
        }
        if (plan.changes.isEmpty()) {
            sb.append("No parameter changes — values already match this context.")
            return sb.toString()
        }
        plan.changes.forEach { sb.append(formatChangeLine(it)).append('\n') }
        return sb.toString().trimEnd()
    }

    fun tryExportSettings(
        context: Context,
        importExportPrefs: ImportExportPrefs,
        exportPasswordDataStore: ExportPasswordDataStore,
    ): TuningExportStatus {
        if (!exportPasswordDataStore.exportPasswordStoreEnabled()) {
            return TuningExportStatus.SKIPPED_DISABLED
        }
        val (password, isExpired, _) = exportPasswordDataStore.getPasswordFromDataStore(context)
        if (password.isEmpty() || isExpired) {
            return if (isExpired) TuningExportStatus.SKIPPED_PASSWORD_EXPIRED
            else TuningExportStatus.SKIPPED_NO_PASSWORD
        }
        return if (importExportPrefs.exportSharedPreferencesNonInteractive(context, password)) {
            TuningExportStatus.SUCCESS
        } else {
            TuningExportStatus.FAILED
        }
    }

    private fun applyChange(preferences: Preferences, change: TuningChange): Boolean {
        val key = change.key
        val newValue = change.newValue
        return when {
            newValue is Double && key is DoublePreferenceKey -> {
                preferences.put(key, newValue)
                true
            }
            newValue is Boolean && key is BooleanPreferenceKey -> {
                preferences.put(key, newValue)
                true
            }
            else -> false
        }
    }
}
