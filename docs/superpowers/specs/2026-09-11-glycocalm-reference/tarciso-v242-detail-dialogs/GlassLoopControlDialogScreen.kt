package app.aaps.plugins.main.general.overview.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class GlassLoopMode { CLOSED, LGS, OPEN, DISABLED }

data class GlassLoopControlState(
    val currentMode: GlassLoopMode,
    val showClosed: Boolean = true,
    val showLgs: Boolean = true,
    val showOpen: Boolean = true,
    val loopEnabled: Boolean = true,
    val showResume: Boolean = false,
    val resumeLabel: String = "Resume",
    val showSuspendSection: Boolean = true,
    val showDisconnectSection: Boolean = true,
    val allow15m: Boolean = true,
    val allow30m: Boolean = true
)

// Circular arrows + pause bars (amber) for suspend chips
private val SuspendPauseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SuspendPause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFF59E0B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20.49f, 9f)
            arcTo(9f, 9f, 0f, false, false, 5.64f, 5.64f)
            lineTo(3f, 8f)
            lineTo(3f, 3f)
            moveTo(3f, 8f)
            lineTo(8f, 8f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFF59E0B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.51f, 15f)
            arcTo(9f, 9f, 0f, false, false, 18.36f, 18.36f)
            lineTo(21f, 16f)
            lineTo(21f, 21f)
            moveTo(21f, 16f)
            lineTo(16f, 16f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFFF59E0B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 9.5f)
            verticalLineTo(14.5f)
            moveTo(14f, 9.5f)
            verticalLineTo(14.5f)
        }
    }.build()
}

// Circular arrows + link marks (slate) for disconnect chips
private val DisconnectIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Disconnect",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF64748B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20.49f, 9f)
            arcTo(9f, 9f, 0f, false, false, 5.64f, 5.64f)
            lineTo(3f, 8f)
            lineTo(3f, 3f)
            moveTo(3f, 8f)
            lineTo(8f, 8f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF64748B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.51f, 15f)
            arcTo(9f, 9f, 0f, false, false, 18.36f, 18.36f)
            lineTo(21f, 16f)
            lineTo(21f, 21f)
            moveTo(21f, 16f)
            lineTo(16f, 16f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF64748B)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 10.5f)
            verticalLineTo(13.5f)
            moveTo(15f, 10.5f)
            verticalLineTo(13.5f)
            moveTo(7.5f, 12f)
            horizontalLineTo(10.5f)
            moveTo(13.5f, 12f)
            horizontalLineTo(16.5f)
        }
    }.build()
}

