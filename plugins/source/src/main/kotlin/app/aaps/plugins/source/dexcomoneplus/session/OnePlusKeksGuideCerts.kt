package app.aaps.plugins.source.dexcomoneplus.session

import android.util.Base64
import android.util.Log
import app.aaps.plugins.source.dexcomoneplus.OnePlusLogMarkers
import jamorham.keks.Plugin

/**
 * KEKS certificate package from the public xDrip G7/One+/Stelo Auto Configure QR
 * (`G7_keks_QR.png` on navid200.github.io/xDrip docs — not the sensor applicator QR).
 *
 * Ob1 loads Pref `keks_p1`/`keks_p2`/`keks_p3` into [Plugin.setPersistence] channels
 * 8/9/10 (PartA/B/C) before the AuthStatus certificate path. Without them, unbonded
 * 4-digit pairing throws "Missing QR code".
 *
 * Provenance: NightscoutFoundation/xDrip guide Auto Configure material.
 */
object OnePlusKeksGuideCerts {

    private val partA: ByteArray by lazy { decode(PART_A_B64) }
    private val partB: ByteArray by lazy { decode(PART_B_B64) }
    private val partC: ByteArray by lazy { decode(PART_C_B64) }

    /**
     * Install guide certs into [plugin]. Safe to call every connect.
     * Mirrors Ob1: `setPersistence(7 + i, keks_p{i})` for i in 1..3.
     */
    fun install(plugin: Plugin) {
        plugin.setPersistence(8, partA.copyOf())
        plugin.setPersistence(9, partB.copyOf())
        plugin.setPersistence(10, partC.copyOf())
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: KEKS guide certs installed " +
                "p1=${partA.size}b p2=${partB.size}b p3=${partC.size}b",
        )
    }

    private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)

    private val PART_A_B64: String =
        "MIIB6jCCAY+gAwIBAgIULzxStusIcBBG1F14zoF4TJ3+UkAwCgYIKoZIzj0EAwIwEzERMA8GA1UE" +
        "AwwIREVYMDBQRzEwHhcNMjAxMDMwMTU1OTA0WhcNMzUxMDI3MTU1OTA0WjATMREwDwYDVQQDDAhE" +
        "RVgwM1BHMTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABPsayiHYruyaTrUfhTBJU9l3oa1Wl5kl" +
        "D/hjmH9Co82fpP9XHrVovGw5YnfD3LUd7a7oVRPIClxENVOKGfWpY0ijgcAwgb0wDwYDVR0TAQH/" +
        "BAUwAwEB/zAfBgNVHSMEGDAWgBSeDx428/J2pwH+jog6biamNb1q/DBaBgNVHR8EUzBRME+gNKAy" +
        "hjBodHRwOi8vY3JsLmRwLnNhYXMucHJpbWVrZXkuY29tL2NybC9ERVgwMFBHMS5jcmyiF6QVMBMx" +
        "ETAPBgNVBAMMCERFWDAwUEcxMB0GA1UdDgQWBBSI9h6BvEsX8FxrG+KZHWAIfM7deTAOBgNVHQ8B" +
        "Af8EBAMCAYYwCgYIKoZIzj0EAwIDSQAwRgIhAKppzYl+xmOvX54VgYffaFH/B1bwDEAWJFZPgaGf" +
        "WgeFAiEA2uu5/bFjtzHrBmHxwKGTKHGlDjma0cb1Geq9TJ57oBM="

    private val PART_B_B64: String =
        "MIIBzTCCAXSgAwIBAgIUGQUvzBdTC/pW5J3K/NrPhTzlunMwCgYIKoZIzj0EAwIwEzERMA8GA1UE" +
        "AwwIREVYMDNQRzEwHhcNMjMwNDE0MTAyODE0WhcNMjUwNDEzMTAyODEzWjA6MTgwNgYDVQQDDC8w" +
        "MSwwMDAwLDAzMDBMUUVDQ3pBQkF3QUEsY2lvaWUzVmJRMmhsWk1qZFVtNXJnQTBZMBMGByqGSM49" +
        "AgEGCCqGSM49AwEHA0IABFEYw16eQefgZU/ugBxSqcXfxRDvCVl9XMqEYeSvnGZnFINPK8kD8W+r" +
        "/EV1WwGD8aCXRc3/y04veZ5QvtmmtYyjfzB9MAwGA1UdEwEB/wQCMAAwHwYDVR0jBBgwFoAUiPYe" +
        "gbxLF/BcaxvimR1gCHzO3XkwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMBMB0GA1UdDgQW" +
        "BBTTCedcByVBLXp5IuOqz7J/fr1r4DAOBgNVHQ8BAf8EBAMCBaAwCgYIKoZIzj0EAwIDRwAwRAIg" +
        "SNSGjPOT2QRBAbbwf9aNfwZCgF+F2nTi/p3o3TUH8CcCIBzRv3xsft1ZQ14ySSX88OuzyuIRDXlA" +
        "fHeqO5O3vATL"

    private val PART_C_B64: String =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgAHz71Zb250R3uMDp9vehdCdeEB72" +
        "v30YyvARgdEntXmhRANCAARRGMNenkHn4GVP7oAcUqnF38UQ7wlZfVzKhGHkr5xmZxSDTyvJA/Fv" +
        "q/xFdVsBg/Ggl0XN/8tOL3meUL7ZprWM"

}
