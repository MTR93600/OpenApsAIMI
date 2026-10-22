package app.aaps.plugins.aps.openAPSAIMI.advisor

internal actual fun isAimiOutOfMemoryError(throwable: Throwable): Boolean = throwable is OutOfMemoryError
