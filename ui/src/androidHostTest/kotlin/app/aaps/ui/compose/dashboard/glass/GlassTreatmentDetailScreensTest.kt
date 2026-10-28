package app.aaps.ui.compose.dashboard.glass

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TTPreset
import app.aaps.core.data.ue.Action
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.ui.UiStringIds
import app.aaps.ui.compose.careDialog.CareDialogUiState
import app.aaps.ui.compose.careDialog.CareportalEventType
import app.aaps.ui.compose.fillDialog.FillDialogUiState
import app.aaps.ui.compose.insulinDialog.InsulinDialogUiState
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.graphs.BgInfoUiState
import app.aaps.ui.compose.runningMode.PendingRunningModeAction
import app.aaps.ui.compose.runningMode.RunningModeManagementUiState
import app.aaps.ui.compose.tempBasalDialog.TempBasalDialogUiState
import app.aaps.ui.compose.tempTarget.TempTargetManagementUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.R as InterfacesR
import app.aaps.core.ui.R as CoreUiR
import app.aaps.ui.R as UiR

/**
 * The six Glass screens that start a treatment or change the loop render, and keep the gates of the
 * normal screens: OK stays disabled without an action or while a prepare runs, the record-only
 * banner shows, the prime is absent where the pump cannot prime, Activate and Cancel are hidden when
 * the master cannot be reached, and a loop button sends the action of the normal screen. The view
 * models are not involved: each test renders the stateless content with a ui state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassTreatmentDetailScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var ctx: Context
    private lateinit var confirm: String
    private lateinit var back: String

    @Before
    fun setUp() {
        // MainApp does this in production; without it the UiStrings would render as their names.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
        ctx = RuntimeEnvironment.getApplication()
        confirm = ctx.getString(CoreUiR.string.confirm)
        back = ctx.getString(CoreUiR.string.back)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { content() } }
    }

    // region insulin

    @Composable
    private fun Insulin(uiState: InsulinDialogUiState, onConfirm: () -> Unit = {}, onBack: () -> Unit = {}) {
        GlassInsulinDetailContent(
            uiState = uiState,
            isDark = true,
            bgInfo = BgInfoUiState(bgInfo = null, timeAgoText = ""),
            iob = IobUiState(),
            cob = CobUiState(),
            dateString = "2024-01-01",
            timeString = "12:00",
            bolusFormat = NumberFormat.DECIMAL_1,
            formatAmount = { it.toString() },
            onEatingSoonChange = {},
            onRecordOnlyChange = {},
            onInsulinChange = {},
            onAddInsulin = {},
            onTimeOffsetChange = {},
            onNotesChange = {},
            onDateClick = {},
            onTimeClick = {},
            onInsulinTypeSelect = {},
            onSettingsClick = null,
            onNavigateBack = onBack,
            onConfirmClick = onConfirm
        )
    }

    @Test
    fun insulinOkIsDisabledWithoutAnAmount() {
        show { Insulin(InsulinDialogUiState(insulin = 0.0)) }
        compose.onNodeWithContentDescription(confirm).assertIsNotEnabled()
    }

    @Test
    fun insulinOkAsksTheViewModelAndBackCloses() {
        var confirmed = 0
        var closed = 0
        show { Insulin(InsulinDialogUiState(insulin = 1.0, maxInsulin = 10.0), onConfirm = { confirmed++ }, onBack = { closed++ }) }
        compose.onNodeWithContentDescription(confirm).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription(back).performClick()
        assertThat(confirmed).isEqualTo(1)
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun insulinWaitsWhileAnSmbIsDelivered() {
        show { Insulin(InsulinDialogUiState(insulin = 1.0, maxInsulin = 10.0, smbBeingDelivered = true)) }
        compose.onNodeWithContentDescription(confirm).assertIsNotEnabled()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.smb_being_delivered_confirm_later)).assertIsDisplayed()
    }

    @Test
    fun forcedRecordOnlyShowsTheBanner() {
        show { Insulin(InsulinDialogUiState(forcedRecordOnly = true, recordOnlyChecked = true)) }
        // The banner and the switch have the same text.
        compose.onAllNodesWithText(ctx.getString(InterfacesR.string.bolus_recorded_only)).assertCountEquals(2)
    }

    // endregion

    // region cannula

    @Composable
    private fun Cannula(uiState: FillDialogUiState, onConfirm: () -> Unit = {}) {
        GlassCannulaDetailContent(
            uiState = uiState,
            isDark = false,
            dateString = "2024-01-01",
            timeString = "12:00",
            bolusFormat = NumberFormat.DECIMAL_1,
            onSiteChangeClick = {},
            onCartridgeChangeClick = {},
            onInsulinChange = {},
            onInsulinSelect = {},
            onNotesChange = {},
            onTimeOffsetChange = {},
            onDateClick = {},
            onTimeClick = {},
            onSettingsClick = null,
            onNavigateBack = {},
            onConfirmClick = onConfirm,
            onPickSiteLocation = {}
        )
    }

    @Test
    fun cannulaOkNeedsAnAction() {
        show { Cannula(FillDialogUiState()) }
        compose.onNodeWithText(ctx.getString(UiR.string.record_pump_site_change)).assertIsDisplayed()
        compose.onNodeWithContentDescription(confirm).assertIsNotEnabled()
    }

    @Test
    fun cannulaWithSiteChangeCanBeConfirmed() {
        var confirmed = 0
        show { Cannula(FillDialogUiState(siteChange = true), onConfirm = { confirmed++ }) }
        compose.onNodeWithContentDescription(confirm).assertIsEnabled().performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun aPatchPumpHasNoPrimeHere() {
        show { Cannula(FillDialogUiState(primeAvailable = false)) }
        compose.onNodeWithText(ctx.getString(UiR.string.fill_prime_amount)).assertDoesNotExist()
    }

    // endregion

    // region basal

    @Composable
    private fun Basal(uiState: TempBasalDialogUiState, onConfirm: () -> Unit = {}) {
        GlassBasalDetailContent(
            uiState = uiState,
            isDark = true,
            onBasalPercentChange = {},
            onBasalAbsoluteChange = {},
            onDurationChange = {},
            onNavigateBack = {},
            onConfirmClick = onConfirm
        )
    }

    @Test
    fun basalOkIsDisabledWithoutAChange() {
        show { Basal(TempBasalDialogUiState(basalPercent = 100.0, durationMinutes = 0.0)) }
        compose.onNodeWithContentDescription(confirm).assertIsNotEnabled()
    }

    @Test
    fun basalOkIsDisabledWhileAPrepareRuns() {
        show { Basal(TempBasalDialogUiState(basalPercent = 150.0, durationMinutes = 60.0, isPreparing = true)) }
        compose.onNodeWithContentDescription(confirm).assertIsNotEnabled()
    }

    @Test
    fun basalOkAsksTheViewModel() {
        var confirmed = 0
        show { Basal(TempBasalDialogUiState(basalPercent = 150.0, durationMinutes = 60.0), onConfirm = { confirmed++ }) }
        compose.onNodeWithContentDescription(confirm).assertIsEnabled().performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    // endregion

    // region sensor insert

    @Test
    fun sensorInsertRendersAndConfirms() {
        var confirmed = 0
        show {
            GlassSensorInsertDetailContent(
                uiState = CareDialogUiState(eventType = CareportalEventType.SENSOR_INSERT),
                isDark = true,
                dateString = "2024-01-01",
                timeString = "12:00",
                onNotesChange = {},
                onTimeOffsetChange = {},
                onNavigateBack = {},
                onConfirmClick = { confirmed++ },
                onDateClick = {},
                onTimeClick = {},
                onPickSiteLocation = {}
            )
        }
        compose.onNodeWithText(ctx.getString(CoreUiR.string.cgm_sensor_insert)).assertIsDisplayed()
        compose.onNodeWithContentDescription(confirm).performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    // endregion

    // region target

    private val dateUtil: DateUtil = mock<DateUtil>().apply { whenever(now()).thenReturn(1_700_000_000_000L) }
    private val profileUtil: ProfileUtil = mock<ProfileUtil>().apply {
        whenever(fromMgdlToStringInUnits(anyOrNull(), anyOrNull())).thenReturn("120 mg/dL")
        whenever(units).thenReturn(GlucoseUnit.MGDL)
    }

    private val preset = TTPreset(id = "p1", name = "Eating", reason = TT.Reason.EATING_SOON, targetValue = 90.0, duration = 45 * 60_000L)

    private fun showTarget(editingEnabled: Boolean, onActivate: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDateUtil provides dateUtil, LocalProfileUtil provides profileUtil) {
                    GlassTargetDetailContent(
                        uiState = TempTargetManagementUiState(presets = listOf(preset), isLoading = false),
                        units = GlucoseUnit.MGDL,
                        isDark = true,
                        editingEnabled = editingEnabled,
                        onCardShown = { _, _ -> },
                        onExpired = {},
                        onActivate = onActivate,
                        onCancelActive = {},
                        onNavigateBack = {}
                    )
                }
            }
        }
    }

    @Test
    fun targetActivateAsksTheViewModel() {
        var activated = 0
        showTarget(editingEnabled = true, onActivate = { activated++ })
        compose.onNodeWithContentDescription(ctx.getString(UiR.string.activate_label)).performClick()
        assertThat(activated).isEqualTo(1)
    }

    @Test
    fun targetActionsAreHiddenWhenTheMasterCannotBeReached() {
        showTarget(editingEnabled = false)
        compose.onNodeWithContentDescription(ctx.getString(UiR.string.activate_label)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.master_offline_banner)).assertIsDisplayed()
    }

    // endregion

    // region loop

    @Test
    fun loopButtonSendsTheActionOfTheNormalScreen() {
        val actions = mutableListOf<PendingRunningModeAction>()
        show {
            GlassLoopDetailContent(
                state = RunningModeManagementUiState(
                    currentMode = RM.Mode.CLOSED_LOOP,
                    allowedNextModes = listOf(RM.Mode.OPEN_LOOP, RM.Mode.SUSPENDED_BY_USER),
                    isLoading = false
                ),
                isDark = true,
                onAction = { actions += it },
                onNavigateBack = {}
            )
        }
        compose.onNodeWithText(ctx.getString(CoreUiR.string.openloop)).performClick()
        compose.onNodeWithText(ctx.getString(UiR.string.duration1h)).performClick()
        assertThat(actions).containsExactly(
            PendingRunningModeAction(RM.Mode.OPEN_LOOP, Action.OPEN_LOOP_MODE, 0),
            PendingRunningModeAction(RM.Mode.SUSPENDED_BY_USER, Action.SUSPEND, 60)
        ).inOrder()
    }

    @Test
    fun loopWithoutProfileSaysSo() {
        show {
            GlassLoopDetailContent(
                state = RunningModeManagementUiState(profileSet = false, isLoading = false),
                isDark = false,
                onAction = {},
                onNavigateBack = {}
            )
        }
        compose.onNodeWithText(ctx.getString(CoreUiR.string.no_profile_set)).assertIsDisplayed()
    }

    // endregion
}
