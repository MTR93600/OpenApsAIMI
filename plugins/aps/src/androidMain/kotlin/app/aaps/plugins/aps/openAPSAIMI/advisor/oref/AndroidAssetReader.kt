package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAssetReader

/** [AimiAssetReader] backed by Android assets. */
internal class AndroidAssetReader(private val context: Context) : AimiAssetReader {
    override fun readAssetBytes(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }
}
