package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * One HTTP request, as AIMI shared code describes it.
 *
 * Everything the platform needs is named here, so that shared code can build a request without
 * naming `java.net.HttpURLConnection` or `okhttp3.Request`, neither of which exists outside the JVM.
 *
 * The two timeouts are required, and there is deliberately no default for either. Every AIMI call
 * site picks its own pair - the auditor waits 15 s to connect and 45 s to read, the Oura client
 * waits 10 s and 15 s - and a shared default would quietly flatten that. Making them mandatory
 * means a new call site has to say what it wants rather than inherit a number nobody chose for it.
 *
 * [body] is sent as UTF-8 bytes, which is what every AIMI client already wrote. `null` means no
 * request body at all, and the request is then sent without an output stream, so a `GET` stays a
 * plain `GET`.
 *
 * [headers] is applied in iteration order, one `setRequestProperty` style entry per pair, so an
 * ordered map keeps its order on the wire.
 */
data class AimiHttpRequest(
    val url: String,
    val method: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

/**
 * What the server answered.
 *
 * All three parts survive to the caller on purpose, because the AIMI clients do not agree on which
 * one they need. The physiology analyser and the Oura client build their failure text from [reason],
 * the coaching service and the auditor build theirs from [body], and all of them print [code]. A
 * response type that dropped any of the three would silently change the text a user reads when their
 * meal photo or their coaching request is refused, which is the only thing that tells them why.
 *
 * @param code the HTTP status code.
 * @param reason the status line reason phrase. `null` when the platform has none to give - HTTP/2
 *   has no reason phrase at all - and kept nullable rather than blanked because one caller prints it
 *   straight into its message and must keep printing exactly what it printed before.
 * @param body the response text, decoded as UTF-8: the normal body on success, the error body on
 *   failure. `null` only when the server sent no body at all.
 */
data class AimiHttpResponse(
    val code: Int,
    val reason: String?,
    val body: String?
) {

    /** `true` for any 2xx status, the way an HTTP client normally counts success. */
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * HTTP for AIMI shared code.
 *
 * AIMI talks to four language model APIs and to the Oura API, and *how* a request leaves the device
 * is a platform question, not a shared one. This interface is the shared half of that: describe a
 * request, get an answer back.
 *
 * **There is deliberately no implementation on iOS.** A stub that quietly answered "no data" would
 * leave the meal photo analysis, the coaching, the auditor's advice and the thermal reading looking
 * alive while they never reached a server, and the user would read a blank answer as a real one.
 * Without a binding, the feature is visibly absent on that target and any future iOS graph fails at
 * wiring time, loudly. See `AimiStorage` for the same reasoning about files.
 *
 * ### A refused request is data, not an exception
 *
 * [execute] answers normally for every status the server gives, including 4xx and 5xx. It does not
 * throw on a non-2xx and it does not reduce one to `null` or `false`. This is not a style choice:
 * each AIMI client words its own failure differently - one throws `HTTP 429: <body>`, another throws
 * `Gemini Error (429): <body>`, another returns a translated string, and the Oura client only writes
 * a log line and carries on - and a seam that threw would have to guess which. Handing the status
 * back as data lets every client keep the exact words it had.
 *
 * ### A failure to reach the server still throws
 *
 * There is no timeout, DNS or socket failure in [AimiHttpResponse], and that is deliberate. The
 * auditor sorts those apart by exception type to decide what to show the user - it tells
 * "no network" from "timed out" from anything else - so wrapping them in a seam type of our own
 * would break a status the user reads. An implementation therefore lets the platform's own transport
 * exception travel out of [execute] untouched.
 *
 * ### Blocking, not suspending
 *
 * [execute] blocks. Every AIMI caller is already inside `withContext(Dispatchers.IO)`, and most of
 * them call through `LlmHttpRetry`, a plain blocking retry helper that sleeps between attempts. A
 * suspending seam would force that helper, and every call site through it, to become suspending as
 * well, for no gain on the platform that has an implementation today.
 *
 * The cost lands on iOS, where `URLSession` is callback based: an iOS implementation would have to
 * wait on its own completion handler, which is safe on a background queue and a deadlock on the main
 * one. When iOS is wired up, the honest move is to add a suspending method **next to** this one and
 * let a caller pick, not to change this one - a suspending method added today would have no caller,
 * and this repository does not ship unused API.
 */
interface AimiHttp {

    /**
     * Sends [request] and waits for the answer.
     *
     * The error body is read from the platform's error stream only. Where that stream is absent -
     * a refusal carrying no body at all - [AimiHttpResponse.body] is `null` and the status is still
     * reported. Implementations must not reach for the success stream as a fallback: on the JVM that
     * call throws for any status at or above 400, which would turn a reportable refusal into an
     * unrelated I/O failure.
     *
     * @throws Exception whatever the platform raises when the server could not be reached at all.
     */
    fun execute(request: AimiHttpRequest): AimiHttpResponse
}
