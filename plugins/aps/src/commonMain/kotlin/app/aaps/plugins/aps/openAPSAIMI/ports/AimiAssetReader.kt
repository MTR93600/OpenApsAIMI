package app.aaps.plugins.aps.openAPSAIMI.ports

/**
 * Reads a bundled read-only asset by path (e.g. `"oref/hypo_lgbm.onnx"`).
 *
 * Android: `Context.assets`. iOS: the app bundle. Throws on a missing or unreadable
 * asset; callers that treat "missing" as a normal case catch it themselves.
 */
interface AimiAssetReader {
    fun readAssetBytes(path: String): ByteArray
}
