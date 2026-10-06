package app.aaps.plugins.aps.openAPSAIMI.ml

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bytes embedded for both platforms are the file from commit 64e630c7fc. */
class UamTfliteModelHashTest {

    @Test
    fun modelBytesMatchTheCommittedSha256() {
        val file = java.io.File("src/tfliteParity/modelUAM.tflite")
        assertEquals(true, file.isFile, file.absolutePath)
        val fileBytes = file.readBytes()
        assertEquals(UamTfliteCorpus.modelBytes.toList(), fileBytes.toList())
        val digest = MessageDigest.getInstance("SHA-256").digest(fileBytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals(UamTfliteCorpus.MODEL_SHA256, hex)
        assertEquals(4504, fileBytes.size)
    }
}