@Composable
fun GlassLoopControlDialogScreen(
    isDark: Boolean,
    state: GlassLoopControlState,
    onModeSelected: (GlassLoopMode) -> Unit,
    onToggleLoop: () -> Unit,
    onSuspend: (hours: Int) -> Unit,
    onDisconnect: (minutes: Int) -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaceWhite = if (isDark) Color(0xFF1E293B) else Color.White
    val surfaceField = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val textPrimary = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val textTitle = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val textMuted = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val borderLight = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val sky = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val skySoftBg = if (isDark) sky.copy(alpha = 0.15f) else Color(0xFFF0F9FF)
    val skySoftBorder = if (isDark) sky.copy(alpha = 0.4f) else Color(0xFFBAE6FD)
    val blue = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val blueCardTop = Color(0xFF38A3EB)
    val blueCardBottom = Color(0xFF2589D8)
    val emerald = if (isDark) Color(0xFF34D399) else Color(0xFF10B981)
    val emeraldSoftBg = if (isDark) emerald.copy(alpha = 0.15f) else Color(0xFFECFDF5)
    val emeraldSoftBorder = if (isDark) emerald.copy(alpha = 0.4f) else Color(0xFFA7F3D0)
    val emeraldText = if (isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
    val amber = Color(0xFFF59E0B)
    val amberSoftBg = if (isDark) amber.copy(alpha = 0.15f) else Color(0xFFFFFBEB)
    val rose = if (isDark) Color(0xFFFB7185) else Color(0xFFF43F5E)
    val roseSoftBg = if (isDark) rose.copy(alpha = 0.15f) else Color(0xFFFFF1F2)
    val roseSoftBorder = if (isDark) rose.copy(alpha = 0.4f) else Color(0xFFFECDD3)
    val teal = if (isDark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
    val closeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val dividerColor = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val footerBg = if (isDark) Color(0xFF0F172A) else Color.White
    val footerBorder = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    val badgeText: String
    val badgeBg: Color
    val badgeBorder: Color
    val badgeColor: Color
    when {
        !state.loopEnabled                  -> {
            badgeText = "Off"; badgeBg = roseSoftBg; badgeBorder = roseSoftBorder; badgeColor = rose
        }
        state.currentMode == GlassLoopMode.DISABLED -> {
            badgeText = "Off"; badgeBg = roseSoftBg; badgeBorder = roseSoftBorder; badgeColor = rose
        }
        else                                -> {
            badgeText = "Active"; badgeBg = skySoftBg; badgeBorder = skySoftBorder; badgeColor = sky
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = Color(0xFF0F172A).copy(alpha = 0.22f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(surfaceWhite.copy(alpha = 0.97f))
                .border(1.dp, cardBorder, RoundedCornerShape(32.dp))
        ) {
            Column {
                // HEADER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
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
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = sky,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Loop",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textTitle
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(badgeBg)
                                        .border(1.dp, badgeBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor)
                                    )
                                    Text(
                                        text = badgeText,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = badgeColor
                                    )
                                }
                            }
                            Text(
                                text = "Micro-bolus & dosage automation",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = textSecondary
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(closeBg)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(dividerColor)
                )

                // SCROLLABLE CONTENT
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // OPERATION MODE
                    if (state.loopEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "OPERATION MODE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textMuted,
                                    letterSpacing = 0.8.sp
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (state.currentMode == GlassLoopMode.CLOSED) emeraldSoftBg
                                            else closeBg
                                        )
                                        .border(
                                            1.dp,
                                            if (state.currentMode == GlassLoopMode.CLOSED) emeraldSoftBorder
                                            else borderLight,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (state.currentMode == GlassLoopMode.CLOSED) "Automatic" else "Manual",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (state.currentMode == GlassLoopMode.CLOSED) emeraldText else textSecondary
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (state.showClosed) {
                                    ModeCard(
                                        isDark = isDark,
                                        title = "Closed Loop",
                                        subtitle = "Full Auto",
                                        selected = state.currentMode == GlassLoopMode.CLOSED,
                                        selectedBg = Brush.verticalGradient(listOf(Color(0xFF34D399), Color(0xFF059669))),
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (state.currentMode == GlassLoopMode.CLOSED) Color.White else emerald,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = { onModeSelected(GlassLoopMode.CLOSED) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (state.showLgs) {
                                    ModeCard(
                                        isDark = isDark,
                                        title = "Low Glucose Suspend",
                                        subtitle = "Active Protection",
                                        selected = state.currentMode == GlassLoopMode.LGS,
                                        selectedBg = Brush.verticalGradient(listOf(blueCardTop, blueCardBottom)),
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = null,
                                                tint = if (state.currentMode == GlassLoopMode.LGS) Color.White else blue,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = { onModeSelected(GlassLoopMode.LGS) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (state.showOpen) {
                                    ModeCard(
                                        isDark = isDark,
                                        title = "Open Loop",
                                        subtitle = "Suggest Doses",
                                        selected = state.currentMode == GlassLoopMode.OPEN,
                                        selectedBg = Brush.verticalGradient(listOf(Color(0xFF64748B), Color(0xFF334155))),
                                        icon = {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = null,
                                                tint = if (state.currentMode == GlassLoopMode.OPEN) Color.White else blue,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = { onModeSelected(GlassLoopMode.OPEN) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            // Disable / Enable row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (!state.loopEnabled) roseSoftBg else surfaceWhite)
                                    .border(
                                        1.dp,
                                        if (!state.loopEnabled) roseSoftBorder else borderLight,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onToggleLoop() }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(roseSoftBg)
                                            .border(1.dp, roseSoftBorder, RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = null,
                                            tint = rose,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Disable Loop",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textPrimary
                                        )
                                        Text(
                                            text = "Full Manual",
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                }
                                RadioDot(isDark = isDark, selected = false, selectedColor = rose)
                            }
                        }
                    } else {
                        // Loop disabled -> Enable row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(emeraldSoftBg)
                                .border(1.dp, emeraldSoftBorder, RoundedCornerShape(12.dp))
                                .clickable { onToggleLoop() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(surfaceWhite)
                                        .border(1.dp, emeraldSoftBorder, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = emerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Enable Loop",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = "Resume automation",
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                }
                            }
                            RadioDot(isDark = isDark, selected = true, selectedColor = emerald)
                        }
                    }

                    // SUSPEND LOOP
                    if (state.showSuspendSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        imageVector = SuspendPauseIcon,
                                        contentDescription = null,
                                        tint = amber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "SUSPEND LOOP",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textSecondary,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                Text(
                                    text = "Auto-resumes",
                                    fontSize = 11.sp,
                                    color = textMuted
                                )
                            }
                            if (state.showResume) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(emeraldSoftBg)
                                        .border(1.dp, emeraldSoftBorder, RoundedCornerShape(12.dp))
                                        .clickable { onResume() }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = state.resumeLabel,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = emeraldText
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SuspendChip(isDark = isDark, label = "1 HOUR", sub = "Pause", onClick = { onSuspend(1) }, modifier = Modifier.weight(1f))
                                SuspendChip(isDark = isDark, label = "2 HOURS", sub = "Pause", onClick = { onSuspend(2) }, modifier = Modifier.weight(1f))
                                SuspendChip(isDark = isDark, label = "3 HOURS", sub = "Pause", onClick = { onSuspend(3) }, modifier = Modifier.weight(1f))
                                SuspendChip(isDark = isDark, label = "10 HOURS", sub = "Night", onClick = { onSuspend(10) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // DISCONNECT PUMP
                    if (state.showDisconnectSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        imageVector = DisconnectIcon,
                                        contentDescription = null,
                                        tint = textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "DISCONNECT PUMP",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textSecondary,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                Text(
                                    text = "Suspends basal",
                                    fontSize = 11.sp,
                                    color = textMuted
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (state.allow15m) {
                                    DisconnectChip(isDark = isDark, label = "15 MINS", sub = "QUICK", onClick = { onDisconnect(15) }, modifier = Modifier.weight(1f))
                                }
                                if (state.allow30m) {
                                    DisconnectChip(isDark = isDark, label = "30 MINS", sub = "SHOWER", onClick = { onDisconnect(30) }, modifier = Modifier.weight(1f))
                                }
                                DisconnectChip(isDark = isDark, label = "1 HOUR", sub = "EXERCISE", onClick = { onDisconnect(60) }, modifier = Modifier.weight(1f))
                                DisconnectChip(isDark = isDark, label = "2 HOURS", sub = "SWIM", onClick = { onDisconnect(120) }, modifier = Modifier.weight(1f))
                                DisconnectChip(isDark = isDark, label = "3 HOURS", sub = "LONG", onClick = { onDisconnect(180) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // INFO BANNER
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(surfaceField)
                            .border(1.dp, borderLight, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = teal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Changes to loop mode immediately affect automatic micro-bolus dosing. Manual doses remain available.",
                            fontSize = 11.sp,
                            color = textSecondary,
                            lineHeight = 14.sp
                        )
                    }
                }

                // FOOTER
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(footerBg)
                        .border(1.dp, footerBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(surfaceWhite)
                            .border(1.dp, borderLight, RoundedCornerShape(14.dp))
                            .clickable { onDismiss() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CANCEL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = textSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    isDark: Boolean,
    title: String,
    subtitle: String,
    selected: Boolean,
    selectedBg: Brush,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) Color(0xFF2589D8) else if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val cardBg: Brush = if (selected) selectedBg
    else Brush.verticalGradient(
        if (isDark) listOf(Color(0xFF1E293B), Color(0xFF1E293B))
        else listOf(Color.White, Color.White)
    )
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) Color.White.copy(alpha = 0.2f)
                            else if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
                RadioDot(
                    isDark = isDark,
                    selected = selected,
                    selectedColor = if (selected) Color.White else if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                    lineHeight = 13.sp
                )
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) Color.White.copy(alpha = 0.9f) else if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun RadioDot(
    isDark: Boolean,
    selected: Boolean,
    selectedColor: Color
) {
    val unchecked = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1)
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(if (selected) selectedColor else Color.Transparent)
            .border(
                2.dp,
                if (selected) selectedColor else unchecked,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selectedColor == Color.White) Color(0xFF2589D8) else Color.White
                    )
            )
        }
    }
}

@Composable
private fun SuspendChip(
    isDark: Boolean,
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0xFF1E293B) else Color.White)
            .border(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = SuspendPauseIcon,
            contentDescription = null,
            tint = Color(0xFFF59E0B),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            textAlign = TextAlign.Center
        )
        Text(
            text = sub,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun DisconnectChip(
    isDark: Boolean,
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0xFF1E293B) else Color.White)
            .border(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = DisconnectIcon,
            contentDescription = null,
            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            textAlign = TextAlign.Center
        )
        Text(
            text = sub,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        )
    }
}
