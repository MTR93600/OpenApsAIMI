package app.aaps.plugins.libre3.crypto

/**
 * Loads the fixed byte tables that ship with the app.
 *
 * They live under `src/main/resources/libre3/` and are read from the classpath, not from Android
 * assets. That choice is deliberate: a classpath resource can also be read by a plain unit test,
 * so the tables can be checked against the published vectors without a device.
 *
 * All of these files come from the MIT LibreCRKit tree. See `plugins/libre3/NOTICE` and
 * `docs/LIBRE3_NATIVE_LICENCE_MEMO.md` for where each one comes from.
 */
object Libre3RuntimeTables {

    private const val FOLDER = "/libre3"

    /** The phone certificate used when a sensor is paired for the first time. */
    const val PHONE_CERT = "phone_cert_162b.bin"

    /** The table banks of the block maker that the pairing messages use. */
    val LIB_AES_TABLES = listOf(
        "libaes_round1_tables_278dc2.bin",
        "libaes_round2_9_tables_279dc4.bin",
        "libaes_5defec_round1_tables_26f621.bin",
        "libaes_keyexp_tables_275bbb.bin",
        "libaes_keyexp_consts_276bbc.bin",
        "libaes_final_key_tables_276cfc.bin",
        "libaes_final_table_index_277cfc.bin",
        "libaes_final_table_map_277d3c.bin",
        "libaes_final_table_words_270624.bin",
    )

    /** The static region the Phase 5 key schedule reads. */
    const val PHASE5_KEY_SCHEDULE = "phase5_keysched_region_274000.bin"

    /**
     * @return the bytes of one table, or null when the file does not ship with this build.
     *
     * Callers must treat null as "this part of the driver cannot run", never as a reason to make
     * up their own bytes.
     */
    fun load(name: String): ByteArray? =
        Libre3RuntimeTables::class.java.getResourceAsStream("$FOLDER/$name")?.use { it.readBytes() }

    /** True when every file the pairing needs is really in this build. */
    fun pairingTablesPresent(): Boolean =
        load(PHONE_CERT) != null &&
            load(PHASE5_KEY_SCHEDULE) != null &&
            LIB_AES_TABLES.all { load(it) != null }

    /** Names of the files that are missing, for a clear message instead of a silent failure. */
    fun missingTables(): List<String> =
        (listOf(PHONE_CERT, PHASE5_KEY_SCHEDULE) + LIB_AES_TABLES).filter { load(it) == null }
}
