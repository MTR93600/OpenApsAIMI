package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.UE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import org.tensorflow.lite.Interpreter
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import app.aaps.plugins.aps.R

import android.content.Context

@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val context: Context
) {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val modelFile = File(externalDir, "ml/model.tflite")
    private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private val csvfile = File(externalDir, "oapsaimiML2_records.csv")
    private val csvfile2 = File(externalDir, "oapsaimi2_records.csv")
    //private val tempFile = File(externalDir, "temp.csv")
    private var bgacc = 0.0
    private var predictedSMB = 0.0f
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute10 = 0.0
    private var averageBeatsPerMinute60 = 0.0
    private var averageBeatsPerMinute180 = 0.0
    private var eventualBG = 0.0
    private var now = System.currentTimeMillis()
    private var iob = 0.0f
    private var cob = 0.0f
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 90.0f
    private var normalBgThreshold = 110.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0
    private var maxSMB = 1.0
    private var maxSMBHB = 1.0
    private var lastBolusSMBUnit = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var aimilimit = 0.0f
    private var ci = 0.0f
    private var sleepTime = false
    private var sportTime = false
    private var snackTime = false
    private var lowCarbTime = false
    private var highCarbTime = false
    private var mealTime = false
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var fastingTime = false
    private var stopTime = false
    private var iscalibration = false
    private var mealruntime: Long = 0
    private var bfastruntime: Long = 0
    private var lunchruntime: Long = 0
    private var dinnerruntime: Long = 0
    private var highCarbrunTime: Long = 0
    private var snackrunTime: Long = 0
    private var intervalsmb = 1
    private var peakintermediaire = 0.0
    private var insulinPeakTime = 0.0
    private var zeroBasalAccumulatedMinutes: Int = 0
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))
    /**
     * Prédit l’évolution de la glycémie sur un horizon donné (en minutes),
     * avec des pas de 5 minutes.
     *
     * @param currentBG La glycémie actuelle (mg/dL)
     * @param basalCandidate La dose basale candidate (en U/h)
     * @param horizonMinutes L’horizon de prédiction (ex. 30 minutes)
     * @param insulinSensitivity La sensibilité insulinique (mg/dL/U)
     * @return Une liste de glycémies prédites pour chaque pas de 5 minutes.
     */
    private fun predictGlycemia(currentBG: Double, basalCandidate: Double, horizonMinutes: Int, insulinSensitivity: Double): List<Double> {
        val predictions = mutableListOf<Double>()
        var bgSimulated = currentBG
        // Supposons un pas de 5 minutes, et une action linéaire de l'insuline
        val steps = horizonMinutes / 5
        for (i in 1..steps) {
            // Par exemple, on soustrait une quantité proportionnelle à la dose basale et à la sensibilité
            bgSimulated -= insulinSensitivity * basalCandidate * (5.0 / 60.0)
            predictions.add(bgSimulated)
        }
        return predictions
    }
    /**
     * Calcule la fonction de coût, ici la somme des carrés des écarts entre les glycémies prédites et la glycémie cible.
     *
     * @param basalCandidate La dose candidate de basal.
     * @param currentBG La glycémie actuelle.
     * @param targetBG La glycémie cible.
     * @param horizonMinutes L’horizon de prédiction (en minutes).
     * @param insulinSensitivity La sensibilité insulinique.
     * @return Le coût cumulé.
     */
    fun costFunction(
        basalCandidate: Double, currentBG: Double,
        targetBG: Double, horizonMinutes: Int,
        insulinSensitivity: Double, nnPrediction: Double
    ): Double {
        val predictions = predictGlycemia(currentBG, basalCandidate, horizonMinutes, insulinSensitivity)
        val predictionCost = predictions.sumOf { (it - targetBG).pow(2) }
        val nnPenalty = (basalCandidate - nnPrediction).pow(2)
        return predictionCost + 0.5 * nnPenalty  // Pondération du terme de pénalité
    }


    private fun roundBasal(value: Double): Double = value
    private fun getZeroBasalDuration(persistenceLayer: PersistenceLayer, lookBackHours: Int): Int {
        val now = System.currentTimeMillis()
        val fromTime = now - lookBackHours * 60 * 60 * 1000L

        // Récupère les basales temporaires triées par timestamp décroissant
        val tempBasals: List<TB> = persistenceLayer
            .getTemporaryBasalsStartingFromTime(fromTime, ascending = false)
            .blockingGet()

        if (tempBasals.isEmpty()) {
            return 0 // Aucune donnée disponible pendant la période de recherche
        }

        var lastZeroTimestamp = fromTime // Initialiser avec le timestamp de base

        for (event in tempBasals) {
            if (event.rate > 0.05) break
            lastZeroTimestamp = event.timestamp
        }

        // Si aucun événement n'a un taux > 0.05, alors on considère la durée depuis le début de la période
        val zeroDuration = if (lastZeroTimestamp == fromTime) {
            now - fromTime
        } else {
            now - lastZeroTimestamp
        }

        return (zeroDuration / 60000L).toInt()
    }
    // -- Classe représentant la décision de sécurité --
    data class SafetyDecision(
        val stopBasal: Boolean,      // true => arrête la basale (ou force une basale à 0)
        val bolusFactor: Double,     // Facteur multiplicateur appliqué à la dose SMB (1.0 = dose complète, 0.0 = annulation)
        val reason: String,           // Log résumant les critères ayant conduit à la décision
        val basalLS: Boolean
    )

    // -- Calcul de la chute de BG par heure sur une fenêtre donnée (en minutes) --
    fun calculateDropPerHour(bgHistory: List<Float>, windowMinutes: Float): Float {
        if (bgHistory.isEmpty()) return 0f
        val drop = bgHistory.last() - bgHistory.first()  // positif si baisse
        return drop * (60f / windowMinutes)
    }

    // -- Fonction de sécurité qui combine plusieurs indicateurs pour ajuster la dose d'insuline --
    fun safetyAdjustment(
        currentBG: Float,
        predictedBG: Float,
        bgHistory: List<Float>,
        combinedDelta: Float,
        iob: Float,
        maxIob: Float,
        tdd24Hrs: Float,
        tddPerHour: Float,
        tirInhypo: Float,
        targetBG: Float,
        zeroBasalDurationMinutes: Int  // Nouvel argument indiquant combien de minutes consécutives la basale est restée à 0
    ): SafetyDecision {
        val windowMinutes = 30f
        val dropPerHour = calculateDropPerHour(bgHistory, windowMinutes)
        val maxAllowedDropPerHour = 25f  // Ajustez si besoin
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val reasonBuilder = StringBuilder()
        var stopBasal = false
        var basalLS = false
        var bolusFactor = 1.0

        // 1. Contrôle de la chute
        if (dropPerHour >= maxAllowedDropPerHour) {
            // Option A : on arrête complètement la basale
            stopBasal = true
            // reasonBuilder.append("BG drop élevé: $dropPerHour mg/dL/h; ")

            // Option B : on réduit fortement le bolusFactor sans stopper la basale
            bolusFactor *= 0.3
            //reasonBuilder.append("BG drop élevé ($dropPerHour mg/dL/h), forte réduction du bolus; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_1,dropPerHour))

        }
        if (delta >= 20f && combinedDelta >= 15f && !honeymoon) {
            // Mode "montée rapide" détecté, on override les réductions habituelles
            bolusFactor = 1.0
            //reasonBuilder.append("Montée rapide détectée (delta ${delta} mg/dL), application du mode d'urgence; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_2,delta))

        }
        // 2. Palier sur le combinedDelta
        when {
            combinedDelta < 1f -> {
                bolusFactor *= 0.6
                //reasonBuilder.append("combinedDelta très faible ($combinedDelta), réduction x0.6; ")
                reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_3,combinedDelta))
            }
            combinedDelta < 2f -> {
                bolusFactor *= 0.8
                //reasonBuilder.append("combinedDelta modéré ($combinedDelta), réduction x0.8; ")
                reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_4,combinedDelta))
            }
            else -> {
                bolusFactor *= computeDynamicBolusMultiplier(combinedDelta)
                //reasonBuilder.append("combinedDelta élevé ($combinedDelta), pas de réduction; ")
                reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_5,combinedDelta))
            }
        }

        // 3. Plateau si BG élevé + combinedDelta très faible
        if (currentBG > 160f && combinedDelta < 1f) {
            bolusFactor *= 0.8
            //reasonBuilder.append("Plateau BG>180 & combinedDelta<2 => réduction x0.8; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_6))
        }

        // 4. Contrôle IOB
        if (iob >= maxIob * 0.85f) {
            bolusFactor *= 0.85
            //reasonBuilder.append("IOB élevé ($iob U), réduction x0.8; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_7,iob))
        }

        // 5. Contrôle du TDD par heure
        val tddThreshold = tdd24Hrs / 24f
        if (tddPerHour > tddThreshold) {
            bolusFactor *= 0.8
            //reasonBuilder.append("TDD/h élevé ($tddPerHour U/h), réduction x0.8; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_8,tddPerHour))
        }

        // 6. TIR élevé
        if (tirInhypo >= 8f) {
            bolusFactor *= 0.5
            //reasonBuilder.append("TIR élevé ($tirInhypo%), réduction x0.5; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_9,tirInhypo))
        }

        // 7. BG prédit proche de la cible
        if (predictedBG < targetBG + 10) {
            bolusFactor *= 0.5
            //reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_10,predictedBG,targetBG))
        }
        // ---- Intégration du suivi de durée zéro basal ----
        // Si nous avons déjà trop longtemps de basal à 0, on ne souhaite pas stopper la basale.
        // Par exemple, si la durée cumulée dépasse 60 minutes, on force l'arrêt de la réduction (i.e. on ne stoppe pas la basale)
        if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION) {
            // On annule la demande de stopper la basale et on force le bolusFactor à 1 (aucune réduction)
            stopBasal = false
            basalLS = true
            bolusFactor = 1.0
            //reasonBuilder.append("Zero basal duration ($zeroBasalDurationMinutes min) dépassé, forçant basal minimal; ")
            reasonBuilder.appendLine(context.getString(R.string.safety_adjustments_11,zeroBasalDurationMinutes))
        }

        return SafetyDecision(
            stopBasal = stopBasal,
            bolusFactor = bolusFactor,
            reason = reasonBuilder.toString(),
            basalLS = basalLS
        )
    }
    /**
     * Ajuste le DIA (en minutes) en fonction du niveau d'IOB.
     *
     * @param diaMinutes Le DIA courant (en minutes) après les autres ajustements.
     * @param currentIOB La quantité actuelle d'insuline active (U).
     * @param threshold Le seuil d'IOB à partir duquel on commence à augmenter le DIA (par défaut 7 U).
     * @return Le DIA ajusté en minutes tenant compte de l'impact de l'IOB.
     */
    fun adjustDIAForIOB(diaMinutes: Float, currentIOB: Float, threshold: Float = 5f): Float {
        // Si l'IOB est inférieur ou égal au seuil, pas d'ajustement.
        if (currentIOB <= threshold) return diaMinutes

        // Calculer l'excès d'IOB
        val excess = currentIOB - threshold
        // Pour chaque unité au-dessus du seuil, augmenter le DIA de 5 %.
        val multiplier = 1 + 0.05f * excess
        return diaMinutes * multiplier
    }
    /**
     * Calcule le DIA ajusté en minutes en fonction de plusieurs paramètres :
     * - baseDIAHours : le DIA de base en heures (par exemple, 9.0 pour 9 heures)
     * - currentHour : l'heure actuelle (0 à 23)
     * - recentSteps5Minutes : nombre de pas sur les 5 dernières minutes
     * - currentHR : fréquence cardiaque actuelle (bpm)
     * - averageHR60 : fréquence cardiaque moyenne sur les 60 dernières minutes (bpm)
     *
     * La logique appliquée :
     * 1. Conversion du DIA de base en minutes.
     * 2. Ajustement selon l'heure de la journée :
     *    - Matin (6-10h) : réduction de 20% (×0.8),
     *    - Soir/Nuit (22-23h et 0-5h) : augmentation de 20% (×1.2).
     * 3. Ajustement en fonction de l'activité physique :
     *    - Si recentSteps5Minutes > 200 et que currentHR > averageHR60, on réduit le DIA de 30% (×0.7).
     *    - Si recentSteps5Minutes == 0 et que currentHR > averageHR60, on augmente le DIA de 30% (×1.3).
     * 4. Ajustement selon la fréquence cardiaque absolue :
     *    - Si currentHR > 130 bpm, on réduit le DIA de 30% (×0.7).
     * 5. Le résultat final est contraint entre 180 minutes (3h) et 720 minutes (12h).
     */
    fun calculateAdjustedDIA(
        baseDIAHours: Float,
        currentHour: Int,
        recentSteps5Minutes: Int,
        currentHR: Float,
        averageHR60: Float,
        pumpAgeDays: Float
    ): Double {
        // 1. Conversion du DIA de base en minutes
        var diaMinutes = baseDIAHours * 60f  // Pour 9h, 9*60 = 540 min

        // 2. Ajustement selon l'heure de la journée
        if (currentHour in 6..10) {
            // Le matin : absorption plus rapide, on réduit le DIA de 20%
            diaMinutes *= 0.8f
        } else if (currentHour in 22..23 || currentHour in 0..5) {
            // Soir/Nuit : absorption plus lente, on augmente le DIA de 20%
            diaMinutes *= 1.2f
        }

        // 3. Ajustement en fonction de l'activité physique
        if (recentSteps5Minutes > 200 && currentHR > averageHR60) {
            // Exercice : absorption accélérée, réduire le DIA de 30%
            diaMinutes *= 0.7f
        } else if (recentSteps5Minutes == 0 && currentHR > averageHR60) {
            // Aucune activité mais HR élevée (stress) : absorption potentiellement plus lente, augmenter le DIA de 30%
            diaMinutes *= 1.3f
        }

        // 4. Ajustement en fonction du niveau absolu de fréquence cardiaque
        if (currentHR > 130f) {
            // HR très élevée : circulation rapide, réduire le DIA de 30%
            diaMinutes *= 0.7f
        }

        // 5. Ajustement en fonction de l'IOB
        diaMinutes = adjustDIAForIOB(diaMinutes, iob)
        // Si le site est utilisé depuis 2 jours ou plus, augmenter le DIA de 10% par jour supplémentaire.
        if (pumpAgeDays >= 2f) {
            val extraDays = pumpAgeDays - 2f
            val ageMultiplier = 1 + 0.2f * extraDays  // par exemple, 2 jours => 1 + 0.2*1 = 1.2
            diaMinutes *= ageMultiplier
        }

        // 6. Contrainte de la plage finale : entre 180 min (3h) et 720 min (12h)
        diaMinutes = diaMinutes.coerceIn(180f, 720f)

        return diaMinutes.toDouble()
    }

    // -- Méthode pour obtenir l'historique récent de BG, similaire à getRecentBGs() --
    private fun getRecentBGs(): List<Float> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        val intervalMinutes = if (bg < 130) 60f else 30f
        val nowTimestamp = data.first().timestamp
        val recentBGs = mutableListOf<Float>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..intervalMinutes) {
                    // Utilisation de la valeur recalculée comme BG
                    recentBGs.add(data[i].recalculated.toFloat())
                }
            }
        }
        return recentBGs
    }

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }
    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        return if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
    }
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double =
        if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)

    private fun convertBG(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun enablesmb(profile: OapsProfileAimi, microBolusAllowed: Boolean, mealData: MealData, targetbg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && targetbg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $targetbg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${mealData.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && targetbg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convertBG(targetbg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    // private fun getMaxSafeBasal(profile: OapsProfileAimi): Double =
    //     min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))
    //  /**
    //  * Pose un temp basal en respectant — ou non — la limite de sécurité.
    //  *
    //  * @param _rate Taux souhaité (U/h).
    //  * @param duration Durée du temp basal (en minutes).
    //  * @param profile Profil utilisateur contenant les paramètres de sécurité.
    //  * @param rT Objet RT pour accumuler la raison.
    //  * @param currenttemp Temp actuel pour comparaison.
    //  * @param overrideSafetyLimits Si vrai, bypass de la limite maxSafeBasal.
    //  */
    // fun setTempBasal(
    //     _rate: Double,
    //     duration: Int,
    //     profile: OapsProfileAimi,
    //     rT: RT,
    //     currenttemp: CurrentTemp,
    //     overrideSafetyLimits: Boolean = false
    // ): RT {
    //     // Calcul du maxSafeBasal habituel
    //     val maxSafe = min(
    //         profile.max_basal,
    //         min(
    //             profile.max_daily_safety_multiplier * profile.max_daily_basal,
    //             profile.current_basal_safety_multiplier * profile.current_basal
    //         )
    //     )
    //     // Choix du taux à appliquer :
    //     // - si overrideSafetyLimits, on ne clamp pas à maxSafe
    //     // - sinon on clamp le rate à maxSafe
    //     val rate = if (overrideSafetyLimits) {
    //         _rate
    //     } else {
    //         _rate.coerceIn(0.0, maxSafe)
    //     }
    //
    //     // Log pour debug
    //     if (overrideSafetyLimits) {
    //         rT.reason.append("→ bypass sécurité (override) : rate = ${rate}U/h\n")
    //     } else if (rate != _rate) {
    //         rT.reason.append("→ clamped à maxSafeBasal $maxSafe U/h (demande: ${_rate}U/h)\n")
    //     }
    //
    //     // Cas où il n’y a pas de changement utile
    //     if (currenttemp.duration > (duration - 10)
    //         && currenttemp.duration <= 120
    //         && rate <= currenttemp.rate * 1.2
    //         && rate >= currenttemp.rate * 0.8
    //         && duration > 0
    //     ) {
    //         rT.reason.append("${currenttemp.duration}m restants & ${currenttemp.rate} ~ req $rate U/h : pas de temp.\n")
    //         return rT
    //     }
    //
    //     // Si le nouveau rate = basal de profil et qu’on skip les neutres
    //     val profileBasal = profile.current_basal
    //     if (rate == profileBasal) {
    //         if (profile.skip_neutral_temps) {
    //             if (currenttemp.duration > 0) {
    //                 rT.reason.append("Taux neutre = profil & temp actif → annulation du temp.\n")
    //                 rT.duration = 0
    //                 rT.rate = 0.0
    //             } else {
    //                 rT.reason.append("Taux neutre = profil & pas de temp → rien à faire.\n")
    //             }
    //         } else {
    //             rT.reason.append("Taux neutre = profil → pose d’un temp à $rate U/h.\n")
    //             rT.duration = duration
    //             rT.rate = rate
    //         }
    //     } else {
    //         // Pose standard du temp
    //         rT.reason.append("Pose temp à $rate U/h pour $duration minutes.\n")
    //         rT.duration = duration
    //         rT.rate = rate
    //     }
    //
    //     return rT
    // }
    /**
     * Pose un temp basal en respectant — ou non — la limite de sécurité.
     *
     * @param _rate Taux souhaité (U/h).
     * @param duration Durée du temp basal (en minutes).
     * @param profile Profil utilisateur contenant les paramètres de sécurité.
     * @param rT Objet RT pour accumuler la raison.
     * @param currenttemp Temp actuel pour comparaison.
     * @param overrideSafetyLimits Si vrai, bypass explicite de la limite maxSafeBasal.
     */
    fun setTempBasal(
        _rate: Double,
        duration: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean = false
    ): RT {
        // ────────────────────────────────────────────────────────────────
        // 1️⃣ On recalcule le mode “meal” / “highCarb” / “snack” / etc.
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        val isMealMode = therapy.snackTime
            || therapy.highCarbTime
            || therapy.mealTime
            || therapy.lunchTime
            || therapy.dinnerTime
            || therapy.bfastTime

        // 2️⃣ On recalcule le mode “early autodrive”
        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7
        val predDelta = predictedDelta(getRecentDeltas()).toFloat()
        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)

        val isEarlyAutodrive = !night
            && !isMealMode
            && autodrive
            && bg > 110
            && detectMealOnset(delta, predDelta, bgacc.toFloat())

        // 3️⃣ On décide de bypasser la limite de sécurité si override ou mode spécial
        val bypassSafety = overrideSafetyLimits || isMealMode || isEarlyAutodrive

        // ────────────────────────────────────────────────────────────────
        // 4️⃣ Calcul du maxSafeBasal standard
        val maxSafe = min(
            profile.max_basal,
            min(
                profile.max_daily_safety_multiplier * profile.max_daily_basal,
                profile.current_basal_safety_multiplier * profile.current_basal
            )
        )

        // 5️⃣ Choix du rate effectif : bypass ou clamp
        val rate = if (bypassSafety) {
            _rate
        } else {
            _rate.coerceIn(0.0, maxSafe)
        }

        // ────────────────────────────────────────────────────────────────
        // 6️⃣ Logging des raisons
        when {
            bypassSafety -> {
                //rT.reason.append("→ bypass sécurité${if (isMealMode) " (meal mode)" else if (isEarlyAutodrive) " (early autodrive)" else ""}: rate = ${rate} U/h\n")
                val suffix = when {
                    isMealMode -> context.getString(R.string.meal_mode_suffix)
                    isEarlyAutodrive -> context.getString(R.string.early_autodrive_suffix)
                    else -> ""
                }

                rT.reason.appendLine(context.getString(R.string.safety_adjustments_12, suffix, rate))
            }
            rate != _rate -> {

                //rT.reason.append("→ clamped à maxSafeBasal ${"%.2f".format(maxSafe)} U/h (demandé: ${"%.2f".format(_rate)} U/h)\n")
                val formattedMaxSafe = "%.2f".format(maxSafe)
                val formattedRate = "%.2f".format(_rate)

                rT.reason.appendLine(context.getString(R.string.safety_adjustments_13, formattedMaxSafe, formattedRate))
            }
        }

        // ────────────────────────────────────────────────────────────────
        // 7️⃣ Si pas de changement utile, on sort
        if (currenttemp.duration > (duration - 10)
            && currenttemp.duration <= 120
            && rate <= currenttemp.rate * 1.2
            && rate >= currenttemp.rate * 0.8
            && duration > 0
        ) {
            //rT.reason.append("${currenttemp.duration}m restants & ${currenttemp.rate} ~ req $rate U/h : pas de temp.\n")
            val formattedDuration = "${currenttemp.duration}m"
            val formattedRate = "%.2f".format(currenttemp.rate)
            val formattedRequestedRate = "%.2f".format(rate)

            rT.reason.appendLine(context.getString(R.string.safety_adjustments_14, formattedDuration, formattedRate, formattedRequestedRate))
            return rT
        }

        // ────────────────────────────────────────────────────────────────
        // 8️⃣ Gestion du “neutral temp” = profil.current_basal
        val profileBasal = profile.current_basal
        if (rate == profileBasal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    //rT.reason.append("Taux neutre = profil & temp actif → annulation du temp.\n")
                    rT.reason.appendLine(context.getString(R.string.basal_arguments_1))
                    rT.duration = 0
                    rT.rate = 0.0
                } else {
                    //rT.reason.append("Taux neutre = profil & pas de temp → rien à faire.\n")
                    rT.reason.appendLine(context.getString(R.string.basal_arguments_2))
                }
            } else {
                //rT.reason.append("Taux neutre = profil → pose d’un temp à $rate U/h.\n")
                rT.reason.appendLine(context.getString(R.string.basal_arguments_3, rate))
                rT.duration = duration
                rT.rate = rate
            }
            return rT
        }

        // ────────────────────────────────────────────────────────────────
        // 9️⃣ Pose “standard” du temp basal
        //rT.reason.append("Pose temp à ${"%.2f".format(rate)} U/h pour $duration minutes.\n")
        rT.reason.appendLine(context.getString(R.string.basal_arguments_4, rate,duration))
        rT.duration = duration
        rT.rate = rate
        return rT
    }


    // fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfileAimi, rT: RT, currenttemp: CurrentTemp, overrideSafetyLimits: Boolean = false): RT {
    //     val maxSafeBasal = getMaxSafeBasal(profile)
    //     var rate = _rate
    //     if (rate < 0) rate = 0.0
    //     //else if (rate > maxSafeBasal && !mealR) rate = maxSafeBasal
    //     else if (rate > maxSafeBasal && !overrideSafetyLimits  &&
    //         !mealTime && !bfastTime && !lunchTime && !dinnerTime && !snackTime && !highCarbTime) {
    //         rate = maxSafeBasal
    //     }
    //
    //     val suggestedRate = roundBasal(rate)
    //
    //     if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 &&
    //         suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 &&
    //         duration > 0) {
    //         rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
    //     } else if (suggestedRate == profile.current_basal) {
    //         if (profile.skip_neutral_temps) {
    //             if (currenttemp.duration > 0) {
    //                 reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
    //                 rT.duration = 0
    //                 rT.rate = 0.0
    //             } else {
    //                 reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
    //             }
    //         } else {
    //             reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
    //             rT.duration = duration
    //             rT.rate = suggestedRate
    //         }
    //     } else {
    //         rT.duration = duration
    //         rT.rate = suggestedRate
    //     }
    //     return rT
    // }

    private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {
        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, predictedSMB, smbGiven\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$predictedSMB,$smbToGive"


        if (!csvfile.exists()) {
            csvfile.parentFile?.mkdirs()
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")
    }
    // private fun createFilteredAndSortedCopy(dateToRemove: String) {
    //     if (!csvfile.exists()) {
    //         println("Le fichier original n'existe pas.")
    //         return
    //     }
    //
    //     try {
    //         // Lire le fichier original ligne par ligne
    //         val lines = csvfile.readLines()
    //         val header = lines.firstOrNull() ?: return
    //         val dataLines = lines.drop(1)
    //
    //         // Liste des lignes valides après filtrage
    //         val validLines = mutableListOf<String>()
    //
    //         // Filtrer les lignes qui ne correspondent pas à la date à supprimer
    //         dataLines.forEach { line ->
    //             val lineParts = line.split(",")
    //             if (lineParts.isNotEmpty()) {
    //                 val dateStr = lineParts[0].trim()
    //                 if (!dateStr.startsWith(dateToRemove)) {
    //                     validLines.add(line)
    //                 } else {
    //                     println("Ligne supprimée : $line")
    //                 }
    //             }
    //         }
    //
    //         // Trier les lignes par ordre croissant de date (en utilisant les dates en texte)
    //         validLines.sortBy { it.split(",")[0] }
    //
    //         if (!tempFile.exists()) {
    //             tempFile.createNewFile()
    //         }
    //
    //         // Écrire les lignes filtrées et triées dans le fichier temporaire
    //         tempFile.writeText(header + "\n")
    //         validLines.forEach { line ->
    //             tempFile.appendText(line + "\n")
    //         }
    //
    //         // Obtenir la date et l'heure actuelles pour renommer le fichier original
    //         val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
    //         val currentDateTime = dateFormat.format(Date())
    //         val backupFileName = "oapsaimiML2_records_$currentDateTime.csv"
    //         val backupFile = File(externalDir, backupFileName)
    //
    //         // Renommer le fichier original en fichier de sauvegarde
    //         if (csvfile.renameTo(backupFile)) {
    //             // Renommer le fichier temporaire en fichier principal
    //             if (tempFile.renameTo(csvfile)) {
    //                 println("Le fichier original a été sauvegardé sous '$backupFileName', et 'temp.csv' a été renommé en 'oapsaimiML2_records.csv'.")
    //             } else {
    //                 println("Erreur lors du renommage du fichier temporaire 'temp.csv' en 'oapsaimiML2_records.csv'.")
    //             }
    //         } else {
    //             println("Erreur lors du renommage du fichier original en '$backupFileName'.")
    //         }
    //
    //     } catch (e: Exception) {
    //         println("Erreur lors de la gestion des fichiers : ${e.message}")
    //     }
    // }
    // fun createFilteredAndSortedCopy(csvFile: File, dateToRemove: String) {
    //     // Vérifier que le fichier CSV existe
    //     if (!csvFile.exists()) {
    //         println("Le fichier original n'existe pas.")
    //         return
    //     }
    //
    //     // Tenter de parser la date cible (attendue au format "dd/MM/yyyy")
    //     val targetDate: Date? = try {
    //         SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(dateToRemove)
    //     } catch (e: Exception) {
    //         println("Erreur de parsing de la date cible : ${e.message}")
    //         null
    //     }
    //     if (targetDate == null) {
    //         println("La date cible est invalide.")
    //         return
    //     }
    //
    //     // Normaliser la date cible dans un format standard (ici "yyyyMMdd")
    //     val normalizedTarget = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(targetDate)
    //
    //     // Liste des formats possibles présents dans le CSV pour la première colonne
    //     val dateFormats = listOf(
    //         SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()),
    //         SimpleDateFormat("d/M/yy HH:mm", Locale.getDefault()),
    //         SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault())
    //     )
    //
    //     // Lecture de toutes les lignes du fichier en mémoire (UTF-8)
    //     val lines = csvFile.readLines(Charsets.UTF_8)
    //     if (lines.isEmpty()) {
    //         println("Le fichier CSV est vide.")
    //         return
    //     }
    //
    //     // La première ligne est l'en-tête
    //     val header = lines.first()
    //     val filteredLines = mutableListOf<String>()
    //
    //     // Traiter chaque ligne (à partir de la deuxième)
    //     for (line in lines.drop(1)) {
    //         val parts = line.split(",")
    //         if (parts.isNotEmpty()) {
    //             val rawDateStr = parts[0].trim() // Par exemple "01/01/2025 00:18" ou "4/3/25 00:44"
    //             var parsedDate: Date? = null
    //             // Essayer de parser la date avec chacun des formats disponibles
    //             for (format in dateFormats) {
    //                 try {
    //                     parsedDate = format.parse(rawDateStr)
    //                     if (parsedDate != null) break
    //                 } catch (e: Exception) {
    //                     // En cas d'erreur, on continue avec le format suivant
    //                 }
    //             }
    //             if (parsedDate != null) {
    //                 // Normaliser la date de la ligne
    //                 val normalizedLineDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(parsedDate)
    //                 if (normalizedLineDate == normalizedTarget) {
    //                     println("Ligne supprimée : $line")
    //                     continue  // Ne pas inclure cette ligne dans le nouveau contenu
    //                 }
    //             } else {
    //                 // Si la date ne peut pas être parsée, on peut choisir de conserver la ligne
    //                 println("Impossible de parser la date pour la ligne : $line")
    //             }
    //         }
    //         filteredLines.add(line)
    //     }
    //
    //     // Reconstituer le contenu final : en-tête + lignes filtrées
    //     val newContent = buildString {
    //         append(header).append("\n")
    //         for (line in filteredLines) {
    //             append(line).append("\n")
    //         }
    //     }
    //
    //     // Créer une sauvegarde du fichier original en y ajoutant un timestamp
    //     val backupFileName = "oapsaimiML2_records_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
    //     val backupFile = File(csvFile.parentFile, backupFileName)
    //     csvFile.copyTo(backupFile, overwrite = true)
    //
    //     // Écraser le fichier original avec le contenu filtré
    //     csvFile.writeText(newContent, Charsets.UTF_8)
    //
    //     println("Fichier mis à jour. Backup créé sous '${backupFile.name}'.")
    // }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr,hourOfDay,weekend," +
            "bg,targetBg,iob,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"
        if (!csvfile2.exists()) {
            csvfile2.parentFile?.mkdirs() // Crée le dossier s'il n'existe pas
            csvfile2.createNewFile()
            csvfile2.appendText(headerRow)
        }
        csvfile2.appendText(valuesToRecord + "\n")
    }
    // private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
    //     // Vérifier si le TIR est inférieur à 80
    //     if (tir1DAYIR < 85) {
    //         // Vérifier si l'heure actuelle est entre 00:05 et 00:10
    //         val currentTime = LocalTime.now()
    //         val start = LocalTime.of(0, 5)
    //         val end = LocalTime.of(0, 10)
    //
    //         if (currentTime.isAfter(start) && currentTime.isBefore(end)) {
    //             // Calculer la date de la veille au format dd/MM/yyyy
    //             val yesterday = LocalDate.now().minusDays(1)
    //             val dateToRemove = yesterday.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    //
    //             // Appeler la méthode de suppression
    //             createFilteredAndSortedCopy(dateToRemove)
    //             println("Les données pour la date $dateToRemove ont été supprimées car TIR1DAIIR est inférieur à 80.")
    //         } else {
    //             println("La suppression ne peut être exécutée qu'entre 00:05 et 00:10.")
    //         }
    //     } else {
    //         println("Aucune suppression nécessaire : tir1DAYIR est supérieur ou égal à 85.")
    //     }
    // }
    fun removeLast200Lines(csvFile: File) {
        if (!csvFile.exists()) {
            println("Le fichier original n'existe pas.")
            return
        }

        // Lire toutes les lignes du fichier
        val lines = csvFile.readLines(Charsets.UTF_8)

        if (lines.size <= 200) {
            println("Le fichier contient moins ou égal à 200 lignes, aucune suppression effectuée.")
            return
        }

        // Conserver toutes les lignes sauf les 200 dernières
        val newLines = lines.dropLast(200)

        // Création d'un nom de sauvegarde avec timestamp
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val backupFileName = "backup_$timestamp.csv"
        val backupFile = File(csvFile.parentFile, backupFileName)

        // Sauvegarder le fichier original
        csvFile.copyTo(backupFile, overwrite = true)

        // Réécrire le fichier original avec les lignes restantes
        csvFile.writeText(newLines.joinToString("\n"), Charsets.UTF_8)

        println("Les 200 dernières lignes ont été supprimées. Le fichier original a été sauvegardé sous '$backupFileName'.")
    }
    private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
        // Vérifier si le TIR est inférieur à 85%
        if (tir1DAYIR < 85) {
            // Vérifier si l'heure actuelle est entre 00:05 et 00:10
            val currentTime = LocalTime.now()
            val start = LocalTime.of(0, 5)
            val end = LocalTime.of(0, 10)

            if (currentTime.isAfter(start) && currentTime.isBefore(end)) {
                // Calculer la date de la veille au format dd/MM/yyyy
                val yesterday = LocalDate.now().minusDays(1)
                val dateToRemove = yesterday.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

                // Appeler la méthode de suppression
                //createFilteredAndSortedCopy(csvfile,dateToRemove)
                removeLast200Lines(csvfile)
                println("Les données pour la date $dateToRemove ont été supprimées car TIR1DAIIR est inférieur à 85%.")
            } else {
                println("La suppression ne peut être exécutée qu'entre 00:05 et 00:10.")
            }
        } else {
            println("Aucune suppression nécessaire : tir1DAYIR est supérieur ou égal à 85%.")
        }
    }

    private fun applySafetyPrecautions(mealData: MealData, smbToGiveParam: Float): Float {
        var smbToGive = smbToGiveParam
        val (conditionResult, _) = isCriticalSafetyCondition(mealData)
        if (conditionResult) return 0.0f
        if (isSportSafetyCondition()) return 0.0f
        // Ajustements basés sur des conditions spécifiques
        smbToGive = applySpecificAdjustments(smbToGive)

        smbToGive = finalizeSmbToGive(smbToGive)
        // Appliquer les limites maximum
        smbToGive = applyMaxLimits(smbToGive)

        return smbToGive
    }
    private fun applyMaxLimits(smbToGive: Float): Float {
        var result = smbToGive

        // Vérifiez d'abord si smbToGive dépasse maxSMB
        if (result > maxSMB) {
            result = maxSMB.toFloat()
        }
        // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
        if (iob + result > maxIob) {
            result = maxIob.toFloat() - iob
        }

        return result
    }
    private fun hasReceivedPbolusMInLastHour(pbolusA: Double): Boolean {
        val epsilon = 0.01
        val oneHourAgo = dateUtil.now() - T.hours(1).msecs()

        val bolusesLastHour = persistenceLayer
            .getBolusesFromTime(oneHourAgo, true)
            .blockingGet()

        return bolusesLastHour.any { Math.abs(it.amount - pbolusA) < epsilon }
    }
    private fun isAutodriveModeCondition(
        targetBg: Float,
        delta: Float,
        autodrive: Boolean,
        slopeFromMinDeviation: Double,
        bg: Float
    ): Boolean {
        // Récupération de la valeur de pbolusMeal depuis les préférences
        //val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pbolusA = when (hour) {
            in 0..10 -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusMorning)
            in 11..17 -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusAfternoon)
            else -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusEvening)
        }
        val autodriveDelta: Double = preferences.get(DoubleKey.OApsAIMIcombinedDelta)
        val autodriveminDeviation: Double = preferences.get(DoubleKey.OApsAIMIAutodriveDeviation)
        //val autodriveISF: Int = preferences.get(IntKey.OApsAIMIautodriveISF)
        val autodriveTarget: Int = preferences.get(IntKey.OApsAIMIAutodriveTarget)
        val autodriveBG: Int = preferences.get(IntKey.OApsAIMIAutodriveBG)
        // Récupération des deltas récents et calcul du delta prédit
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        // Si un bolus de pbolusA a déjà été administré dans la dernière heure, on ne le ré-administrera pas
        if (hasReceivedPbolusMInLastHour(pbolusA)) {
            return false
        }

        return targetBg <= autodriveTarget &&
            combinedDelta >= autodriveDelta &&
            autodrive &&
            slopeFromMinDeviation >= autodriveminDeviation &&
            bg >= autodriveBG
    }

    private fun isMealModeCondition(): Boolean {
        val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        return mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
    }
    private fun isbfastModeCondition(): Boolean {
        val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
        return bfastruntime in 0..7 && lastBolusSMBUnit != pbolusbfast.toFloat() && bfastTime
    }
    private fun isbfast2ModeCondition(): Boolean {
        val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
        return bfastruntime in 15..30 && lastBolusSMBUnit != pbolusbfast2.toFloat() && bfastTime
    }
    private fun isLunchModeCondition(): Boolean {
        val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
        return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
    }
    private fun isLunch2ModeCondition(): Boolean {
        val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
        return lunchruntime in 15..24 && lastBolusSMBUnit != pbolusLunch2.toFloat() && lunchTime
    }
    private fun isDinnerModeCondition(): Boolean {
        val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
        return dinnerruntime in 0..7 && lastBolusSMBUnit != pbolusDinner.toFloat() && dinnerTime
    }
    private fun isDinner2ModeCondition(): Boolean {
        val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
        return dinnerruntime in 15..24 && lastBolusSMBUnit != pbolusDinner2.toFloat() && dinnerTime
    }
    private fun isHighCarbModeCondition(): Boolean {
        val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
        return highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
    }

    private fun issnackModeCondition(): Boolean {
        val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
        return snackrunTime in 0..7 && lastBolusSMBUnit != pbolussnack.toFloat() && snackTime
    }
    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }
    /*private fun isCriticalSafetyCondition(mealData: MealData): Pair<Boolean, String> {
        val conditionsTrue = mutableListOf<String>()
        //val slopedeviation = mealData.slopeFromMaxDeviation <= -1.5 && mealData.slopeFromMinDeviation < 0.3
        //if (slopedeviation) conditionsTrue.add("slopedeviation")
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val nosmbHM = iob > 0.7 && honeymoon && delta <= 10.0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && predictedBg < 130
        if (nosmbHM) conditionsTrue.add("nosmbHM")
        val honeysmb = honeymoon && delta < 0 && bg < 170
        if (honeysmb) conditionsTrue.add("honeysmb")
        val negdelta = delta <= 0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && eventualBG < 140
        if (negdelta) conditionsTrue.add("negdelta")
        val nosmb = iob >= 2*maxSMB && bg < 110 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (nosmb) conditionsTrue.add("nosmb")
        val fasting = fastingTime
        if (fasting) conditionsTrue.add("fasting")
        val belowMinThreshold = bg < 100 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowMinThreshold) conditionsTrue.add("belowMinThreshold")
        val isNewCalibration = iscalibration && delta > 8
        if (isNewCalibration) conditionsTrue.add("isNewCalibration")
        val belowTargetAndDropping = bg < targetBg && delta < -2 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndDropping) conditionsTrue.add("belowTargetAndDropping")
        val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndStableButNoCob) conditionsTrue.add("belowTargetAndStableButNoCob")
        val droppingFast = bg < 150 && delta < -2
        if (droppingFast) conditionsTrue.add("droppingFast")
        val droppingFastAtHigh = bg < 220 && delta <= -7
        if (droppingFastAtHigh) conditionsTrue.add("droppingFastAtHigh")
        val droppingVeryFast = delta < -11
        if (droppingVeryFast) conditionsTrue.add("droppingVeryFast")
        val prediction = predictedBg < targetBg && bg < 135 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (prediction) conditionsTrue.add("prediction")
        val interval = eventualBG < targetBg && delta > 10 && iob >= maxSMB/2 && lastsmbtime < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime && !snackTime
        if (interval) conditionsTrue.add("interval")
        val targetinterval = targetBg >= 120 && delta > 0 && iob >= maxSMB/2 && lastsmbtime < 12 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime && !snackTime
        if (targetinterval) conditionsTrue.add("targetinterval")
        //val stablebg = delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 120 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        //if (stablebg) conditionsTrue.add("stablebg")
        val acceleratingDown = delta < -2 && delta - longAvgDelta < -2 && lastsmbtime < 15
        if (acceleratingDown) conditionsTrue.add("acceleratingDown")
        val decceleratingdown = delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta) && lastsmbtime < 15
        if (decceleratingdown) conditionsTrue.add("decceleratingdown")
        val nosmbhoneymoon = honeymoon && iob > maxIob / 2 && delta < 0
        if (nosmbhoneymoon) conditionsTrue.add("nosmbhoneymoon")
        val bg90 = bg < 90
        if (bg90) conditionsTrue.add("bg90")
        val result = belowTargetAndDropping || belowTargetAndStableButNoCob || nosmbHM || honeysmb ||
            droppingFast || droppingFastAtHigh || droppingVeryFast || prediction || interval || targetinterval || bg90 || negdelta ||
            fasting || nosmb || isNewCalibration || belowMinThreshold || acceleratingDown || decceleratingdown || nosmbhoneymoon

        val conditionsTrueString = if (conditionsTrue.isNotEmpty()) {
            conditionsTrue.joinToString(", ")
        } else {
            "No conditions met"
        }

        return Pair(result, conditionsTrueString)
    } */
    private fun isCriticalSafetyCondition(mealData: MealData): Pair<Boolean, String> {
        val conditionsTrue = mutableListOf<String>()
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val nosmbHM = iob > 0.7 && honeymoon && delta <= 10.0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && predictedBg < 130
        if (nosmbHM) conditionsTrue.add(context.getString(R.string.safety_adjustments_15))
        val honeysmb = honeymoon && delta < 0 && bg < 170
        if (honeysmb) conditionsTrue.add(context.getString(R.string.safety_adjustments_16))
        val negdelta = delta <= 0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && eventualBG < 140
        if (negdelta) conditionsTrue.add(context.getString(R.string.safety_adjustments_17))
        val nosmb = iob >= 2*maxSMB && bg < 110 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (nosmb) conditionsTrue.add(context.getString(R.string.safety_adjustments_18))
        val fasting = fastingTime
        if (fasting) conditionsTrue.add(context.getString(R.string.safety_adjustments_19))
        val belowMinThreshold = bg < 100 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowMinThreshold) conditionsTrue.add(context.getString(R.string.safety_adjustments_20))
        val isNewCalibration = iscalibration && delta > 8
        if (isNewCalibration) conditionsTrue.add(context.getString(R.string.safety_adjustments_21))
        val belowTargetAndDropping = bg < targetBg && delta < -2 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndDropping) conditionsTrue.add(context.getString(R.string.safety_adjustments_22))
        val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndStableButNoCob) conditionsTrue.add(context.getString(R.string.safety_adjustments_23))
        val droppingFast = bg < 150 && delta < -2
        if (droppingFast) conditionsTrue.add(context.getString(R.string.safety_adjustments_24))
        val droppingFastAtHigh = bg < 220 && delta <= -7
        if (droppingFastAtHigh) conditionsTrue.add(context.getString(R.string.safety_adjustments_25))
        val droppingVeryFast = delta < -11
        if (droppingVeryFast) conditionsTrue.add(context.getString(R.string.safety_adjustments_26))
        val prediction = predictedBg < targetBg && bg < 135 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (prediction) conditionsTrue.add(context.getString(R.string.safety_adjustments_27))
        val interval = eventualBG < targetBg && delta > 10 && iob >= maxSMB/2 && lastsmbtime < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime && !snackTime
        if (interval) conditionsTrue.add(context.getString(R.string.safety_adjustments_28))
        val targetinterval = targetBg >= 120 && delta > 0 && iob >= maxSMB/2 && lastsmbtime < 12 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime && !snackTime
        if (targetinterval) conditionsTrue.add(context.getString(R.string.safety_adjustments_29))
        val acceleratingDown = delta < -2 && delta - longAvgDelta < -2 && lastsmbtime < 15
        if (acceleratingDown) conditionsTrue.add(context.getString(R.string.safety_adjustments_30))
        val decceleratingdown = delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta) && lastsmbtime < 15
        if (decceleratingdown) conditionsTrue.add(context.getString(R.string.safety_adjustments_31))
        val nosmbhoneymoon = honeymoon && iob > maxIob / 2 && delta < 0
        if (nosmbhoneymoon) conditionsTrue.add(context.getString(R.string.safety_adjustments_32))
        val bg90 = bg < 90
        if (bg90) conditionsTrue.add(context.getString(R.string.safety_adjustments_33))

        val result = belowTargetAndDropping || belowTargetAndStableButNoCob || nosmbHM || honeysmb ||
            droppingFast || droppingFastAtHigh || droppingVeryFast || prediction || interval || targetinterval || bg90 || negdelta ||
            fasting || nosmb || isNewCalibration || belowMinThreshold || acceleratingDown || decceleratingdown || nosmbhoneymoon

        val conditionsTrueString = if (conditionsTrue.isNotEmpty()) {
            conditionsTrue.joinToString(", ")
        } else {
            context.getString(R.string.no_conditions_met)
        }

        return Pair(result, conditionsTrueString)
    }
    private fun isSportSafetyCondition(): Boolean {
        val sport = targetBg >= 140 && recentSteps5Minutes >= 200 && recentSteps10Minutes >= 400
        val sport1 = targetBg >= 140 && recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute10
        val sport2 = recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute10
        val sport3 = recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
        val sport4 = targetBg >= 140
        val sport5= sportTime

        return sport || sport1 || sport2 || sport3 || sport4 || sport5

    }
    private fun calculateSMBInterval(): Int {
        // Récupération des intervalles configurés
        val intervalSnack = preferences.get(IntKey.OApsAIMISnackinterval)
        val intervalMeal = preferences.get(IntKey.OApsAIMImealinterval)
        val intervalBF = preferences.get(IntKey.OApsAIMIBFinterval)
        val intervalLunch = preferences.get(IntKey.OApsAIMILunchinterval)
        val intervalDinner = preferences.get(IntKey.OApsAIMIDinnerinterval)
        val intervalSleep = preferences.get(IntKey.OApsAIMISleepinterval)
        val intervalHC = preferences.get(IntKey.OApsAIMIHCinterval)
        val intervalHighBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        if (delta > 15f) {
            return 1
        }
        // Par défaut, on part d'un intervalle de base (par exemple 5 minutes)
        var interval = 5

        // Si une des conditions d'intervalle est satisfaite, annuler l'intervalle (0 minute)
        if (shouldApplyIntervalAdjustment(
                intervalSnack, intervalMeal, intervalBF,
                intervalLunch, intervalDinner, intervalSleep,
                intervalHC, intervalHighBG
            )) {
            interval = 0
        }
        // Sinon, si une condition de sécurité s'applique, forcer un intervalle de 10 minutes
        else if (shouldApplySafetyAdjustment()) {
            interval = 10
        }
        // Sinon, si une condition temporelle (ex. heure inappropriée) s'applique, fixer l'intervalle à 10 minutes
        else if (shouldApplyTimeAdjustment()) {
            interval = 10
        }

        // Si une forte activité est détectée via les pas, l'intervalle devient 0 (on annule toute nouvelle administration)
        if (shouldApplyStepAdjustment()) {
            interval = 0
        }

        // Ajustements supplémentaires :
        // Si BG est en dessous de la cible (et donc en chute), augmenter l'intervalle (attendre plus longtemps)
        if (bg < targetBg) {
            interval = (interval * 2).coerceAtMost(20)
        }
        // En mode honeymoon avec BG < 170 et delta faible, attendre plus longtemps
        if (preferences.get(BooleanKey.OApsAIMIhoneymoon) && bg < 170 && delta < 5) {
            interval = (interval * 2).coerceAtMost(20)
        }
        // Si c'est la nuit (par exemple à 23h) et que delta est faible et IOB bas, on réduit légèrement l'intervalle
        val currentHour = LocalTime.now().hour
        if (preferences.get(BooleanKey.OApsAIMInight) && currentHour == 23 && delta < 10 && iob < maxSMB) {
            interval = (interval * 0.8).toInt()
        }

        return interval
    }

    private fun applySpecificAdjustments(smbToGive: Float): Float {
        var result = smbToGive
        val intervalSMBsnack = preferences.get(IntKey.OApsAIMISnackinterval)
        val intervalSMBmeal = preferences.get(IntKey.OApsAIMImealinterval)
        val intervalSMBbfast = preferences.get(IntKey.OApsAIMIBFinterval)
        val intervalSMBlunch = preferences.get(IntKey.OApsAIMILunchinterval)
        val intervalSMBdinner = preferences.get(IntKey.OApsAIMIDinnerinterval)
        val intervalSMBsleep = preferences.get(IntKey.OApsAIMISleepinterval)
        val intervalSMBhc = preferences.get(IntKey.OApsAIMIHCinterval)
        val intervalSMBhighBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val belowTargetAndDropping = bg < targetBg
        val night = preferences.get(BooleanKey.OApsAIMInight)
        val currentHour = LocalTime.now().hour

        when {
            shouldApplyIntervalAdjustment(intervalSMBsnack, intervalSMBmeal, intervalSMBbfast, intervalSMBlunch, intervalSMBdinner, intervalSMBsleep, intervalSMBhc, intervalSMBhighBG) -> {
                result = 0.0f
            }
            shouldApplySafetyAdjustment() -> {
                result *= 0.75f
                this.intervalsmb = 10
            }
            shouldApplyTimeAdjustment() -> {
                result = 0.0f
                this.intervalsmb = 10
            }
        }

        if (shouldApplyStepAdjustment()) result = 0.0f
        if (belowTargetAndDropping) result /= 2
        if (honeymoon && bg < 170 && delta < 5) result /= 2
        if (night && currentHour in 23..23 && delta < 10 && iob < maxSMB) result *= 0.8f
        if (currentHour in 0..7 && delta < 10 && iob < maxSMB) result *= 0.8f // Ajout d'une réduction pendant la période de minuit à 5h du matin

        return result
    }


    private fun shouldApplyIntervalAdjustment(
        intervalSMBsnack: Int, intervalSMBmeal: Int, intervalSMBbfast: Int,
        intervalSMBlunch: Int, intervalSMBdinner: Int, intervalSMBsleep: Int,
        intervalSMBhc: Int, intervalSMBhighBG: Int
    ): Boolean {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        return (lastsmbtime < intervalSMBsnack && snackTime)
            || (lastsmbtime < intervalSMBmeal && mealTime)
            || (lastsmbtime < intervalSMBbfast && bfastTime)
            || (lastsmbtime < intervalSMBlunch && lunchTime)
            || (lastsmbtime < intervalSMBdinner && dinnerTime)
            || (lastsmbtime < intervalSMBsleep && sleepTime)
            || (lastsmbtime < intervalSMBhc && highCarbTime)
            || (!honeymoon && lastsmbtime < intervalSMBhighBG && bg > 120)
            || (honeymoon && lastsmbtime < intervalSMBhighBG && bg > 180)
    }


    private fun shouldApplySafetyAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime >= 15
    }

    private fun shouldApplyTimeAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime < 15
    }

    private fun shouldApplyStepAdjustment(): Boolean {
        return recentSteps5Minutes > 100 && recentSteps30Minutes > 500 && lastsmbtime < 20
    }
    private fun finalizeSmbToGive(smbToGive: Float): Float {
        var result = smbToGive
        // Assurez-vous que smbToGive n'est pas négatif
        if (result < 0.0f) {
            result = 0.0f
        }
        if (iob <= 0.1 && bg > 120 && delta >= 2 && result == 0.0f) {
            result = 0.1f
        }
        return result
    }
    private fun calculateSMBFromModel(): Float {
        val selectedModelFile: File?
        val modelInputs: FloatArray

        when {
            cob > 0 && lastCarbAgeMin < 240 && modelFile.exists() -> {
                selectedModelFile = modelFile
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta
                )
            }

            modelFileUAM.exists()   -> {
                selectedModelFile = modelFileUAM
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                    tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                    recentSteps5Minutes.toFloat(),recentSteps10Minutes.toFloat(),recentSteps15Minutes.toFloat(),recentSteps30Minutes.toFloat(),recentSteps60Minutes.toFloat(),recentSteps180Minutes.toFloat()
                )
            }

            else                 -> {
                return 0.0F
            }
        }

        val interpreter = Interpreter(selectedModelFile)
        val output = arrayOf(floatArrayOf(0.0F))
        interpreter.run(modelInputs, output)
        interpreter.close()
        var smbToGive = output[0][0].toString().replace(',', '.').toDouble()

        val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))
        smbToGive = formatter.format(smbToGive).toDouble()

        return smbToGive.toFloat()
    }

