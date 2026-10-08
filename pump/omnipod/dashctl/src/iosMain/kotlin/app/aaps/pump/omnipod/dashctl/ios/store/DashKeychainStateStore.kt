package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
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
 * Wire format (binary, big-endian, versioned):
 * - u8 version (1)
 * - u8 flags: bit0 = ltk present, bit1 = uniqueId present, bit2 = bluetoothAddress present
 * - [16] ltk if present
 * - u64 eapAkaSequenceNumber
 * - u32 messageSequenceNumber
 * - u64 uniqueId if present
 * - u32 activationProgress ordinal
 * - u32 + bytes bluetoothAddress (UTF-8) if present
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
        private const val VERSION: Byte = 1

        private const val FLAG_LTK: Int = 0x01
        private const val FLAG_UNIQUE_ID: Int = 0x02
        private const val FLAG_BT_ADDRESS: Int = 0x04

        fun encode(state: DashPodState): ByteArray {
            val addressBytes = state.bluetoothAddress?.encodeToByteArray()
            val size = 1 + 1 +
                (if (state.ltk != null) 16 else 0) +
                8 + 4 +
                (if (state.uniqueId != null) 8 else 0) +
                4 +
                (if (addressBytes != null) 4 + addressBytes.size else 0)
            val out = ByteArray(size)
            var p = 0
            out[p++] = VERSION
            var flags = 0
            if (state.ltk != null) flags = flags or FLAG_LTK
            if (state.uniqueId != null) flags = flags or FLAG_UNIQUE_ID
            if (addressBytes != null) flags = flags or FLAG_BT_ADDRESS
            out[p++] = flags.toByte()
            if (state.ltk != null) {
                require(state.ltk.size == 16) { "LTK must be 16 bytes" }
                state.ltk.copyInto(out, p); p += 16
            }
            p = putLong(out, p, state.eapAkaSequenceNumber)
            p = putInt(out, p, state.messageSequenceNumber)
            if (state.uniqueId != null) {
                p = putLong(out, p, state.uniqueId)
            }
            p = putInt(out, p, state.activationProgress.ordinal)
            if (addressBytes != null) {
                p = putInt(out, p, addressBytes.size)
                addressBytes.copyInto(out, p); p += addressBytes.size
            }
            check(p == size) { "codec size mismatch" }
            return out
        }

        fun decode(bytes: ByteArray): DashPodState {
            var p = 0
            require(bytes.size >= 2) { "too short" }
            val version = bytes[p++]
            require(version == VERSION) { "unsupported version $version" }
            val flags = bytes[p++].toInt() and 0xFF
            val ltk = if (flags and FLAG_LTK != 0) {
                require(bytes.size >= p + 16) { "truncated ltk" }
                bytes.copyOfRange(p, p + 16).also { p += 16 }
            } else null
            require(bytes.size >= p + 12) { "truncated fixed fields" }
            val eapSqn = getLong(bytes, p); p += 8
            val msgSeq = getInt(bytes, p); p += 4
            val uniqueId = if (flags and FLAG_UNIQUE_ID != 0) {
                require(bytes.size >= p + 8) { "truncated uniqueId" }
                getLong(bytes, p).also { p += 8 }
            } else null
            require(bytes.size >= p + 4) { "truncated progress" }
            val progressOrdinal = getInt(bytes, p); p += 4
            val progress = ActivationProgress.entries.getOrNull(progressOrdinal)
                ?: throw IllegalArgumentException("bad progress ordinal $progressOrdinal")
            val address = if (flags and FLAG_BT_ADDRESS != 0) {
                require(bytes.size >= p + 4) { "truncated address length" }
                val len = getInt(bytes, p); p += 4
                require(len >= 0 && bytes.size >= p + len) { "truncated address" }
                bytes.decodeToString(p, p + len).also { p += len }
            } else null
            require(p == bytes.size) { "trailing bytes" }
            return DashPodState(
                ltk = ltk,
                eapAkaSequenceNumber = eapSqn,
                messageSequenceNumber = msgSeq,
                uniqueId = uniqueId,
                activationProgress = progress,
                bluetoothAddress = address
            )
        }

        private fun putLong(out: ByteArray, p: Int, v: Long): Int {
            for (i in 7 downTo 0) out[p + (7 - i)] = (v shr (i * 8)).toByte()
            return p + 8
        }

        private fun putInt(out: ByteArray, p: Int, v: Int): Int {
            for (i in 3 downTo 0) out[p + (3 - i)] = (v shr (i * 8)).toByte()
            return p + 4
        }

        private fun getLong(b: ByteArray, p: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
            return v
        }

        private fun getInt(b: ByteArray, p: Int): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (b[p + i].toInt() and 0xFF)
            return v
        }
    }
}
