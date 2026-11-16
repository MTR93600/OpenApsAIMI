package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorDataSufficiency
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetricRules
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetrics
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiPrefsSnapshot
import app.aaps.plugins.aps.openAPSAIMI.advisor.PkpdAdvisor
import app.aaps.plugins.aps.openAPSAIMI.advisor.PkpdPrefsSnapshot
import app.aaps.plugins.aps.openAPSAIMI.advisor.metricRecommendations
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoDeltaBuilder
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPackId
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPreferenceKeys
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoProposal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step A1: the final audit of the automatic writers after the settings reorganisation.
 *
 * The Advisor (metric rules, PK/PD advisor, the service rules), the tuning engine and TPO propose or
 * write preference values. This test runs the proposal code over many stored states and periods,
 * collects every key and value it can produce, and checks for each one:
 * - the key is a setting the person owns (manifest entry with the USER writer), not internal state
 *   and not a deleted key;
 * - the person can find it: it is reachable in the settings tree (a page, a feature card, Expert or
 *   the PK/PD screen), and it is not in the reachability debt;
 * - the person can see and undo the change: the settings audit ("Changes") shows a row for it, and
 *   its reset goes back to the key default;
 * - the value is on the key's own scale and inside its bounds, and it is a value that has an effect
 *   (not in a zone the loop rewrites or floors).
 *
 * Shared code, so it also runs on `iosSimulatorArm64Test`.
 */
class AimiAutomaticWritersAuditTest {

    private object StubText : TextResolver {
        override fun gs(ref: TextRef): String = "text"
        override fun gs(ref: TextRef, vararg args: Any?): String = "text"
        override fun gsNotLocalised(ref: TextRef): String = "text"
    }

    /** One proposed write: who proposes it, the key, the value before and the new value. */
    private data class Write(val source: String, val key: PreferenceKey, val value: Any, val old: Any? = null)

