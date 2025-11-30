package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfAdjustmentEngine
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.floor
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfBlender
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusion
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusionBounds
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration

@Singleton
open class OpenAPSAIMIPlugin  @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalaimiSMB2: DetermineBasalaimiSMB2,
    private val profiler: Profiler,
    private val context: Context,
    private val apsResultProvider: Provider<APSResult>
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openapsaimi)
        .shortName(R.string.oaps_aimi_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList({ config.APS })
        .description(R.string.description_openapsaimi)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    override fun onStart() {
        super.onStart()
        AimiUamHandler.clearCache(context)
        AimiUamHandler.installConfidenceSupplier {
            // retourne null si tu veux "laisser la main" au runtime
            preferences.get(DoubleKey.AimiUamConfidence)
        }
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
            if (variableSens > 0) dynIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorAimi.getGlucoseStatusData(allowOldData)
    override fun onStop() {
        super.onStop()
        AimiUamHandler.close(context)
    }
    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AIMI
    override var lastAPSResult: APSResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
    private val pkpdIntegration = PkPdIntegration(preferences)
    private var lastPkpdScale: Double = 1.0
    // Dans votre classe principale (ou plugin), vous pouvez déclarer :
    private val kalmanISFCalculator = KalmanISFCalculator(tddCalculator, preferences, aapsLogger)
    // Fusion lente (TDD/profile) + rate-limit de blend
    private val isfBlender = IsfBlender()
    // top-level (à côté de isfBlender / pkpdIntegration)
    private val isfAdjEngine = IsfAdjustmentEngine()

    // état EMA persistant (clé Prefs à créer si tu veux le garder entre runs)
    private var tddEma: Double? = null
    private val TDD_EMA_ALPHA = 0.2 // ou pref


    // Recrée les bornes de la fusion ISF depuis les préférences (mêmes clés que PkPdIntegration)
    private fun isfFusion(): IsfFusion {
        val bounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
        )
        return IsfFusion(bounds)
    }

    @SuppressLint("DefaultLocale")
    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as? ProfileSealed.EPS)?.value?.originalPercentage?.div(100.0)
            ?: return null

        val sensitivity = calculateVariableIsf(start, multiplier)

        profiler.log(
            LTag.APS,
            "getIsfMgdl() ${sensitivity.first} ${sensitivity.second} ${dateUtil.dateAndTimeAndSecondsString(start)} $caller",
            start
        )

        return sensitivity.second?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        if (dynIsfCache == null || dynIsfCache.size() == 0) {
            aapsLogger.warn(LTag.APS, "dynIsfCache is null or empty. Unable to calculate average ISF.")
            return profileFunction.getProfile()?.getProfileIsfMgdl() ?: 20.0
        }
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(8).msecs()
        dynIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()
        val autoSensOrDynIsfSensEnabled = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
        } else {
            preferences.get(BooleanKey.ApsUseAutosens)
        }

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }

    private val dynIsfCache = LongSparseArray<Double>()

    // Exemple de fonction pour prédire le delta futur à partir d'un historique récent
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }
    private fun estimateKalmanTrustFromDelta(delta: Double?): Double {
        val d = kotlin.math.abs(delta ?: 0.0)
        // 0..10 mg/dL/5min -> 0.1..0.9
        return (d / 10.0).coerceIn(0.1, 0.9)
    }

    // ISF basé TDD (ancre 1800/TDD 24h) avec garde-fous
    private fun tddIsf24hOr(profileIsf: Double): Double {
        val tdd24 = tddCalculator
            .averageTDD(tddCalculator.calculate(1, allowMissingDays = false))
            ?.data?.totalAmount
            ?: preferences.get(DoubleKey.OApsAIMITDD7) // fallback 7j
        val anchored = if (tdd24 > 0.1) 1800.0 / tdd24 else profileIsf
        return anchored.coerceIn(5.0, 400.0)
    }
    private fun dynamicDeltaCorrectionFactor(delta: Double?, predicted: Double?, bg: Double?): Double {
        if (delta == null || predicted == null || bg == null) return 1.0
        val combinedDelta = (delta + predicted) / 2.0
        return when {
            // En cas d'hypoglycémie (delta négatif), on augmente progressivement l'ISF
            combinedDelta < 0 -> {
                val factor = Math.exp(0.15 * Math.abs(combinedDelta))
                factor.coerceAtMost(1.4)
            }
            // En hyperglycémie : si BG est > 130, on applique une réduction progressive
            bg > 110.0        -> {
                // On réduit d’un certain pourcentage (ici jusqu’à 30%) en fonction de BG
                val bgReduction = 1.0 - ((bg - 110.0) / (200.0 - 110.0)) * 0.5
                // On combine ce facteur avec la réponse exponentielle basée sur combinedDelta si nécessaire
                if (combinedDelta > 10) {
                    // Si le delta est important, on accentue la réduction avec une réponse exponentielle
                    val expFactor = Math.exp(-0.3 * (combinedDelta - 10))
                    minOf(expFactor, bgReduction)
                } else {
                    bgReduction
                }
            }

            else              -> 1.0
        }
    }

    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(true) ?: return emptyList()
        val bg = smb.glucose
        val deltaNow = smb.delta
        if (data.isEmpty()) return emptyList()

        val standardWindow = if (bg < 130) 30f else 15f
        val rapidRiseWindow = 10f
        val intervalMinutes = if (deltaNow > 15) rapidRiseWindow else standardWindow

        val nowTs = data.first().timestamp
        val recent = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val r = data[i]
            if (r.value > 39 && !r.filledGap) {
                val minAgo = ((nowTs - r.timestamp) / 60000.0).toFloat()
                if (minAgo in 0.0f..intervalMinutes) {
                    val d = (data.first().recalculated - r.recalculated) / minAgo * 5f
                    recent.add(d)
                }
            }
        }
        return recent
    }
    @Synchronized
    @SuppressLint("DefaultLocale")
    private fun calculateVariableIsf(timestamp: Long, bg: Double?): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return "OFF" to null

        // 0) cache DB existant
        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null) return "DB" to result.variableSens

        // 1) BG & deltas actuels
        val glucose = bg ?: glucoseStatusProvider.glucoseStatusData?.glucose ?: return "GLUC" to null
        val currentDelta = glucoseStatusProvider.glucoseStatusData?.delta
        val recentDeltas = getRecentDeltas()
        val predictedDelta = predictedDelta(recentDeltas)

        // 2) facteur historique (comme avant)
        val dynamicFactor = dynamicDeltaCorrectionFactor(currentDelta, predictedDelta, bg)

        // 3) ISF rapide #1 : Kalman existant
        val kalmanFastIsf = kalmanISFCalculator.calculateISF(glucose, currentDelta, predictedDelta)
        aapsLogger.debug(LTag.APS, "Adaptive ISF via Kalman: $kalmanFastIsf for BG: $glucose")

        // 4) ISF lent (socle) : profil/TDD fusionné + pkpdScale (inchangé)
        val profileIsf = profileFunction.getProfile()?.getProfileIsfMgdl() ?: 20.0
        val tddIsf = tddIsf24hOr(profileIsf)
        val fusedSlowIsf = isfFusion().fused(profileIsf, tddIsf, lastPkpdScale)
        aapsLogger.debug(LTag.APS, "Fused slow ISF: $fusedSlowIsf (profile=$profileIsf, tddIsf=$tddIsf, pkpdScale=$lastPkpdScale)")

        // 5) EMA TDD (stabilise l’ajustement AF)
        val tdd24 = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: tddIsf /* fallback */
        tddEma = when (val prev = tddEma) {
            null -> tdd24
            else -> prev + TDD_EMA_ALPHA * (tdd24 - prev)
        }

        // 6) proxys de confiance (si variance non exposée ici)
        val kalmanTrustProxy = estimateKalmanTrustFromDelta(currentDelta)             // 0..1
        val kalmanVarProxy = (1.0 - kalmanTrustProxy).coerceIn(0.0, 1.0)             // 1-trust
        val sippConfidence = AimiUamHandler.confidenceOrZero().coerceIn(0.0, 1.0)

        // 7) ISF rapide #2 : IsfAdjustmentEngine (AF ln(BG/55) + TDD-EMA + rate-limit)
        val isfAdj = isfAdjEngine.compute(
            bgKalman = glucose,
            tddEma   = (tddEma ?: tdd24),
            profileIsf = profileIsf,
            sippConfidence = sippConfidence,
            kalmanVar = kalmanVarProxy,
            nowMs = System.currentTimeMillis()
        )
        aapsLogger.debug(LTag.APS, "Adaptive ISF via IsfAdjustmentEngine: $isfAdj (tddEma=$tddEma, sipp=$sippConfidence, var=$kalmanVarProxy)")

        // 8) Combine les deux rapides par médiane robuste (résistant aux outliers)
        val fastMedian = listOf(kalmanFastIsf, isfAdj).sorted()[1]

        // 9) Blend final (socle lent vs rapide), avec rate-limit temporel de IsfBlender
        var blended = isfBlender.blend(
            fusedIsf = fusedSlowIsf,
            kalmanIsf = fastMedian,
            trustFast = kalmanTrustProxy,
            nowMs = System.currentTimeMillis()
        )

        // 10) facteur dynamique + bornes globales
        blended *= dynamicFactor
        blended = blended.coerceIn(5.0, 300.0)

        aapsLogger.debug(LTag.APS, "Final DynISF: $blended")
        aapsLogger.debug(
            LTag.APS,
            "DynISF inputs: fusedSlowIsf=$fusedSlowIsf, kalmanFastIsf=$kalmanFastIsf, isfAdj=$isfAdj, trustFast=$kalmanTrustProxy, pkpdScale=$lastPkpdScale"
        )

        // 11) cache
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        if (dynIsfCache.size() > 1000) dynIsfCache.clear()
        dynIsfCache.put(key, blended)

        return "CALC" to blended
    }


    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = getGlucoseStatusData(false)
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump

        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAIMIPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data

        val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }
        val insulin = activePlugin.activeInsulin
        val insulinDivisor = when {
            insulin.peak > 65 -> 55 // rapid peak: 75
            insulin.peak > 50 -> 65 // ultra rapid peak: 55
            else              -> 45 // lyumjev peak: 45
        }

        var autosensResult = AutosensResult()
        val tddStatus: TddStatus?
        var variableSensitivity = 0.0
        var tdd = 0.0
        if (dynIsfMode) {
            val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
//
// // Plancher pour éviter des TDD trop faibles au démarrage
            val minTDD = 10.0
//
// Récupération et ajustement du TDD sur 7 jours
            var tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))
            if (tdd7D != null && tdd7D.data.totalAmount > tdd7P && tdd7D.data.totalAmount > 1.3 * tdd7P) {
                tdd7D.data.totalAmount = 1.2 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 7 days limited to 10% increase. New TDD7D: ${tdd7D.data.totalAmount}")
            }
            if (tdd7D != null && tdd7D.data.totalAmount < tdd7P * 0.9) {
                tdd7D.data.totalAmount = tdd7P * 0.9
                aapsLogger.info(LTag.APS, "TDD for 7 days was too low. Adjusted to 90% of TDD7P: ${tdd7D.data.totalAmount}")
            }

            // Calcul du TDD sur 2 jours
            var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            if (tdd2Days == 0.0 || tdd2Days < tdd7P) tdd2Days = tdd7P
