package app.aaps.utils

import leakcanary.LeakCanary

fun configureLeakCanary(isEnabled: Boolean = false) {
    LeakCanary.config = LeakCanary.config.copy(dumpHeap = isEnabled)
    LeakCanary.showLeakDisplayActivityLauncherIcon(isEnabled)
}