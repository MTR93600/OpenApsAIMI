# Glass StatusAgoraCard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new selectable "Glass" home-screen skin whose main card is a faithful, working port of the
reference's `StatusAgoraCard` (3-column glassmorphism status card), fed by real, live data — the first
end-to-end visible slice of the larger Glass dashboard port.

**Architecture:** Port the reference's pure-UI composables (`GlassContainer`, `GlassPill`, `BottomMetricPill`,
`StatusAgoraCard`) near-verbatim into a new `plugins/main/.../dashboard/glass/` package. Instead of a second,
parallel data-computation ViewModel (as the design doc first sketched), derive `GlassUiState` **inline in
Compose** from two ViewModels this codebase already has and already computes this exact data in:
`OverviewViewModel.statusCardState` (glucose/delta/IOB/target/basal/loop) and `StatusViewModel.uiState`
(sensor/insulin/cannula/battery age + `StatusLevel` warning color). This avoids injecting one ViewModel into
another (fragile in Hilt) and avoids re-deriving data a tested ViewModel already provides.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 not used for these composables — Glass draws its own
glassmorphism shapes/gradients), Hilt (existing ViewModels only, no new ViewModel class in this plan).

**Spec:** `docs/superpowers/specs/2026-09-10-glass-dashboard-port-design.md`

