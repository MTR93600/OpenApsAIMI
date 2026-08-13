package app.aaps.ui.compose.overview.statusLights

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric composable test for [StatusSectionContent]: renders sensor/insulin/cannula/battery status items. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatusSectionContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersAllStatusItems() {
        compose.setContent {
            MaterialTheme {
                StatusSectionContent(
                    sensorStatus = StatusItem(
                        label = "Sensor", age = "5d 12h", ageStatus = StatusLevel.NORMAL, agePercent = 0.55f,
                        level = "Signal OK", levelStatus = StatusLevel.NORMAL, levelPercent = 0.2f, icon = IcCgmInsert
                    ),
                    insulinStatus = StatusItem(
                        label = "Insulin", age = "2d 3h", ageStatus = StatusLevel.WARNING, agePercent = 0.75f,
                        level = "86 U", levelStatus = StatusLevel.NORMAL, levelPercent = -1f, icon = IcPumpCartridge
                    ),
                    cannulaStatus = StatusItem(
                        label = "Cannula", age = "1d 18h", ageStatus = StatusLevel.NORMAL, agePercent = 0.6f, icon = IcCannulaChange
                    ),
                    batteryStatus = StatusItem(
                        label = "Battery", age = "14d", ageStatus = StatusLevel.CRITICAL, agePercent = 0.95f,
                        level = "12%", levelStatus = StatusLevel.CRITICAL, levelPercent = 0.88f, icon = IcPumpBattery
                    ),
                    onSensorInsertClick = {},
                    onFillClick = {},
                    onInsulinChangeClick = {},
                    onBatteryChangeClick = {}
                )
            }
        }
        compose.onNodeWithText("5d 12h").assertIsDisplayed()
        compose.onNodeWithText("86 U").assertIsDisplayed()
        compose.onNodeWithText("12%").assertIsDisplayed()
    }

    /** The two transient CGM rows show up next to the sensor they belong to. */
    @Test
    fun rendersWarmUpAndSecondSensorRows() {
        compose.setContent {
            MaterialTheme {
                StatusSectionContent(
                    sensorStatus = StatusItem(
                        label = "Sensor", age = "9d 4h", ageStatus = StatusLevel.WARNING, agePercent = 0.9f, icon = IcCgmInsert
                    ),
                    insulinStatus = null,
                    cannulaStatus = null,
                    batteryStatus = null,
                    warmUpStatus = StatusItem(
                        label = "Warm-up", age = "23 min", agePercent = 0.24f, icon = IcCgmInsert, compactLevel = false
                    ),
                    secondSensorStatus = StatusItem(
                        label = "New sensor", age = "ready in 8 h", agePercent = 0.33f, level = "12 readings",
                        icon = IcGenericCgm, compactLevel = false
                    )
                )
            }
        }
        compose.onNodeWithText("23 min").assertIsDisplayed()
        compose.onNodeWithText("ready in 8 h").assertIsDisplayed()
        compose.onNodeWithText("12 readings").assertIsDisplayed()
    }

    /** The switch button appears on the second-sensor row only when a callback is given. */
    @Test
    fun showsSwitchButtonOnlyWhenPromotionIsAllowed() {
        var promoted = false
        compose.setContent {
            MaterialTheme {
                StatusSectionContent(
                    sensorStatus = null,
                    insulinStatus = null,
                    cannulaStatus = null,
                    batteryStatus = null,
                    secondSensorStatus = StatusItem(
                        label = "New sensor", age = "Ready to switch", ageStatus = StatusLevel.NORMAL,
                        level = "72 readings", icon = IcGenericCgm, compactLevel = false
                    ),
                    onPromoteSecondSensorClick = { promoted = true }
                )
            }
        }
        compose.onNodeWithText("Switch").assertIsDisplayed()
        compose.onNodeWithText("Switch").performClick()
        assertThat(promoted).isTrue()
    }
}
