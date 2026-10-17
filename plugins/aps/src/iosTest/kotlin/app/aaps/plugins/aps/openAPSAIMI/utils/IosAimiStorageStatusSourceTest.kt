package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStorageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAimiStorageStatusSourceTest {

    @Test
    fun `a writable folder is DOCUMENTS_AAPS with its path`() {
        val (status, path, error) = iosStorageStatus({ "/docs/AAPS" }, { true })
        assertEquals(AimiStorageStatus.DOCUMENTS_AAPS, status)
        assertEquals("/docs/AAPS", path)
        assertNull(error)
    }

    @Test
    fun `no Documents directory is ERROR with the reason`() {
        val (status, path, error) = iosStorageStatus({ error("no Documents directory") }, { true })
        assertEquals(AimiStorageStatus.ERROR, status)
        assertNull(path)
        assertTrue(error!!.contains("no Documents directory"))
    }

    @Test
    fun `a folder that is not writable is ERROR with its path`() {
        val (status, path, error) = iosStorageStatus({ "/docs/AAPS" }, { false })
        assertEquals(AimiStorageStatus.ERROR, status)
        assertEquals("/docs/AAPS", path)
        assertNotNull(error)
    }

    @Test
    fun `the Android only tiers are never returned`() {
        val answers = listOf(
            iosStorageStatus({ "/a" }, { true }).first,
            iosStorageStatus({ "/a" }, { false }).first,
            iosStorageStatus({ error("x") }, { true }).first,
        )
        assertTrue(answers.none { it == AimiStorageStatus.APP_SCOPED_EXTERNAL || it == AimiStorageStatus.INTERNAL_ONLY })
    }
}