private fun neuralnetwork5(
    delta: Float,
    shortAvgDelta: Float,
    longAvgDelta: Float,
    predictedSMB: Float,
    profile: OapsProfileAimi
): Float {
    val recentDeltas = getRecentDeltas()
    val predicted = predictedDelta(recentDeltas)
    val combinedDelta = (delta + predicted) / 2.0f
    // Définir un nombre maximal d'itérations plus bas en cas de montée rapide
    val maxIterations = if (combinedDelta > 15f) 25 else 50
    var finalRefinedSMB: Float = calculateSMBFromModel()

    val allLines = csvfile.readLines()
    println("CSV file path: \${csvfile.absolutePath}")
    if (allLines.isEmpty()) {
        println("CSV file is empty.")
        return predictedSMB
    }

    val headerLine = allLines.first()
    val headers = headerLine.split(",").map { it.trim() }
    val requiredColumns = listOf(
        "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
        "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
        "predictedSMB", "smbGiven"
    )
    if (!requiredColumns.all { headers.contains(it) }) {
        println("CSV file is missing required columns.")
        return predictedSMB
    }

    val colIndices = requiredColumns.map { headers.indexOf(it) }
    val targetColIndex = headers.indexOf("smbGiven")
    val inputs = mutableListOf<FloatArray>()
    val targets = mutableListOf<DoubleArray>()
    var lastEnhancedInput: FloatArray? = null

    for (line in allLines.drop(1)) {
        val cols = line.split(",").map { it.trim() }
        val rawInput = colIndices.mapNotNull { idx -> cols.getOrNull(idx)?.toFloatOrNull() }.toFloatArray()

        val trendIndicator = calculateTrendIndicator(
            delta, shortAvgDelta, longAvgDelta,
            bg.toFloat(), iob, variableSensitivity, cob, normalBgThreshold,
            recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
            profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
        )

        val enhancedInput = rawInput.copyOf(rawInput.size + 1)
        enhancedInput[rawInput.size] = trendIndicator.toFloat()
        lastEnhancedInput = enhancedInput

        val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
        if (targetValue != null) {
            inputs.add(enhancedInput)
            targets.add(doubleArrayOf(targetValue))
        }
    }

    if (inputs.isEmpty() || targets.isEmpty()) {
        println("Insufficient data for training.")
        return predictedSMB
    }

    val maxK = 10
    val adjustedK = minOf(maxK, inputs.size)
    val foldSize = maxOf(1, inputs.size / adjustedK)
    var bestNetwork: AimiNeuralNetwork? = null
    var bestFoldValLoss = Double.MAX_VALUE

    for (k in 0 until adjustedK) {
        val validationInputs = inputs.subList(k * foldSize, minOf((k + 1) * foldSize, inputs.size))
        val validationTargets = targets.subList(k * foldSize, minOf((k + 1) * foldSize, targets.size))
        val trainingInputs = inputs.minus(validationInputs)
        val trainingTargets = targets.minus(validationTargets)
        if (validationInputs.isEmpty()) continue

        val tempNetwork = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 5,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = 0.001,
                epochs = 200
            ),
            regularizationLambda = 0.01
        )

        tempNetwork.trainWithValidation(trainingInputs, trainingTargets, validationInputs, validationTargets)
        val foldValLoss = tempNetwork.validate(validationInputs, validationTargets)

        if (foldValLoss < bestFoldValLoss) {
            bestFoldValLoss = foldValLoss
            bestNetwork = tempNetwork
        }
    }

    val adjustedLearningRate = if (bestFoldValLoss < 0.01) 0.0005 else 0.001
    val epochs = if (bestFoldValLoss < 0.01) 100 else 200

    if (bestNetwork != null) {
        println("Réentraînement final avec les meilleurs hyperparamètres sur toutes les données...")
        val finalNetwork = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 5,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = adjustedLearningRate,
                beta1 = 0.9,
                beta2 = 0.999,
                epsilon = 1e-8,
                patience = 10,
                batchSize = 32,
                weightDecay = 0.01,
                epochs = epochs,
                useBatchNorm = false,
                useDropout = true,
                dropoutRate = 0.3,
                leakyReluAlpha = 0.01
            ),
            regularizationLambda = 0.01
        )
        finalNetwork.copyWeightsFrom(bestNetwork)
        finalNetwork.trainWithValidation(inputs, targets, inputs, targets)
        bestNetwork = finalNetwork
    }

    // --- Normalisation légère sur lastEnhancedInput ---
    fun normalize(input: FloatArray): FloatArray {
        val mean = input.average().toFloat()
        val std = input.map { (it - mean) * (it - mean) }.average().let { sqrt(it).toFloat().coerceAtLeast(1e-8f) }
        return input.map { (it - mean) / std }.toFloatArray()
    }

    var iterationCount = 0
    do {
        val dynamicThreshold = calculateDynamicThreshold(iterationCount, delta, shortAvgDelta, longAvgDelta)
        val normalizedInput = lastEnhancedInput?.let { normalize(it) }?.toDoubleArray() ?: DoubleArray(0)
        val refinedSMB = bestNetwork?.let {
            AimiNeuralNetwork.refineSMB(finalRefinedSMB, it, normalizedInput)
        } ?: finalRefinedSMB

        println("→ Iteration $iterationCount | SMB=$finalRefinedSMB → $refinedSMB | Δ=${abs(finalRefinedSMB - refinedSMB)} | threshold=$dynamicThreshold")

        if (abs(finalRefinedSMB - refinedSMB) <= dynamicThreshold) {
            finalRefinedSMB = max(0.05f, refinedSMB)
            break
        }
        iterationCount++
    } while (iterationCount < maxIterations)

    if (finalRefinedSMB > predictedSMB && bg > 150 && delta > 5) {
        println("Modèle prédictif plus élevé, ajustement retenu.")
        return finalRefinedSMB
    }

    val alpha = 0.7f
    val blendedSMB = alpha * finalRefinedSMB + (1 - alpha) * predictedSMB
    return blendedSMB
}
    private fun computeDynamicBolusMultiplier(delta: Float): Float {
        return when {
            delta > 20f -> 1.2f  // Montée très rapide : augmenter la dose corrective
            delta > 15f -> 1.1f  // Montée rapide
            delta > 10f -> 1.0f  // Montée modérée : pas de réduction
            delta in 5f..10f -> 0.9f // Légère réduction pour des changements moins brusques
            else -> 0.8f // Pour des variations faibles ou des baisses, appliquer une réduction standard
        }
    }
    private fun calculateDynamicThreshold(
        iterationCount: Int,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float
    ): Float {
        val baseThreshold = if (delta > 15f) 1.5f else 2.5f
        // Réduit le seuil au fur et à mesure des itérations pour exiger une convergence plus fine
        val iterationFactor = 1.0f / (1 + iterationCount / 100)
        val trendFactor = when {
            delta > 8 || shortAvgDelta > 4 || longAvgDelta > 3 -> 0.5f
            delta < 5 && shortAvgDelta < 3 && longAvgDelta < 3 -> 1.5f
            else -> 1.0f
        }
        return baseThreshold * iterationFactor * trendFactor
    }

    private fun FloatArray.toDoubleArray(): DoubleArray {
        return this.map { it.toDouble() }.toDoubleArray()
    }

    private fun calculateGFactor(delta: Float, lastHourTIRabove120: Double, bg: Float): Double {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        // 🔹 Facteurs initiaux (ajustés dynamiquement)
        var deltaFactor = when {
            bg > 140 && delta > 5 -> 2.0 // Réaction forte si la glycémie monte rapidement et est élevée
            bg > 120 && delta > 2 -> 1.5 // Réaction modérée
            delta > 1  -> 1.2 // Réaction légère
            bg < 120 && delta < 0 -> 0.6
            else -> 1.0 // Pas de variation significative
        }
        var bgFactor = when {
            bg > 140 -> 1.8 // Réduction forte si glycémie > 150 mg/dL
            bg > 120 -> 1.4 // Réduction modérée si > 120 mg/dL
            bg > 100 -> 1.0 // Neutre entre 100 et 120
            bg < 80  -> 0.5 // Augmente l'ISF si la glycémie est sous la cible
            else -> 0.9 // Légère augmentation de l'ISF
        }
        var tirFactor = when {
            lastHourTIRabove120 > 0.5 && bg > 120 -> 1.2 + lastHourTIRabove120 * 0.15 // Augmente si tendance à rester haut
            bg < 100 -> 0.8 // Augmente l'ISF si retour à une glycémie basse
            else -> 1.0
        }
        // 🔹 Mode "Honeymoon" (ajustements spécifiques)
        if (honeymoon) {
            deltaFactor = when {
                bg > 140 && delta > 5 -> 2.2
                bg > 120 && delta > 2 -> 1.7
                delta > 1 -> 1.3
                else -> 1.0
            }
            bgFactor = when {
                bg > 150 -> 1.6
                bg > 130 -> 1.4
                bg < 100 -> 0.6 // Augmente encore plus l'ISF en honeymoon
                else -> 0.9
            }
            tirFactor = when {
                lastHourTIRabove120 > 0.5 && bg > 120 -> 1.3 + lastHourTIRabove120 * 0.05
                bg < 100 -> 0.7 // Encore plus de renforcement de l'ISF
                else -> 1.0
            }
        }

        // 🔹 Combine tous les facteurs
        return deltaFactor * bgFactor * tirFactor
    }
    private fun interpolateFactor(value: Float, start1: Float, end1: Float, start2: Float, end2: Float): Float {
        return start2 + (value - start1) * (end2 - start2) / (end1 - start1)
    }
    // Méthode pour récupérer les deltas récents (entre 2.5 et 7.5 minutes par exemple)
    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        // Fenêtre standard selon BG
        val standardWindow = if (bg < 130) 30f else 15f
        // Fenêtre raccourcie pour détection rapide
        val rapidRiseWindow = 10f
        // Si le delta instantané est supérieur à 15 mg/dL, on choisit la fenêtre rapide
        val intervalMinutes = if (delta > 15) rapidRiseWindow else standardWindow

        val nowTimestamp = data.first().timestamp
        val recentDeltas = mutableListOf<Double>()
        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 0.0f..intervalMinutes) {
                    val delta = (data.first().recalculated - data[i].recalculated) / minutesAgo * 5f
                    recentDeltas.add(delta)
                }
            }
        }
        return recentDeltas
    }

    // Calcul d'un delta prédit à partir d'une moyenne pondérée
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }

    private fun adjustFactorsBasedOnBgAndHypo(
        morningFactor: Float,
        afternoonFactor: Float,
        eveningFactor: Float
    ): Triple<Float, Float, Float> {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val hypoAdjustment = if (bg < 120 || (iob > 3 * maxSMB)) 0.3f else 0.9f
        // Récupération des deltas récents et calcul du delta prédit
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        // s'assurer que combinedDelta est positif pour le calcul logarithmique
        val safeCombinedDelta = if (combinedDelta <= 0) 0.0001f else combinedDelta
        val deltaAdjustment = ln(safeCombinedDelta.toDouble() + 1).coerceAtLeast(0.0)


        // Interpolation de base pour factorAdjustment selon la glycémie (bg)
        var factorAdjustment = when {
            bg < 110 -> interpolateFactor(bg.toFloat(), 70f, 110f, 0.1f, 0.3f)
            else -> interpolateFactor(bg.toFloat(), 110f, 280f, 0.75f, 2.5f)
        }
        if (honeymoon) factorAdjustment = when {
            bg < 160 -> interpolateFactor(bg.toFloat(), 70f, 160f, 0.2f, 0.4f)
            else -> interpolateFactor(bg.toFloat(), 160f, 250f, 0.4f, 0.65f)
        }
        var bgAdjustment = 1.0f + (deltaAdjustment - 1) * factorAdjustment
        bgAdjustment *= 1.2f

        val dynamicCorrection = when {
            hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22 -> 0.7f
            combinedDelta > 11f  -> 2.5f   // Très forte montée, on augmente très agressivement
            combinedDelta > 8f  -> 2.0f   // Montée forte
            combinedDelta > 4f  -> 1.5f   // Montée modérée à forte
            combinedDelta > 2f  -> 1.0f   // Montée légère
            combinedDelta in -2f..2f -> 0.8f  // Stable
            combinedDelta < -2f && combinedDelta >= -4f -> 0.7f  // Baisse légère
            combinedDelta < -4f && combinedDelta >= -6f -> 0.5f  // Baisse modérée
            combinedDelta < -6f -> 0.4f   // Baisse forte, on diminue considérablement pour éviter l'hypo
            else -> 1.0f
        }
        // On applique ce facteur sur bgAdjustment pour intégrer l'anticipation
        bgAdjustment *= dynamicCorrection

        // // Interpolation pour scalingFactor basée sur la cible (targetBg)
        // val scalingFactor = interpolateFactor(bg.toFloat(), targetBg, 110f, 09f, 0.5f).coerceAtLeast(0.1f)

        val maxIncreaseFactor = 12.5f
        val maxDecreaseFactor = 0.2f

        val adjustFactor = { factor: Float ->
            val adjustedFactor = factor * bgAdjustment * hypoAdjustment //* scalingFactor
            adjustedFactor.coerceIn(((factor * maxDecreaseFactor).toDouble()), ((factor * maxIncreaseFactor).toDouble()))
        }

        return Triple(
            adjustFactor(morningFactor).takeIf { !it.isNaN() } ?: morningFactor,
            adjustFactor(afternoonFactor).takeIf { !it.isNaN() } ?: afternoonFactor,
            adjustFactor(eveningFactor).takeIf { !it.isNaN() } ?: eveningFactor
        ) as Triple<Float, Float, Float>
    }



    private fun calculateAdjustedDelayFactor(
        bg: Float,
        recentSteps180Minutes: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float
    ): Float {
        val currentHour = LocalTime.now().hour
        var delayFactor = if (bg.isNaN() || averageBeatsPerMinute.isNaN() || averageBeatsPerMinute10.isNaN() || averageBeatsPerMinute10 == 0f) {
            1f
        } else {
            val stepActivityThreshold = 1500
            val heartRateIncreaseThreshold = 1.2
            val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

            val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold
            val heartRateChange = averageBeatsPerMinute / averageBeatsPerMinute10
            val increasedHeartRateActivity = heartRateChange >= heartRateIncreaseThreshold

            val baseFactor = when {
                bg <= normalBgThreshold -> 1f
                bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
                else -> 0.5f
            }

            if (increasedPhysicalActivity || increasedHeartRateActivity) {
                (baseFactor.toFloat() * 0.8f).coerceAtLeast(0.5f)
            } else {
                baseFactor.toFloat()
            }
        }
        // Augmenter le délai si l'heure est le soir (18h à 23h) ou diminuer le besoin entre 00h à 5h
        if (currentHour in 18..23) {
            delayFactor *= 1.2f
        } else if (currentHour in 0..5) {
            delayFactor *= 0.8f
        }
        return delayFactor
    }

    fun calculateMinutesAboveThreshold(
        bg: Double,           // Glycémie actuelle (mg/dL)
        slope: Double,        // Pente de la glycémie (mg/dL par minute)
        thresholdBG: Double   // Seuil de glycémie (mg/dL)
    ): Int {
        val bgDifference = bg - thresholdBG

        // Vérifier si la glycémie est en baisse
        if (slope >= 0) {
            // La glycémie est stable ou en hausse, retournez une valeur élevée
            return Int.MAX_VALUE // ou un grand nombre, par exemple 999
        }

        // Estimer le temps jusqu'au seuil
        val minutesAboveThreshold = bgDifference / -slope

        // Vérifier que le temps est positif et raisonnable
        return if (minutesAboveThreshold.isFinite() && minutesAboveThreshold > 0) {
            minutesAboveThreshold.roundToInt()
        } else {
            // Retourner une valeur maximale par défaut si le calcul n'est pas valide
            Int.MAX_VALUE
        }
    }


    fun estimateRequiredCarbs(
        bg: Double, // Glycémie actuelle
        targetBG: Double, // Objectif de glycémie
        slope: Double, // Vitesse de variation de la glycémie (mg/dL par minute)
        iob: Double, // Insulin On Board - quantité d'insuline encore active
        csf: Double, // Facteur de sensibilité aux glucides (mg/dL par gramme de glucides)
        isf: Double, // Facteur de sensibilité à l'insuline (mg/dL par unité d'insuline)
        cob: Double // Carbs On Board - glucides en cours d'absorption
    ): Int {
        // 1. Calculer la projection de la glycémie future basée sur la pente actuelle et le temps (30 minutes)
        val timeAhead = 20.0 // Projection sur 30 minutes
        val projectedDrop = slope * timeAhead // Estimation de la chute future de la glycémie

        // 2. Estimer l'effet de l'insuline active restante (IOB) sur la glycémie
        val insulinEffect = iob * isf // L'effet de l'insuline résiduelle

        // 3. Effet total estimé : baisse de la glycémie + effet de l'insuline
        val totalPredictedDrop = projectedDrop + insulinEffect

        // 4. Calculer la glycémie future estimée sans intervention
        val futureBG = bg - totalPredictedDrop

        // 5. Si la glycémie projetée est inférieure à la cible, estimer les glucides nécessaires
        if (futureBG < targetBG) {
            val bgDifference = targetBG - futureBG

            // 6. Si des glucides sont en cours d'absorption (COB), les prendre en compte
            val netCarbImpact = max(0.0, bgDifference - (cob * csf)) // Ajuster avec COB

            // 7. Calculer les glucides nécessaires pour combler la différence de glycémie
            val carbsReq = round(netCarbImpact / csf).toInt()

            // Debug info
            consoleError.add("Future BG: $futureBG, Projected Drop: $projectedDrop, Insulin Effect: $insulinEffect, COB Impact: ${cob * csf}, Carbs Required: $carbsReq")

            return carbsReq
        }

        return 0 // Aucun glucide nécessaire si la glycémie future est au-dessus de la cible
    }

    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float
    ): Float {
        // Calculer l'effet initial de l'insuline
        var insulinEffect = iob * variableSensitivity / insulinDivisor

        // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
        if (cob > 0) {
            // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
            insulinEffect *= 0.9f
        }
        val physicalActivityFactor = 1.0f - recentSteps180Min / 10000f
        insulinEffect *= physicalActivityFactor
        // Calculer le facteur de retard ajusté en fonction de l'activité physique
        val adjustedDelayFactor = calculateAdjustedDelayFactor(
            normalBgThreshold,
            recentSteps180Min,
            averageBeatsPerMinute,
            averageBeatsPerMinute10
        )

        // Appliquer le facteur de retard ajusté à l'effet de l'insuline
        insulinEffect *= adjustedDelayFactor
        if (bg > normalBgThreshold) {
            insulinEffect *= 1.2f
        }
        val currentHour = LocalTime.now().hour
        if (currentHour in 0..5) {
            insulinEffect *= 0.8f
        }

        return insulinEffect
    }
    private fun calculateTrendIndicator(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float,
        recentSteps5min: Int,
        recentSteps10min: Int
    ): Int {

        // Calcul de l'impact de l'insuline
        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Min,
            averageBeatsPerMinute, averageBeatsPerMinute10, insulinDivisor
        )

        // Calcul de l'impact de l'activité physique
        val activityImpact = (recentSteps5min - recentSteps10min) * 0.05

        // Calcul de l'indicateur de tendance
        val trendValue = (delta * 0.5) + (shortAvgDelta * 0.25) + (longAvgDelta * 0.15) + (insulinEffect * 0.2) + (activityImpact * 0.1)

        return when {
            trendValue > 1.0 -> 1 // Forte tendance à la hausse
            trendValue < -1.0 -> -1 // Forte tendance à la baisse
            abs(trendValue) < 0.5 -> 0 // Pas de tendance significative
            trendValue > 0.5 -> 2 // Faible tendance à la hausse
            else -> -2 // Faible tendance à la baisse
        }
    }

    private fun predictEventualBG(
        bg: Float,                     // Glycémie actuelle (mg/dL)
        iob: Float,                    // Insuline active (IOB)
        variableSensitivity: Float,    // Sensibilité insulinique (mg/dL/U)
        minDelta: Float,               // Delta instantané (mg/dL/5min)
        minAvgDelta: Float,            // Delta moyen court terme (mg/dL/5min)
        longAvgDelta: Float,           // Delta moyen long terme (mg/dL/5min)
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        honeymoon: Boolean
    ): Float {
        // 1. Détermination des paramètres glucidiques en fonction du contexte
        val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
            highCarbTime -> Triple(3.5f, 0.75f, 100f)
            snackTime    -> Triple(1.5f, 1.25f, 15f)
            mealTime     -> Triple(2.5f, 1.0f, 55f)
            bfastTime    -> Triple(3.5f, 1.0f, 55f)
            lunchTime    -> Triple(2.5f, 1.0f, 70f)
            dinnerTime   -> Triple(2.5f, 1.0f, 70f)
            else         -> Triple(2.5f, 1.0f, 70f)
        }

        // 2. Détermination du facteur d'absorption en fonction de l'heure de la journée
        val currentHour = LocalTime.now().hour
        val absorptionFactor = when (currentHour) {
            in 6..10 -> 1.3f
            in 11..15 -> 0.8f
            in 16..23 -> 1.2f
            else -> 1.0f
        }

        // 3. Calcul de l'effet insuline
        val insulinEffect = iob * variableSensitivity

        // 4. Calcul de la déviation basée sur la tendance sur 30 minutes
        var deviation = (30f / 5f) * minDelta
        deviation *= absorptionFactor * carbTypeFactor
        if (deviation < 0) {
            deviation = (30f / 5f) * minAvgDelta * absorptionFactor * carbTypeFactor
            if (deviation < 0) {
                deviation = (30f / 5f) * longAvgDelta * absorptionFactor * carbTypeFactor
            }
        }

        // 5. Prédiction alternative basée sur un modèle dédié qui prend aussi le contexte alimentaire
        val predictedFutureBG = predictFutureBg(
            bg, iob, variableSensitivity,
            averageCarbAbsorptionTime, carbTypeFactor, estimatedCob,
            honeymoon
        )

        // 6. Combinaison finale : effet insuline + tendance + impact COB
        val predictedBG = predictedFutureBG + deviation + (estimatedCob * 0.05f)

        // 7. Seuil minimal de sécurité
        val finalPredictedBG = when {
            !honeymoon && predictedBG < 39f -> 39f
            honeymoon && predictedBG < 50f -> 50f
            else -> predictedBG
        }

        return finalPredictedBG
    }

    private fun predictFutureBg(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        averageCarbAbsorptionTime: Float,
        carbTypeFactor: Float,
        estimatedCob: Float,
        honeymoon: Boolean
    ): Float {
        // Prise en compte de l'effet insuline
        val insulinEffect = iob * variableSensitivity

        // Prise en compte d'une absorption glucidique estimée (simple modèle linéaire)
        val carbImpact = (estimatedCob / averageCarbAbsorptionTime) * carbTypeFactor

        var futureBg = bg - insulinEffect + carbImpact

        if (!honeymoon && futureBg < 39f) {
            futureBg = 39f
        } else if (honeymoon && futureBg < 50f) {
            futureBg = 50f
        }

        return futureBg
    }

    private fun interpolatebasal(bg: Double): Double {
        val clampedBG = bg.coerceIn(80.0, 300.0)
        return when {
            clampedBG < 80 -> 0.5
            clampedBG < 120 -> {
                // Interpolation linéaire entre 80 (0.5) et 120 (2.0)
                val slope = (2.0 - 0.5) / (120.0 - 80.0)  // 1.5/40 = 0.0375
                0.5 + slope * (clampedBG - 80.0)
            }
            clampedBG < 180 -> {
                // Interpolation linéaire entre 120 (2.0) et 180 (5.0)
                val slope = (5.0 - 2.0) / (180.0 - 120.0)  // 3.0/60 = 0.05
                2.0 + slope * (clampedBG - 120.0)
            }
            else -> 5.0
        }
    }

    private fun interpolate(xdata: Double): Double {
        // Définir les points de référence pour l'interpolation, à partir de 80 mg/dL
        val polyX = arrayOf(80.0, 90.0, 100.0, 110.0, 130.0, 160.0, 200.0, 220.0, 240.0, 260.0, 280.0, 300.0)
        val polyY = arrayOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 9.0, 10.0, 10.0, 10.0, 10.0, 10.0) // Ajustement des valeurs pour la basale

        // Constants for basal adjustment weights
        val higherBasalRangeWeight: Double = 1.5 // Facteur pour les glycémies supérieures à 100 mg/dL
        val lowerBasalRangeWeight: Double = 0.8 // Facteur pour les glycémies inférieures à 100 mg/dL mais supérieures ou égales à 80

        val polymax = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        // État d'hypoglycémie (pour les valeurs < 80)
        if (xdata < 80) {
            newVal = 0.5 // Multiplicateur fixe pour l'hypoglycémie
        }
        // Extrapolation en avant (pour les valeurs > 300)
        else if (stepT < xdata) {
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
            // Limiter la valeur maximale si nécessaire
            newVal = min(newVal, 10.0) // Limitation de l'effet maximum à 10
        }
        // Interpolation normale
        else {
            for (i in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }

        // Appliquer des pondérations supplémentaires si nécessaire
        newVal = if (xdata > 100) {
            newVal * higherBasalRangeWeight
        } else {
            newVal * lowerBasalRangeWeight
        }

        // Limiter la valeur minimale à 0 et la valeur maximale à 10
        newVal = newVal.coerceIn(0.0, 10.0)

        return newVal
    }
    private fun calculateSmoothBasalRate(
        tddRecent: Float,
        tddPrevious: Float,
        currentBasalRate: Float
    ): Float {
        val weightRecent = 0.6f
        val weightPrevious = 1.0f - weightRecent  // 0.4f
        val weightedTdd = (tddRecent * weightRecent) + (tddPrevious * weightPrevious)
        val adjustedBasalRate = currentBasalRate * (weightedTdd / tddRecent)
        // Clamp pour éviter des ajustements trop extrêmes (par exemple, entre 50% et 200% de la basale actuelle)
        return adjustedBasalRate.coerceIn(currentBasalRate * 0.5f, currentBasalRate * 2.0f)
    }
    private fun computeFinalBasal(
        bg: Double,
        tddRecent: Float,
        tddPrevious: Float,
        currentBasalRate: Float
    ): Double {
        // 1. Lissage de la basale de fond sur la base du TDD
        val smoothBasal = calculateSmoothBasalRate(tddRecent, tddPrevious, currentBasalRate)

        // 2. Calcul du facteur d'ajustement en fonction de la glycémie
        val basalAdjustmentFactor = interpolate(bg)

        // 3. Application du facteur sur la basale lissée pour obtenir le taux final
        val finalBasal = smoothBasal * basalAdjustmentFactor

        // 4. Clamp du résultat final pour éviter des valeurs trop extrêmes (ex. entre 0 et 8 U/h)
        return finalBasal.coerceIn(0.0, 8.0)
    }

    //private fun determineNoteBasedOnBg(bg: Double): String {
      //  return when {
            //bg > 170 -> "more aggressive"
            //bg in 90.0..100.0 -> "less aggressive"
            //bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
            //bg < 80 -> "low treatment"
            //else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts

        //}
    //}

    private fun determineNoteBasedOnBg(bg: Double, context: Context): String {
        return when {
            bg > 170 -> context.getString(R.string.table_plugin_miscellaneous_8)
            bg in 90.0..100.0 -> context.getString(R.string.table_plugin_miscellaneous_9)
            bg in 80.0..89.9 -> context.getString(R.string.table_plugin_miscellaneous_10)
            bg < 80 -> context.getString(R.string.table_plugin_miscellaneous_11)
            else -> context.getString(R.string.table_plugin_miscellaneous_12)
        }
    }
    private fun processNotesAndCleanUp(notes: String): String {
        return notes.lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            //.replace("a", " ")
            .replace("an", " ")
            .replace("and", " ")
            .replace("\\s+", " ")
    }
   private fun calculateDynamicPeakTime(
    currentActivity: Double,
    futureActivity: Double,
    sensorLagActivity: Double,
    historicActivity: Double,
    profile: OapsProfileAimi,
    stepCount: Int? = null, // Nombre de pas
    heartRate: Int? = null, // Rythme cardiaque
    bg: Double,             // Glycémie actuelle
    delta: Double           // Variation glycémique
): Double {
    var dynamicPeakTime = profile.peakTime
    val activityRatio = futureActivity / (currentActivity + 0.0001)

       // Calcul d'un facteur de correction hyperglycémique de façon continue
       val hyperCorrectionFactor = when {
           bg <= 130 || delta <= 4 -> 1.0
           bg in 130.0..240.0 -> {
               // Le multiplicateur passe de 0.6 à 0.3 quand bg évolue de 130 à 240
               0.6 - (bg - 130) * (0.6 - 0.3) / (240 - 130)
           }
           else -> 0.3
       }
       dynamicPeakTime *= hyperCorrectionFactor

    // 2️⃣ **Ajustement basé sur l'IOB (currentActivity)**
    if (currentActivity > 0.1) {
        dynamicPeakTime += currentActivity * 20 + 5 // Ajuster proportionnellement à l'activité
    }

    // 3️⃣ **Ajustement basé sur le ratio d'activité**
    dynamicPeakTime *= when {
        activityRatio > 1.5 -> 0.5 + (activityRatio - 1.5) * 0.05
        activityRatio < 0.5 -> 1.5 + (0.5 - activityRatio) * 0.05
        else -> 1.0
    }

    // 4️⃣ **Ajustement basé sur le nombre de pas**
    stepCount?.let {
        if (it > 500) {
            dynamicPeakTime += it * 0.015 // Ajustement proportionnel plus agressif
        } else if (it < 100) {
            dynamicPeakTime *= 0.9 // Réduction du peakTime si peu de mouvement
        }
    }

    // 5️⃣ **Ajustement basé sur le rythme cardiaque**
    heartRate?.let {
        if (it > 110) {
            dynamicPeakTime *= 1.15 // Augmenter le peakTime de 15% si FC élevée
        } else if (it < 55) {
            dynamicPeakTime *= 0.85 // Réduire le peakTime de 15% si FC basse
        }
    }

    // 6️⃣ **Corrélation entre pas et rythme cardiaque**
    if (stepCount != null && heartRate != null) {
        if (stepCount > 1000 && heartRate > 110) {
            dynamicPeakTime *= 1.2 // Augmenter peakTime si activité intense
        } else if (stepCount < 200 && heartRate < 50) {
            dynamicPeakTime *= 0.75 // Réduction plus forte si repos total
        }
    }

    this.peakintermediaire = dynamicPeakTime

    // 7️⃣ **Ajustement basé sur le retard capteur (sensor lag) et historique**
    if (dynamicPeakTime > 40) {
        if (sensorLagActivity > historicActivity) {
            dynamicPeakTime *= 0.85
        } else if (sensorLagActivity < historicActivity) {
            dynamicPeakTime *= 1.2
        }
    }

    // 🔥 **Limiter le peakTime à des valeurs réalistes (35-120 min)**
    return dynamicPeakTime.coerceIn(35.0, 120.0)
}

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
    }

    private fun parseNotes(context: Context,startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""
        val recentNotes2: MutableList<String> = mutableListOf()
        val autoNote = determineNoteBasedOnBg(bg,context)
        recentNotes2.add(autoNote)
        notes += autoNote  // Ajout de la note auto générée

        recentNotes?.forEach { note ->
            if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                val noteText = note.note.lowercase()
                if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") || noteText.contains("bfast") || noteText.contains("lunch") || noteText.contains("dinner") ||
                    noteText.contains("lowcarb") || noteText.contains("highcarb") || noteText.contains("meal") || noteText.contains("fasting") ||
                    noteText.contains("low treatment") || noteText.contains("less aggressive") ||
                    noteText.contains("more aggressive") || noteText.contains("too aggressive") ||
                    noteText.contains("normal")) {

                    notes += if (notes.isEmpty()) recentNotes2 else " "
                    notes += note.note
                    recentNotes2.add(note.note)
                }
            }
        }

        notes = processNotesAndCleanUp(notes)
        return notes
    }

    @SuppressLint("NewApi", "DefaultLocale") fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        // On définit fromTime pour couvrir une longue période (par exemple, les 7 derniers jours)
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
// Récupération des événements de changement de cannule
        val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)

