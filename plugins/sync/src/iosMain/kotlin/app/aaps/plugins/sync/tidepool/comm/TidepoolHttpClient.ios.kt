package app.aaps.plugins.sync.tidepool.comm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

/** Darwin, which runs on `NSURLSession`: the system's own connection handling and certificate checks. */
internal actual fun tidepoolHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) { configure() }
