package app.aaps.plugins.aps.openAPSAIMI.orchestration

/** JVM: real stack frames as "class.method:line" strings, newest first. Same as Android. */
internal actual fun aimiThrowableFrames(error: Throwable): List<String> =
    error.stackTrace.map { "${it.className}.${it.methodName}:${it.lineNumber}" }
