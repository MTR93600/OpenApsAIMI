package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.StringPreferenceKey

/*
 * Some Apex settings only make sense for a pump firmware that reports the battery voltage. That is
 * known only at run time, from the connected pump, so the key enums cannot say it. These wrappers give
 * a key a visibility rule from the plugin and keep everything else of the key, the same way
 * `withEntries` adds entries. They replace the `findPreference(...).isVisible` calls of the old
 * preference screen.
 */

private class BooleanKeyWithVisibility(
    private val delegate: BooleanPreferenceKey,
    override val visibility: ElementVisibility
) : BooleanPreferenceKey by delegate

private class StringKeyWithVisibility(
    private val delegate: StringPreferenceKey,
    override val visibility: ElementVisibility
) : StringPreferenceKey by delegate

private class DoubleKeyWithVisibility(
    private val delegate: DoublePreferenceKey,
    override val visibility: ElementVisibility
) : DoublePreferenceKey by delegate

internal fun BooleanPreferenceKey.withVisibility(visibility: ElementVisibility): BooleanPreferenceKey = BooleanKeyWithVisibility(this, visibility)
internal fun StringPreferenceKey.withVisibility(visibility: ElementVisibility): StringPreferenceKey = StringKeyWithVisibility(this, visibility)
internal fun DoublePreferenceKey.withVisibility(visibility: ElementVisibility): DoublePreferenceKey = DoubleKeyWithVisibility(this, visibility)
