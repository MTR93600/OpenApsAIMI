package app.aaps.plugins.source.dexcomoneplus.oem

import android.os.Build
import android.util.Log

/**
 * Resolves OEM BLE defaults from [Build.MANUFACTURER] / [Build.MODEL].
 * Optional [override] supports future debug pref wiring (A8/A9) without UI here.
 */
object DeviceProfileRegistry {

    const val LOG_MARKER = "DEXCOM_ONEPLUS_OEM_PROFILE"

    /** Pixel 6/7/8 family — Juggluco never requestMtu before discover on Dex path. */
    val PixelDefault: OemDeviceProfile = OemDeviceProfile(
        id = OemProfileId.PIXEL,
        connectTimeoutMs = 30_000L,
        connectRetryCount = 3,
        connectRetryDelayMs = 2_000L,
        preferredMtu = 517,
        useForegroundService = true,
        aggressiveReconnect = false,
        postCloseSettleMs = 2_000L,
        scanHandoffMs = 500L,
        preConnectScanMs = 2_000L,
        requestMtuOnConnect = false,
        useGattRefresh = true,
        autoConnectFromAttempt = 2,
        postDiscoverDelayMs = 0L,
    )

    /**
     * Samsung S22–S25 — patient connect / reconnect (status 147 / 133 common).
     * Field logs: blind autoConnect=false without fresh ADV → 147; success needed ADV + autoConnect.
     */
    val SamsungDefault: OemDeviceProfile = OemDeviceProfile(
        id = OemProfileId.SAMSUNG,
        connectTimeoutMs = 45_000L,
        connectRetryCount = 5,
        connectRetryDelayMs = 10_000L,
        preferredMtu = 517,
        useForegroundService = true,
        aggressiveReconnect = true,
        postCloseSettleMs = 6_000L,
        scanHandoffMs = 500L,
        preConnectScanMs = 8_000L,
        requestMtuOnConnect = false,
        useGattRefresh = true,
        autoConnectFromAttempt = 0,
        postDiscoverDelayMs = 1_000L,
        requireAdvBeforeConnect = true,
    )

    /** Unknown OEM — conservative timeouts, safer MTU, FGS on. */
    val GenericFallback: OemDeviceProfile = OemDeviceProfile(
        id = OemProfileId.GENERIC_FALLBACK,
        connectTimeoutMs = 60_000L,
        connectRetryCount = 5,
        connectRetryDelayMs = 5_000L,
        preferredMtu = 185,
        useForegroundService = true,
        aggressiveReconnect = true,
        postCloseSettleMs = 3_000L,
        scanHandoffMs = 500L,
        preConnectScanMs = 3_000L,
        requestMtuOnConnect = false,
        useGattRefresh = true,
        autoConnectFromAttempt = 2,
        postDiscoverDelayMs = 0L,
    )

    fun byId(id: OemProfileId): OemDeviceProfile = when (id) {
        OemProfileId.PIXEL -> PixelDefault
        OemProfileId.SAMSUNG -> SamsungDefault
        OemProfileId.GENERIC_FALLBACK -> GenericFallback
    }

    /**
     * Picks a profile for the running device (or [override]).
     * Always logs [LOG_MARKER] for logcat / QA grepping.
     */
    fun resolve(
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
        override: OemProfileId? = null,
    ): OemDeviceProfile {
        val profile = when {
            override != null -> byId(override)
            isPixel(manufacturer) -> PixelDefault
            isSamsung(manufacturer) -> SamsungDefault
            else -> GenericFallback
        }
        Log.i(
            LOG_MARKER,
            "$LOG_MARKER id=${profile.id} manufacturer=$manufacturer model=$model " +
                "override=$override connectTimeoutMs=${profile.connectTimeoutMs} " +
                "retry=${profile.connectRetryCount} mtu=${profile.preferredMtu} " +
                "fgsFlag=${profile.useForegroundService} (not wired) aggressiveReconnect=${profile.aggressiveReconnect} " +
                "settleMs=${profile.postCloseSettleMs} preScanMs=${profile.preConnectScanMs} " +
                "mtuOnConnect=${profile.requestMtuOnConnect} autoConnectFrom=${profile.autoConnectFromAttempt} " +
                "requireAdv=${profile.requireAdvBeforeConnect}",
        )
        return profile
    }

    private fun isPixel(manufacturer: String): Boolean {
        val m = manufacturer.lowercase()
        return m.contains("google")
    }

    private fun isSamsung(manufacturer: String): Boolean {
        val m = manufacturer.lowercase()
        return m.contains("samsung")
    }
}
