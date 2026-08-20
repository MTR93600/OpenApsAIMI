package app.aaps.plugins.source

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.Libre3AvailabilityProvider.Companion.LIBRE3_ACCESS_FILE_NAME
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The engineering gate as the plugin framework sees it: Libre 3 native appears in a plugin list,
 * that is Config Builder, Setup Wizard, search and Quick Launch, which all read
 * `ActivePlugin.getSpecificPluginsVisibleInList`, so `PluginBase.showInList`, only when the marker
 * file is really there.
 */
class Libre3NativePluginVisibilityTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var dateUtil: DateUtil

    private val extraDir: DocumentFile = mock()
    private val markerFile: DocumentFile = mock()

    private lateinit var plugin: Libre3NativePlugin

    @BeforeEach
    fun setup() {
        whenever(dateUtil.now()).thenReturn(1_000_000L)
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("content://tree/AAPS")
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(true)
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(extraDir)
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(markerFile)
        val availabilityProvider =
            Libre3AvailabilityProvider(aapsLogger, Lazy { fileListProvider }, preferences, notificationManager, dateUtil)
        plugin = Libre3NativePlugin(rh, aapsLogger, preferences, config, context, persistenceLayer, availabilityProvider)
    }

    @Test
    fun `1 - Libre 3 native is visible when the exact marker file exists`() {
        assertThat(plugin.showInList(PluginType.BGSOURCE)).isTrue()
    }

    @Test
    fun `2 - Libre 3 native is hidden when the marker file is absent`() {
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `3 - Libre 3 native is hidden when the folder grant is gone`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `4 - Libre 3 native is hidden when the directory throws while being read`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(SecurityException("grant revoked"))

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `5 - Libre 3 native is hidden when no AAPS directory is selected`() {
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `6 - a sibling BG source plugin is untouched by the Libre 3 gate`() {
        val aidex = AidexPlugin(rh, aapsLogger, preferences, config, notificationManager)
        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
        assertThat(aidex.showInList(PluginType.BGSOURCE)).isTrue()
        assertThat(aidex.specialEnableCondition()).isTrue()
    }

    @Test
    fun `7 - the gate does not touch the enable path, so a running sensor is unaffected`() {
        // Hiding the plugin never disables an instance that is already selected, and never starts a
        // BG source fallback.
        assertThat(plugin.specialEnableCondition()).isTrue()

        whenever(extraDir.findFile(LIBRE3_ACCESS_FILE_NAME)).thenReturn(null)
        assertThat(plugin.specialEnableCondition()).isTrue()

        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)
        assertThat(plugin.specialEnableCondition()).isTrue()
    }
}
