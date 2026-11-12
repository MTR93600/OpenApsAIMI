package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateCodec
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
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class DashKeychainStateStore(
    private val service: String = "app.aaps.pump.omnipod.dash",
    private val account: String = "pod-state"
) : DashPodStateStore {

    override fun load(): DashPodState? {
        val bytes = keychainLoad() ?: return null
        return try {
            decode(bytes)
        } catch (e: Exception) {
            null
        }
    }

    override fun save(state: DashPodState) {
        keychainStore(encode(state))
    }

    override fun clear() {
        keychainDelete()
    }

    // -- Keychain -----------------------------------------------------------

    private fun keychainLoad(): ByteArray? = memScoped {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account,
            kSecReturnData to true
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
        if (status != errSecSuccess) return@memScoped null
        (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
    }

    private fun keychainStore(bytes: ByteArray) {
        keychainDelete()
        val attributes = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData to bytes.toNSData()
        )
        SecItemAdd(attributes.toCFDictionary(), null)
    }

    private fun keychainDelete(): Boolean {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account
        )
        return SecItemDelete(query.toCFDictionary()) == errSecSuccess
    }

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
    }
}
