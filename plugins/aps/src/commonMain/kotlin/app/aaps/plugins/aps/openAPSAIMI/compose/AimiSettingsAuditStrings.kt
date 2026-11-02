package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings

/**
 * The strings the settings audit needs.
 *
 * The audit content built by [AimiSettingsAudit] is pure — a function of the manifest and the
 * stored preferences — but the "changed by" labels and the yes/no value descriptors need
 * localized text. They come in through this interface so a test can pass its own text;
 * [AimiSettingsAuditResourceStrings] is the one the app uses on every platform.
 */
internal interface AimiSettingsAuditStrings {
    val changedByLoop: TextRef
    val changedBySlider: TextRef
    val changedByPreset: TextRef
    val changedByUser: TextRef
    val yes: TextRef
    val no: TextRef
}

/** The app strings for the settings audit. Plain string references, so they work on Android and iOS. */
internal object AimiSettingsAuditResourceStrings : AimiSettingsAuditStrings {
    override val changedByLoop: TextRef = ApsStrings.aimi_settings_audit_changed_by_loop
    override val changedBySlider: TextRef = ApsStrings.aimi_settings_audit_changed_by_slider
    override val changedByPreset: TextRef = ApsStrings.aimi_settings_audit_changed_by_preset
    override val changedByUser: TextRef = ApsStrings.aimi_settings_audit_changed_by_user
    override val yes: TextRef = CoreUiStrings.yes
    override val no: TextRef = CoreUiStrings.no
}

/** Resolves the display label for who changed a setting. */
internal fun AimiSettingsAuditChangedBy.label(strings: AimiSettingsAuditStrings): TextRef = when (this) {
    AimiSettingsAuditChangedBy.LOOP   -> strings.changedByLoop
    AimiSettingsAuditChangedBy.SLIDER -> strings.changedBySlider
    AimiSettingsAuditChangedBy.PRESET -> strings.changedByPreset
    AimiSettingsAuditChangedBy.USER   -> strings.changedByUser
}
