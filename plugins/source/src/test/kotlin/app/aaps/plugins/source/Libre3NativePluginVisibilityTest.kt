package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Libre 3 native is offered like any other BG source: it appears in a plugin list on its own, with
 * nothing to install in the AAPS directory first.
 *
 * It used to be hidden behind a marker file named `engineering_libre3` in the `extra` directory.
 * These tests pin the removal of that gate, so nothing quietly reintroduces a condition on
 * `showInList` — which is what
 * [app.aaps.core.interfaces.plugin.ActivePlugin.getSpecificPluginsVisibleInList] filters on, and so
 * what Config Builder, the Setup Wizard, search and Quick Launch all inherit.
 *
 * What still protects the user is the driver choice, not the visibility: `Libre3CgmDrivers.default()`
 * hands out the stub until the engineering switch `Libre3BooleanKey.UseRealSkeleton` is on, and that
 * switch is off by default.
 */
class Libre3NativePluginVisibilityTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var fileListProvider: FileListProvider

    private lateinit var plugin: Libre3NativePlugin

    @BeforeEach
    fun setup() {
        plugin = Libre3NativePlugin(rh, aapsLogger, preferences, config, context, persistenceLayer)
    }

    @Test
    fun `1 - Libre 3 native is visible with nothing installed in the AAPS directory`() {
        assertThat(plugin.showInList(PluginType.BGSOURCE)).isTrue()
    }

    @Test
    fun `2 - visibility does not depend on the AAPS directory being selected`() {
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn(null)

        assertThat(plugin.showInList(PluginType.BGSOURCE)).isTrue()
    }

    @Test
    fun `3 - the plugin never reaches for the AAPS directory to decide it is visible`() {
        plugin.showInList(PluginType.BGSOURCE)

        verifyNoInteractions(fileListProvider)
    }
}
