package app.aaps.plugins.source

import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.Libre3AvailabilityProvider.Companion.CACHE_TTL_MS
import app.aaps.plugins.source.Libre3AvailabilityProvider.Companion.LIBRE3_ACCESS_FILE_NAME
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Availability matrix for the Libre 3 engineering gate, see [Libre3AvailabilityProvider].
 *
 * Storage is used only through the injected [FileListProvider] and mocked [DocumentFile]s, so no
 * real Android storage is touched.
 */
class Libre3AvailabilityProviderTest : TestBase() {

    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var dateUtil: DateUtil

    private lateinit var provider: Libre3AvailabilityProvider

    private val extraDir: DocumentFile = mock()
    private val markerFile: DocumentFile = mock()

    private var now = 1_000_000L

    @BeforeEach
    fun setup() {
        whenever(dateUtil.now()).thenAnswer { now }
        // Happy path: directory selected, grant held, extra dir resolves, marker present.
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("content://tree/AAPS")
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(true)
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(extraDir)
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(markerFile)
        provider = Libre3AvailabilityProvider(aapsLogger, Provider { fileListProvider }, preferences, notificationManager, dateUtil)
    }

    /**
     * Counts `post(LIBRE3_DIR_ACCESS_LOST, …)` calls straight from the mock's invocation log.
     * `post` has three overloads, one of them with a `vararg`, which makes plain matchers hard to
     * read here. The recorded invocations are clear.
     */
    private fun accessLostPostCount(): Int =
        Mockito.mockingDetails(notificationManager).invocations
            .filter { it.method.name == "post" }
            .count { it.arguments.firstOrNull() == NotificationId.LIBRE3_DIR_ACCESS_LOST }

    private fun verifyAccessLostPosted(times: Int) = assertThat(accessLostPostCount()).isEqualTo(times)

    @Test
    fun `1 - directory reachable and marker present is Available`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.Available)
    }

    @Test
    fun `2 - a near-miss file name does not count`() {
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)
        whenever(extraDir.findFile("engineering_libre3.txt")).thenReturn(markerFile)
        whenever(extraDir.findFile("engineering_libre2")).thenReturn(markerFile)
        whenever(extraDir.findFile("engineering_mode")).thenReturn(markerFile)

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.MarkerFileMissing)
    }

    @Test
    fun `3 - marker file absent is MarkerFileMissing and stays silent`() {
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.MarkerFileMissing)
        verifyAccessLostPosted(0)
        verify(notificationManager, never()).dismiss(NotificationId.LIBRE3_DIR_ACCESS_LOST)
    }

    @Test
    fun `4 - no AAPS directory URI is AapsFolderUnavailable`() {
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.AapsFolderUnavailable)
    }

    @Test
    fun `5 - a revoked folder grant is FolderPermissionMissing`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.FolderPermissionMissing)
    }

    @Test
    fun `6 - SecurityException during access is FolderPermissionMissing`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(SecurityException("no saved grant"))

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.FolderPermissionMissing)
    }

    @Test
    fun `7 - an unresolvable AAPS directory is a TechnicalError, never Available`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(null)

        val result = provider.evaluateAvailability()

        assertThat(result).isInstanceOf(Libre3Availability.TechnicalError::class.java)
        assertThat(provider.isAvailable()).isFalse()
    }

    @Test
    fun `8 - an unexpected failure is a TechnicalError, never Available`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(IllegalStateException("boom"))

        val result = provider.evaluateAvailability()

        assertThat(result).isInstanceOf(Libre3Availability.TechnicalError::class.java)
        assertThat((result as Libre3Availability.TechnicalError).reason).isEqualTo("IllegalStateException")
        assertThat(provider.isAvailable()).isFalse()
    }

    @Test
    fun `9 - real access loss posts the restore access notification once`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        repeat(3) { provider.invalidate() }

        verifyAccessLostPosted(1)
    }

    @Test
    fun `10 - restoring then losing access notifies again`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)
        provider.invalidate()
        verifyAccessLostPosted(1)

        // Access is back, so the old notification is cleared and the latch is armed again.
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(true)
        provider.invalidate()
        verify(notificationManager).dismiss(NotificationId.LIBRE3_DIR_ACCESS_LOST)

        clearInvocations(notificationManager)
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)
        provider.invalidate()
        verifyAccessLostPosted(1)
    }

    @Test
    fun `11 - the marker file content is never touched`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.Available)

        verifyNoInteractions(markerFile)
    }

    @Test
    fun `12 - only the extra directory is searched, and only by exact name`() {
        provider.evaluateAvailability()

        verify(extraDir).findFile(LIBRE3_ACCESS_FILE_NAME)
        verify(fileListProvider, never()).ensurePreferenceDirExists()
        verify(fileListProvider, never()).ensureExportDirExists()
        verify(fileListProvider, never()).ensureTempDirExists()
    }

    @Test
    fun `13 - a cached answer is reused inside the TTL and checked again after it`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.Available)

        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)
        now += CACHE_TTL_MS - 1
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.Available)

        now += 2
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.MarkerFileMissing)
    }

    @Test
    fun `14 - changing the AAPS directory checks again at once`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.Available)

        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("content://tree/OTHER")
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(Libre3Availability.MarkerFileMissing)
    }
}
