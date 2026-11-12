package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarminWatchAppsTest {

    @Test
    fun theSixAndroidAppIdsAreDashedForTheIphoneSdk() {
        assertEquals(
            listOf(
                "GlucoseWatch_Dev",
                "GlucoseDataField_Dev",
                "GlucoseWidget_Dev",
                "GlucoseWatch",
                "GlucoseDataField",
                "GlucoseWidget",
            ),
            GarminWatchApps.byId.values.toList(),
        )
        assertEquals(
            "C9E90EE7-E692-4829-A8B4-5E7DAFFF5CB4",
            GarminWatchApps.dashedUuid("C9E90EE7E6924829A8B45E7DAFFF5CB4"),
        )
        assertEquals(
            "C9E90EE7-E692-4829-A8B4-5E7DAFFF5CB4",
            GarminWatchApps.dashedUuid("c9e90ee7-e692-4829-a8b4-5e7dafff5cb4"),
        )
        assertTrue(GarminWatchApps.byId.keys.all { it.length == 32 })
    }
}
