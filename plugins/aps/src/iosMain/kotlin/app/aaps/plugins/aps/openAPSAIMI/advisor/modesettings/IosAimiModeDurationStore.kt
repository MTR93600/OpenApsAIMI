package app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings

import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.AimiModeDurationStore.Companion.DEFAULT_MODE_DURATION_MIN
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.AimiModeDurationStore.Companion.STORE_NAME
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache

/**
 * iOS half of [AimiModeDurationStore], in the `aimi_mode_activity` suite of [AimiKeyValueCache].
 *
 * A named suite, not the standard store: the standard store is moved into the encrypted settings
 * store and emptied at every start, so a duration saved there would be lost.
 */
class IosAimiModeDurationStore(private val cache: AimiKeyValueCache) : AimiModeDurationStore {

    override fun minutes(key: String): Int =
        cache.getLong(STORE_NAME, key, DEFAULT_MODE_DURATION_MIN.toLong()).toInt()

    override fun save(key: String, minutes: Int) {
        cache.putLong(STORE_NAME, key, minutes.toLong())
    }
}
