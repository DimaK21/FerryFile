package ru.kryu.ferryfile.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.kryu.ferryfile.domain.repository.ServerRepository

/**
 * The platform documents only "a few seconds" between `Service.onTimeout` and the required
 * `stopSelf()`; a late call kills the process. Netty's graceful shutdown alone can take up to 6 s
 * (see `KtorServer.stop`), so the wait is capped well below that.
 */
internal const val TIME_LIMIT_STOP_BUDGET_MS = 3_000L

/**
 * Stops the server for the system's dataSync time limit and returns within [budgetMs].
 *
 * The shutdown runs as its own job in [scope] and is only *awaited* here: `KtorServer.stop()` is
 * `NonCancellable`, so `withTimeout { stopFromService() }` would not return at the deadline.
 * `Job.join()` is cancellable, which lets the caller move on to `stopSelf()` on time while the
 * shutdown finishes in the background (`onDestroy` then waits for it and reconciles the state).
 *
 * Uses the service-origin stop: the UI-origin `ServerRepository.stop()` would dispatch
 * `ACTION_STOP` back into the service that is being torn down.
 *
 * [logError] is a seam (defaulting to `Log.e`) so JVM tests can observe failures without the
 * framework stub.
 *
 * @return `true` if the shutdown ended (successfully or not) within the budget.
 */
internal suspend fun stopServerForTimeLimit(
    scope: CoroutineScope,
    repository: ServerRepository,
    budgetMs: Long = TIME_LIMIT_STOP_BUDGET_MS,
    logError: (String, Throwable) -> Unit = { message, cause ->
        Log.e(FileServerService.LOG_TAG, message, cause)
    }
): Boolean {
    val shutdown = scope.launch {
        try {
            repository.stopFromService()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            logError("Failed to stop server at the time limit", cause)
        }
    }
    return withTimeoutOrNull(budgetMs) { shutdown.join() } != null
}
