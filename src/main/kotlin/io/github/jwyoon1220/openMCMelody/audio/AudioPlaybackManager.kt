package io.github.jwyoon1220.openMCMelody.audio

import org.bukkit.Bukkit
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.util.UUID

/** One target listening to [track], started at [startedAtNanos] (System.nanoTime) so elapsed time can be computed on demand. */
class AudioSession(val targets: MutableSet<UUID>, val track: AudioTrack, val startedAtNanos: Long) {
    val elapsedSeconds: Int get() = ((System.nanoTime() - startedAtNanos) / 1_000_000_000L).toInt()
    val finished: Boolean get() = elapsedSeconds >= track.durationSeconds
}

/**
 * Plays real-audio [AudioTrack]s to players via a custom resource-pack sound, instead of
 * [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager]'s per-tick note-block synthesis.
 * Much simpler than that manager: one `playSound` call starts a whole track and Minecraft plays it
 * through to completion on its own, so there's no per-tick scheduling here - just session
 * bookkeeping for `/openmcmelody audio status`. Public methods are main-thread-only, matching
 * command dispatch.
 */
class AudioPlaybackManager(private val packManager: AudioPackManager) {

    private val sessionsByTarget = HashMap<UUID, AudioSession>()

    // Players who already have the current build of the audio pack - avoids re-sending it on every play.
    private val pushedPackSha1 = HashMap<UUID, String>()

    fun play(targets: Set<UUID>, track: AudioTrack, publicUrl: String) {
        stop(targets)
        val built = packManager.ensureBuilt(listOf(track))
        val sha1Hex = built.sha1.toHexString()
        val url = "$publicUrl/audiopack/pack.zip"
        val soundKey = packManager.soundKey(track)
        val session = AudioSession(targets.toMutableSet(), track, System.nanoTime())
        for (uuid in targets) {
            sessionsByTarget[uuid] = session
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (pushedPackSha1[uuid] != sha1Hex) {
                player.addResourcePack(packManager.packId, url, built.sha1, "OpenMCMelody audio tracks", false)
                pushedPackSha1[uuid] = sha1Hex
            }
            player.playSound(player.location, soundKey, SoundCategory.MUSIC, 1f, 1f)
        }
    }

    fun stop(targets: Set<UUID>) {
        for (uuid in targets) {
            val session = sessionsByTarget.remove(uuid) ?: continue
            session.targets.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: continue
            player.stopSound(packManager.soundKey(session.track), SoundCategory.MUSIC)
        }
    }

    fun statusFor(uuid: UUID): AudioSession? {
        val session = sessionsByTarget[uuid] ?: return null
        if (session.finished) {
            sessionsByTarget.remove(uuid)
            return null
        }
        return session
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
