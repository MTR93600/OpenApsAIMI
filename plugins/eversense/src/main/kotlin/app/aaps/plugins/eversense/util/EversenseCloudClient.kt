package app.aaps.plugins.eversense.util

import app.aaps.plugins.eversense.BuildConfig

/**
 * How this app names itself to the Eversense cloud (DMS) at sign in. One place for both
 * [EversenseHttp365Util] and [EversenseHttpE3Util].
 *
 * The client secret is not in the source. The build reads it from the environment variable
 * `EVERSENSE_CLIENT_SECRET` or from `eversense.clientSecret` in local.properties (see
 * plugins/eversense/build.gradle.kts). When a build has none, every cloud sign in fails: the
 * Eversense 365 transmitter cannot finish pairing and the cloud upload does not work.
 */
object EversenseCloudClient {

    const val CLIENT_ID = "eversenseMMAAndroid"

    val clientSecret: String get() = BuildConfig.EVERSENSE_CLIENT_SECRET

    /** False when this build was made without the client secret, so no cloud sign in can work. */
    fun hasClientSecret(): Boolean = clientSecret.isNotEmpty()
}
