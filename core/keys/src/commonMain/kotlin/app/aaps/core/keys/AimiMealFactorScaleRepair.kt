package app.aaps.core.keys

import app.aaps.core.keys.interfaces.Preferences

/**
 * One-shot repair of AIMI meal factors that the old TPO wrote on the wrong scale (step T2 of the
 * AIMI settings plan).
 *
 * The meal factor keys are percent: 50 is half the meal SMB, 100 is neutral. Before step T1 the TPO
 * stepped the lunch and dinner factors as if they were on a 1.0 scale, so an accepted pack could
 * store about 1.0 to 1.3. That turns the meal SMB almost off. A value below [BROKEN_BELOW] can only
 * come from that old write, so once, every stored meal factor below it goes back to its key default
 * (50, sleep 60). Any other value is kept, and a key that was never stored is not written.
 *
 * It runs once: [BooleanNonKey.OApsAIMIMealFactorScaleRepaired] records that it ran, and a low value
 * the user sets by hand later is kept. The flag is exported, so a backup made after the repair keeps
 * its values on import, and a backup made before it is repaired on import.
 *
 * Two callers share this rule: the AIMI plugin start (over [Preferences], Android and iOS; it sets
 * the flag) and the start-up and import preference migrations (`PreferenceMigrations`, over the raw
 * store or the import file; they read the flag but do not write it, so an import of a file with no
 * meal factor changes nothing).
 *
 * Dosing effect: a user with such a value gets the default meal SMB back (50 % or 60 % instead of
 * about 1 %).
 */
object AimiMealFactorScaleRepair {

    /** The meal factor keys this repair looks at. All percent, key minimum 1.0. */
    val keys: List<DoubleKey> = listOf(
        DoubleKey.OApsAIMIMealFactor,
        DoubleKey.OApsAIMIBFFactor,
        DoubleKey.OApsAIMILunchFactor,
        DoubleKey.OApsAIMIDinnerFactor,
        DoubleKey.OApsAIMIHCFactor,
        DoubleKey.OApsAIMISnackFactor,
        DoubleKey.OApsAIMIsleepFactor,
    )

    /** A stored value below this is a 1.0-scale value from the old TPO. */
    const val BROKEN_BELOW: Double = 2.0

    /** One repaired key: what was stored, and the default it goes back to. */
    class Repair(val key: DoubleKey, val storedValue: Double) {

        val newValue: Double get() = key.defaultValue

        override fun toString(): String = "${key.key}: $storedValue -> $newValue"
    }

    /**
     * The repair over any store. [readStored] gives the stored value, or null when the key is not
     * stored (or cannot be read as a number, which is then left in place).
     *
     * @return the keys it repaired, empty when [alreadyDone] or nothing was broken.
     */
    fun repairOnce(
        alreadyDone: Boolean,
        readStored: (DoubleKey) -> Double?,
        write: (DoubleKey, Double) -> Unit,
        markDone: () -> Unit,
    ): List<Repair> {
        if (alreadyDone) return emptyList()
        val repairs = keys.mapNotNull { key ->
            readStored(key)?.takeIf { it < BROKEN_BELOW }?.let { Repair(key, it) }
        }
        repairs.forEach { write(it.key, it.newValue) }
        markDone()
        return repairs
    }

    /** The repair over [preferences], for the AIMI plugin start. */
    fun repairOnce(preferences: Preferences): List<Repair> =
        repairOnce(
            alreadyDone = preferences.get(BooleanNonKey.OApsAIMIMealFactorScaleRepaired),
            readStored = { preferences.getIfExists(it) },
            write = { key, value -> preferences.put(key, value) },
            markDone = { preferences.put(BooleanNonKey.OApsAIMIMealFactorScaleRepaired, true) },
        )
}
