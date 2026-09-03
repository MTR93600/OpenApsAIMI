package app.aaps.plugins.source

import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.DexcomOnePlusAvailabilityProvider.Companion.CACHE_TTL_MS
import app.aaps.plugins.source.DexcomOnePlusAvailabilityProvider.Companion.ONE_PLUS_ACCESS_FILE_NAME
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
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
 * Availability matrix for the Dexcom ONE+ engineering gate (see [DexcomOnePlusAvailabilityProvider]).
 *
 * Storage is exercised entirely through the injected [FileListProvider] abstraction and mocked
 * [DocumentFile]s — no real Android storage, no SAF.
 */
class DexcomOnePlusAvailabilityProviderTest : TestBase() {

    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var dateUtil: DateUtil

    private lateinit var provider: DexcomOnePlusAvailabilityProvider

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
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(markerFile)
        provider = DexcomOnePlusAvailabilityProvider(aapsLogger, { fileListProvider }, preferences, notificationManager, dateUtil)
    }

    /**
     * Counts `post(DEXCOM_ONEPLUS_DIR_ACCESS_LOST, …)` calls straight off the mock's invocation
     * log. `post` has three overloads, one of them with a `vararg formatArgs`, which makes plain
     * `verify(...)` matchers unreadable here; the recorded invocations are unambiguous.
     */
    private fun accessLostPostCount(): Int =
        Mockito.mockingDetails(notificationManager).invocations
            .filter { it.method.name == "post" }
            .count { it.arguments.firstOrNull() == NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST }

    private fun verifyAccessLostPosted(times: Int) = assertThat(accessLostPostCount()).isEqualTo(times)

    // ---- 1..10: availability matrix ----

    @Test
    fun `1 - directory reachable and marker present is Available`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)
    }

    @Test
    fun `2 - an empty marker file is still Available`() {
        // Emptiness is unobservable to the provider: it only asks whether the entry exists.
        whenever(markerFile.length()).thenReturn(0L)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)
    }

    @Test
    fun `3 - a near-miss file name does not count`() {
        // Only the exact name is looked up; anything else is simply not found.
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)
        whenever(extraDir.findFile("engineering_oneplus.txt")).thenReturn(markerFile)
        whenever(extraDir.findFile("engineering_oneplus2")).thenReturn(markerFile)
        whenever(extraDir.findFile("engineering_mode")).thenReturn(markerFile)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.MarkerFileMissing)
    }

    @Test
    fun `4 - marker file absent is MarkerFileMissing`() {
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.MarkerFileMissing)
    }

    @Test
    fun `5 - extra subdirectory absent resolves to no marker, not an error`() {
        // ensureExtraDirExists creates `extra` when missing (existing project behaviour), so a
        // freshly created, empty directory reports the marker as absent.
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.MarkerFileMissing)
        verifyAccessLostPosted(0)
    }

    @Test
    fun `6 - no AAPS directory URI is AapsFolderUnavailable`() {
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.AapsFolderUnavailable)
    }

    @Test
    fun `7 - revoked SAF grant is FolderPermissionMissing`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.FolderPermissionMissing)
    }

    @Test
    fun `8 - SecurityException during access is FolderPermissionMissing`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(SecurityException("no persisted grant"))

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.FolderPermissionMissing)
    }

    @Test
    fun `9 - unresolvable AAPS directory is a TechnicalError, never Available`() {
        // URI stored and grant reported, but the tree cannot be opened.
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(null)

        val result = provider.evaluateAvailability()

        assertThat(result).isInstanceOf(DexcomOnePlusAvailability.TechnicalError::class.java)
        assertThat(provider.isAvailable()).isFalse()
    }

    @Test
    fun `10 - unexpected failure is a TechnicalError, never Available`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(IllegalStateException("boom"))

        val result = provider.evaluateAvailability()

        assertThat(result).isInstanceOf(DexcomOnePlusAvailability.TechnicalError::class.java)
        assertThat((result as DexcomOnePlusAvailability.TechnicalError).reason).isEqualTo("IllegalStateException")
        assertThat(provider.isAvailable()).isFalse()
    }

    // ---- 16..18: notification behaviour ----

    @Test
    fun `16 - real access loss posts the restore-access notification`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        provider.evaluateAvailability()

        verifyAccessLostPosted(1)
    }

    @Test
    fun `17 - a missing marker file posts no permission notification`() {
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        provider.evaluateAvailability()

        verifyAccessLostPosted(0)
        verify(notificationManager, never()).dismiss(NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST)
    }

    @Test
    fun `18 - the notification is posted once, not on every check`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        repeat(3) {
            provider.invalidate() // force a fresh evaluation each time, bypassing the cache
        }

        verifyAccessLostPosted(1)
    }

    @Test
    fun `18b - restoring then losing access notifies again`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)
        provider.invalidate()
        verifyAccessLostPosted(1)

        // Access restored → the stale notification is cleared and the latch re-arms.
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(true)
        provider.invalidate()
        verify(notificationManager).dismiss(NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST)

        clearInvocations(notificationManager)
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)
        provider.invalidate()
        verifyAccessLostPosted(1)
    }

    // ---- 19: the marker file is never opened ----

    @Test
    fun `19 - the marker file content is never touched`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)

        // Presence was established purely from findFile; nothing was asked of the file itself.
        verifyNoInteractions(markerFile)
    }

    @Test
    fun `19b - only the extra directory is searched, and only by exact name`() {
        provider.evaluateAvailability()

        verify(extraDir).findFile(ONE_PLUS_ACCESS_FILE_NAME)
        verify(fileListProvider, never()).ensurePreferenceDirExists()
        verify(fileListProvider, never()).ensureExportDirExists()
        verify(fileListProvider, never()).ensureTempDirExists()
    }

    // ---- caching / re-evaluation ----

    @Test
    fun `cached answer is reused within the TTL and re-evaluated after it`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)

        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)
        now += CACHE_TTL_MS - 1
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)

        now += 2
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.MarkerFileMissing)
    }

    @Test
    fun `changing the AAPS directory re-evaluates immediately`() {
        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.Available)

        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("content://tree/OTHER")
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(provider.evaluateAvailability()).isEqualTo(DexcomOnePlusAvailability.MarkerFileMissing)
    }
}
