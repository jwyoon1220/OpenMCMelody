package io.github.jwyoon1220.openMCMelody.playback

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

private const val MODES_KEY = "modes"

/**
 * Per-player [PlayMode] preference (`playmodes.yml`), set via `/midi mode`. Unset players default
 * to [PlayMode.TICK]. [modeOf] is read from [PlaybackManager]'s tick loop and [setMode] from
 * command dispatch - both main-thread, so the in-memory [cache] needs no synchronization. Writes
 * are rare (a manual command, not a hot path) so each is just handed off as a one-shot async write
 * rather than needing [io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager]'s coalescing.
 */
class PlayModeManager(private val plugin: Plugin, private val file: File) {

    private val config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)
    private val cache = HashMap<UUID, PlayMode>()

    init {
        config.getConfigurationSection(MODES_KEY)?.getKeys(false)?.forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            val mode = runCatching { PlayMode.valueOf(config.getString("$MODES_KEY.$key")!!) }.getOrNull() ?: return@forEach
            cache[uuid] = mode
        }
    }

    fun modeOf(uuid: UUID): PlayMode = cache[uuid] ?: PlayMode.TICK

    fun setMode(uuid: UUID, mode: PlayMode) {
        cache[uuid] = mode
        config.set("$MODES_KEY.$uuid", mode.name)
        val snapshot = config.saveToString()
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                file.parentFile?.mkdirs()
                file.writeText(snapshot)
            },
        )
    }
}