    private fun metrics(timeBelow70: Double, timeAbove180: Double, todayTir: Double? = null) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = (1.0 - timeBelow70 - timeAbove180).coerceAtLeast(0.0),
        tir70_140 = 0.4,
        timeBelow70 = timeBelow70,
        timeBelow54 = timeBelow70 / 3.0,
        timeAbove180 = timeAbove180,
        timeAbove250 = timeAbove180 / 3.0,
        meanBg = 160.0,
        variabilityCv = 0.35,
        gmi = 7.0,
        tdd = 40.0,
        basalPercent = 0.60,
        hypoEvents = 3,
        severeHypoEvents = 1,
        hyperEvents = 5,
        todayTir = todayTir,
        todayTdd = null,
        dataSufficiency = AdvisorDataSufficiency.GOOD,
    )

    private val periods: List<AdvisorMetrics> =
        listOf(0.0, 0.02, 0.03, 0.04, 0.05, 0.06, 0.09).flatMap { hypo ->
            listOf(0.05, 0.12, 0.20, 0.30, 0.45).map { hyper -> metrics(hypo, hyper) }
        }

    /** Stored states: the defaults, every double at its min and at its max, every boolean on and off. */
    private fun storedStates(): List<MapPreferences> = listOf(
        MapPreferences(),
        MapPreferences().apply { DoubleKey.entries.forEach { doubles[it] = it.min } },
        MapPreferences().apply { DoubleKey.entries.forEach { doubles[it] = it.max } },
        MapPreferences().apply { BooleanKey.entries.forEach { booleans[it] = true } },
        MapPreferences().apply { BooleanKey.entries.forEach { booleans[it] = false } },
        MapPreferences().apply {
            BooleanKey.entries.forEach { booleans[it] = false }
            DoubleKey.entries.forEach { doubles[it] = it.max }
        },
        MapPreferences().apply {
            BooleanKey.entries.forEach { booleans[it] = true }
            DoubleKey.entries.forEach { doubles[it] = it.min }
        },
    )

    private fun tuningWrites(): List<Write> =
        storedStates().flatMap { preferences ->
            AimiTuningContext.entries.flatMap { context ->
                periods.flatMap { period ->
                    listOf(true, false).flatMap { t3c ->
                        TuningContextEngine.computePlan(context, period, preferences, t3c).changes
                            .map { Write("Tuning", it.key, it.newValue, it.oldValue) }
                    }
                }
            }
        }

    private fun tpoWrites(): List<Write> =
        storedStates().flatMap { preferences ->
            TpoPackId.entries.flatMap { pack ->
                TuningStepTier.entries.flatMap { tier ->
                    listOf(0.1, 0.5).flatMap { hypoLoad ->
                        listOf(true, false).flatMap { t3c ->
                            TpoDeltaBuilder.buildPlan(TpoProposal(pack, tier, 0.8, listOf("AUDIT")), preferences, hypoLoad, t3c).changes
                                .map { Write("TPO", it.key, it.newValue, it.oldValue) }
                        }
                    }
                }
            }
        }

    private fun advisorRuleWrites(): List<Write> =
        storedStates().flatMap { preferences ->
            val prefs = AimiPrefsSnapshot(
                maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB),
                lunchFactor = preferences.get(DoubleKey.OApsAIMILunchFactor),
                unifiedReactivityFactor = 1.0,
                autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal),
            )
            val pkpd = listOf(true, false).map { enabled ->
                PkpdPrefsSnapshot(
                    pkpdEnabled = enabled,
                    learnedDiaH = 6.0,
                    learnedPeakMin = 75.0,
                    maxDiaChangePerDayH = 2.0,
                    maxPeakChangePerDayMin = 20.0,
                    isfFusionMinFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
                    isfFusionMaxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
                    isfFusionMaxChangePerTick = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick),
                    smbTailThreshold = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
                    smbTailDamping = PkpdSmbTailDamping.effectiveStoredValue(preferences.get(DoubleKey.OApsAIMISmbTailDamping)),
                    smbExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
                    smbLateFatDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping),
                )
            }
            periods.flatMap { period ->
                val metric = metricRecommendations(period, prefs, StubText)
                val pkpdRecs = pkpd.flatMap { PkpdAdvisor().analysePkpd(period, it, StubText) }
                (metric + pkpdRecs).mapNotNull { rec ->
                    (rec.action as? AimiAction.PreferenceUpdate)?.let { action ->
                        val old = (action.key as? DoublePreferenceKey)?.let { preferences.get(it) }
                        Write("Advisor", action.key, action.newValue, old)
                    }
                }
            }
        }

    /**
     * The keys the Advisor service rules can propose (`AimiAdvisorService.generateRecommendations`,
     * the "pragmatic SMB/PKPD governance" block). The service needs a database and a pump, so these are
     * listed by hand: keep the list in sync with that block.
     */
    private val advisorServiceKeys: List<PreferenceKey> = listOf(
        BooleanKey.OApsAIMIRecursiveBeliefShadow,
        BooleanKey.OApsAIMIRecursiveBeliefAuthority,
        BooleanKey.OApsAIMIHyperTrajectoryRelease,
        BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled,
        DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
        DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
        DoubleKey.OApsAIMIPriorityMaxIobFactor,
        DoubleKey.OApsAIMIPriorityMaxIobExtraU,
    )

    private val allWrites: List<Write> by lazy { tuningWrites() + tpoWrites() + advisorRuleWrites() }

    private val allKeys: Set<PreferenceKey> by lazy { (allWrites.map { it.key } + advisorServiceKeys + TpoPreferenceKeys.allKeys()).toSet() }

    @Test
    fun theProposalCodeReallyProposesSomething() {
        // Guards the grid above: if it stopped reaching the rules, every other check would pass empty.
        assertTrue(tuningWrites().isNotEmpty())
        assertTrue(tpoWrites().isNotEmpty())
        assertTrue(advisorRuleWrites().isNotEmpty())
    }

    @Test
    fun everyAutomaticWriteIsASettingThePersonOwns() {
        val notOwned = allKeys.map { it.key }.filter { key ->
            val entry = AimiSettingsManifest.entryForKeyString(key)
            entry == null || AimiSettingsWriter.USER !in entry.writers
        }
        assertEquals(emptyList(), notOwned.sorted())
    }

    @Test
    fun everyKeyTpoWritesIsOnItsWhiteList() {
        // A key outside the white list is dropped by the session manager without a word, so a pack
        // would promise a change it never makes.
        val outside = tpoWrites().map { it.key.key }.toSet().filterNot { TpoPreferenceKeys.isWhitelisted(it) }
        assertEquals(emptyList(), outside.sorted())
    }

    @Test
    fun everyAutomaticWriteIsReachableInTheSettings() {
        // The same walk as `AimiSettingsReachabilityTest`, with the screens of their own (PK/PD, context)
        // that the tree opens. iOS has the same PK/PD and context entries as Android. No key that an
        // automatic writer changes may be in the reachability debt.
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems))
        val lost = allKeys.map { it.key }.filterNot { it in reachable }
        assertEquals(emptyList(), lost.sorted())
        assertEquals(emptyList(), allKeys.map { it.key }.filter { it in AimiSettingsManifest.REACHABILITY_DEBT }.sorted())
    }

    /** A store that keeps "never stored" apart for doubles and booleans, so the audit sees real rows. */
    private class StoredPreferences(private val base: MapPreferences = MapPreferences()) : Preferences by base {

        val booleans: MutableMap<NonPreferenceKey, Boolean> = mutableMapOf()

        override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = booleans[key]
        override fun get(key: BooleanNonPreferenceKey): Boolean = booleans[key] ?: key.defaultValue
        override fun get(key: BooleanPreferenceKey): Boolean = booleans[key] ?: key.defaultValue
        override fun put(key: BooleanNonPreferenceKey, value: Boolean) {
            booleans[key] = value
        }
    }

    @Test
    fun everyAutomaticWriteShowsInTheChangesPageAndCanBeUndone() {
        val samples = allWrites.groupBy { it.key.key }.mapNotNull { (_, writes) ->
            writes.firstOrNull { write -> !sameAsDefault(write) }
        }.associateBy { it.key.key }
        val serviceSamples = advisorServiceKeys.filterNot { it.key in samples }.map { key ->
            when (key) {
                is BooleanPreferenceKey -> Write("Advisor", key, !key.defaultValue)
                is DoublePreferenceKey  -> Write("Advisor", key, if (key.defaultValue < key.max) key.max else key.min)
                else                    -> error("no sample for ${key.key}")
            }
        }
        for (write in samples.values + serviceSamples) {
            val preferences = StoredPreferences()
            store(preferences, write)
            val row = assertNotNull(
                AimiSettingsAudit.build(preferences, AimiSettingsAuditResourceStrings).rows.singleOrNull { it.keyString == write.key.key },
                "no Changes row for ${write.source} ${write.key.key}",
            )
            row.resetToDefault(preferences)
            assertTrue(sameAsDefault(preferences, write.key), "reset did not restore ${write.key.key}")
        }
    }

    private fun sameAsDefault(write: Write): Boolean = when (val key = write.key) {
        is BooleanPreferenceKey -> write.value == key.defaultValue
        is DoublePreferenceKey  -> abs((write.value as Double) - key.defaultValue) < 1e-9
        else                    -> false
    }

    private fun sameAsDefault(preferences: Preferences, key: PreferenceKey): Boolean = when (key) {
        is BooleanPreferenceKey -> preferences.get(key) == key.defaultValue
        is DoublePreferenceKey  -> abs(preferences.get(key) - key.defaultValue) < 1e-9
        else                    -> false
    }

    private fun store(preferences: Preferences, write: Write) {
        when (val key = write.key) {
            is BooleanPreferenceKey -> preferences.put(key, write.value as Boolean)
            is DoublePreferenceKey  -> preferences.put(key, write.value as Double)
            else                    -> error("unexpected key type ${key.key}")
        }
    }

    @Test
    fun everyProposedNumberIsInsideTheBoundsOfItsKey() {
        val outside = allWrites.mapNotNull { write ->
            val key = write.key as? DoublePreferenceKey ?: return@mapNotNull null
            val value = write.value as Double
            "${write.source} ${key.key}=$value".takeIf { value.isNaN() || value < key.min - 1e-9 || value > key.max + 1e-9 }
        }
        assertEquals(emptyList(), outside.distinct())
    }

    @Test
    fun everyProposedMealFactorIsOnThePercentScaleAndAnAutomaticRaiseStopsAtNeutral() {
        val mealFactors = setOf(DoubleKey.OApsAIMILunchFactor.key, DoubleKey.OApsAIMIDinnerFactor.key)
        val wrong = allWrites.filter { it.key.key in mealFactors }.mapNotNull { write ->
            val value = write.value as Double
            // Below 2 is the old 1.0 scale that the T2 repair turns back into the default.
            "${write.source} ${write.key.key}=$value".takeIf { value < 2.0 }
        }
        assertEquals(emptyList(), wrong.distinct())
    }

    @Test
    fun noAutomaticWriteLandsWhereTheLoopIgnoresIt() {
        val wrong = allWrites.mapNotNull { write ->
            val value = write.value as? Double ?: return@mapNotNull null
            val label = "${write.source} ${write.key.key}=$value"
            when (write.key.key) {
                // At or below 0.55 the loop reads the neutral 0.85 instead (legacy zone).
                DoubleKey.OApsAIMISmbTailDamping.key                                         ->
                    label.takeIf { value < PkpdSmbTailDamping.DAMPING_STRONG - 1e-9 || value > PkpdSmbTailDamping.DAMPING_LIGHT + 1e-9 }
                // Below 0.5 the loop applies 0.5 (floor of `SmbDamping`).
                DoubleKey.OApsAIMISmbExerciseDamping.key, DoubleKey.OApsAIMISmbLateFatDamping.key ->
                    label.takeIf { value < SmbDamping.DAMPING_FLOOR - 1e-9 }
                // The loop clamps these to the same range as the key; nothing outside it acts.
                DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key                           -> label.takeIf { value < 0.50 || value > 1.0 }
                DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key                              -> label.takeIf { value < 0.50 || value > 0.95 }
                DoubleKey.OApsAIMIPriorityMaxIobFactor.key                                   -> label.takeIf { value < 1.0 || value > 1.6 }
                DoubleKey.OApsAIMIPriorityMaxIobExtraU.key                                   -> label.takeIf { value < 0.0 || value > 5.0 }
                else                                                                         -> null
            }
        }
        assertEquals(emptyList(), wrong.distinct())
    }

    @Test
    fun anAutomaticLunchFactorRaiseNeverEndsAboveNeutral() {
        // Step T2: a raise stops at 100. A value already above 100 may only be lowered.
        val tooHigh = (tuningWrites() + advisorRuleWrites())
            .filter { it.key.key == DoubleKey.OApsAIMILunchFactor.key }
            .mapNotNull { write ->
                val value = write.value as Double
                val old = write.old as Double
                "${write.source} $old -> $value".takeIf { value > old && value > AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING + 1e-9 }
            }
        assertEquals(emptyList(), tooHigh.distinct())
    }
}
