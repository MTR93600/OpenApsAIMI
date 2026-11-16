package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.VisibilityContext
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The prediction authority shadow switch only acts while the authority itself is off
 * (`shadowOnly = shadow && !enabled` in the dose terminal). Shown next to the enabled switch it read
 * as "the authority is only a test", so it is shown only when the authority is off.
 */
class AimiPredictionAuthorityShadowVisibilityTest {

    /** A context whose preferences answer only the authority switch; nothing else is read. */
    private fun contextWithAuthority(enabled: Boolean): VisibilityContext {
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Preferences::class.java)) { _, method, args ->
            val key = args?.firstOrNull()
            when {
                method.name == "get" && key is BooleanPreferenceKey && key == BooleanKey.OApsAIMIPredictionAuthorityEnabled -> enabled
                else -> error("unexpected call ${method.name}(${args?.toList()})")
            }
        } as Preferences
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(VisibilityContext::class.java)) { _, method, _ ->
            if (method.name == "getPreferences") preferences else error("unexpected call ${method.name}")
        } as VisibilityContext
    }

    @Test
    fun `the shadow switch is hidden while the authority is on`() {
        assertThat(BooleanKey.OApsAIMIPredictionAuthorityShadow.visibility.isVisible(contextWithAuthority(enabled = true))).isFalse()
    }

    @Test
    fun `the shadow switch is shown while the authority is off`() {
        assertThat(BooleanKey.OApsAIMIPredictionAuthorityShadow.visibility.isVisible(contextWithAuthority(enabled = false))).isTrue()
    }

    @Test
    fun `the shadow switch says when it is used`() {
        assertThat(BooleanKey.OApsAIMIPredictionAuthorityShadow.summary).isNotNull()
    }
}
