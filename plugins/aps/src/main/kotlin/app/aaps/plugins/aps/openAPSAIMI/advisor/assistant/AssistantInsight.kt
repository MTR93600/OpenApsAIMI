package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** A value the tool may change. [kind] BOOL uses 0.0 for off and 1.0 for on. */
data class InsightChange(
    val prefKey: String,
    /** English name, as in the AIMI settings of the app. */
    val name: String,
    val kind: AssistantSetting.Kind,
    val isBool: Boolean,
    val from: Double,
    val to: Double,
    val min: Double,
    val max: Double,
    /** Why this one, in Russian. */
    val reasonRu: String,
    /** True when the link between this value and the effect was read from the code. */
    val verified: Boolean,
)

/** A group of changes with one common cause. */
data class InsightBundle(
    val titleRu: String,
    val causeRu: String,
    val changes: List<InsightChange>,
)

/** One step of the SMB chain, summed over the period. */
data class StageSummary(
    val info: StageInfo,
    val cutU: Double,
    val cyclesWithCut: Int,
    val cyclesSeen: Int,
)

/** A rise of at least 40 mg/dL and how the engine reacted to it. */
data class RiseEpisode(
    val startMs: Long,
    val startMgdl: Double,
    val peakMgdl: Double,
    /** Minutes to the first real SMB; null if there was none. */
    val firstSmbMin: Int?,
    val cyclesFirst10: Int,
    val blockedByInterval: Int,
    val blockedByPostHypo: Int,
    val smallCapCycles: Int,
    val afterLow: Boolean,
)

/** A stretch of time when glucose was rising, the engine wanted insulin, and a guard took all of it away. */
data class StuckRise(
    val startMs: Long,
    val minutes: Int,
    val startMgdl: Double,
    val peakMgdl: Double,
    /** Name of the step that most often set the SMB to 0 in this stretch. */
    val guard: String,
    val cycles: Int,
    /** Sum of the SMB sizes the engine wanted in these cycles (conditional units, not insulin given). */
    val wantedU: Double,
    val sportSafety: Boolean,
)

data class NightDrift(
    val samples: Int,
    val medianMgdlPerHour: Double,
)

data class InsightResult(
    val cycles: Int,
    val hours: Int,
    val stages: List<StageSummary>,
    val rises: List<RiseEpisode>,
    val stuck: List<StuckRise>,
    val lows: List<LowEpisode>,
    val bundles: List<InsightBundle>,
    val nightDrift: NightDrift?,
    val summaryRu: String,
)

/**
 * Looks at the last hours of the AIMI decision log, finds what limited the SMB and builds a short list of
 * changes with reasons in Russian. It only reads. It does not know carbs or insulin entered by the user, so it works from the
 * glucose curve and the chain of steps in each cycle.
 */
object AssistantInsightEngine {

    private val KEY_HIGH_BG_INTERVAL = IntKey.OApsAIMIHighBGinterval.key
    private val KEY_HIGH_BG_MAX_SMB = DoubleKey.OApsAIMIHighBGMaxSMB.key
    private val KEY_PRIORITY_EXTRA = DoubleKey.OApsAIMIPriorityMaxIobExtraU.key
    private val KEY_PH_MARGIN = DoubleKey.OApsAIMIPostHypoReleaseMarginMgdl.key
    private val KEY_PH_DELTA = DoubleKey.OApsAIMIPostHypoReleaseDeltaMgdl.key
    private val KEY_MEAL_EARLY = BooleanKey.OApsAIMIMealConfirmedEarlyRelease.key

    private const val RISE_MGDL = 40.0
    private const val SMALL_CAP_U = 0.1

    fun analyze(cycles: List<LiveCycle>, hours: Int, current: (String, Boolean) -> Double?): InsightResult {
        val stages = summarizeStages(cycles)
        val rises = findRises(cycles)
        val stuck = findStuck(cycles)
        val samples = cycles.filter { !it.bgMgdl.isNaN() }.map { AssistantSample(it.timeMs, it.bgMgdl, it.iobU, it.decision == "SMB_Delivery") }
        val lows = AssistantAnalyzer.lowEpisodes(samples)
        val drift = nightDrift(cycles)
        val bundles = ArrayList<InsightBundle>()
        buildLateBundle(rises, lows, current)?.let { bundles.add(it) }
        buildStuckBundle(stuck, current)?.let { bundles.add(it) }
        buildLowBundle(lows, current)?.let { bundles.add(it) }
        val summary = summaryText(cycles.size, hours, rises, lows, bundles, drift, stuck)
        return InsightResult(cycles.size, hours, stages, rises, stuck, lows, bundles, drift, summary)
    }

