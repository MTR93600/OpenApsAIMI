package app.aaps.plugins.aps.openAPSAIMI

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.stats.TddCalculator
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contrat **asynchrone** de [DetermineBasalInvocationCaches].
 *
 * Le cache ne calcule plus le TDD de façon synchrone : `getXxxCached` déclenche un rafraîchissement en
 * tâche de fond (`ioScope`, [kotlinx.coroutines.Dispatchers.IO]) et retourne immédiatement la **dernière
 * valeur connue** — donc `null` au tout premier appel, avant que la coroutine ait abouti. La valeur
 * calculée n'est visible qu'à partir de l'invocation suivante, après un nouveau [
 * DetermineBasalInvocationCaches.beginInvocation].
 *
 * L'invariant réellement protégé est donc : **un seul appel au calculateur par invocation**, quel que
 * soit le nombre de lectures du cache. Ces tests étaient écrits pour l'ancien contrat synchrone et
 * échouaient depuis le passage en asynchrone.
 */
class DetermineBasalInvocationCachesTest {

    /** Attend qu'une condition devienne vraie, ou échoue au bout de [timeoutMs]. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition non atteinte en ${timeoutMs}ms")
    }

    @Test
    fun `two getTdd24h in same invocation hit the calculator once`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        coEvery { tdd.calculateDaily(-24, 0) } coAnswers {
            calls.incrementAndGet()
            TDD(timestamp = 1L, totalAmount = 40.0)
        }

        caches.beginInvocation()
        // Premier appel : déclenche le refresh, aucune valeur encore disponible.
        assertThat(caches.getTdd24hTotalAmountCached(tdd)).isNull()
        // Second appel dans la même invocation : sert le cache, ne redéclenche rien.
        assertThat(caches.getTdd24hTotalAmountCached(tdd)).isNull()

        awaitUntil { calls.get() == 1 }
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `beginInvocation publishes the async result`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        // Valeur constante : le compteur d'appels est incrémenté *avant* que le cache publie la valeur,
        // donc l'attendre ne garantit pas la visibilité. Une valeur stable rend l'assertion déterministe.
        coEvery { tdd.calculateDaily(-24, 0) } coAnswers {
            calls.incrementAndGet()
            TDD(timestamp = 1L, totalAmount = 40.0)
        }

        caches.beginInvocation()
        // Première invocation : rien de publié encore.
        assertThat(caches.getTdd24hTotalAmountCached(tdd)).isNull()

        // La valeur calculée en tâche de fond devient visible à partir d'une invocation ultérieure.
        var published: Double? = null
        awaitUntil {
            caches.beginInvocation()
            published = caches.getTdd24hTotalAmountCached(tdd)
            published != null
        }
        assertThat(published).isEqualTo(40.0)
        assertThat(calls.get()).isAtLeast(1)
    }

    @Test
    fun `getTdd1d sparse is served from cache within one invocation`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        val sparse = LongSparseArray<TDD>().apply {
            put(1L, TDD(timestamp = 1L, totalAmount = 33.0))
        }
        coEvery { tdd.calculate(1L, false) } coAnswers {
            calls.incrementAndGet()
            sparse
        }

        caches.beginInvocation()
        assertThat(caches.getTddCalculate1DaySparseCached(tdd)).isNull()
        assertThat(caches.getTddCalculate1DaySparseCached(tdd)).isNull()
        awaitUntil { calls.get() == 1 }
        assertThat(calls.get()).isEqualTo(1)

        caches.beginInvocation()
        assertThat(caches.getTddCalculate1DaySparseCached(tdd)).isSameInstanceAs(sparse)
    }
}
