package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns soundpack build/activation state. One pack is active for the whole server at a time
 * (not per-player). `builds` and `activeName` are read from HTTP worker threads too (to serve
 * the built zip and to resolve which pack is active), so both are thread-safe; everything that
 * touches [Player]/Bukkit state (`activate`, `pushTo`) must still be called on the main thread.
 */
class SoundPackManager(private val soundpacksFolder: File, private val stateFile: File) {

    class BuildResult(val definition: SoundPackDefinition, val pack: ResourcePackBuilder.BuiltPack) {
        // Precomputed once per build so the playback hot path never builds a "namespace:key" string per note.
        val customSoundKeys: Map<InstrumentSlot, String> =
            definition.slotFiles.keys.associateWith { "openmcmelody:${it.key}" }
        // (holdMillis, sound key) per slot, sorted ascending - see resolveRelease's nearest-checkpoint pick.
        val releaseSoundKeys: Map<InstrumentSlot, List<Pair<Long, String>>> =
            definition.releaseSlotFiles.mapValues { (slot, checkpoints) ->
                checkpoints.map { it.holdMillis to "openmcmelody:${ResourcePackBuilder.releaseSoundKey(slot.key, it.holdMillis)}" }
            }
    }

    private val builds = ConcurrentHashMap<String, BuildResult>()

    @Volatile
    var activeName: String? = null
        private set

    /**
     * Re-loads and re-builds whichever pack was active when the server last stopped (`activeName`
     * only lives in memory otherwise, so without this every restart would silently fall back to
     * vanilla sounds). Synchronous file I/O + zip - fine as a one-off plugin-enable cost, called
     * from [io.github.jwyoon1220.openMCMelody.OpenMCMelody.onEnable] before any player can join.
     * Returns the restored name, or null if nothing was active or it failed to rebuild.
     */
    fun restoreActiveFromDisk(): String? {
        val saved = if (stateFile.isFile) stateFile.readText().trim() else return null
        if (saved.isEmpty()) return null
        return try {
            build(saved)
            activeName = saved
            saved
        } catch (e: SoundPackLoader.SoundPackLoadException) {
            null
        }
    }

    fun listAvailable(): List<String> =
        soundpacksFolder.listFiles { f -> f.isDirectory && File(f, "pack.yml").isFile }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun buildResult(name: String): BuildResult? = builds[name]

    /**
     * Loads pack.yml + validates/zips it, caches the in-memory result, and also writes the zip
     * to `soundpacks/<name>.zip` - so "just build the file, I'll host it myself" works without
     * needing [activate]. Pure file I/O + CPU (no Bukkit API), safe to call off the main thread.
     */
    fun build(name: String): BuildResult {
        val folder = File(soundpacksFolder, name)
        if (!folder.isDirectory) throw SoundPackLoader.SoundPackLoadException("No soundpack folder named '$name' under soundpacks/")
        val definition = SoundPackLoader.load(folder)
        val built = ResourcePackBuilder.build(definition)
        File(soundpacksFolder, "$name.zip").writeBytes(built.bytes)
        val result = BuildResult(definition, built)
        builds[name] = result
        return result
    }

    /**
     * Sets an already-[build]-cached pack active and pushes it to every online player. Must run
     * on the main thread (touches [Bukkit.getOnlinePlayers] / [Player.setResourcePack]) - callers
     * should run [build] asynchronously first, then hop back to the main thread for this.
     */
    fun applyActivation(name: String, publicUrl: String): BuildResult {
        val result = builds[name] ?: throw SoundPackLoader.SoundPackLoadException("Soundpack '$name' has not been built yet")
        activeName = name
        stateFile.writeText(name)
        val url = resourcePackUrl(publicUrl, name)
        for (player in Bukkit.getOnlinePlayers()) {
            player.setResourcePack(url, result.pack.sha1, null as String?, false)
        }
        return result
    }

    /** Stops using the active pack's custom sound keys for new notes; does not un-apply the resource pack client-side. */
    fun deactivate() {
        activeName = null
        stateFile.delete()
    }

    fun pushTo(player: Player, publicUrl: String) {
        val name = activeName ?: return
        val result = builds[name] ?: return
        player.setResourcePack(resourcePackUrl(publicUrl, name), result.pack.sha1.toHexString())
    }

    /** Resolves [slot] to a custom `openmcmelody:<slot>` sound key via the active pack, or null to fall back to vanilla. */
    fun resolve(slot: InstrumentSlot): String? = activeName?.let { builds[it]?.customSoundKeys?.get(slot) }

    /**
     * Resolves [slot]'s release-tail sound key whose checkpoint hold length is closest to
     * [heldMillis] (see GmNames.RELEASE_HOLD_CHECKPOINTS_SECONDS) - null if the active pack has no
     * release checkpoints for this slot. Picking the nearest rather than always the same one keeps
     * the release's starting amplitude/timbre close to whatever the main sample's was at the actual
     * moment of interruption, instead of the audible splice mismatch a single fixed checkpoint has.
     */
    fun resolveRelease(slot: InstrumentSlot, heldMillis: Long): String? {
        val checkpoints = activeName?.let { builds[it]?.releaseSoundKeys?.get(slot) } ?: return null
        return checkpoints.minByOrNull { (holdMillis, _) -> kotlin.math.abs(holdMillis - heldMillis) }?.second
    }

    /**
     * A single resolved sound key to actually pass to a `playSound` call for a note, plus whether
     * it's [selfTerminating] - see [resolvePlaybackSound]'s doc.
     */
    class SoundResolution(val customKey: String?, val releaseKey: String?, val selfTerminating: Boolean)

    /**
     * Picks which sound actually gets played for [slot] given the note's real [durationMicros].
     *
     * When the active soundpack has duration checkpoints for this slot (see
     * [io.github.jwyoon1220.openMCMelody.midi.GmNames.RELEASE_HOLD_CHECKPOINTS_SECONDS]/
     * [io.github.jwyoon1220.openMCMelody.soundfont.SoundFontExtractorMain] - each one a complete
     * "held for about this long, then let go" performance already ending in its own natural decay)
     * and the note's duration is known and shorter than the full sustained sample, this plays the
     * checkpoint whose hold length is closest to it outright and marks the result
     * [SoundResolution.selfTerminating] - no stopSound cutoff is ever needed for it, since the clip
     * already ends on its own at roughly the right time. This is the common case for most real
     * notes (much shorter than a multi-second capture).
     *
     * Falls back to the long sustained sample (paired with [releaseKey] for callers that still
     * splice a short release tail onto a hard stopSound cutoff) whenever the pack has no
     * checkpoints for this slot, or the duration is unknown or already at least as long as the
     * sample - i.e. genuinely meant to ring out or held indefinitely.
     */
    fun resolvePlaybackSound(slot: InstrumentSlot, durationMicros: Long): SoundResolution {
        val customKey = resolve(slot)
        if (customKey == null || !slot.needsExplicitCutoff()) return SoundResolution(customKey, null, false)
        val naturalMicros = (slot.naturalSampleSeconds() * 1_000_000).toLong()
        if (durationMicros in 0 until naturalMicros) {
            val matched = resolveRelease(slot, durationMicros / 1000)
            if (matched != null) return SoundResolution(matched, null, true)
        }
        val releaseKey = resolveRelease(slot, durationMicros.coerceAtLeast(0) / 1000)
        return SoundResolution(customKey, releaseKey, false)
    }

    private fun resourcePackUrl(publicUrl: String, name: String) = "$publicUrl/resourcepack/$name.zip"
}