    // ---- stages ----

    private fun summarizeStages(cycles: List<LiveCycle>): List<StageSummary> {
        val cut = LinkedHashMap<String, Double>()
        val withCut = HashMap<String, Int>()
        val seen = HashMap<String, Int>()
        for (c in cycles) {
            for ((name, v) in c.cutByName) {
                cut[name] = (cut[name] ?: 0.0) + v
                seen[name] = (seen[name] ?: 0) + 1
                if (v > 0.0005) withCut[name] = (withCut[name] ?: 0) + 1
            }
        }
        return cut.entries
            .map { StageSummary(AssistantStageInfo.info(it.key), it.value, withCut[it.key] ?: 0, seen[it.key] ?: 0) }
            .sortedByDescending { it.cutU }
    }

    // ---- rises ----

    private fun findRises(cycles: List<LiveCycle>): List<RiseEpisode> {
        val usable = cycles.filter { !it.bgMgdl.isNaN() }
        val out = ArrayList<RiseEpisode>()
        var i = 0
        while (i < usable.size) {
            val start = usable[i]
            var j = i
            while (j < usable.size && usable[j].timeMs - start.timeMs <= 40 * 60_000L && usable[j].bgMgdl < start.bgMgdl + RISE_MGDL) j++
            if (j < usable.size && usable[j].timeMs - start.timeMs <= 40 * 60_000L && usable[j].bgMgdl >= start.bgMgdl + RISE_MGDL) {
                var k = j
                while (k + 1 < usable.size && usable[k + 1].bgMgdl >= usable[k].bgMgdl - 2 && usable[k + 1].timeMs - start.timeMs <= 120 * 60_000L) k++
                out.add(episode(usable, i, k))
                i = k + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun episode(list: List<LiveCycle>, from: Int, to: Int): RiseEpisode {
        val start = list[from]
        val firstSmb = (from..to).map { list[it] }.firstOrNull { it.decision == "SMB_Delivery" && it.amountU > 0.05 }
        val early = (from..to).map { list[it] }.filter { it.timeMs - start.timeMs <= 10 * 60_000L }
        val lowBefore = list.filter { it.timeMs in (start.timeMs - 3 * 3600_000L)..start.timeMs }.any { it.bgMgdl < 70.0 }
        return RiseEpisode(
            startMs = start.timeMs,
            startMgdl = start.bgMgdl,
            peakMgdl = (from..to).maxOf { list[it].bgMgdl },
            firstSmbMin = firstSmb?.let { ((it.timeMs - start.timeMs) / 60_000L).toInt() },
            cyclesFirst10 = early.size,
            blockedByInterval = early.count { (it.cutByName["REFRACTORY_AND_THYROID"] ?: 0.0) > 0.0005 },
            blockedByPostHypo = early.count { it.postHypoActive || (it.cutByName["POST_HYPO_CAP"] ?: 0.0) > 0.0005 },
            smallCapCycles = early.count { (it.maxSmbU ?: 1.0) <= SMALL_CAP_U },
            afterLow = lowBefore,
        )
    }


    // ---- long rise without SMB ----

    /**
     * Finds stretches of at least 8 minutes where glucose rose (8 mg/dL or more in 5 minutes), the engine wanted at
     * least 0.3 U of SMB, and no SMB was given because a step set it to 0. Short gaps of 3 minutes are bridged.
     */
    private fun findStuck(cycles: List<LiveCycle>): List<StuckRise> {
        val usable = cycles.filter { !it.bgMgdl.isNaN() }
        fun wanted(c: LiveCycle) = c.stages.maxOfOrNull { it.beforeU } ?: 0.0
        fun zeroedBy(c: LiveCycle): String? =
            c.stages.firstOrNull { it.cutU > 0.0005 && it.afterU <= 0.001 }?.name
        val flagged = ArrayList<Pair<LiveCycle, String>>()
        for (i in 5 until usable.size) {
            val c = usable[i]
            val rising = c.bgMgdl - usable[i - 5].bgMgdl >= 8.0 && c.bgMgdl >= 100.0
            val given = c.decision == "SMB_Delivery" && c.amountU > 0.05
            if (!rising || given || wanted(c) < 0.3) continue
            val guard = zeroedBy(c) ?: continue
            flagged.add(c to guard)
        }
        val out = ArrayList<StuckRise>()
        var i = 0
        while (i < flagged.size) {
            var j = i
            while (j + 1 < flagged.size && flagged[j + 1].first.timeMs - flagged[j].first.timeMs <= 3 * 60_000L) j++
            val part = flagged.subList(i, j + 1)
            val minutes = ((part.last().first.timeMs - part.first().first.timeMs) / 60_000L).toInt() + 1
            if (part.size >= 5 && minutes >= 8) {
                val guard = part.groupingBy { it.second }.eachCount().maxByOrNull { it.value }!!.key
                out.add(
                    StuckRise(
                        startMs = part.first().first.timeMs,
                        minutes = minutes,
                        startMgdl = part.first().first.bgMgdl,
                        peakMgdl = part.maxOf { it.first.bgMgdl },
                        guard = guard,
                        cycles = part.size,
                        wantedU = part.sumOf { wanted(it.first) },
                        sportSafety = part.count { it.first.sportSafety } * 2 >= part.size,
                    )
                )
            }
            i = j + 1
        }
        return out
    }

    private fun buildStuckBundle(stuck: List<StuckRise>, current: (String, Boolean) -> Double?): InsightBundle? {
        val postHypo = stuck.filter { it.guard == "POST_HYPO_CAP" }
        if (postHypo.isEmpty()) return null
        val curMargin = current(KEY_PH_MARGIN, false) ?: 20.0
        val curDelta = current(KEY_PH_DELTA, false) ?: 5.0
        val peak = postHypo.maxOf { it.peakMgdl }
        val changes = ArrayList<InsightChange>()
        change(current, KEY_PH_MARGIN, "Post-hypo release margin", AssistantSetting.Kind.DOUBLE, false, if (curMargin > 20.0) 20.0 else 12.0, 5.0, 60.0,
            "Защита после гипо ${postHypo.size} раз подряд держала SMB на нуле, пока сахар рос (до ${"%.1f".format(peak / 18.0)} ммоль/л). " +
                "Сейчас она отпускает при сахаре выше цели на ${curMargin.roundToInt()} мг/дл и росте больше ${curDelta.roundToInt()} мг/дл за 5 минут.", true)?.let { changes.add(it) }
        change(current, KEY_PH_DELTA, "Post-hypo release delta", AssistantSetting.Kind.DOUBLE, false, if (curDelta > 5.0) 5.0 else 3.0, 1.0, 30.0,
            "Рост за 5 минут, при котором защита отпускает. Меньше значение = защита отпускает раньше.", true)?.let { changes.add(it) }
        if (changes.isEmpty()) return null
        return InsightBundle(
            "SMB не подавался при росте после гипо",
            "Найдено ${postHypo.size} затяжных подъёмов (от ${postHypo.minOf { it.minutes }} мин), где защита после гипо обнуляла SMB. Пик ${"%.1f".format(peak / 18.0)} ммоль/л.",
            changes,
        )
    }

    // ---- night drift (for the profile hint) ----

    private fun nightDrift(cycles: List<LiveCycle>): NightDrift? {
        val usable = cycles.filter { !it.bgMgdl.isNaN() }
        val cal = Calendar.getInstance()
        fun hour(ms: Long): Int { cal.timeInMillis = ms; return cal.get(Calendar.HOUR_OF_DAY) }
        val slopes = ArrayList<Double>()
        var idx = 0
        for (c in usable) {
            if (hour(c.timeMs) !in 0..5 || c.iobU !in -0.3..0.3) continue
            val lastSmb = usable.asSequence().filter { it.timeMs in (c.timeMs - 60 * 60_000L)..c.timeMs && it.decision == "SMB_Delivery" }.any()
            if (lastSmb) continue
            val later = usable.firstOrNull { it.timeMs >= c.timeMs + 60 * 60_000L } ?: continue
            if (later.timeMs - c.timeMs > 75 * 60_000L || later.iobU !in -0.3..0.3) continue
            slopes.add(later.bgMgdl - c.bgMgdl)
            idx++
            if (idx > 400) break
        }
        if (slopes.size < 20) return null
        val sorted = slopes.sorted()
        return NightDrift(slopes.size, sorted[sorted.size / 2])
    }

    // ---- bundles ----

    private fun change(
        current: (String, Boolean) -> Double?,
        key: String,
        name: String,
        kind: AssistantSetting.Kind,
        isBool: Boolean,
        to: Double,
        min: Double,
        max: Double,
        reason: String,
        verified: Boolean,
    ): InsightChange? {
        val cur = current(key, isBool) ?: return null
        val target = to.coerceIn(min, max)
        if (target == cur) return null
        return InsightChange(key, name, kind, isBool, cur, target, min, max, reason, verified)
    }

    private fun buildLateBundle(rises: List<RiseEpisode>, lows: List<LowEpisode>, current: (String, Boolean) -> Double?): InsightBundle? {
        val late = rises.filter { (it.firstSmbMin ?: 999) > 12 }
        if (rises.size < 3 || late.size < 2) return null
        val changes = ArrayList<InsightChange>()
        val withCycles = late.filter { it.cyclesFirst10 > 0 }
        fun share(f: (RiseEpisode) -> Int): Int {
            val total = withCycles.sumOf { it.cyclesFirst10 }
            return if (total == 0) 0 else (withCycles.sumOf(f) * 100.0 / total).roundToInt()
        }
        val intervalShare = share { it.blockedByInterval }
        val postHypoShare = share { it.blockedByPostHypo }
        val smallShare = share { it.smallCapCycles }
        val medianDelay = late.mapNotNull { it.firstSmbMin }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

        val curMargin = current(KEY_PH_MARGIN, false) ?: 20.0
        val curDelta = current(KEY_PH_DELTA, false) ?: 5.0
        if (postHypoShare >= 25) {
            change(current, KEY_PH_MARGIN, "Post-hypo release margin", AssistantSetting.Kind.DOUBLE, false, 12.0, 5.0, 60.0,
                "В первые 10 минут ${late.size} поздних подъёмов защита после гипо держала SMB в $postHypoShare% циклов. Запас над целью " +
                    "20 мг/дл (1,1 ммоль/л) — это сахар, при котором защита отпускает. Уменьшение до 12 мг/дл отпустит её на 8 мг/дл раньше. " +
                    "Риск: повторное падение, если низкий сахар был совсем недавно.", true)?.let { changes.add(it) }
            change(current, KEY_PH_DELTA, "Post-hypo release delta", AssistantSetting.Kind.DOUBLE, false, 3.0, 1.0, 30.0,
                "Защита отпускает только когда сахар растёт быстрее заданной скорости (мг/дл за 5 минут). Сейчас ${curDelta.roundToInt()}: она ждёт заметного подъёма; " +
                    "3 отпустит её при более медленном росте.", true)?.let { changes.add(it) }
        }
        if (intervalShare >= 40) {
            change(current, KEY_HIGH_BG_INTERVAL, "High BG SMB interval (min)", AssistantSetting.Kind.INT, false,
                (current(KEY_HIGH_BG_INTERVAL, false) ?: 3.0) - 1.0, 2.0, 15.0,
                "В первые 10 минут подъёмов следующий SMB в $intervalShare% циклов ждал интервал между подачами. Уменьшение интервала на 1 минуту " +
                    "даёт подавать чаще. Действует при сахаре выше 6,7 ммоль/л; ниже интервал всегда 3 минуты и настройкой не меняется.", true)?.let { changes.add(it) }
        }
        if (smallShare >= 50) {
            change(current, KEY_HIGH_BG_MAX_SMB, "High BG Max SMB (U)", AssistantSetting.Kind.DOUBLE, false,
                (current(KEY_HIGH_BG_MAX_SMB, false) ?: 1.0) + 0.1, 0.05, 15.0,
                "В первые 10 минут подъёмов потолок размера SMB был не больше 0,1 Ед в $smallShare% циклов. В ситуации быстрого роста реальным пределом " +
                    "служит именно Max SMB High BG; поднять его на 0,1 Ед даёт первой дозе больше места.", true)?.let { changes.add(it) }
        }
        if (lows.isEmpty() && late.size >= 3) {
            change(current, KEY_MEAL_EARLY, "Meal-confirmed early release", AssistantSetting.Kind.DOUBLE, true, 1.0, 0.0, 1.0,
                "Включение даёт раннюю подачу, когда AIMI уверен, что это еда: снимает часть ограничений в начале роста. За период не было низких сахаров, " +
                    "поэтому запас безопасности есть. Риск: ложное распознавание еды (утренний рост, шум датчика).", false)?.let { changes.add(it) }
        }
        if (changes.isEmpty()) return null
        return InsightBundle(
            "Поздняя реакция на подъёмы",
            "Из ${rises.size} подъёмов на 2,2 ммоль/л и больше в ${late.size} первый SMB пришёл позже чем через 12 минут (медиана $medianDelay мин). " +
                "Основные причины: интервал между подачами $intervalShare%, защита после гипо $postHypoShare%, маленький потолок $smallShare% циклов первых 10 минут.",
            changes,
        )
    }

    private fun buildLowBundle(lows: List<LowEpisode>, current: (String, Boolean) -> Double?): InsightBundle? {
        if (lows.isEmpty()) return null
        val minLow = lows.minOf { it.minMmol }
        val serial = lows.filter { it.smbCount2h >= 8 && it.smbMinutesBetween in 0.1..4.0 }
        val changes = ArrayList<InsightChange>()
        if (serial.isNotEmpty()) {
            change(current, KEY_HIGH_BG_INTERVAL, "High BG SMB interval (min)", AssistantSetting.Kind.INT, false,
                (current(KEY_HIGH_BG_INTERVAL, false) ?: 3.0) + 2.0, 2.0, 6.0,
                "Перед ${serial.size} низкими сахарами шла серия SMB: в среднем ${serial.map { it.smbCount2h }.average().roundToInt()} штук за 2 часа с " +
                    "паузой около ${"%.1f".format(serial.map { it.smbMinutesBetween }.average())} минуты. Увеличение интервала разрежает подачи.", true)?.let { changes.add(it) }
        }
        change(current, KEY_PRIORITY_EXTRA, "Priority MaxIOB extra (U)", AssistantSetting.Kind.DOUBLE, false,
            (current(KEY_PRIORITY_EXTRA, false) ?: 1.0) - 0.5, 0.0, 5.0,
            "Было ${lows.size} эпизодов с низким сахаром (минимум ${"%.1f".format(minLow)} ммоль/л). Дополнительный запас Max IOB для приоритетных " +
                "ситуаций позволяет накапливать больше инсулина; уменьшение его снижает риск перебора.", false)?.let { changes.add(it) }
        if (changes.isEmpty()) return null
        return InsightBundle(
            "Низкие сахара после серии подач",
            "Было ${lows.size} эпизодов ниже 3,5 ммоль/л (минимум ${"%.1f".format(minLow)}). Это сигнал снизить агрессивность подач.",
            changes,
        )
    }

    // ---- summary ----

    private fun summaryText(
        cycles: Int, hours: Int, rises: List<RiseEpisode>, lows: List<LowEpisode>,
        bundles: List<InsightBundle>, drift: NightDrift?, stuck: List<StuckRise> = emptyList(),
    ): String {
        if (cycles < 60) return "Недостаточно данных: в журнале за последние $hours ч только $cycles циклов."
        val late = rises.count { (it.firstSmbMin ?: 999) > 12 }
        val sb = StringBuilder()
        sb.append("За $hours ч: $cycles циклов, подъёмов ${rises.size}, из них поздних $late, низких сахаров ${lows.size}. ")
        if (bundles.isEmpty()) sb.append("Менять настройки по этим данным нет оснований: система работает ровно.")
        else sb.append("Предлагаю ${bundles.sumOf { it.changes.size }} изменений в ${bundles.size} группах. Каждое можно отключить флажком.")
        if (stuck.isNotEmpty()) {
            sb.append(" Затяжных подъёмов без SMB: ${stuck.size} (смотри раздел «Подъёмы без SMB»).")
        }
        if (drift != null && abs(drift.medianMgdlPerHour) >= 12.0) {
            sb.append(" Ночью при нулевом инсулине сахар ")
            sb.append(if (drift.medianMgdlPerHour > 0) "растёт" else "падает")
            sb.append(" примерно на ${abs(drift.medianMgdlPerHour).roundToInt()} мг/дл в час — смотри подсказку по профилю.")
        }
        return sb.toString()
    }

    /** Hint for the profile. It is advice only; nothing is changed. */
    fun profileHintRu(drift: NightDrift?): String? {
        if (drift == null || abs(drift.medianMgdlPerHour) < 12.0) return null
        val up = drift.medianMgdlPerHour > 0
        return (if (up) "Ночью (0–6 ч), когда инсулина на борту почти нет и SMB не подавались, сахар растёт примерно на "
        else "Ночью (0–6 ч), когда инсулина на борту почти нет и SMB не подавались, сахар падает примерно на ") +
            "${abs(drift.medianMgdlPerHour).roundToInt()} мг/дл в час (по ${drift.samples} замерам). " +
            (if (up) "Возможно, ночная базаль в профиле мала. " else "Возможно, ночная базаль в профиле велика. ") +
            "Достоверность низкая: AIMI сам меняет базаль временными отметками, и еда не вводится. " +
            "Менять профиль только после проверки на нескольких ночах и вместе с врачом. Само приложение профиль не меняет."
    }
}
