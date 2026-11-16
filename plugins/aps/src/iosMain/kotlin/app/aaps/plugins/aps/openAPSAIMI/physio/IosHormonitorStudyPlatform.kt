package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.IosAimiDiagPlatform
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

/**
 * iOS half of [HormonitorStudyPlatform].
 *
 * Both paths are the AIMI storage folder (the app's `Documents/AAPS`, visible in Files under
 * "On My iPhone > AAPS > AAPS"), next to the other AIMI files. iOS has no shared folder that can
 * refuse the write, so there is no second place to fall back to.
 *
 * The dataset id seed uses `identifierForVendor`, which is the app's own id for this phone, never a
 * hardware id; it is hashed before it reaches a file, as on Android.
 */
class IosHormonitorStudyPlatform(private val storage: AimiStorage) : HormonitorStudyPlatform {

    private val diag = IosAimiDiagPlatform()

    override fun uptimeMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

    override fun appVersion(): String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"

    override fun datasetIdSeed(): String =
        "${NSBundle.mainBundle.bundleIdentifier.orEmpty()}|${UIDevice.currentDevice.identifierForVendor?.UUIDString.orEmpty()}"

    override fun sha256Hex(input: String): String = diag.sha256Hex(input)

    override fun primaryFile(name: String): AimiPath = storage.file(name)

    override fun fallbackFile(name: String): AimiPath = storage.file(name)
}
