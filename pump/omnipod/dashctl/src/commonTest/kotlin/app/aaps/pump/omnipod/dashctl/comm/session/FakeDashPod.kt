package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.io.sameUuid
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.message.StringLengthPrefixEncoding
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadJoiner
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.crypto.SecureRandomBytes
import app.aaps.pump.omnipod.dashctl.parity.parityAesCcm
import app.aaps.pump.omnipod.dashctl.parity.parityAesCmac
import app.aaps.pump.omnipod.dashctl.parity.parityAesEcb
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.parityX25519
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.util.MessageUtil
import kotlin.test.assertTrue

/**
 * A simulated Dash pod: the pod side of pairing, session set up (EAP-AKA) and encrypted commands.
 *
 * It sits behind a [FakeDashPodCentral] (see [central]): the phone talks to it through the real
 * `DashBleLink` and `MessageIO`, at the level of BLE events. The central plays the RTS / CTS /
 * DATA / SUCCESS flow, and this class answers each whole message from the phone.
 *
 * Pairing (mirror of the Android `LTKExchanger`): SP1/SP2 is kept (it gives the pod its id), SPS1
 * is answered with the pod public key and nonce, SPS2 is checked and answered with the pod
 * confirmation, SP0GP0 is answered with P0 (`a5`). See [PodKeys] for where the keys come from.
 *
 * Session (mirror of the Android `SessionEstablisher`): the pod reads SQN out of AUTN with its own
 * Milenage, checks MAC-A, and answers RES and its IV ([nodeIv]) when SQN is above [lastSqn], or
 * AUTS (resynchronization, carrying [lastSqn]) when it is not. The EAP SUCCESS gives the pod the
 * session key CK and the nonce, as the phone has them.
 *
 * Commands (mirror of the Android `Session`): the pod decrypts each `ENCRYPTED` message with the
 * shared nonce, checks the `S0.0=...,G0.0` envelope, the unique id and the CRC of the command, and
 * answers with an encrypted `0.0=` message that holds [answerFor]. It checks the MAC and the ACK
 * number of each ACK.
 *
 * The pod reads the phone messages on its own (see [PhoneMessage]), not with `MessagePacket.parse`:
 * that parse reads the message type from the wrong flag byte (an Android bug, kept), so it would
 * not see a PAIRING message as one.
 *
 * A failed check on the pod side does not throw: the call comes from inside `BleCentral.write`,
 * and `DashBleLink` turns an exception there into a send error, which would hide it. It is added
 * to [problems] instead, and the pod sends no answer. Call [assertNoProblems] at the end of a test.
 *
 * "Phone message number" in the knobs below: the n-th whole message the pod got on the current
 * link, counted from 1 (resent messages count again). After a fresh pairing on one link, with no
 * message sent twice, it is the same as the outer sequence number of the message.
 */
