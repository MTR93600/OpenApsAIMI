package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileAdvisorActivity
import app.aaps.plugins.aps.openAPSAIMI.ISF.ObservedIsfStore
import app.aaps.plugins.aps.openAPSAIMI.meal.EatingEngine
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** New settings assistant: finds problem episodes, explains why, and changes a setting only after a tap. */
class AimiAssistantActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aimiStorageHelper: AimiStorageHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer

    private lateinit var root: LinearLayout
    private lateinit var history: AssistantHistory
    private var samples: List<AssistantSample> = emptyList()
    private var coefficientRows: List<CoefficientsAnalyzer.Row>? = null
    private var hourlyCoefficientRows: List<HourlyCoefficientRow>? = null
    private val eating by lazy { EatingEngine(this) }
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // All glucose texts and inputs follow the units chosen in AndroidAPS.
        AimiUnits.mmol = profileFunction.getUnits() == GlucoseUnit.MMOL
        history = AssistantHistory(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(AssistantUi.BG)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AssistantUi.BG)
            addView(root)
        })
        render(loading = true)
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            samples = withContext(Dispatchers.IO) {
                AssistantAnalyzer.readSamples(aimiStorageHelper.getAimiFile("AIMI_Decisions.jsonl"), now)
            }
            coefficientRows = withContext(Dispatchers.IO) { loadCoefficients() }
            hourlyCoefficientRows = withContext(Dispatchers.IO) { loadHourlyCoefficients(now) }
            render(loading = false)
        }
    }

    // ---- values ----

    private fun readValue(s: AssistantSetting): Double = when (s.kind) {
        AssistantSetting.Kind.DOUBLE -> preferences.get(DoubleKey.entries.first { it.key == s.prefKey })
        AssistantSetting.Kind.INT -> preferences.get(IntKey.entries.first { it.key == s.prefKey }).toDouble()
    }

    private fun writeValue(s: AssistantSetting, value: Double) {
        val v = value.coerceIn(s.min, s.max)
        when (s.kind) {
            AssistantSetting.Kind.DOUBLE -> preferences.put(DoubleKey.entries.first { it.key == s.prefKey }, v)
            AssistantSetting.Kind.INT -> preferences.put(IntKey.entries.first { it.key == s.prefKey }, v.toInt())
        }
    }

    private fun fmt(s: AssistantSetting, v: Double): String =
        if (s.kind == AssistantSetting.Kind.INT) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun applyChange(s: AssistantSetting, to: Double) {
        val now = System.currentTimeMillis()
        val from = readValue(s)
        val before = AssistantAnalyzer.stats(samples, now - 3 * 24L * 3600_000L, now)
        writeValue(s, to)
        history.add(ChangeRecord(s.prefKey, from, to, now, before))
    }

    // ---- screen ----

    private fun render(loading: Boolean) {
        root.removeAllViews()
        root.addView(title(getString(R.string.aimi_as_title)))
        root.addView(AssistantTabs.bar(this, 0))
        // Two groups: the general settings work always; the eating settings work only while «Я ем» runs.
        root.addView(section("Общие настройки AIMI"))
        root.addView(AssistantUi.hint(this, "Действуют всегда: с режимом «Я ем» и без него."))
        root.addView(sizeCard())
        root.addView(intervalsCard())
        root.addView(fastRiseCard())
        root.addView(postHypoCard())
        root.addView(trainingCard())
        root.addView(section("Режим «Я ем»"))
        root.addView(AssistantUi.hint(this, "Действуют только когда режим «Я ем» идёт (запущен кнопкой или автостартом)."))
        root.addView(eatingSettingsCard())
        root.addView(section("Сброс и анализ"))
        root.addView(defaultsCard())
        root.addView(coefficientsCard())
        if (loading) {
            root.addView(text(getString(R.string.aimi_as_loading)))
            return
        }
        val now = System.currentTimeMillis()
        val lows = AssistantAnalyzer.lowEpisodes(samples)
        val late = AssistantAnalyzer.lateEpisodes(samples)
        val stats = AssistantAnalyzer.stats(samples, now - 3 * 24L * 3600_000L, now)

        root.addView(section(getString(R.string.aimi_as_overview)))
        root.addView(
            text(
                if (stats == null) getString(R.string.aimi_as_no_data)
                else getString(R.string.aimi_as_stats, stats.tirPercent, stats.lowPercent, stats.meanMmol, lows.size, late.size)
            )
        )
        lows.forEach {
            root.addView(
                text(getString(R.string.aimi_as_low_line, dateFormat.format(Date(it.timeMs)), it.minMmol, it.smbCount2h, it.smbMinutesBetween, it.iobStart))
            )
        }

        root.addView(section(getString(R.string.aimi_as_proposals)))
        // One change at a time: after a change we wait one day, so the result can be seen.
        val last = history.all().maxByOrNull { it.timeMs }
        val waitMs = 24L * 3600_000L
        if (last != null && now - last.timeMs < waitMs) {
            val hoursLeft = ((waitMs - (now - last.timeMs)) / 3600_000L).toInt() + 1
            val name = AssistantCatalog.byKey(last.prefKey)?.englishName ?: last.prefKey
            root.addView(text(getString(R.string.aimi_as_waiting, name, dateFormat.format(Date(last.timeMs)), hoursLeft)))
        } else {
            val proposals = AssistantProposals.build(lows, late) { readValue(it) }
            if (proposals.isEmpty()) {
                root.addView(text(getString(R.string.aimi_as_nothing)))
            } else {
                root.addView(proposalCard(proposals.first()))
                if (proposals.size > 1) root.addView(text(getString(R.string.aimi_as_more, proposals.drop(1).joinToString { it.setting.englishName })))
            }
        }

        root.addView(section(getString(R.string.aimi_as_settings)))
        AssistantCatalog.all.forEach { root.addView(settingRow(it)) }

        root.addView(Button(this).apply {
            text = getString(R.string.aimi_as_open_old)
            setOnClickListener { AimiHaptics.click(this@AimiAssistantActivity); startActivity(Intent(this@AimiAssistantActivity, AimiProfileAdvisorActivity::class.java)) }
        })
    }

    private fun trainingCard(): LinearLayout {
        val card = box()
        card.addView(text(getString(R.string.aimi_adv_train_q_title), 16f, bold = true))
        card.addView(text(getString(R.string.aimi_adv_train_q_desc), 13f))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.aimi_adv_train_q_hint)
            setTextColor(Color.WHITE)
            setText(String.format(Locale.US, "%.2f", preferences.get(DoubleKey.OApsAIMISmbTrainingMinQuality)))
        }
        card.addView(input)
        val relaxed = SwitchCompat(this).apply {
            text = getString(R.string.aimi_adv_train_f_title)
            setTextColor(Color.WHITE)
            isChecked = preferences.get(BooleanKey.OApsAIMISmbTrainingRelaxedFilter)
        }
        card.addView(text(getString(R.string.aimi_adv_train_f_desc), 13f))
        card.addView(relaxed)
        val status = text("", 13f)
        card.addView(Button(this).apply {
            text = getString(R.string.aimi_adv_train_q_apply)
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val value = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || value !in 0.0..1.0) {
                    status.text = getString(R.string.aimi_adv_train_q_invalid)
                } else {
                    preferences.put(DoubleKey.OApsAIMISmbTrainingMinQuality, value)
                    preferences.put(BooleanKey.OApsAIMISmbTrainingRelaxedFilter, relaxed.isChecked)
                    status.text = getString(R.string.aimi_adv_train_q_saved)
                }
            }
        })
        card.addView(status)
        return card
    }

    private fun postHypoCard(): LinearLayout {
        val card = box()
        card.addView(text(getString(R.string.aimi_as_ph_title), 16f, bold = true))
        card.addView(text(getString(R.string.aimi_as_ph_desc), 13f))
        val marginInput = AssistantUi.numberField(this, preferences.get(DoubleKey.OApsAIMIPostHypoReleaseMarginMgdl))
        val deltaInput = AssistantUi.numberField(this, preferences.get(DoubleKey.OApsAIMIPostHypoReleaseDeltaMgdl))
        card.addView(text(getString(R.string.aimi_as_ph_margin_label), 13f))
        card.addView(marginInput)
        card.addView(text(getString(R.string.aimi_as_ph_delta_label), 13f))
        card.addView(deltaInput)
        val status = text("", 13f)
        card.addView(Button(this).apply {
            text = getString(R.string.aimi_as_ph_apply)
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val margin = marginInput.text.toString().replace(',', '.').toDoubleOrNull()
                val delta = deltaInput.text.toString().replace(',', '.').toDoubleOrNull()
                val marginKey = DoubleKey.OApsAIMIPostHypoReleaseMarginMgdl
                val deltaKey = DoubleKey.OApsAIMIPostHypoReleaseDeltaMgdl
                if (margin == null || delta == null || margin !in marginKey.min..marginKey.max || delta !in deltaKey.min..deltaKey.max) {
                    status.text = getString(R.string.aimi_as_ph_invalid)
                } else {
                    preferences.put(marginKey, margin)
                    preferences.put(deltaKey, delta)
                    status.text = getString(R.string.aimi_as_ph_saved)
                }
            }
        })
        card.addView(status)
        return card
    }


    /** The values that decide the size of SMB and how fast the dose grows at the start of a meal rise. */
    private fun sizeCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Размер SMB"))
        card.addView(AssistantUi.hint(this, "Порог ${AimiUnits.g(120.0)} делит два режима: ниже него действует Max SMB, выше него High BG Max SMB."))
        val maxKey = DoubleKey.OApsAIMIMaxSMB
        val hbKey = DoubleKey.OApsAIMIHighBGMaxSMB
        val lowKey = DoubleKey.OApsAIMIFrontDoseRiseLow
        val fullKey = DoubleKey.OApsAIMIFrontDoseRiseFull
        val mmolDiv = AimiUnits.div()
        val maxInput = AssistantUi.numberField(this, preferences.get(maxKey))
        val hbInput = AssistantUi.numberField(this, preferences.get(hbKey))
        // The two rise limits are stored in mg/dL per 5 minutes and shown in the user's units per 5 minutes.
        val riseDecimals = if (AimiUnits.mmol) 2 else 1
        val lowInput = AssistantUi.numberField(this, preferences.get(lowKey) / mmolDiv, riseDecimals)
        val fullInput = AssistantUi.numberField(this, preferences.get(fullKey) / mmolDiv, riseDecimals)

        card.addView(AssistantUi.label(this, "Max SMB, ЕД (${maxKey.min} … ${maxKey.max})"))
        card.addView(AssistantUi.hint(this, "Потолок одной дозы при сахаре ниже ${AimiUnits.g(120.0)}. Исключение: в начале подтверждённого роста от еды потолок временно равен половине High BG Max SMB (с ${AimiUnits.g(95.0)})."))
        card.addView(maxInput)
        card.addView(AssistantUi.label(this, "High BG Max SMB, ЕД (${hbKey.min} … ${hbKey.max})"))
        card.addView(AssistantUi.hint(this, "Потолок одной дозы при сахаре от ${AimiUnits.g(120.0)}. Полностью действует от ${AimiUnits.g(140.0)}, между ${AimiUnits.num(120.0)} и ${AimiUnits.num(140.0)} даётся 85%. При быстром росте на сахаре ниже ${AimiUnits.num(120.0)} берётся половина этого значения."))
        card.addView(hbInput)
        card.addView(AssistantUi.label(this, "Старт роста: нижняя граница (${AimiUnits.num(lowKey.min, 2)} … ${AimiUnits.rate5(lowKey.max)})"))
        card.addView(AssistantUi.hint(this, "Ниже этого прироста за 5 минут минимальной стартовой дозы нет: считаем это шумом датчика. Стандартное значение ${AimiUnits.rate5(3.0)}."))
        card.addView(lowInput)
        card.addView(AssistantUi.label(this, "Старт роста: полная доза (${AimiUnits.num(fullKey.min, 2)} … ${AimiUnits.rate5(fullKey.max)})"))
        card.addView(AssistantUi.hint(this, "С этого прироста за 5 минут и выше стартовая доза равна половине High BG Max SMB. Между границами доза растёт плавно. Стандартное значение ${AimiUnits.rate5(8.0)}."))
        card.addView(fullInput)
        val status = AssistantUi.hint(this, "")
        card.addView(AssistantUi.button(this, "Сохранить размер SMB").apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val a = AssistantUi.parse(maxInput)
                val b = AssistantUi.parse(hbInput)
                val d = AssistantUi.parse(lowInput)?.times(mmolDiv)
                val e = AssistantUi.parse(fullInput)?.times(mmolDiv)
                if (a == null || b == null || d == null || e == null ||
                    a !in maxKey.min..maxKey.max || b !in hbKey.min..hbKey.max ||
                    d !in lowKey.min..lowKey.max || e !in fullKey.min..fullKey.max || e <= d
                ) {
                    status.text = "Значение вне допустимого диапазона."
                } else {
                    preferences.put(maxKey, a)
                    preferences.put(hbKey, b)
                    preferences.put(lowKey, d)
                    preferences.put(fullKey, e)
                    status.text = "Сохранено."
                }
            }
        })
        card.addView(status)
        return card
    }

    /**
     * "Fast rise (meal)": the 5-minute rise that counts as a real meal rise. Stored in mg/dL per 5 minutes in the
     * eating prefs, shown in the user's units.
     */
    private fun fastRiseCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Быстрый рост (еда)"))
        val mmolDiv = AimiUnits.div()
        val minMgdl = EatingEngine.FAST_RISE_MIN_MGDL5
        val maxMgdl = EatingEngine.FAST_RISE_MAX_MGDL5
        val decimals = if (AimiUnits.mmol) 2 else 1
        card.addView(AssistantUi.label(this, "Рост за 5 минут (${AimiUnits.num(minMgdl, 2)} … ${AimiUnits.rate5(maxMgdl)})"))
        card.addView(
            AssistantUi.hint(
                this,
                "С какого роста сахар считается ростом от еды. Работает так:\n" +
                    "• автостарт «Я ем»: такой рост на 3 замерах подряд; если рост больше ещё на ${AimiUnits.rate5(2.0)}, стартовая доза полная, иначе половина;\n" +
                    "• в режиме «Я ем»: при таком росте одна доза SMB может быть до High BG Max SMB;\n" +
                    "• без режима: базал может выйти из узкого коридора (не выше 1,5× профиля при сахаре ниже ${AimiUnits.g(117.0)});\n" +
                    "• выход после гипо: инсулин на еду разрешается при таком росте.\n" +
                    "Меньше значение — раньше реакция, но чаще срабатывает на шум датчика. Стандартное значение ${AimiUnits.rate5(EatingEngine.FAST_RISE_DEFAULT_MGDL5)}.",
            ),
        )
        val input = AssistantUi.numberField(this, eating.fastRiseMgdl5() / mmolDiv, decimals)
        card.addView(input)
        val status = AssistantUi.hint(this, "")
        card.addView(AssistantUi.button(this, "Сохранить быстрый рост").apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val value = AssistantUi.parse(input)?.times(mmolDiv)
                if (value == null || value < minMgdl - 0.01 || value > maxMgdl + 0.01) {
                    status.text = "Значение вне допустимого диапазона."
                } else {
                    eating.setFastRiseMgdl5(value)
                    status.text = "Сохранено."
                }
            }
        })
        card.addView(status)
        return card
    }

    /**
     * All SMB intervals in one card. The general "SMB interval" is a lock in the loop: no SMB goes out sooner than it,
     * so a smaller value in any other line has no effect. The effective interval is the larger of the two.
     */
    private fun intervalsCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "SMB интервал (минуты)"))
        card.addView(AssistantUi.hint(this, "Минимум между двумя SMB. Действует всегда. Если сахар ниже цели, интервал дополнительно удваивается (не больше 20 минут)."))
        data class Line(val key: IntKey, val title: String, val hint: String)
        val gate = Line(
            IntKey.ApsMaxSmbFrequency, "SMB интервал",
            "Действует всегда и при любом сахаре. Это минимум между двумя SMB. Он же задаёт, как часто работает цикл AIMI в режиме Autodrive."
        )
        val lines = listOf(
            gate,
        )
        val inputs = lines.map { line ->
            card.addView(AssistantUi.label(this, "${line.title} (${line.key.min} … ${line.key.max})"))
            card.addView(AssistantUi.hint(this, line.hint))
            AssistantUi.numberField(this, preferences.get(line.key).toDouble(), 0).also { card.addView(it) }
        }
        val status = AssistantUi.hint(this, "")
        card.addView(AssistantUi.button(this, "Сохранить интервал").apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val values = inputs.map { AssistantUi.parse(it)?.toInt() }
                val bad = lines.indices.any { n -> values[n] == null || values[n]!! !in lines[n].key.min..lines[n].key.max }
                if (bad) {
                    status.text = "Значение вне допустимого диапазона."
                } else {
                    lines.indices.forEach { n -> preferences.put(lines[n].key, values[n]!!) }
                    status.text = "Сохранено."
                }
            }
        })
        card.addView(status)
        return card
    }

    /** One button that puts the main settings back to the default values. */
    private fun defaultsCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Значения по умолчанию"))
        card.addView(AssistantUi.hint(this, "Стартовая доза 1,0 ЕД, предел на приём 8 ЕД, остановка по прогнозу ${AimiUnits.g(108.0)}, SMB интервал 3 мин, быстрый рост ${AimiUnits.rate5(EatingEngine.FAST_RISE_DEFAULT_MGDL5)}."))
        card.addView(AssistantUi.button(this, "Вернуть по умолчанию", AssistantUi.RED).apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                AlertDialog.Builder(this@AimiAssistantActivity)
                    .setTitle("Вернуть значения по умолчанию?")
                    .setPositiveButton("Вернуть") { _, _ -> AimiHaptics.click(this@AimiAssistantActivity);
                        preferences.put(DoubleKey.OApsAIMIEatingStartDoseU, 1.0)
                        preferences.put(DoubleKey.OApsAIMIEatingMaxWindowU, 8.0)
                        preferences.put(DoubleKey.OApsAIMIEatingStopForecastMgdl, 108.0)
                        preferences.put(IntKey.ApsMaxSmbFrequency, 3)
                        eating.setFastRiseMgdl5(EatingEngine.FAST_RISE_DEFAULT_MGDL5)
                        render(loading = false)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        return card
    }

    /** Settings of the eating mode ("I eat" button) and the state of its learning. */
    private fun eatingSettingsCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Настройки режима"))
        card.addView(AssistantUi.hint(this, "Кнопка «Я ем» на главном экране запускает режим. Система сама оценивает съеденное по росту сахара. Углеводы вводить не нужно."))
        val startKey = DoubleKey.OApsAIMIEatingStartDoseU
        val maxKey = DoubleKey.OApsAIMIEatingMaxWindowU
        val stopKey = DoubleKey.OApsAIMIEatingStopForecastMgdl
        val mmolDiv = AimiUnits.div()
        val startInput = AssistantUi.numberField(this, preferences.get(startKey), 2)
        val maxInput = AssistantUi.numberField(this, preferences.get(maxKey))
        val stopInput = AssistantUi.numberField(this, preferences.get(stopKey) / mmolDiv, if (AimiUnits.mmol) 1 else 0)

        card.addView(AssistantUi.label(this, "Стартовая доза по умолчанию, ЕД (${startKey.min} … ${startKey.max})"))
        card.addView(
            AssistantUi.hint(
                this,
                "Первая доза после нажатия кнопки. Дальше режим добавляет дозы по плану для выбранного размера. Уходит сразу, если сахар от ${AimiUnits.g(90.0)}, не падает, прогноз выше ${AimiUnits.g(80.0)} и IOB не выше 3,5 ЕД."
            )
        )
        val mealHistory = eating.meals()
        val matched = mealHistory.filter { eating.isMatched(it) && it.optString("note") != "fat" }
        val lastLearned = mealHistory.lastOrNull { it.optDouble("newK", 1.0) != it.optDouble("k", 1.0) }
        val lastLearnedText = lastLearned?.optLong("startMs", 0L)?.takeIf { it > 0L }?.let {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "пока нет"
        card.addView(AssistantUi.body(this, "Последнее изменение обучения: $lastLearnedText. Успешных обычных приёмов: ${matched.size}."))
        val perXe = eating.goodMealUPerXe()
        card.addView(AssistantUi.body(this, "Инсулин на 1 ХЕ: ${perXe?.let { AssistantUi.num(it.first, 2) + " ЕД (${it.second} приёмов)" } ?: "пока мало данных"}."))
        val excluded = mealHistory.count { eating.isCancelled(it) || it.optString("note") == "fat" || it.optInt("verdict", 0) < 0 }
        card.addView(AssistantUi.hint(this, "Исключено из обычного обучения: $excluded (отменённые, прерванные, жирные или с низким сахаром)."))
        card.addView(startInput)
        card.addView(AssistantUi.label(this, "Предел инсулина на приём пищи, ЕД (${maxKey.min} … ${maxKey.max})"))
        card.addView(AssistantUi.hint(this, "Предохранитель: сколько инсулина всего (стартовая доза, SMB и лишняя временная база) может уйти за окно «Я ем» при размере «большой». «Средний» = 70%, «малый» = 40% от этого значения. При сахаре выше ${AimiUnits.g(150.0)} и росте предохранитель не мешает. Окно длится до 3 часов (для «жирной» до 5)."))
        card.addView(maxInput)
        card.addView(AssistantUi.label(this, "Остановка по прогнозу, ${AimiUnits.unit()} (${AimiUnits.num(stopKey.min)} … ${AimiUnits.num(stopKey.max)})"))
        card.addView(AssistantUi.hint(this, "Когда рост замедляется и прогноз ниже этого значения, новые SMB прекращаются. Пока рост ускоряется или идёт ровно, прогноз не учитывается: он не знает про несъеденные углеводы. Стандартное значение ${AimiUnits.g(108.0)}."))
        card.addView(stopInput)
        val autoStart = SwitchCompat(this).apply {
            text = "Автостарт «Я ем»"
            setTextColor(Color.WHITE)
            isChecked = eating.autoStartEnabled()
            setOnCheckedChangeListener { _, checked -> AimiHaptics.click(this@AimiAssistantActivity); eating.setAutoStartEnabled(checked) }
        }
        card.addView(autoStart)
        card.addView(AssistantUi.hint(this, "Если включено, режим сам запускается при уверенном росте сахара после еды (половина стартовой дозы). Если выключено, работает обычный AIMI."))
        val status = AssistantUi.hint(this, "")
        card.addView(AssistantUi.button(this, "Сохранить настройки «Я ем»").apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                val b = AssistantUi.parse(startInput)
                val c = AssistantUi.parse(maxInput)
                val d = AssistantUi.parse(stopInput)?.times(mmolDiv)
                if (b == null || c == null || d == null ||
                    b !in startKey.min..startKey.max ||
                    c !in maxKey.min..maxKey.max || d !in stopKey.min..stopKey.max
                ) {
                    status.text = "Значение вне допустимого диапазона."
                } else {
                    preferences.put(startKey, b)
                    preferences.put(maxKey, c)
                    preferences.put(stopKey, d)
                    status.text = "Сохранено."
                }
            }
        })
        card.addView(status)

        card.addView(AssistantUi.label(this, "Обучение"))
        card.addView(
            AssistantUi.hint(
                this,
                "Множитель оценки: ${AssistantUi.num(eating.multiplier(), 2)} (допустимо 0,70 … 1,40). Учтено приёмов пищи: ${eating.learnedMeals()}. " +
                    "Первые 10 приёмов система только копит данные. Дальше после каждого приёма меняет множитель на 5–10%: вниз после низкого сахара, вверх после высокого."
            )
        )
        val frozen = SwitchCompat(this).apply {
            text = "Заморозить обучение"
            setTextColor(Color.WHITE)
            isChecked = preferences.get(BooleanKey.OApsAIMIEatingLearningFrozen)
            setOnCheckedChangeListener { _, checked -> AimiHaptics.click(this@AimiAssistantActivity); preferences.put(BooleanKey.OApsAIMIEatingLearningFrozen, checked) }
        }
        card.addView(frozen)
        card.addView(AssistantUi.button(this, "Сбросить обучение", AssistantUi.RED).apply {
            setOnClickListener {
                AimiHaptics.click(this@AimiAssistantActivity)
                AlertDialog.Builder(this@AimiAssistantActivity)
                    .setTitle("Сбросить обучение?")
                    .setMessage("Множитель вернётся к 1,00, счётчик приёмов станет 0. История приёмов пищи останется.")
                    .setPositiveButton("Сбросить") { _, _ -> AimiHaptics.click(this@AimiAssistantActivity); eating.resetLearning(); render(loading = false) }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        return card
    }

    /** Profile values against reference values (daily dose rules and the result of the eating mode). */
    private fun coefficientsCard(): LinearLayout {
        val card = AssistantUi.card(this)
        card.addView(AssistantUi.heading(this, "Анализ коэффициентов"))
        card.addView(AssistantUi.hint(this, "Цветная цифра — ориентир по измеренным данным. Значение в скобках — то, что сейчас стоит в профиле."))
        val rows = coefficientRows
        if (rows == null) {
            card.addView(AssistantUi.body(this, "Считаю…"))
            return card
        }
        if (rows.isEmpty()) {
            card.addView(AssistantUi.body(this, "Нет данных профиля."))
            return card
        }
        rows.forEach { r ->
            card.addView(AssistantUi.label(this, r.name))
            card.addView(AssistantUi.body(this, r.reference, 18f, if (r.differs) AssistantUi.AMBER else AssistantUi.GREEN, bold = true))
            card.addView(AssistantUi.body(this, "(сейчас: ${r.profile})", 13.5f, AssistantUi.MUTED))
            card.addView(AssistantUi.hint(this, r.comment))
        }
        card.addView(AssistantUi.heading(this, "По часам суток"))
        card.addView(AssistantUi.hint(this, "Час слева — когда менялся сахар. Для базала справа указан более ранний интервал, который мог повлиять на этот час. Сдвиг −1 ч для Люмжева пока проверяется как гипотеза, а не применяется к профилю."))
        val hourly = hourlyCoefficientRows
        if (hourly == null) {
            card.addView(AssistantUi.body(this, "Собираю сопоставимые дни…"))
        } else {
            hourly.forEach { row ->
                val previous = (row.hour + 23) % 24
                card.addView(AssistantUi.label(this, "%02d:00–%02d:00 · сахар".format(row.hour, (row.hour + 1) % 24)))
                val basalObserved = row.basalObserved?.let { AssistantUi.num(it, 2) + " ЕД/ч" } ?: "мало данных"
                card.addView(AssistantUi.body(this, "Базал %02d:00–%02d:00: $basalObserved (${AssistantUi.num(row.basalProfile, 2)}; эпизодов ${row.basalEpisodes})".format(previous, row.hour), 14f,
                    if (row.basalObserved != null) AssistantUi.GREEN else AssistantUi.MUTED, bold = row.basalObserved != null))
                val isfObserved = row.isfObserved?.let { AimiUnits.isfShort(it) } ?: "мало данных"
                card.addView(AssistantUi.body(this, "ISF: $isfObserved (${AimiUnits.num(row.isfProfile, 2)}; эпизодов ${row.isfEpisodes})", 14f,
                    if (row.isfObserved != null) AssistantUi.GREEN else AssistantUi.MUTED))
                val icObserved = row.icObserved?.let { AssistantUi.num(it, 1) + " г/ЕД" } ?: "мало данных"
                card.addView(AssistantUi.body(this, "IC: $icObserved (${AssistantUi.num(row.icProfile, 1)}; приёмов ${row.icEpisodes})", 14f,
                    if (row.icObserved != null) AssistantUi.GREEN else AssistantUi.MUTED))
            }
        }
        return card
    }

    private suspend fun loadHourlyCoefficients(now: Long): List<HourlyCoefficientRow> {
        val profile = profileFunction.getProfile() ?: return emptyList()
        val from = now - 8L * 24 * 3_600_000L
        val glucose = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true).filter { it.isValid }
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(from - 2 * 3_600_000L, now, true).filter { it.isValid && it.amount > 0.0 }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(from - 2 * 3_600_000L, now, true).filter { it.isValid && it.amount > 0.0 }
        val tempBasals = persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(from - 3_600_000L, now).filter { it.isValid }
        val basalEvidence = Array(24) { ArrayList<Double>() }
        val basalCounts = IntArray(24)
        val calendar = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun nearest(at: Long) = glucose.minByOrNull { kotlin.math.abs(it.timestamp - at) }
            ?.takeIf { kotlin.math.abs(it.timestamp - at) <= 10 * 60_000L }
        while (calendar.timeInMillis + 3_600_000L <= now) {
            val start = calendar.timeInMillis
            val end = start + 3_600_000L
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val cleanFrom = start - 2 * 3_600_000L
            val contaminated = carbs.any { it.timestamp in cleanFrom..end } || boluses.any { it.timestamp in cleanFrom..end }
            val first = nearest(start)
            val last = nearest(end)
            if (!contaminated && first != null && last != null && first.value in 70.0..250.0 && last.value in 70.0..250.0) {
                val basalStart = start - 3_600_000L
                var delivered = 0.0
                for (step in 0 until 12) {
                    val t = basalStart + step * 5 * 60_000L + 150_000L
                    val active = tempBasals.filter { t >= it.timestamp && t < it.timestamp + it.duration }.maxByOrNull { it.timestamp }
                    val rate = active?.let { if (it.isAbsolute) it.rate else profile.getBasal(t) * it.rate / 100.0 } ?: profile.getBasal(t)
                    delivered += rate / 12.0
                }
                val isf = profile.getIsfMgdlTimeFromMidnight(hour * 3600).coerceAtLeast(1.0)
                val suggested = (delivered + (last.value - first.value) / isf).coerceIn(0.0, profile.getBasal(start) * 3.0)
                basalEvidence[hour].add(suggested)
                basalCounts[hour]++
            }
            calendar.add(Calendar.HOUR_OF_DAY, 1)
        }
        val isfByHour = ObservedIsfStore.readHourly(this, now).associateBy { it.hour }
        val icByHour = eating.goodMealIcByHour().associateBy { it.hour }
        val result = (0..23).map { hour ->
            val previousSec = ((hour + 23) % 24) * 3600
            val isf = isfByHour[hour]
            val ic = icByHour[hour]
            HourlyCoefficientRow(
                hour = hour,
                basalProfile = profile.getBasalTimeFromMidnight(previousSec),
                basalObserved = HourlyCoefficientAnalysis.median(basalEvidence[hour]),
                basalEpisodes = basalCounts[hour],
                isfProfile = profile.getIsfMgdlTimeFromMidnight(hour * 3600),
                isfObserved = isf?.medianMgdlPerU,
                isfEpisodes = isf?.count ?: 0,
                icProfile = profile.getIcTimeFromMidnight(hour * 3600),
                icObserved = ic?.medianGramsPerU?.takeIf { (ic?.count ?: 0) >= 3 },
                icEpisodes = ic?.count ?: 0,
            )
        }
        cacheHourlySummary(result, profile)
        return result
    }

    /**
     * The main screen card shows three rows: sensitivity and carb ratio (written by `loadCoefficients`, the same
     * numbers as on this screen) and the basal (written here, from the hourly evidence). Before, this function wrote
     * all three rows with other numbers and overwrote the first two, so the main screen did not match this screen.
     */
    private fun cacheHourlySummary(rows: List<HourlyCoefficientRow>, profile: app.aaps.core.interfaces.profile.EffectiveProfile) {
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val basalValues = rows.mapNotNull { it.basalObserved }
        val reference = HourlyCoefficientAnalysis.median(basalValues, 1)?.let { AssistantUi.num(it, 2) + " ЕД/ч" } ?: "мало сопоставимых эпизодов"
        val nowSec = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 3600 + it.get(Calendar.MINUTE) * 60 }
        getSharedPreferences("aimi_coefficient_summary", MODE_PRIVATE).edit()
            .putString("name_2", "Базальная скорость")
            .putString("reference_2", reference)
            .putString("profile_2", AssistantUi.num(profile.getBasalTimeFromMidnight(nowSec), 2) + " ЕД/ч")
            .putBoolean("differs_2", false)
            .putInt("count", 3).putInt("hour", nowHour).putLong("updated_ms", System.currentTimeMillis())
            .apply()
    }

    private suspend fun loadCoefficients(): List<CoefficientsAnalyzer.Row> {
        return try {
            val profile = profileFunction.getProfile() ?: return emptyList()
            val tddData = tddCalculator.averageTDD(tddCalculator.calculate(14, allowMissingDays = true))?.data
            val tdd = tddData?.let {
                if (it.totalAmount > 0.0) it.totalAmount else it.basalAmount + it.bolusAmount
            }
            val rows = CoefficientsAnalyzer.analyze(
                CoefficientsAnalyzer.Input(
                    tddUnits = tdd,
                    tddBasalUnits = tddData?.basalAmount?.takeIf { it > 0.0 },
                    // The value written in the profile for this time, not the dynamic one: this is the number the user
                    // compares and sets, and the hourly table below shows the same one.
                    profileIsfMgdl = Calendar.getInstance().let {
                        profile.getIsfMgdlTimeFromMidnight(it.get(Calendar.HOUR_OF_DAY) * 3600 + it.get(Calendar.MINUTE) * 60)
                    },
                    profileIcGramsPerU = profile.getIc(),
                    profileBasalPerDayU = profile.baseBasalSum(),
                    targetMgdl = profile.getTargetMgdl(),
                    eatingUPerXe = preferences.get(DoubleKey.OApsAIMIEatingUPerXe),
                    eatingMultiplier = eating.multiplier(),
                    eatingLearnedMeals = eating.learnedMeals(),
                    goodMealUPerXe = eating.goodMealUPerXe(),
                    observed = ObservedIsfStore.read(this, System.currentTimeMillis()),
                )
            )
            val summary = getSharedPreferences("aimi_coefficient_summary", MODE_PRIVATE).edit()
            // Rows 0 and 1 (sensitivity, carb ratio) for the main screen card; row 2 (basal) is written by cacheHourlySummary.
            rows.take(2).forEachIndexed { index, row ->
                summary.putString("name_$index", row.name)
                summary.putString("reference_$index", row.reference)
                summary.putString("profile_$index", row.profile)
                summary.putBoolean("differs_$index", row.differs)
            }
            summary.putInt("count", 3).putLong("updated_ms", System.currentTimeMillis()).apply()
            fun fmt(v: Double) = "%.2f".format(java.util.Locale.getDefault(), v)
            val nowMin = System.currentTimeMillis() / 60000L
            val values = sortedMapOf<Long, Double>()
            val diaDays = linkedSetOf<String>()
            runCatching { aimiStorageHelper.getAimiFile("oapsaimi_pkpd_records.csv").useLines { lines ->
                lines.forEach { line ->
                    val fields = line.split(',')
                    val minute = fields.getOrNull(1)?.toLongOrNull()
                    val dia = fields.getOrNull(7)?.toDoubleOrNull()
                    if (minute != null && minute in (nowMin - 10079)..nowMin && dia != null && dia.isFinite() && dia > 0) {
                        values[minute] = dia
                        fields.firstOrNull()?.take(10)?.let(diaDays::add)
                    }
                }
            } }
            val diaText = if (values.isEmpty()) "Нет данных за 7 дней" else
                "${fmt(values.values.min())}–${fmt(values.values.max())} ч · ${diaDays.size} дн. · ${values.size} точек"
            val diaEvidence = when {
                diaDays.size >= 5 -> "Подтверждение хорошее: PK/PD-кривая наблюдалась минимум в 5 разных днях. Диапазон показывает устойчивость оценки, а не одно измерение."
                diaDays.size >= 3 -> "Предварительно: есть ${diaDays.size} разных дня. Для уверенной DIA желательно минимум 5 сопоставимых дней с наблюдаемым хвостом действия."
                else -> "Недостаточно независимых данных: точки относятся лишь к ${diaDays.size} дн. Нужны разные дни и участки позднего хвоста без новой еды и коррекций."
            }
            summary.putString("dia_week", diaText).apply()
            val schedule = (0..23).joinToString("\n") { hour ->
                val sec = hour * 3600
                "%02d:00".format(hour) + "   " + fmt(profile.getBasalTimeFromMidnight(sec)) + " / " +
                    AimiUnits.num(profile.getIsfMgdlTimeFromMidnight(sec), 2) + " / " + fmt(profile.getIcTimeFromMidnight(sec))
            }
            rows + listOf(CoefficientsAnalyzer.Row("DIA · модель за 7 дней", "модель PK/PD", diaText, false, diaEvidence),
                CoefficientsAnalyzer.Row("Почасовой профиль", "на начало часа", "Базал / ISF / IC", false, "Ед/ч / ${AimiUnits.unit()} на Ед / г на Ед\n" + schedule))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun proposalCard(p: AssistantProposal): LinearLayout {
        val card = box()
        val s = p.setting
        card.addView(text("${s.englishName}: ${fmt(s, p.current)} → ${fmt(s, p.proposed)}", 16f, bold = true))
        card.addView(text(getString(s.whatItDoes), 13f))
        card.addView(text(getString(p.reasonRes, *p.reasonArgs.toTypedArray()), 14f))
        card.addView(text(getString(s.related), 13f))
        if (s.tpoWritable) card.addView(text(getString(R.string.aimi_as_tpo_note), 13f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_apply)
            setOnClickListener { AimiHaptics.click(this@AimiAssistantActivity); applyChange(s, p.proposed); render(loading = false) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_history_icon)
            setOnClickListener { AimiHaptics.click(this@AimiAssistantActivity); showHistory(s) }
        })
        card.addView(row)
        return card
    }

    private fun settingRow(s: AssistantSetting): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        row.addView(text("${s.englishName}: ${fmt(s, readValue(s))}", 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = getString(R.string.aimi_as_history_icon)
            setOnClickListener { AimiHaptics.click(this@AimiAssistantActivity); showHistory(s) }
        })
        return row
    }

    private fun showHistory(s: AssistantSetting) {
        val now = System.currentTimeMillis()
        val records = history.forKey(s.prefKey)
        val sb = StringBuilder()
        sb.append(getString(R.string.aimi_as_hist_default, fmt(s, s.default))).append("\n")
        sb.append(getString(R.string.aimi_as_hist_now, fmt(s, readValue(s)))).append("\n\n")
        if (records.isEmpty()) sb.append(getString(R.string.aimi_as_hist_none))
        records.forEach { r ->
            sb.append(getString(R.string.aimi_as_hist_line, dateFormat.format(Date(r.timeMs)), fmt(s, r.from), fmt(s, r.to))).append("\n")
            val after = AssistantAnalyzer.stats(samples, r.timeMs, now)
            if (r.before != null && after != null) {
                sb.append(
                    getString(
                        R.string.aimi_as_hist_result,
                        r.before.tirPercent, r.before.lowPercent, r.before.meanMmol,
                        after.tirPercent, after.lowPercent, after.meanMmol,
                    )
                ).append("\n")
            }
            sb.append("\n")
        }
        AlertDialog.Builder(this)
            .setTitle(s.englishName)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.aimi_as_restore_default) { _, _ -> AimiHaptics.click(this@AimiAssistantActivity);
                applyChange(s, s.default)
                render(loading = false)
            }
            .setNegativeButton(R.string.aimi_as_close, null)
            .show()
    }

    // ---- small view helpers ----

    private fun title(t: String) = text(t, 22f, bold = true)
    private fun section(t: String) = text(t, 18f, bold = true).apply {
        setPadding(0, 40, 0, 8)
        setTextColor(AssistantUi.ACCENT)
    }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(if (bold) Color.WHITE else AssistantUi.TEXT)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun box(): LinearLayout = AssistantUi.card(this)
}
