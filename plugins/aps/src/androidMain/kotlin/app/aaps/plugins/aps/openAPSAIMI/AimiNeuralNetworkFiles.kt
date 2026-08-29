package app.aaps.plugins.aps.openAPSAIMI

import java.io.File

/**
 * The `java.io.File` half of [AimiNeuralNetwork] serialization, kept on Android.
 *
 * The network itself is shared code now, so it can only hand out and take back a JSON string. These
 * two extensions are the only place that string meets a file, and they reproduce exactly what
 * `saveToFile` / `loadFromFile` did when they were members: same JSON, same "a file we cannot read is
 * simply no model" answer, same silence.
 *
 * `AimiNeuralModelStore` is their only caller. Its tmp -> bak -> rename protocol needs directory
 * creation, rename and delete, none of which the AIMI storage seam offers, so it stays on Android too.
 */
internal fun AimiNeuralNetwork.saveToFile(file: File) {
    file.writeText(toJsonString())
}

internal fun AimiNeuralNetwork.Companion.loadFromFile(file: File): AimiNeuralNetwork? {
    if (!file.exists()) return null
    return try {
        AimiNeuralNetwork.fromJsonString(file.readText())
    } catch (_: Exception) {
        // Same contract as before the split: an unreadable file is "no model", never an exception at
        // the caller. AimiNeuralModelStore.load then tries the .bak.
        null
    }
}