**Deviation from the spec, decided during planning (documented here per that skill's own rules):** the spec's
architecture section said "new Hilt ViewModel using our own suspend-based PersistenceLayer...". Investigation
during planning found this would duplicate ~30 already-computed fields that `OverviewViewModel.statusCardState`
and `StatusViewModel.uiState` already expose correctly (including the exact sensor/insulin/cannula/battery
age + warning-color model via `StatusItem`/`StatusLevel`, which is richer than the reference's raw `Int`
colors). This plan maps those two existing states into `GlassUiState` instead. No new data source is created.

## Global Constraints

- No new inter-module Gradle dependencies (project convention).
- No new `DialogFragment`s — every click handler routes through `uiInteraction.openComposeMainAtRoute` /
  existing typed `UiInteraction` methods, per the spec's click-handler mapping table.
- No `git commit` unless the user explicitly asks.
- Compile after every task; manually verify visually once Task 6 is done (this is a UI-heavy port with no
  existing Compose UI test harness in this module, matching this session's established practice).
- Simple, plain English in any new user-facing string, KDoc, or comment.
- Never manipulate localized/formatted strings programmatically (no `.replace`/`.removePrefix` etc. on
  `StatusCardState.deltaText` or similar) — reuse formatted text as-is even where it differs cosmetically from
  the reference (noted per-field below where this applies).

---

### Task 1: Copy the 5 Glass icon drawables

**Files:**
- Create (copy verbatim, no edits): `plugins/main/src/main/res/drawable/ic_glyco_insulin.xml`
- Create (copy verbatim, no edits): `plugins/main/src/main/res/drawable/ic_glyco_cannula.xml`
- Create (copy verbatim, no edits): `plugins/main/src/main/res/drawable/ic_glyco_battery.xml`
- Create (copy verbatim, no edits): `plugins/main/src/main/res/drawable/ic_glyco_sensor.xml`
- Create (copy verbatim, no edits): `plugins/main/src/main/res/drawable/ic_glyco_settings.xml`

**Interfaces:**
- Produces: `app.aaps.plugins.main.R.drawable.ic_glyco_insulin` / `ic_glyco_cannula` / `ic_glyco_battery` /
  `ic_glyco_sensor` / `ic_glyco_settings` — usable directly, no adaptation, because both the reference and our
  repo use the identical module package `app.aaps.plugins.main`.

- [ ] **Step 1: Copy the files**

```bash
cp ~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/res/drawable/ic_glyco_insulin.xml \
   /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_insulin.xml
cp ~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/res/drawable/ic_glyco_cannula.xml \
   /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_cannula.xml
cp ~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/res/drawable/ic_glyco_battery.xml \
   /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_battery.xml
cp ~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/res/drawable/ic_glyco_sensor.xml \
   /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_sensor.xml
cp ~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/res/drawable/ic_glyco_settings.xml \
   /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_settings.xml
```

- [ ] **Step 2: Verify the files landed correctly**

Run: `ls /Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/drawable/ic_glyco_*.xml`
Expected: 5 files listed.

---

### Task 2: Port `GlassUiState` (trimmed to this slice's fields)

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewModels.kt`

**Interfaces:**
- Produces: `data class GlassUiState(...)` — consumed by Task 4 (`StatusAgoraCard`) and Task 6 (the mapping
  function).

Only the fields `StatusAgoraCard` actually reads are included (the reference's `GlassUiState` also carries
`bgReadings`/`iobReadings`/`treatments`/`stats`/`notifications`/`isDarkMode` for other Glass screens — those
belong to the follow-up plan that ports `BgChartCard`/`IobChartCard`/`TimeFilterBar`, not this one; adding
fields with no data source yet would be exactly the kind of placeholder this process forbids). Two fields
change type from the reference: `insulinAgeColor`/`cannulaAgeColor`/`batteryAgeColor`/`sensorAgeColor` become
`app.aaps.core.ui.compose.StatusLevel` instead of a raw `Int` color, because our codebase's `StatusItem` already
models warning color this way (`StatusItem.ageStatus: StatusLevel`) — resolved to an actual `Color` at render
time via the existing `statusLevelToColor(StatusLevel): Color` composable (`core/ui/.../StatusLevel.kt`).

- [ ] **Step 1: Write the file**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.ui.compose.StatusLevel

/** UI state for the ported Glass StatusAgoraCard — trimmed to only the fields it renders. */
data class GlassUiState(
    val currentBg: String = "--",
    val unit: String = "mg/dL",
    val glucoseColor: Int = 0xFF94A3B8.toInt(),
    val deltaText: String = "",
    val trendArrowRes: Int? = null,
    val timeAgo: String = "--",
    val insulinAge: String = "--",
    val insulinAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val cannulaAge: String = "--",
    val cannulaAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val batteryAge: String = "--",
    val batteryAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val sensorAge: String = "--",
    val sensorAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val loopStatusText: String = "Loop",
    val iobText: String = "--",
    val isTempTargetActive: Boolean = false,
    val targetText: String = "--",
    val basalPercentText: String = "--",
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Port `GlassContainer`, `GlassPill`, `BottomMetricPill`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComponents.kt`

**Interfaces:**
- Produces: `GlassContainer(isDark, modifier, content)`, `GlassPill(label, value, isDark, valueColor?, modifier,
  leadingIcon?)`, `BottomMetricPill(title, value, isDark, onClick?, modifier, accentColor?)` — all `internal`,
  consumed by Task 4's `StatusAgoraCard`.

These three are ported **verbatim** from the reference (they take only primitive/Compose types as parameters,
no reference-fork-specific dependency) — copy the exact code below, changing only the `@Composable fun` to
`@Composable internal fun` (this package's composables are not part of any public API) and the package
declaration.

- [ ] **Step 1: Write the file**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement

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
    val shape = RoundedCornerShape(13.dp)
    val bgBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x18FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xE4E8F0FA)))
    }
    val borderColor = if (isDark) Color(0x38FFFFFF) else Color(0xD0CBD5E1)

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 6.dp else 3.dp,
                shape = shape,
                spotColor = if (isDark) Color.Black.copy(alpha = 0.50f) else Color(0x22000000)
            )
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (leadingIcon != null) {
                leadingIcon()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
            ) {
                androidx.compose.material3.Text(
                    text = label,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    lineHeight = 11.sp
                )
                androidx.compose.material3.Text(
                    text = value,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor ?: if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                    lineHeight = 13.sp
                )
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
                androidx.compose.material3.Text(
                    text = "$title ",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
            androidx.compose.material3.Text(
                text = value,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor
            )
        }
    }
}
```

Note: the reference file has a single `import androidx.compose.material3.*` at the top covering `Text`; this
port uses explicit `androidx.compose.material3.Text(...)` calls instead (per this project's explicit-imports
rule) rather than a star import — add `import androidx.compose.material3.Text` and drop the fully-qualified
calls if preferred, but keep it one or the other consistently, not mixed.

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Port `StatusAgoraCard`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`

**Interfaces:**
- Consumes: `GlassUiState` (Task 2), `GlassContainer`/`GlassPill`/`BottomMetricPill` (Task 3),
  `statusLevelToColor(StatusLevel): Color` (`app.aaps.core.ui.compose.statusLevelToColor`, already exists).
