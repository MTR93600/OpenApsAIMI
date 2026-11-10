package app.aaps.implementation.maintenance

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.StopsWithAaps
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

internal class AapsStopImplTest {

    /** The stored key, so the test reads back what the class wrote. */
    private var stored = false

    private val preferences: Preferences = mock {
        on { get(BooleanNonKey.AapsStoppedByUser) } doAnswer { stored }
        on { put(eq(BooleanNonKey.AapsStoppedByUser), any<Boolean>()) } doAnswer { stored = it.getArgument(1); Unit }
    }
    private val aapsLogger: AAPSLogger = mock()
    private val notificationManager: NotificationManager = mock()
    private val uel: UserEntryLogger = mock()
    private val commandQueue: CommandQueue = mock()
    private val activePlugin: ActivePlugin = mock()

    /** A sensor plugin: it holds its own radio link. */
    private val sensor: PluginBase = mock(extraInterfaces = arrayOf(StopsWithAaps::class))

    /** A plugin with no link of its own. */
    private val other: PluginBase = mock()

    private fun sut() = AapsStopImpl(
        aapsLogger, preferences, generatedTextResolver(), notificationManager, uel,
        { activePlugin }, { commandQueue }, CoroutineScope(Dispatchers.Unconfined)
    )

    @BeforeEach
    fun setUp() {
        whenever(activePlugin.getPluginsList()).thenReturn(arrayListOf(sensor, other))
        whenever(sensor.isEnabled()).thenReturn(true)
        whenever(other.isEnabled()).thenReturn(true)
    }

    @Test
    fun `stop is kept, drops the waiting commands and lets the sensor go`(): Unit = runBlocking {
        val sut = sut()

        sut.stop(Sources.Maintenance)

        assertThat(stored).isTrue()
        assertThat(sut.isStopped.value).isTrue()
        verify(commandQueue).cancelAll(CoreUiStrings.aaps_stopped_short, false)
        verify(sensor as StopsWithAaps).onAapsStop()
        verify(notificationManager).post(eq(NotificationId.AAPS_STOPPED), eq(CoreUiStrings.aaps_stopped_notification), any(), any(), any(), any(), anyOrNull(), any(), anyOrNull())
        verify(uel).log(eq(Action.EXIT_AAPS), eq(Sources.Maintenance), any<String>(), any<List<ValueWithUnit>>())
    }

    @Test
    fun `start again takes the sensor back and reaches the pump`(): Unit = runBlocking {
        val sut = sut()
        sut.stop(Sources.Maintenance)

        sut.resume(Sources.Maintenance)

        assertThat(stored).isFalse()
        assertThat(sut.isStopped.value).isFalse()
        verify(sensor as StopsWithAaps).onAapsResume()
        verify(notificationManager).dismiss(NotificationId.AAPS_STOPPED)
        verify(commandQueue).readStatus(any())
    }

    @Test
    fun `a switched off sensor plugin is left alone`(): Unit = runBlocking {
        whenever(sensor.isEnabled()).thenReturn(false)

        sut().stop(Sources.Maintenance)

        verify(sensor as StopsWithAaps, never()).onAapsStop()
    }

    @Test
    fun `after a restart a stopped phone is still stopped and shows it`() {
        stored = true

        val sut = sut()

        assertThat(sut.isStopped.value).isTrue()
        verify(notificationManager).post(eq(NotificationId.AAPS_STOPPED), eq(CoreUiStrings.aaps_stopped_notification), any(), any(), any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `stopping twice does the work once`(): Unit = runBlocking {
        val sut = sut()

        sut.stop(Sources.Maintenance)
        sut.stop(Sources.Maintenance)

        verify(commandQueue).cancelAll(CoreUiStrings.aaps_stopped_short, false)
    }
}
