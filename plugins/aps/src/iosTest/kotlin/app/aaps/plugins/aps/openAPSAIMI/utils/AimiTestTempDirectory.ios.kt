package app.aaps.plugins.aps.openAPSAIMI.utils

import platform.Foundation.NSTemporaryDirectory

internal actual fun aimiTestTempDirectory(): String = NSTemporaryDirectory()
