package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.resources.TextRefValueRegistry
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.ui.UiStringsValues
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassTrainingStringResourcesTest {

    @AfterTest
    fun clearRegistry() {
        TextRefValueRegistry.clear()
    }

    @Test
    fun trainingAndModelLabelsResolveThroughTheUiOwnerForIos() {
        TextRefValueRegistry.register("ui", UiStringsValues::textOf)

        assertEquals(
            "ML Training",
            TextRefValueRegistry.textOf(TextRef.Named("ui", "dashboard_glass_loop_ml_training_title")),
        )
        assertEquals(
            "Model rejected by a quality check",
            TextRefValueRegistry.textOf(TextRef.Named("ui", "dashboard_glass_loop_ml_smb_outcome_rejected")),
        )
        assertEquals(
            "Not created yet",
            TextRefValueRegistry.textOf(TextRef.Named("ui", "dashboard_glass_loop_ml_file_missing")),
        )
    }
}
