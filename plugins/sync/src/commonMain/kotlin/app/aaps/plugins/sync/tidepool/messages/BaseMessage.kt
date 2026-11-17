package app.aaps.plugins.sync.tidepool.messages

import kotlinx.serialization.json.JsonObject

/** A request body sent to Tidepool. The Android Retrofit body is made from [toJson] in `comm`. */
abstract class BaseMessage {

    /** The message as Tidepool expects it. */
    abstract fun toJson(): JsonObject
}
