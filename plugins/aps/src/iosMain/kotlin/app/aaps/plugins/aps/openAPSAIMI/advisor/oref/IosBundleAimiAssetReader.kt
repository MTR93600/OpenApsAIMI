package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAssetReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

class IosBundleAimiAssetReader : AimiAssetReader {

    @OptIn(ExperimentalForeignApi::class)
    override fun readAssetBytes(path: String): ByteArray {
        val fileName = path.substringAfterLast('/')
        val directory = path.substringBeforeLast('/', "").ifEmpty { null }
        val name = fileName.substringBeforeLast('.')
        val fileType = fileName.substringAfterLast('.', "").ifEmpty { null }
        val filePath = NSBundle.mainBundle.pathForResource(name, fileType, directory)
            ?: error("Asset not found in the iOS app bundle: $path")
        val data = NSData.dataWithContentsOfFile(filePath)
            ?: error("Could not read asset from the iOS app bundle: $path")
        return data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
    }
}
