package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class CircadianMealProfileSnapshotJsonTest : TestBase() {

    private fun roundTrip(snapshot: CircadianMealProfileSnapshot): CircadianMealProfileSnapshot =
        CircadianMealProfileSnapshot.fromJsonObject(
            Json.parseToJsonElement(snapshot.toJsonObject().toString()).jsonObject
        )

    @Test
    fun defaults_survive_a_round_trip() {
        val original = CircadianMealProfileSnapshot()
        assertThat(roundTrip(original)).isEqualTo(original)
    }

    @Test
    fun learned_values_survive_a_round_trip() {
        val original = CircadianMealProfileSnapshot(
            breakfastCenterHour = 7.4166,
            breakfastSamples = 9,
            lunchCenterHour = 13.05,
            lunchSamples = 30,
            dinnerCenterHour = 20.75,
            dinnerSamples = 4,
            snackCenterHour = 15.5,
            snackSamples = 1,
            dawnCenterHour = 5.8,
            dawnSamples = 12,
        )
        assertThat(roundTrip(original)).isEqualTo(original)
    }

    @Test
    fun a_file_written_by_org_json_still_reads() {
        // org.json writes a whole Double without the fraction: 19.00 becomes 19.
        val legacy = """
            {"breakfast_center_hour":8.25,"breakfast_samples":0,
             "lunch_center_hour":12.5,"lunch_samples":3,
             "dinner_center_hour":19,"dinner_samples":0,
             "snack_center_hour":16,"snack_samples":0,
             "dawn_center_hour":6.25,"dawn_samples":2}
        """.trimIndent()
        val read = CircadianMealProfileSnapshot.fromJsonObject(Json.parseToJsonElement(legacy).jsonObject)
        assertThat(read.dinnerCenterHour).isEqualTo(19.00)
        assertThat(read.snackCenterHour).isEqualTo(16.00)
        assertThat(read.lunchSamples).isEqualTo(3)
        assertThat(read.dawnSamples).isEqualTo(2)
    }

    @Test
    fun a_missing_key_falls_back_to_the_constructor_default() {
        val partial = """{"lunch_center_hour":13.5,"lunch_samples":6}"""
        val read = CircadianMealProfileSnapshot.fromJsonObject(Json.parseToJsonElement(partial).jsonObject)
        assertThat(read.lunchCenterHour).isEqualTo(13.5)
        assertThat(read.lunchSamples).isEqualTo(6)
        assertThat(read).isEqualTo(
            CircadianMealProfileSnapshot(lunchCenterHour = 13.5, lunchSamples = 6)
        )
    }

    @Test
    fun every_key_is_written() {
        val keys = CircadianMealProfileSnapshot().toJsonObject().keys
        assertThat(keys).containsExactly(
            "breakfast_center_hour", "breakfast_samples",
            "lunch_center_hour", "lunch_samples",
            "dinner_center_hour", "dinner_samples",
            "snack_center_hour", "snack_samples",
            "dawn_center_hour", "dawn_samples",
        )
    }
}
