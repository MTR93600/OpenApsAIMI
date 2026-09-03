package app.aaps.plugins.source

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.DexcomOnePlusAvailabilityProvider.Companion.ONE_PLUS_ACCESS_FILE_NAME
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The engineering gate as the plugin framework sees it: ONE+ appears in a plugin list — Config
 * Builder, Setup Wizard, search, Quick Launch all read
 * `ActivePlugin.getSpecificPluginsVisibleInList`, i.e. `PluginBase.showInList` — only when the
 * marker file is confirmed present.
 */
class DexcomOnePlusPluginVisibilityTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var warmupBasalGuard: DexcomOnePlusWarmupBasalGuard
    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var dateUtil: DateUtil

    private val extraDir: DocumentFile = mock()
    private val markerFile: DocumentFile = mock()
    private val bleRadioPriority: BleRadioPriority = mock()

    private lateinit var plugin: DexcomOnePlusPlugin

    @BeforeEach
    fun setup() {
        whenever(dateUtil.now()).thenReturn(1_000_000L)
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("content://tree/AAPS")
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(true)
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(extraDir)
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(markerFile)
        val availabilityProvider =
            DexcomOnePlusAvailabilityProvider(aapsLogger, { fileListProvider }, preferences, notificationManager, dateUtil)
        plugin = DexcomOnePlusPlugin(rh, aapsLogger, preferences, config, context, persistenceLayer, warmupBasalGuard, availabilityProvider, bleRadioPriority)
    }

    @Test
    fun `11 - ONE+ is visible when the exact marker file exists`() {
        assertThat(plugin.showInList(PluginType.BGSOURCE)).isTrue()
    }

    @Test
    fun `12 - ONE+ is hidden when the marker file is absent`() {
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `13 - ONE+ is hidden when the extra directory yields no marker`() {
        // `extra` freshly created by ensureExtraDirExists → empty → no marker.
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `14 - ONE+ is hidden when the SAF grant is gone`() {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `14c - ONE+ is hidden when the directory throws while being read`() {
        whenever(fileListProvider.ensureExtraDirExists()).thenThrow(SecurityException("grant revoked"))

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `14b - ONE+ is hidden when no AAPS directory is selected`() {
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
    }

    @Test
    fun `15 - a sibling BG source plugin is untouched by the ONE+ gate`() {
        val aidex = AidexPlugin(rh, aapsLogger, preferences, config, notificationManager)
        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isFalse()
        assertThat(aidex.showInList(PluginType.BGSOURCE)).isTrue()
        assertThat(aidex.specialEnableCondition()).isTrue()
    }

    @Test
    fun `20 - the gate does not touch the enable path, so a running sensor is unaffected`() {
        // specialEnableCondition stays untouched in every state: hiding ONE+ never disables an
        // already-selected instance, never triggers a BG-source fallback, and changes no
        // insulin/sensor behaviour.
        assertThat(plugin.specialEnableCondition()).isTrue()

        whenever(extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME)).thenReturn(null)
        assertThat(plugin.specialEnableCondition()).isTrue()

        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)
        assertThat(plugin.specialEnableCondition()).isTrue()
    }
}