// Calcul de l'âge du site en jours
        val pumpAgeDays: Float = if (siteChanges.isNotEmpty()) {
            // On suppose que la liste est triée par ordre décroissant (le plus récent en premier)
            val latestChangeTimestamp = siteChanges.last().timestamp
            ((System.currentTimeMillis() - latestChangeTimestamp).toFloat() / (1000 * 60 * 60 * 24))
        } else {
            // Si aucun changement n'est enregistré, vous pouvez définir une valeur par défaut
            0f
        }

        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : on combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        val tp = calculateDynamicPeakTime(
            currentActivity = profile.currentActivity,
            futureActivity = profile.futureActivity,
            sensorLagActivity = profile.sensorLagActivity,
            historicActivity = profile.historicActivity,
            profile,
            recentSteps15Minutes,
            averageBeatsPerMinute.toInt(),
            bg,
            combinedDelta.toDouble()
        )

        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)
        val calendarInstance = Calendar.getInstance()
        this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        this.bg = glucose_status.glucose
        val getlastBolusSMB = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
        val lastBolusSMBTime = getlastBolusSMB?.timestamp ?: 0L
        //val lastBolusSMBMinutes = lastBolusSMBTime / 60000
        this.lastBolusSMBUnit = getlastBolusSMB?.amount?.toFloat() ?: 0.0F
        val diff = abs(now - lastBolusSMBTime)
        this.lastsmbtime = (diff / (60 * 1000)).toInt()
        this.maxIob = preferences.get(DoubleKey.ApsSmbMaxIob)
