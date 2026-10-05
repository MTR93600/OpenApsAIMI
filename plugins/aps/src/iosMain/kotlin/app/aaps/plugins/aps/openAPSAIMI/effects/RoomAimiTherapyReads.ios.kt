package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.database.di.IosAppDatabaseBuilder

actual fun openIosTickTherapyReads(): RoomAimiTherapyReads =
    RoomAimiTherapyReads(
        IosAppDatabaseBuilder().provideTherapyWindowReads(IOS_TICK_DATABASE_FILE),
    )
