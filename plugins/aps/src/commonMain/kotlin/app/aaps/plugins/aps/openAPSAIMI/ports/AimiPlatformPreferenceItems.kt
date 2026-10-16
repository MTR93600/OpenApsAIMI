package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.core.keys.interfaces.PreferenceItem

/**
 * The AIMI settings entries that open a screen which only the platform can show.
 *
 * Most of the AIMI settings tree is shared code. A few entries open a screen that is still built on
 * Android classes (for example the support package export, or the health permission screen). The
 * plugin asks this port for each one by its [AimiPlatformPreferenceSlot]. The plugin decides where
 * the entry sits in the tree. The platform decides what the entry is.
 *
 * A platform that cannot honour an entry returns `null`. The entry is then not in the settings
 * tree at all. It is never shown as a dead row.
 */
interface AimiPlatformPreferenceItems {

    /** Returns the entry for [slot], or `null` when this platform has no such screen. */
    fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem?
}

/** The entries that a platform provides. See [AimiPlatformPreferenceItems]. */
enum class AimiPlatformPreferenceSlot {
    /** Builds and shares the support package (zip). */
    SUPPORT_PACKAGE,

    /** The patient context screen. */
    CONTEXT,

    /** The AI meal advisor screen. */
    MEAL_ADVISOR,

    /** The mode settings screen. */
    MODE_SETTINGS,

    /** The profile advisor screen. */
    PROFILE_ADVISOR,

    /** The screen where the user grants the emergency SOS permissions. */
    SOS_PERMISSIONS,

    /** The screen where the user grants the health data permissions. */
    HEALTH_CONNECT_PERMISSIONS,

    /** The read only study data viewer. */
    HORMONITOR_VIEWER,

    /** The guided PK/PD setup screen. */
    PKPD_SETUP,
}