internal class FakeDashPod(
    private val keys: PodKeys = PodKeys.AndroidVector,
    private val nodeIv: ByteArray = parityHex("b0b1b2b3"),
    private val otherThread: Boolean = false
) {

    /** Where the pod keys come from during pairing. */
    sealed class PodKeys {

        /**
         * The values of the Android `KeyExchangeTest.testLTK`: the pod sends that pod public key
         * and that nonce, expects that phone public key and phone confirmation, and answers that
         * pod confirmation. The LTK is the one of the test. The phone must use the private key and
         * the nonce of the test. Android does not give the pod private key, so the pod cannot
         * compute these values itself; it replays them.
         */
        object AndroidVector : PodKeys()

        /**
         * A pod with its own X25519 key: it computes the shared secret and every key with the
         * formulas of the Android `KeyExchange.generateKeys`, from the pod side. Any phone key
         * works.
         */
        class Generated(
            val podPrivate: ByteArray = parityHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"),
            val podNonce: ByteArray = parityHex("101112131415161718191a1b1c1d1e1f")
        ) : PodKeys()
    }

    private val x25519 = parityX25519()
    private val cmac = parityAesCmac()
    private val ecb = parityAesEcb()
    private val ccm = parityAesCcm()

    /** The current link. Replaced by [reconnect]. */
    var central: FakeDashPodCentral = newCentral()
        private set

    // ---------------------------------------------------------------- what the pod saw

    /** Every phone message the pod answered, in order, on every link. */
    val fromPhone = mutableListOf<PhoneMessage>()

    /** The pod commands taken out of `S0.0=...,G0.0`, in order. */
    val commands = mutableListOf<ByteArray>()

    /** The decrypted payload of each command message, envelope included, in order. */
    val envelopes = mutableListOf<ByteArray>()

    /** The ACKs the phone sent, after their MAC and ACK number were checked. */
    val acks = mutableListOf<PhoneMessage>()

    /** Every check that failed on the pod side. Must stay empty, see [assertNoProblems]. */
    val problems = mutableListOf<String>()

    // ---------------------------------------------------------------- pod state

    /** The pod id the phone gave in SP1 (null before pairing). */
    var podId: Id? = null
        private set

    /** The LTK of the pod: from pairing, or given by [storePairing]. */
    var ltk: ByteArray? = null
        private set

    /**
     * The highest EAP SQN the pod has accepted. A challenge with a SQN that is not above it gets a
     * resynchronization (AUTS) that carries this value. The Android phone starts its counter at 1
     * and adds one before each session (`OmnipodDashPodStateManagerImpl.increaseEapAkaSequenceNumber`),
     * so a new pod has seen 1.
     */
    var lastSqn: Long = 1

    /** Set by the EAP SUCCESS: the session key and the nonce, as the phone has them. */
    var ck: ByteArray? = null
        private set
    var nonce: DashNonce? = null
        private set

    /** The outer sequence number of the next pod answer to a command. */
    var podSequence: Int = 0x0c

    // ---------------------------------------------------------------- knobs

    /** The pod sends a podConf with one bit changed. */
    var wrongPodConf = false

    /** The pod sends a RES with one bit changed. */
    var wrongRes = false

    /** The pod changes one bit of the MAC of its answers to commands. */
    var breakAnswerMac = false

    /**
     * The response the pod gives to a command (the pod response bytes, without unique id, header
     * and CRC). By default: the default status response of the Android capture in `EnDecryptTest`.
     */
    var answerFor: (command: ByteArray) -> ByteArray = { parityHex(DEFAULT_STATUS_RESPONSE) }

    /**
     * What the pod answers on CMD after phone message number n: SUCCESS (and then its own answer),
     * FAIL, or nothing.
     */
    var finalAnswerFor: (messageNumber: Int) -> FakeDashPodCentral.FinalAnswer = { FakeDashPodCentral.FinalAnswer.SUCCESS }

    /** Drop the link during phone message number [first], on the packet named by [second]. */
    var dropLinkOnPhoneMessage: Pair<Int, Packet>? = null

    /**
     * Drop the link while the pod answers phone message number [first], after it sent [second]
     * DATA packets of its answer. The pod has handled the phone message (a command is executed).
     */
    var dropLinkWhileAnswering: Pair<Int, Int>? = null

    enum class Packet { FIRST, LAST }

    // ---------------------------------------------------------------- private state

    private var podPublic = ByteArray(0)
    private var podNonce = ByteArray(0)
    private var pdmPublic = ByteArray(0)
    private var pdmNonce = ByteArray(0)
    private var pdmConf = ByteArray(0)
    private var podConf = ByteArray(0)
    private var pendingMilenage: DashMilenage? = null
    private var pendingSqn: Long = 0
    private var controllerIv = ByteArray(0)
    private var lastAnswerSequence: Int? = null
    private var lastIndexOfPhoneMessage = -1

    /** A pod that was paired before: for a test that starts at the session. */
    fun storePairing(ltk: ByteArray, podId: Id) {
        this.ltk = ltk
        this.podId = podId
    }

    /**
     * The link was lost and the phone connects again. The pod keeps its id, its LTK and [lastSqn],
     * and drops the session (CK and nonce). Returns the new central.
     */
    fun reconnect(): FakeDashPodCentral {
        central.close()
        ck = null
        nonce = null
        pendingMilenage = null
        lastAnswerSequence = null
        central = newCentral()
        return central
    }

    /** Stop the delivery thread of the current link. */
    fun close() {
        central.close()
    }

    fun assertNoProblems() {
        assertTrue(problems.isEmpty(), "Pod side problems: $problems")
    }

    private fun newCentral(): FakeDashPodCentral = FakeDashPodCentral(otherThread).also { c ->
        c.replyTo = ::reply
        c.finalAnswerFor = { finalAnswerFor(c.messagesFromPhone.size) }
        c.disconnectOnWrite = { uuid, data -> dropNow(c, uuid, data) }
    }

    /** True when the phone write of [data] is the packet that [dropLinkOnPhoneMessage] names. */
    private fun dropNow(c: FakeDashPodCentral, uuid: String, data: ByteArray): Boolean {
        if (!sameUuid(uuid, FakeDashPodCentral.DATA) || data.isEmpty()) return false
        val index = data[0].toInt()
        if (index == 0) {
            // The first packet says how many follow: the same rule as FakeDashPodCentral.isComplete.
            val joiner = PayloadJoiner(data)
            lastIndexOfPhoneMessage = joiner.fullFragments + if (joiner.oneExtraPacket) 1 else 0
        }
        val (number, packet) = dropLinkOnPhoneMessage ?: return false
        if (number != c.messagesFromPhone.size + 1) return false
        return when (packet) {
            Packet.FIRST -> index == 0
            Packet.LAST  -> index == lastIndexOfPhoneMessage
        }
    }

    private fun reply(bytes: ByteArray): ByteArray? {
        val number = central.messagesFromPhone.size
        dropLinkWhileAnswering?.let { (n, packets) -> if (n == number) central.disconnectAfterPacketsToPhone = packets }
        val msg = PhoneMessage(bytes)
        fromPhone += msg
        return try {
            when (msg.type) {
                MessageType.PAIRING.value               -> onPairing(msg)
                MessageType.SESSION_ESTABLISHMENT.value -> onEap(msg)
                MessageType.ENCRYPTED.value             -> onEncrypted(msg)
                else                                    -> problem("Unexpected message type ${msg.type}")
            }
        } catch (e: Exception) {
            problem("Phone message $number: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun problem(text: String): ByteArray? {
        problems += text
        return null
    }

    // ---------------------------------------------------------------- pairing

    private fun onPairing(msg: PhoneMessage): ByteArray? {
        if (msg.destination != Ids.notActivated()) return problem("Pairing message to ${msg.destination}")
        val text = msg.payload.decodeToString()
        return when {
            text.startsWith("SP1=")    -> {
                val values = StringLengthPrefixEncoding.parseKeys(arrayOf("SP1=", ",SP2="), msg.payload)
                podId = Id(values[0])
                null
            }

            text.startsWith("SPS1=")   -> {
                val value = StringLengthPrefixEncoding.parseKeys(arrayOf("SPS1="), msg.payload)[0]
                pdmPublic = value.copyOfRange(0, 32)
                pdmNonce = value.copyOfRange(32, 48)
                if (!deriveKeys()) return null
                clear(MessageType.PAIRING, StringLengthPrefixEncoding.formatKeys(arrayOf("SPS1="), arrayOf(podPublic + podNonce)))
            }

            text.startsWith("SPS2=")   -> {
                val phoneConf = StringLengthPrefixEncoding.parseKeys(arrayOf("SPS2="), msg.payload)[0]
                if (!phoneConf.contentEquals(pdmConf)) {
                    return problem("Wrong pdmConf ${phoneConf.toParityHex()}, expected ${pdmConf.toParityHex()}")
                }
                val conf = if (wrongPodConf) podConf.flipBit(0) else podConf
                clear(MessageType.PAIRING, StringLengthPrefixEncoding.formatKeys(arrayOf("SPS2="), arrayOf(conf)))
            }

            text.startsWith("SP0,GP0") -> clear(MessageType.PAIRING, StringLengthPrefixEncoding.formatKeys(arrayOf("P0="), arrayOf(byteArrayOf(0xa5.toByte()))))
            else                       -> problem("Unexpected pairing message $text")
        }
    }

    /** Sets the pod keys after the phone SPS1. Returns false (and a problem) when they do not fit. */
    private fun deriveKeys(): Boolean {
        when (keys) {
            PodKeys.AndroidVector -> {
                if (pdmPublic.toParityHex() != VECTOR_PDM_PUBLIC || pdmNonce.toParityHex() != VECTOR_PDM_NONCE) {
                    problem("The Android vector needs the phone key and nonce of KeyExchangeTest")
                    return false
                }
                podPublic = parityHex(VECTOR_POD_PUBLIC)
                podNonce = parityHex(VECTOR_POD_NONCE)
                pdmConf = parityHex(VECTOR_PDM_CONF)
                podConf = parityHex(VECTOR_POD_CONF)
                ltk = parityHex(VECTOR_LTK)
            }

            is PodKeys.Generated  -> {
                // The Android KeyExchange.generateKeys, from the pod side: the same inputs in the
                // same order, only the shared secret is computed with the pod private key.
                podPublic = x25519.publicFromPrivate(keys.podPrivate)
                podNonce = keys.podNonce
                val curve = x25519.computeSharedSecret(keys.podPrivate, pdmPublic)
                val firstKey = podPublic.copyOfRange(28, 32) + pdmPublic.copyOfRange(28, 32) +
                    podNonce.copyOfRange(12, 16) + pdmNonce.copyOfRange(12, 16)
                val intermediate = cmac.compute(firstKey, curve)
                val twit = "TWIt".encodeToByteArray()
                ltk = cmac.compute(intermediate, byteArrayOf(2) + twit + podNonce + pdmNonce + byteArrayOf(0, 1))
                val confKey = cmac.compute(intermediate, byteArrayOf(1) + twit + podNonce + pdmNonce + byteArrayOf(0, 1))
                pdmConf = cmac.compute(confKey, "KC_2_U".encodeToByteArray() + pdmNonce + podNonce)
                podConf = cmac.compute(confKey, "KC_2_V".encodeToByteArray() + podNonce + pdmNonce)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- session

    private fun onEap(msg: PhoneMessage): ByteArray? {
        if (msg.destination != podId) return problem("EAP message to ${msg.destination}, pod is $podId")
        val eap = EapMessage.parse(SilentPodLogger, msg.payload)
        if (eap.code == EapCode.SUCCESS) {
            val m = pendingMilenage ?: return problem("SUCCESS before a challenge")
            ck = m.ck
            nonce = DashNonce(controllerIv + nodeIv, 0)
            pendingMilenage = null
            return null
        }
        if (eap.code != EapCode.REQUEST) return problem("Unexpected EAP code ${eap.code}")
        val autn = eap.attributes.filterIsInstance<EapAkaAttributeAutn>().single().payload
        val rand = eap.attributes.filterIsInstance<EapAkaAttributeRand>().single().payload
        controllerIv = eap.attributes.filterIsInstance<EapAkaAttributeCustomIV>().single().payload
        val key = ltk ?: return problem("Challenge without an LTK")
        // AUTN = (SQN xor AK) || AMF || MAC-A. With an all zero SQN, Milenage gives AK itself.
        val ak = DashMilenage(ecb, key, ByteArray(6), randParam = rand).autn.copyOfRange(0, 6)
        val phoneSqn = ByteArray(6) { (autn[it].toInt() xor ak[it].toInt()).toByte() }
        val m = DashMilenage(ecb, key, phoneSqn, randParam = rand)
        if (!m.autn.contentEquals(autn)) return problem("MAC-A does not match: wrong LTK or RAND")
        val sqn = phoneSqn.toLong()
        if (sqn <= lastSqn) {
            // AUTS = (lastSqn xor AK*) || MAC-S, with the resynchronization AMF.
            val podSqn = lastSqn.toSqnBytes()
            val resync = DashMilenage(ecb, key, podSqn, randParam = rand, amf = DashMilenage.RESYNC_AMF)
            val akStar = resync.synchronizationSqn // with an all zero AUTS this is AK* itself
            val auts = ByteArray(6) { (podSqn[it].toInt() xor akStar[it].toInt()).toByte() } + resync.macS
            // Written by hand: the Android EapAkaAttributeAuts.toByteArray writes 18 bytes.
            val attrs = byteArrayOf(4, 4) + auts
            val total = 8 + attrs.size
            val header = byteArrayOf(EapCode.RESPONSE.code, eap.identifier, 0, total.toByte(), 0x17, 4, 0, 0)
            return clear(MessageType.SESSION_ESTABLISHMENT, header + attrs)
        }
        lastSqn = sqn
        pendingMilenage = m
        val res = if (wrongRes) m.res.flipBit(7) else m.res
        val answer = EapMessage(
            code = EapCode.RESPONSE,
            identifier = eap.identifier,
            attributes = arrayOf(EapAkaAttributeRes(res), EapAkaAttributeCustomIV(nodeIv))
        )
        return clear(MessageType.SESSION_ESTABLISHMENT, answer.toByteArray())
    }

    // ---------------------------------------------------------------- commands

    private fun onEncrypted(msg: PhoneMessage): ByteArray? {
        if (msg.destination != podId) return problem("Encrypted message to ${msg.destination}, pod is $podId")
        val key = ck ?: return problem("Encrypted message without a session")
        val n = nonce!!.increment(podReceiving = true)
        // The size field of a phone message counts the plain text, so the header is the AAD as is.
        val plain = ccm.decrypt(key, n, msg.bytes.copyOfRange(0, 16), msg.payload, 8)
        if (msg.ack) {
            if (plain.isNotEmpty()) return problem("ACK with data ${plain.toParityHex()}")
            if (msg.eqos != 0) return problem("ACK with eqos ${msg.eqos}")
            val answered = lastAnswerSequence ?: return problem("ACK with no answer to acknowledge")
            if (msg.ackNumber.toInt() and 0xff != (answered + 1) and 0xff) {
                return problem("ACK number ${msg.ackNumber}, expected ${answered + 1}")
            }
            acks += msg
            return null
        }
        if (msg.eqos != 1) return problem("Command with eqos ${msg.eqos}")
        envelopes += plain
        val command = checkEnvelope(plain) ?: return null
        commands += command
        val commandSequence = (command[4].toInt() and 0xff shr 2) and 0x0f
        val wrapped = StringLengthPrefixEncoding.formatKeys(arrayOf("0.0="), arrayOf(responseData(command, commandSequence)))
        val packet = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = msg.destination,
            destination = msg.source,
            payload = wrapped,
            sequenceNumber = podSequence.toByte(),
            ack = true,
            ackNumber = (msg.sequenceNumber + 1).toByte(),
            eqos = 1, // a real pod sets it, see the Android capture in EnDecryptTest (flags 11 a1)
            lastMessage = true
        )
        lastAnswerSequence = podSequence
        podSequence = (podSequence + 1) and 0xff
        val header = packet.asByteArray(true).copyOfRange(0, 16)
        var cipher = ccm.encrypt(key, nonce!!.increment(podReceiving = false), header, wrapped, 8)
        if (breakAnswerMac) cipher = cipher.flipBit(cipher.size - 1)
        return packet.copy(payload = cipher).asByteArray()
    }

    /**
     * Checks the Android `Session.getCmdMessage` envelope: `S0.0=`, 2 byte length, the command,
     * then `,G0.0` with no length (an empty last value). Then the command itself: our unique id
     * and a good CRC. Returns the command, or null (and a problem).
     */
    private fun checkEnvelope(plain: ByteArray): ByteArray? {
        val prefix = "S0.0=".encodeToByteArray()
        val suffix = ",G0.0".encodeToByteArray()
        if (plain.size < prefix.size + 2 + suffix.size ||
            !plain.copyOfRange(0, prefix.size).contentEquals(prefix) ||
            !plain.copyOfRange(plain.size - suffix.size, plain.size).contentEquals(suffix)
        ) return problem("Not an S0.0=...,G0.0 envelope: ${plain.toParityHex()}")
        val length = (plain[prefix.size].toInt() and 0xff shl 8) or (plain[prefix.size + 1].toInt() and 0xff)
        if (length != plain.size - prefix.size - 2 - suffix.size) return problem("Envelope length $length does not fit ${plain.toParityHex()}")
        val command = plain.copyOfRange(prefix.size + 2, prefix.size + 2 + length)
        // GetVersion (0x07) and SetUniqueId (0x03) go to the unique id ffffffff, as on Android.
        val toAnyPod = command.size > 6 && (command[6] == 0x07.toByte() || command[6] == 0x03.toByte()) &&
            command.copyOfRange(0, 4).all { it == 0xff.toByte() }
        if (!toAnyPod && !command.copyOfRange(0, 4).contentEquals(podId!!.address)) {
            return problem("Command for unique id ${command.copyOfRange(0, 4).toParityHex()}, pod is $podId")
        }
        val crc = MessageUtil.createCrc(command.copyOfRange(0, command.size - 2))
        val sent = ((command[command.size - 2].toInt() and 0xff) shl 8) or (command[command.size - 1].toInt() and 0xff)
        if (crc.toInt() and 0xffff != sent) return problem("Command CRC ${sent.toString(16)}, expected ${(crc.toInt() and 0xffff).toString(16)}")
        return command
    }

    /**
     * The pod message inside `0.0=`: the unique id of the command, 2 bytes with the sequence (the
     * command sequence plus one, as a pod answers) and the length, the response, and the CRC.
     * Android does not check any of this (the TODOs in `Session.parseResponse`). The layout is
     * checked against the Android capture in `DashSessionEndToEndTest`.
     */
    private fun responseData(command: ByteArray, commandSequence: Int): ByteArray =
        buildResponseData(command.copyOfRange(0, 4), (commandSequence + 1) and 0x0f, answerFor(command))

    private fun clear(type: MessageType, payload: ByteArray): ByteArray = MessagePacket(
        type = type,
        source = Ids.notActivated(),
        destination = Id.fromInt(DashBleLink.CONTROLLER_ID),
        payload = payload,
        sequenceNumber = 0
    ).asByteArray()

    /** A phone message as the pod reads it: the header fields straight from the bytes. */
    class PhoneMessage(val bytes: ByteArray) {

        val type: Byte = (bytes[3].toInt() and 0x0f).toByte()
        val ack: Boolean = bytes[3].toInt() and 0x80 != 0
        val eqos: Int = bytes[2].toInt() and 0x07
        val sequenceNumber: Byte = bytes[4]
        val ackNumber: Byte = bytes[5]
        val source = Id(bytes.copyOfRange(8, 12))
        val destination = Id(bytes.copyOfRange(12, 16))
        val payload: ByteArray = bytes.copyOfRange(16, bytes.size)
    }

    companion object {

        /** The Android capture in `EnDecryptTest.decrypt`: a default status response. */
        const val DEFAULT_STATUS_RESPONSE = "1d050016b00000000bff"

        // Android KeyExchangeTest.testLTK
        const val VECTOR_PDM_PRIVATE = "27ec94b71a201c5e92698d668806ae5ba00594c307cf5566e60c1fc53a6f6bb6"
        const val VECTOR_PDM_NONCE = "edfdacb242c7f4e1d2bc4d93ca3c5706"
        const val VECTOR_PDM_PUBLIC = "f2b6940243aba536a66e19fb9a39e37f1e76a1cd50ab59b3e05313b4fc93975e"
        const val VECTOR_POD_PUBLIC = "2fe57da347cd62431528daac5fbb290730fff684afc4cfc2ed90995f58cb3b74"
        const val VECTOR_POD_NONCE = "00000000000000000000000000000000"
        const val VECTOR_PDM_CONF = "5fc3b4da865e838ceaf1e9e8bb85d1ac"
        const val VECTOR_POD_CONF = "af4f10db5f96e5d9cd6cfc1f54f4a92f"
        const val VECTOR_LTK = "341e16d13f1cbf73b19d1c2964fee02b"

        /** Unique id, sequence and length (the Android `encodeHeader` layout), response, CRC. */
        fun buildResponseData(uniqueId: ByteArray, sequence: Int, response: ByteArray): ByteArray {
            val header = ((sequence and 0x0f) shl 10) or response.size
            val body = uniqueId + byteArrayOf((header shr 8).toByte(), header.toByte()) + response
            val crc = MessageUtil.createCrc(body).toInt()
            return body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        }

        private fun ByteArray.flipBit(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

        private fun ByteArray.toLong(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

        private fun Long.toSqnBytes(): ByteArray = ByteArray(6) { ((this shr (8 * (5 - it))) and 0xff).toByte() }
    }
}

/** Random bytes 1, 2, 3, ... so a test can know them. */
internal class CountingRandomBytes(private var next: Int = 1) : SecureRandomBytes {

    val drawn = mutableListOf<ByteArray>()

    override fun nextBytes(length: Int): ByteArray = ByteArray(length) { (next++).toByte() }.also { drawn += it }
}
