package app.aaps.plugins.aps.openAPSAIMI

/**
 * Blocks the calling thread for [millis], and says whether the wait ran to its end.
 *
 * This exists for one caller: the backoff between two attempts in
 * [app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry]. That helper is blocking on purpose - see the
 * "Blocking, not suspending" note on [app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp] - so the wait
 * between attempts has to block too. `Thread.sleep` is a JVM call, so the wait itself is the one
 * line that has to stay on the platform.
 *
 * A suspending `delay` was the obvious alternative and was rejected: it would turn every call
 * through `LlmHttpRetry` into a suspending call, and on the JVM `runBlocking { delay(...) }` answers
 * a thread interrupt differently from `Thread.sleep` - it clears the interrupt flag and raises an
 * `InterruptedException` of its own, where the code being ported restores the flag and re-throws the
 * error it was retrying. Keeping the sleep on the platform keeps that exact behaviour.
 *
 * @return `true` when the full [millis] elapsed, `false` when the wait was cut short because the
 *   thread was interrupted. On `false` the platform has already restored the thread's interrupt
 *   flag, and the caller is expected to stop retrying.
 */
expect fun aimiWaitMs(millis: Long): Boolean
