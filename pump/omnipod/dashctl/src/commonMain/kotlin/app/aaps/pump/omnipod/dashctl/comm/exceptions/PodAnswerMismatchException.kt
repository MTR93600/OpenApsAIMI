package app.aaps.pump.omnipod.dashctl.comm.exceptions

/**
 * A pod answer that does not belong to the command just sent: its 4-bit message sequence number is
 * not the one of the command plus one, or its unique id is not the one of the command. Thrown by
 * `Session.readAndAckResponse` after the ACK, as OmnipodKit (Trio) `BleMessageTransport.readAndAckResponse`
 * throws `MessageError.invalidSequence`. The caller treats it as no answer.
 *
 * No Android counterpart: Android does not check these fields (the TODOs in its `Session.parseResponse`).
 */
class PodAnswerMismatchException(message: String) : Exception(message)
