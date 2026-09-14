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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar

enum class TempTargetPresetKind { EATING, ACTIVITY, HYPO }

data class TempTargetPreset(
    val kind: TempTargetPresetKind,
    val title: String,
    val subtitle: String
)

// Pulse wave badge icon - matches Temporary Target design header
private val PulseWaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PulseWave",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF0284C7)),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 12f)
            horizontalLineTo(6f)
            lineTo(8.5f, 6f)
            lineTo(12.5f, 18f)
            lineTo(16f, 9f)
            lineTo(18f, 14f)
            horizontalLineTo(21f)
        }
    }.build()
}

// Cloche/food icon for Eating Soon preset
private val FoodIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Food",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFEA580C)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 19f)
            horizontalLineTo(20f)
            moveTo(4f, 15f)
            horizontalLineTo(20f)
            moveTo(12f, 4f)
            verticalLineTo(6f)
            moveTo(12f, 6f)
            arcTo(7f, 7f, 0f, false, false, 19f, 13f)
            horizontalLineTo(5f)
            arcTo(7f, 7f, 0f, false, false, 12f, 6f)
            close()
        }
    }.build()
}

// Swimmer/activity icon for Activity preset
private val ActivityIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ActivityIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF0284C7)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 4f)
            arcTo(2f, 2f, 0f, false, true, 14f, 6f)
            arcTo(2f, 2f, 0f, false, true, 12f, 8f)
            arcTo(2f, 2f, 0f, false, true, 10f, 6f)
            arcTo(2f, 2f, 0f, false, true, 12f, 4f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF0284C7)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 17f)
            curveTo(4f, 17f, 4f, 18f, 6f, 18f)
            curveTo(8f, 18f, 8f, 17f, 10f, 17f)
            curveTo(12f, 17f, 12f, 18f, 14f, 18f)
            curveTo(16f, 18f, 16f, 17f, 18f, 17f)
            curveTo(20f, 17f, 20f, 18f, 22f, 18f)
            moveTo(2f, 21f)
            curveTo(4f, 21f, 4f, 20f, 6f, 20f)
            curveTo(8f, 20f, 8f, 21f, 10f, 21f)
            curveTo(12f, 21f, 12f, 20f, 14f, 20f)
            curveTo(16f, 20f, 16f, 21f, 18f, 21f)
            curveTo(20f, 21f, 20f, 20f, 22f, 20f)
            moveTo(8f, 11f)
            lineTo(12f, 13f)
            lineTo(16f, 11f)
        }
    }.build()
}

