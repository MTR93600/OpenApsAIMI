package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.openAPSAIMI.energy.EnergyMeter
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The "Energy" screen (the lightning button): how much battery went since the last charge and which parts of the
 * work were running. It only measures and shows; it changes no setting.
 */
class AimiEnergyActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var storageHelper: AimiStorageHelper

    private lateinit var root: LinearLayout
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("ru", "RU"))
    private val dateTimeFormat = SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU"))
    private var journalLimit = 120
    private val ru = Locale("ru", "RU")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EnergyMeter.attach(this)
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
        render()
    }

    // ---- model ----

    private class Row(val tag: String, val runs: Int, val wallMs: Long, val cpuMs: Long)

    private class Model(
        val charge: EnergyMeter.ChargeInfo,
        val minutes: List<EnergyMeter.Minute>,
        val rows: List<Row>,
        val appCpuMs: Long,
        val measuredCpuMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    private fun buildModel(): Model {
        val charge = EnergyMeter.chargeInfo(this)
        val minutes = EnergyMeter.readMinutes(this, charge.sinceMs)
        val totals = HashMap<String, LongArray>() // runs, wall, cpu
        minutes.forEach { m ->
            m.features.forEach { (tag, v) ->
                val t = totals.getOrPut(tag) { LongArray(3) }
                t[0] += v.first.toLong()
                t[1] += v.second
                t[2] += v.third
            }
        }
        val rows = totals.map { (tag, t) -> Row(tag, t[0].toInt(), t[1], t[2]) }.sortedByDescending { it.cpuMs }
        return Model(
            charge = charge,
            minutes = minutes,
            rows = rows,
            appCpuMs = minutes.sumOf { it.appCpuMs },
            measuredCpuMs = rows.sumOf { it.cpuMs },
            rxBytes = minutes.sumOf { it.rxBytes },
            txBytes = minutes.sumOf { it.txBytes },
        )
    }

    private fun appPercentSetting(): Double =
        getSharedPreferences(EnergyMeter.PREFS, MODE_PRIVATE).getFloat("app_pct", 0f).toDouble()

    // ---- screen ----

    private fun render() {
        root.removeAllViews()
        root.addView(AssistantUi.body(this, "⚡ Энергия", 22f, Color.WHITE, bold = true))
        val model = buildModel()
        root.addView(chargeCard(model))
        root.addView(appPercentCard())
        root.addView(suspectsCard(model))
        root.addView(hoursCard(model))
        root.addView(journalCard(model))
        root.addView(
            AssistantUi.button(this, "Сохранить отчёт в файл").apply {
                setOnClickListener {
                    val file = storageHelper.getAimiFile("AIMI_Energy_report.txt")
                    try {
                        file.writeText(reportText(model))
                        text = "Сохранено: ${file.name}"
                    } catch (e: Exception) {
                        text = "Не удалось сохранить: ${e.javaClass.simpleName}"
                    }
                }
            }
        )
    }

    private fun chargeCard(m: Model): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Заряд с последней зарядки"))
        val c = m.charge
        if (c.sinceMs == 0L) {
            card.addView(AssistantUi.hint(this, "Замер только начался. Данные появятся через несколько минут работы цикла."))
            return card
        }
        val elapsedMin = ((System.currentTimeMillis() - c.sinceMs) / 60_000L).coerceAtLeast(1L)
        val drop = (c.levelAtStart - c.levelNow).coerceAtLeast(0)
        val perHour = drop * 60.0 / elapsedMin
        card.addView(AssistantUi.body(this, "С ${dateTimeFormat.format(Date(c.sinceMs))}: ${c.levelAtStart}% → ${c.levelNow}%", 18f, Color.WHITE, bold = true))
        card.addView(AssistantUi.body(this, "Ушло $drop% за ${elapsedMin / 60} ч ${elapsedMin % 60} мин, в среднем ${AssistantUi.num(perHour, 1)}% в час"))
        if (c.plugged) card.addView(AssistantUi.body(this, "Сейчас телефон на зарядке.", 14f, AssistantUi.GREEN))
        if (c.approximate) {
            card.addView(AssistantUi.hint(this, "Точный момент последней зарядки неизвестен: замер начался, когда телефон уже был на батарее. Как только вы поставите телефон на зарядку и снимете, отсчёт станет точным."))
        }
        val last = m.minutes.lastOrNull()
        if (last != null) {
            card.addView(AssistantUi.hint(this, "Последний замер ${timeFormat.format(Date(last.timeMs))}: температура ${AssistantUi.num(last.tempC, 1)} °C, ток ${last.milliamp} мА, напряжение ${last.millivolt} мВ."))
        }
        return card
    }

    private fun appPercentCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Расход приложения по данным Android"))
        card.addView(
            AssistantUi.hint(
                this,
                "Android не даёт приложению прочитать собственный расход. Откройте Настройки → Батарея → Использование по приложениям, найдите GIS и введите процент. Тогда доли ниже пересчитаются в проценты заряда. Это можно не вводить."
            )
        )
        val input = AssistantUi.numberField(this, appPercentSetting(), 1)
        card.addView(input)
        val status = AssistantUi.hint(this, "")
        card.addView(
            AssistantUi.button(this, "Сохранить").apply {
                setOnClickListener {
                    val v = AssistantUi.parse(input)
                    if (v == null || v < 0.0 || v > 100.0) {
                        status.text = "Введите число от 0 до 100."
                    } else {
                        getSharedPreferences(EnergyMeter.PREFS, MODE_PRIVATE).edit().putFloat("app_pct", v.toFloat()).apply()
                        render()
                    }
                }
            }
        )
        card.addView(status)
        return card
    }

    private fun suspectsCard(m: Model): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Что работало и сколько"))
        if (m.rows.isEmpty()) {
            card.addView(AssistantUi.hint(this, "Пока нет данных. Замер идёт, пока работает цикл."))
            return card
        }
        val appPct = appPercentSetting()
        val measuredShareOfApp = if (m.appCpuMs > 0L) m.measuredCpuMs * 100.0 / m.appCpuMs else 0.0
        card.addView(
            AssistantUi.hint(
                this,
                "Доля считается по времени процессора, которое потратила каждая функция. Bluetooth и экран в процессор не входят, поэтому для команд помпе смотрите число запусков. Из всего процессора приложения (${m.appCpuMs / 1000} с) замерено функциями AIMI ${AssistantUi.num(measuredShareOfApp, 0)}%, остальное: ядро AAPS, Nightscout, экран."
            )
        )
        val top = m.rows.first()
        val topFeature = EnergyMeter.featureOf(top.tag)
        val topShare = if (m.measuredCpuMs > 0L) top.cpuMs * 100.0 / m.measuredCpuMs else 0.0
        card.addView(AssistantUi.body(this, "Больше всего процессора: ${topFeature.title} (${AssistantUi.num(topShare, 0)}%)", 15f, AssistantUi.AMBER, bold = true))
        card.addView(AssistantUi.hint(this, "Что это регулирует: ${topFeature.setting}"))
        m.rows.forEach { r ->
            val f = EnergyMeter.featureOf(r.tag)
            val share = if (m.measuredCpuMs > 0L) r.cpuMs * 100.0 / m.measuredCpuMs else 0.0
            card.addView(AssistantUi.label(this, "${f.title}"))
            val battery = if (appPct > 0.0) ", примерно ${AssistantUi.num(appPct * measuredShareOfApp / 100.0 * share / 100.0, 2)}% заряда" else ""
            card.addView(AssistantUi.body(this, "Доля ${AssistantUi.num(share, 1)}%$battery", 14f))
            card.addView(AssistantUi.hint(this, "Запусков ${r.runs}, время ${formatMs(r.wallMs)}, процессор ${formatMs(r.cpuMs)}. Настройка: ${f.setting}"))
        }
        card.addView(AssistantUi.hint(this, "Сеть приложения за период: принято ${m.rxBytes / 1024} КБ, отправлено ${m.txBytes / 1024} КБ."))
        return card
    }

    private fun hoursCard(m: Model): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "По часам"))
        card.addView(AssistantUi.hint(this, "Сколько процентов заряда ушло в каждый час и сколько работал процессор приложения. Если заряд падает при малой работе, расход идёт не от AIMI."))
        if (m.minutes.isEmpty()) {
            card.addView(AssistantUi.hint(this, "Нет данных."))
            return card
        }
        val byHour = m.minutes.groupBy { it.timeMs / 3600_000L }
        byHour.entries.sortedByDescending { it.key }.take(24).forEach { (hour, list) ->
            val first = list.firstOrNull { it.level >= 0 } ?: return@forEach
            val last = list.lastOrNull { it.level >= 0 } ?: return@forEach
            val drop = first.level - last.level
            val runs = list.sumOf { it.features.values.sumOf { v -> v.first } }
            val cpu = list.sumOf { it.appCpuMs }
            val plugged = if (list.any { it.plugged }) " (зарядка)" else ""
            card.addView(
                AssistantUi.body(
                    this,
                    "${timeFormat.format(Date(hour * 3600_000L))}: ${if (drop >= 0) "-$drop" else "+${-drop}"}%, запусков $runs, процессор ${formatMs(cpu)}$plugged",
                    13.5f
                )
            )
        }
        return card
    }

    private fun journalCard(m: Model): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Журнал по минутам"))
        card.addView(AssistantUi.hint(this, "Минуты, в которых что-то работало: во сколько, какая функция и какая настройка за неё отвечает. Новые сверху."))
        val shown = m.minutes.filter { it.features.isNotEmpty() }.reversed()
        shown.take(journalLimit).forEach { min ->
            card.addView(AssistantUi.label(this, "${timeFormat.format(Date(min.timeMs))} · заряд ${min.level}% · ${AssistantUi.num(min.tempC, 0)} °C"))
            min.features.entries.sortedByDescending { it.value.third }.forEach { (tag, v) ->
                val f = EnergyMeter.featureOf(tag)
                card.addView(AssistantUi.body(this, "${f.title} ×${v.first}: ${formatMs(v.second)}, процессор ${formatMs(v.third)}", 13f))
                card.addView(AssistantUi.hint(this, "Настройка: ${f.setting}"))
            }
        }
        if (shown.size > journalLimit) {
            card.addView(
                AssistantUi.button(this, "Показать ещё").apply {
                    setOnClickListener {
                        journalLimit += 120
                        render()
                    }
                }
            )
        }
        if (shown.isEmpty()) card.addView(AssistantUi.hint(this, "Пока пусто."))
        return card
    }

    // ---- text report ----

    private fun reportText(m: Model): String {
        val sb = StringBuilder()
        val c = m.charge
        sb.append("ЭНЕРГИЯ GIS\n")
        sb.append("Отчёт: ").append(dateTimeFormat.format(Date())).append("\n")
        if (c.sinceMs > 0L) {
            val elapsedMin = ((System.currentTimeMillis() - c.sinceMs) / 60_000L).coerceAtLeast(1L)
            sb.append("С ").append(dateTimeFormat.format(Date(c.sinceMs))).append(": ")
                .append(c.levelAtStart).append("% -> ").append(c.levelNow).append("% за ")
                .append(elapsedMin / 60).append(" ч ").append(elapsedMin % 60).append(" мин")
            if (c.approximate) sb.append(" (момент зарядки приблизительный)")
            sb.append("\n")
        }
        sb.append("Расход приложения по Android: ").append(AssistantUi.num(appPercentSetting(), 1)).append("%\n")
        sb.append("Процессор приложения: ").append(m.appCpuMs / 1000).append(" с, замерено функциями: ").append(m.measuredCpuMs / 1000).append(" с\n")
        sb.append("Сеть: принято ").append(m.rxBytes / 1024).append(" КБ, отправлено ").append(m.txBytes / 1024).append(" КБ\n\n")
        sb.append("ФУНКЦИИ (по процессору)\n")
        m.rows.forEach { r ->
            val f = EnergyMeter.featureOf(r.tag)
            val share = if (m.measuredCpuMs > 0L) r.cpuMs * 100.0 / m.measuredCpuMs else 0.0
            sb.append(f.title).append(": доля ").append(AssistantUi.num(share, 1)).append("%, запусков ").append(r.runs)
                .append(", время ").append(formatMs(r.wallMs)).append(", процессор ").append(formatMs(r.cpuMs))
                .append(", настройка: ").append(f.setting).append("\n")
        }
        sb.append("\nЖУРНАЛ ПО МИНУТАМ\n")
        m.minutes.filter { it.features.isNotEmpty() }.forEach { min ->
            sb.append(dateTimeFormat.format(Date(min.timeMs))).append(" заряд ").append(min.level).append("% ")
                .append(AssistantUi.num(min.tempC, 0)).append("C ток ").append(min.milliamp).append("мА: ")
            sb.append(
                min.features.entries.joinToString("; ") { (tag, v) ->
                    "${EnergyMeter.featureOf(tag).title} x${v.first} ${formatMs(v.second)} cpu ${formatMs(v.third)}"
                }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun formatMs(ms: Long): String = when {
        ms >= 60_000L -> String.format(ru, "%.1f мин", ms / 60_000.0)
        ms >= 1000L -> String.format(ru, "%.1f с", ms / 1000.0)
        else -> "$ms мс"
    }
}