// Tarciso Dynamic Max IOB
        var DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Calcul initial avec un ajustement dynamique basé sur bg et delta
        DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Sécurisation : imposer une borne minimale et une borne maximale
        DinMaxIob = DinMaxIob.coerceAtLeast(1.0f).coerceAtMost(maxIob.toFloat() * 1.3f)

// Réduction de l'augmentation si on est la nuit (0h-6h)
        if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
            DinMaxIob = DinMaxIob.coerceAtMost(maxIob.toFloat())
        }

        this.maxIob = if (autodrive) DinMaxIob.toDouble() else maxIob
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        // Calcul initial avec ajustement basé sur la glycémie et le delta
        var DynMaxSmb = ((bg / 200) * (bg / 100) + (combinedDelta / 2)).toFloat()

// ⚠ Sécurisation : bornes min/max pour éviter des valeurs extrêmes
        DynMaxSmb = DynMaxSmb.coerceAtLeast(0.1f).coerceAtMost(maxSMBHB.toFloat() * 2.5f)

// ⚠ Ajustement si delta est négatif (la glycémie baisse) pour éviter un SMB trop fort
        if (combinedDelta < 0) {
            DynMaxSmb *= 0.75f // Réduction de 25% si la glycémie baisse
        }

// ⚠ Réduction nocturne pour éviter une surcorrection pendant le sommeil (0h - 6h)
        if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
            DynMaxSmb *= 0.6f
        }

