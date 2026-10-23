package app.aaps.plugins.aps.openAPSAIMI.background

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSError

/** What iOS answered to one background task request. [error] is set only when it was refused. */
internal data class BackgroundTaskSubmit(val accepted: Boolean, val error: String?)

/**
 * Hands [request] to `BGTaskScheduler` and keeps its answer.
 *
 * `submitTaskRequest` reports a refusal through its return value and an `NSError`, not through an
 * exception. Typical refusals: the identifier is missing from `BGTaskSchedulerPermittedIdentifiers`
 * in Info.plist, background refresh is switched off, too many pending requests, or a simulator.
 * Ignoring the return value made every one of them look like a queued job.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun submitToBackgroundTaskScheduler(request: BGTaskRequest): BackgroundTaskSubmit =
    try {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val accepted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
            BackgroundTaskSubmit(
                accepted = accepted,
                error = if (accepted) null else (error.value?.localizedDescription ?: "refused with no reason given"),
            )
        }
    } catch (e: Exception) {
        BackgroundTaskSubmit(accepted = false, error = e.message ?: e.toString())
    }

/**
 * Turns [result] into the Boolean the schedulers return, and logs a refusal on [log].
 *
 * @param taskName the identifier, so the log line says which job was not queued.
 */
internal fun reportBackgroundTaskSubmit(taskName: String, result: BackgroundTaskSubmit, log: (String) -> Unit): Boolean {
    if (!result.accepted) log("background task $taskName was NOT queued by iOS: ${result.error}")
    return result.accepted
}
