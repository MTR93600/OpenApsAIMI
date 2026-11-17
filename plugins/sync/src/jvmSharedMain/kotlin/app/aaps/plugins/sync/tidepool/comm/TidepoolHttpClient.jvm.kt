package app.aaps.plugins.sync.tidepool.comm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/** OkHttp, the HTTP stack the app already ships on Android and the desktop. */
internal actual fun tidepoolHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) { configure() }
