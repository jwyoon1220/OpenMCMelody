package io.github.jwyoon1220.openMCMelody.web

import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * HTTP requests are handled on a plain thread pool (not the Bukkit main thread), but
 * [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager] and most Bukkit API calls
 * (player lookups, permission checks) are main-thread only. This blocks the calling HTTP
 * worker thread until [block] has run on the main thread - simple and correct, and fine for a
 * control panel where a request may cost up to one extra server tick of latency.
 */
object MainThreadBridge {

    class MainThreadUnavailableException(cause: Throwable) : RuntimeException(cause)

    fun <T> run(plugin: Plugin, block: () -> T): T {
        val future = CompletableFuture<T>()
        try {
            plugin.server.scheduler.runTask(plugin, Runnable {
                try {
                    future.complete(block())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            })
        } catch (e: IllegalStateException) {
            // Scheduler no longer accepting tasks (plugin disabling / server shutting down).
            throw MainThreadUnavailableException(e)
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw MainThreadUnavailableException(e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
