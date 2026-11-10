package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text

@Composable
internal fun GlassContainer(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val backgroundBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(Color(0xEC1C2640), Color(0xF5080E18)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xF0E8EEF6)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }
    val borderColor = if (isDark) Color(0x40FFFFFF) else Color(0xE0FFFFFF)

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 26.dp else 16.dp,
                shape = shape,
                spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color(0x660F172A),
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.50f) else Color(0x400F172A)
            )
            .clip(shape)
            .background(backgroundBrush)
            .border(1.5.dp, borderColor, shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(1.5.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            if (isDark) Color.White.copy(alpha = 0.60f) else Color.White.copy(alpha = 1.0f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    )
                )
        )
        content()
    }
}

@Composable
internal fun GlassPill(
    label: String,
    value: String,
    isDark: Boolean,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    // A flat outlined info block, not a button: thin outline, a barely visible fill, strong text for contrast.
    val shape = RoundedCornerShape(12.dp)
    val bgBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x08FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x66E8F0FA)))
    }
    val borderColor = if (isDark) Color(0x40FFFFFF) else Color(0xB0CBD5E1)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderColor, shape)
            .heightIn(min = 38.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        // Icon on the left in a fixed slot, text centred in the rest, so all pills line up the same way.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (leadingIcon != null) {
                Box(modifier = Modifier.width(18.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // With an icon the label is not shown: the icon tells what it is, so the value has room.
                if (leadingIcon == null) Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFFA8B3C7) else Color(0xFF52627A),
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isNotEmpty()) {
                    Text(
                        text = value,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = valueColor ?: if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A),
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
internal fun BottomMetricPill(
    title: String,
    value: String,
    isDark: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    accentColor: Color? = null
) {
    val shape = RoundedCornerShape(14.dp)
    val bgBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x14FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xE4EEF2FA)))
    }
    val overlayColor = accentColor?.copy(alpha = if (isDark) 0.14f else 0.12f)
    val borderColor = accentColor?.copy(alpha = 0.50f) ?: if (isDark) Color(0x30FFFFFF) else Color(0xD0CBD5E1)
    val textColor = if (accentColor != null && !isDark) Color(0xFF303030) else accentColor ?: if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val valueColor = if (accentColor != null && !isDark) Color(0xFF303030) else accentColor ?: if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)

    Box(
        modifier = modifier
            .shadow(
                elevation = if (accentColor != null) 8.dp else if (isDark) 7.dp else 4.dp,
                shape = shape,
                spotColor = if (accentColor != null) accentColor.copy(alpha = 0.35f) else if (isDark) Color.Black.copy(alpha = 0.50f) else Color(0x22000000)
            )
            .clip(shape)
            .background(bgBrush)
            .then(if (overlayColor != null) Modifier.background(overlayColor) else Modifier)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 7.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (title.isNotEmpty()) {
                Text(
                    text = "$title ",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Text(
                text = value,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
    }
}

/**
 * The running "I eat" button: green frame, the title with the minutes on the left, and the two live numbers on the
 * right: carbohydrates counted so far (orange) and insulin given in the window (blue). No units: the colors say which is which.
 */
@Composable
internal fun EatingRunningPill(
    mode: String,
    remainingMin: Long,
    carbs: String,
    insulin: String,
    carbsPeak: String,
    insulinNeed: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = Color(0xFF22C55E)
    val orange = Color(0xFFFB923C)
    val blue = Color(0xFF38BDF8)
    val shape = RoundedCornerShape(16.dp)
    val bgBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x14FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xE4EEF2FA)))
    }
    val titleColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val mutedColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    Box(
        modifier = modifier
            // No shadow here: the fill is see-through, so a shadow showed as a square plate under the pill.
            .clip(shape)
            .background(bgBrush)
            .background(green.copy(alpha = if (isDark) 0.14f else 0.10f))
            .border(1.dp, green.copy(alpha = 0.55f), shape)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The left part takes the free space and wraps the long title (for example "Большой + жирная") to the next line.
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = mode, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = titleColor)
                val remaining = if (remainingMin >= 60) "осталось ${remainingMin / 60} ч ${remainingMin % 60} мин" else "осталось $remainingMin мин"
                Text(text = remaining, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = mutedColor)
            }
            // The numbers keep their own width and never wrap. Under each number a short caption says what it is:
            // the carbs are what has been absorbed so far (not what was eaten), the insulin is what has been given.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = carbs, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = orange, maxLines = 1, softWrap = false)
                        Text(text = " ($carbsPeak)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = orange.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
                    }
                    Text(text = "ХЕ всосалось", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mutedColor, maxLines = 1, softWrap = false)
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .width(1.dp)
                        .height(26.dp)
                        .background(mutedColor.copy(alpha = 0.45f))
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = insulin, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = blue, maxLines = 1, softWrap = false)
                        Text(text = " ($insulinNeed)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = blue.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
                    }
                    Text(text = "ЕД подано (нужно)", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mutedColor, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
