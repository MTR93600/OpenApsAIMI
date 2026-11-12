package app.aaps.implementation.notifications

import app.aaps.core.interfaces.ios.IosKeepAwake
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Who wants AAPS awake, and whether the audio category may be set now. No platform type, so a test
 * can run it.
 *
 * While an alarm sounds, the alarm player owns the audio category: it may be `SoloAmbient`, which
 * respects the silent switch as the person chose. This class must not turn it back into `Playback`
 * in the middle of the alarm. Once the alarm stops, the category is taken back.
 */
internal class KeepAwakeState {

    private val owners = mutableSetOf<String>()

    var alarmPlaying: Boolean = false

    val wanted: Boolean get() = owners.isNotEmpty()

    /** Records the wish of [owner]. True when this changed whether AAPS should stay awake. */
    fun set(owner: String, wanted: Boolean): Boolean {
        val before = this.wanted
        if (wanted) owners += owner else owners -= owner
        return before != this.wanted
    }

    /** True when the keep-awake category may be set now. */
    val mayClaimCategory: Boolean get() = wanted && !alarmPlaying
}

/**
 * The iOS [IosKeepAwake]: a loop of silence on an audio session iOS lets run in the background.
 *
 * This is what Spike did so Garmin watch faces could always reach its local server. It needs `audio`
 * in `UIBackgroundModes`. The session uses `Playback` with `MixWithOthers`, so music or a podcast is
 * not stopped, and the silence is never heard.
 *
 * It restarts itself after a phone call (interruption ended), after iOS resets its media services,
 * and whenever the app comes to the front, because each of those can stop the player without telling
 * anyone else. It cannot start while the app is suspended: an owner asking then is served at the next
 * wake.
 *
 * The alarm player tells it when an alarm starts and stops, see [KeepAwakeState].
 *
 * ⚠️ ASYNC IMPACT: every change happens on the main queue. [setWanted] may be called from any thread
 * and only hands the work over.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class IosSilentAudioKeepAwake(
    private val aapsLogger: AAPSLogger,
) : IosKeepAwake {

    // --- main-queue-confined state ---
    private val state = KeepAwakeState()
    private var player: AVAudioPlayer? = null
    private var observing = false

    override fun setWanted(owner: String, wanted: Boolean) = onMain {
        if (!state.set(owner, wanted)) return@onMain
        if (state.wanted) {
            aapsLogger.info(LTag.CORE, "Keep awake on (asked by $owner)")
            observe()
            ensurePlaying("on")
        } else {
            aapsLogger.info(LTag.CORE, "Keep awake off")
            player?.stop()
            player = null
        }
    }

    /** An alarm took the audio session. Called by the alarm player, on the main queue. */
    internal fun onAlarmStarted() {
        state.alarmPlaying = true
    }

    /** The alarm stopped: take the category back and make sure the silence still plays. */
    internal fun onAlarmStopped() {
        state.alarmPlaying = false
        if (state.wanted) ensurePlaying("after alarm")
    }

    private fun ensurePlaying(reason: String) {
        if (!state.mayClaimCategory) return
        claimSession()
        val current = player ?: openSilence()?.also { player = it } ?: return
        if (current.playing) return
        current.numberOfLoops = -1
        current.prepareToPlay()
        if (current.play()) {
            aapsLogger.debug(LTag.CORE, "Keep awake playing ($reason)")
        } else {
            aapsLogger.warn(LTag.CORE, "Keep awake: the silent player refused to start ($reason)")
        }
    }

    private fun claimSession() = memScoped {
        val session = AVAudioSession.sharedInstance()
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!session.setCategory(AVAudioSessionCategoryPlayback, withOptions = AVAudioSessionCategoryOptionMixWithOthers, error = error.ptr)) {
            aapsLogger.warn(LTag.CORE, "Keep awake: cannot set the audio category: ${error.value?.localizedDescription}")
        }
        if (!session.setActive(true, error = error.ptr)) {
            aapsLogger.warn(LTag.CORE, "Keep awake: cannot activate the audio session: ${error.value?.localizedDescription}")
        }
    }

    /**
     * One second of silence, looped. AVAudioPlayer will not start at volume 0, so the data is silent
     * instead of the volume.
     */
    private fun openSilence(): AVAudioPlayer? = memScoped {
        val wav = AlarmMelodySynth.toWav(ShortArray(AlarmMelodySynth.SAMPLE_RATE))
        val data = NSData.create(bytes = allocArrayOf(wav), length = wav.size.toULong())
        val error = alloc<ObjCObjectVar<NSError?>>()
        val created = AVAudioPlayer(data = data, error = error.ptr)
        if (error.value != null) {
            aapsLogger.warn(LTag.CORE, "Keep awake: cannot open the silence: ${error.value?.localizedDescription}")
            null
        } else {
            created
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, null) { note: NSNotification? ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeEnded) onMain { ensurePlaying("interruption ended") }
        }
        center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, null) { _ ->
            // The old player is dead after a reset; a new one is needed.
            onMain {
                player = null
                ensurePlaying("media services reset")
            }
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) { _ ->
            onMain { if (state.wanted) ensurePlaying("app active") }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }
}
