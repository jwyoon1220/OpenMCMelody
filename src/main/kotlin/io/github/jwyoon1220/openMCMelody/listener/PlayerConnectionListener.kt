package io.github.jwyoon1220.openMCMelody.listener

import io.github.jwyoon1220.openMCMelody.playback.PlaybackManager
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerConnectionListener(
    private val playbackManager: PlaybackManager,
    private val soundPackManager: SoundPackManager,
    private val publicUrl: String,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (publicUrl.isNotBlank()) soundPackManager.pushTo(event.player, publicUrl)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        playbackManager.stop(setOf(event.player.uniqueId))
    }
}