- Produces: `@Composable internal fun StatusAgoraCard(state: GlassUiState, isDark: Boolean, onOpenLoop: () ->
  Unit, onOpenTarget: () -> Unit, onOpenInsulin: () -> Unit, onOpenPump: () -> Unit, onOpenCannula: () -> Unit,
  onOpenBattery: () -> Unit, onOpenBasal: () -> Unit, onOpenSensorInsert: () -> Unit, onOpenPreferences: () ->
  Unit)` — consumed by Task 6.

Ported from the reference with these deliberate adaptations (both explained, neither a placeholder):
1. `onToggleTheme` is renamed `onOpenPreferences` and the "Ajustes" pill's label/value change from
   `"Ajustes"/"Perfil auto"` to reuse `app.aaps.core.ui.R.string.preferences`/a static "—" value — this app has
   one global dark-mode preference, not a per-screen toggle, so the pill's real job here is a settings shortcut.
2. `Color(state.xColor)` (reference, raw `Int`) becomes `statusLevelToColor(state.xStatus)` (our `StatusLevel`
   enum, richer and already used by the rest of this codebase's status UI).
3. The reference recomputes `glucoseColor` from `rawBg`/`lowLine`/`highLine`; this port uses
   `Color(state.glucoseColor)` directly — `OverviewViewModel` already computes this correctly (Task 6), so
   there is no need for `GlassUiState` to carry raw BG + low/high lines just to redo that comparison.
4. `deltaText`/`iobText`/`targetText`/`basalPercentText` are pre-formatted strings from `StatusCardState`
   (reused verbatim, per the Global Constraints rule against manipulating formatted text) rather than the
   reference's raw numeric fields assembled into a `String.format(...)` inline — e.g. `state.deltaText` renders
   whatever `OverviewViewModel` already formats (this may include its own leading "Δ" glyph, a small, accepted
   cosmetic difference from the reference noted here rather than hidden).

- [ ] **Step 1: Write the file**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.main.R

@Composable
internal fun StatusAgoraCard(
    state: GlassUiState,
    isDark: Boolean,
    onOpenLoop: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenInsulin: () -> Unit,
    onOpenPump: () -> Unit,
    onOpenCannula: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBasal: () -> Unit,
    onOpenSensorInsert: () -> Unit,
    onOpenPreferences: () -> Unit,
) {
    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LEFT: pump status
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassPill(
                        label = "Insulina",
                        value = state.insulinAge,
                        isDark = isDark,
                        valueColor = statusLevelToColor(state.insulinAgeStatus),
                        modifier = Modifier.width(100.dp).clickable { onOpenPump() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_insulin),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    GlassPill(
                        label = "Cânula",
                        value = state.cannulaAge,
                        isDark = isDark,
                        valueColor = statusLevelToColor(state.cannulaAgeStatus),
                        modifier = Modifier.width(100.dp).clickable { onOpenCannula() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_cannula),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    GlassPill(
                        label = "Bateria",
                        value = state.batteryAge,
                        isDark = isDark,
                        valueColor = statusLevelToColor(state.batteryAgeStatus),
                        modifier = Modifier.width(100.dp).clickable { onOpenBattery() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_battery),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                // CENTER: glucose
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = state.currentBg,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(state.glucoseColor),
                        letterSpacing = (-1.5).sp,
                        lineHeight = 42.sp,
                        modifier = Modifier.clickable { onOpenLoop() }
                    )
                    Text(
                        text = state.unit,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (state.trendArrowRes != null) {
                            Icon(
                                painter = painterResource(id = state.trendArrowRes),
                                contentDescription = null,
                                tint = Color(state.glucoseColor),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = state.deltaText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(state.glucoseColor),
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.timeAgo,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }

                // RIGHT: CGM & loop
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassPill(
                        label = "Sensor",
                        value = state.sensorAge,
                        isDark = isDark,
                        valueColor = statusLevelToColor(state.sensorAgeStatus),
                        modifier = Modifier.width(100.dp).clickable { onOpenSensorInsert() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_sensor),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    GlassPill(
                        label = "Loop",
                        value = state.loopStatusText,
                        isDark = isDark,
                        modifier = Modifier.width(100.dp).clickable { onOpenLoop() },
                        leadingIcon = {
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 0.8f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF22C55E).copy(alpha = pulseAlpha), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                            }
                        }
                    )
                    GlassPill(
                        label = stringResource(CoreUiR.string.preferences),
                        value = "—",
                        isDark = isDark,
                        modifier = Modifier.width(100.dp).clickable { onOpenPreferences() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_settings),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BottomMetricPill(
                    title = "IOB",
                    value = state.iobText,
                    isDark = isDark,
                    onClick = onOpenInsulin,
                    modifier = Modifier.weight(1f)
                )
                BottomMetricPill(
                    title = if (state.isTempTargetActive) "" else "Target",
                    value = state.targetText,
                    isDark = isDark,
                    onClick = onOpenTarget,
                    modifier = Modifier.weight(1f),
                    accentColor = if (state.isTempTargetActive) Color(0xFFF4D700) else null
                )
                BottomMetricPill(
                    title = "Basal T",
                    value = state.basalPercentText,
                    isDark = isDark,
                    onClick = onOpenBasal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
```

Confirm `app.aaps.core.ui.R.string.preferences` exists (`core/ui/src/main/res/values/strings.xml`) before
using it — if the exact key differs, use whatever key that file already defines for the word "Preferences"
(do not invent a new string resource for a word this codebase already translates).

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: `GlassHeroCommands` — real click targets, no new dialogs

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`
  (add the implementation, same object/pattern as `createHeroCommands()`)

**Interfaces:**
- Consumes: `uiInteraction: UiInteraction` (already a `DashboardShellController` field), `host.activity`/
  `host.context` (already available), `protectionCheck: ProtectionCheck` (already available — reuse the
  `withBolusProtection` helper added earlier this session for the carbs/wizard/temp-target/quick-wizard
  dashboard shortcuts, since these Glass pills reach the same protected screens).
- Produces: `interface GlassHeroCommands { fun openLoop(); fun openTarget(); fun openInsulin(); fun openPump();
  fun openCannula(); fun openBattery(); fun openBasal(); fun openSensorInsert(); fun openPreferences() }` +
  `NoopGlassHeroCommands` + `LocalGlassHeroCommands` — consumed by Task 6.

Target routes (all real, already-existing destinations — no placeholder):

| Command | Implementation |
|---|---|
| `openLoop` | `uiInteraction.openRunningModeScreen(activity)` — same call `DashboardHeroCommands.openLoopDialogFromHero` already uses |
| `openInsulin` | `uiInteraction.openInsulinScreen(activity)` — same call `DashboardShellController.openBolus()` already uses |
| `openTarget` | `withBolusProtection { activity -> uiInteraction.openTempTargetManagementScreen(activity) }` |
| `openBasal` | `withBolusProtection { uiInteraction.openComposeMainAtRoute(host.context, "temp_basal_dialog") }` (`AppRoute.TempBasalDialog.route`) |
| `openCannula` | `withBolusProtection { uiInteraction.openComposeMainAtRoute(host.context, "fill_dialog/0") }` (`AppRoute.FillDialog.createRoute(0)` — `0` = no preselected fill type, matches the route's own default) |
| `openPump`, `openBattery` | reuse the exact `OKDialog.show(context, label, value)` pattern already built for DASHBOARD_V2's Reservoir/Battery badges this session — a real, working detail popup, not the reference's deeper pump-fragment/CareDialog navigation (documented simplification: those specific deep links need more investigation into this codebase's `UiInteraction.EventType`/pump-plugin-index equivalents, deferred to a follow-up task once this card is confirmed visually correct) |
| `openSensorInsert` | until Phase 3 of the spec (the ported `GlassSensorInsertScreen`) exists, route to `uiInteraction.openComposeMainAtRoute(host.context, "care_dialog/${TE.Type.SENSOR_CHANGE.ordinal}")` (`AppRoute.CareDialog`) — reuses the existing Care Portal sensor-change dialog, a real screen, not a stub |
| `openPreferences` | `uiInteraction.openComposeMainAtRoute(host.context, "preferences")` (`AppRoute.Preferences.route`) |

- [ ] **Step 1: Write `GlassHeroCommands.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.runtime.compositionLocalOf

interface GlassHeroCommands {
    fun openLoop()
    fun openTarget()
    fun openInsulin()
    fun openPump()
    fun openCannula()
    fun openBattery()
    fun openBasal()
    fun openSensorInsert()
    fun openPreferences()
}

object NoopGlassHeroCommands : GlassHeroCommands {
    override fun openLoop() {}
    override fun openTarget() {}
    override fun openInsulin() {}
    override fun openPump() {}
    override fun openCannula() {}
    override fun openBattery() {}
    override fun openBasal() {}
    override fun openSensorInsert() {}
    override fun openPreferences() {}
}

val LocalGlassHeroCommands = compositionLocalOf<GlassHeroCommands> { NoopGlassHeroCommands }
```

- [ ] **Step 2: Implement in `DashboardShellController.kt`**

Add a `createGlassHeroCommands(): GlassHeroCommands` method next to the existing `createHeroCommands()`,
reusing the `withBolusProtection` helper already added earlier this session (`DashboardShellController.kt`,
the method whose signature is `private fun withBolusProtection(action: (FragmentActivity) -> Unit)`):

```kotlin
private fun createGlassHeroCommands(): GlassHeroCommands =
    object : GlassHeroCommands {
        override fun openLoop() = openLoopDialog()

        override fun openInsulin() = openBolus().let { }

        override fun openTarget() = withBolusProtection { activity ->
            uiInteraction.openTempTargetManagementScreen(activity)
        }

        override fun openBasal() = withBolusProtection {
            uiInteraction.openComposeMainAtRoute(host.context, "temp_basal_dialog")
        }

        override fun openCannula() = withBolusProtection {
            uiInteraction.openComposeMainAtRoute(host.context, "fill_dialog/0")
        }

        override fun openPump() {
            // Reservoir detail — same OKDialog pattern as DASHBOARD_V2's Reservoir badge.
            OKDialog.show(host.context, "Insulina", statusCardStateSnapshot()?.reservoirText ?: "--")
        }

        override fun openBattery() {
            OKDialog.show(host.context, "Bateria", statusCardStateSnapshot()?.pumpBatteryText ?: "--")
        }

        override fun openSensorInsert() = withBolusProtection {
            uiInteraction.openComposeMainAtRoute(
                host.context,
                "care_dialog/${app.aaps.core.data.model.TE.Type.SENSOR_CHANGE.ordinal}",
            )
        }

        override fun openPreferences() =
            uiInteraction.openComposeMainAtRoute(host.context, "preferences")
    }
```

`statusCardStateSnapshot()` is a small helper to add alongside — `DashboardShellController` already holds
`viewModel: OverviewViewModel` (constructor param, confirmed present since it's used to build the existing
`createHeroCommands()`'s neighbors); add:

```kotlin
private fun statusCardStateSnapshot(): StatusCardState? = viewModel.statusCardState.value
```

(`OverviewViewModel.statusCardState` is a `LiveData<StatusCardState>` — `.value` reads the current value
synchronously, safe to call from a click handler on the main thread.)

Note `openInsulin()`'s body: the existing private `openBolus(): Boolean` method already does exactly the
`protectionCheck` + `uiInteraction.openInsulinScreen(activity)` sequence this command needs — call it directly
and discard its `Boolean` return (`.let { }` used only to satisfy the `Unit`-returning override in one
expression; an ordinary `{ openBolus() }` block also works and is clearer — use that instead of the `.let`
form shown above).

- [ ] **Step 3: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Skin registration + the embedding composable

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/skins/DashboardHomeVariant.kt`
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/skins/SkinGlass.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/AimiDashboardComposeRootView.kt`
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: `StatusAgoraCard` (Task 4), `GlassUiState` (Task 2), `LocalGlassHeroCommands`/`GlassHeroCommands`
  (Task 5), `OverviewViewModel.statusCardState: LiveData<StatusCardState>` (existing),
  `StatusViewModel.uiState: StateFlow<StatusUiState>` (existing, `ui/.../overview/statusLights/`).
- Produces: `GlassOverviewComposeEmbedded(overviewViewModel: OverviewViewModel, statusViewModel:
  StatusViewModel, isDark: Boolean, modifier: Modifier)` — a `@Composable` that renders `StatusAgoraCard` with
  live data, wired into `AimiDashboardComposeRootView`'s `when (dashboardHomeVariant)`.

- [ ] **Step 1: Add the `GLASS` enum entry**

`DashboardHomeVariant.kt` currently:
```kotlin
enum class DashboardHomeVariant { OVERVIEW, DASHBOARD_V1, DASHBOARD_V2 }
```
becomes:
```kotlin
enum class DashboardHomeVariant { OVERVIEW, DASHBOARD_V1, DASHBOARD_V2, GLASS }
```

- [ ] **Step 2: Create `SkinGlass`**

Read `plugins/main/src/main/kotlin/app/aaps/plugins/main/skins/SkinDashboardV2.kt` first — copy its exact
shape (class declaration, constructor injection, any other `SkinInterface` members it overrides beyond
`dashboardHomeVariant`) and produce `SkinGlass.kt` identically except `dashboardHomeVariant` returns `GLASS`
and the class is named `SkinGlass`. Do not guess `SkinInterface`'s other members — copy `SkinDashboardV2.kt`'s
overrides verbatim aside from the name and the one changed property, so nothing required by the interface is
missed.

- [ ] **Step 3: Write `GlassOverviewComposeEmbedded.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusUiState
import app.aaps.ui.compose.overview.statusLights.StatusViewModel

@Composable
internal fun GlassOverviewComposeEmbedded(
    overviewViewModel: OverviewViewModel,
    statusViewModel: StatusViewModel,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val status by overviewViewModel.statusCardState.observeAsState()
    val statusLights by statusViewModel.uiState.collectAsStateWithLifecycle()
    val commands = LocalGlassHeroCommands.current

    AapsTheme {
        val state = buildGlassUiState(status, statusLights)
        StatusAgoraCard(
            state = state,
            isDark = isDark,
            onOpenLoop = commands::openLoop,
            onOpenTarget = commands::openTarget,
            onOpenInsulin = commands::openInsulin,
            onOpenPump = commands::openPump,
            onOpenCannula = commands::openCannula,
            onOpenBattery = commands::openBattery,
            onOpenBasal = commands::openBasal,
            onOpenSensorInsert = commands::openSensorInsert,
            onOpenPreferences = commands::openPreferences,
        )
    }
}

private fun buildGlassUiState(status: StatusCardState?, lights: StatusUiState?): GlassUiState {
    if (status == null) return GlassUiState()
    return GlassUiState(
        currentBg = status.glucoseText,
        glucoseColor = status.glucoseColor,
        deltaText = status.deltaText,
        trendArrowRes = status.trendArrowRes,
        timeAgo = status.timeAgo,
        insulinAge = lights?.insulinStatus?.age ?: "--",
        insulinAgeStatus = lights?.insulinStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        cannulaAge = lights?.cannulaStatus?.age ?: "--",
        cannulaAgeStatus = lights?.cannulaStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        batteryAge = lights?.batteryStatus?.age ?: "--",
        batteryAgeStatus = lights?.batteryStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        sensorAge = lights?.sensorStatus?.age ?: "--",
        sensorAgeStatus = lights?.sensorStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        loopStatusText = status.loopStatusText,
        iobText = status.iobText,
        isTempTargetActive = !status.targetText.isNullOrBlank(),
        targetText = status.targetText ?: "--",
        basalPercentText = status.effectiveBasalText ?: "--",
    )
}
```

`StatusItem`/`StatusUiState` imports must match their real package — confirmed earlier in this session as
`app.aaps.ui.compose.overview.statusLights` (`ui` module, already a dependency of `plugins:main`).

- [ ] **Step 4: Wire the `AimiDashboardComposeRootView.kt` branch**

Currently (from this session's earlier reading of the file):
```kotlin
when (dashboardHomeVariant) {
    DashboardHomeVariant.DASHBOARD_V2 -> DashboardV2ComposeEmbedded(
        shellPostRoot = this@AimiDashboardComposeRootView,
        embeddedState = embeddedComposeState,
        viewModel = viewModel,
        graphViewModel = graphViewModel,
        config = deps.config,
        onShellBindingReady = onShellBindingReady,
    )

    DashboardHomeVariant.DASHBOARD_V1,
    DashboardHomeVariant.OVERVIEW,
    -> AimiDashboardComposeEmbedded(
        shellPostRoot = this@AimiDashboardComposeRootView,
        embeddedState = embeddedComposeState,
        preferences = deps.preferences,
        viewModel = viewModel,
        graphViewModel = graphViewModel,
        onShellBindingReady = onShellBindingReady,
    )
}
```

Add a `GLASS` branch. It needs a `StatusViewModel` instance — obtain it the same way `graphViewModel` is
obtained just above in this same file (`ViewModelProvider(act)[GraphViewModel::class.java]`):

```kotlin
val statusViewModel = ViewModelProvider(act)[StatusViewModel::class.java]
```

(add this line next to the existing `val graphViewModel = ...` line, before the `setContent {}` block, so it's
in scope for the `when`). Then:

```kotlin
when (dashboardHomeVariant) {
    DashboardHomeVariant.DASHBOARD_V2 -> DashboardV2ComposeEmbedded(
        shellPostRoot = this@AimiDashboardComposeRootView,
        embeddedState = embeddedComposeState,
        viewModel = viewModel,
        graphViewModel = graphViewModel,
        config = deps.config,
        onShellBindingReady = onShellBindingReady,
    )

    DashboardHomeVariant.GLASS -> {
        onShellBindingReady(
            DashboardShellBinding.fromComposeEmbeddedColumn(
                shellPostRoot = this@AimiDashboardComposeRootView,
                auditorHost = FrameLayout(context),
                glucoseGraph = null,
            ),
        )
        GlassOverviewComposeEmbedded(
            overviewViewModel = viewModel,
            statusViewModel = statusViewModel,
            isDark = androidx.compose.foundation.isSystemInDarkTheme(),
        )
    }

    DashboardHomeVariant.DASHBOARD_V1,
    DashboardHomeVariant.OVERVIEW,
    -> AimiDashboardComposeEmbedded(
        shellPostRoot = this@AimiDashboardComposeRootView,
        embeddedState = embeddedComposeState,
        preferences = deps.preferences,
        viewModel = viewModel,
        graphViewModel = graphViewModel,
        onShellBindingReady = onShellBindingReady,
    )
}
```

The `GLASS` branch's `onShellBindingReady(...)` call with a throwaway `FrameLayout(context)` auditor host
mirrors how `DashboardV2ComposeEmbedded` itself constructs its binding (confirmed at the top of that file
earlier this session) — Glass doesn't use the auditor overlay this phase, so an unattached `FrameLayout` is a
real, harmless placeholder view (not a placeholder *behavior*), matching the shell's expected binding contract
without adding a real auditor feature this phase doesn't need yet.

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compile the whole app**

Run: `./gradlew :app:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL` (confirms the new `DashboardHomeVariant.GLASS` case doesn't break any other
exhaustive `when` on that enum — re-check `ComposeMainActivity.kt` if this fails, per the spec's note that its
comparisons there are `==`/`!=`, not exhaustive `when`, so should be unaffected, but verify).

- [ ] **Step 7: Manual verification**

Selecting the Glass skin requires a `StringKey.GeneralSkin` preference value that resolves to `SkinGlass` via
`DashboardHomeVariantResolver` — confirm how `SkinDashboardV2`/`SkinMinimal` register their own selectable
identity (probably a preference-value string tied to the class) and give `SkinGlass` the equivalent, so it's
reachable from the app's skin picker before asking the user to verify visually on-device.

## Self-Review Notes

- **Spec coverage**: this plan covers only `StatusAgoraCard` from the spec's Phase 1 (skin registration +
  the main card). `BgChartCard`/`IobChartCard`/`TimeFilterBar`/notifications and Phases 2-4 are intentionally
  out of scope — each needs its own plan once this slice is confirmed correct on-device, per the Scope Check
  rule (this alone is already a 6-task, 6-file plan).
- **Placeholder scan**: every click handler has a real, working target (Task 5's table); the one simplified
  pair (`openPump`/`openBattery` using a value-only dialog instead of the reference's deeper navigation) is
  explicitly documented as a deliberate, real fallback, not a stub.
- **Type consistency**: `GlassUiState` (Task 2) fields match exactly what `StatusAgoraCard` (Task 4) reads and
  what `buildGlassUiState` (Task 6) produces — cross-checked name-by-name while writing this plan.
