package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.OnePlusLog
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import kotlin.concurrent.Volatile

/**
 * [OnePlusCpuLease] on iPhone: there is no lease to take, and [hold] says so by answering null.
 *
 * ## Why there is nothing to hold
 *
 * An app does not decide when an iPhone runs. There is no `PARTIAL_WAKE_LOCK` and no API with the
 * same meaning: iOS suspends the app and wakes it for an event, and the event this driver is woken
 * for is a Bluetooth one. What replaces the wake lock here is the `bluetooth-central` background
 * mode in the app's Info.plist, which is what makes CoreBluetooth deliver a connection, a
 * subscription value or a disconnect to a suspended app at all. It is a different guarantee, not a
 * weaker version of the same one: it says the app is woken for the radio, not that the app may keep
 * the processor for the next ten minutes.
 *
 * The nearest thing iOS does offer is `beginBackgroundTaskWithExpirationHandler`, which buys a
 * bounded extension - about 30 seconds in current iOS - when the app is going to the background.
 * The session asks for **600 000 ms**. Handing back a `Hold` built on a 30 second assertion would
 * be answering a ten minute question with a thirty second answer and letting the caller believe it
 * got what it asked for, so it is not done here. Whether to take such an assertion anyway, as a
 * smaller and honestly named thing, is a decision for the owner and needs a device to judge.
 *
 * ## Why null is the honest answer and not a silent no-op
 *
 * [OnePlusCpuLease.hold] already has a word for "this platform could not take one": it returns
 * null, and its own documentation says the work goes ahead and is only less protected. The session
 * holds the result in a nullable and releases it with `?.`, so nothing downstream changes shape.
 * The dishonest form would be a `Hold` that does nothing, because the caller would then believe it
 * holds a lease it does not hold. This returns null and writes one line saying why.
 *
 * @param slot which sensor slot asked, so the one line is attributable in a field trace.
 */
class OnePlusIosCpuLease(private val slot: String) : OnePlusCpuLease {

    @Volatile
    private var said = false

    override fun hold(maxMillis: Long): OnePlusCpuLease.Hold? {
        if (!said) {
            said = true
            OnePlusLog.i(
                "${OnePlusLogMarkers.SESSION}: [$slot] no CPU lease on iPhone — iOS has no wake " +
                    "lock; the session is kept running by the bluetooth-central background mode " +
                    "instead (asked for ${maxMillis}ms)",
            )
        }
        return null
    }
}
