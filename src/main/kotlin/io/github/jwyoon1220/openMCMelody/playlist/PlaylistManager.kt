package io.github.jwyoon1220.openMCMelody.playlist

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PLAYLISTS_KEY = "playlists"
private const val SONGS_KEY = "songs"

/**
 * Global/shared playlist storage (`playlists.yml`), editable by any player.
 *
 * Mutations update the in-memory [YamlConfiguration] synchronously on the main thread (cheap,
 * no I/O), then hand a serialized snapshot off to a single-slot async writer so disk I/O never
 * stalls the main thread. Rapid-fire edits collapse into the latest snapshot rather than queuing
 * one write per edit.
 */
class PlaylistManager(private val plugin: Plugin, private val file: File) {

    private val config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    private val pendingWrite = AtomicReference<String?>(null)
    private val flushScheduled = AtomicBoolean(false)

    @Synchronized
    fun list(): List<String> =
        config.getConfigurationSection(PLAYLISTS_KEY)?.getKeys(false)?.sorted() ?: emptyList()

    @Synchronized
    fun get(name: String): Playlist? {
        val section = config.getConfigurationSection("$PLAYLISTS_KEY.$name") ?: return null
        return Playlist(name, section.getStringList(SONGS_KEY).toMutableList())
    }

    @Synchronized
    fun create(name: String): Boolean {
        if (config.contains("$PLAYLISTS_KEY.$name")) return false
        config.set("$PLAYLISTS_KEY.$name.$SONGS_KEY", emptyList<String>())
        scheduleSave()
        return true
    }

    @Synchronized
    fun delete(name: String): Boolean {
        if (!config.contains("$PLAYLISTS_KEY.$name")) return false
        config.set("$PLAYLISTS_KEY.$name", null)
        scheduleSave()
        return true
    }

    /** Adds [filename] to playlist [name]. Caller is expected to have already validated it exists under `midi/`. */
    @Synchronized
    fun addSong(name: String, filename: String): Boolean {
        val section = config.getConfigurationSection("$PLAYLISTS_KEY.$name") ?: return false
        val songs = section.getStringList(SONGS_KEY).toMutableList()
        songs.add(filename)
        config.set("$PLAYLISTS_KEY.$name.$SONGS_KEY", songs)
        scheduleSave()
        return true
    }

    /** Removes a song from playlist [name] by 1-based index or exact filename. */
    @Synchronized
    fun removeSong(name: String, entry: String): Boolean {
        val section = config.getConfigurationSection("$PLAYLISTS_KEY.$name") ?: return false
        val songs = section.getStringList(SONGS_KEY).toMutableList()
        val index = entry.toIntOrNull()?.minus(1)
        val removed = if (index != null && index in songs.indices) {
            songs.removeAt(index)
            true
        } else {
            songs.remove(entry)
        }
        if (!removed) return false
        config.set("$PLAYLISTS_KEY.$name.$SONGS_KEY", songs)
        scheduleSave()
        return true
    }

    private fun scheduleSave() {
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
        // Catch a write that raced in between our getAndSet(null) and clearing the flag above.
        if (pendingWrite.get() != null) requestFlush()
    }
}
