package io.github.jwyoon1220.openMCMelody.jukebox

import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val BLOCKS_KEY = "blocks"

/** Stable, order-independent key for a block's position - used both as the yaml list entry and the in-memory set key. */
private fun blockKey(block: Block): String = "${block.world.name};${block.x};${block.y};${block.z}"

/**
 * Tracks which jukebox blocks have been converted into "special" (song-playing) jukeboxes -
 * persisted to `jukeboxes.yml` so the conversion survives a restart, same async-single-slot-write
 * pattern as [io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager] so disk I/O never stalls
 * the main thread mutation that triggered it.
 */
class SpecialJukeboxManager(private val plugin: Plugin, private val file: File) {

    private val config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)
    private val keys: MutableSet<String> = config.getStringList(BLOCKS_KEY).toMutableSet()

    private val pendingWrite = AtomicReference<String?>(null)
    private val flushScheduled = AtomicBoolean(false)

    @Synchronized
    fun isSpecial(block: Block): Boolean = blockKey(block) in keys

    /** Returns false if [block] was already special. */
    @Synchronized
    fun markSpecial(block: Block): Boolean {
        if (!keys.add(blockKey(block))) return false
        scheduleSave()
        return true
    }

    /** Returns false if [block] wasn't special to begin with. */
    @Synchronized
    fun unmarkSpecial(block: Block): Boolean {
        if (!keys.remove(blockKey(block))) return false
        scheduleSave()
        return true
    }

    private fun scheduleSave() {
        config.set(BLOCKS_KEY, keys.toList())
        pendingWrite.set(config.saveToString())
        requestFlush()
    }

    private fun requestFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { flush() })
        }
    }

    private fun flush() {
        val snapshot = pendingWrite.getAndSet(null)
        if (snapshot != null) {
            file.parentFile?.mkdirs()
            file.writeText(snapshot)
        }
        flushScheduled.set(false)
        if (pendingWrite.get() != null) requestFlush()
    }
}
