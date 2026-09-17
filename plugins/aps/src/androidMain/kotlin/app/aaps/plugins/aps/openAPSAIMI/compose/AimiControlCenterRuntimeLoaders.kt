package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickRecord
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickRecord
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickStatus

// These two loaders and their formatters stay in androidMain: T3cRuntimeHistoryReader and
// HarmoniaRuntimeHistoryReader read history files through android.os.Environment/java.io.File, and
// the tick-record types they return (T3cRuntimeTickRecord/HarmoniaRuntimeTickRecord) are declared
// inside those same androidMain reader files. Everything else that used to live in
// AimiControlCenterSnapshot.kt moved to commonMain with this file's move to androidMain - see that
// file for the pure model types (AimiT3cRuntimeSnapshot, AimiControlDetail, ...) these functions
// build.

internal fun loadLatestT3cRuntimeSnapshot(): AimiT3cRuntimeSnapshot {
    val tick = T3cRuntimeHistoryReader.readLatestTick() ?: return unavailableT3cRuntimeSnapshot()
    val status = when (tick.status) {
        T3cRuntimeTickStatus.NATIVE_APPLIED -> AimiT3cRuntimeStatus.NativeApplied
        T3cRuntimeTickStatus.NATIVE_READY -> AimiT3cRuntimeStatus.NativeReady
        T3cRuntimeTickStatus.NATIVE_BLOCKED -> AimiT3cRuntimeStatus.NativeBlocked
        T3cRuntimeTickStatus.LEGACY_FALLBACK -> AimiT3cRuntimeStatus.LegacyFallback
        T3cRuntimeTickStatus.SAFETY_TERMINAL -> AimiT3cRuntimeStatus.SafetyTerminal
        T3cRuntimeTickStatus.UNAVAILABLE -> AimiT3cRuntimeStatus.Unavailable
    }
    val owner = when (tick.ownershipCategory) {
        T3cRuntimeOwnershipCategory.NATIVE -> AimiT3cRuntimeOwner.NativeRbt
        T3cRuntimeOwnershipCategory.LEGACY -> AimiT3cRuntimeOwner.LegacyBypass
        T3cRuntimeOwnershipCategory.SAFETY -> AimiT3cRuntimeOwner.SafetyGate
        T3cRuntimeOwnershipCategory.UNAVAILABLE -> AimiT3cRuntimeOwner.Unavailable
    }

    val details = buildList {
        add(AimiControlDetail(ApsStrings.aimi_control_center_t3c_mode, valueText = tick.mode))
        tick.basalDemandRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_t3c_basal_demand,
                    valueText = formatT3cBasalDemand(tick),
                ),
            )
        }
        tick.appliedRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_t3c_applied_rate,
                    valueText = formatT3cAppliedRate(tick),
                ),
            )
        }
        tick.blocker?.let { blocker ->
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_t3c_blocker,
                    valueText = blocker,
                ),
            )
        }
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_t3c_authority_applied,
                valueResId = if (tick.authorityApplied) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_t3c_shadow_only,
                valueResId = if (tick.shadowOnly) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_t3c_selected_for_production,
                valueResId = if (tick.selectedForProduction) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_t3c_bypass_neutralized,
                valueResId = if (tick.historicalBypassNeutralized) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
    }

    return AimiT3cRuntimeSnapshot(
        status = status,
        owner = owner,
        modeText = tick.mode,
        authorityApplied = tick.authorityApplied,
        shadowOnly = tick.shadowOnly,
        details = details,
    )
}

