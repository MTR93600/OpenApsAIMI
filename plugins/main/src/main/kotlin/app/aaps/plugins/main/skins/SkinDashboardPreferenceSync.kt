package app.aaps.plugins.main.skins

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures [StringKey.GeneralSkin] has a persisted canonical value on startup.
 *
 * Compose home routing is skin-driven via [SkinInterface.dashboardHomeVariant]. The
 * [app.aaps.core.keys.BooleanKey.OverviewUseDashboardLayout] toggle remains a legacy/classic-UI preference and
 * must not be forced from skin selection in Compose mode.
 */
@Singleton
class SkinDashboardPreferenceSync @Inject constructor(
    private val preferences: Preferences,
    private val skinProvider: SkinProvider,
) {

    /**
     * Run once when the main shell starts.
     *
     * - [StringKey.GeneralSkin] defaults to `""`; [SkinProviderImpl.activeSkin] then falls back to the first skin,
     *   but Compose list prefs show an empty summary when the stored value is not one of the entry keys.
     *   Persist the canonical class name so the chosen skin appears in Settings.
     */
    fun onStartup() {
        val storedSkin = preferences.get(StringKey.GeneralSkin)
        val skins = skinProvider.list
        val resolved = when {
            storedSkin.isEmpty() -> skins.firstOrNull()
            else                 -> skins.firstOrNull { it.javaClass.name == storedSkin }
                ?: skins.firstOrNull { it.javaClass.simpleName == storedSkin }
                ?: skins.firstOrNull()
        } ?: return
        if (storedSkin != resolved.javaClass.name) {
            preferences.put(StringKey.GeneralSkin, resolved.javaClass.name)
        }
    }

    fun onSkinSelectionChanged() {
        // Intentionally no-op for Compose: skin selection itself drives home routing.
    }
}