//
            val tdd2DaysPerHour = tdd2Days / 24
            val tddLast4H = tdd2DaysPerHour * 4
//
// Calcul du TDD sur 1 jour avec une limite minimale pour éviter des instabilités
            var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            if (tddDaily == 0.0 || tddDaily < tdd7P / 2) tddDaily = maxOf(tdd7P, minTDD)

            if (tddDaily > tdd7P && tddDaily > 1.1 * tdd7P) {
                tddDaily = 1.1 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 1 day limited to 10% increase. New TDDDaily: $tddDaily")
            }
// // Calcul du TDD sur 24 heures
            var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 0.0
            if (tdd24Hrs == 0.0) tdd24Hrs = tdd7P
            val tdd24HrsPerHour = tdd24Hrs / 24
            val tddLast8to4H = tdd24HrsPerHour * 4
// // Calcul pondéré du TDD récent pour éviter les fluctuations extrêmes
            val tddWeightedFromLast8H = ((1.2 * tdd2DaysPerHour) + (0.3 * tddLast4H) + (0.5 * tddLast8to4H)) * 3
            var tdd = (tddWeightedFromLast8H * 0.20) + (tdd2Days * 0.50) + (tddDaily * 0.30)

            // On récupère la glycémie et le delta actuel
            val currentBG = glucoseStatusProvider.glucoseStatusData?.glucose
            if (currentBG == null) {
                aapsLogger.error(LTag.APS, "Données de glycémie indisponibles, impossibilité de calculer l'ISF adaptatif.")
                return
            }
            val currentDelta = glucoseStatusProvider.glucoseStatusData?.delta
            val recentDeltas = getRecentDeltas()
            val predictedDelta = predictedDelta(recentDeltas)

            // Calcul adaptatif de l'ISF via le filtre de Kalman
            var variableSensitivity = kalmanISFCalculator.calculateISF(currentBG, currentDelta, predictedDelta)
            aapsLogger.debug(LTag.APS, "Adaptive ISF computed: $variableSensitivity for BG: $currentBG, currentDelta: $currentDelta, predictedDelta: $predictedDelta")

            // Imposition des bornes pour que l'ISF soit toujours compris entre 5 et 300
            variableSensitivity = variableSensitivity.coerceIn(5.0, 300.0)
            aapsLogger.debug(LTag.APS, "Final adaptive ISF after clamping: $variableSensitivity")