internal fun loadLatestHarmoniaRuntimeSnapshot(): AimiHarmoniaRuntimeSnapshot {
    val tick = HarmoniaRuntimeHistoryReader.readLatestTick() ?: return unavailableHarmoniaRuntimeSnapshot()
    val status = when (tick.status) {
        HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> AimiHarmoniaRuntimeStatus.NativeApplied
        HarmoniaRuntimeTickStatus.NATIVE_READY -> AimiHarmoniaRuntimeStatus.NativeReady
        HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> AimiHarmoniaRuntimeStatus.NativeBlocked
        HarmoniaRuntimeTickStatus.T3C_PRIORITY -> AimiHarmoniaRuntimeStatus.T3cPriority
        HarmoniaRuntimeTickStatus.UNAVAILABLE -> AimiHarmoniaRuntimeStatus.Unavailable
    }
    val details = buildList {
        add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_mode, valueText = tick.productionMode ?: "RBT"))
        tick.sourceAction?.let { action ->
            add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_action, valueText = action))
        }
        tick.branch?.let { branch ->
            add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_branch, valueText = branch))
        }
        tick.basalDemandRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_harmonia_basal_demand,
                    valueText = formatHarmoniaBasalDemand(tick),
                ),
            )
        }
        tick.appliedRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_harmonia_applied_rate,
                    valueText = formatHarmoniaAppliedRate(tick),
                ),
            )
        }
        if (tick.smbEligible || tick.smbAppliedToRbtDemand || tick.targetSmbU != null || tick.smbBlocker != null) {
            add(
                AimiControlDetail(
                    titleResId = ApsStrings.aimi_control_center_harmonia_smb_channel,
                    valueText = when {
                        tick.smbAppliedToRbtDemand && tick.smbReducesRbtDemand -> "REDUCED_RBT_DEMAND"
                        tick.smbAppliedToRbtDemand -> "APPLIED_TO_RBT_DEMAND"
                        tick.smbEligible -> "READY"
                        tick.smbBlocker != null -> "BLOCKED"
                        else -> "OBSERVED"
                    },
                ),
            )
            tick.targetSmbU?.let {
                add(
                    AimiControlDetail(
                        titleResId = ApsStrings.aimi_control_center_harmonia_smb_demand,
                        valueText = formatHarmoniaSmbDemand(tick),
                    ),
                )
            }
            tick.smbBlocker?.let { blocker ->
                add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_smb_blocker, valueText = blocker))
            }
        }
        tick.blocker?.let { blocker ->
            add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_blocker, valueText = blocker))
        }
        tick.basalFirstChannel?.let { channel ->
            add(AimiControlDetail(ApsStrings.aimi_control_center_harmonia_basal_first_channel, valueText = channel))
        }
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_harmonia_selected_for_production,
                valueResId = if (tick.selectedForProduction) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_harmonia_adds_smb_authority,
                valueResId = if (tick.addsSmbAuthority) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        )
    }

    return AimiHarmoniaRuntimeSnapshot(
        status = status,
        productionModeText = tick.productionMode ?: "RBT",
        active = tick.active,
        eligible = tick.eligible,
        selectedForProduction = tick.selectedForProduction,
        addsSmbAuthority = tick.addsSmbAuthority,
        details = details,
    )
}

private fun formatT3cBasalDemand(tick: T3cRuntimeTickRecord): String {
    val demand = formatControlCenterDoubleValue(tick.basalDemandRateUph ?: 0.0, "U/h")
    val bounded = formatControlCenterDoubleValue(tick.boundedRateUph ?: tick.basalDemandRateUph ?: 0.0, "U/h")
    return "$demand -> $bounded"
}

private fun formatT3cAppliedRate(tick: T3cRuntimeTickRecord): String {
    val appliedRate = tick.appliedRateUph ?: return ""
    val rateText = formatControlCenterDoubleValue(appliedRate, "U/h")
    val appliedDuration = tick.appliedDurationMin
    return if (appliedDuration != null) "$rateText / ${appliedDuration}m" else rateText
}

private fun formatHarmoniaBasalDemand(tick: HarmoniaRuntimeTickRecord): String {
    val demand = formatControlCenterDoubleValue(tick.basalDemandRateUph ?: 0.0, "U/h")
    val bounded = formatControlCenterDoubleValue(tick.boundedRateUph ?: tick.basalDemandRateUph ?: 0.0, "U/h")
    val cap = tick.maxBasalCapUph?.let { " cap ${formatControlCenterDoubleValue(it, "U/h")}" }.orEmpty()
    return "$demand -> $bounded$cap"
}

private fun formatHarmoniaAppliedRate(tick: HarmoniaRuntimeTickRecord): String {
    val appliedRate = tick.appliedRateUph ?: return ""
    val rateText = formatControlCenterDoubleValue(appliedRate, "U/h")
    val appliedDuration = tick.appliedDurationMin
    return if (appliedDuration != null) "$rateText / ${appliedDuration}m" else rateText
}

private fun formatHarmoniaSmbDemand(tick: HarmoniaRuntimeTickRecord): String {
    val simulated = formatControlCenterDoubleValue(tick.targetSmbU ?: 0.0, "U")
    val bounded = formatControlCenterDoubleValue(tick.boundedSmbU ?: tick.targetSmbU ?: 0.0, "U")
    val after = tick.smbDemandAfterU?.let { " RBT ${formatControlCenterDoubleValue(tick.smbDemandBeforeU ?: 0.0, "U")} -> ${formatControlCenterDoubleValue(it, "U")}" }.orEmpty()
    val cap = tick.maxSmbCapU?.let { " cap ${formatControlCenterDoubleValue(it, "U")}" }.orEmpty()
    return "$simulated -> $bounded$after$cap"
}
