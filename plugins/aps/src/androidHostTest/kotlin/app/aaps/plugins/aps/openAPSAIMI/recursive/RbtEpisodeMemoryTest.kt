package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RbtEpisodeMemoryTest {

    @BeforeEach
    fun reset() {
        RbtEpisodeMemory.clearForTests()
    }

    @Test
    fun light_post_hypo_episode_expires_after_30_min_from_start() {
        val t0 = 1_000_000L
        val episode = RbtEpisodeMemory.tick(
            nowMs = t0,
            postHypoReboundProb = 0.75,
            chaosScore = 0.1,
            mealProb = 0.2,
            recentNadirBgMgdl = 65.0,
        )
        assertThat(episode?.kind).isEqualTo(RbtEpisodeMemory.EpisodeKind.POST_HYPO_REBOUND)
        assertThat(episode?.deepHypo).isFalse()
        // Sticky signal must not refresh wall-clock TTL forever.
        RbtEpisodeMemory.tick(
            nowMs = t0 + 20 * 60_000L,
            postHypoReboundProb = 0.80,
            chaosScore = 0.1,
            mealProb = 0.2,
            recentNadirBgMgdl = 65.0,
        )
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 29 * 60_000L)).isNotNull()
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 31 * 60_000L)).isNull()
    }

    @Test
    fun deep_post_hypo_episode_expires_after_45_min_from_start() {
        val t0 = 1_000_000L
        val episode = RbtEpisodeMemory.tick(
            nowMs = t0,
            postHypoReboundProb = 0.75,
            chaosScore = 0.1,
            mealProb = 0.2,
            recentNadirBgMgdl = 52.0,
        )
        assertThat(episode?.deepHypo).isTrue()
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 44 * 60_000L)).isNotNull()
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 46 * 60_000L)).isNull()
    }

    @Test
    fun aggressive_rise_exit_clears_post_hypo_episode() {
        val t0 = 1_000_000L
        RbtEpisodeMemory.tick(
            nowMs = t0,
            postHypoReboundProb = 0.75,
            chaosScore = 0.1,
            mealProb = 0.2,
            recentNadirBgMgdl = 65.0,
        )
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 5 * 60_000L)).isNotNull()
        val cleared = RbtEpisodeMemory.tick(
            nowMs = t0 + 10 * 60_000L,
            postHypoReboundProb = 0.75,
            chaosScore = 0.1,
            mealProb = 0.8,
            recentNadirBgMgdl = 65.0,
            aggressiveRiseExit = true,
        )
        assertThat(cleared).isNull()
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 10 * 60_000L)).isNull()
    }

    @Test
    fun chaos_episode_overrides_post_hypo() {
        val t0 = 2_000_000L
        RbtEpisodeMemory.tick(t0, postHypoReboundProb = 0.65, chaosScore = 0.1, mealProb = 0.1)
        val chaotic = RbtEpisodeMemory.tick(
            t0 + 5 * 60_000L,
            postHypoReboundProb = 0.50,
            chaosScore = 0.80,
            mealProb = 0.1,
        )
        assertThat(chaotic?.kind).isEqualTo(RbtEpisodeMemory.EpisodeKind.CHAOTIC)
    }
}