// 🔹 Création du résultat final
            autosensResult = AutosensResult(
                ratio = tdd24Hrs / tdd2Days,
                ratioFromTdd = tdd24Hrs / tdd2Days,
                ratioFromCarbs = 1.0 // Peut être ajusté si nécessaire
            )

            val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            var currentActivity = 0.0
            for (i in -4..0) { //MP: -4 to 0 calculates all the insulin active during the last 5 minutes
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(i.toLong()), profile)
                currentActivity += iob.activity
            }
            var futureActivity = 0.0
            val activityPredTimePK = insulin.peak
            for (i in -4..0) { //MP: calculate 5-minute-insulin activity centering around peakTime
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityPredTimePK.toLong() - i), profile)
                futureActivity += iob.activity
            }
            val sensorLag = -10L //MP Assume that the glucose value measurement reflect the BG value from 'sensorlag' minutes ago & calculate the insulin activity then
            var sensorLagActivity = 0.0
            for (i in -4..0) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sensorLag - i), profile)
                sensorLagActivity += iob.activity
            }

            val activityHistoric = -20L //MP Activity at the time in minutes from now. Used to calculate activity in the past to use as target activity.
            var historicActivity = 0.0
            for (i in -2..2) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityHistoric - i), profile)
                historicActivity += iob.activity
            }
// Récupère GS standard + features AIMI
            val pack = glucoseStatusCalculatorAimi.compute(allowOldData = true)
            val gs = pack.gs ?: run {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
                aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
                return
            }
            val f = pack.features

