package app.aaps.core.interfaces.rx.events

/**
 * Posted when a preference value changes (e.g. from the settings UI).
 *
 * @param key The preference key that changed ([app.aaps.core.keys.interfaces.PreferenceKey.key] or equivalent).
 */
class EventPreferenceChange(val key: String) : Event() {

    fun isChanged(preferenceKey: String): Boolean = preferenceKey == key
}
