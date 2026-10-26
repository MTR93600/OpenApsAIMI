package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.AimiUamHandler
import app.aaps.plugins.aps.openAPSAIMI.AimiUamInference
import app.aaps.plugins.aps.openAPSAIMI.aimiPlatformUamPredictor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The iPhone UAM path end to end: the model file in `Documents/AAPS/ml`, the predictor the plugin
 * installs, and the dose the engine gets. Expected doses come from the Android interpreter bits, so
 * this proves the iPhone doses what Android doses for the same inputs.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUamPredictorTest {

    private object StubText : TextResolver {
        override fun gs(ref: TextRef): String = "text"
        override fun gs(ref: TextRef, vararg args: Any?): String = "text"
        override fun gsNotLocalised(ref: TextRef): String = "text"
    }

    @Test
    fun theInstalledPredictorIsTheIosOne() {
        assertSame<AimiUamHandler.AimiUamPredictor?>(IosUamPredictor, aimiPlatformUamPredictor())
    }

    @Test
    fun theDoseMatchesTheAndroidInterpreterOnEveryVector() {
        writeModel(UamTfliteCorpus.modelBytes)
        val mismatches = ArrayList<String>()
        for (index in 0 until UamTfliteCorpus.VECTOR_COUNT) {
            val expected = AimiUamInference.doseFromRaw(Float.fromBits(UamTfliteCorpus.androidBits(index)))
            val actual = IosUamPredictor.predict(UamTfliteCorpus.inputFloats(index), StringBuilder(), StubText)
            if (actual != expected) mismatches += "$index android=$expected ios=$actual"
        }
        assertEquals(0, mismatches.size, mismatches.joinToString("\n"))
    }

    @Test
    fun aWrongNumberOfInputsGivesNoSmb() {
        writeModel(UamTfliteCorpus.modelBytes)
        assertEquals(0f, IosUamPredictor.predict(FloatArray(3), null, StubText))
    }

    private fun writeModel(bytes: ByteArray) {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val directory = "$documents/AAPS/ml"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile("$documents/${AimiUamInference.MODEL_RELATIVE_PATH}", atomically = true)
    }
}
