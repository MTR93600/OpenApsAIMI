package app.aaps.plugins.source.compose

/**
 * Whether the phone lets this app scan for Bluetooth sensors, and how to ask.
 *
 * Android has a runtime permission the user grants before a scan can hear anything, and it has to
 * be asked for from the screen that is on top. The sensor start screen is shared code and has no
 * way to reach that screen, so the Android entry point hands it one of these.
 */
interface OnePlusBlePermissionGate {

    /** True when a scan may start now. */
    fun granted(): Boolean

    /**
     * Ask the user for what is still missing.
     *
     * The screen only calls this when [granted] said no, so an implementation never has to decide
     * whether asking is needed.
     */
    fun request()

    companion object {

        /**
         * For a phone that has no permission step before a scan.
         *
         * iOS is one: CoreBluetooth asks for Bluetooth with its own system prompt the first time
         * the app actually scans, and there is nothing an app can check or request beforehand. So
         * [granted] is true, and [request] is never reached - the screen asks only when [granted]
         * says no.
         *
         * ⚠️ This is not a way to switch the check off on a platform that does have one. A phone
         * that needs a permission and answers true here would show an empty scan list with no
         * explanation.
         */
        val NoSeparateStep: OnePlusBlePermissionGate = object : OnePlusBlePermissionGate {
            override fun granted(): Boolean = true
            override fun request() = Unit
        }
    }
}
