package app.aaps.pump.omnipod.dashctl.comm.io

/**
 * Read and write on one BLE characteristic of the pod (CMD or DATA).
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.BleCharacteristicIO`.
 * Same functions, same default timeout. The contract below is what the Android implementation
 * (`legacy/io/BleIO.kt`, `legacy/callbacks/BleCommCallbacks.kt`) does. A platform implementation
 * (for example iOS CoreBluetooth) must do the same, because `MessageIO` depends on it.
 *
 * Threading: every function BLOCKS the calling thread, like on Android. `MessageIO` calls them
 * one after the other from one worker thread. Never call them on the thread or queue that
 * delivers the BLE callbacks, or a wait can never end.
 *
 * Incoming packets:
 * - Each indication (notification) received on this characteristic is put, as one packet, at
 *   the END of a FIFO queue that belongs to this characteristic only. CMD and DATA have separate
 *   queues. The queue has no size limit, and a packet is never dropped or merged.
 * - The bytes are stored as received: no copy is changed, no header is removed.
 *
 * Write confirmation:
 * - A write is a "write with response". The implementation waits for the BLE stack to confirm
 *   it, and checks that the confirmation is for the same characteristic and the same bytes.
 * - A confirmation that arrives with nobody waiting is thrown away before the next write
 *   (Android: `flushConfirmationQueue()` before each write, and the confirmation queue holds one
 *   item only).
 */
interface BleCharacteristicIO {

    /**
     * Take the packet at the head of the incoming queue. If the queue is empty, wait until a
     * packet arrives, at most [timeoutMs] milliseconds.
     *
     * Android: `BlockingQueue.poll(timeoutMs, MILLISECONDS)`.
     *
     * @return the packet (removed from the queue), or null on timeout or interrupt. Never throws.
     */
    fun receivePacket(timeoutMs: Long = DEFAULT_IO_TIMEOUT_MS): ByteArray?

    /**
     * Write [payload] on the characteristic and wait for the write confirmation, at most
     * [DEFAULT_IO_TIMEOUT_MS] milliseconds.
     *
     * Android order: set the value, drop any old confirmation, start the write, then wait.
     *
     * @return [BleSendSuccess] when the confirmation came for the same characteristic and the same
     *  bytes; [BleSendErrorSending] when the write could not be started (nothing was sent);
     *  [BleSendErrorConfirming] when the write started but the confirmation did not come in time,
     *  came with an error status, or did not match.
     */
    fun sendAndConfirmPacket(payload: ByteArray): BleSendResult

    /**
     * Remove every packet that waits in the incoming queue, without waiting for new ones.
     * Called before a new message is sent: the queues should be empty then, so Android logs a
     * warning for each packet it drops.
     *
     * @return true if one of the dropped packets is not empty and its first byte is the RTS
     *  command (0x00). This matters on CMD only: it means the pod wanted to send a message.
     *  On DATA the result is ignored.
     */
    fun flushIncomingQueue(): Boolean

    /**
     * Turn on indications for this characteristic (write the CCC descriptor with the ENABLE
     * INDICATION value, and wait for the descriptor write confirmation, at most
     * [DEFAULT_IO_TIMEOUT_MS]). After this the pod can send packets on it.
     *
     * @return [BleSendSuccess]. On Android every failure THROWS a `ConnectException` instead of
     *  returning an error, and the connection code relies on that.
     */
    fun readyToRead(): BleSendResult

    companion object {

        const val DEFAULT_IO_TIMEOUT_MS = 1000L
    }
}
