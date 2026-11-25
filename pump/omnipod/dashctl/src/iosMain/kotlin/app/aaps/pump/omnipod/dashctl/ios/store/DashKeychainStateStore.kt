package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateCodec
import app.aaps.pump.omnipod.dashctl.session.DashPodStateRead
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [DashPodStateStore] in the iOS Keychain.
 *
 * The LTK is long-lived key material: it is stored `AfterFirstUnlockThisDeviceOnly`
 * (never synced to iCloud, never restored onto another device, readable after first
 * unlock so background work still runs). The whole state blob is one Keychain item;
 * there is no separate plaintext metadata.
 *
 * The bytes are [DashPodStateCodec], common code tested on the JVM and on iOS. It still reads the
 * version 1 this store wrote first, so a pod paired before keeps its key. Since version 2 the open
 * delivery command ([app.aaps.pump.omnipod.dashctl.session.DashActiveCommand]) is in the same item:
 * it survives a crash or a restart, and the pod is asked before anything new is sent.
 *
 * ## A read that fails is not "no pod"
 *
 * [read] gives [DashPodStateRead.Empty] only for `errSecItemNotFound`. Any other status (for example
 * `errSecInteractionNotAllowed`, when iOS starts the app for a BLE event before the first unlock after
 * a reboot) and bytes that do not decode give [DashPodStateRead.Unreadable], and an error is logged.
 * The item is not touched then: `DashPodStateManager` refuses every write until a read works (see
 * `DashPodStateManager.isStateKnown`).
 *
 * ## A save never deletes first
 *
 * [save] updates the item in place (`SecItemUpdate`) and adds it only when there is none
 * (`errSecItemNotFound`). The old code deleted the item and then added the new one without checking
 * the result: a failed add then lost the key for good. A failed save is logged as an error.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class DashKeychainStateStore(
    private val service: String = "app.aaps.pump.omnipod.dash",
    private val account: String = "pod-state",
    private val aapsLogger: AAPSLogger? = null,
) : DashPodStateStore {

    override fun load(): DashPodState? = (read() as? DashPodStateRead.Loaded)?.state

    override fun read(): DashPodStateRead {
        val (status, bytes) = keychainLoad()
        val result = readResult(status, bytes)
        if (result is DashPodStateRead.Unreadable) {
            aapsLogger?.error(LTag.PUMP, "Dash: the pod state in the Keychain cannot be read: ${result.reason}. Nothing is sent to the pod and the item is kept.")
        }
        return result
    }

    override fun save(state: DashPodState) {
        keychainStore(encode(state))
    }

    override fun saveChecked(state: DashPodState): Boolean = keychainStore(encode(state))

    override fun clear() {
        keychainDelete()
    }

    // -- Keychain -----------------------------------------------------------

    private fun itemQuery(): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to account,
    )

    /** The status of the read and the bytes (null unless the status is `errSecSuccess`). */
    private fun keychainLoad(): Pair<Int, ByteArray?> = memScoped {
        val query = itemQuery() + (kSecReturnData to true)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
        if (status != errSecSuccess) return@memScoped status to null
        status to (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
    }

    /** Writes [bytes] to the Keychain item. False when the Keychain refused it (logged). */
    private fun keychainStore(bytes: ByteArray): Boolean {
        val data = bytes.toNSData()
        val update = mapOf<Any?, Any?>(
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        )
        val updated = SecItemUpdate(itemQuery().toCFDictionary(), update.toCFDictionary())
        if (updated == errSecSuccess) return true
        if (updated != errSecItemNotFound) {
            aapsLogger?.error(LTag.PUMP, "Dash: the pod state was not saved in the Keychain (update status $updated)")
            return false
        }
        val attributes = itemQuery() + mapOf<Any?, Any?>(
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData to data,
        )
        val added = SecItemAdd(attributes.toCFDictionary(), null)
        if (added != errSecSuccess) {
            aapsLogger?.error(LTag.PUMP, "Dash: the pod state was not saved in the Keychain (add status $added)")
            return false
        }
        return true
    }

    private fun keychainDelete(): Boolean =
        SecItemDelete(itemQuery().toCFDictionary()) == errSecSuccess

    private fun Map<Any?, Any?>.toCFDictionary(): CFDictionaryRef? =
        CFBridgingRetain(this as Map<Any?, *>) as? CFDictionaryRef

    private fun ByteArray.toNSData(): NSData =
        NSData.create(base64EncodedString = Base64.encode(this), options = 0u) ?: NSData()

    private fun NSData.toByteArray(): ByteArray =
        Base64.decode(base64EncodedStringWithOptions(0u))

    // -- Codec --------------------------------------------------------------

    internal companion object {

        fun encode(state: DashPodState): ByteArray = DashPodStateCodec.encode(state)

        fun decode(bytes: ByteArray): DashPodState = DashPodStateCodec.decode(bytes)

        /**
         * What a Keychain read gave: [DashPodStateRead.Empty] only for `errSecItemNotFound`; any other
         * failed status, no data, or bytes that do not decode are [DashPodStateRead.Unreadable].
         */
        fun readResult(status: Int, bytes: ByteArray?): DashPodStateRead = when {
            status == errSecItemNotFound -> DashPodStateRead.Empty
            status != errSecSuccess      -> DashPodStateRead.Unreadable("Keychain read status $status")
            bytes == null                -> DashPodStateRead.Unreadable("Keychain read gave no data")
            else                         -> try {
                DashPodStateRead.Loaded(decode(bytes))
            } catch (e: Exception) {
                DashPodStateRead.Unreadable("the stored bytes do not decode: ${e.message}")
            }
        }
    }
}
