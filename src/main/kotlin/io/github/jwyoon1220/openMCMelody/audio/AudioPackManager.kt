package io.github.jwyoon1220.openMCMelody.audio

import io.github.jwyoon1220.openMCMelody.web.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val PACK_FORMAT = 34
private const val NAMESPACE = "openmcmelody_audio"

/**
 * Builds and caches a single resource pack zip containing every currently-known [AudioTrack]
 * (namespace `openmcmelody_audio`), each declared with `"stream": true` (required by Minecraft for
 * clips longer than a few seconds - unlike the short one-shot instrument samples in
 * [io.github.jwyoon1220.openMCMelody.soundpack.ResourcePackBuilder]). Unlike named instrument
 * soundpacks, there's exactly one audio pack for the whole server, rebuilt whenever a track that
 * isn't in the last build is requested.
 */
class AudioPackManager {

    /** Fixed pack id so [org.bukkit.entity.Player.addResourcePack] can update/replace this exact pack rather than stacking a new one every rebuild. */
    val packId: UUID = UUID.fromString("6f6d6d63-6175-6469-6f70-61636b000001")

    class BuiltPack(val bytes: ByteArray, val sha1: ByteArray, val trackKeys: Set<String>)

    @Volatile
    var current: BuiltPack? = null
        private set

    fun soundKey(track: AudioTrack): String = "$NAMESPACE:${track.key}"

    /** Rebuilds (if needed) so [tracks] are all present in [current], and returns it. */
    @Synchronized
    fun ensureBuilt(tracks: List<AudioTrack>): BuiltPack {
        val existing = current
        if (existing != null && tracks.all { it.key in existing.trackKeys }) return existing
        val built = build(tracks)
        current = built
        return built
    }

    private fun build(tracks: List<AudioTrack>): BuiltPack {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            writeEntry(zip, "pack.mcmeta", packMcmeta())
            writeEntry(zip, "assets/$NAMESPACE/sounds.json", soundsJson(tracks))
            for (track in tracks) {
                zip.putNextEntry(ZipEntry("assets/$NAMESPACE/sounds/${track.key}.ogg"))
                track.oggFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val bytes = buffer.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
        return BuiltPack(bytes, sha1, tracks.mapTo(HashSet()) { it.key })
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun packMcmeta(): String =
        Json.stringify(mapOf("pack" to mapOf("pack_format" to PACK_FORMAT, "description" to "OpenMCMelody real-audio tracks")))

    private fun soundsJson(tracks: List<AudioTrack>): String =
        Json.stringify(tracks.associate { it.key to soundEvent(it.key) })

    private fun soundEvent(key: String) = mapOf("sounds" to listOf(mapOf("name" to "$NAMESPACE:$key", "stream" to true)))
}