// ⚠ Alignement avec `maxSMB` et `profile.peakTime`
        DynMaxSmb = DynMaxSmb.coerceAtMost(maxSMBHB.toFloat() * (tp / 60.0).toFloat())


        //val DynMaxSmb = (bg / 200) * (bg / 100) + (delta / 2)
        val enableUAM = profile.enableUAM

        this.maxSMBHB = if (autodrive && !honeymoon) DynMaxSmb.toDouble() else preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >=1.4 || bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) maxSMBHB else maxSMB
        this.tir1DAYabove = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.abovePct()!!
        val tir1DAYIR = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.inRangePct()!!
        this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
        this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
        this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!
        this.lastHourTIRLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0,140.0))?.belowPct()!!
        val lastHourTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0))?.abovePct()
        this.lastHourTIRLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,140.0))?.belowPct()!!
        this.lastHourTIRabove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,170.0))?.abovePct()!!
        this.lastHourTIRabove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,120.0))?.abovePct()!!
        val tirbasal3IR = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.inRangePct()
        val tirbasal3B = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.belowPct()
        val tirbasal3A = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.abovePct()
        val tirbasalhAP = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0))?.abovePct()
        //this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        this.now = System.currentTimeMillis()
        automateDeletionIfBadDay(tir1DAYIR.toInt())

        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0
        var lastCarbTimestamp = mealData.lastCarbTime
        if (lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = persistenceLayer.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }
        this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toInt()

        this.futureCarbs = persistenceLayer.getFutureCob().toFloat()
        if (lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = persistenceLayer.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
        }

        val fourHoursAgo = now - 4 * 60 * 60 * 1000
        this.recentNotes = persistenceLayer.getUserEntryDataFromTime(fourHoursAgo).blockingGet()

        this.tags0to60minAgo = parseNotes(context,0, 60)
        this.tags60to120minAgo = parseNotes(context,60, 120)
        this.tags120to180minAgo = parseNotes(context,120, 180)
        this.tags180to240minAgo = parseNotes(context,180, 240)
        this.delta = glucose_status.delta.toFloat()
        this.shortAvgDelta = glucose_status.shortAvgDelta.toFloat()
        this.longAvgDelta = glucose_status.longAvgDelta.toFloat()
        val bgAcceleration = glucose_status.bgAcceleration ?: 0f
        this.bgacc = bgAcceleration.toDouble()
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents()
        }
        val deleteEventDate = therapy.deleteEventDate
        val deleteTime = therapy.deleteTime
        if (deleteTime) {
            //removeLastNLines(100)
            //createFilteredAndSortedCopy(csvfile,deleteEventDate.toString())
            removeLast200Lines(csvfile)
        }
        this.sleepTime = therapy.sleepTime
        this.snackTime = therapy.snackTime
        this.sportTime = therapy.sportTime
        this.lowCarbTime = therapy.lowCarbTime
        this.highCarbTime = therapy.highCarbTime
        this.mealTime = therapy.mealTime
        this.bfastTime = therapy.bfastTime
        this.lunchTime = therapy.lunchTime
        this.dinnerTime = therapy.dinnerTime
        this.fastingTime = therapy.fastingTime
        this.stopTime = therapy.stopTime
        this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
        this.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
        this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
        this.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
        this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
        this.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
        this.iscalibration = therapy.calibrationTime
        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 180) 1 else 0
        val AutodriveAcceleration = preferences.get(DoubleKey.OApsAIMIAutodriveAcceleration)
        val nightbis = hourOfDay <= 7
        val modesCondition = !mealTime && !lunchTime && !bfastTime && !dinnerTime && !sportTime && !snackTime && !highCarbTime && !sleepTime && !lowCarbTime
        //val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val hourpbolusAS = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pbolusAS = when (hourpbolusAS) {
            in 0..10 -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusMorning)
            in 11..17 -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusAfternoon)
            else -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusEvening)
        }


        if (bg > 100 && predictedBg > 140 && !nightbis && !hasReceivedPbolusMInLastHour(pbolusAS) && autodrive && detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && modesCondition) {
            rT.units = pbolusAS //original >110
            //rT.reason.appendLine("Autodrive early meal detection/snack: Microbolusing ${pbolusAS}U, CombinedDelta : ${combinedDelta}, Predicted : ${predicted}, Acceleration : ${bgAcceleration}.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_autodrive_1),
                    pbolusAS,
                    //combinedDelta,
                    //predicted,
                    //bgAcceleration
                    combinedDelta.toFloat(), // Assicurati che combinedDelta sia un numero
                    predicted.toFloat(), // Assicurati che predicted sia un numero
                    bgAcceleration.toDouble() // Assicurati che bgAcceleration sia un numero
                )
            )
            return rT
        }
        if (isMealModeCondition()){
             val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
                 rT.units = pbolusM
                // rT.reason.appendLine("Microbolusing Meal Mode ${pbolusM}U.")
                rT.reason.appendLine(
                    String.format(
                       context.getString(R.string.table_plugin_mealmodes_prebolus_1),
                      pbolusM
                 )
                )

            return rT
         }
        if (!nightbis && isAutodriveModeCondition(targetBg, delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat()) && modesCondition && bgAcceleration.toDouble() >= AutodriveAcceleration){
            //val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            val pbolusAMorning = preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusMorning)
            val pbolusAAfternoon = preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusAfternoon)
            val pbolusAEvening = preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusEvening)

            val pbolusA: Double = when (hour) {
                in 0..10 -> pbolusAMorning
                in 11..17 -> pbolusAAfternoon
                else -> pbolusAEvening
            }
            rT.units = pbolusA
            //rT.reason.appendLine("Microbolusing Autodrive Mode ${pbolusA}U. TargetBg : ${targetBg}, CombinedDelta : ${combinedDelta}, Slopemindeviation : ${mealData.slopeFromMinDeviation}, Acceleration : ${bgAcceleration}. ")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_autodrive_2),
                    pbolusA,
                    targetBg.toFloat(), // Assicurati che targetBg sia un numero, altrimenti usa .toFloat() o .toDouble()
                    combinedDelta.toFloat(), // Assicurati che combinedDelta sia un numero
                    mealData.slopeFromMinDeviation.toFloat(), // Assicurati che slopeFromMinDeviation sia un numero
                    bgAcceleration.toDouble() // Assicurati che bgAcceleration sia un numero

                )
            )

            return rT
        }
        if (isbfastModeCondition()){
            val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            rT.units = pbolusbfast
            //rT.reason.appendLine("Microbolusing 1/2 Breakfast Mode ${pbolusbfast}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_2),
                    pbolusbfast
                )
            )

            return rT
        }
        if (isbfast2ModeCondition()){
            val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            this.maxSMB = pbolusbfast2
            rT.units = pbolusbfast2
            //rT.reason.appendLine("Microbolusing 2/2 Breakfast Mode ${pbolusbfast2}U. ")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_3),
                    pbolusbfast2
                )
            )

            return rT
        }
        if (isLunchModeCondition()){
            val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
                rT.units = pbolusLunch
                //rT.reason.appendLine("Microbolusing 1/2 Lunch Mode ${pbolusLunch}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_4),
                    pbolusLunch
                )
            )
            return rT
        }
        if (isLunch2ModeCondition()){
            val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            this.maxSMB = pbolusLunch2
            rT.units = pbolusLunch2
            //rT.reason.appendLine("Microbolusing 2/2 Lunch Mode ${pbolusLunch2}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_5),
                    pbolusLunch2
                )
            )

            return rT
        }
        if (isDinnerModeCondition()){
            val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            rT.units = pbolusDinner
            //rT.reason.appendLine("Microbolusing 1/2 Dinner Mode ${pbolusDinner}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_6),
                    pbolusDinner
                )
            )
            return rT
        }
        if (isDinner2ModeCondition()){
            val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            this.maxSMB = pbolusDinner2
            rT.units = pbolusDinner2
            //rT.reason.appendLine("Microbolusing 2/2 Dinner Mode ${pbolusDinner2}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_7),
                    pbolusDinner2
                )
            )
            return rT
        }
        if (isHighCarbModeCondition()){
            val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            rT.units = pbolusHC
            //rT.reason.appendLine("Microbolusing High Carb Mode ${pbolusHC}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_8),
                    pbolusHC
                )
            )
            return rT
        }
        if (issnackModeCondition()){
            val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            rT.units = pbolussnack
            //rT.reason.appendLine("Microbolusing snack Mode ${pbolussnack}U.")
            rT.reason.appendLine(
                String.format(
                    context.getString(R.string.table_plugin_mealmodes_prebolus_9),
                    pbolussnack
                )
            )
            return rT
        }

        var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
        nowMinutes = (kotlin.math.round(nowMinutes * 100) / 100)  // Arrondi à 2 décimales
        val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
            (0.00016422 * nowMinutes.pow(4)) +
            (0.00128081 * nowMinutes.pow(3)) +
            (0.02533782 * nowMinutes.pow(2)) -
            (0.33275556 * nowMinutes) +
            1.38581503

        val circadianSmb = kotlin.math.round(
            ((0.00000379 * delta * nowMinutes.pow(5)) -
                (0.00016422 * delta * nowMinutes.pow(4)) +
                (0.00128081 * delta * nowMinutes.pow(3)) +
                (0.02533782 * delta * nowMinutes.pow(2)) -
                (0.33275556 * delta * nowMinutes) +
                1.38581503) * 100
        ) / 100  // Arrondi à 2 décimales
        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        val profile_current_basal = roundBasal(profile.current_basal)
        var basal: Double

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        //bg = glucose_status.glucose.toFloat()
        //this.bg = bg.toFloat()
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }

        // TODO eliminate
        //val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver
        val max_iob = maxIob
        //this.maxIob = max_iob
        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = if (honeymoon) 130 else 100

        val halfBasalTarget = profile.half_basal_exercise_target

        when {
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 && predictedBg < 140 -> {
                this.targetBg = 130.0f
            }
            !profile.temptargetSet && predictedBg >= 120 && combinedDelta > 3 -> {
                var baseTarget = if (honeymoon) 110.0 else 70.0
                if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22){
                    baseTarget = if (honeymoon) 110.0 else 90.0
                }
                var hyperTarget = max(baseTarget, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
                hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
                hyperTarget = max(hyperTarget, baseTarget.toInt())

                this.targetBg = hyperTarget.toFloat()
                target_bg = hyperTarget.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
            !profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120 -> {
                val baseHypoTarget = if (honeymoon) 130.0 else 110.0
                val hypoTarget = baseHypoTarget * max(1.0, circadianSensitivity)
                this.targetBg = min(hypoTarget.toFloat(), 166.0f)
                target_bg = targetBg.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
            else -> {
                val defaultTarget = profile.target_bg
                this.targetBg = defaultTarget.toFloat()
                target_bg = targetBg.toDouble()
            }
        }
        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleLog.add("Autosens ratio: $sensitivityRatio; ")
        }
        basal = profile.current_basal * sensitivityRatio
        basal = roundBasal(basal)
        if (basal != profile_current_basal)
            consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
        else
            consoleLog.add("Basal unchanged: $basal; ")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            consoleLog.add("Temp Target set, not adjusting with autosens")
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg
            }
        }

        var iob2 = 0.0f
        val iobArray = iob_data_array
        val iob_data = iobArray[0]
        this.iob = iob_data.iob.toFloat()
        if (iob_data.basaliob < 0) {
            iob2 = -iob_data.basaliob.toFloat()+ iob
            this.iob = iob2
        }

        val tick: String = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        // val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))
        val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
        var tdd7Days = profile.TDD
        if (tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P
        this.tdd7DaysPerHour = (tdd7Days / 24).toFloat()

        var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tdd2Days == 0.0f || tdd2Days < tdd7P) tdd2Days = tdd7P.toFloat()
        this.tdd2DaysPerHour = tdd2Days / 24

        var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f || tddDaily < tdd7P / 2) tddDaily = tdd7P.toFloat()
        this.tddPerHour = tddDaily / 24

        var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()

        this.tdd24HrsPerHour = tdd24Hrs / 24
        var sens = profile.variable_sens
        this.variableSensitivity = sens.toFloat()
        consoleError.add("CR:${profile.carb_ratio}")
        this.predictedBg = predictEventualBG(bg.toFloat(), iob, variableSensitivity, minDelta.toFloat(), shortAvgDelta, longAvgDelta, mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime, snackTime, honeymoon )
        //val insulinEffect = calculateInsulinEffect(bg.toFloat(),iob,variableSensitivity,cob,normalBgThreshold,recentSteps180Minutes,averageBeatsPerMinute.toFloat(),averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat())

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis10 = now - 10 * 60 * 1000 // 10 minutes en millisecondes
        val timeMillis15 = now - 15 * 60 * 1000 // 15 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis60 = now - 60 * 60 * 1000 // 60 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(timeMillis180, now)

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
        allStepsCounts.forEach { stepCount ->
            val timestamp = stepCount.timestamp
            if (timestamp >= timeMillis5) {
                this.recentSteps5Minutes = stepCount.steps5min
            }
            if (timestamp >= timeMillis10) {
                this.recentSteps10Minutes = stepCount.steps10min
            }
            if (timestamp >= timeMillis15) {
                this.recentSteps15Minutes = stepCount.steps15min
            }
            if (timestamp >= timeMillis30) {
                this.recentSteps30Minutes = stepCount.steps30min
            }
            if (timestamp >= timeMillis60) {
                this.recentSteps60Minutes = stepCount.steps60min
            }
            if (timestamp >= timeMillis180) {
                this.recentSteps180Minutes = stepCount.steps180min
            }
        }
        }else{
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val heartRates5 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis5,now)
            this.averageBeatsPerMinute = heartRates5.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute = 80.0
        }
        try {
            val heartRates10 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis10,now)
            this.averageBeatsPerMinute10 = heartRates10.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute10 = 80.0
        }
        try {
            val heartRates60 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis60,now)
            this.averageBeatsPerMinute60 = heartRates60.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute60 = 80.0
        }
        try {

            val heartRates180 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis180,now)
            this.averageBeatsPerMinute180 = heartRates180.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute180 = 80.0
        }
        if (tdd7Days.toFloat() != 0.0f) {
            basalaimi = (tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat()
        }
        this.basalaimi = calculateSmoothBasalRate(tdd7P.toFloat(),tdd7Days.toFloat(),basalaimi)
        if (tdd7Days.toFloat() != 0.0f) {
            this.ci = (450 / tdd7Days).toFloat()
        }

        val choKey: Double = preferences.get(DoubleKey.OApsAIMICHO)
        if (ci != 0.0f && ci != Float.POSITIVE_INFINITY && ci != Float.NEGATIVE_INFINITY) {
            this.aimilimit = (choKey / ci).toFloat()
        } else {
            this.aimilimit = (choKey / profile.carb_ratio).toFloat()
        }
        val timenow = LocalTime.now().hour
        val sixAMHour = LocalTime.of(6, 0).hour

        val pregnancyEnable = preferences.get(BooleanKey.OApsAIMIpregnancy)

        if (tirbasal3B != null && pregnancyEnable && tirbasal3IR != null) {
            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5 -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()
                timenow < sixAMHour -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    (basalaimi * multiplier).toFloat()
                }
                timenow > sixAMHour -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    (basalaimi * multiplier).toFloat()
                }
                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0 -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70 -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5 -> (basalaimi * 0.85).toFloat()
                else -> basalaimi
            }
        }

        this.basalaimi = if (honeymoon && basalaimi > profile_current_basal * 2) (profile_current_basal.toFloat() * 2) else basalaimi

        this.basalaimi = if (basalaimi < 0.0f) 0.0f else basalaimi

        this.variableSensitivity = if (honeymoon) {
            if (bg < 150) {
                profile.sens.toFloat() * 1.2f // Légère augmentation pour honeymoon en cas de BG bas
            } else {
                max(
                    profile.sens.toFloat() / 3.0f, // Réduction plus forte en honeymoon
                    sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
                )
            }
        } else {
            if (bg < 100) {
                // 🔹 Correction : Permettre une légère adaptation de l’ISF même en dessous de 100 mg/dL
                profile.sens.toFloat() * 1.1f
            } else if (bg > 120) {
                // 🔹 Si BG > 120, on applique une réduction progressive plus forte
                max(
                    profile.sens.toFloat() / 5.0f,  // 🔥 Réduction plus agressive (divisé par 5)
                    sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
                )
            } else {
                // 🔹 Plage intermédiaire (100-120) avec ajustement plus doux
                sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
            }
        }

