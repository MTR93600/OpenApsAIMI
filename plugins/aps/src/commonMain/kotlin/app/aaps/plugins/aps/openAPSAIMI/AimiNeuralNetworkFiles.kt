package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * The storage-port half of [AimiNeuralNetwork] serialization, kept on Android.
 *
 * The network itself is shared code now, so it can only hand out and take back a JSON string. These
 * two extensions are the only place that string meets a stored file, and they reproduce exactly what
 * `saveToFile` / `loadFromFile` did when they were members: same JSON, same "a file we cannot read is
 * simply no model" answer, same silence.
 *
 * `AimiNeuralModelStore` and `OrefPersonalMlTrainer` are their callers. They take [AimiStorage] as a
 * parameter rather than being converted into injected classes - the house pattern for this sweep, see
 * `DetermineBasalAIMI2.kt`'s note on the `CircadianMealProfileStore` entry points. These extensions are
 * on a type that already lives in commonMain, so once they take the port they are candidates to move
 * there later; not this lot.
 */
internal fun AimiNeuralNetwork.saveToFile(storage: AimiStorage, path: AimiPath): Boolean =
    storage.writeText(path, toJsonString())

internal fun AimiNeuralNetwork.Companion.loadFromFile(storage: AimiStorage, path: AimiPath): AimiNeuralNetwork? {
    val text = storage.readText(path) ?: return null
    return try {
        AimiNeuralNetwork.fromJsonString(text)
    } catch (_: Exception) {
        // Same contract as before the split: an unreadable file is "no model", never an exception at
        // the caller. AimiNeuralModelStore.load then tries the .bak.
        null
    }
}
