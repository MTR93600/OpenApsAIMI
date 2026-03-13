package app.aaps.plugins.aps.openAPSAIMI.model

import app.aaps.core.keys.interfaces.PreferenceKey

/**
 * 🛰️ AIMI Core Decision Models
 * 
 * Unified sealed class architecture for the Advanced AIMI Hybrid Closed Loop.
 * Ensures compile-time safety and absolute exhaustiveness in decision handling.
 */

/**
 * 🛠️ AimiAction
 * Represents a discrete medical action proposed by an Advisor or Plugin.
 */
sealed class AimiAction {
    abstract val reason: String
    abstract val timestamp: Long
    abstract val domain: AimiDomain
    abstract val priority: AimiPriority

    data class TemporaryBasal(
        val rate: Double,
        val durationMinutes: Int,
        override val reason: String,
        override val domain: AimiDomain = AimiDomain.Basal,
        override val priority: AimiPriority = AimiPriority.Medium,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AimiAction()

    data class SMB(
        val amount: Double,
        override val reason: String,
        override val domain: AimiDomain = AimiDomain.Smb,
        override val priority: AimiPriority = AimiPriority.Medium,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AimiAction()

    data class Bolus(
        val amount: Double,
        override val reason: String,
        override val domain: AimiDomain = AimiDomain.Bolus,
        override val priority: AimiPriority = AimiPriority.High,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AimiAction()

    data class PreferenceUpdate(
        val key: PreferenceKey,
        val newValue: Any,
        override val reason: String,
        override val domain: AimiDomain = AimiDomain.Profile,
        override val priority: AimiPriority = AimiPriority.Medium,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AimiAction()

    data class Notification(
        val title: String,
        val message: String,
        override val domain: AimiDomain = AimiDomain.Safety,
        override val priority: AimiPriority = AimiPriority.Low,
        override val reason: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AimiAction()
}

/**
 * 🌍 AimiDomain
 * Sealed class replacing the RecommendationDomain enum.
 */
sealed class AimiDomain(val name: String) {
    object Safety : AimiDomain("Safety")
    object Basal : AimiDomain("Basal")
    object Isf : AimiDomain("Isf")
    object Target : AimiDomain("Target")
    object Smb : AimiDomain("Smb")
    object Bolus : AimiDomain("Bolus")
    object Profile : AimiDomain("Profile")
    object Pkpd : AimiDomain("Pkpd")
}

/**
 * ⚡ AimiPriority
 * Sealed class replacing the RecommendationPriority enum.
 */
sealed class AimiPriority(val name: String) {
    object Critical : AimiPriority("Critical")
    object High : AimiPriority("High")
    object Medium : AimiPriority("Medium")
    object Low : AimiPriority("Low")
}

/**
 * ⚖️ AimiVerdict
 * Represents the Auditor's judgment on a proposed AimiAction.
 */
sealed class AimiVerdict {
    abstract val action: AimiAction
    abstract val auditorReason: String
    abstract val confidence: Double

    data class Confirmed(
        override val action: AimiAction,
        override val auditorReason: String,
        override val confidence: Double
    ) : AimiVerdict()

    data class Modified(
        override val action: AimiAction,
        val modifiedAction: AimiAction,
        override val auditorReason: String,
        override val confidence: Double
    ) : AimiVerdict()

    data class Rejected(
        override val action: AimiAction,
        override val auditorReason: String,
        override val confidence: Double = 1.0
    ) : AimiVerdict()
}

/**
 * 🔍 VerdictType
 * Sealed class replacing the VerdictType enum.
 */
sealed class VerdictType(val name: String) {
    object Confirm : VerdictType("CONFIRM")
    object Soften : VerdictType("SOFTEN")
    object ShiftToTbr : VerdictType("SHIFT_TO_TBR")
}

/**
 * 🚦 AimiState
 * Represents the operational state of the AIMI AutoDrive loop.
 */
sealed class AimiState {
    object Manual : AimiState()
    
    data class AutoDrive(
        val isActive: Boolean,
        val isShadowMode: Boolean,
        val controllerType: ControllerType
    ) : AimiState() {
        sealed class ControllerType {
            object MPC : ControllerType()
            object PI : ControllerType()
            object Hybrid : ControllerType()
        }
    }

    data class SafetyIntervention(
        val reason: String,
        val recoveryAction: RecoveryAction
    ) : AimiState() {
        sealed class RecoveryAction {
            object Rollback : RecoveryAction()
            object LgsSuspend : RecoveryAction()
            object NotifyUser : RecoveryAction()
        }
    }
}

/**
 * 🧬 AimiPluginContext
 * Comprehensive snapshot of the system state for decision plugins.
 */
data class AimiPluginContext(
    val glucose: app.aaps.core.interfaces.aps.GlucoseStatusAIMI,
    val profile: app.aaps.core.interfaces.aps.OapsProfileAimi,
    val iob: List<app.aaps.core.interfaces.aps.IobTotal>,
    val cob: Double,
    val preferences: app.aaps.core.keys.interfaces.Preferences,
    val timestamp: Long = System.currentTimeMillis()
)
