package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.plugins.aps.openAPSAIMI.ISF.ObservedIsfStore
import kotlin.math.abs

/**
 * Compares the values of the profile with values that our own algorithms measured. The glucose values are in mg/dL here;
 * the screen shows them in the user's units (see AimiUnits).
 *
 * Sources of the reference values:
 *  - sensitivity: measured from the results (the fall of glucose per unit of absorbed insulin over clean falls);
 *  - carbohydrate ratio: the eating mode, with the multiplier that it learned, and the finished meals that ended well;
 *  - basal share: the basal that was really given (with temp basal) against the daily dose.
 * A row is marked as a hint only when the difference is clear (15% and more).
 */
object CoefficientsAnalyzer {

    data class Row(
        val name: String,
        val profile: String,
        val reference: String,
        /** True when the profile differs clearly from the reference value. */
        val differs: Boolean,
        val comment: String,
    )

    data class Input(
        val tddUnits: Double?,
        /** Basal that was really given per day (with temp basal), average of the last days. */
        val tddBasalUnits: Double?,
        val profileIsfMgdl: Double,
        /** Grams of carbohydrate for 1 unit, from the profile. */
        val profileIcGramsPerU: Double,
        val profileBasalPerDayU: Double,
        val targetMgdl: Double,
        /** Units for 1 bread unit (10 g) that the eating mode uses, with its learned multiplier. */
        val eatingUPerXe: Double,
        val eatingMultiplier: Double,
        val eatingLearnedMeals: Int,
        /** Median units per bread unit of the meals that ended well, with the number of those meals. */
        val goodMealUPerXe: Pair<Double, Int>?,
        /** Sensitivity measured by the algorithm from the results. */
        val observed: ObservedIsfStore.Summary,
    )

