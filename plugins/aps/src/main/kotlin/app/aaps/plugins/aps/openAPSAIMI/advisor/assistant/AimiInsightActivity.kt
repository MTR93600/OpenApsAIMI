package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Summary, dashboard and live view for the AIMI SMB chain. Parameter names stay in English as in the AIMI settings;
 * explanations are in Russian on purpose, whatever the app language is. Reads the decision log; changes a setting only
 * after the user ticks it and taps Apply.
 */
class AimiInsightActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aimiStorageHelper: AimiStorageHelper

    private lateinit var root: LinearLayout
    private lateinit var history: AssistantHistory
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private var hours = 48
    private var result: InsightResult? = null
    private var liveCycle: LiveCycle? = null
    private var liveJob: Job? = null
    private var statsBefore: AssistantStats? = null
    private val checked = HashMap<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = AssistantHistory(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(root)
        })
        load()
    }

    override fun onResume() {
        super.onResume()
        liveJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val cycle = withContext(Dispatchers.IO) { AssistantLive.readTail(aimiStorageHelper.getAimiFile(LOG_FILE), 1).lastOrNull() }
                    if (cycle != null && cycle.timeMs != liveCycle?.timeMs) {
                        liveCycle = cycle
                        if (result != null) render()
                    }
                } catch (_: Throwable) {
                    // The live view is optional: ignore a failed refresh.
                }
                delay(60_000L)
            }
        }
    }

    override fun onPause() {
        liveJob?.cancel()
        super.onPause()
    }

    private fun load() {
        render(loading = true)
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            try {
                val data = withContext(Dispatchers.IO) {
                    val cycles = AssistantLive.readHours(aimiStorageHelper.getAimiFile(LOG_FILE), now, hours)
                    liveCycle = cycles.lastOrNull()
                    statsBefore = AssistantAnalyzer.stats(
                        cycles.filter { !it.bgMgdl.isNaN() }.map { AssistantSample(it.timeMs, it.bgMgdl, it.iobU, it.decision == "SMB_Delivery") },
                        now - 3 * 24L * 3600_000L, now,
                    )
                    AssistantInsightEngine.analyze(cycles, hours) { key, isBool -> readValue(key, isBool) }
                }
                result = data
                checked.clear()
                data.bundles.flatMap { it.changes }.forEach { checked[it.prefKey] = true }
                render()
            } catch (e: Throwable) {
                showError(e)
            }
        }
    }

    /** Shows the error on the screen, so a failure never closes the app. */
    private fun showError(e: Throwable) {
        root.removeAllViews()
        root.addView(AssistantTabs.bar(this, 1))
        root.addView(text("AIMI Assistant: Insight", 22f, bold = true))
        root.addView(text("Не удалось разобрать журнал: ${e.javaClass.simpleName}: ${e.message}", 14f))
        root.addView(text(e.stackTrace.take(8).joinToString("\n"), 11f))
        root.addView(Button(this).apply {
            text = "Повторить за 24 ч"
            setOnClickListener { hours = 24; load() }
        })
    }

    // ---- preference access ----

    private fun readValue(key: String, isBool: Boolean): Double? {
        if (isBool) return BooleanKey.entries.firstOrNull { it.key == key }?.let { if (preferences.get(it)) 1.0 else 0.0 }
        DoubleKey.entries.firstOrNull { it.key == key }?.let { return preferences.get(it) }
        return IntKey.entries.firstOrNull { it.key == key }?.let { preferences.get(it).toDouble() }
    }

    private fun writeValue(key: String, isBool: Boolean, value: Double) {
        if (isBool) {
            BooleanKey.entries.firstOrNull { it.key == key }?.let { preferences.put(it, value >= 0.5) }
            return
        }
        val d = DoubleKey.entries.firstOrNull { it.key == key }
        if (d != null) {
            preferences.put(d, value.coerceIn(d.min, d.max))
            return
        }
        IntKey.entries.firstOrNull { it.key == key }?.let { preferences.put(it, value.roundToInt().coerceIn(it.min, it.max)) }
    }

    private fun show(c: InsightChange, v: Double): String = when {
        c.isBool -> if (v >= 0.5) "ON" else "OFF"
        c.kind == AssistantSetting.Kind.INT -> v.roundToInt().toString()
        else -> String.format(Locale.US, "%.2f", v)
    }

    // ---- actions ----

    private fun applySelected() {
        val data = result ?: return
        val now = System.currentTimeMillis()
        val stats = statsBefore
        var count = 0
        data.bundles.flatMap { it.changes }.filter { checked[it.prefKey] == true }.forEach { c ->
            val from = readValue(c.prefKey, c.isBool) ?: return@forEach
            writeValue(c.prefKey, c.isBool, c.to)
            history.add(ChangeRecord(c.prefKey, from, c.to, now, stats))
            count++
        }
        AlertDialog.Builder(this)
            .setMessage(if (count == 0) "Ничего не отмечено, ничего не изменено." else "Применено изменений: $count. Через сутки сравни результат: кнопка «Откатить последнюю пачку» вернёт прежние значения.")
            .setPositiveButton("OK", null)
            .show()
        load()
    }

    private fun rollbackLast() {
        val all = history.all()
        val lastTime = all.maxOfOrNull { it.timeMs } ?: return
        val batch = all.filter { it.timeMs == lastTime }
        val now = System.currentTimeMillis()
        batch.forEach { r ->
            val isBool = BooleanKey.entries.any { it.key == r.prefKey }
            writeValue(r.prefKey, isBool, r.from)
            history.add(ChangeRecord(r.prefKey, r.to, r.from, now, null))
        }
        AlertDialog.Builder(this).setMessage("Возвращено значений: ${batch.size}.").setPositiveButton("OK", null).show()
        load()
    }

    // ---- screen ----

    private fun render(loading: Boolean = false) {
        root.removeAllViews()
        root.addView(AssistantTabs.bar(this, 1))
        root.addView(text("AIMI Assistant: Insight", 22f, bold = true))
        if (loading) {
            root.addView(text("Читаю журнал решений за $hours ч… Это может занять до минуты.", 14f))
            return
        }
        val data = result ?: return
        root.addView(periodRow())
        root.addView(card().apply { addView(text(data.summaryRu, 15f)) })

        root.addView(section("Резюме: что менять и почему"))
        if (data.bundles.isEmpty()) root.addView(text("Предложений нет.", 14f))
        data.bundles.forEach { root.addView(bundleCard(it)) }
        if (data.bundles.isNotEmpty()) {
            root.addView(Button(this).apply {
                text = "Применить выбранные"
                setOnClickListener { applySelected() }
            })
        }
        if (history.all().isNotEmpty()) {
            root.addView(Button(this).apply {
                text = "Откатить последнюю пачку"
                setOnClickListener { rollbackLast() }
            })
        }
        AssistantInsightEngine.profileHintRu(data.nightDrift)?.let {
            root.addView(section("Профиль (только подсказка)"))
            root.addView(card().apply { addView(text(it, 14f)) })
        }


        root.addView(section("Подъёмы без SMB"))
        if (data.stuck.isEmpty()) {
            root.addView(text("За период не было затяжных подъёмов, где защита обнулила SMB.", 13f))
        } else {
            data.stuck.forEach { root.addView(stuckCard(it)) }
        }

        root.addView(section("Дашборд: что влияло на SMB"))
        root.addView(text("Срезано дозы по стадиям за $hours ч (условные единицы: это предложения системы, а не поданный инсулин). Нажми на строку — объяснение.", 13f))
        val max = data.stages.maxOfOrNull { it.cutU }?.coerceAtLeast(0.0001) ?: 1.0
        data.stages.filter { it.cutU > 0.0005 }.forEach { root.addView(stageRow(it, max)) }
        val silent = data.stages.filter { it.cutU <= 0.0005 }
        root.addView(section("Не влияли ни разу"))
        root.addView(
            text(
                if (silent.isEmpty()) "Таких стадий нет."
                else silent.joinToString { it.info.name } + ". Настройки, связанные с этими стадиями, на твоих данных ничего не ограничивали: менять их бесполезно.",
                13f,
            )
        )

        root.addView(section("Live: последний цикл"))
        root.addView(liveCard())
    }

    private fun periodRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(24, 48, 168).forEach { h ->
            row.addView(Button(this).apply {
                text = if (h == 168) "7 дней" else "$h ч"
                isEnabled = h != hours
                setOnClickListener { hours = h; load() }
            })
        }
        return row
    }

    private fun bundleCard(b: InsightBundle): LinearLayout {
        val card = card()
        card.addView(text(b.titleRu, 16f, bold = true))
        card.addView(text(b.causeRu, 13f))
        b.changes.forEach { c ->
            val box = CheckBox(this).apply {
                text = "${c.name}: ${show(c, c.from)} → ${show(c, c.to)}"
                setTextColor(Color.WHITE)
                isChecked = checked[c.prefKey] == true
                setOnCheckedChangeListener { _, on -> checked[c.prefKey] = on }
            }
            card.addView(box)
            val reason = text(c.reasonRu + if (c.verified) "\n(связь проверена по коду)" else "\n(предположение, по коду не проверено)", 13f).apply { visibility = View.GONE }
            card.addView(TextView(this).apply {
                text = "Почему? ▾"
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(64, 0, 0, 8)
                setOnClickListener { reason.visibility = if (reason.visibility == View.GONE) View.VISIBLE else View.GONE }
            })
            card.addView(reason)
        }
        return card
    }


    private fun stuckCard(e: StuckRise): LinearLayout {
        val card = card()
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.startMs))
        card.addView(text("$time, ${e.minutes} мин: ${"%.1f".format(e.startMgdl / 18.0)} → ${"%.1f".format(e.peakMgdl / 18.0)} ммоль/л", 15f, bold = true))
        val info = AssistantStageInfo.info(if (e.sportSafety) "SAFETY_PRECAUTIONS_PKPD" else e.guard)
        card.addView(text("SMB обнулял шаг: ${info.name}" + if (e.sportSafety) " (защита «спорт» по шагам)" else "", 13f))
        card.addView(text("Циклов: ${e.cycles}. Система хотела подать за это время около ${"%.1f".format(e.wantedU)} условных Ед.", 13f))
        if (e.sportSafety) {
            card.addView(text("Это не настройка: защита «спорт» срабатывает по числу шагов и обнуляет SMB, даже если режим «Resting». Настройкой это не отключается, нужна правка кода.", 13f))
        }
        return card
    }

    private fun stageRow(s: StageSummary, max: Double): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            setOnClickListener { stageDialog(s) }
        }
        val typeRu = when (s.info.type) {
            StageType.TIME -> "время"
            StageType.SIZE -> "размер"
            StageType.OTHER -> "общее"
        }
        row.addView(text("${s.info.name}  ($typeRu)  ${String.format(Locale.US, "%.1f", s.cutU)}  —  ${s.cyclesWithCut} из ${s.cyclesSeen} циклов", 13f))
        val bar = View(this).apply { setBackgroundColor(Color.parseColor(if (s.info.type == StageType.TIME) "#F59E0B" else "#38BDF8")) }
        val width = ((s.cutU / max) * 1000).roundToInt().coerceAtLeast(6)
        row.addView(bar, LinearLayout.LayoutParams(width, 14))
        return row
    }

    private fun stageDialog(s: StageSummary) {
        val settings = if (s.info.settings.isEmpty()) "Настройки пока не определены." else "Настройки: " + s.info.settings.joinToString("; ")
        AlertDialog.Builder(this)
            .setTitle(s.info.name)
            .setMessage(s.info.ru + "\n\n" + settings + "\n\n" + if (s.info.verified) "Связь проверена по коду." else "Связь по коду проверена не полностью.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun liveCard(): LinearLayout {
        val card = card()
        val c = liveCycle
        if (c == null) {
            card.addView(text("Нет данных в журнале.", 13f))
            return card
        }
        val ago = ((System.currentTimeMillis() - c.timeMs) / 60_000L).toInt()
        card.addView(text("${timeFormat.format(Date(c.timeMs))} ($ago мин назад)  сахар ${String.format(Locale.US, "%.1f", c.bgMgdl / 18.0)}  IOB ${String.format(Locale.US, "%.2f", c.iobU)}", 14f, bold = true))
        card.addView(text("Решение: ${c.decision}, подано ${String.format(Locale.US, "%.2f", c.amountU)} Ед. Режим: ${c.patientMode ?: "—"}. Защита после гипо: ${c.postHypoTag ?: "—"}.", 13f))
        if (ago > 10) card.addView(text("Внимание: последнему циклу больше 10 минут. Проверь, что петля работает и помпа подключена.", 13f))
        val stages = c.stages.filter { it.beforeU > 0.0005 || it.afterU > 0.0005 }
        if (stages.isEmpty()) card.addView(text("В этом цикле SMB не предлагался.", 13f))
        val top = stages.maxOfOrNull { it.beforeU }?.coerceAtLeast(0.0001) ?: 1.0
        stages.forEach { st ->
            val mark = if (st.cutU > 0.0005) "  ✂ −${String.format(Locale.US, "%.2f", st.cutU)}" else ""
            card.addView(text("${st.name}: ${String.format(Locale.US, "%.2f", st.beforeU)} → ${String.format(Locale.US, "%.2f", st.afterU)}$mark", 12f))
            card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#34D399")) }, LinearLayout.LayoutParams(((st.afterU / top) * 800).roundToInt().coerceAtLeast(4), 8))
        }
        val biggest = stages.maxByOrNull { it.cutU }
        if (biggest != null && biggest.cutU > 0.0005) {
            val info = AssistantStageInfo.info(biggest.name)
            card.addView(text("Больше всего срезала стадия ${biggest.name}. ${info.ru}", 13f))
        }
        return card
    }

    // ---- views ----

    private fun section(t: String) = text(t, 18f, bold = true).apply { setPadding(0, 40, 0, 8) }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.parseColor("#CBD5E1"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(24, 24, 24, 24)
        setBackgroundColor(Color.parseColor("#1E293B"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
    }

    private companion object {
        const val LOG_FILE = "AIMI_Decisions.jsonl"
    }
}