// 🔹 Ajustement basé sur l'activité physique : correction plus fine des valeurs
        if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10
            || recentSteps180Minutes > 1500 && bg < 130 && delta < 10) {

            this.variableSensitivity *= 1.3f //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat() // Réduction du facteur d’augmentation
        }

// 🔹 Réduction du boost si l’activité est modérée pour éviter une ISF excessive
        if (recentSteps30Minutes > 500 && recentSteps5Minutes in 1..99 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.2f //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
        }

// 🔹 Sécurisation des bornes minimales et maximales
        this.variableSensitivity = this.variableSensitivity.coerceIn(5.0f, 300.0f)


        sens = variableSensitivity.toDouble()
        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }
        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG = round(bg - (iob_data.iob * sens), 0)
        // and adjust it for the deviation above
        this.eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }

        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel()
        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convertBG(threshold)} to ${convertBG(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }
        this.predictedSMB = modelcal

        if (preferences.get(BooleanKey.OApsAIMIMLtraining) && csvfile.exists()){
            val allLines = csvfile.readLines()
            val minutesToConsider = 2500.0
            val linesToConsider = (minutesToConsider / 5).toInt()
            if (allLines.size > linesToConsider) {
                val refinedSMB = neuralnetwork5(combinedDelta.toFloat(), shortAvgDelta, longAvgDelta, predictedSMB, profile)
                this.predictedSMB = refinedSMB
                if (bg > 200 && delta > 4 && iob < preferences.get(DoubleKey.ApsSmbMaxIob) ) {
                    this.predictedSMB *= 1.7f // Augmente de 70% si montée très rapide
                } else if (bg > 180 && delta > 3 && iob < preferences.get(DoubleKey.ApsSmbMaxIob)) {
                    this.predictedSMB *= 1.5f // Augmente de 50% si montée modérée
                }

                basal =
                    when {
                        (honeymoon && bg < 170) -> basalaimi * 0.65
                        else -> basalaimi.toDouble()
                    }
                basal = roundBasal(basal)
            }
            rT.reason.append("csvfile ${csvfile.exists()}")
        }else {
            rT.reason.append("ML Decision data training","ML decision has no enough data to refine the decision")
        }

        var smbToGive = if (bg > 130  && delta > 2 && predictedSMB == 0.0f) modelcal else predictedSMB
        smbToGive = if (honeymoon && bg < 170) smbToGive * 0.8f else smbToGive

        val morningfactor: Double = preferences.get(DoubleKey.OApsAIMIMorningFactor) / 100.0
        val afternoonfactor: Double = preferences.get(DoubleKey.OApsAIMIAfternoonFactor) / 100.0
        val eveningfactor: Double = preferences.get(DoubleKey.OApsAIMIEveningFactor) / 100.0
        val hyperfactor: Double = preferences.get(DoubleKey.OApsAIMIHyperFactor) / 100.0
        val highcarbfactor: Double = preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0
        val mealfactor: Double = preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0
        val bfastfactor: Double = preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0
        val lunchfactor: Double = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0
        val dinnerfactor: Double = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0
        val snackfactor: Double = preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0
        val sleepfactor: Double = preferences.get(DoubleKey.OApsAIMIsleepFactor) / 100.0

        val adjustedFactors = adjustFactorsBasedOnBgAndHypo(
                morningfactor.toFloat(), afternoonfactor.toFloat(), eveningfactor.toFloat()
            )

        val (adjustedMorningFactor, adjustedAfternoonFactor, adjustedEveningFactor) = adjustedFactors

        // Appliquer les ajustements en fonction de l'heure de la journée
        smbToGive = when {
            bg > 160 && delta > 4 && iob < 0.7 && honeymoon && smbToGive == 0.0f && LocalTime.now().run { (hour in 23..23 || hour in 0..10) } -> 0.15f
            bg > 120 && delta > 8 && iob < 1.0 && !honeymoon && smbToGive < 0.05f                                                            -> profile_current_basal.toFloat()
            highCarbTime                                                                                                                     -> smbToGive * highcarbfactor.toFloat()
            mealTime                                                                                                                         -> smbToGive * mealfactor.toFloat()
            bfastTime                                                                                                                        -> smbToGive * bfastfactor.toFloat()
            lunchTime                                                                                                                        -> smbToGive * lunchfactor.toFloat()
            dinnerTime                                                                                                                       -> smbToGive * dinnerfactor.toFloat()
            snackTime                                                                                                                        -> smbToGive * snackfactor.toFloat()
            sleepTime                                                                                                                        -> smbToGive * sleepfactor.toFloat()
            hourOfDay in 1..11                                                                                                         -> smbToGive * adjustedMorningFactor.toFloat()
            hourOfDay in 12..18                                                                                                        -> smbToGive * adjustedAfternoonFactor.toFloat()
            hourOfDay in 19..23                                                                                                        -> smbToGive * adjustedEveningFactor.toFloat()
            bg > 120 && delta > 7 && !honeymoon                                                                                              -> smbToGive * hyperfactor.toFloat()
            bg > 180 && delta > 5 && iob < 1.2 && honeymoon                                                                                  -> smbToGive * hyperfactor.toFloat()
            else -> smbToGive
        }
        rT.reason.append("adjustedMorningFactor $adjustedMorningFactor")
        rT.reason.append("adjustedAfternoonFactor $adjustedAfternoonFactor")
        rT.reason.append("adjustedEveningFactor $adjustedEveningFactor")
        val factors = when {
            lunchTime -> lunchfactor
            bfastTime -> bfastfactor
            dinnerTime -> dinnerfactor
            snackTime -> snackfactor
            sleepTime -> sleepfactor
            hourOfDay in 1..11 -> adjustedMorningFactor
            hourOfDay in 12..18 -> adjustedAfternoonFactor
            hourOfDay in 19..23 -> adjustedEveningFactor
            highCarbTime -> highcarbfactor
            mealTime -> mealfactor
            bg > 120 && delta > 7 && !honeymoon -> hyperfactor
            else -> 1.0
        }
        val currentHour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        // Calcul du DIA ajusté en minutes
        val adjustedDIAInMinutes = calculateAdjustedDIA(
            baseDIAHours = profile.dia.toFloat(),
            currentHour = currentHour,
            recentSteps5Minutes = recentSteps5Minutes,
            currentHR = averageBeatsPerMinute.toFloat(),
            averageHR60 = averageBeatsPerMinute60.toFloat(),
            pumpAgeDays = pumpAgeDays
        )
        consoleLog.add("DIA ajusté (en minutes) : $adjustedDIAInMinutes")
        val actCurr = profile.sensorLagActivity
        val actFuture = profile.futureActivity
        val td = adjustedDIAInMinutes
        val deltaGross = round((glucose_status.delta + actCurr * sens).coerceIn(0.0, 35.0), 1)
        val actTarget = deltaGross / sens * factors.toFloat()
        var actMissing = 0.0
        var deltaScore: Double = 0.5

        if (glucose_status.delta <= 4.0) {

            actMissing = round((actCurr * smbToGive - Math.max(actFuture, 0.0)) / 5, 4)
            deltaScore = ((bg - target_bg) / 100).coerceIn(0.0, 1.0)
        } else {
            actMissing = round((actTarget - Math.max(actFuture, 0.0)) / 5, 4)
        }


        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val S = 1 / (1 - a + (1 + a) * Math.exp((-td / tau)))
        var AimiInsReq = actMissing / (S / Math.pow(tau, 2.0) * tp * (1 - tp / td) * Math.exp((-tp / tau)))

        AimiInsReq = if (AimiInsReq < smbToGive) AimiInsReq else smbToGive.toDouble()

        val finalInsulinDose = round(AimiInsReq, 2)
        // ===== Intégration du module MPC et du correctif PI =====
// Exemple d’optimisation simple sur la dose basale candidate

// Définition des bornes (par exemple de 0.0 à la basale courante maximale ou une valeur fixée)
        val doseMin = 0.0
        val doseMax = maxSMB
// Paramètres pour le module prédictif
        val horizon = 30  // horizon en minutes
        val insulinSensitivity = variableSensitivity.toDouble()  // conversion si nécessaire

// On utilise une recherche itérative simple pour trouver la dose qui minimise le coût
        var optimalDose = doseMin
        var bestCost = Double.MAX_VALUE
        val nSteps = 20  // nombre de pas d’échantillonnage entre doseMin et doseMax

        for (i in 0..nSteps) {
            val candidate = doseMin + i * (doseMax - doseMin) / nSteps
            val cost = costFunction(basal, bg.toDouble(), targetBg.toDouble(), horizon, insulinSensitivity, smbToGive.toDouble())
            if (cost < bestCost) {
                bestCost = cost
                optimalDose = candidate
            }
        }

// Correction en boucle fermée avec un simple contrôleur PI
        val error = bg.toDouble() - targetBg.toDouble()  // erreur actuelle
        val Kp = 0.1  // gain proportionnel (à calibrer)
        val correction = -Kp * error

        val optimalBasalMPC = optimalDose + correction

// On loggue ces valeurs pour debug
        consoleLog.add("Module MPC: dose candidate = ${optimalDose}, correction = ${correction}, optimalBasalMPC = ${optimalBasalMPC}")

// On peut maintenant utiliser cette dose pour ajuster la décision.
        smbToGive = optimalBasalMPC.toFloat()
// ===== Fin de l’intégration du module MPC =====
        smbToGive = applySafetyPrecautions(mealData,finalInsulinDose.toFloat())
        smbToGive = roundToPoint05(smbToGive)

        logDataMLToCsv(predictedSMB, smbToGive)
        logDataToCsv(predictedSMB, smbToGive)

        //logDataToCsv(predictedSMB, smbToGive)
        //logDataToCsvHB(predictedSMB, smbToGive)

        rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = variableSensitivity.toDouble()
        )

        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI.toFloat()
        }
        var remainingCATimeMin = 2.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        var remainingCATime = remainingCATimeMin
        val totalCI = max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, mealData.mealCOB - totalCA)
        remainingCarbs = min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        val slopeFromMaxDeviation = mealData.slopeFromMaxDeviation
        val slopeFromMinDeviation = mealData.slopeFromMinDeviation
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()

        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        val aci = 8
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, mealData.mealCOB * csf / ci))
        }
        val acid = max(0.0, mealData.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        consoleError.add("Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0

        var minUAMPredBG = 999.0
        var minGuardBG: Double

        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg

        val lastIOBpredBG: Double

        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null


        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(IOBpredBGs[IOBpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =
                if (dynIsfMode) round((-iobTick.iobWithZeroTemp!!.activity * (1800 / (profile.TDD * (ln((max(ZTpredBGs[ZTpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(UAMpredBGs[UAMpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 24) IOBpredBGs.add(IOBpredBG)
            if (UAMpredBGs.size < 24) UAMpredBGs.add(UAMpredBG)
            if (ZTpredBGs.size < 24) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (UAMpredBG < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            this.insulinPeakTime = tp
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            if (enableUAM && UAMpredBGs.size > 6 && (UAMpredBG < minUAMPredBG)) minUAMPredBG = round(UAMpredBG, 0)
        }

        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }

        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }
        //fin predictions
        ////////////////////////////////////////////
        //estimation des glucides nécessaires si risque hypo
        val thresholdBG = 70.0
        val carbsRequired = estimateRequiredCarbs(bg, targetBg.toDouble(), slopeFromDeviations, iob.toDouble(), csf,sens, cob.toDouble())
        val minutesAboveThreshold = calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
        if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !lunchTime && !dinnerTime && !bfastTime && !highCarbTime && !mealTime) {
            rT.carbsReq = carbsRequired
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsRequired add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        val forcedBasalmealmodes = preferences.get(DoubleKey.meal_modes_MaxBasal)
        //val forcedBasal = preferences.get(DoubleKey.autodriveMaxBasal)
        val hourforcedBasal = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val forcedBasal = when (hourforcedBasal) {
            in 1..11 -> preferences.get(DoubleKey.autodriveMaxBasalMorning)
            in 12..18 -> preferences.get(DoubleKey.autodriveMaxBasalAfternoon)
            else -> preferences.get(DoubleKey.autodriveMaxBasalEvening)
        }

/* var rate = when {
     detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && !mealTime && !lunchTime && !bfastTime && !dinnerTime && !sportTime && !snackTime && !highCarbTime && !sleepTime && !lowCarbTime -> calculateRate(forcedBasal, profile_current_basal, 1.0, "Early Meal detection: activation d'une basale maximale pendant 30 minutes.", currenttemp, rT)
     snackTime && snackrunTime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because snackTime $snackrunTime.", currenttemp, rT)
     mealTime && mealruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because mealTime $mealruntime.", currenttemp, rT)
     bfastTime && bfastruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bfastTime $bfastruntime.", currenttemp, rT)
     lunchTime && lunchruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because lunchTime $lunchruntime.", currenttemp, rT)
     dinnerTime && dinnerruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because dinnerTime $dinnerruntime.", currenttemp, rT)
     highCarbTime && highCarbrunTime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because highcarb $highcarbfactor.", currenttemp, rT)
     fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
     sportTime && bg > 169 && delta > 4 -> calculateRate(profile_current_basal, profile_current_basal, 1.3, "AI Force basal because sportTime && bg > 170", currenttemp, rT)
     //!honeymoon && delta in 0.0 .. 7.0 && bg in 81.0..111.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because bg lesser than 110 and delta lesser than 8", currenttemp, rT)
     honeymoon && delta in 0.0.. 6.0 && bg in 99.0..141.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because honeymoon and bg lesser than 140 and delta lesser than 6", currenttemp, rT)
     bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon -> calculateRate(basal, profile_current_basal, 1.0, "AI Force basal because bg is between 80 and 100 with a small delta.", currenttemp, rT)
     //bg > 145 &&detectMealOnset delta > 0 && smbToGive == 0.0f && !honeymoon -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bg is greater than 145 and SMB = 0U.", currenttemp, rT)
     bg > 120 && delta > 0 && smbToGive == 0.0f && honeymoon -> calculateRate(basal, profile_current_basal, 5.0, "AI Force basal because bg is greater than 120 and SMB = 0U.", currenttemp, rT)
     else -> null
 }
 rate?.let {
     rT.rate = it
     rT.deliverAt = deliverAt
     rT.duration = 30
     return rT
 } */

 // var rate = when {
 //     detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && !night && !mealTime && !lunchTime && !bfastTime && !dinnerTime && !sportTime && !snackTime && !highCarbTime && !sleepTime && !lowCarbTime -> calculateRate(forcedBasal, profile_current_basal, 1.0, "Early Meal detection: activation d'une basale maximale pendant 30 minutes.", currenttemp, rT)
 //     snackTime && snackrunTime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because snackTime $snackrunTime.", currenttemp, rT)
 //     mealTime && mealruntime in 0..30 && delta < 10 -> calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "AI Force basal because mealTime $mealruntime.", currenttemp, rT)
 //     bfastTime && bfastruntime in 0..30 && delta < 10 -> calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "AI Force basal because bfastTime $bfastruntime.", currenttemp, rT)
 //     lunchTime && lunchruntime in 0..30 && delta < 10 -> calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "AI Force basal because lunchTime $lunchruntime.", currenttemp, rT)
 //     dinnerTime && dinnerruntime in 0..30 && delta < 10 -> calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "AI Force basal because dinnerTime $dinnerruntime.", currenttemp, rT)
 //     highCarbTime && highCarbrunTime in 0..30 && delta < 10 -> calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "AI Force basal because highcarb $highcarbfactor.", currenttemp, rT)
 //     fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
 //     sportTime && bg > 169 && delta > 4 -> calculateRate(profile_current_basal, profile_current_basal, 1.3, "AI Force basal because sportTime && bg > 170", currenttemp, rT)
 //     //!honeymoon && delta in 0.0 .. 7.0 && bg in 81.0..111.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because bg lesser than 110 and delta lesser than 8", currenttemp, rT)
 //     honeymoon && delta in 0.0.. 6.0 && bg in 99.0..141.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because honeymoon and bg lesser than 140 and delta lesser than 6", currenttemp, rT)
 //     bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon -> calculateRate(basal, profile_current_basal, 1.0, "AI Force basal because bg is between 80 and 100 with a small delta.", currenttemp, rT)
 //     //bg > 145 &&detectMealOnset delta > 0 && smbToGive == 0.0f && !honeymoon -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bg is greater than 145 and SMB = 0U.", currenttemp, rT)
 //     bg > 120 && delta > 0 && smbToGive == 0.0f && honeymoon -> calculateRate(basal, profile_current_basal, 5.0, "AI Force basal because bg is greater than 120 and SMB = 0U.", currenttemp, rT)
 //     else -> null
 // }
 // // rate?.let {
 // //     rT.rate = it
 // //     rT.deliverAt = deliverAt
 // //     rT.duration = 30
 // //     return rT
 // // }
 //        return setTempBasal(
 //            _rate             = rate!!.toDouble(),
 //            duration          = 30,
 //            profile           = profile,
 //            rT                = rT,
 //            currenttemp       = currenttemp,
 //            overrideSafetyLimits = true
 //        )

 val enableSMB = enablesmb(profile, microBolusAllowed, mealData, target_bg)


 rT.COB = mealData.mealCOB
 rT.IOB = iob_data.iob
 rT.reason.append(
     "COB: ${round(mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
         round(profile.carb_ratio, 2)
             .withoutZeros()
     }, Target: ${convertBG(target_bg)}}"
 )
  val (conditionResult, conditionsTrue) = isCriticalSafetyCondition(mealData)
 this.zeroBasalAccumulatedMinutes = getZeroBasalDuration(persistenceLayer,2)
 val screenWidth = preferences.get(IntKey.OApsAIMIlogsize)// Largeur d'écran par défaut en caractères si non spécifié
 val columnWidth = (screenWidth / 2) - 2 // Calcul de la largeur des colonnes en fonction de la largeur de l'écran
 val logTemplate = buildString {
     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "AAPS-MASTER-AIMI"))
     //appendLine(String.format("║ %-${screenWidth}s ║", "OpenApsAIMI Settings"))
     //appendLine(String.format("║ %-${screenWidth}s ║", "30 Avril 2025"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_1)))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_2)))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_3)))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "TIR Data"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_tir_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     //appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR Low", String.format("%.1f", currentTIRLow)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR In Range", String.format("%.1f", currentTIRRange)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR High", String.format("%.1f", currentTIRAbove)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR Low", String.format("%.1f", lastHourTIRLow)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR >120", String.format("%.1f", lastHourTIRabove120)))
     appendLine(String.format("║ %-${columnWidth}s │ %s%%", context.getString(R.string.table_plugin_tir_1), String.format("%.1f", currentTIRLow)))
     appendLine(String.format("║ %-${columnWidth}s │ %s%%", context.getString(R.string.table_plugin_tir_2), String.format("%.1f", currentTIRRange)))
     appendLine(String.format("║ %-${columnWidth}s │ %s%%", context.getString(R.string.table_plugin_tir_3), String.format("%.1f", currentTIRAbove)))
     appendLine(String.format("║ %-${columnWidth}s │ %s%%", context.getString(R.string.table_plugin_tir_4), String.format("%.1f", lastHourTIRLow)))
     appendLine(String.format("║ %-${columnWidth}s │ %s%%", context.getString(R.string.table_plugin_tir_5), String.format("%.1f", lastHourTIRabove120)))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Request"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_request_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Reason", "COB: $cob, Dev: $deviation, BGI: $bgi, ISF: $variableSensitivity, CR: $ci, Target: $target_bg"))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "SMB Prediction"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_smb_prediction_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     appendLine(String.format("║ %-${columnWidth}s │ %s u", "AI Pred.", String.format("%.2f", predictedSMB)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", "Req. SMB", String.format("%.2f", smbToGive)))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Adjusted Factors"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_adjusted_factors_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Factors", adjustedFactors))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Limits & Conditions"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_limits_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
    /* appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max IOB", String.format("%.1f", maxIob)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB", String.format("%.1f", iob)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB2", String.format("%.1f", iob2)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max SMB", String.format("%.1f", maxSMB)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Safety", conditionResult))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Met", conditionsTrue))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "peakTimeProfile", String.format("%.1f", profile.peakTime)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "currentActivity", String.format("%.1f", profile.currentActivity)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "After IOB Adjustment", String.format("%.1f", peakintermediaire)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Activity Ratio", String.format("%.1f", profile.futureActivity / (profile.currentActivity + 0.0001))))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Final Peak Time after coerceIn", String.format("%.1f", tp)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Adjusted Dia H", String.format("%.1f", adjustedDIAInMinutes/60)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "pumpAgeDays", String.format("%.1f", pumpAgeDays)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "zeroBasalAccumulatedMinutes", String.format("%.1f", zeroBasalAccumulatedMinutes.toDouble())))

     */

     appendLine(String.format("║ %-${columnWidth}s │ %s u", context.getString(R.string.table_plugin_limits_1), String.format("%.1f", maxIob)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", context.getString(R.string.table_plugin_limits_2), String.format("%.1f", iob)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", context.getString(R.string.table_plugin_limits_3), String.format("%.1f", iob2)))
     appendLine(String.format("║ %-${columnWidth}s │ %s u", context.getString(R.string.table_plugin_limits_4), String.format("%.1f", maxSMB)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_5), conditionResult))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_6), conditionsTrue))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_7), String.format("%.1f", profile.peakTime)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_8), String.format("%.1f", profile.currentActivity)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_9), String.format("%.1f", peakintermediaire)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_10), String.format("%.1f", profile.futureActivity / (profile.currentActivity + 0.0001))))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_11), String.format("%.1f", tp)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_12), String.format("%.1f", adjustedDIAInMinutes / 60)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_13), String.format("%.1f", pumpAgeDays)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_limits_14), String.format("%.1f", zeroBasalAccumulatedMinutes.toDouble())))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Glucose Data"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_glucose_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     /*appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Current BG", String.format("%.1f", bg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "predictedBg", String.format("%.1f", predictedBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Target BG", String.format("%.1f", targetBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Prediction", String.format("%.1f", predictedBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Eventual BG", String.format("%.1f", eventualBG)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Delta", String.format("%.1f", delta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "combinedDelta", String.format("%.1f", combinedDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Short Δ", String.format("%.1f", shortAvgDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "Long Δ", String.format("%.1f", longAvgDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMaxDeviation", String.format("%.1f", mealData.slopeFromMaxDeviation)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMinDeviation", String.format("%.1f", mealData.slopeFromMinDeviation)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", "bgAcceleration", String.format("%.1f", bgAcceleration)))

      */

     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", context.getString(R.string.table_plugin_glucose_1), String.format("%.1f", bg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", context.getString(R.string.table_plugin_glucose_2), String.format("%.1f", predictedBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", context.getString(R.string.table_plugin_glucose_3), String.format("%.1f", targetBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", context.getString(R.string.table_plugin_glucose_4), String.format("%.1f", predictedBg)))
     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", context.getString(R.string.table_plugin_glucose_5), String.format("%.1f", eventualBG)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_6), String.format("%.1f", delta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_7), String.format("%.1f", combinedDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_8), String.format("%.1f", shortAvgDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_9), String.format("%.1f", longAvgDelta)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_10), String.format("%.1f", mealData.slopeFromMaxDeviation)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_11), String.format("%.1f", mealData.slopeFromMinDeviation)))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_glucose_12), String.format("%.1f", bgAcceleration)))
     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()





     //appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Modes"))
     //appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_manual_modes_title)))
     //appendLine("╠${"═".repeat(screenWidth)}╣")
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Delete Time", if (deleteTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Date", deleteEventDate ?: "N/A"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Sleep", if (sleepTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Sport", if (sportTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Snack", if (snackTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Low Carb", if (lowCarbTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "High Carb", if (highCarbTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Meal", if (mealTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Breakfast", if (bfastTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Lunch", if (lunchTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Dinner", if (dinnerTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Fasting", if (fastingTime) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Calibration", if (iscalibration) "Active" else "Inactive"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_1), if (deleteTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_2), deleteEventDate ?: "N/A"))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_2), deleteEventDate ?: context.getString(R.string.table_plugin_manual_modes_na)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_3), if (sleepTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_4), if (sportTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_5), if (snackTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_6), if (lowCarbTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_7), if (highCarbTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_8), if (mealTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_9), if (bfastTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_10), if (lunchTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_11), if (dinnerTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_12), if (fastingTime) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_13), if (iscalibration) context.getString(R.string.table_plugin_manual_modes_active) else context.getString(R.string.table_plugin_manual_modes_inactive)))
     //appendLine("╚${"═".repeat(screenWidth)}╝")
     //appendLine()

     val shouldShowTable = deleteTime ||
         (deleteEventDate != null && deleteEventDate.lowercase() != "null" && deleteEventDate.isNotBlank()) ||
         sleepTime || sportTime || snackTime || lowCarbTime ||
         highCarbTime || mealTime || bfastTime || lunchTime ||
         dinnerTime || fastingTime || iscalibration

     if (shouldShowTable) {
         appendLine("╔${"═".repeat(screenWidth)}╗")
         appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_manual_modes_title)))
         appendLine("╠${"═".repeat(screenWidth)}╣")

         if (deleteTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_1), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         // Stampa la data solo se valida
         if (!deleteEventDate.isNullOrBlank() && deleteEventDate.lowercase() != "null") {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_2), deleteEventDate))
         }
         if (sleepTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_3), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (sportTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_4), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (snackTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_5), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (lowCarbTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_6), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (highCarbTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_7), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (mealTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_8), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (bfastTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_9), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (lunchTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_10), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (dinnerTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_11), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (fastingTime) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_12), context.getString(R.string.table_plugin_manual_modes_active)))
         }
         if (iscalibration) {
             appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_manual_modes_13), context.getString(R.string.table_plugin_manual_modes_active)))
         }

         appendLine("╚${"═".repeat(screenWidth)}╝")
         appendLine()
     }

     if (autodrive) {
         //val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
         val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
         val pbolusAS = when (hour) {
             in 1..11 -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusMorning)
             in 12..18 -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusAfternoon)
             else -> preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolusEvening)
         }
         //val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
         val pbolusA = when (hour) {
             in 1..11 -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusMorning)
             in 12..18 -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusAfternoon)
             else -> preferences.get(DoubleKey.OApsAIMIAutodrivePrebolusEvening)
         }
         val autodriveDelta: Double = preferences.get(DoubleKey.OApsAIMIcombinedDelta)
         val AutodriveAcceleration: Double = preferences.get(DoubleKey.OApsAIMIAutodriveAcceleration)
         val autodriveminDeviation: Double = preferences.get(DoubleKey.OApsAIMIAutodriveDeviation)
         val autodriveTarget: Int = preferences.get(IntKey.OApsAIMIAutodriveTarget)
         val autodriveBG: Int = preferences.get(IntKey.OApsAIMIAutodriveBG)
         val forcedBasal = when (hour) {
             in 1..11 -> preferences.get(DoubleKey.autodriveMaxBasalMorning)
             in 12..18 -> preferences.get(DoubleKey.autodriveMaxBasalAfternoon)
             else -> preferences.get(DoubleKey.autodriveMaxBasalEvening)
         }

         appendLine("╔${"═".repeat(screenWidth)}╗")
         appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_autodrive_title)))
         appendLine("╠${"═".repeat(screenWidth)}╣")
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_autodrive_state), "ON"))
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_prebolus_snack), pbolusAS))
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_prebolus_meal), pbolusA))
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_prebolus_tbr), forcedBasal))
         appendLine("╠${"═".repeat(screenWidth)}╣")
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_autodrive_conditions_title), ""))
         appendLine("╠${"═".repeat(screenWidth)}╣")
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_combined_delta_min), autodriveDelta))
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_acceleration_min), AutodriveAcceleration))
         appendLine(String.format("║ %-${columnWidth}s │ %.1f", context.getString(R.string.table_plugin_autodrive_min_deviation), autodriveminDeviation))
         appendLine(String.format("║ %-${columnWidth}s │ %d", context.getString(R.string.table_plugin_autodrive_target_bg), autodriveTarget))
         appendLine(String.format("║ %-${columnWidth}s │ %d", context.getString(R.string.table_plugin_autodrive_min_bg), autodriveBG))
         appendLine("╚${"═".repeat(screenWidth)}╝")
         appendLine()
     }

     //  appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Step Data"))
     //  appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_steps_title)))
     //  appendLine("╠${"═".repeat(screenWidth)}╣")
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (5m)", recentSteps5Minutes))
     //  appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_1), recentSteps5Minutes))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (30m)", recentSteps30Minutes))
     // appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_2), recentSteps30Minutes))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (60m)", recentSteps60Minutes))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_3), recentSteps180Minutes))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (180m)", recentSteps180Minutes))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_4), recentSteps180Minutes))

     //appendLine("╚${"═".repeat(screenWidth)}╝")
     //appendLine()


     if (recentSteps5Minutes > 0 || recentSteps30Minutes > 0 || recentSteps60Minutes > 0 || recentSteps180Minutes > 0) {
         appendLine("╔${"═".repeat(screenWidth)}╗")
         appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_steps_title)))
         appendLine("╠${"═".repeat(screenWidth)}╣")
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_1), recentSteps5Minutes))
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_2), recentSteps30Minutes))
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_3), recentSteps60Minutes))
         appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_steps_4), recentSteps180Minutes))
         appendLine("╚${"═".repeat(screenWidth)}╝")
         appendLine()
     }

     //appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Heart Rate Data"))
     //appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_heart_title)))
     //appendLine("╠${"═".repeat(screenWidth)}╣")
     //appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (5m)", String.format("%.1f", averageBeatsPerMinute)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s bpm", context.getString(R.string.table_plugin_heart_1), String.format("%.1f", averageBeatsPerMinute)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (60m)", String.format("%.1f", averageBeatsPerMinute60)))
     //appendLine(String.format("║ %-${columnWidth}s │ %s bpm", context.getString(R.string.table_plugin_heart_2), String.format("%.1f", averageBeatsPerMinute60)))

     //appendLine("╚${"═".repeat(screenWidth)}╝")
     //appendLine()

     if (
         (!averageBeatsPerMinute.isNaN() && averageBeatsPerMinute > 0) ||
         (!averageBeatsPerMinute60.isNaN() && averageBeatsPerMinute60 > 0)
     ) {
         appendLine("╔${"═".repeat(screenWidth)}╗")
         appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_heart_title)))
         appendLine("╠${"═".repeat(screenWidth)}╣")
         appendLine(String.format("║ %-${columnWidth}s │ %s bpm", context.getString(R.string.table_plugin_heart_1), String.format("%.1f", averageBeatsPerMinute)))
         appendLine(String.format("║ %-${columnWidth}s │ %s bpm", context.getString(R.string.table_plugin_heart_2), String.format("%.1f", averageBeatsPerMinute60)))
         appendLine("╚${"═".repeat(screenWidth)}╝")
         appendLine()
     }

     appendLine("╔${"═".repeat(screenWidth)}╗")
     //appendLine(String.format("║ %-${screenWidth}s ║", "Miscellaneous"))
     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_miscellaneous_title)))
     appendLine("╠${"═".repeat(screenWidth)}╣")
     //appendLine(String.format("║ %-${columnWidth}s │ %s min", "Last SMB", lastsmbtime))
     appendLine(String.format("║ %-${columnWidth}s │ %s min", context.getString(R.string.table_plugin_miscellaneous_1), lastsmbtime))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Hour", hourOfDay))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_2), hourOfDay))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "Weekend", weekend))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_3), weekend))

     //appendLine(String.format("║ %-${columnWidth}s │ %s", "tags0-60m", tags0to60minAgo))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_4), tags0to60minAgo))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "tags60-120m", tags60to120minAgo))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_5), tags60to120minAgo))
    //appendLine(String.format("║ %-${columnWidth}s │ %s", "tags120-180m", tags120to180minAgo))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_6), tags120to180minAgo))
     //appendLine(String.format("║ %-${columnWidth}s │ %s", "tags180-240m", tags180to240minAgo))
     appendLine(String.format("║ %-${columnWidth}s │ %s", context.getString(R.string.table_plugin_miscellaneous_7), tags180to240minAgo))

     appendLine("╚${"═".repeat(screenWidth)}╝")
     appendLine()

     // Fin de l'assemblage du log
 }

 rT.reason.append(logTemplate)

 // eventual BG is at/above target
 // if iob is over max, just cancel any temps
 if (eventualBG >= max_bg) {
     rT.reason.appendLine("Eventual BG " + convertBG(eventualBG) + " >= " + convertBG(max_bg) + ", ")
 }
 if (iob_data.iob > max_iob) {
     rT.reason.appendLine("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
     if (delta < 0) {
         rT.reason.appendLine(", BG is dropping (delta $delta), setting basal to 0. ")
         return setTempBasal(0.0, 30, profile, rT, currenttemp, overrideSafetyLimits = false) // Basal à 0 pendant 30 minutes
     }
     return if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
         rT.reason.appendLine(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
         rT
     } else {
         rT.reason.appendLine("; setting current basal of ${round(basal, 2)} as temp. ")
         setTempBasal(basal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
     }
 } else {
     var insulinReq = smbToGive.toDouble()
     val recentBGValues: List<Float> = getRecentBGs()
     //updateZeroBasalDuration(profile_current_basal)

     val safetyDecision = safetyAdjustment(
         currentBG = bg.toFloat(),
         predictedBG = predictedBg,
         bgHistory = recentBGValues,
         combinedDelta = combinedDelta.toFloat(),
         iob = iob,
         maxIob = maxIob.toFloat(),
         tdd24Hrs = tdd24Hrs,
         tddPerHour = tddPerHour,
         tirInhypo = currentTIRLow.toFloat(),
         targetBG = targetBg,
         zeroBasalDurationMinutes = zeroBasalAccumulatedMinutes
     )
     rT.reason.append(safetyDecision.reason)

     // Ajustement de la dose SMB proposée en fonction du bolusFactor calculé
     insulinReq = insulinReq * safetyDecision.bolusFactor
     insulinReq = round(insulinReq, 3)
     rT.insulinReq = insulinReq
     //console.error(iob_data.lastBolusTime);
     // minutes since last bolus
     val lastBolusAge = round((systemTime - iob_data.lastBolusTime) / 60000.0, 1)
     //console.error(lastBolusAge);
     //console.error(profile.temptargetSet, target_bg, rT.COB);
     // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus

     if (microBolusAllowed && enableSMB) {
         val microBolus = insulinReq
         rT.reason.appendLine(" insulinReq $insulinReq")
         if (microBolus >= maxSMB) {
             rT.reason.appendLine("; maxBolus $maxSMB")
         }
         rT.reason.append(". ")

         // allow SMBIntervals between 1 and 10 minutes
         //val SMBInterval = min(10, max(1, profile.SMBInterval))
         val smbInterval = min(20, max(1, calculateSMBInterval()))
         val nextBolusMins = round(smbInterval - lastBolusAge, 0)
         val nextBolusSeconds = round((smbInterval - lastBolusAge) * 60, 0) % 60
         if (lastBolusAge > smbInterval) {
             if (microBolus > 0) {
                 rT.units = microBolus
                 rT.reason.appendLine("Microbolusing ${microBolus}U. ")
             }
         } else {
             rT.reason.appendLine("Waiting " + nextBolusMins + "m " + nextBolusSeconds + "s to microbolus again. ")
         }

     }

// Calcul du facteur d'ajustement en fonction de la glycémie
// (ici, j'utilise la fonction simplifiée d'interpolation)
     val basalAdjustmentFactor = interpolatebasal(bg)

// Calcul du taux basal final lissé à partir du TDD récent
     val finalBasalRate = computeFinalBasal(bg, tdd7P.toFloat(), tdd7Days.toFloat(), basalaimi)

// Taux basal courant comme valeur de base
     var rate = profile_current_basal
     if (safetyDecision.stopBasal) {
         return setTempBasal(0.0, 30, profile, rT, currenttemp)
     }
     if (safetyDecision.basalLS && combinedDelta in -1.0..3.0 && predictedBg > 130 && iob > 0.1){
         return setTempBasal(profile_current_basal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
     }

     // ------------------------------
// 1️⃣ Préparation des variables
     var overrideSafety = false
     var chosenRate: Double? = null

// ------------------------------
// 2️⃣ Early‐meal detection → bypass sécurité, forçage vers `forcedBasal`
     if (detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat())
         && !nightbis && modesCondition && bg > 100 && autodrive
     ) {
         chosenRate     = forcedBasal.toDouble()
         overrideSafety = true
         //rT.reason.appendLine("Early meal detected → TBR forcée à ${forcedBasal}U/h x30 (override).\n")
         rT.reason.appendLine(context.getString(R.string.early_meal_tbr_forced, forcedBasal))
     } else {
         // ------------------------------
         // 3️⃣ Cas snack / meal / bfast / lunch / dinner / highCarb / fasting / sport
         chosenRate = when {
             snackTime && snackrunTime in 0..30 && delta < 10 -> {
                 // pas de bypass pour snack
                 calculateRate(basal, profile_current_basal, 4.0, "SnackTime", currenttemp, rT).toDouble()
             }

             mealTime && mealruntime in 0..30 && delta < 10 -> {
                 // meal forcé → bypass
                 overrideSafety = true
                 calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "MealTime", currenttemp, rT).toDouble()
             }

             bfastTime && bfastruntime in 0..30 && delta < 10 -> {
                 // breakfast forcé → bypass
                 overrideSafety = true
                 calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Breakfast", currenttemp, rT).toDouble()
             }

             lunchTime && lunchruntime in 0..30 && delta < 10 -> {
                 // lunch forcé → bypass
                 overrideSafety = true
                 calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Lunch", currenttemp, rT).toDouble()
             }

             dinnerTime && dinnerruntime in 0..30 && delta < 10 -> {
                 // dinner forcé → bypass
                 overrideSafety = true
                 calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Dinner", currenttemp, rT).toDouble()
             }

             highCarbTime && highCarbrunTime in 0..30 && delta < 10 -> {
                 // highCarb forcé → bypass
                 overrideSafety = true
                 calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "HighCarb", currenttemp, rT).toDouble()
             }

             fastingTime ->
                 calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "FastingTime", currenttemp, rT).toDouble()

             sportTime && bg > 169 && delta > 4 ->
                 calculateRate(profile_current_basal, profile_current_basal, 1.3, "SportTime", currenttemp, rT).toDouble()

             honeymoon && delta in 0.0..6.0 && bg in 99.0..141.0 ->
                 calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "Honeymoon", currenttemp, rT).toDouble()

             bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon ->
                 calculateRate(basal, profile_current_basal, 1.0, "Honeymoon small-rise", currenttemp, rT).toDouble()

             bg > 120 && delta > 0 && smbToGive == 0.0f && honeymoon ->
                 calculateRate(basal, profile_current_basal, 5.0, "Honeymoon corr.", currenttemp, rT).toDouble()

             else -> null
         }
     }

