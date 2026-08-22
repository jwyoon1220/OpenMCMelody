package io.github.jwyoon1220.openMCMelody.playback

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

private const val SAMPLE_INTERVAL_TICKS = 20L // ~1 Hz - ping doesn't need to be sampled any faster than that.
private const val RING_SIZE = 12

/**
 * Tracks each online player's recent [Player.getPing] samples and turns them into a per-player
 * "lead time" - how much earlier [PlaybackManager]'s instant-dispatch path should fire that
 * player's note packet so it lands close to the note's true intended moment on their client,
 * given one-way latency (`ping/2`) plus a safety margin for how much that ping actually jitters
 * from sample to sample (population stddev of the ring buffer) - a player with a stable 200ms
 * ping needs a very different margin than one bouncing between 20 and 300ms.
 *
 * Owns its own low-frequency repeating task, separate from [PlaybackManager]'s own 1-tick loop.
 */
class PingTracker(
    private val jitterSafetyFactor: Double,
    private val maxLeadMillis: Long,
) {
    private class RingBuffer {
        val samples = IntArray(RING_SIZE)
        var count = 0
        var next = 0

        fun push(value: Int) {
            samples[next] = value
            next = (next + 1) % RING_SIZE
            if (count < RING_SIZE) count++
        }

        fun mean(): Double {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += samples[i]
            return sum / count
        }

        fun stddev(mean: Double): Double {
            if (count == 0) return 0.0
            var sumSq = 0.0
            for (i in 0 until count) {
                val d = samples[i] - mean
                sumSq += d * d
            }
            return sqrt(sumSq / count)
        }
    }

    private val buffers = ConcurrentHashMap<UUID, RingBuffer>()
    private var task: BukkitTask? = null

    fun start(plugin: Plugin) {
        check(task == null) { "PingTracker already started" }
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { sample() }, 0L, SAMPLE_INTERVAL_TICKS)
    }

    fun stop() {
        task?.cancel()
        task = null
        buffers.clear()
    }

    private fun sample() {
        val online = Bukkit.getOnlinePlayers()
        val onlineIds = online.mapTo(HashSet()) { it.uniqueId }
        buffers.keys.retainAll(onlineIds)
        for (player: Player in online) {
            val ping = player.ping
            if (ping <= 0) continue // 0/unset before the client's first keepalive round-trip - not a real sample yet.
            buffers.getOrPut(player.uniqueId) { RingBuffer() }.push(ping)
        }
    }

    /** How many milliseconds early to fire [uuid]'s note packets - 0 if no ping samples are available yet. */
    fun leadTimeMillis(uuid: UUID): Long {
        val buffer = buffers[uuid] ?: return 0L
        if (buffer.count == 0) return 0L
        val mean = buffer.mean()
        val jitter = buffer.stddev(mean)
        val lead = mean / 2.0 + jitter * jitterSafetyFactor
        return lead.toLong().coerceIn(0L, maxLeadMillis)
    }
}
