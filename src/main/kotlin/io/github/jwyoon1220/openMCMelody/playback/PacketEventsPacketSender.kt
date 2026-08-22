package io.github.jwyoon1220.openMCMelody.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.sound.StaticSound
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect
import org.bukkit.Location
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.SoundCategory as BukkitSoundCategory
import org.bukkit.Sound as BukkitSound
import com.github.retrooper.packetevents.protocol.sound.SoundCategory as PeSoundCategory

/**
 * [PacketSender] that writes the raw sound-effect packet via PacketEvents' player manager instead
 * of [Player.playSound] - skips Bukkit's own wrapper (entity/world lookups, permission-adjacent
 * checks it does internally) and, more importantly, lets [flush] be deferred: every note in one
 * [PlaybackManager.dispatchInstantChord] batch is [sendNoteSound]-written (buffered, no network
 * write) and only the batch's single trailing [flush] actually pushes bytes to the socket, instead
 * of one write+flush per note.
 *
 * Only ever constructed (see `OpenMCMelody.kt`) when the `packetevents` plugin is actually present
 * and its API initializes cleanly - if that construction throws, callers fall back to
 * [BukkitPacketSender] instead of this class ever being used.
 */
class PacketEventsPacketSender : PacketSender {

    override fun sendNoteSound(player: Player, category: BukkitSoundCategory, customKey: String?, vanillaSound: BukkitSound, pitch: Float, volume: Float, location: Location) {
        val soundId = customKey ?: Registry.SOUNDS.getKeyOrThrow(vanillaSound).asString()
        val sound = StaticSound(ResourceLocation(soundId), null)
        val peCategory = toPacketEventsCategory(category)
        val position = Vector3i(
            (location.x * 8.0).toInt(),
            (location.y * 8.0).toInt(),
            (location.z * 8.0).toInt(),
        )
        val wrapper = WrapperPlayServerSoundEffect(sound, peCategory, position, volume, pitch)
        // write, not sendPacket - deliberately does not flush yet, see flush().
        PacketEvents.getAPI().playerManager.writePacket(player, wrapper)
    }

    override fun flush(player: Player) {
        val channel = PacketEvents.getAPI().playerManager.getChannel(player) ?: return
        ChannelHelper.flush(channel)
    }

    private fun toPacketEventsCategory(category: BukkitSoundCategory): PeSoundCategory = when (category) {
        BukkitSoundCategory.MASTER -> PeSoundCategory.MASTER
        BukkitSoundCategory.MUSIC -> PeSoundCategory.MUSIC
        BukkitSoundCategory.RECORDS -> PeSoundCategory.RECORD
        BukkitSoundCategory.WEATHER -> PeSoundCategory.WEATHER
        BukkitSoundCategory.BLOCKS -> PeSoundCategory.BLOCK
        BukkitSoundCategory.HOSTILE -> PeSoundCategory.HOSTILE
        BukkitSoundCategory.NEUTRAL -> PeSoundCategory.NEUTRAL
        BukkitSoundCategory.PLAYERS -> PeSoundCategory.PLAYER
        BukkitSoundCategory.AMBIENT -> PeSoundCategory.AMBIENT
        BukkitSoundCategory.VOICE -> PeSoundCategory.VOICE
        else -> PeSoundCategory.MASTER // BukkitSoundCategory.UI has no protocol equivalent in PacketEvents 2.7.0 - never used by PlaybackManager anyway.
    }
}
