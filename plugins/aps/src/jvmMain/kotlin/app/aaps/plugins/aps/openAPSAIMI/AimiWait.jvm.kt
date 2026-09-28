package app.aaps.plugins.aps.openAPSAIMI

/**
 * Desktop half of [aimiWaitMs]. Same `Thread.sleep` as the Android half.
 *
 * The two are written out twice rather than shared through a `jvmSharedMain` source set: this module
 * has no such source set, and adding one means calling `applyDefaultHierarchyTemplate()` by hand,
 * which in a module this size (Compose, Metro, Android resources, four targets) is a much larger
 * change than four duplicated lines. `:core:interfaces` does have one, and is where this would move
 * if a second caller ever needed it.
 */
actual fun aimiWaitMs(millis: Long): Boolean =
    try {
        Thread.sleep(millis)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
