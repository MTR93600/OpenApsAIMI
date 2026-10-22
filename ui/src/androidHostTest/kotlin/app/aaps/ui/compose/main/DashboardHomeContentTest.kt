package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardHomeContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appliesTheShellContentPaddingToSkinContent() {
        compose.setContent {
            Box(Modifier.size(100.dp)) {
                DashboardHomeContent(
                    contentPadding = PaddingValues(top = 20.dp, bottom = 10.dp),
                ) {
                    Box(Modifier.fillMaxSize().testTag("skin-content"))
                }
            }
        }

        val bounds = compose.onNodeWithTag("skin-content").getBoundsInRoot()
        assertThat(bounds.top).isEqualTo(20.dp)
        assertThat(bounds.bottom).isEqualTo(90.dp)
    }
}
