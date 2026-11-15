package app.aaps.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.di.metro.AppRootGraph
import app.aaps.pump.omnipod.common.keys.DashBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodIntPreferenceKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertBooleanKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey
import app.aaps.shared.tests.aapsClassesOnClasspath
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A checked-in record of every preference key and whether it leaves the phone.
 *
 * ## Why this exists
 *
 * `exportable` decides whether a key is written into a settings export - and therefore whether
 * another phone's copy of it can arrive in an import. It is DEFAULTED to `true` on
 * [NonPreferenceKey], which is right about 91% of the time (25 of roughly 287 entries opt out), so
 * the default is kept rather than making 287 entries declare it by hand.
 *
 * That decision is only safe with this file. The three pump-runtime keys in `StringNonKey` were
 * exportable for a long time with nobody typing it: they sat in that enum beside fourteen neighbours
 * that carried the `exportable = false` they were missing, and nothing ever showed the difference.
 * A default is not the problem; an INVISIBLE default is. So the classification is written down here,
 * a new key lands in a reviewable diff, and flipping one cannot pass CI in silence.
 *
 * ## How it works, and what to do when it fails
 *
 * Like Room's exported schema. A local run **rewrites** the snapshot and fails, so the change shows
 * up in `git status` and gets committed with the key that caused it. CI only compares. If this test
 * fails on your machine: read the diff, satisfy yourself the change is what you meant - especially
 * any line whose `exportable` flipped to `true` - then commit the file.
 *
 * ## What it deliberately does NOT do yet
 *
 * 3.3 asks for one snapshot per module, migration-step cross-checks and tombstones. Those belong
 * with 4.2 steps 2 and 4, and they need `kind`, which does not exist. This is the part that unblocks
 * step 2 on its own. One global snapshot also sidesteps 8.D's fourth finding by construction: there
 * is no per-module file left behind when a module leaves the build - its keys simply stop being
 * scanned, which shows up here as a removal to confirm.
 *
 * When `kind` lands, add it as a third column. The mechanism is the same.
 */
class PreferenceKeySnapshotTest {

    /**
     * Compiled classes rather than the graph: reading the live registry would mean constructing every
     * plugin, and `PreferencesImpl` only learns a plugin's keys when that plugin is built. The scan
     * sees them all without Android.
     */
    private fun allKeys(): List<NonPreferenceKey> =
        aapsClassesOnClasspath(listOf(AppRootGraph::class.java))
            .filter { NonPreferenceKey::class.java.isAssignableFrom(it) && it.isEnum }
            .flatMap { it.enumConstants.orEmpty().toList() }
            .filterIsInstance<NonPreferenceKey>()

    /** `<stored key>|<kotlin type>|<exportable>`, sorted, one per line. */
    private fun render(keys: List<NonPreferenceKey>): String =
        keys.map { key ->
            // A composed key stores many values under one prefix, so record the format it composes
            // with - the prefix alone would not say which stored strings belong to it.
            val type = key::class.java.simpleName
            "${stored(key)}|$type|${key.exportable}"
        }.distinct().sorted().joinToString("\n")

    @Test
    fun `every preference key and its exportable flag is recorded`() {
        val keys = allKeys()
        check(keys.size > 200) { "Found only ${keys.size} keys - the classpath scan broke, and an empty scan would rewrite the snapshot to nothing" }

        val rendered = render(keys) + "\n"
        val snapshot = File(repoRoot(), "app/src/testFull/resources/prefs-schema.txt")

        // Compared with the line endings normalised. Git checks this file out with CRLF on Windows
        // while the text built above always uses LF, so a byte comparison fails after every checkout,
        // rewrites the file, and fails again after the next one - committing the rewrite does not
        // help, because git re-normalises it on the way back out. Only the content is the record.
        val onDisk = snapshot.takeIf { it.exists() }?.readText()?.replace("\r\n", "\n")

        if (onDisk != rendered) {
            snapshot.parentFile.mkdirs()
            snapshot.writeText(rendered)
            // Fail on purpose: a rewritten snapshot that passed would be committed by accident, or
            // not committed at all, and CI would then be comparing against a file nobody read.
            assertThat("snapshot rewritten at ${snapshot.path} - read the diff and commit it").isEmpty()
        }
    }

    /**
     * The stored key is the only thing connecting a user's saved value to the code that reads it, so
     * two keys sharing one string means one of them reads the other's value.
     *
     * The only exception is [SHARED_STORED_KEYS]: a group of keys that share a string passes only when
     * it is exactly one pair of that list.
     */
    @Test
    fun `no two keys share a stored key string`() {
        val allowed = SHARED_STORED_KEYS.map { (android, ios) -> setOf(id(android), id(ios)) }.toSet()
        val duplicates = allKeys()
            .groupBy(::stored)
            .filter { (_, sharing) ->
                val ids = sharing.map(::id).toSet()
                ids.size > 1 && ids !in allowed
            }
            .keys

        assertThat(duplicates).isEmpty()
    }

    /**
     * Each pair of [SHARED_STORED_KEYS] must really share its stored string, and both sides must read
     * the saved value the same way: same value type, same default, same limits, same export flag.
     * Otherwise an iPhone and an Android phone would see a different setting under the same name.
     * A pair whose keys no longer share a string is a stale entry and must be removed from the list.
     */
    @Test
    fun `every allowed shared key pair shares its string and reads the value the same way`() {
        val scanned = allKeys().map(::id).toSet()
        SHARED_STORED_KEYS.forEach { (android, ios) ->
            val pair = "${id(android)} / ${id(ios)}"
            assertWithMessage("$pair: both keys must be found by the classpath scan")
                .that(scanned).containsAtLeast(id(android), id(ios))
            assertWithMessage("$pair: stored key").that(stored(ios)).isEqualTo(stored(android))
            assertWithMessage("$pair: exportable").that(ios.exportable).isEqualTo(android.exportable)
            assertWithMessage("$pair: default value").that(defaultValue(ios)).isEqualTo(defaultValue(android))
            if (android is IntPreferenceKey || ios is IntPreferenceKey) {
                assertWithMessage("$pair: both must be int preference keys")
                    .that(android is IntPreferenceKey && ios is IntPreferenceKey).isTrue()
                android as IntPreferenceKey
                ios as IntPreferenceKey
                assertWithMessage("$pair: min").that(ios.min).isEqualTo(android.min)
                assertWithMessage("$pair: max").that(ios.max).isEqualTo(android.max)
            }
        }
    }

    private fun stored(key: NonPreferenceKey): String = if (key is ComposedKey) key.key + key.format else key.key

    private fun id(key: NonPreferenceKey): String = key::class.java.name + "." + key

    /** The value type and the default together, so a Boolean `true` never equals an Int `1`. */
    private fun defaultValue(key: NonPreferenceKey): Pair<String, Any> = when (key) {
        is BooleanNonPreferenceKey -> "Boolean" to key.defaultValue
        is IntNonPreferenceKey     -> "Int" to key.defaultValue
        else                       -> error("${id(key)}: add its value type here before it can be a shared key")
    }

    private companion object {

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
         * Add a pair only when two keys must be one setting on two platforms. The test above then
         * checks that both sides read the saved value the same way.
         */
        val SHARED_STORED_KEYS: List<Pair<NonPreferenceKey, NonPreferenceKey>> = listOf(
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
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "Could not find the repository root above ${System.getProperty("user.dir")}" }
    }
}
