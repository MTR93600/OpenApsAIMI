package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectIqRegistryTest {

    @Test
    fun theInstalledCompanionIsTheOneThePluginWillUse() {
        val fake = FakeCompanion()
        installConnectIqCompanion(fake)
        try {
            assertEquals(fake, ConnectIqRegistry.companion)
            assertFalse(fake.opened)
            assertTrue(fake.handleOpenUrl("aaps-ciq://devices"))
            assertEquals("aaps-ciq://devices", fake.openedUrl)
            assertFalse(fake.handleOpenUrl("https://example.com"))
        } finally {
            ConnectIqRegistry.companion = null
        }
    }

    private class FakeCompanion : ConnectIqCompanion {
        var opened = false
        var openedUrl: String? = null

        override fun start(callback: ConnectIqCallback, appIdsCsv: String) = Unit

        override fun showDeviceSelection() = Unit

        override fun handleOpenUrl(url: String): Boolean {
            opened = true
            openedUrl = url
            return url.startsWith("aaps-ciq")
        }

        override fun restoreDevices(encoded: String) = Unit

        override fun send(
            deviceUuid: String,
            modelName: String,
            friendlyName: String,
            partNumber: String,
            appId: String,
            payloadJson: String,
            token: String,
        ) = Unit

        override fun stop() {
            stopped = true
        }

        var stopped = false
    }
}
