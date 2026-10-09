package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.TextRef

/**
 * Platform-provided strings for the settings audit screen.
 *
 * The audit content built by [AimiSettingsAudit] is pure — a function of the manifest and the
 * stored preferences — but the "changed by" labels and the yes/no value descriptors need
 * localized text. Those live in Android `R.string` tables, so they are a platform seam:
 * Android provides the implementation, commonMain only declares what it needs.
 */
internal interface AimiSettingsAuditStrings {
    val changedByLoop: TextRef
    val changedBySlider: TextRef
    val changedByPreset: TextRef
    val changedByUser: TextRef
    val yes: TextRef
    val no: TextRef
}

/** Resolves the display label for who changed a setting. */
internal fun AimiSettingsAuditChangedBy.label(strings: AimiSettingsAuditStrings): TextRef = when (this) {
    AimiSettingsAuditChangedBy.LOOP   -> strings.changedByLoop
    AimiSettingsAuditChangedBy.SLIDER -> strings.changedBySlider
    AimiSettingsAuditChangedBy.PRESET -> strings.changedByPreset
    AimiSettingsAuditChangedBy.USER   -> strings.changedByUser
}
