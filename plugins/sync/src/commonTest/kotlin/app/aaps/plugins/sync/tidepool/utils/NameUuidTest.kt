package app.aaps.plugins.sync.tidepool.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Tidepool origin id must be the same on every platform as Java's `UUID.nameUUIDFromBytes`, or a
 * record already uploaded from Android would be uploaded again. The expected values come from Python's
 * hashlib MD5 with the version 3 bits set the way Java sets them.
 */
class NameUuidTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun md5_matches_the_rfc_vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(NameUuid.md5(ByteArray(0))))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(NameUuid.md5("abc".encodeToByteArray())))
    }

    @Test
    fun md5_handles_more_than_one_block() {
        assertEquals("887f30b43b2867f4a9accceee7d16e6c", hex(NameUuid.md5("a".repeat(200).encodeToByteArray())))
    }

    @Test
    fun ids_match_the_ones_android_already_uploaded() {
        assertEquals("c08899bd-b8dd-3f8d-95fc-836b9c9be72c", NameUuid.fromName("AAPS-cgm1700000000000"))
        assertEquals("d83a9008-c6bf-3712-afce-ddf1a246646b", NameUuid.fromName("AAPS-bolus1700000000000"))
        assertEquals("4188ce46-7327-3c2c-b676-40f00127ca80", NameUuid.fromName("AAPS-note-bolus1000"))
        assertEquals(
            "b2eb2677-ddb8-351b-b2fd-7308ac153f19",
            NameUuid.fromName("AAPS-basal-automated1700000000000-1700000300000"),
        )
    }

    @Test
    fun empty_and_non_ascii_names() {
        assertEquals("d41d8cd9-8f00-3204-a980-0998ecf8427e", NameUuid.fromName(""))
        assertEquals("66ddcd97-cfde-3bb2-b6fb-8a999b4bc76f", NameUuid.fromName("é"))
    }
}
