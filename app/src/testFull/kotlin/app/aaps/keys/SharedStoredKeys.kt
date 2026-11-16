package app.aaps.keys

import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.pump.omnipod.common.keys.DashBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodIntPreferenceKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertBooleanKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey

/**
 * Keys that share a stored string on purpose. This is the one place where sharing is allowed.
 *
 * Each pair is an Android key and its iPhone copy. The iPhone Omnipod Dash driver
 * (`:pump:omnipod:dashctl`, its plugin lives in iosMain only) reads and writes the same
 * settings as the Android Omnipod Dash driver (`:pump:omnipod:dash` with
 * `:pump:omnipod:common`): same stored string, same default, same limits. So a settings export
 * from an Android phone keeps its beeps and alerts when it is imported on an iPhone, and the
 * other way round (see `docs/kmp-migration/omnipod-dash-ios.md`, phase 4 decisions).
 *
 * Both copies are never registered in one app graph: Android registers only the Android keys
 * and the iPhone only the `dashctl` keys. They only meet here, because the Android test
 * classpath holds the compiled `dashctl` classes too.
 *
 * Add a pair only when two keys must be one setting on two platforms. `PreferenceKeySnapshotTest`
 * then checks that both sides read the saved value the same way, and `PreferenceKeyRoundTripTest`
 * accepts that a stored name of a pair resolves to either key of that pair.
 */
internal val SHARED_STORED_KEYS: List<Pair<NonPreferenceKey, NonPreferenceKey>> = listOf(
    OmnipodBooleanPreferenceKey.BolusBeepsEnabled to DashBeepKey.BolusBeepsEnabled,
    OmnipodBooleanPreferenceKey.SmbBeepsEnabled to DashBeepKey.SmbBeepsEnabled,
    OmnipodBooleanPreferenceKey.BasalBeepsEnabled to DashBeepKey.BasalBeepsEnabled,
    OmnipodBooleanPreferenceKey.TbrBeepsEnabled to DashBeepKey.TbrBeepsEnabled,
    OmnipodBooleanPreferenceKey.ExpirationReminder to DashAlertBooleanKey.ExpirationReminder,
    OmnipodBooleanPreferenceKey.ExpirationAlarm to DashAlertBooleanKey.ExpirationAlarm,
    OmnipodBooleanPreferenceKey.LowReservoirAlert to DashAlertBooleanKey.LowReservoirAlert,
    OmnipodIntPreferenceKey.ExpirationReminderHours to DashAlertIntKey.ExpirationReminderHours,
    OmnipodIntPreferenceKey.ExpirationAlarmHours to DashAlertIntKey.ExpirationAlarmHours,
    OmnipodIntPreferenceKey.LowReservoirAlertUnits to DashAlertIntKey.LowReservoirAlertUnits,
    OmnipodBooleanPreferenceKey.SoundUncertainTbrNotification to DashSoundKey.SoundUncertainTbrNotification,
    OmnipodBooleanPreferenceKey.SoundUncertainSmbNotification to DashSoundKey.SoundUncertainSmbNotification,
    OmnipodBooleanPreferenceKey.SoundUncertainBolusNotification to DashSoundKey.SoundUncertainBolusNotification,
    DashBooleanPreferenceKey.SoundDeliverySuspendedNotification to DashSoundKey.SoundDeliverySuspendedNotification,
)

/** The full name of [key]: its enum class and its name, so two keys with the same name never match. */
internal fun sharedKeyId(key: NonPreferenceKey): String = key::class.java.name + "." + key

/** Each pair of [SHARED_STORED_KEYS] as the set of its two [sharedKeyId]s. */
internal val SHARED_STORED_KEY_IDS: Set<Set<String>> =
    SHARED_STORED_KEYS.map { (android, ios) -> setOf(sharedKeyId(android), sharedKeyId(ios)) }.toSet()

/** True when [a] and [b] are one pair of [SHARED_STORED_KEYS], in any order. */
internal fun isSharedOnPurpose(a: NonPreferenceKey, b: NonPreferenceKey): Boolean =
    setOf(sharedKeyId(a), sharedKeyId(b)) in SHARED_STORED_KEY_IDS