    fun analyze(i: Input): List<Row> {
        val rows = mutableListOf<Row>()
        val tdd = i.tddUnits?.takeIf { it > 5.0 }

        // sensitivity: measured by our algorithm, not by a rule of thumb
        val obs = i.observed
        val measured = obs.medianMgdlPerU
        val profileIsfText = AimiUnits.isf(i.profileIsfMgdl)
        if (measured == null) {
            rows += Row(
                name = "Чувствительность (ISF)",
                profile = profileIsfText,
                reference = "измерение копится (чистых падений: ${obs.count} из 3)",
                differs = false,
                comment = "Система сама меряет чувствительность: на сколько падает сахар на 1 ЕД всосавшегося инсулина. " +
                    "Берутся только чистые падения: от 30 минут, минус ${AimiUnits.g(25.0)} и больше, без еды за последние 4 часа. Нужно минимум 3 таких падения.",
            )
        } else {
            val parts = StringBuilder(
                "${AimiUnits.isf(measured)}, падений: ${obs.count}"
            )
            obs.nightMedianMgdlPerU?.let { parts.append("; ночью ${AimiUnits.num(it, 2)}") }
            obs.dayMedianMgdlPerU?.let { parts.append("; днём ${AimiUnits.num(it, 2)}") }
            val higher = measured > i.profileIsfMgdl
            rows += Row(
                name = "Чувствительность (ISF)",
                profile = profileIsfText,
                reference = parts.toString(),
                differs = differs(i.profileIsfMgdl, measured),
                comment = if (higher) {
                    "Измерено больше, чем в профиле. Это значит: либо реальная чувствительность выше, либо базал в профиле завышен " +
                        "(тогда лишний инсулин базала не считается как расход, и цифра получается выше). Смотрите вместе со строкой «Доля базала»."
                } else {
                    "Измерено не выше профиля. Это нижняя граница: печень отдаёт глюкозу во время падения, поэтому реальная чувствительность " +
                        "не ниже измеренной. Профиль не нужно снижать только по этой цифре."
                },
            )
        }

        // carbohydrate ratio: from the learned eating multiplier
        val eatingUPerXeEffective = i.eatingUPerXe * i.eatingMultiplier
        val eatingGramsPerU = 10.0 / eatingUPerXeEffective
        rows += Row(
            name = "Углеводный коэффициент",
            profile = "${AssistantUi.num(i.profileIcGramsPerU, 1)} г на 1 ЕД (${AssistantUi.num(10.0 / i.profileIcGramsPerU, 2)} ЕД на ХЕ)",
            reference = "${AssistantUi.num(eatingGramsPerU, 1)} г на 1 ЕД (${AssistantUi.num(eatingUPerXeEffective, 2)} ЕД на ХЕ) по режиму «Я ем»",
            differs = differs(i.profileIcGramsPerU, eatingGramsPerU),
            comment = if (i.eatingLearnedMeals < 10) {
                "Режим «Я ем» учится: накоплено ${i.eatingLearnedMeals} из 10 приёмов пищи. Значение пока ориентировочное. " +
                    "Именно этот коэффициент режим использует для оценки углеводов и для бюджета инсулина."
            } else {
                "Получено по ${i.eatingLearnedMeals} приёмам пищи: система подогнала оценку под ваши результаты. " +
                    "Именно этот коэффициент режим использует для оценки углеводов и для бюджета инсулина."
            },
        )

        // what the meals really needed: only meals that ended well
        val good = i.goodMealUPerXe
        if (good == null) {
            rows += Row(
                name = "Инсулин на 1 ХЕ в удачных приёмах",
                profile = "${AssistantUi.num(10.0 / i.profileIcGramsPerU, 2)} ЕД на ХЕ",
                reference = "мало данных (нужно 3 удачных приёма)",
                differs = false,
                comment = "Удачный приём: без низкого сахара и без высокого пика. По ним видно, сколько инсулина на 1 ХЕ реально понадобилось " +
                    "вместе со стартовой дозой, ускорением и временной базой.",
            )
        } else {
            val profileUPerXe = 10.0 / i.profileIcGramsPerU
            rows += Row(
                name = "Инсулин на 1 ХЕ в удачных приёмах",
                profile = "${AssistantUi.num(profileUPerXe, 2)} ЕД на ХЕ",
                reference = "${AssistantUi.num(good.first, 2)} ЕД на ХЕ (приёмов: ${good.second})",
                differs = differs(profileUPerXe, good.first),
                comment = "Это фактический расход инсулина в приёмах, которые закончились хорошо (с учётом временной базы). " +
                    "Если цифра ниже профиля, профиль даёт лишний инсулин. Если выше, режим «Я ем» добавляет к профилю.",
            )
        }

        if (tdd != null) {
            // basal share: the basal that was really given, with temp basal
            val actualBasal = i.tddBasalUnits ?: i.profileBasalPerDayU
            val share = actualBasal / tdd * 100.0
            val profileShare = i.profileBasalPerDayU / tdd * 100.0
            rows += Row(
                name = "Доля базала в суточной дозе",
                profile = "по профилю ${AssistantUi.num(profileShare, 0)}% (${AssistantUi.num(i.profileBasalPerDayU, 1)} ЕД из ${AssistantUi.num(tdd, 1)} ЕД)",
                reference = "фактически ${AssistantUi.num(share, 0)}% (${AssistantUi.num(actualBasal, 1)} ЕД), ориентир 40–50%",
                differs = share < 35.0 || share > 55.0 || profileShare > 55.0,
                comment = if (profileShare > 55.0) {
                    "Базал в профиле велик. Признак: сначала петля долго держит ноль, сахар растёт, потом система переливает инсулин. " +
                        "Это ваши ночные волны. Попробуйте снизить базал на 5–10% в те часы, где ноль держится дольше всего."
                } else if (share < 35.0) {
                    "Базала мало: сахар между едой может расти, а система компенсирует это болюсами."
                } else {
                    "В пределах нормы."
                },
            )
        }

        rows += Row(
            name = "Цель",
            profile = AimiUnits.g(i.targetMgdl),
            reference = "${AimiUnits.g(99.0)} для вашей задачи",
            differs = abs(i.targetMgdl - 99.0) > 9.0,
            comment = "От цели считаются пороги AIMI: стоп-прогноз, остановка базы и торможение «Я ем». Если цель выше, система остановится выше ${AimiUnits.num(99.0)}.",
        )
        return rows
    }

    private fun differs(profile: Double, reference: Double): Boolean =
        reference > 0.0 && abs(profile - reference) / reference >= 0.15
}
