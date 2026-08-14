package io.github.jwyoon1220.openMCMelody.jukebox

import io.github.jwyoon1220.openMCMelody.midi.BukkitExecutors
import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import io.github.jwyoon1220.openMCMelody.midi.ParsedSong
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.concurrent.CompletableFuture

private const val MAX_NOTES_PER_TICK = 12

// Vertical offset above the jukebox's center the melody-note particle spawns at - roughly where a
// vanilla note block's own particle appears above it.
private const val PARTICLE_Y_OFFSET = 1.2

// Note particle color cycles over 2 octaves (24 semitones) - see flushBatch's spawnMelodyParticle.
private const val NOTE_PARTICLE_RANGE = 24

/**
 * Drives every currently-playing "special jukebox" (see [SpecialJukeboxManager]) from a single
 * repeating 1-tick task, mirroring [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager]'s
 * shape but anchored to a fixed block [Location] instead of a set of player targets: notes are
 * broadcast with [org.bukkit.World.playSound] (Bukkit's positional, distance-falloff broadcast to
 * every nearby player, not just one specific listener), so the block itself is the "target".
 *
 * Deliberately simpler than [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager]'s
 * per-player path in one respect: no explicit stopSound cutoff is ever scheduled here. [SoundPackManager.resolvePlaybackSound]
 * already resolves most real (short) notes to a duration-matched, self-decaying checkpoint clip
 * that needs no cutoff at all - see its doc - and [org.bukkit.World] has no `stopSound(sound,
 * category)` overload the way [org.bukkit.entity.Player] does, so there'd be no clean way to
 * target just one jukebox's notes without also silencing every other jukebox currently playing the
 * same instrument elsewhere in the world. Genuinely long/unknown-duration notes are simply left to
 * ring out their sample's natural length, same as the per-player path's own fallback.
 */
class JukeboxPlaybackManager(
    private val plugin: Plugin,
    private val songCache: SongCache,
    private val soundPackManager: SoundPackManager,
) {
    private class Session(val block: Block, val center: Location, var song: ParsedSong) {
        var cursorTick = 0
        var nextEventIndex = 0
    }

    private val sessions = LinkedHashMap<String, Session>()
    private var task: BukkitTask? = null

    private var batchSlot = arrayOfNulls<InstrumentSlot>(MAX_NOTES_PER_TICK)
    private var batchVanillaPitch = FloatArray(MAX_NOTES_PER_TICK)
    private var batchCustomPitch = FloatArray(MAX_NOTES_PER_TICK)
    private var batchVolume = FloatArray(MAX_NOTES_PER_TICK)
    private var batchDurationMicros = LongArray(MAX_NOTES_PER_TICK)
    private var batchRawNote = IntArray(MAX_NOTES_PER_TICK)
    private var batchSize = 0

    fun enable() {
        check(task == null) { "JukeboxPlaybackManager already enabled" }
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 0L, 1L)
    }

    fun disable() {
        task?.cancel()
        task = null
        sessions.clear()
    }

    fun isPlaying(block: Block): Boolean = sessions.containsKey(key(block))

    /** Starts (or replaces) playback of [file] at [block]. Parsing runs off-thread via [songCache]; the session is only registered once it lands back on the main thread. */
    fun play(block: Block, file: File): CompletableFuture<Void> {
        val blockKey = key(block)
        val center = block.location.add(0.5, 0.5, 0.5)
        return songCache.get(file).thenAcceptAsync({ song ->
            sessions[blockKey] = Session(block, center, song)
        }, BukkitExecutors.main(plugin))
    }

    /** Returns false if nothing was playing at [block]. */
    fun stop(block: Block): Boolean = sessions.remove(key(block)) != null

    /** The source filename currently playing at [block], or null if nothing is - see [ParsedSong.sourceFileName]. */
    fun currentSongName(block: Block): String? = sessions[key(block)]?.song?.sourceFileName

    private fun key(block: Block) = "${block.world.name};${block.x};${block.y};${block.z}"

    private fun tick() {
        if (sessions.isEmpty()) return
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            if (session.block.type != Material.JUKEBOX) {
                iterator.remove() // broken/changed underneath the playing song
                continue
            }

            val song = session.song
            batchSize = 0
            while (session.nextEventIndex < song.size && song.tick[session.nextEventIndex] <= session.cursorTick) {
                val idx = session.nextEventIndex
                batchAdd(song.sound[idx], song.vanillaPitch[idx], song.customPitch[idx], song.volume[idx], song.durationMicros[idx], song.rawNote[idx])
                session.nextEventIndex++
            }
            if (batchSize > 0) flushBatch(session.center)

            session.cursorTick++
            if (session.cursorTick > song.totalTicks) iterator.remove()
        }
    }

    private fun batchAdd(slot: InstrumentSlot, vanillaPitch: Float, customPitch: Float, volume: Float, durationMicros: Long, rawNote: Int) {
        // Same dedup rationale as PlaybackManager.batchAdd - see its doc.
        for (i in 0 until batchSize) {
            if (batchSlot[i] === slot && batchVanillaPitch[i] == vanillaPitch) {
                if (volume > batchVolume[i]) {
                    batchVolume[i] = volume
                    batchDurationMicros[i] = durationMicros
                    batchRawNote[i] = rawNote
                }
                return
            }
        }
        // Fixed-size, no growth: a jukebox is ambient background music, not a targeted performance -
        // silently dropping the quietest excess notes of an already-dense chord is an acceptable cap.
        if (batchSize >= batchSlot.size) return
        batchSlot[batchSize] = slot
        batchVanillaPitch[batchSize] = vanillaPitch
        batchCustomPitch[batchSize] = customPitch
        batchVolume[batchSize] = volume
        batchDurationMicros[batchSize] = durationMicros
        batchRawNote[batchSize] = rawNote
        batchSize++
    }

    private fun flushBatch(center: Location) {
        val world = center.world ?: return
        for (i in 0 until batchSize) {
            val slot = batchSlot[i]!!
            val resolution = soundPackManager.resolvePlaybackSound(slot, batchDurationMicros[i])
            try {
                if (resolution.customKey != null) {
                    world.playSound(center, resolution.customKey, SoundCategory.RECORDS, batchVolume[i], batchCustomPitch[i])
                } else {
                    world.playSound(center, slot.vanilla, SoundCategory.RECORDS, batchVolume[i], batchVanillaPitch[i])
                }
            } catch (_: Exception) {
                // Best-effort, same reasoning as PlaybackManager's playSound try/catches.
            }
        }
        spawnMelodyParticle(world, center)
    }

    /**
     * Floats a single colored note particle above the jukebox for this tick's "main melody" note -
     * the loudest one in the batch, the same prominence heuristic already used elsewhere (louder =
     * more melodically foregrounded) rather than trying to run real voice-leading analysis. Uses
     * the same 0-24 semitone encoding vanilla note blocks use for [Particle.NOTE]'s color (count=0,
     * offsetX=note/24, everything else 0, extra=1.0 is the documented trick for a specific color
     * instead of a random one).
     */
    private fun spawnMelodyParticle(world: org.bukkit.World, center: Location) {
        var loudest = 0
        for (i in 1 until batchSize) if (batchVolume[i] > batchVolume[loudest]) loudest = i
        val noteId = Math.floorMod(batchRawNote[loudest], NOTE_PARTICLE_RANGE)
        val particleLocation = center.clone().add(0.0, PARTICLE_Y_OFFSET, 0.0)
        world.spawnParticle(Particle.NOTE, particleLocation, 0, noteId.toDouble() / NOTE_PARTICLE_RANGE, 0.0, 0.0, 1.0)
    }
}
