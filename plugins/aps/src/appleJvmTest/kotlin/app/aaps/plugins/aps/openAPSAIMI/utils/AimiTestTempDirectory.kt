package app.aaps.plugins.aps.openAPSAIMI.utils

/** A directory a test may write files into. JVM: `java.io.tmpdir`. iOS: the app temporary directory. */
internal expect fun aimiTestTempDirectory(): String
