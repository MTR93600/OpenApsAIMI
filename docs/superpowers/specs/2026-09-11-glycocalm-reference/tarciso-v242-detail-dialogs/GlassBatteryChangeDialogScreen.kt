package app.aaps.plugins.main.general.overview.glass

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar

// Battery with bolt icon - matches GlycoCalm battery change design
private val BatteryBoltIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BatteryBolt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color(0xFF0284C7)),
            strokeLineWidth = 2.2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(2f, 7f)
            horizontalLineTo(18f)
            verticalLineTo(17f)
            horizontalLineTo(2f)
            close()
        }
        path(
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color(0xFF0284C7)),
            strokeLineWidth = 2.2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(22f, 11f)
            verticalLineTo(13f)
        }
        path(
            fill = androidx.compose.ui.graphics.SolidColor(Color(0xFF0284C7)),
            stroke = null
        ) {
            moveTo(11f, 10.5f)
            lineTo(9f, 13.5f)
            horizontalLineTo(13f)
            lineTo(11f, 16.5f)
            close()
        }
    }.build()
}

@Composable
fun GlassBatteryChangeDialogScreen(
    isDark: Boolean,
    formattedDate: String,
    formattedTime: String,
    notes: String,
    isSaving: Boolean,
    isSaved: Boolean,
    errorMessage: String?,
    eventTimestamp: Long,
    onNotesChange: (String) -> Unit,
    onDateSet: (year: Int, month: Int, day: Int) -> Unit,
    onTimeSet: (hour: Int, minute: Int) -> Unit,
    onSetToNow: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    // GlycoCalm Theme Colors (sky accent, dark/light)
    val surfaceWhite = if (isDark) Color(0xFF1E293B) else Color.White
    val surfaceField = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val textPrimary = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val textMuted = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val borderLight = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val teal = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val tealDark = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1)
    val tealSoftBg = if (isDark) teal.copy(alpha = 0.15f) else Color(0xFFE0F4F7)
    val tealSoftBorder = if (isDark) teal.copy(alpha = 0.4f) else Color(0xFFB2E4EC)
    val iconChipTop = if (isDark) teal.copy(alpha = 0.18f) else Color(0xFFE4F5F9)
    val iconChipBottom = if (isDark) teal.copy(alpha = 0.08f) else Color(0xFFD5EDF3)
    val bannerBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val bannerBorder = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val bannerIcon = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val bannerText = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
    val saveTop = Color(0xFF34A5E8)
    val saveBottom = Color(0xFF1E80CB)
    val closeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val dividerColor = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val footerBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val footerBorder = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    val context = LocalContext.current

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
                    ambientColor = Color(0xFF0F172A).copy(alpha = 0.22f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(surfaceWhite)
                .border(1.dp, cardBorder, RoundedCornerShape(32.dp))
        ) {
            Column {
                // HEADER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Battery Icon Chip
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.verticalGradient(listOf(iconChipTop, iconChipBottom)))
                                .border(1.dp, tealSoftBorder, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = BatteryBoltIcon,
                                contentDescription = null,
                                tint = teal,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Pump Battery Change",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                letterSpacing = (-0.3).sp
                            )
                            Text(
                                text = "Log hardware power cell replacement",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = textSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    // Close Button
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
                            tint = textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(dividerColor)
                )

                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // NOTES & BRAND
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NOTES & BRAND",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Optional",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = textMuted
                            )
                        }
                        Box {
                            OutlinedTextField(
                                value = notes,
                                onValueChange = onNotesChange,
                                placeholder = {
                                    Text(
                                        text = "e.g. Energizer Ultimate Lithium AAA, sealed cap cleaned...",
                                        fontSize = 14.sp,
                                        color = textMuted
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 3,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = surfaceField,
                                    unfocusedContainerColor = surfaceField,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary,
                                    focusedBorderColor = teal.copy(alpha = 0.5f),
                                    unfocusedBorderColor = borderLight,
                                    cursorColor = teal
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.Create,
                                contentDescription = null,
                                tint = textMuted,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 10.dp)
                                    .size(14.dp)
                            )
                        }
                    }

                    // EVENT TIMESTAMP
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccessTime,
                                    contentDescription = null,
                                    tint = textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "EVENT TIMESTAMP",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textSecondary,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            // Set to Now badge
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(tealSoftBg)
                                    .border(1.dp, tealSoftBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { onSetToNow() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(teal)
                                )
                                Text(
                                    text = "Set to Now",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tealDark
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // DATE CARD
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(surfaceField)
                                    .border(1.dp, borderLight, RoundedCornerShape(16.dp))
                                    .clickable {
                                        val cal = Calendar.getInstance().apply { timeInMillis = eventTimestamp }
                                        DatePickerDialog(
                                            context,
                                            { _, year, month, day -> onDateSet(year, month, day) },
                                            cal.get(Calendar.YEAR),
                                            cal.get(Calendar.MONTH),
                                            cal.get(Calendar.DAY_OF_MONTH)
                                        ).show()
                                    }
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "DATE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textMuted,
                                        letterSpacing = 0.6.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formattedDate,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(surfaceWhite)
                                                .border(1.dp, borderLight, RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CalendarToday,
                                                contentDescription = null,
                                                tint = textSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            // TIME CARD
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(surfaceField)
                                    .border(1.dp, borderLight, RoundedCornerShape(16.dp))
                                    .clickable {
                                        val cal = Calendar.getInstance().apply { timeInMillis = eventTimestamp }
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute -> onTimeSet(hour, minute) },
                                            cal.get(Calendar.HOUR_OF_DAY),
                                            cal.get(Calendar.MINUTE),
                                            false
                                        ).show()
                                    }
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "TIME (LOCAL)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textMuted,
                                        letterSpacing = 0.6.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formattedTime,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(surfaceWhite)
                                                .border(1.dp, borderLight, RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AccessTime,
                                                contentDescription = null,
                                                tint = textSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // INFO BANNER
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bannerBg)
                            .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = bannerIcon,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Pump communication & insulin telemetry will resume immediately.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = bannerText,
                            lineHeight = 14.sp
                        )
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // FOOTER BUTTONS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(footerBg)
                        .border(1.dp, footerBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(surfaceWhite)
                            .border(1.dp, borderLight, RoundedCornerShape(16.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textSecondary
                        )
                    }
                    // Save
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                brush = if (isSaved) Brush.verticalGradient(
                                    listOf(Color(0xFF10B981), Color(0xFF059669))
                                )
                                else Brush.horizontalGradient(listOf(saveTop, saveBottom))
                            )
                            .clickable(enabled = !isSaving && !isSaved) { onSave() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else if (isSaved) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Saved",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Save",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