// Construit l’objet attendu par determine_basal
            val glucoseStatusAimi = GlucoseStatusAIMI(
                glucose         = gs.glucose,
                noise           = gs.noise,
                delta           = gs.delta,
                shortAvgDelta   = gs.shortAvgDelta,
                longAvgDelta    = gs.longAvgDelta,
                date            = gs.date,

                // Champs AIMI disponibles
                duraISFminutes  = f?.stable5pctMinutes ?: 0.0,
                deltaPl         = f?.delta5Prev ?: 0.0,
                deltaPn         = f?.delta5Next ?: 0.0,
                bgAcceleration  = f?.accel ?: 0.0,
                corrSqu         = f?.corrR2 ?: 0.0,

                // Champs non exposés par AimiBgFeatures => valeurs neutres
                duraISFaverage  = 0.0,
                parabolaMinutes = 0.0,
                a0              = 0.0,
                a1              = 0.0,
                a2              = 0.0
            )
            futureActivity = Round.roundTo(futureActivity, 0.0001)
            sensorLagActivity = Round.roundTo(sensorLagActivity, 0.0001)
            historicActivity = Round.roundTo(historicActivity, 0.0001)
            currentActivity = Round.roundTo(currentActivity, 0.0001)
            // === PK/PD: calcule un pkpdScale cohérent et le mémorise ===
            // === PK/PD: calcule un pkpdScale cohérent et le mémorise (SANS dyn ISF) ===
            val nowMs = dateUtil.now()

// valeurs BG/delta déjà dispo dans invoke (glucoseStatus)
            val bgNow = glucoseStatus.glucose
            val deltaNow = glucoseStatus.delta

// IOB instantané
            val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMs, profile).iob

// Utilise le TDD 24h que tu as déjà calculé/chargé dans invoke (évite les IO coûteuses)
            val tdd24ForPk = tdd24Hrs  // garde ta variable existante ici (Double)

