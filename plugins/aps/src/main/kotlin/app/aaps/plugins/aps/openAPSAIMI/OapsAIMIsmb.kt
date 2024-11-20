/*
package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.plugins.aps.R
import dagger.android.HasAndroidInjector
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

class OapsAIMIsmb {

        @Inject lateinit var aapsLogger: AAPSLogger
        @Inject lateinit var constraintChecker: ConstraintsChecker
        @Inject lateinit var sp: SP
        @Inject lateinit var profileFunction: ProfileFunction
        @Inject lateinit var iobCobCalculator: IobCobCalculator
        @Inject lateinit var activePlugin: ActivePlugin
        @Inject lateinit var repository: AppRepository
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var tddCalculator: TddCalculator
        @Inject lateinit var tirCalculator: TirCalculator


        private var iob = 0.0f
        private var cob = 0.0f
        private var predictedBg = 0.0f
        private var lastCarbAgeMin: Int = 0
        private var futureCarbs = 0.0f
        private var enablebasal: Boolean = false
        private var recentNotes: List<UserEntry>? = null
        private var tags0to60minAgo = ""
        private var tags60to120minAgo = ""
        private var tags120to180minAgo = ""
        private var tags180to240minAgo = ""
        private var currentTIRLow: Double = 0.0
        private var currentTIRRange: Double = 0.0
        private var currentTIRAbove: Double = 0.0
        private var bg = 0.0f
        private var targetBg = 100.0f
        private var normalBgThreshold = 150.0f
        private var delta = 0.0f
        private var shortAvgDelta = 0.0f
        private var longAvgDelta = 0.0f
        private var lastsmbtime = 0
        private var accelerating_up: Int = 0
        private var deccelerating_up: Int = 0
        private var accelerating_down: Int = 0
        private var deccelerating_down: Int = 0
        private var stable: Int = 0
        private var maxIob = 0.0f
        private var maxSMB = 1.0f
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
        private var basalSMB = 0.0f
        private var aimilimit = 0.0f
        private var CI = 0.0f
        private var sleepTime = false
        private var sportTime = false
        private var snackTime = false
        private var lowCarbTime = false
        private var highCarbTime = false
        private var mealTime = false
        private var fastingTime = false
        private var mealruntime: Long = 0
        private var highCarbrunTime: Long = 0
        private var intervalsmb = 5
        private var variableSensitivity = 0.0f
        private var averageBeatsPerMinute = 0.0
        private var averageBeatsPerMinute60 = 0.0
        private var averageBeatsPerMinute180 = 0.0
        private var b30upperbg = 0.0
        private var b30upperdelta = 0.0
        private var profile = JSONObject()
        private var glucoseStatus = JSONObject()
        private var iobData: JSONArray? = null
        private var mealData = JSONObject()
        private var currentTemp = JSONObject()
        private var autosensData = JSONObject()
        private val path = File(Environment.getExternalStorageDirectory().toString())
        private val modelFile = File(path, "AAPS/ml/model.tflite")
        private val modelFileUAM = File(path, "AAPS/ml/modelUAM.tflite")
        private val csvfile = File(path, "AAPS/oapsaimiML_records.csv")
        private var predictedSMB = 0.0f

        var currentTempParam: String? = null
        var iobDataParam: String? = null
        var glucoseStatusParam: String? = null
        var profileParam: String? = null
        var mealDataParam: String? = null
        var scriptDebug = ""

        private var now: Long = 0

        @Suppress("SpellCheckingInspection")
        operator fun invoke(): APSResult {
            aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")
            this.predictedSMB = calculateSMBFromModel()
            //var smbToGive = predictedSMB
            if ((sp.getBoolean(R.string.key_enable_ML_training, false) === true) && csvfile.exists()){
                val allLines = csvfile.readLines()
                val minutesToConsider = SafeParse.stringToDouble(sp.getString(R.string.key_nb_day_ML_training, "60"))
                val linesToConsider = (minutesToConsider / 5).toInt()
                if (allLines.size > linesToConsider) {
                    this.predictedSMB = neuralnetwork5(delta, shortAvgDelta, longAvgDelta)
                }
                this.profile.put("csvfile", csvfile.exists())

            }else {
                this.profile.put("ML Decision data training","ML decision has no enough data to refine the decision")
            }
            var smbToGive = predictedSMB

            val morningfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_morning_factor, "50")) / 100.0
            val afternoonfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_afternoon_factor, "50")) / 100.0
            val eveningfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_evening_factor, "50")) / 100.0
            val hyperfactor = SafeParse.stringToDouble(sp.getString(R.string.key_oaps_aimi_hyper_factor, "50")) / 100.0
            smbToGive = when {
                highCarbTime -> smbToGive * 130.0f
                mealTime -> smbToGive * 200.0f
                hourOfDay in 1..11 -> smbToGive * morningfactor.toFloat()
                hourOfDay in 12..18 -> smbToGive * afternoonfactor.toFloat()
                hourOfDay in 19..23 -> smbToGive * eveningfactor.toFloat()
                bg > 180 -> (smbToGive * hyperfactor).toFloat()
                else -> smbToGive
            }

            smbToGive = applySafetyPrecautions(smbToGive)
            smbToGive = roundToPoint05(smbToGive)
            logDataMLToCsv(predictedSMB, smbToGive)
            logDataToCsv(predictedSMB, smbToGive)
            logDataToCsvHB(predictedSMB, smbToGive)

            val constraintStr = " Max IOB: $maxIob <br/> Max SMB: $maxSMB<br/> sleep: $sleepTime<br/> sport: $sportTime<br/> snack: $snackTime<br/>" +
                "lowcarb: $lowCarbTime<br/> highcarb: $highCarbTime<br/> meal: $mealTime<br/> fastingtime: $fastingTime<br/> intervalsmb: $intervalsmb<br/>" +
                "mealruntime: $mealruntime<br/> highCarbrunTime: $highCarbrunTime<br/>"
            val glucoseStr = " bg: $bg <br/> targetBG: $targetBg <br/> futureBg: $predictedBg <br/>" +
                " delta: $delta <br/> short avg delta: $shortAvgDelta <br/> long avg delta: $longAvgDelta <br/>" +
                " accelerating_up: $accelerating_up <br/> deccelerating_up: $deccelerating_up <br/> accelerating_down: $accelerating_down <br/> deccelerating_down: $deccelerating_down <br/> stable: $stable <br/>" +
                " neuralnetwork5: " + "${neuralnetwork5(delta, shortAvgDelta, longAvgDelta)}<br/>"
            val iobStr = " IOB: $iob <br/> tdd 7d/h: ${roundToPoint05(tdd7DaysPerHour)} <br/> " +
                "tdd 2d/h : ${roundToPoint05(tdd2DaysPerHour)} <br/> " +
                "tdd daily/h : ${roundToPoint05(tddPerHour)} <br/> " +
                "tdd 24h/h : ${roundToPoint05(tdd24HrsPerHour)}<br/>" +
                " enablebasal: $enablebasal <br/> basalaimi: $basalaimi <br/> basalsmb: $basalSMB <br/> ISF: $variableSensitivity <br/> "
            val profileStr = " Hour of day: $hourOfDay <br/> Weekend: $weekend <br/>" +
                " 5 Min Steps: $recentSteps5Minutes <br/> 10 Min Steps: $recentSteps10Minutes <br/> 15 Min Steps: $recentSteps15Minutes <br/>" +
                " 30 Min Steps: $recentSteps30Minutes <br/> 60 Min Steps: $recentSteps60Minutes <br/> 180 Min Steps: $recentSteps180Minutes <br/>" +
                "Heart Beat(average past 5 minutes) : $averageBeatsPerMinute <br/> Heart Beat(average past 180 minutes) : $averageBeatsPerMinute180"
            val mealStr = " COB: ${cob}g   Future: ${futureCarbs}g <br/> COB Age Min: $lastCarbAgeMin <br/><br/> " +
                "tags0to60minAgo: ${tags0to60minAgo}<br/> tags60to120minAgo: $tags60to120minAgo<br/> " +
                "tags120to180minAgo: $tags120to180minAgo<br/> tags180to240minAgo: $tags180to240minAgo<br/> " +
                "currentTIRLow: $currentTIRLow<br/> currentTIRRange: $currentTIRRange<br/> currentTIRAbove: $currentTIRAbove<br/>"
            val reason = "The ai model predicted SMB of ${roundToPoint001(predictedSMB)}u and after safety requirements and rounding to .05, requested ${smbToGive}u to the pump" +
                ",<br/> Version du plugin OpenApsAIMI-MT.1 ML.2, 16 Décembre 2023"
            val determineBasalResultAIMISMB = DetermineBasalResultAIMISMB(injector, smbToGive, constraintStr, glucoseStr, iobStr, profileStr, mealStr, reason)

            glucoseStatusParam = glucoseStatus.toString()
            iobDataParam = iobData.toString()
            currentTempParam = currentTemp.toString()
            profileParam = profile.toString()
            mealDataParam = mealData.toString()
            return determineBasalResultAIMISMB
        }

        private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {

            val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
            val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)


            val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, predictedSMB, smbGiven\n"
            val valuesToRecord = "$dateStr," +
                "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
                "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
                "$predictedSMB,$smbToGive"

            val file = File(path, "AAPS/oapsaimiML_records.csv")
            if (!file.exists()) {
                file.createNewFile()
                file.appendText(headerRow)
            }
            file.appendText(valuesToRecord + "\n")
        }
        private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

            val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
            val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)


            val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
                "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
                "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
                "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
                "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
                "predictedSMB,maxIob,maxSMB,smbGiven\n"
            val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
                "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
                "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
                "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
                "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
                "$predictedSMB,$maxIob,$maxSMB,$smbToGive"

            val file = File(path, "AAPS/oapsaimi_records.csv")
            if (!file.exists()) {
                file.createNewFile()
                file.appendText(headerRow)
            }
            file.appendText(valuesToRecord + "\n")
        }

        private fun logDataToCsvHB(predictedSMB: Float, smbToGive: Float) {
            val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

            val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
                "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
                "accelerating_up,deccelerating_up,accelerating_down,deccelerating_down,stable," +
                "tdd7DaysPerHour,tdd2DaysPerHour,tddDailyPerHour,tdd24HrsPerHour," +
                "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,averageBeatsPerMinute, averageBeatsPerMinute180," +
                "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
                "variableSensitivity,lastbolusage,predictedSMB,maxIob,maxSMB,smbGiven\n"
            val valuesToRecord = "$dateStr,${dateUtil.now()},$hourOfDay,$weekend," +
                "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
                "$accelerating_up,$deccelerating_up,$accelerating_down,$deccelerating_down,$stable," +
                "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
                "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
                "$averageBeatsPerMinute, $averageBeatsPerMinute180," +
                "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
                "$variableSensitivity,$predictedSMB,$maxIob,$maxSMB,$smbToGive"

            val file = File(path, "AAPS/oapsaimiHB_records.csv")
            if (!file.exists()) {
                file.createNewFile()
                file.appendText(headerRow)
            }
            file.appendText(valuesToRecord + "\n")
        }
        private fun applySafetyPrecautions(smbToGiveParam: Float): Float {
            var smbToGive = smbToGiveParam
            val pbolusM = SafeParse.stringToDouble(sp.getString(R.string.key_prebolus_meal_mode, "2"))
            val pbolusHC = SafeParse.stringToDouble(sp.getString(R.string.key_prebolus_highcarb_mode, "2"))
            // Vérifier les conditions de sécurité critiques
            if (isMealModeCondition()) return pbolusM.toFloat()
            if (isHighCarbModeCondition()) return pbolusHC.toFloat()
            if (isCriticalSafetyCondition()) return 0.0f
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
                result = maxSMB
            }
            // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
            if (iob + result > maxIob) {
                result = maxIob - iob
            }

            return result
        }

        private fun isMealModeCondition(): Boolean{
            val pbolusM = SafeParse.stringToDouble(sp.getString(R.string.key_prebolus_meal_mode, "2"))
            val modeMealPB = mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
            return modeMealPB
        }
        private fun isHighCarbModeCondition(): Boolean{
            val pbolusHC = SafeParse.stringToDouble(sp.getString(R.string.key_prebolus_highcarb_mode, "2"))
            val modeHcPB = highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
            return modeHcPB
        }

        private fun isCriticalSafetyCondition(): Boolean {
            val fasting = fastingTime
            val nightTrigger = LocalTime.now().run { (hour in 23..23 || hour in 0..4) } && delta > 15 && cob === 0.0f
            val belowMinThreshold = bg < 80
            val belowTargetAndDropping = bg < targetBg && delta < -2
            val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 5
            val droppingFast = bg < 150 && delta < -5
            val droppingFastAtHigh = bg < 200 && delta < -7
            val droppingVeryFast = delta < -10
            val prediction = predictedBg < targetBg && delta < 10
            val interval = predictedBg < targetBg && delta > 10 && iob >= maxSMB/2 && lastsmbtime < 10
            val targetinterval = targetBg >= 120 && delta > 0 && iob >= maxSMB/2 && lastsmbtime < 15
            val nosmb = iob >= 2*maxSMB && bg < 110 && delta < 10


            return belowMinThreshold || belowTargetAndDropping || belowTargetAndStableButNoCob ||
                droppingFast || droppingFastAtHigh || droppingVeryFast || prediction || interval || targetinterval ||
                fasting || nosmb || nightTrigger
        }
        private fun isSportSafetyCondition(): Boolean {
            val sport = targetBg >= 140 && recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
            val sport1 = targetBg >= 140 && recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute60
            val sport2 = recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute60
            val sport3 = recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
            val sport4 = targetBg >= 140
            val sport5= sportTime

            return sport || sport1 || sport2 || sport3 || sport4 || sport5

        }

        private fun applySpecificAdjustments(smbToGive: Float): Float {
            var result = smbToGive

            val safetysmb = recentSteps180Minutes > 1500 && bg < 130
            if ((safetysmb || sleepTime || snackTime || lowCarbTime ) && lastsmbtime >= 10) {
                result /= 2
                this.intervalsmb = 10
            }else if ((safetysmb || sleepTime || snackTime) && lastsmbtime < 10){
                result = 0.0f
                this.intervalsmb = 10
            }



            if (recentSteps5Minutes > 100 && recentSteps30Minutes > 500 && lastsmbtime < 20) {
                result = 0.0f
            }

            return result
        }

        private fun finalizeSmbToGive(smbToGive: Float): Float {
            var result = smbToGive
            // Assurez-vous que smbToGive n'est pas négatif
            if (result < 0.0f) {
                result = 0.0f
            }
            return result
        }

        private fun roundToPoint05(number: Float): Float {
            return (number * 20.0).roundToInt() / 20.0f
        }

        private fun roundToPoint001(number: Float): Float {
            return (number * 1000.0).roundToInt() / 1000.0f
        }

        private fun calculateSMBFromModel(): Float {
            val selectedModelFile: File?
            val modelInputs: FloatArray

            when {
                cob > 0 && lastCarbAgeMin < 240 && modelFile.exists() -> {
                    selectedModelFile = modelFile
                    modelInputs = floatArrayOf(
                        hourOfDay.toFloat(), weekend.toFloat(),
                        bg, targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta
                    )
                }

                modelFileUAM.exists()   -> {
                    selectedModelFile = modelFileUAM
                    modelInputs = floatArrayOf(
                        hourOfDay.toFloat(), weekend.toFloat(),
                        bg, targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                        tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                        recentSteps5Minutes.toFloat(),recentSteps10Minutes.toFloat(),recentSteps15Minutes.toFloat(),recentSteps30Minutes.toFloat(),recentSteps60Minutes.toFloat(),recentSteps180Minutes.toFloat()
                    )
                }

                else                 -> {
                    aapsLogger.error(LTag.APS, "NO Model found at specified location")
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
        private fun neuralnetwork5(delta: Float, shortAvgDelta: Float, longAvgDelta: Float): Float {
            val minutesToConsider = SafeParse.stringToDouble(sp.getString(R.string.key_nb_day_ML_training, "60"))
            val linesToConsider = (minutesToConsider / 5).toInt()
            var averageDifference: Float
            var totalDifference: Float
            val maxIterations = SafeParse.stringToDouble(sp.getString(R.string.key_nb_iteration_ML_training, "100"))
            var differenceWithinRange = false
            var finalRefinedSMB: Float = calculateSMBFromModel()
            val maxGlobalIterations = 5 // Nombre maximum d'itérations globales
            var globalConvergenceReached = false

            for (globalIteration in 1..maxGlobalIterations) {
                var globalIterationCount = 0
                var iterationCount = 0

                while (globalIterationCount < maxGlobalIterations && !globalConvergenceReached) {

                    val allLines = csvfile.readLines()
                    val headerLine = allLines.first()
                    val headers = headerLine.split(",").map { it.trim() }
                    val colIndices = listOf("bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta", "predictedSMB").map { headers.indexOf(it) }
                    val targetColIndex = headers.indexOf("smbGiven")
                    this.profile.put("colIndices", colIndices)

                    val lines = if (allLines.size > linesToConsider) allLines.takeLast(linesToConsider + 1) else allLines // +1 pour inclure l'en-tête

                    val inputs = mutableListOf<FloatArray>()
                    val targets = mutableListOf<DoubleArray>()

                    for (line in lines.drop(1)) { // Ignorer l'en-tête
                        val cols = line.split(",").map { it.trim() }
                        this.profile.put("cols", cols)

                        val input = colIndices.mapNotNull { index -> cols.getOrNull(index)?.toFloatOrNull() }.toFloatArray()
                        // Calculez et ajoutez l'indicateur de tendance directement dans 'input'
                        val trendIndicator = when {
                            delta > 0 && shortAvgDelta > 0 && longAvgDelta > 0 -> 1
                            delta < 0 && shortAvgDelta < 0 && longAvgDelta < 0 -> -1
                            else                                               -> 0
                        }
                        val enhancedInput = input.copyOf(input.size + 1)
                        enhancedInput[input.size] = trendIndicator.toFloat()
                        this.profile.put("input", input.contentToString())
                        this.profile.put("input.size", input.size)
                        this.profile.put("colIndices.size", colIndices.size)

                        val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
                        this.profile.put("targetValue", targetValue.toString())
                        if (enhancedInput.size == colIndices.size + 1 && targetValue != null) {
                            inputs.add(enhancedInput)
                            targets.add(doubleArrayOf(targetValue))
                            this.profile.put("inputs", inputs.size.toString())
                            this.profile.put("targets", targets.size.toString())
                        }
                    }

                    if (inputs.isEmpty() || targets.isEmpty()) {
                        return predictedSMB
                    }
                    val epoch = SafeParse.stringToDouble(sp.getString(R.string.key_nb_epoch_ML_training, "40")).toInt()
                    val learningrate = SafeParse.stringToDouble(sp.getString(R.string.key_nb_learningrate_ML_training, "0.1"))
                    //val neuralNetwork = aimiNeuralNetwork(inputs.first().size, 5, 1)
                    // Déterminer la taille de l'ensemble de validation
                    val validationSize = (inputs.size * 0.1).toInt() // Par exemple, 10% pour la validation

                    // Diviser les données en ensembles d'entraînement et de validation
                    val validationInputs = inputs.takeLast(validationSize)
                    val validationTargets = targets.takeLast(validationSize)
                    val trainingInputs = inputs.take(inputs.size - validationSize)
                    val trainingTargets = targets.take(targets.size - validationSize)

                    // Création et entraînement du réseau de neurones
                    val neuralNetwork = aimiNeuralNetwork(inputs.first().size, 5, 1)
                    neuralNetwork.train(trainingInputs, trainingTargets, validationInputs, validationTargets, epoch, learningrate)

                    val inputForPrediction = inputs.last()
                    val prediction = neuralNetwork.predict(inputForPrediction)
                    this.profile.put("predictionML", prediction[0].toString())

                    do {
                        totalDifference = 0.0f

                        for (enhancedInput in inputs) {
                            val predictedrefineSMB = finalRefinedSMB// Prédiction du modèle TFLite
                            val refinedSMB = refineSMB(predictedrefineSMB, neuralNetwork, enhancedInput)
                            this.profile.put("predictedrefineSMB", predictedrefineSMB)
                            this.profile.put("refinedSMB", refinedSMB)

                            val difference = kotlin.math.abs(predictedrefineSMB - refinedSMB)
                            totalDifference += difference
                            if (difference in 0.0..1.5) {
                                finalRefinedSMB = refinedSMB
                                differenceWithinRange = true
                                this.profile.put("finalRefinedSMB in the loop", finalRefinedSMB)
                                break  // Sortie anticipée si la différence est dans la plage souhaitée
                            }
                        }
                        this.profile.put("differenceWithinRange", differenceWithinRange)
                        averageDifference = totalDifference / inputs.size
                        this.profile.put("averageDifferenceML", averageDifference)
                        iterationCount++
                        if (differenceWithinRange || iterationCount >= maxIterations) {
                            println("Maximum iterations reached.")
                            this.profile.put("iterationCount", iterationCount)
                            this.profile.put("maxIterations", maxIterations)
                            break
                        }

                        // Ajustez ici si nécessaire. Par exemple, ajuster les paramètres du modèle ou les données d'entrée
                    } while (true)
                    if (differenceWithinRange || iterationCount >= maxIterations) {
                        globalConvergenceReached = true
                    }


                    globalIterationCount++
                }
            }
            this.profile.put("globalConvergenceReached", globalConvergenceReached)
            this.profile.put("finalRefinedSMB2", finalRefinedSMB)
            this.profile.put("differenceWithinRange", differenceWithinRange)
            // Retourne finalRefinedSMB si la différence est dans la plage, sinon predictedSMB
            return if (globalConvergenceReached) finalRefinedSMB else predictedSMB
        }


        private fun calculateAdjustedDelayFactor(
            bg: Float, recentSteps180Minutes: Int, averageBeatsPerMinute60: Float, averageBeatsPerMinute180: Float
        ): Float {
            // Seuil pour une activité physique significative basée sur les étapes
            val stepActivityThreshold = 1500

            // Seuil d'augmentation de la fréquence cardiaque indiquant une activité accrue
            val heartRateIncreaseThreshold = 1.2  // par exemple, une augmentation de 20%

            // Seuil à partir duquel l'efficacité de l'insuline commence à diminuer
            val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

            // Déterminer si une activité physique significative a eu lieu
            val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold

            // Calculer le changement relatif de la fréquence cardiaque
            val heartRateChange = averageBeatsPerMinute60 / averageBeatsPerMinute180

            // Indicateur d'une augmentation possible de la fréquence cardiaque due à l'exercice
            val increasedHeartRateActivity = heartRateChange >= heartRateIncreaseThreshold

            // Calculer le facteur de base avant de prendre en compte l'activité physique
            val baseFactor = when {
                bg <= normalBgThreshold -> 1f
                bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
                else -> 0.5f // Arbitraire, à ajuster en fonction de la physiologie individuelle
            }

            // Si une activité physique est détectée (soit par les étapes, soit par la fréquence cardiaque),
            // nous ajustons le facteur de retard pour augmenter la sensibilité à l'insuline.
            return if (increasedPhysicalActivity || increasedHeartRateActivity) {
                (baseFactor.toFloat() * 0.8f).coerceAtLeast(0.5f)  // Ici, nous utilisons 0.8f pour indiquer qu'il s'agit d'un Float
            } else {
                baseFactor.toFloat()  // Cela devrait déjà être un Float
            }
        }

        private fun calculateInsulinEffect(
            bg: Float,
            iob: Float,
            variableSensitivity: Float,
            cob: Float,
            normalBgThreshold: Float,
            recentSteps180Min: Int,
            averageBeatsPerMinute60: Float,
            averageBeatsPerMinute180: Float
        ): Float {
            // Calculer l'effet initial de l'insuline
            var insulinEffect = iob * variableSensitivity

            // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
            if (cob > 0) {
                // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
                insulinEffect *= 0.9f
            }

            // Calculer le facteur de retard ajusté en fonction de l'activité physique
            val adjustedDelayFactor = calculateAdjustedDelayFactor(
                normalBgThreshold,
                recentSteps180Min,
                averageBeatsPerMinute60,
                averageBeatsPerMinute180
            )

            // Appliquer le facteur de retard ajusté à l'effet de l'insuline
            insulinEffect *= adjustedDelayFactor
            if (bg > normalBgThreshold) {
                insulinEffect *= 1.1f
            }

            return insulinEffect
        }
        private fun predictFutureBg(
            bg: Float,
            iob: Float,
            variableSensitivity: Float,
            cob: Float,
            CI: Float,
        ): Float {
            // Temps moyen d'absorption des glucides en heures
            val averageCarbAbsorptionTime = 2.5f
            val absorptionTimeInMinutes = averageCarbAbsorptionTime * 60

            // Calculer l'effet de l'insuline sur la baisse de la glycémie
            val insulinEffect = calculateInsulinEffect(
                bg,
                iob,
                variableSensitivity,
                cob,
                normalBgThreshold,
                recentSteps180Minutes,
                averageBeatsPerMinute60.toFloat(),
                averageBeatsPerMinute180.toFloat()
            )

            // Calculer l'effet des glucides sur l'augmentation de la glycémie
            // en supposant que 'absorptionTime' représente la période de temps pendant laquelle les glucides sont absorbés
            val carbEffect = (cob / absorptionTimeInMinutes) * CI

            // Prédire la glycémie future
            val futureBg = bg - insulinEffect + carbEffect

            return futureBg
        }

        private fun calculateGFactor(delta: Float, shortAvgDelta: Float, longAvgDelta: Float): Double {
            val accelerationFactor = 0.5 // Facteur pour accélération de la glycémie
            val decelerationFactor = 1.3 // Facteur pour décélération de la glycémie
            val stableFactor = 1.7 // Facteur pour glycémie stable

            return when {
                // Accélération rapide
                delta > 2 && (delta > shortAvgDelta && delta > longAvgDelta) -> accelerationFactor

                // Décélération ou tendance à la stabilisation
                delta < 2 && (delta < shortAvgDelta || delta < longAvgDelta) -> decelerationFactor

                // Glycémie stable
                else -> stableFactor
            }
        }

        @Suppress("SpellCheckingInspection")
        @Throws(JSONException::class)
        override fun setData(
            profile: Profile,
            maxIob: Double,
            maxBasal: Double,
            minBg: Double,
            maxBg: Double,
            targetBg: Double,
            basalRate: Double,
            iobArray: Array<IobTotal>,
            glucoseStatus: GlucoseStatus,
            mealData: MealData,
            autosensDataRatio: Double,
            tempTargetSet: Boolean,
            microBolusAllowed: Boolean,
            uamAllowed: Boolean,
            advancedFiltering: Boolean,
            flatBGsDetected: Boolean,
            tdd1D: Double?,
            tdd7D: Double?,
            tddLast24H: Double?,
            tddLast4H: Double?,
            tddLast8to4H: Double?
        ) {
            this.now = System.currentTimeMillis()
            val calendarInstance = Calendar.getInstance()
            this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
            val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
            this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0

            val iobCalcs = iobCobCalculator.calculateIobFromBolus()
            this.iob = iobCalcs.iob.toFloat() + iobCalcs.basaliob.toFloat()
            this.bg = glucoseStatus.glucose.toFloat()
            this.targetBg = targetBg.toFloat()
            this.cob = mealData.mealCOB.toFloat()
            var lastCarbTimestamp = mealData.lastCarbTime

            if(lastCarbTimestamp.toInt() == 0) {
                val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
                lastCarbTimestamp = iobCobCalculator.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
            }
            this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toDouble().roundToInt()

            if(lastCarbAgeMin < 15 && cob == 0.0f) {
                this.cob = iobCobCalculator.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
            }

            this.futureCarbs = iobCobCalculator.getFutureCob().toFloat()
            val fourHoursAgo = now - 4 * 60 * 60 * 1000
            this.recentNotes = iobCobCalculator.getUserEntryDataWithNotesFromTime(fourHoursAgo)

            this.tags0to60minAgo = parseNotes(0, 60)
            this.tags60to120minAgo = parseNotes(60, 120)
            this.tags120to180minAgo = parseNotes(120, 180)
            this.tags180to240minAgo = parseNotes(180, 240)
            this.delta = glucoseStatus.delta.toFloat()
            this.shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat()
            this.longAvgDelta = glucoseStatus.longAvgDelta.toFloat()
            var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
            nowMinutes = round(nowMinutes * 100) / 100  // Arrondi à 2 décimales

            val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
                (0.00016422 * nowMinutes.pow(4)) +
                (0.00128081 * nowMinutes.pow(3)) +
                (0.02533782 * nowMinutes.pow(2)) -
                (0.33275556 * nowMinutes) +
                1.38581503

            val circadianSmb = round(
                ((0.00000379 * delta * nowMinutes.pow(5)) -
                    (0.00016422 * delta * nowMinutes.pow(4)) +
                    (0.00128081 * delta * nowMinutes.pow(3)) +
                    (0.02533782 * delta * nowMinutes.pow(2)) -
                    (0.33275556 * delta * nowMinutes) +
                    1.38581503) * 100
            ) / 100  // Arrondi à 2 décimales
            when {
                !tempTargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 -> {
                    this.targetBg = 130.0f
                }
                !tempTargetSet && predictedBg >= 120 && delta > 5 -> {
                    var hyperTarget = kotlin.math.max(65.0, profile.getTargetLowMgdl() - (bg - profile.getTargetLowMgdl()) / 3).roundToInt()
                    hyperTarget = (hyperTarget * kotlin.math.min(circadianSensitivity, 1.0)).toInt()
                    hyperTarget = kotlin.math.max(hyperTarget, 65)

                    this.targetBg = hyperTarget.toFloat()
                }
                !tempTargetSet && circadianSmb > 0.1 && predictedBg < 130 -> {
                    var hypoTarget = 100 * kotlin.math.max(1.0, circadianSensitivity)
                    this.targetBg = (hypoTarget + circadianSmb).toFloat()
                }
                else -> {
                    val defaultTarget = profile.getTargetLowMgdl()
                    this.targetBg = defaultTarget.toFloat()
                }
            }
            this.enablebasal = sp.getBoolean(R.string.key_enable_basal, false)
            val therapy = therapy(appRepository = repository)
            therapy.updateStatesBasedOnTherapyEvents()
            this.sleepTime = therapy.sleepTime
            this.snackTime = therapy.snackTime
            this.sportTime = therapy.sportTime
            this.lowCarbTime = therapy.lowCarbTime
            this.highCarbTime = therapy.highCarbTime
            this.mealTime = therapy.mealTime
            this.fastingTime = therapy.fastingTime
            this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
            this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")

            this.accelerating_up = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
            this.deccelerating_up = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
            this.accelerating_down = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
            this.deccelerating_down = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
            this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3) 1 else 0
            val tdd7P = SafeParse.stringToDouble(sp.getString(R.string.key_tdd7, "50"))
            var tdd7Days = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
            if (tdd7Days == 0.0f) tdd7Days = tdd7P.toFloat()
            this.tdd7DaysPerHour = tdd7Days / 24

            var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
            if (tdd2Days == 0.0f) tdd2Days = tdd7P.toFloat()
            this.tdd2DaysPerHour = tdd2Days / 24
            val tddLast4H = tdd2DaysPerHour.toDouble() * 4
            var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.totalAmount?.toFloat() ?: 0.0f
            if (tddDaily == 0.0f) tddDaily = tdd7P.toFloat()
            this.tddPerHour = tddDaily / 24

            var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
            if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()
            this.tdd24HrsPerHour = tdd24Hrs / 24
            val tddLast8to4H  = tdd24HrsPerHour.toDouble() * 4
            val insulin = activePlugin.activeInsulin

            val insulinDivisor = when {
                insulin.peak >= 35 -> 55 // lyumjev peak: 45
                insulin.peak > 45  -> 65 // ultra rapid peak: 55
                else               -> 75 // rapid peak: 75
            }
            val tddWeightedFromLast8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
            var tdd = (tddWeightedFromLast8H * 0.33) + (tdd7Days.toDouble() * 0.34) + (tddDaily.toDouble() * 0.33)
            val dynISFadjust = SafeParse.stringToDouble(sp.getString(R.string.key_DynISFAdjust, "120")) / 100.0
            val dynISFadjusthyper = SafeParse.stringToDouble(sp.getString(R.string.key_DynISFAdjusthyper, "150")) / 100.0

            tdd = when{
                sportTime -> tdd * 50.0
                sleepTime -> tdd * 80.0
                lowCarbTime -> tdd * 85.0
                snackTime -> tdd * 65.0
                highCarbTime -> tdd * 500.0
                mealTime -> tdd * 200.0
                bg > 180 -> tdd * dynISFadjusthyper
                else -> tdd * dynISFadjust
            }
            this.variableSensitivity = kotlin.math.max(profile.getIsfMgdl().toFloat()/2.5f, Round.roundTo(1800 / (tdd * (ln((glucoseStatus.glucose / insulinDivisor) + 1))), 0.1).toFloat() * calculateGFactor(delta, shortAvgDelta, longAvgDelta).toFloat())
            this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
            this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
            this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!

            val beatsPerMinuteValues: List<Int>
            val beatsPerMinuteValues60: List<Int>
            val beatsPerMinuteValues180: List<Int>
            val timeMillisNow = System.currentTimeMillis()
            val timeMillis5 = System.currentTimeMillis() - 5 * 60 * 1000 // 5 minutes en millisecondes
            val timeMillis10 = System.currentTimeMillis() - 10 * 60 * 1000 // 10 minutes en millisecondes
            val timeMillis15 = System.currentTimeMillis() - 15 * 60 * 1000 // 15 minutes en millisecondes
            val timeMillis30 = System.currentTimeMillis() - 30 * 60 * 1000 // 30 minutes en millisecondes
            val timeMillis60 = System.currentTimeMillis() - 60 * 60 * 1000 // 60 minutes en millisecondes
            val timeMillis180 = System.currentTimeMillis() - 180 * 60 * 1000 // 180 minutes en millisecondes
            val stepsCountList5 = repository.getLastStepsCountFromTimeToTime(timeMillis5, timeMillisNow)
            val stepsCount5 = stepsCountList5?.steps5min ?: 0

            val stepsCountList10 = repository.getLastStepsCountFromTimeToTime(timeMillis10, timeMillisNow)
            val stepsCount10 = stepsCountList10?.steps10min ?: 0

            val stepsCountList15 = repository.getLastStepsCountFromTimeToTime(timeMillis15, timeMillisNow)
            val stepsCount15 = stepsCountList15?.steps15min ?: 0

            val stepsCountList30 = repository.getLastStepsCountFromTimeToTime(timeMillis30, timeMillisNow)
            val stepsCount30 = stepsCountList30?.steps30min ?: 0

            val stepsCountList60 = repository.getLastStepsCountFromTimeToTime(timeMillis60, timeMillisNow)
            val stepsCount60 = stepsCountList60?.steps60min ?: 0

            val stepsCountList180 = repository.getLastStepsCountFromTimeToTime(timeMillis180, timeMillisNow)
            val stepsCount180 = stepsCountList180?.steps180min ?: 0
            if (sp.getBoolean(R.string.count_steps_watch, false)===true) {
                this.recentSteps5Minutes = stepsCount5
                this.recentSteps10Minutes = stepsCount10
                this.recentSteps15Minutes = stepsCount15
                this.recentSteps30Minutes = stepsCount30
                this.recentSteps60Minutes = stepsCount60
                this.recentSteps180Minutes = stepsCount180
            }else{
                this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
                this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
                this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
                this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
                this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
                this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
            }
            try {
                val heartRates = repository.getHeartRatesFromTimeToTime(timeMillis5,timeMillisNow)
                beatsPerMinuteValues = heartRates.map { it.beatsPerMinute.toInt() } // Extract beatsPerMinute values from heartRates
                this.averageBeatsPerMinute = if (beatsPerMinuteValues.isNotEmpty()) {
                    beatsPerMinuteValues.average()
                } else {
                    80.0 // or some other default value
                }

            } catch (e: Exception) {
                // Log that watch is not connected
                //beatsPerMinuteValues = listOf(80)
                averageBeatsPerMinute = 80.0
            }
            try {
                val heartRates = repository.getHeartRatesFromTimeToTime(timeMillis60,timeMillisNow)
                beatsPerMinuteValues60 = heartRates.map { it.beatsPerMinute.toInt() } // Extract beatsPerMinute values from heartRates
                this.averageBeatsPerMinute60 = if (beatsPerMinuteValues60.isNotEmpty()) {
                    beatsPerMinuteValues60.average()
                } else {
                    80.0 // or some other default value
                }

            } catch (e: Exception) {
                // Log that watch is not connected
                //beatsPerMinuteValues = listOf(80)
                averageBeatsPerMinute60 = 80.0
            }
            try {

                val heartRates180 = repository.getHeartRatesFromTimeToTime(timeMillis180,timeMillisNow)
                beatsPerMinuteValues180 = heartRates180.map { it.beatsPerMinute.toInt() } // Extract beatsPerMinute values from heartRates
                this.averageBeatsPerMinute180 = if (beatsPerMinuteValues180.isNotEmpty()) {
                    beatsPerMinuteValues180.average()
                } else {
                    80.0 // or some other default value
                }

            } catch (e: Exception) {
                // Log that watch is not connected
                //beatsPerMinuteValues180 = listOf(80)
                averageBeatsPerMinute180 = 80.0
            }
            if (tdd2Days != null && tdd2Days != 0.0f) {
                this.basalaimi = (tdd2Days / SafeParse.stringToDouble(sp.getString(R.string.key_aimiweight, "50"))).toFloat()
            } else {
                this.basalaimi = (tdd7P / SafeParse.stringToDouble(sp.getString(R.string.key_aimiweight, "50"))).toFloat()
            }
            if (tdd2Days != null && tdd2Days != 0.0f) {
                this.CI = 450 / tdd2Days
            } else {

                this.CI = (450 / tdd7P).toFloat()
            }

            val choKey = SafeParse.stringToDouble(sp.getString(R.string.key_cho, "50"))
            if (CI != 0.0f && CI != Float.POSITIVE_INFINITY && CI != Float.NEGATIVE_INFINITY) {
                this.aimilimit = (choKey / CI).toFloat()
            } else {
                this.aimilimit = (choKey / profile.getIc()).toFloat()
            }
            val timenow = LocalTime.now()
            val sixAM = LocalTime.of(6, 0)
            if (averageBeatsPerMinute != 0.0) {
                this.basalaimi = when {
                    averageBeatsPerMinute >= averageBeatsPerMinute180 && recentSteps5Minutes > 100 && recentSteps10Minutes > 200 -> (basalaimi * 0.65).toFloat()
                    averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute > averageBeatsPerMinute180 && bg >= 130 && recentSteps10Minutes === 0 && timenow > sixAM -> (basalaimi * 1.3).toFloat()
                    averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute < averageBeatsPerMinute180 && recentSteps10Minutes === 0 && bg >= 110 -> (basalaimi * 1.2).toFloat()
                    else -> basalaimi
                }
            }

            this.b30upperbg = SafeParse.stringToDouble(sp.getString(R.string.key_B30_upperBG, "130"))
            this.b30upperdelta = SafeParse.stringToDouble(sp.getString(R.string.key_B30_upperdelta, "10"))
            val b30duration = SafeParse.stringToDouble(sp.getString(R.string.key_B30_duration, "20"))

            val variableSensitivityDouble = variableSensitivity.toDoubleSafely()
            if (variableSensitivityDouble != null) {
                if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10|| recentSteps180Minutes > 1500 && bg < 130 && delta < 10) this.variableSensitivity *= 1.5f
                if (recentSteps30Minutes > 500 && recentSteps5Minutes >= 0 && recentSteps5Minutes < 100 && bg < 130 && delta < 10) this.variableSensitivity *= 1.3f

            } else {
                this.variableSensitivity = profile.getIsfMgdl().toFloat() * calculateGFactor(delta,shortAvgDelta,longAvgDelta).toFloat()
            }
            val getlastBolusSMB = repository.getLastBolusRecordOfTypeWrapped(Bolus.Type.SMB).blockingGet()
            val lastBolusSMBTime = if (getlastBolusSMB is ValueWrapper.Existing) getlastBolusSMB.value.timestamp else 0L
            this.lastBolusSMBUnit = if (getlastBolusSMB is ValueWrapper.Existing) getlastBolusSMB.value.amount.toFloat() else 0.0F
            this.lastsmbtime = ((now - lastBolusSMBTime) / (60 * 1000)).toDouble().roundToInt().toLong().toInt()

            this.maxIob = sp.getDouble(R.string.key_openapssmb_max_iob, 5.0).toFloat()
            this.maxSMB = sp.getDouble(R.string.key_openapsaimi_max_smb, 1.0).toFloat()

            // profile.dia
            val abs = iobCobCalculator.calculateAbsoluteIobFromBaseBasals(System.currentTimeMillis())
            val absIob = abs.iob
            val absNet = abs.netInsulin
            val absBasal = abs.basaliob

            aapsLogger.debug(LTag.APS, "IOB options : bolus iob: ${iobCalcs.iob} basal iob : ${iobCalcs.basaliob}")
            aapsLogger.debug(LTag.APS, "IOB options : calculateAbsoluteIobFromBaseBasals iob: $absIob net : $absNet basal : $absBasal")
            val tddDouble = tdd.toDoubleSafely()
            val glucoseDouble = glucoseStatus.glucose.toDoubleSafely()
            val insulinDivisorDouble = insulinDivisor.toDoubleSafely()

            if (tddDouble != null && glucoseDouble != null && insulinDivisorDouble != null) {
                variableSensitivity = (1800 / (tdd.toDouble() * (ln((glucoseStatus.glucose / insulinDivisor) + 1)))).toFloat()

                // Ajout d'un log pour vérifier la valeur de variableSensitivity après le calcul
                val variableSensitivityDouble = variableSensitivity.toDoubleSafely()
                if (variableSensitivityDouble != null) {
                    if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10|| recentSteps180Minutes > 1500 && bg < 130 && delta < 10) variableSensitivity *= 1.5f
                    if (recentSteps30Minutes > 500 && recentSteps5Minutes >= 0 && recentSteps5Minutes < 100 && bg < 130 && delta < 10) variableSensitivity *= 1.3f
                }
            } else {
                variableSensitivity = profile.getIsfMgdl().toFloat()
            }
            this.predictedBg = predictFutureBg(bg, iob, variableSensitivity, cob, CI)
            this.profile = JSONObject()
            this.profile.put("max_iob", maxIob)
            this.profile.put("dia", kotlin.math.min(profile.dia, 3.0))
            this.profile.put("type", "current")
            this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
            this.profile.put("max_basal", maxBasal)
            this.profile.put("min_bg", minBg)
            this.profile.put("max_bg", maxBg)
            this.profile.put("target_bg", targetBg)
            this.profile.put("circadianSmb", circadianSmb)
            this.profile.put("predictedBg", predictedBg)
            this.profile.put("carb_ratio", CI)
            this.profile.put("sens", variableSensitivity)
            this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
            this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))
            this.profile.put("skip_neutral_temps", true)
            this.profile.put("current_basal", basalRate)
            this.profile.put("temptargetSet", tempTargetSet)
            this.profile.put("autosens_adjust_targets", sp.getBoolean(R.string.key_openapsama_autosens_adjusttargets, true))
            this.profile.put("sleepTime", sleepTime)
            this.profile.put("sportTime", sportTime)
            this.profile.put("snackTime", snackTime)
            this.profile.put("highCarbTime", highCarbTime)
            this.profile.put("mealTime", mealTime)
            this.profile.put("fastingTime", fastingTime)
            this.profile.put("Sport0SMB", isSportSafetyCondition())
            this.profile.put("modelFileUAM", modelFileUAM.exists())
            this.profile.put("modelFile",  modelFile.exists())

            if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
                this.profile.put("out_units", "mmol/L")
            }

            val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
            val newRate = if ( bg > 80 && delta > 0 && isSportSafetyCondition() === false) {
                basalaimi

            } else {
                tb?.convertedToAbsolute(now, profile) ?: 0.0
            }
            // Déterminer la durée pour le nouveau basal temporaire
            //val newDuration = tb?.plannedRemainingMinutes ?: 30
            // Créer ou mettre à jour l'objet TemporaryBasal pour la nouvelle commande
            val newTempBasal = TemporaryBasal(
                timestamp = now,
                duration = 30,//newDuration * 60 * 1000L, // Convertir en millisecondes
                rate = if(bg > 80 && delta > 0 && isSportSafetyCondition() === false) newRate.toDouble() else 0.0,
                isAbsolute = true,
                type = TemporaryBasal.Type.NORMAL
            )

            currentTemp = JSONObject()
            currentTemp.put("temp", "absolute")
            currentTemp.put("duration", newTempBasal.duration)
            currentTemp.put("rate", newTempBasal.rate)

            // as we have non default temps longer than 30 minutes
            if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

            iobData = iobCobCalculator.convertToJSONArray(iobArray)
            this.glucoseStatus = JSONObject()
            this.glucoseStatus.put("glucose", glucoseStatus.glucose)
            if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
                this.glucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
            } else {
                this.glucoseStatus.put("delta", glucoseStatus.delta)
            }
            this.glucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
            this.glucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
            this.mealData = JSONObject()
            this.mealData.put("carbs", mealData.carbs)
            this.mealData.put("mealCOB", mealData.mealCOB)
            if (constraintChecker.isAutosensModeEnabled().value()) {
                autosensData.put("ratio", autosensDataRatio)
            } else {
                autosensData.put("ratio", 1.0)
            }
        }


        private fun determineNoteBasedOnBg(bg: Double): String {
            return when {
                bg > 170 -> "more aggressive"
                bg in 90.0..100.0 -> "less aggressive"
                bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
                bg < 80 -> "low treatment"
                else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts
            }
        }

        private fun Number.toDoubleSafely(): Double? {
            val doubleValue = this.toDouble()
            return doubleValue.takeIf { !it.isNaN() && !it.isInfinite() }
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

        private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
            val olderTimeStamp = now - endMinAgo * 60 * 1000
            val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
            var notes = ""
            val recentNotes2: MutableList<String> = mutableListOf()
            val autoNote = determineNoteBasedOnBg(bg.toDouble())
            recentNotes2.add(autoNote)
            notes += autoNote  // Ajout de la note auto générée

            recentNotes?.forEach { note ->
                if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                    val noteText = note.note.lowercase()
                    if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") ||
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
        init {
            injector.androidInjector().inject(this)
        }
    }



}*/
