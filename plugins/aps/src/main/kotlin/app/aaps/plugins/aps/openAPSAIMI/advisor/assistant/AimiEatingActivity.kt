package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.openAPSAIMI.meal.EatingEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The "I eat" screen: starts and cancels the eating mode, shows how the meal is estimated, and the statistics of past meals.
 * Starting needs no confirmation; cancelling asks for one.
 */
class AimiEatingActivity : TranslatedDaggerAppCompatActivity() {

    private companion object {
        /** Carbohydrates are orange and insulin is blue, the same as on the main screen. */
        val ORANGE = Color.parseColor("#FB923C")
        val BLUE = Color.parseColor("#38BDF8")

        /** When to switch on "fat food". */
        const val FAT_HINT = "Жирная пища — пицца, бургер, шашлык, жареное, сливочные соусы, сыр, торт с кремом, фастфуд. " +
            "Сахар растёт позже и дольше, до 4–5 часов. Включайте вместе с размером: окно станет 5 часов, " +
            "и система будет добавлять инсулин по медленному позднему росту."
    }

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction

    private val eating by lazy { EatingEngine(this) }
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU"))
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(AssistantUi.BG)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AssistantUi.BG)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // All glucose texts follow the units chosen in AndroidAPS.
        AimiUnits.mmol = profileFunction.getUnits() == GlucoseUnit.MMOL
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(AssistantUi.body(this, "Я ем", 22f, Color.WHITE, bold = true))
        root.addView(AssistantTabs.bar(this, 2))
        val snap = eating.snapshot()
        root.addView(if (snap.active) activeCard(snap) else startCard())
        root.addView(realXeCard())
        root.addView(statisticsCard())
    }

    // ---- start ----

    private fun startCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Режим не запущен"))
        graceHint()?.let { card.addView(AssistantUi.body(this, it, 15f, ORANGE, bold = true)) }
        card.addView(
            AssistantUi.hint(
                this,
                "Нажмите за 10–15 минут до еды. Инсулин начинает действовать через 10–15 минут, и тогда он успеет к началу подъёма. Система даст стартовую дозу, потом сама оценит съеденное по росту сахара и будет подавать инсулин по ускорению роста. Углеводы вводить не нужно."
            )
        )
        card.addView(AssistantUi.label(this, "Размер приёма пищи (необязательно)"))
        card.addView(AssistantUi.hint(this, "Можно не выбирать: тогда режим «Авто». Система сама оценивает, насколько сильный рост, по его ускорению, и подаёт инсулин пропорционально. Повторное нажатие на выбранный размер возвращает «Авто»."))
        card.addView(AssistantUi.hint(this, "Задаёт предохранитель инсулина на окно: малый 40%, средний 70%, большой и авто 100% от предела в настройках."))
        val sizeRow = sizeButtons()
        card.addView(sizeRow)
        card.addView(AssistantUi.hint(this, sizeHint(eating.size())))
        card.addView(fatButton())
        card.addView(AssistantUi.hint(this, FAT_HINT))
        card.addView(
            AssistantUi.button(this, "Запустить режим «Я ем»", AssistantUi.GREEN).apply {
                setOnClickListener {
                    AimiHaptics.click(this@AimiEatingActivity)
                    // The start needs no confirmation: the first dose is small and every later dose is limited by the budget.
                    eating.start(System.currentTimeMillis(), 0.0, eating.size())
                    render()
                }
            }
        )
        return card
    }

    private fun sizeButtons(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val choices = listOf(
            EatingEngine.SIZE_AUTO to "Авто",
            0 to "Малый",
            1 to "Средний",
            2 to "Большой"
        )
        choices.forEach { (index, name) ->
            val selected = eating.size() == index
            row.addView(
                Button(this).apply {
                    text = name
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = AssistantUi.choiceBackground(this@AimiEatingActivity, if (selected) AssistantUi.ACCENT else AssistantUi.CARD_BORDER)
                    setOnClickListener {
                        AimiHaptics.click(this@AimiEatingActivity)
                        eating.setSize(index)
                        render()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 8, 4, 8) }
            )
        }
        return row
    }

    /**
     * Timer of the "same meal" time: a new start within 30 minutes of the last one keeps the old start time, so the
     * start dose is not given again and all insulin since then stays counted. Null when the time is over.
     */
    private fun graceHint(): String? {
        val leftMs = eating.restartGraceLeftMs(System.currentTimeMillis())
        if (leftMs <= 0L) return null
        val min = (leftMs + 59_999L) / 60_000L
        return "Перезапуск без новой стартовой дозы: ещё $min мин. Если отменить и запустить режим снова в это " +
            "время (например, чтобы сменить размер), это будет та же еда: уже поданный инсулин засчитается."
    }

    /** What food fits the chosen size, with bread units (1 XE = 10 g of carbohydrates). */
    private fun sizeHint(size: Int): String = when (size) {
        0 -> "Малый — перекус: фрукт, йогурт, бутерброд, печенье, стакан сока. Примерно 1–3 ХЕ (10–30 г углеводов)."
        1 -> "Средний — обычная тарелка: каша, суп с хлебом, второе с гарниром, омлет с хлебом. Примерно 3–5 ХЕ (30–50 г углеводов)."
        2 -> "Большой — плотный приём: паста, плов, суши, 2–3 куска пиццы, обед из нескольких блюд, десерт после еды. Примерно 5–8 ХЕ и больше (50–80 г углеводов и больше)."
        else -> "Авто — если не знаете размер. Система сама оценит съеденное по скорости роста сахара. Ориентиры: малый 1–3 ХЕ, средний 3–5 ХЕ, большой 5–8 ХЕ и больше."
    }

    private fun fatButton(): Button {
        val enabled = eating.fat()
        return Button(this).apply {
            text = if (enabled) "Жирная пища · поздняя фаза включена" else "Жирная пища"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = AssistantUi.choiceBackground(
                this@AimiEatingActivity,
                if (enabled) Color.rgb(245, 158, 11) else AssistantUi.CARD_BORDER
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 8, 4, 8)
            }
            setOnClickListener {
                AimiHaptics.click(this@AimiEatingActivity)
                eating.setFat(!enabled)
                render()
            }
        }
    }

    // ---- running ----

    private fun activeCard(snap: EatingEngine.Snapshot): LinearLayout {
        val card = AssistantUi.card(this)
        val ageMin = ((System.currentTimeMillis() - snap.startMs) / 60_000L).coerceAtLeast(0L)
        val totalMin = ((eating.windowEndMs() - snap.startMs) / 60_000L).coerceAtLeast(0L)
        card.addView(AssistantUi.heading(this, "Режим идёт: $ageMin мин из $totalMin"))
        graceHint()?.let { card.addView(AssistantUi.hint(this, it)) }
        if (snap.estimateG <= 0.0 && snap.deliveredU <= 0.0) {
            card.addView(AssistantUi.hint(this, "Ждём первого шага алгоритма (до нескольких минут)."))
        }
        val xe = snap.estimateG / 10.0
        // In brackets: the most that the system counted in this meal, without the falls of the estimate.
        card.addView(
            AssistantUi.body(
                this,
                "Оценка съеденного: ${AssistantUi.num(xe, 1)} ХЕ (${AssistantUi.num(snap.estimateG, 0)} г). Насчитано по росту за приём (${AssistantUi.num(snap.peakCarbsG / 10.0, 1)} ХЕ)",
                20f, ORANGE, bold = true
            )
        )
        card.addView(AssistantUi.body(this, "Подано в этом окне: ${AssistantUi.num(snap.deliveredU, 2)} ЕД (нужно всего ${AssistantUi.num(snap.peakNeedU, 2)} ЕД)", 20f, BLUE, bold = true))
        card.addView(AssistantUi.body(this, "Нужно инсулина по оценке: ${AssistantUi.num(snap.neededU, 2)} ЕД"))
        val leftG = (snap.countedG - snap.coveredG).coerceAtLeast(0.0)
        card.addView(
            AssistantUi.body(
                this,
                "Насчитано по росту: ${AssistantUi.num(snap.peakCarbsG / 10.0, 1)} ХЕ, последний прирост на ${snap.peakCarbsMin.toInt()}-й минуте. " +
                    "Пик действия инсулина: ${AssistantUi.num(snap.peakActivityUph, 2)} ЕД/ч на ${snap.peakActivityMin.toInt()}-й минуте"
            )
        )
        card.addView(
            AssistantUi.body(
                this,
                "Насчитано всего: ${AssistantUi.num(snap.countedG / 10.0, 1)} ХЕ. Перекрыто инсулином: ${AssistantUi.num(snap.coveredG / 10.0, 1)} ХЕ. Осталось: ${AssistantUi.num(leftG / 10.0, 1)} ХЕ"
            )
        )
        card.addView(AssistantUi.body(this, "Лимит окна: ${AssistantUi.num(limitU(snap.size), 1)} ЕД (размер: ${sizeName(snap.size)})"))
        card.addView(AssistantUi.body(this, "Торможение: ${brakeText(snap.brake)}"))
        if (snap.peakMgdl > 0.0) {
            card.addView(AssistantUi.body(this, "Пик за окно: ${AssistantUi.glucose(snap.peakMgdl)}"))
        }
        card.addView(
            AssistantUi.hint(
                this,
                "Как считается: оценка растёт из прироста сахара, который не объясняется активным инсулином. Новая порция еды даёт новый прирост оценки. Всё поданное в окне (стартовая доза, SMB, ручные болюсы и лишний инсулин временной базы выше профильной) вычитается из бюджета. «Насчитано всего» не уменьшается: оценка может снижаться, когда рост остановился, а насчитанное остаётся."
            )
        )
        card.addView(AssistantUi.label(this, "Размер приёма пищи"))
        card.addView(sizeButtons())
        card.addView(fatButton())
        card.addView(
            AssistantUi.button(this, "Отменить режим «Я ем»", AssistantUi.RED).apply {
                setOnClickListener {
                    AimiHaptics.click(this@AimiEatingActivity)
                    AlertDialog.Builder(this@AimiEatingActivity)
                        .setTitle("Отменить режим «Я ем»?")
                        .setMessage("Окно закроется, из него ничего не будет записано в обучение. Уже поданный инсулин останется. Дальше AIMI работает как обычно.")
                        .setPositiveButton("Отменить режим") { _, _ ->
                            AimiHaptics.click(this@AimiEatingActivity)
                            eating.cancel()
                            render()
                        }
                        .setNegativeButton("Нет, продолжить", null)
                        .show()
                }
            }
        )
        return card
    }

    private fun limitU(size: Int): Double {
        val share = doubleArrayOf(0.4, 0.7, 1.0, 1.0, 1.0)[size.coerceIn(0, 4)]
        return preferences.get(DoubleKey.OApsAIMIEatingMaxWindowU) * share
    }

    private fun sizeName(size: Int) = listOf("малый", "средний", "большой", "авто", "авто")[size.coerceIn(0, 4)] + if (eating.fat()) " + жирная" else ""

    private fun brakeText(brake: Double): String = when {
        brake >= 0.99 -> "нет, рост идёт"
        brake <= 0.01 -> "полное: рост остановился или замедлился при низком прогнозе"
        else -> "частичное, подача ${AssistantUi.num(brake * 100.0, 0)}% (рост замедляется)"
    }

    // ---- real value of the last meal ----

    private fun realXeCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Сколько ХЕ вы съели (необязательно)"))
        card.addView(AssistantUi.hint(this, "Если знаете, укажите ХЕ последнего приёма пищи. Это точное значение для обучения: система сравнит его со своей оценкой. Можно оставить пустым."))
        val input = AssistantUi.numberField(this, 0.0, 1)
        card.addView(input)
        val status = AssistantUi.hint(this, "")
        card.addView(
            AssistantUi.button(this, "Сохранить").apply {
                setOnClickListener {
                    AimiHaptics.click(this@AimiEatingActivity)
                    val value = AssistantUi.parse(input)
                    if (value == null || value <= 0.0 || value > 30.0) {
                        status.text = "Введите число от 0,1 до 30."
                    } else {
                        eating.setRealXe(value)
                        status.text = "Сохранено для последнего приёма пищи."
                    }
                }
            }
        )
        card.addView(status)
        return card
    }

    // ---- statistics ----

    private fun statisticsCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Статистика приёмов пищи"))
        card.addView(
            AssistantUi.hint(
                this,
                "Множитель оценки: ${AssistantUi.num(eating.multiplier(), 2)} (1,00 = без поправки). Учтено в обучении: ${eating.learnedMeals()} приёмов. " +
                    "Итог приёма проверяется через 3 часа 20 минут: пик выше ${AimiUnits.g(162.0)} без низких значений значит «мало инсулина», минимум ниже ${AimiUnits.g(81.0)} значит «много»."
            )
        )
        val meals = eating.meals().takeLast(12).reversed()
        if (meals.isEmpty()) {
            card.addView(AssistantUi.body(this, "Приёмов пищи пока нет."))
            return card
        }
        val all = eating.meals()
        val counted = all.filter { !eating.isCancelled(it) }
        val lowCount = counted.count { it.optInt("verdict") == -1 }
        val highCount = counted.count { it.optInt("verdict") == 1 }
        val matchedCount = counted.count { eating.isMatched(it) }
        card.addView(
            AssistantUi.body(
                this,
                "Всего приёмов: ${all.size}, отменено: ${all.size - counted.size}. Совпало с ожиданием: $matchedCount. Слишком много инсулина: $lowCount. Слишком мало: $highCount.",
                13.5f
            )
        )
        card.addView(AssistantUi.hint(this, "Зелёная плашка: результат совпал с ожиданием (без низкого, пик не высокий, в конце сахар не остался высоким). Только такие приёмы идут в обучение стартовой дозы и инсулина на ХЕ. Красная плашка: приём отменён или прерван, на него ориентироваться не нужно."))
        meals.forEach { m ->
            val verdict = if (eating.isCancelled(m)) "не учитывать" else when (m.optInt("verdict")) {
                -1 -> "много инсулина"
                1 -> "мало инсулина"
                else -> "в норме"
            }
            val real = m.optDouble("realXe", -1.0)
            val line = StringBuilder()
            line.append(dateFormat.format(Date(m.optLong("startMs")))).append(": ")
            line.append("насчитано по росту ${AssistantUi.num(if (m.has("countedXe")) m.optDouble("countedXe") else m.optDouble("estXe"), 1)} ХЕ")
            if (m.has("estMaxMin")) line.append(" на ${m.optDouble("estMaxMin").toInt()}-й мин")
            if (real > 0.0) line.append(", реально ${AssistantUi.num(real, 1)} ХЕ")
            line.append(", подано ${AssistantUi.num(m.optDouble("deliveredU"), 1)} ЕД")
            if (m.has("peakActUph")) {
                line.append("\nпик действия инсулина ${AssistantUi.num(m.optDouble("peakActUph"), 2)} ЕД/ч на ${m.optDouble("peakActMin").toInt()}-й мин")
            }
            line.append("\nпик ${AssistantUi.glucose(m.optDouble("peakMgdl"))}")
            line.append("\nминимум ${AssistantUi.glucose(m.optDouble("minMgdl"))}")
            line.append("\nитог: $verdict")
            val cancelled = eating.isCancelled(m)
            if (cancelled) {
                line.insert(0, "ОТМЕНЁН. ")
            } else if (eating.isMatched(m)) {
                line.insert(0, "СОВПАЛО. ")
            }
            val row = AssistantUi.body(this, line.toString(), 13f)
            if (cancelled || eating.isMatched(m)) {
                val color = if (cancelled) AssistantUi.RED else AssistantUi.GREEN
                row.background = GradientDrawable().apply {
                    cornerRadius = AssistantUi.dp(this@AimiEatingActivity, 12).toFloat()
                    setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
                    setStroke(AssistantUi.dp(this@AimiEatingActivity, 1), color)
                }
                row.setPadding(AssistantUi.dp(this@AimiEatingActivity, 10), AssistantUi.dp(this@AimiEatingActivity, 8), AssistantUi.dp(this@AimiEatingActivity, 10), AssistantUi.dp(this@AimiEatingActivity, 8))
                row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, AssistantUi.dp(this@AimiEatingActivity, 6), 0, AssistantUi.dp(this@AimiEatingActivity, 6))
                }
            }
            card.addView(row)
        }
        return card
    }
}