// IMPORTANT : passer un ISF "profil brut" pour éviter toute ré-entrée dans dynISF
            val profileIsfRaw = profile.getProfileIsfMgdl()   // mg/dL/U du profil, SANS dynamique

            val pkpdRuntimeNow = pkpdIntegration.computeRuntime(
                epochMillis = nowMs,
                bg = bgNow,
                deltaMgDlPer5 = deltaNow,
                iobU = iobNow,
                carbsActiveG = 0.0,          // branche tes carbs actifs réels si tu les as ici
                windowMin = 240,             // fenêtre standard (4h) – ajuste si besoin
                exerciseFlag = false,        // remplace par ton flag 'sportTime' si dispo ICI
                profileIsf = profileIsfRaw,  // ← **PROFIL BRUT, PAS getIsfMgdl()**
                tdd24h = tdd24ForPk
            )

            lastPkpdScale = pkpdRuntimeNow?.pkpdScale ?: 1.0
            aapsLogger.debug(LTag.APS, "PK/PD: pkpdScale=$lastPkpdScale (bg=$bgNow, delta=$deltaNow, iob=$iobNow, tdd24=$tdd24ForPk, isfRaw=$profileIsfRaw)")
            var tdd4D = tddCalculator.averageTDD(tddCalculator.calculate(4, allowMissingDays = false))
            val oapsProfile = OapsProfileAimi(
                dia = profile.dia,
                min_5m_carbimpact = 0.0, // not used
                max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
                max_daily_basal = profile.getMaxDailyBasal(),
                max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
                min_bg = minBg,
                max_bg = maxBg,
                target_bg = targetBg,
                carb_ratio = profile.getIc(),
                sens = profile.getIsfMgdl("OpenAPSAIMIPlugin"),
                autosens_adjust_targets = false, // not used
                max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
                current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
                lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
                high_temptarget_raises_sensitivity = false,
                low_temptarget_lowers_sensitivity = false,
                sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
                resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
                adv_target_adjustments = SMBDefaults.adv_target_adjustments,
                exercise_mode = SMBDefaults.exercise_mode,
                half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
                maxCOB = SMBDefaults.maxCOB,
                skip_neutral_temps = pump.setNeutralTempAtFullHour(),
                remainingCarbsCap = SMBDefaults.remainingCarbsCap,
                enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
                A52_risk_enable = SMBDefaults.A52_risk_enable,
                SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
                enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
                enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
                allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
                enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
                enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
                maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
                maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
                bolus_increment = pump.pumpDescription.bolusStep,
                carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
                current_basal = activePlugin.activePump.baseBasalRate,
                temptargetSet = isTempTarget,
                autosens_max = preferences.get(DoubleKey.AutosensMax),
                out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
                variable_sens = variableSensitivity!!,
                insulinDivisor = insulinDivisor,
                TDD = if (tdd4D == null) preferences.get(DoubleKey.OApsAIMITDD7) else tdd,
                peakTime = activityPredTimePK.toDouble(),
                futureActivity = futureActivity,
                sensorLagActivity = sensorLagActivity,
                historicActivity = historicActivity,
                currentActivity = currentActivity
            )

            val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
            val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

            aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AIMI <<<")
            aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
            aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
            aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
            aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
            aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
            aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
            aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
            aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
            aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

            determineBasalaimiSMB2.determine_basal(
                glucose_status = glucoseStatusAimi,
                currenttemp = currentTemp,
                iob_data_array = iobArray,
                profile = oapsProfile,
                autosens_data = autosensResult,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = now,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode,
                uiInteraction = uiInteraction
            ).also {
                val determineBasalResult = apsResultProvider.get().with(it)
                // Preserve input data
                determineBasalResult.inputConstraints = inputConstraints
                determineBasalResult.autosensResult = autosensResult
                determineBasalResult.iobData = iobArray
                determineBasalResult.glucoseStatus = glucoseStatus
                determineBasalResult.currentTemp = currentTemp
                determineBasalResult.oapsProfileAimi = oapsProfile
                determineBasalResult.mealData = mealData
                lastAPSResult = determineBasalResult
                lastAPSRun = now
                aapsLogger.debug(LTag.APS, "Result: $it")
                rxBus.send(EventAPSCalculationFinished())
            }

            rxBus.send(EventOpenAPSUpdateGui())
        }
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }
    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
    }

    override fun applyBasalConstraints(
        absoluteRate: Constraint<Double>,
        profile: Profile
    ): Constraint<Double> {
        // ────────────────────────────────────────────────────
        // 1️⃣ On détecte si l’on est en mode “meal” ou “early autodrive”
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        val isMealMode = therapy.snackTime
            || therapy.highCarbTime
            || therapy.mealTime
            || therapy.lunchTime
            || therapy.dinnerTime
            || therapy.bfastTime

        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(false) ?: return absoluteRate
        val feats = glucoseStatusCalculatorAimi.getAimiFeatures(false)
        val accel = feats?.accel ?: 0.0
        val isEarlyAutodrive = !night && !isMealMode && !therapy.sportTime &&
            smb.glucose > 110 &&
            detectMealOnset(
                smb.delta.toFloat(),
                predictedDelta(getRecentDeltas()).toFloat(),
                accel.toFloat()
            )

        val isSpecialMode = isMealMode || isEarlyAutodrive

        // ────────────────────────────────────────────────────
        // 2️⃣ On choisit la bonne pref en fonction du mode
        var maxBasal = when {
            isMealMode       -> preferences.get(DoubleKey.meal_modes_MaxBasal)
            isEarlyAutodrive -> preferences.get(DoubleKey.autodriveMaxBasal)
            else             -> preferences.get(DoubleKey.ApsMaxBasal)
        }

        if (isEnabled()) {
            // 3️⃣ On remonte au maxDailyBasal si besoin
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(
                    rh.gs(R.string.increasing_max_basal),
                    this
                )
            }

            // 4️⃣ On bride toujours sur maxBasal
            absoluteRate.setIfSmaller(
                maxBasal,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxBasal,
                    rh.gs(R.string.maxvalueinpreferences)
                ),
                this
            )

            // ───> **Si on est dans un mode spécial, on s’arrête là :**
            if (isSpecialMode) {
                return absoluteRate
            }

            // ────────────────────────────────────────────────────
            // 5️⃣ Sinon, on applique en plus le multiplicateur “current basal”
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxFromBasalMultiplier,
                    rh.gs(R.string.max_basal_multiplier)
                ),
                this
            )

            // 6️⃣ Et le multiplicateur “daily basal”
            val maxDailyMultiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxDailyMultiplier * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromDaily,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxFromDaily,
                    rh.gs(R.string.max_daily_basal_multiplier)
                ),
                this
            )
        }

        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "AIMI_Settings"
            title = rh.gs(R.string.aimi_preferences)
            initialExpandedChildrenCount = 0

            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "Global User Preferences"
                //title = "Global User Preferences"
                title = rh.gs(R.string.user_preferences)
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.user_preferences_title_menu)
                })
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMIMLtraining, title = R.string.oaps_aimi_enableMlTraining_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIweight, dialogMessage = R.string.oaps_aimi_weight_summary, title = R.string.oaps_aimi_weight_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMICHO, dialogMessage = R.string.oaps_aimi_cho_summary, title = R.string.oaps_aimi_cho_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMITDD7, dialogMessage = R.string.oaps_aimi_tdd7_summary, title = R.string.oaps_aimi_tdd7_title))
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "AIMI_PKPD"
                    title = rh.gs(R.string.oaps_aimi_pkpd_section_title)
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = BooleanKey.OApsAIMIPkpdEnabled,
                            summary = R.string.oaps_aimi_pkpd_enabled_summary,
                            title = R.string.oaps_aimi_pkpd_enabled_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdInitialDiaH,
                            dialogMessage = R.string.oaps_aimi_pkpd_initial_dia_summary,
                            title = R.string.oaps_aimi_pkpd_initial_dia_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdInitialPeakMin,
                            dialogMessage = R.string.oaps_aimi_pkpd_initial_peak_summary,
                            title = R.string.oaps_aimi_pkpd_initial_peak_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdBoundsDiaMinH,
                            dialogMessage = R.string.oaps_aimi_pkpd_dia_min_summary,
                            title = R.string.oaps_aimi_pkpd_dia_min_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdBoundsDiaMaxH,
                            dialogMessage = R.string.oaps_aimi_pkpd_dia_max_summary,
                            title = R.string.oaps_aimi_pkpd_dia_max_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdBoundsPeakMinMin,
                            dialogMessage = R.string.oaps_aimi_pkpd_peak_min_summary,
                            title = R.string.oaps_aimi_pkpd_peak_min_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdBoundsPeakMinMax,
                            dialogMessage = R.string.oaps_aimi_pkpd_peak_max_summary,
                            title = R.string.oaps_aimi_pkpd_peak_max_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH,
                            dialogMessage = R.string.oaps_aimi_pkpd_max_dia_delta_summary,
                            title = R.string.oaps_aimi_pkpd_max_dia_delta_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin,
                            dialogMessage = R.string.oaps_aimi_pkpd_max_peak_delta_summary,
                            title = R.string.oaps_aimi_pkpd_max_peak_delta_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMinFactor,
                            dialogMessage = R.string.oaps_aimi_isf_fusion_min_summary,
                            title = R.string.oaps_aimi_isf_fusion_min_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxFactor,
                            dialogMessage = R.string.oaps_aimi_isf_fusion_max_summary,
                            title = R.string.oaps_aimi_isf_fusion_max_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxChangePerTick,
                            dialogMessage = R.string.oaps_aimi_isf_fusion_slope_summary,
                            title = R.string.oaps_aimi_isf_fusion_slope_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMISmbTailThreshold,
                            dialogMessage = R.string.oaps_aimi_smb_tail_threshold_summary,
                            title = R.string.oaps_aimi_smb_tail_threshold_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMISmbTailDamping,
                            dialogMessage = R.string.oaps_aimi_smb_tail_damping_summary,
                            title = R.string.oaps_aimi_smb_tail_damping_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMISmbExerciseDamping,
                            dialogMessage = R.string.oaps_aimi_smb_exercise_damping_summary,
                            title = R.string.oaps_aimi_smb_exercise_damping_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMISmbLateFatDamping,
                            dialogMessage = R.string.oaps_aimi_smb_late_fat_damping_summary,
                            title = R.string.oaps_aimi_smb_late_fat_damping_title
                        )
                    )
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMIEnableStepsFromWatch, title = R.string.countsteps_watch_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsxdriponeminute, title = R.string.Enable_xdripOM_title))
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.user_modes_preferences_title_menu)
                })

                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "Women_Cycle"
                    title = rh.gs(R.string.wcycle_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.wcycle_preferences_title_menu)
                    })
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = BooleanKey.OApsAIMIwcycle,
                            title = R.string.wcycle_enable_title,
                            summary = R.string.wcycle_enable_summary
                        )
                    )
                    val trackingEntries = context.resources.getStringArray(R.array.wcycle_tracking_entries).map { it as CharSequence }.toTypedArray()
                    val trackingValues = context.resources.getStringArray(R.array.wcycle_tracking_values).map { it as CharSequence }.toTypedArray()
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.OApsAIMIWCycleTrackingMode,
                            title = R.string.wcycle_tracking_mode_title,
                            entries = trackingEntries,
                            entryValues = trackingValues
                        )
                    )
                    val contraceptiveEntries = context.resources.getStringArray(R.array.wcycle_contraceptive_entries).map { it as CharSequence }.toTypedArray()
                    val contraceptiveValues = context.resources.getStringArray(R.array.wcycle_contraceptive_values).map { it as CharSequence }.toTypedArray()
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.OApsAIMIWCycleContraceptive,
                            title = R.string.wcycle_contraceptive_title,
                            entries = contraceptiveEntries,
                            entryValues = contraceptiveValues
                        )
                    )
                    val thyroidEntries = context.resources.getStringArray(R.array.wcycle_thyroid_entries).map { it as CharSequence }.toTypedArray()
                    val thyroidValues = context.resources.getStringArray(R.array.wcycle_thyroid_values).map { it as CharSequence }.toTypedArray()
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.OApsAIMIWCycleThyroid,
                            title = R.string.wcycle_thyroid_title,
                            entries = thyroidEntries,
                            entryValues = thyroidValues
                        )
                    )
                    val verneuilEntries = context.resources.getStringArray(R.array.wcycle_verneuil_entries).map { it as CharSequence }.toTypedArray()
                    val verneuilValues = context.resources.getStringArray(R.array.wcycle_verneuil_values).map { it as CharSequence }.toTypedArray()
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.OApsAIMIWCycleVerneuil,
                            title = R.string.wcycle_verneuil_title,
                            entries = verneuilEntries,
                            entryValues = verneuilValues
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIwcycledateday,
                            dialogMessage = R.string.wcycle_start_dom_title,
                            title = R.string.wcycle_start_dom_title
                        )
                    )
                    addPreference(
                        AdaptiveIntPreference(
                            ctx = context,
                            intKey = IntKey.OApsAIMIWCycleAvgLength,
                            dialogMessage = R.string.wcycle_avg_len_title,
                            title = R.string.wcycle_avg_len_title
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = BooleanKey.OApsAIMIWCycleShadow,
                            title = R.string.wcycle_shadow_title
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = BooleanKey.OApsAIMIWCycleRequireConfirm,
                            title = R.string.wcycle_confirm_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIWCycleClampMin,
                            dialogMessage = R.string.wcycle_clamp_min_title,
                            title = R.string.wcycle_clamp_min_title
                        )
                    )
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.OApsAIMIWCycleClampMax,
                            dialogMessage = R.string.wcycle_clamp_max_title,
                            title = R.string.wcycle_clamp_max_title
                        )
                    )
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMIpregnancy, title = R.string.OApsAIMI_Enable_pregnancy))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMIhoneymoon, title = R.string.OApsAIMI_Enable_honeymoon))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMInight, title = R.string.OApsAIMI_Enable_night_title))
                
                // 🎯 Learners Section
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.oaps_aimi_learners_title)
                })
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = BooleanKey.OApsAIMIUnifiedReactivityEnabled,
                        title = R.string.unified_reactivity_title,
                        summary = R.string.unified_reactivity_summary
                    )
                )
                
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.oaps_aimi_ngr_title)
                })
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = BooleanKey.OApsAIMINightGrowthEnabled,
                        summary = R.string.oaps_aimi_ngr_enabled_summary,
                        title = R.string.oaps_aimi_ngr_enabled_title
                    )
                )
                addPreference(
                    AdaptiveIntPreference(
                        ctx = context,
                        intKey = IntKey.OApsAIMINightGrowthAgeYears,
                        dialogMessage = R.string.oaps_aimi_ngr_age_summary,
                        title = R.string.oaps_aimi_ngr_age_title
                    )
                )
                val hhmmValidator = DefaultEditTextValidator.Parameters(
                    testType = EditTextValidator.TEST_REGEXP,
                    customRegexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$"
                )
                addPreference(
                    AdaptiveStringPreference(
                        ctx = context,
                        stringKey = StringKey.OApsAIMINightGrowthStart,
                        dialogMessage = R.string.oaps_aimi_ngr_night_start_summary,
                        summary = R.string.oaps_aimi_ngr_night_start_summary,
                        title = R.string.oaps_aimi_ngr_night_start_title,
                        validatorParams = hhmmValidator
                    )
                )
                addPreference(
                    AdaptiveStringPreference(
                        ctx = context,
                        stringKey = StringKey.OApsAIMINightGrowthEnd,
                        dialogMessage = R.string.oaps_aimi_ngr_night_end_summary,
                        summary = R.string.oaps_aimi_ngr_night_end_summary,
                        title = R.string.oaps_aimi_ngr_night_end_title,
                        validatorParams = hhmmValidator
                    )
                )
                addPreference(
                    AdaptiveDoublePreference(
                        ctx = context,
                        doubleKey = DoubleKey.OApsAIMINightGrowthMaxIobExtra,
                        dialogMessage = R.string.oaps_aimi_ngr_max_iob_summary,
                        title = R.string.oaps_aimi_ngr_max_iob_title
                    )
                )

            })

            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "AIMI_Manual_Modes"
                title = rh.gs(R.string.training_ml_modes_preferences)
                
                // ❌ TIME-BASED REACTIVITY REMOVED (replaced by UnifiedReactivityLearner)
                // Previously: morning/afternoon/evening/hyper factors
                // Now: UnifiedReactivityLearner.globalFactor handles all reactivity adaptation
                
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.high_BG_preferences)
                })

            })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "high_BG_settings"
                //title = "High BG Preferences (BG > 120)"
                title = rh.gs(R.string.high_BG_preferences)
                addPreference(PreferenceCategory(context).apply {
                       title = rh.gs(R.string.bg_over_120_preferences_title_menu)
                })
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIHyperFactor, dialogMessage = R.string.oaps_aimi_hyper_factor_summary, title = R.string.oaps_aimi_hyper_factor_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIHighBGinterval, dialogMessage = R.string.oaps_aimi_HIGHBG_interval_summary, title = R.string.oaps_aimi_HIGHBG_interval_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIHighBGMaxSMB, dialogMessage = R.string.openapsaimi_highBG_maxsmb_summary, title = R.string.openapsaimi_highBG_maxsmb_title))
            })



            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "Training_ML_Modes"
                //title = "Training ML and Modes"
                title = rh.gs(R.string.training_ml_modes_preferences)
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.manual_modes_preferences_title_menu)
                })

                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.meal_modes_MaxBasal, dialogMessage = R.string.meal_modes_max_basal_summary, title = R.string.meal_modes_max_basal_title))

                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_Breakfast"
                    //title = "Breakfast Mode settings"
                    title = rh.gs(R.string.training_ml_breakfast_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.breakfast_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIBFPrebolus, dialogMessage = R.string.prebolus_BF_mode_summary, title = R.string.prebolus_BF_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIBFPrebolus2, dialogMessage = R.string.prebolus2_BF_mode_summary, title = R.string.prebolus2_BF_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIBFFactor, dialogMessage = R.string.OApsAIMI_BFFactor_summary, title = R.string.OApsAIMI_BFFactor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIBFinterval, dialogMessage = R.string.oaps_aimi_BF_interval_summary, title = R.string.oaps_aimi_BF_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_Lunch"
                    //title = "Lunch Mode settings"
                    title = rh.gs(R.string.training_ml_lunch_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.lunch_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMILunchPrebolus, dialogMessage = R.string.prebolus_lunch_mode_summary, title = R.string.prebolus_lunch_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMILunchPrebolus2, dialogMessage = R.string.prebolus2_lunch_mode_summary, title = R.string.prebolus2_lunch_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMILunchFactor, dialogMessage = R.string.OApsAIMI_LunchFactor_summary, title = R.string.OApsAIMI_lunchFactor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMILunchinterval, dialogMessage = R.string.oaps_aimi_lunch_interval_summary, title = R.string.oaps_aimi_lunch_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_dinner"
                    //title = "Dinner Mode settings"
                    title = rh.gs(R.string.training_ml_dinner_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.dinner_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIDinnerPrebolus, dialogMessage = R.string.prebolus_Dinner_mode_summary, title = R.string.prebolus_Dinner_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIDinnerPrebolus2, dialogMessage = R.string.prebolus2_Dinner_mode_summary, title = R.string.prebolus2_Dinner_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIDinnerFactor, dialogMessage = R.string.OApsAIMI_DinnerFactor_summary, title = R.string.OApsAIMI_DinnerFactor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIDinnerinterval, dialogMessage = R.string.oaps_aimi_Dinner_interval_summary, title = R.string.oaps_aimi_Dinner_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_highcarb"
                    //title = "High Carb Mode settings"
                    title = rh.gs(R.string.training_ml_high_carb_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.high_carb_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIHighCarbPrebolus, dialogMessage = R.string.prebolus_highcarb_mode_summary, title = R.string.prebolus_highcarb_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIHighCarbPrebolus2, dialogMessage = R.string.prebolus2_highcarb_mode_summary, title = R.string.prebolus2_highcarb_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIHCFactor, dialogMessage = R.string.OApsAIMI_HC_Factor_summary, title = R.string.OApsAIMI_HC_Factor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIHCinterval, dialogMessage = R.string.oaps_aimi_HC_interval_summary, title = R.string.oaps_aimi_HC_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_snack"
                    //title = "Snack Mode settings"
                    title = rh.gs(R.string.training_ml_snack_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.snack_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMISnackPrebolus, dialogMessage = R.string.prebolus_snack_mode_summary, title = R.string.prebolus_snack_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMISnackFactor, dialogMessage = R.string.OApsAIMI_snack_Factor_summary, title = R.string.OApsAIMI_snack_Factor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMISnackinterval, dialogMessage = R.string.oaps_aimi_snack_interval_summary, title = R.string.oaps_aimi_snack_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_meal"
                    //title = "Meal Mode settings"
                    title = rh.gs(R.string.training_ml_generic_meal_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.generic_meal_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIMealPrebolus, dialogMessage = R.string.prebolus_meal_mode_summary, title = R.string.prebolus_meal_mode_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIMealFactor, dialogMessage = R.string.OApsAIMI_MealFactor_summary, title = R.string.OApsAIMI_MealFactor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMImealinterval, dialogMessage = R.string.oaps_aimi_meal_interval_summary, title = R.string.oaps_aimi_meal_interval_title))
                })
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "mode_sleep"
                    //title = "Sleep Mode settings"
                    title = rh.gs(R.string.training_ml_sleep_modes_preferences)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.sleep_modes_preferences_title_menu)
                    })
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIsleepFactor, dialogMessage = R.string.OApsAIMI_sleep_Factor_summary, title = R.string.OApsAIMI_sleep_Factor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMISleepinterval, dialogMessage = R.string.oaps_aimi_sleep_interval_summary, title = R.string.oaps_aimi_sleep_interval_title))
                })
            })

            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "Autodrive"
                //title = "Autodrive settings"
                title = rh.gs(R.string.autodrive_preferences)
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.autodrive_preferences_title_menu)
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OApsAIMIautoDrive, title = R.string.oaps_aimi_enableMlautoDrive_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.autodriveMaxBasal, dialogMessage = R.string.autodrive_max_basal_summary, title = R.string.autodrive_max_basal_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIautodrivesmallPrebolus, dialogMessage = R.string.prebolussmall_autodrive_mode_summary, title = R.string.prebolussmall_autodrive_mode_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIautodrivePrebolus, dialogMessage = R.string.prebolus_autodrive_mode_summary, title = R.string.prebolus_autodrive_mode_title))
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "Autodrive prebolus variables"
                    //title = "Autodrive prebolus variables"
                    title = rh.gs(R.string.autodrive_prebolus_variables)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.autodrive_prebolus_title_menu)
                    })
                    //addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIAutodriveTarget, dialogMessage = R.string.oaps_aimi_AutodriveTarget_summary, title = R.string.oaps_aimi_AutodriveTarget_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.OApsAIMIAutodriveBG, dialogMessage = R.string.oaps_aimi_AutodriveBG_summary, title = R.string.oaps_aimi_AutodriveBG_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIcombinedDelta, dialogMessage = R.string.OApsAIMI_CombinedDelta_summary, title = R.string.OApsAIMI_CombinedDelta_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIAutodriveDeviation, dialogMessage = R.string.oaps_aimi_AutodriveDeviation_summary, title = R.string.oaps_aimi_AutodriveDeviation_title))
                    //addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.OApsAIMIAutodriveAcceleration, dialogMessage = R.string.oaps_aimi_AutodriveAcceleration_summary, title = R.string.oaps_aimi_AutodriveAcceleration_title))
                })
            })
            addPreference(PreferenceCategory(context).apply {
                title = rh.gs(R.string.aimi_preferences_title_menu)
            })
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "OAPS_SMB_Settings"
                title = rh.gs(R.string.AAPS_SMB_Settings)
                addPreference(PreferenceCategory(context).apply {
                    title = rh.gs(R.string.aaps_preferences_title_menu)
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseDynamicSensitivity, summary = R.string.use_dynamic_sensitivity_summary, title = R.string.use_dynamic_sensitivity_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfAdjustmentFactor, dialogMessage = R.string.dyn_isf_adjust_summary, title = R.string.dyn_isf_adjust_title))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfAdjustSensitivity, summary = R.string.dynisf_adjust_sensitivity_summary, title = R.string.dynisf_adjust_sensitivity))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob, summary = R.string.enable_smb_with_cob_summary, title = R.string.enable_smb_with_cob))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs, summary = R.string.enable_smb_after_carbs_summary, title = R.string.enable_smb_after_carbs))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "absorption_smb_advanced"
                    title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                    addPreference(PreferenceCategory(context).apply {
                        title = rh.gs(R.string.aaps_preferences_title_menu)
                    })
                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.openapsama_link_to_preference_json_doc)) },
                            summary = R.string.openapsama_link_to_preference_json_doc_txt
                        )
                    )
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier, dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary, title = R.string.openapsama_current_basal_safety_multiplier)
                    )
                })
            })
        }
    }
}
