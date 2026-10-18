package app.aaps.plugins.libre3

/**
 * iOS sink. The iOS half of this driver is not written yet, so this only has to make the shared
 * protocol code compile and keep its lines visible while it is being built. It prints to standard
 * output, which Xcode shows, with the same tag Android uses so the two logs read the same way.
 *
 * Replace this with the real AAPS log file writer when the iOS driver lands. Printing is a
 * deliberate placeholder, not a silent no-op: a line that is written is a line that can be read.
 */
internal actual fun libre3LogWrite(level: Libre3LogLevel, message: String, cause: Throwable?) {
    val suffix = if (cause == null) "" else " cause=$cause"
    println("${Libre3LogMarkers.TAG} ${level.name} $message$suffix")
}
