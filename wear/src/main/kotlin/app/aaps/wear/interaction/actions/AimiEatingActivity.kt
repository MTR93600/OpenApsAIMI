package app.aaps.wear.interaction.actions

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.graphics.drawable.GradientDrawable
import app.aaps.wear.complications.eatingModeColor
import android.widget.TextView
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import dagger.android.support.DaggerAppCompatActivity
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/** Wear owns intent/confirmation UI only. EatingEngine and all treatment calculations stay on phone. */
class AimiEatingActivity : DaggerAppCompatActivity() {
    @Inject lateinit var rxBus: RxBus
    private val disposable = CompositeDisposable()
    private lateinit var root: LinearLayout
    private var fatty = false
    private var selectedSize = 4
    private var currentState: EventData.AimiEatingState? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stateTimeout = Runnable {
        if (currentState == null && !isFinishing) renderConnectionError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (resources.displayMetrics.density * 10).toInt()
            setPadding(pad * 3, pad * 3, pad * 3, pad * 3)
            setBackgroundColor(Color.BLACK)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
        disposable += rxBus.toObservable(EventData.AimiEatingState::class.java).subscribe { state ->
            runOnUiThread {
                handler.removeCallbacks(stateTimeout)
                currentState = state
                render()
            }
        }
        requestState()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateTimeout)
        disposable.clear()
        super.onDestroy()
    }

    private fun requestState() {
        currentState = null
        renderLoading()
        rxBus.send(EventWearToMobile(EventData.AimiEatingStateRequest))
        handler.removeCallbacks(stateTimeout)
        handler.postDelayed(stateTimeout, 6_000L)
    }

    private fun renderLoading() {
        root.removeAllViews()
        root.addView(label("Получаю состояние…", 19f))
    }

    private fun renderConnectionError() {
        root.removeAllViews()
        root.addView(label("Телефон не отвечает", 20f), weighted(0.8f))
        root.addView(label("Проверьте Bluetooth и Galaxy Wearable", 15f), weighted(0.8f))
        root.addView(Button(this).apply {
            text = "ПОВТОРИТЬ"
            setOnClickListener { requestState() }
        }, weighted(0.9f))
        root.addView(Button(this).apply {
            text = "НАЗАД"
            setOnClickListener { finish() }
        }, weighted(0.9f))
    }

    private fun render() {
        root.removeAllViews()
        val state = currentState
        if (state?.active == true) renderActive(state) else renderStart()
    }

    private fun renderStart() {
        root.addView(label("Я ем", 22f), weighted(0.7f))
        listOf("Малый", "Средний", "Большой", "Авто").forEachIndexed { index, valueText ->
            val value = if (index == 3) 4 else index
            root.addView(Button(this).apply {
                val ink = eatingModeColor(value)
                text = if (value == selectedSize) "✓  $valueText" else valueText
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(ink)
                    if (value == selectedSize) setStroke(dp(2), Color.WHITE)
                }
                setOnClickListener { selectedSize = value; render() }
            }, LinearLayout.LayoutParams(-1, dp(listOf(45, 52, 60, 48)[index])).apply {
                bottomMargin = dp(4)
            })
        }
        val fat = Switch(this).apply {
            text = "Жирная пища"
            isChecked = fatty
            setOnCheckedChangeListener { _, checked -> fatty = checked }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(fat, weighted(0.9f))
        root.addView(Button(this).apply {
            text = "Проверить и запустить"
            isAllCaps = false
            textSize = 17f
            setOnClickListener {
                rxBus.send(EventWearToMobile(EventData.AimiEatingPreCheck(selectedSize, fat.isChecked)))
                ContactingMasterActivity.show(this@AimiEatingActivity)
                finish()
            }
        }, weighted(1f))
    }

    private fun renderActive(state: EventData.AimiEatingState) {
        val mode = when (state.size) { 0 -> "Малый"; 1 -> "Средний"; 2 -> "Большой"; else -> "Авто" }
        root.addView(label("Я ем · $mode${if (state.fat) " + жирная" else ""}", 20f), weighted(0.9f))
        root.addView(label("Осталось ${state.remainingMin} мин", 17f), weighted(0.7f))
        root.addView(label("Оценка углеводов: ${"%.0f".format(state.estimateG)} г", 17f), weighted(0.7f))
        root.addView(label("Рассчитано: ${"%.2f".format(state.neededU)} ЕД", 17f), weighted(0.7f))
        root.addView(label("Подано: ${"%.2f".format(state.deliveredU)} ЕД", 17f), weighted(0.7f))
        root.addView(Button(this).apply {
            text = "ЗАВЕРШИТЬ"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(190, 55, 45))
            setOnClickListener {
                rxBus.send(EventWearToMobile(EventData.AimiEatingStopPreCheck))
                ContactingMasterActivity.show(this@AimiEatingActivity)
                finish()
            }
        }, weighted(1.2f))
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
    private fun weighted(weight: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(if (weight >= 1f) 6 else 3) }
}
