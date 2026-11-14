package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.pump.omnipod.dashctl.history.DashPodHistoryStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.Foundation.NSUserDefaults

/**
 * The pod history in the user defaults, as Base64 text of the `DashPodHistoryCodec` bytes. Not in the
 * Keychain with the pod state: it holds no key, it is only read by the user, and it is bigger.
 */
@OptIn(ExperimentalEncodingApi::class)
class IosDashHistoryStore(private val key: String = "app.aaps.pump.omnipod.dash.history") : DashPodHistoryStore {

    override fun load(): ByteArray? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)?.let { Base64.decode(it) }

    override fun save(bytes: ByteArray) {
        NSUserDefaults.standardUserDefaults.setObject(Base64.encode(bytes), forKey = key)
    }

    /** Tests only: forgets the stored history. */
    fun clear() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }
}
