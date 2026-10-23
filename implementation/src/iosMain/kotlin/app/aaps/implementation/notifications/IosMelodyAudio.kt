package app.aaps.implementation.notifications

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UserNotifications.UNNotificationSound

/**
 * The soft alarm melodies on iOS. The samples come from the shared [AlarmMelodySynth], so they are
 * exactly the ones Android plays.
 *
 * Two uses:
 * - [wavData]: the melody as in-memory WAV data, which `AVAudioPlayer` plays and loops while the app
 *   runs. No file is needed for that.
 * - [notificationSound]: the melody as the sound of an urgent notification. While iOS suspends the
 *   app, the alarm player cannot start any audio, so the notification sound is the only sound the
 *   person hears. iOS plays a custom notification sound only from the app bundle or from
 *   `Library/Sounds` in the app container, so the WAV is written there once.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosMelodyAudio {

    fun wavData(melody: AlarmMelody): NSData = AlarmMelodySynth.toWav(AlarmMelodySynth.render(melody)).toNSData()

    /**
     * The notification sound of [melody], or `null` when the file cannot be written. Then the caller
     * keeps the default system sound: a notification must never become silent because of a melody.
     */
    fun notificationSound(melody: AlarmMelody): UNNotificationSound? {
        val manager = NSFileManager.defaultManager
        val library = manager.URLsForDirectory(directory = NSLibraryDirectory, inDomains = NSUserDomainMask).firstOrNull() as? NSURL ?: return null
        val soundsPath = library.URLByAppendingPathComponent("Sounds")?.path ?: return null
        if (!manager.fileExistsAtPath(soundsPath)) {
            manager.createDirectoryAtPath(soundsPath, withIntermediateDirectories = true, attributes = null, error = null)
        }
        // The version in the name lets a changed melody replace the old file, like on Android.
        val name = "aaps_${melody.id}_v1.wav"
        val filePath = "$soundsPath/$name"
        if (!manager.fileExistsAtPath(filePath) && !wavData(melody).writeToFile(filePath, atomically = true)) return null
        return UNNotificationSound.soundNamed(name)
    }

    private fun ByteArray.toNSData(): NSData = memScoped {
        // dataWithBytes:length: copies, so the NSData outlives this scope.
        NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
    }
}
