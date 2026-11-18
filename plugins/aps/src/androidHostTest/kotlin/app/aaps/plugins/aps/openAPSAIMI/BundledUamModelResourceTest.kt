package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * The build puts the UAM model in the Android Java resources, and it is the very file the parity tests
 * check. Without this resource the install has nothing to write and the model answers 0 again.
 */
class BundledUamModelResourceTest {

    @Test
    fun `the shipped model is the parity model`() {
        val shipped = AimiModelHandler::class.java.getResourceAsStream(BUNDLED_UAM_MODEL_RESOURCE)?.use { it.readBytes() }
        assertNotNull(shipped, "no $BUNDLED_UAM_MODEL_RESOURCE in the Android resources")
        assertContentEquals(File("src/tfliteParity/modelUAM.tflite").readBytes(), shipped)
    }
}
