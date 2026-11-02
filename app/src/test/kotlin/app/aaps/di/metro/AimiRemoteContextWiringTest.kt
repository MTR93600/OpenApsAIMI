package app.aaps.di.metro

import app.aaps.core.interfaces.aps.AimiRemoteContext
import app.aaps.plugins.aps.openAPSAIMI.context.AimiRemoteContextImpl
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `NsIncomingDataProcessor` takes the AIMI context system as an optional parameter, because the
 * desktop client has no AIMI. An optional parameter fails quietly: if Metro used the default here,
 * the master would drop every context the AIMI Remote Control sends, and no test would notice. So
 * the Android graph is asked directly.
 */
class AimiRemoteContextWiringTest {

    @Test
    fun `the master applies AIMI contexts through the real context system`() {
        val processor = testRoot().nsIncomingDataProcessor
        val field = processor.javaClass.getDeclaredField("aimiRemoteContext").apply { isAccessible = true }
        val sink = field.get(processor) as AimiRemoteContext?
        assertThat(sink).isInstanceOf(AimiRemoteContextImpl::class.java)
    }
}
