package io.github.jwyoon1220.openMCMelody.playback

import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

/**
 * Transport for one note's sound - either the vanilla [Player.playSound] wrapper, or (when
 * available) a direct packet send that skips it. [flush] lets a batch of same-callback notes
 * (a chord - see [PlaybackManager.dispatchInstantChord]) share a single network write where the
 * implementation supports deferring it, instead of one flush per note.
 */
interface PacketSender {
    fun sendNoteSound(player: Player, category: SoundCategory, customKey: String?, vanillaSound: Sound, pitch: Float, volume: Float, location: Location)
    fun flush(player: Player)
}

/** Default transport - identical to what [PlaybackManager] always did before packet-level bypass existed. */
object BukkitPacketSender : PacketSender {
    override fun sendNoteSound(player: Player, category: SoundCategory, customKey: String?, vanillaSound: Sound, pitch: Float, volume: Float, location: Location) {
        if (customKey != null) {
            player.playSound(location, customKey, category, volume, pitch)
        } else {
            player.playSound(location, vanillaSound, category, volume, pitch)
        }
    }

    // Player.playSound already sends+flushes on every call - nothing to defer.
    override fun flush(player: Player) {}
}
