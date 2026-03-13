package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiLoggerTest {

    private lateinit var aapsLogger: AAPSLogger
    private lateinit var aimiLogger: AimiLogger

    @BeforeEach
    fun setup() {
        aapsLogger = mockk(relaxed = true)
        aimiLogger = AimiLogger(aapsLogger)
    }

    @Test
    fun `test structured info logging with metadata`() {
        aimiLogger.info {
            context = "advisor"
            operation = "test"
            message = "Hello World"
            tag("key1", "val1")
            tag("key2", 42)
        }

        verify {
            aapsLogger.info(
                LTag.APS,
                match { it.contains("🤖 [advisor:test] Hello World {key1=val1, key2=42}") }
            )
        }
    }

    @Test
    fun `test performance measurement logging`() {
        val result = aimiLogger.measure("SlowOperation") {
            Thread.sleep(10)
            "done"
        }

        assert(result == "done")
        verify {
            aapsLogger.info(
                LTag.APS,
                match { it.contains("🤖 [performance:SlowOperation] Execution completed {duration_ms=") }
            )
        }
    }
}
