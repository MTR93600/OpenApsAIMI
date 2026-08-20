package app.aaps.plugins.libre3.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.util.Log
import app.aaps.plugins.libre3.Libre3LogMarkers
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android side of the NFC step: turns reader mode on while a screen is in front of the user, and
 * runs one [Libre3NfcSession] on the tag that is found.
 *
 * ⚠️ ASYNC IMPACT: Android calls back on a binder thread. The tag work is moved to one dedicated
 * thread, so it never runs on the main thread and never on the Bluetooth executor. A tag link must
 * not be shared between threads.
 *
 * The reader is on only while the screen is resumed. [disable] is called when the screen pauses, so
 * a phone in a pocket does not keep scanning.
 */
class Libre3NfcReader(private val session: Libre3NfcSession) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libre3-nfc").apply { isDaemon = true }
    }

    @Volatile
    private var adapter: NfcAdapter? = null

    /**
     * Turns reader mode on for [activity].
     *
     * @param onResult called on the NFC thread when a sensor was read and stored.
     * @param onError called on the NFC thread when the scan failed, with the reason the screen
     *   should translate. Details for support stay in the log.
     * @return false when this phone has no NFC or NFC is switched off, so the screen can say so.
     */
    fun enable(
        activity: Activity,
        onResult: (Libre3NfcScanResult) -> Unit,
        onError: (Libre3NfcFailure) -> Unit,
    ): Boolean {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!nfcAdapter.isEnabled) return false
        adapter = nfcAdapter
        nfcAdapter.enableReaderMode(
            activity,
            { tag -> executor.execute { handleTag(tag, onResult, onError) } },
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.NFC}: reader on")
        return true
    }

    /** Turns reader mode off again. Safe to call more than once. */
    fun disable(activity: Activity) {
        adapter?.let {
            try {
                it.disableReaderMode(activity)
            } catch (e: Exception) {
                Log.w(Libre3LogMarkers.TAG, "${Libre3LogMarkers.NFC}: reader could not be turned off, ${e.javaClass.simpleName}")
            }
        }
        adapter = null
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.NFC}: reader off")
    }

    /**
     * Releases the NFC thread. The screen that created this reader must call it when it is
     * destroyed, otherwise every new screen leaves one more thread behind.
     */
    fun shutdown() {
        executor.shutdown()
    }

    private fun handleTag(tag: Tag, onResult: (Libre3NfcScanResult) -> Unit, onError: (Libre3NfcFailure) -> Unit) {
        val nfcV = NfcV.get(tag)
        if (nfcV == null) {
            onError(Libre3NfcFailure.NOT_A_LIBRE3_SENSOR)
            return
        }
        try {
            nfcV.connect()
            val result = session.scan { frame -> checkAnswer(nfcV.transceive(frame)) }
            onResult(result)
        } catch (e: Libre3NfcException) {
            // The message is for the log and for support, never for the screen.
            Log.e(Libre3LogMarkers.TAG, "${Libre3LogMarkers.ERROR}: NFC scan failed, ${e.message}")
            onError(e.failure)
        } catch (e: Exception) {
            Log.e(Libre3LogMarkers.TAG, "${Libre3LogMarkers.ERROR}: NFC link failed, ${e.javaClass.simpleName}")
            onError(Libre3NfcFailure.LINK_LOST)
        } finally {
            try {
                nfcV.close()
            } catch (e: Exception) {
                Log.w(Libre3LogMarkers.TAG, "${Libre3LogMarkers.NFC}: tag could not be closed, ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The first byte of an ISO 15693 answer is the answer flag. Bit 0 set means the sensor refused
     * the command, and the byte after it says why.
     */
    private fun checkAnswer(answer: ByteArray): ByteArray {
        if (answer.isEmpty()) {
            throw Libre3NfcException("the sensor sent an empty answer", Libre3NfcFailure.LINK_LOST)
        }
        if (answer[0].toInt() and 0x01 != 0) {
            val code = if (answer.size > 1) "0x%02X".format(answer[1]) else "unknown"
            throw Libre3NfcException("the sensor refused the command, error $code", Libre3NfcFailure.SENSOR_REFUSED)
        }
        return answer
    }
}
