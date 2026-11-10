package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Small helpers for the look of the Assistant screens: dark rounded cards, a clear title, a muted hint under each value. */
object AssistantUi {

    val BG = Color.parseColor("#0B1220")
    val CARD = Color.parseColor("#162033")
    val CARD_BORDER = Color.parseColor("#24324B")
    val ACCENT = Color.parseColor("#38BDF8")
    val GREEN = Color.parseColor("#16A34A")
    val RED = Color.parseColor("#DC2626")
    val AMBER = Color.parseColor("#F59E0B")
    val TEXT = Color.parseColor("#E2E8F0")
    val MUTED = Color.parseColor("#94A3B8")

    private val RU = Locale("ru", "RU")

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(context: Context, fill: Int, stroke: Int? = null, radiusDp: Int = 18) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }

    fun choiceBackground(context: Context, fill: Int) = rounded(context, fill, null, 14)

    /** A rounded card. Add the views of one group of settings into it. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
        background = rounded(context, CARD, CARD_BORDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 14)
        }
    }

    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(context, 4))
    }

    fun body(context: Context, text: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 4), 0, dp(context, 4))
    }

    /** Muted small text: explains when and for what a value works. */
    fun hint(context: Context, text: String): TextView = body(context, text, 12.5f, MUTED)

    /** Name of a field, with its allowed range. */
    fun label(context: Context, text: String): TextView = body(context, text, 14f, TEXT, bold = true).apply {
        setPadding(0, dp(context, 12), 0, 0)
    }

    fun numberField(context: Context, value: Double, decimals: Int = 1): EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        setText(String.format(Locale.US, "%.${decimals}f", value))
    }

    fun button(context: Context, text: String, color: Int = ACCENT): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rounded(context, color, null, 14)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50)).apply {
            topMargin = dp(context, 12)
        }
    }

    /** Parses a number typed with a dot or a comma. */
    fun parse(field: EditText): Double? = field.text.toString().trim().replace(',', '.').toDoubleOrNull()

    // ---- units: the user thinks in mmol/L; mg/dL is shown in brackets ----

    const val MGDL_PER_MMOL = 18.0

    fun mmol(mgdl: Double): Double = mgdl / MGDL_PER_MMOL

    /** "6,7 ммоль/л (120 мг/дл)" */
    fun glucose(mgdl: Double): String = String.format(RU, "%.1f ммоль/л (%.0f мг/дл)", mgdl / MGDL_PER_MMOL, mgdl)

    /** "0,8 ммоль/л за 5 мин (15 мг/дл)" */
    fun rise5(mgdl: Double): String = String.format(RU, "%.2f ммоль/л за 5 мин (%.0f мг/дл)", mgdl / MGDL_PER_MMOL, mgdl)

    fun num(value: Double, decimals: Int = 1): String = String.format(RU, "%.${decimals}f", value)
}