// ------------------------------
// 4️⃣ Sécurité immédiate avant hypo : predictedBg<100 & slope négative OU IOB trop haut
     if (chosenRate == null &&
         (predictedBg < 100 && mealData.slopeFromMaxDeviation <= 0 || iob > maxIob)
     ) {
         chosenRate     = 0.0
         overrideSafety = false
         //rT.reason.append("Safety cut: predictedBg<100 ou IOB>$maxIob → basale à 0.\n")
         rT.reason.appendLine(context.getString(R.string.safety_cut, maxIob))
     }

// ------------------------------
// 5️⃣ Hypoglycémies & basale réduite
     if (chosenRate == null) {
         when {
             bg < 80.0 -> {
                 chosenRate = 0.0
                 rT.reason.appendLine("BG<80 → basale à 0.\n")
             }
             bg in 80.0..90.0 &&
                 slopeFromMaxDeviation <= 0 && iob > 0.1f && !sportTime -> {
                 chosenRate = 0.0
                 rT.reason.appendLine("BG 80-90 & chute → basale à 0.\n")
             }
             bg in 80.0..90.0 &&
                 slopeFromMinDeviation >= 0.3 && slopeFromMaxDeviation >= 0 &&
                 combinedDelta in -1.0..2.0 && !sportTime &&
                 bgAcceleration.toFloat() > 0.0f -> {
                 chosenRate = profile_current_basal * 0.2
                 rT.reason.appendLine("BG 80-90 stable → basale x0.2.\n")
             }
             bg in 90.0..100.0 &&
                 slopeFromMinDeviation <= 0.3 && iob > 0.1f && !sportTime &&
                 bgAcceleration.toFloat() > 0.0f -> {
                 chosenRate = 0.0
                 rT.reason.appendLine("BG 90-100 & risque modéré → basale à 0.\n")
             }
             bg in 90.0..100.0 &&
                 slopeFromMinDeviation >= 0.3 && combinedDelta in -1.0..2.0 && !sportTime &&
                 bgAcceleration.toFloat() > 0.0f -> {
                 chosenRate = profile_current_basal * 0.5
                 rT.reason.appendLine("BG 90-100 gain léger → basale x0.5.\n")
             }
         }
     }