// Droplet with down arrow for Hypo preset
private val HypoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Hypo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFE11D48)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2.69f)
            lineTo(17.66f, 8.35f)
            arcTo(8f, 8f, 0f, true, true, 6.35f, 8.35f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFE11D48)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 8f)
            verticalLineTo(13f)
            moveTo(10f, 11f)
            lineTo(12f, 13f)
            lineTo(14f, 11f)
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTempTargetDialogScreen(
    isDark: Boolean,
    targetText: String,
    rangeText: String,
    unitLabel: String,
    durationMin: Int,
    presets: List<TempTargetPreset>,
    selectedPreset: TempTargetPresetKind?,
    reasonOptions: List<String>,
    selectedReasonIndex: Int,
    hasActiveTT: Boolean,
    formattedDate: String,
    formattedTime: String,
    isSaving: Boolean,
    isSaved: Boolean,
    errorMessage: String?,
    eventTimestamp: Long,
    onTargetMinus: () -> Unit,
    onTargetPlus: () -> Unit,
    onDurationMinus: () -> Unit,
    onDurationPlus: () -> Unit,
    onPresetSelected: (TempTargetPresetKind) -> Unit,
    onReasonSelected: (Int) -> Unit,
    onDateSet: (year: Int, month: Int, day: Int) -> Unit,
    onTimeSet: (hour: Int, minute: Int) -> Unit,
    onCancelActiveTT: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val surfaceWhite = if (isDark) Color(0xFF1E293B) else Color.White
    val surfaceField = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val textPrimary = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val textTitle = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val textMuted = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val borderLight = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val sky = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val badgeBg = if (isDark) sky.copy(alpha = 0.15f) else Color(0xFFE0F4F7)
    val badgeBorder = if (isDark) sky.copy(alpha = 0.4f) else Color(0xFFB2E4EC)
    val saveTop = Color(0xFF34A5E8)
    val saveBottom = Color(0xFF1E80CB)
    val orange = Color(0xFFEA580C)
    val orangeText = if (isDark) Color(0xFFFB923C) else Color(0xFFC2410C)
    val orangeSoftBg = if (isDark) orange.copy(alpha = 0.15f) else Color(0xFFFFF7ED)
    val orangeSoftBorder = if (isDark) orange.copy(alpha = 0.4f) else Color(0xFFFED7AA)
    val orangeChipBg = if (isDark) orange.copy(alpha = 0.2f) else Color(0xFFFFEDD5)
    val blueText = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1)
    val blueSoftBg = if (isDark) sky.copy(alpha = 0.12f) else Color(0xFFF0F9FF)
    val blueSoftBorder = if (isDark) sky.copy(alpha = 0.4f) else Color(0xFFBAE6FD)
    val blueChipBg = if (isDark) sky.copy(alpha = 0.2f) else Color(0xFFE0F2FE)
    val rose = Color(0xFFE11D48)
    val roseText = if (isDark) Color(0xFFFDA4AF) else Color(0xFFBE123C)
    val roseSoftBg = if (isDark) rose.copy(alpha = 0.15f) else Color(0xFFFFF1F2)
    val roseSoftBorder = if (isDark) rose.copy(alpha = 0.4f) else Color(0xFFFECDD3)
    val roseChipBg = if (isDark) rose.copy(alpha = 0.2f) else Color(0xFFFFE4E6)
    val closeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    val context = LocalContext.current
    var reasonExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color(0xFF0F172A).copy(alpha = 0.28f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(surfaceWhite)
                .border(1.dp, cardBorder, RoundedCornerShape(28.dp))
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
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
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(badgeBg)
                                    .border(1.dp, badgeBorder, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = PulseWaveIcon,
                                    contentDescription = null,
                                    tint = sky,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Temporary target",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary,
                                    letterSpacing = (-0.2).sp
                                )
                                Text(
                                    text = "Override target glucose profile",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textMuted
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
                                tint = textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // TARGET STEPPER
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(surfaceField)
                            .border(1.dp, borderLight, RoundedCornerShape(16.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = "TARGET",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = rangeText,
                                fontSize = 11.sp,
                                color = textMuted
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceWhite)
                                    .border(1.dp, borderLight, RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(closeBg)
                                        .clickable { onTargetMinus() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = targetText,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textTitle,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(closeBg)
                                        .clickable { onTargetPlus() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = unitLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    // DURATION STEPPER
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(surfaceField)
                            .border(1.dp, borderLight, RoundedCornerShape(16.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = "DURATION",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Step: 15 min",
                                fontSize = 11.sp,
                                color = textMuted
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceWhite)
                                    .border(1.dp, borderLight, RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(closeBg)
                                        .clickable { onDurationMinus() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = "$durationMin",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textTitle,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(closeBg)
                                        .clickable { onDurationPlus() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = "min",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    // QUICK PRESETS
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "QUICK PRESETS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textMuted,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { preset ->
                                val isSelected = selectedPreset == preset.kind
                                val cardBg: Color
                                val presetBorder: Color
                                val titleColor: Color
                                val subColor: Color
                                val chipBg: Color
                                val presetIcon: ImageVector
                                val iconTint: Color
                                when (preset.kind) {
                                    TempTargetPresetKind.EATING -> {
                                        cardBg = orangeSoftBg; presetBorder = orangeSoftBorder
                                        titleColor = orangeText; subColor = orangeText.copy(alpha = 0.8f)
                                        chipBg = orangeChipBg; presetIcon = FoodIcon; iconTint = Color(0xFFEA580C)
                                    }
                                    TempTargetPresetKind.ACTIVITY -> {
                                        cardBg = blueSoftBg; presetBorder = blueSoftBorder
                                        titleColor = blueText; subColor = blueText.copy(alpha = 0.8f)
                                        chipBg = blueChipBg; presetIcon = ActivityIcon; iconTint = sky
                                    }
                                    TempTargetPresetKind.HYPO -> {
                                        cardBg = roseSoftBg; presetBorder = roseSoftBorder
                                        titleColor = roseText; subColor = roseText.copy(alpha = 0.8f)
                                        chipBg = roseChipBg; presetIcon = HypoIcon; iconTint = Color(0xFFE11D48)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(cardBg)
                                        .border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) sky else presetBorder,
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable { onPresetSelected(preset.kind) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 2.dp, end = 6.dp)
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(sky),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(chipBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = presetIcon,
                                                contentDescription = null,
                                                tint = iconTint,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Text(
                                            text = preset.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = titleColor
                                        )
                                        Text(
                                            text = preset.subtitle,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = subColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // REASON
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "REASON",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textMuted,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        ExposedDropdownMenuBox(
                            expanded = reasonExpanded,
                            onExpandedChange = { reasonExpanded = !reasonExpanded }
                        ) {
                            OutlinedTextField(
                                value = reasonOptions.getOrNull(selectedReasonIndex) ?: "",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = textMuted
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = surfaceField,
                                    unfocusedContainerColor = surfaceField,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary,
                                    focusedBorderColor = borderLight,
                                    unfocusedBorderColor = borderLight
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = reasonExpanded,
                                onDismissRequest = { reasonExpanded = false }
                            ) {
                                reasonOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = { Text(option, fontSize = 13.sp, color = textPrimary) },
                                        onClick = {
                                            onReasonSelected(index)
                                            reasonExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // START EVENT TIME
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "START EVENT TIME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textMuted,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceField)
                                    .border(1.dp, borderLight, RoundedCornerShape(12.dp))
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
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        text = "DATE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textMuted
                                    )
                                    Text(
                                        text = formattedDate,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceField)
                                    .border(1.dp, borderLight, RoundedCornerShape(12.dp))
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
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccessTime,
                                    contentDescription = null,
                                    tint = textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        text = "TIME",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textMuted
                                    )
                                    Text(
                                        text = formattedTime,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                }
                            }
                        }
                    }

                    if (hasActiveTT) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(roseSoftBg)
                                .border(1.dp, roseSoftBorder, RoundedCornerShape(12.dp))
                                .clickable { onCancelActiveTT() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CANCEL ACTIVE TEMP TARGET",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = roseText,
                                letterSpacing = 0.6.sp
                            )
                        }
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

                // FOOTER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(closeBg)
                            .clickable { onDismiss() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CANCEL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textSecondary,
                            letterSpacing = 0.8.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                brush = if (isSaved) Brush.verticalGradient(
                                    listOf(Color(0xFF10B981), Color(0xFF059669))
                                )
                                else Brush.horizontalGradient(listOf(saveTop, saveBottom))
                            )
                            .clickable(enabled = !isSaving && !isSaved) { onSave() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isSaved) "SAVED" else "CONFIRM TARGET",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = 0.8.sp
                                )
                                if (!isSaved) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
