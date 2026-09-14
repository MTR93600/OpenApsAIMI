package app.aaps.plugins.main.general.overview.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.plugins.main.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Lightning bolt icon - deliver button
private val BoltIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Bolt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13f, 3f)
            lineTo(4f, 14f)
            horizontalLineTo(11f)
            lineTo(11f, 21f)
            lineTo(20f, 10f)
            horizontalLineTo(13f)
            close()
        }
    }.build()
}

@Composable
fun GlassInsulinDialogScreen(
    isDark: Boolean,
    maxInsulin: Double,
    bolusStep: Double,
    currentIOB: Double,
    currentBG: Double,
    isf: Double,
    isMmol: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, recordOnly: Boolean, eatingSoon: Boolean, notes: String) -> Unit
) {
    val surfaceWhite = if (isDark) Color(0xFF1E293B) else Color.White
    val surfaceField = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val textPrimary = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0B1C30)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val textMuted = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val accentBlue = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val deliverTop = Color(0xFF2563EB)
    val deliverBottom = Color(0xFF1D4ED8)
    val borderLight = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val sky = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val skySoftBg = if (isDark) sky.copy(alpha = 0.15f) else Color(0xFFE0F2FE)
    val skySoftBorder = if (isDark) sky.copy(alpha = 0.4f) else Color(0xFFBAE6FD)
    val emerald = if (isDark) Color(0xFF34D399) else Color(0xFF00B894)
    val emeraldSoftBg = if (isDark) emerald.copy(alpha = 0.15f) else Color(0xFFECFDF5)
    val closeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)
    val switchUnchecked = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1)
    val disabledBtn = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val stripBg = if (isDark) sky.copy(alpha = 0.08f) else Color(0xFFEFF4FF)
    val stripBorder = if (isDark) sky.copy(alpha = 0.25f) else Color(0xFFDCE9FF)
    val heroTop = if (isDark) Color(0xFF16233D) else Color(0xFFF8FAFF)
    val heroBottom = if (isDark) sky.copy(alpha = 0.12f) else Color(0xFFE0F2FE).copy(alpha = 0.3f)

    var amount by remember { mutableStateOf(0.0) }
    var recordOnly by remember { mutableStateOf(false) }
    var eatingSoon by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    val canConfirm = amount > 0 || eatingSoon
    val dosePercentage = if (maxInsulin > 0) (amount / maxInsulin).coerceIn(0.0, 1.0) else 0.0
    val projectedBg = max(0.0, currentBG - amount * isf)
    val bgText = if (isMmol) String.format(Locale.US, "%.1f", projectedBg)
    else projectedBg.toInt().toString()
    val unitLabel = if (isMmol) "mmol/L" else "mg/dL"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = Color(0xFF0B1C30).copy(alpha = 0.28f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(surfaceWhite)
                .border(1.dp, cardBorder, RoundedCornerShape(32.dp))
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Grabber
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1))
                        .align(Alignment.CenterHorizontally)
                )

                // HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(skySoftBg)
                                .border(1.dp, skySoftBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_bolus),
                                contentDescription = null,
                                tint = sky,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "Bolus Delivery",
                                    color = textPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(skySoftBg)
                                        .border(1.dp, skySoftBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "Quick Bolus",
                                        color = sky,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "Quick insulin dose administration",
                                color = textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(closeBg)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // CLINICAL STRIP: IOB + EST GLUCOSE
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(stripBg)
                        .border(1.dp, stripBorder, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Active IOB
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceWhite)
                                .border(1.dp, stripBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AccessTime,
                                contentDescription = null,
                                tint = sky,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Column {
                            Text("ACTIVE IOB", color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    String.format(Locale.US, "%.2f", currentIOB),
                                    color = textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    " U remaining",
                                    color = textSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                            }
                        }
                    }
                    // Est glucose
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceWhite)
                                .border(1.dp, stripBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.TrendingUp,
                                contentDescription = null,
                                tint = emerald,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Column {
                            Text("EST. GLUCOSE", color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    bgText,
                                    color = textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    " $unitLabel",
                                    color = textSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                                Text(
                                    "${(dosePercentage * 100).toInt()}% max",
                                    color = emerald,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(emeraldSoftBg)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                // HERO DIAL CARD
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.verticalGradient(listOf(heroTop, heroBottom)))
                        .border(1.dp, skySoftBorder, RoundedCornerShape(16.dp))
                        .padding(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "DOSE AMOUNT",
                                color = textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                "U (max: ${String.format(Locale.US, "%.1f", maxInsulin)})",
                                color = sky,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(surfaceWhite)
                                .border(1.dp, borderLight.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(closeBg)
                                    .clickable {
                                        amount = max(0.0, (amount - bolusStep) * 100.0 / 100.0)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = "Decrease",
                                    tint = textPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    String.format(Locale.US, "%.1f", amount),
                                    color = textPrimary,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(skySoftBg)
                                        .border(1.dp, skySoftBorder, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "Units",
                                        color = sky,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF34A5E8), Color(0xFF1E80CB))))
                                    .clickable {
                                        amount = min(maxInsulin, amount + bolusStep)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Increase",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Safety Limit", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${String.format(Locale.US, "%.1f", amount)}/${String.format(Locale.US, "%.1f", maxInsulin)} U max",
                                color = textSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(borderLight)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = dosePercentage.toFloat())
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Brush.horizontalGradient(listOf(accentBlue, emerald)))
                            )
                        }
                    }
                }

                // PRESET PILLS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DosePresetPill(isDark = isDark, label = "+0.3") { amount = min(maxInsulin, amount + 0.3) }
                    DosePresetPill(isDark = isDark, label = "+0.5") { amount = min(maxInsulin, amount + 0.5) }
                    DosePresetPill(isDark = isDark, label = "+1.0") { amount = min(maxInsulin, amount + 1.0) }
                    DosePresetPill(isDark = isDark, label = "+2.0") { amount = min(maxInsulin, amount + 2.0) }
                }

                // OPTIONS
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(surfaceWhite)
                        .border(1.dp, borderLight, RoundedCornerShape(14.dp))
                ) {
                    Column {
                        // Eating Soon
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { eatingSoon = !eatingSoon }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(skySoftBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.AccessTime,
                                        contentDescription = null,
                                        tint = sky,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "Eating Soon TT",
                                            color = textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(closeBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "?",
                                                color = textMuted,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        "Temporary target for meals",
                                        color = textMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Switch(
                                checked = eatingSoon,
                                onCheckedChange = { eatingSoon = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = accentBlue,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = switchUnchecked
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(borderLight.copy(alpha = 0.5f))
                        )

                        // Record Only
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { recordOnly = !recordOnly }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(closeBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("R", color = textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Column {
                                    Text(
                                        "Record Only",
                                        color = textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Log dose without delivering to pump",
                                        color = textMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Switch(
                                checked = recordOnly,
                                onCheckedChange = { recordOnly = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = accentBlue,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = switchUnchecked
                                )
                            )
                        }
                    }
                }

                // NOTES
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = {
                        Text(
                            "Add notes (e.g. snack, exercise)...",
                            color = textMuted,
                            fontSize = 12.sp
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Create,
                            contentDescription = null,
                            tint = textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = surfaceField,
                        unfocusedContainerColor = surfaceField,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedBorderColor = sky.copy(alpha = 0.5f),
                        unfocusedBorderColor = borderLight,
                        cursorColor = sky
                    )
                )

                // ACTION BUTTONS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(closeBg)
                            .border(1.dp, borderLight, RoundedCornerShape(12.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Cancel",
                            color = textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                brush = if (canConfirm) Brush.horizontalGradient(listOf(deliverTop, deliverBottom))
                                else Brush.horizontalGradient(listOf(disabledBtn, disabledBtn))
                            )
                            .clickable(enabled = canConfirm) {
                                onConfirm(amount, recordOnly, eatingSoon, notes)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (canConfirm && amount > 0) {
                                Icon(
                                    imageVector = BoltIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                if (amount > 0) "Deliver ${String.format(Locale.US, "%.1f", amount)} U" else "Set Dose",
                                color = if (canConfirm) Color.White else textMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DosePresetPill(
    isDark: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val sky = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val bg = if (isDark) sky.copy(alpha = 0.12f) else Color(0xFFE0F2FE).copy(alpha = 0.6f)
    val border = if (isDark) sky.copy(alpha = 0.35f) else Color(0xFFBAE6FD).copy(alpha = 0.8f)
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = sky,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