// ------------------------------
// 6️⃣ Hausses lentes / rapides
     if (chosenRate == null) {
         if (bg > 120 &&
             slopeFromMinDeviation in 0.4..20.0 &&
             combinedDelta > 1 && !sportTime &&
             bgAcceleration.toFloat() > 1.0f
         ) {
             chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, combinedDelta.toDouble())
             //rT.reason.append("Montée lente → ajustement proportionnel.\n")
             rT.reason.appendLine(context.getString(R.string.basal_arguments_5))
         }
         else if (eventualBG > 110 && !sportTime && bg > 150 &&
             combinedDelta in -2.0..15.0 &&
             bgAcceleration.toFloat() > 0.0f
         ) {
             chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
             rT.reason.appendLine(context.getString(R.string.basal_arguments_6))
         }
     }

// ------------------------------
// 7️⃣ Horaires & activité
     if (chosenRate == null) {
         if ((timenow in 11..13 || timenow in 18..21) &&
             iob < 0.8 && recentSteps5Minutes < 100 &&
             combinedDelta > -1 && slopeFromMinDeviation > 0.3 &&
             bgAcceleration.toFloat() > 0.0f
         ) {
             chosenRate = profile_current_basal * 1.5
             //rT.reason.append("Repas calme & horaire → basale x1.5.\n")
             rT.reason.appendLine(context.getString(R.string.table_plugin_hours_activity_1))
         }
         else if (timenow > sixAMHour && recentSteps5Minutes > 100) {
             chosenRate = 0.0
             //rT.reason.append("Activité matinale → basale à 0.\n")
             rT.reason.appendLine(context.getString(R.string.table_plugin_hours_activity_2))
         }
         else if (timenow <= sixAMHour && delta > 0 && bgAcceleration.toFloat() > 0.0f) {
             chosenRate = profile_current_basal.toDouble()
             //rT.reason.append("Matinée montante → basale de profil.\n")
             rT.reason.appendLine(context.getString(R.string.table_plugin_hours_activity_3))
         }
     }

// ------------------------------
// 8️⃣ Repas & snacks (boucle)
     if (chosenRate == null) {
         val mealConditions = listOf(
             snackTime to snackrunTime,
             mealTime  to mealruntime,
             bfastTime to bfastruntime,
             lunchTime to lunchruntime,
             dinnerTime to dinnerruntime,
             highCarbTime to highCarbrunTime
         )
         for ((meal, runtime) in mealConditions) {
             if (meal && runtime in 0..30) {
                 chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, 10.0)
                 //rT.reason.append("Repas/snack <30m → basale x10.\n")
                 rT.reason.appendLine(context.getString(R.string.table_plugin_meal_snack_1))
                 break
             }
             else if (meal && runtime in 30..60 && delta > 0) {
                 chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, delta.toDouble())
                 //rT.reason.append("Repas/snack 30-60m & montée → basale Δ.\n")
                 rT.reason.appendLine(context.getString(R.string.table_plugin_meal_snack_2))
                 break
             }
         }
     }

// ------------------------------
// 9️⃣ Hyperglycémies & corrections
     if (chosenRate == null) {
         when {
             eventualBG > 180 && delta > 3 ->
                 chosenRate = calculateBasalRate(basalaimi.toDouble(), profile_current_basal, basalAdjustmentFactor).also {
                     //rT.reason.append("EventualBG>180 & hyper → ajustement basalaimi.\n")
                     rT.reason.appendLine(context.getString(R.string.table_plugin_hyperglycemia_1))
                 }
             bg > 180 && delta in -5.0..1.0 ->
                 chosenRate = (profile_current_basal * basalAdjustmentFactor).also {
                     //rT.reason.append("BG>180 stable → basale x facteur.\n")
                     rT.reason.appendLine(context.getString(R.string.table_plugin_hyperglycemia_2))
                 }
         }
     }

// ------------------------------
// 🔟 Mode “honeymoon”
     if (chosenRate == null && honeymoon) {
         when {
             bg in 140.0..169.0 && delta > 0 ->
                 chosenRate = profile_current_basal.toDouble().also { rT.reason.append("Honeymoon BG 140-169 → profil.\n") }
             bg > 170 && delta > 0 ->
                 chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor).also {
                     rT.reason.appendLine("Honeymoon BG>170 → ajustement.\n")
                 }
             combinedDelta > 2 && bg in 90.0..119.0 ->
                 chosenRate = profile_current_basal.toDouble().also { rT.reason.appendLine("Honeymoon Δ>2 & BG 90-119 → profil.\n") }
             combinedDelta > 0 && bg > 110 && eventualBG > 120 && bg < 160 ->
                 chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.appendLine("Honeymoon corr. mixte.\n") }
             mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 && bg > 110 && combinedDelta > 0 ->
                 chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.appendLine("Honeymoon + repas détection.\n") }
             mealData.slopeFromMaxDeviation in 0.0..0.2 && mealData.slopeFromMinDeviation in 0.0..0.5 &&
                 bg in 120.0..150.0 && delta > 0 ->
                 chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.appendLine("Honeymoon petit slope.\n") }
             mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 &&
                 bg in 100.0..120.0 && delta > 0 ->
                 chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.appendLine("Honeymoon slope repas.\n") }
         }
     }

// ------------------------------
// 1️⃣1️⃣ Cas grossesse
     if (chosenRate == null && pregnancyEnable && delta > 0 && bg > 110 && !honeymoon) {
         chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
         rT.reason.appendLine("Grossesse & Δ>0 → ajustement.\n")
     }

// ------------------------------
// 1️⃣2️⃣ Valeur de repli si aucun cas ne s'est déclenché
     val finalRate = chosenRate ?: profile_current_basal.toDouble()

// ------------------------------
// 1️⃣3️⃣ Appel unique à setTempBasal()
     return setTempBasal(
         _rate                = finalRate,
         duration             = 30,
         profile              = profile,
         rT                   = rT,
         currenttemp          = currenttemp,
         overrideSafetyLimits = overrideSafety
     )


 }
}
}
