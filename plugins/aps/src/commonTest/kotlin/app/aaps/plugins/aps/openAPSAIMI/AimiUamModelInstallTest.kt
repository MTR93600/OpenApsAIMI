package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.AimiUamModelInstall.Outcome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shipped UAM model reaches the file the predictors read, and is not rewritten for nothing. */
class AimiUamModelInstallTest {

    private val shipped = byteArrayOf(1, 2, 3, 4)
    private var file: ByteArray? = null
    private var writes = 0

    private fun install(bundled: ByteArray? = shipped, read: () -> ByteArray? = { file }) =
        AimiUamModelInstall.install(bundled, read) { file = it; writes++ }

    @Test
    fun aPhoneWithoutTheFileGetsIt() {
        assertEquals(Outcome.INSTALLED, install())
        assertContentEquals(shipped, file)
    }

    @Test
    fun theSameFileIsNotWrittenAgain() {
        file = shipped.copyOf()
        assertEquals(Outcome.ALREADY_CURRENT, install())
        assertEquals(0, writes)
    }

    @Test
    fun anOldModelIsReplaced() {
        file = byteArrayOf(9, 9)
        assertEquals(Outcome.INSTALLED, install())
        assertContentEquals(shipped, file)
    }

    @Test
    fun aFileTheAppCannotReadIsWrittenOver() {
        assertEquals(Outcome.INSTALLED, install(read = { error("EACCES") }))
        assertContentEquals(shipped, file)
    }

    @Test
    fun aFailedWriteIsReportedAndNotThrown() {
        assertEquals(Outcome.FAILED, AimiUamModelInstall.install(shipped, { null }) { error("read-only") })
    }

    @Test
    fun aBuildWithoutAModelWritesNothing() {
        assertEquals(Outcome.NOT_BUNDLED, install(bundled = null))
        assertEquals(Outcome.NOT_BUNDLED, install(bundled = ByteArray(0)))
        assertNull(file)
    }
}
