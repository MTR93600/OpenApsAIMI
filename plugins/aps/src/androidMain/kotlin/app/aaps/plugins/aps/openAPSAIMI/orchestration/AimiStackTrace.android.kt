package app.aaps.plugins.aps.openAPSAIMI.orchestration

/** JVM/Android: real stack frames as "class.method:line" strings, newest first. */
internal actual fun aimiThrowableFrames(error: Throwable): List<String> =
    error.stackTrace.map { "${it.className}.${it.methodName}:${it.lineNumber}" }
