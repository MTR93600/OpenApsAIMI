package app.aaps.plugins.source

import android.content.Context
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Starting the keep-alive service must not crash the sensor session when the platform refuses it.
 *
 * Reference `Libre3SessionService.start` on `dev_OAPSAIMI` @ `3dd0ca64772` wraps
 * `startForegroundService` / `startService` in `runCatching` and does not rethrow.
 */
class Libre3SessionServiceStartTest : TestBase() {

    @Mock lateinit var context: Context

    @Test
    fun `start does not throw when the platform refuses the service`() {
        whenever(context.startService(any())).thenThrow(SecurityException("blocked"))
        whenever(context.startForegroundService(any())).thenThrow(SecurityException("blocked"))

        Libre3SessionService.start(context)
    }
}
