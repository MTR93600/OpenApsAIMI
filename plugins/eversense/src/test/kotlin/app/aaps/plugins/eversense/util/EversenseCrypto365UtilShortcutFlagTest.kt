package app.aaps.plugins.eversense.util

import android.content.SharedPreferences
import app.aaps.plugins.eversense.models.EversenseSecureState
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Round-trip lock on the saved secure state.
 *
 * `EversenseCGMPlugin.connect(device)` now calls `disallowUseShortcut()` on every scan-and-pick
 * connect. That method does a read-modify-write of the whole state blob, so it must change one
 * flag and leave everything else alone. If it ever wiped the key pair the app would build a new
 * identity, and if it ever wiped the region flag EU users would be sent to the US DMS hosts.
 */
class EversenseCrypto365UtilShortcutFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    /** The single value the mocked [SharedPreferences] holds, for key `StorageKeys.SECURE_STATE`. */
    private var storedState: String? = null

    @BeforeEach
    fun setUp() {
        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getString(eq(StorageKeys.SECURE_STATE), anyOrNull())).thenAnswer { storedState }
        whenever(editor.putString(eq(StorageKeys.SECURE_STATE), anyOrNull())).thenAnswer {
            storedState = it.getArgument<String?>(1)
            editor
        }
        whenever(editor.commit()).thenReturn(true)
        whenever(editor.apply()).then { }
    }

    /** Writes a full state to the mocked store and returns a copy of what was written. */
    private fun seedState(): EversenseSecureState {
        val state = EversenseSecureState()
        state.canUseShortcut = true
        state.clientId = "aabbcc"
        state.publicKey = "00112233"
        state.privateKey = "44556677"
        state.username = "user@example.com"
        state.password = "not-a-real-password"
        state.isEuropeanRegion = true
        storedState = json.encodeToString(EversenseSecureState.serializer(), state)
        return state
    }

    @Test
    fun `disallowUseShortcut turns the shortcut flag off`() {
        seedState()
        val util = EversenseCrypto365Util(prefs)
        assertTrue(util.canUseShortcut())

        util.disallowUseShortcut()

        assertFalse(util.canUseShortcut())
    }

    @Test
    fun `disallowUseShortcut keeps the key pair, the credentials and the region flag`() {
        val before = seedState()

        EversenseCrypto365Util(prefs).disallowUseShortcut()

        val after = json.decodeFromString(EversenseSecureState.serializer(), storedState ?: "{}")
        assertEquals(before.clientId, after.clientId)
        assertEquals(before.publicKey, after.publicKey)
        assertEquals(before.privateKey, after.privateKey)
        assertEquals(before.username, after.username)
        assertEquals(before.password, after.password)
        assertTrue(after.isEuropeanRegion)
        assertFalse(after.canUseShortcut)
    }
}
