package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.abs

/*
 * The read only T3C and Harmonia runtime cards of the "What AIMI learned" page. They show the last
 * loop tick of each engine. They were on the Control Center until step S2. `AimiRuntimeLoaders.kt`
 * builds them from the runtime history files.
 */

/** One line of a runtime card: a title and a value (a text, or a string such as yes / no). */
internal data class AimiRuntimeDetail(
    val titleResId: TextRef,
    val valueText: String? = null,
    val valueResId: TextRef? = null,
)

internal enum class AimiT3cRuntimeStatus(val labelResId: TextRef) {
    NativeApplied(ApsStrings.aimi_control_center_t3c_status_native_applied),
    NativeReady(ApsStrings.aimi_control_center_t3c_status_native_ready),
    NativeBlocked(ApsStrings.aimi_control_center_t3c_status_native_blocked),
    LegacyFallback(ApsStrings.aimi_control_center_t3c_status_legacy_fallback),
    SafetyTerminal(ApsStrings.aimi_control_center_t3c_status_safety_terminal),
    Unavailable(ApsStrings.aimi_control_center_t3c_status_unavailable),
}

internal enum class AimiT3cRuntimeOwner(val labelResId: TextRef) {
    NativeRbt(ApsStrings.aimi_control_center_t3c_owner_native),
    LegacyBypass(ApsStrings.aimi_control_center_t3c_owner_legacy),
    SafetyGate(ApsStrings.aimi_control_center_t3c_owner_safety),
    Unavailable(ApsStrings.aimi_control_center_t3c_owner_unavailable),
}

internal data class AimiT3cRuntimeSnapshot(
    val status: AimiT3cRuntimeStatus,
    val owner: AimiT3cRuntimeOwner,
    val modeText: String,
    val authorityApplied: Boolean,
    val shadowOnly: Boolean,
    val details: List<AimiRuntimeDetail>,
)

internal enum class AimiHarmoniaRuntimeStatus(val labelResId: TextRef) {
    NativeApplied(ApsStrings.aimi_control_center_harmonia_status_native_applied),
    NativeReady(ApsStrings.aimi_control_center_harmonia_status_native_ready),
    NativeBlocked(ApsStrings.aimi_control_center_harmonia_status_native_blocked),
    T3cPriority(ApsStrings.aimi_control_center_harmonia_status_t3c_priority),
    Unavailable(ApsStrings.aimi_control_center_harmonia_status_unavailable),
}

internal data class AimiHarmoniaRuntimeSnapshot(
    val status: AimiHarmoniaRuntimeStatus,
    val productionModeText: String,
    val active: Boolean,
    val eligible: Boolean,
    val selectedForProduction: Boolean,
    val addsSmbAuthority: Boolean,
    val details: List<AimiRuntimeDetail>,
)

/** The T3C card when no runtime history can be read. */
internal fun unavailableT3cRuntimeSnapshot(): AimiT3cRuntimeSnapshot =
    AimiT3cRuntimeSnapshot(
        status = AimiT3cRuntimeStatus.Unavailable,
        owner = AimiT3cRuntimeOwner.Unavailable,
        modeText = "UNAVAILABLE",
        authorityApplied = false,
        shadowOnly = true,
        details = emptyList(),
    )

/** The Harmonia card when no runtime history can be read. */
internal fun unavailableHarmoniaRuntimeSnapshot(): AimiHarmoniaRuntimeSnapshot =
    AimiHarmoniaRuntimeSnapshot(
        status = AimiHarmoniaRuntimeStatus.Unavailable,
        productionModeText = "UNAVAILABLE",
        active = false,
        eligible = false,
        selectedForProduction = false,
        addsSmbAuthority = false,
        details = emptyList(),
    )

/**
 * A number for a card or the audit screen: no decimals for a whole number, one decimal from 10
 * up, two below, then the unit if there is one.
 */
internal fun formatAimiNumber(value: Double, unit: String?): String {
    val formatted = when {
        abs(value - value.toInt().toDouble()) < 0.005 -> value.toInt().toString()
        value >= 10.0                                 -> aimiFmt1(value)
        else                                          -> aimiFmt2(value)
    }
    return if (unit.isNullOrBlank()) formatted else "$formatted $unit"
}
