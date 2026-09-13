package app.aaps.core.ui.compose

import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.core.text.HtmlCompat

/**
 * Converts a string containing simple `<font color='#RRGGBB'>...</font>` markup — the only HTML some
 * dashboard view-models emit, meant for a legacy `TextView`'s `Html.fromHtml` (see `StatusCardView`) — into
 * a Compose [AnnotatedString] carrying the same color spans, so a Compose `Text` renders the color instead
 * of leaking the raw tags as literal text. A string with no HTML tags passes through unchanged.
 */
fun String.htmlToAnnotatedString(): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY)
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java).forEach { span ->
            addStyle(
                SpanStyle(color = Color(span.foregroundColor)),
                spanned.getSpanStart(span),
                spanned.getSpanEnd(span),
            )
        }
    }
}
