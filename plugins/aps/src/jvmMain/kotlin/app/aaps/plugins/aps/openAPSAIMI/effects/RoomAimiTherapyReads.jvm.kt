package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.database.di.JvmAppDatabaseBuilder
import java.io.File

actual fun openIosTickTherapyReads(): RoomAimiTherapyReads {
    val path = File(System.getProperty("java.io.tmpdir"), IOS_TICK_DATABASE_FILE).absolutePath
    return RoomAimiTherapyReads(JvmAppDatabaseBuilder().provideTherapyWindowReads(path))
}
