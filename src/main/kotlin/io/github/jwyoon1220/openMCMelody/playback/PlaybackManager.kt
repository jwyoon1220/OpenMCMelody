package io.github.jwyoon1220.openMCMelody.playback

import io.github.jwyoon1220.openMCMelody.midi.BukkitExecutors
import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import io.github.jwyoon1220.openMCMelody.midi.MC_TICK_MICROS
import io.github.jwyoon1220.openMCMelody.midi.ParsedSong
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val MAX_NOTES_PER_TICK = 16

// How many ticks before a playlist song ends to start warming the next song in the cache.
private const val PREFETCH_LEAD_TICKS = 100 // 5s

// SoundCategory each dispatched note tries to claim (see PlaybackManager.allocateCategory) as one
// of several independent "voice slots" a note's cutoff can be scoped to. Deliberately excludes
// MASTER (the global slider - stopping it would be equivalent to stopping everything), MUSIC and
// BLOCKS (both commonly muted/turned down by players in their client sound settings, unlike the
// rest of these, which players don't usually intentionally silence).
private val ROTATING_CATEGORIES = arrayOf(
    SoundCategory.RECORDS,
    SoundCategory.WEATHER,
    SoundCategory.HOSTILE,
    SoundCategory.NEUTRAL,
    SoundCategory.PLAYERS,
    SoundCategory.AMBIENT,
    SoundCategory.VOICE,
)

/**
 * Owns every active [PlaybackSession] and drives them all from a single repeating 1-tick task.
 * All public methods are main-thread only (matches Bukkit command dispatch / event handlers).
 */
class PlaybackManager(
    private val plugin: Plugin,
    private val playlistManager: PlaylistManager,
    private val songCache: SongCache,
    private val midiFolder: File,
    private val soundPackManager: SoundPackManager,
    private val playModeManager: PlayModeManager,
) {
    private val mainThreadExecutor: Executor = BukkitExecutors.main(plugin)

    private val sessionsByTarget = HashMap<UUID, PlaybackSession>()
    private val activeSessions = LinkedHashSet<PlaybackSession>()

    private var task: BukkitTask? = null

    // Dedicated real-time thread for PlayMode.INSTANT dispatch - see scheduleInstantNote. Kept
    // separate from the Bukkit scheduler entirely, since the latter can't schedule sub-tick.
    private var instantExecutor: ScheduledExecutorService? = null

    // Reusable per-tick scratch buffers (dedupe + polyphony cap), avoids allocating every tick.
    private var batchSlot = arrayOfNulls<InstrumentSlot>(32)
    private var batchVanillaPitch = FloatArray(32)
    private var batchCustomPitch = FloatArray(32)
    private var batchVolume = FloatArray(32)
    private var batchDurationMicros = LongArray(32)
    private var batchSize = 0

    // Advances on every dispatched note (both tick-locked and instant paths) - see ROTATING_CATEGORIES.
    private var categoryCursor = 0

    // Which "voice" currently owns each (sound identity, category) pair - mutated from the main
    // thread (allocateVoice, on every note dispatch) and read/cleared from instantExecutor's thread
    // (scheduleRelease's callback), hence a concurrent map rather than a plain HashMap. See
    // allocateVoice's doc for why this exists.
    private val voiceOwners = ConcurrentHashMap<VoiceKey, Long>()
    private var nextVoiceId = 0L

    // (soundIdentity, category) uniquely identifies a slot Minecraft's stopSound(sound, category)
    // can target - soundIdentity is either the custom sound key (String) or the vanilla Sound enum
    // value actually passed to playSound.
    private data class VoiceKey(val soundIdentity: Any, val category: SoundCategory)

    private class Voice(val soundIdentity: Any, val category: SoundCategory, val id: Long, val explicitStop: Boolean, val releaseDelayMillis: Long)

    fun enable() {
        check(task == null) { "PlaybackManager already enabled" }
        instantExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenMCMelody-InstantDispatch").apply { isDaemon = true }
        }
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 0L, 1L)
    }

    fun disable() {
        task?.cancel()
        task = null
        instantExecutor?.shutdownNow()
        instantExecutor = null
        activeSessions.clear()
        sessionsByTarget.clear()
        voiceOwners.clear()
    }

    fun startSession(targets: Set<UUID>, song: ParsedSong, mode: SessionMode, playlistName: String? = null): PlaybackSession {
        stop(targets)
        val session = PlaybackSession(UUID.randomUUID(), targets.toMutableSet(), song, mode, playlistName)
        for (uuid in session.targets) sessionsByTarget[uuid] = session
        activeSessions += session
        return session
    }

    /** Detaches [targets] from whatever session(s) they're in; a session only fully stops once empty. */
    fun stop(targets: Set<UUID>) {
        for (uuid in targets) {
            val session = sessionsByTarget.remove(uuid) ?: continue
            session.targets.remove(uuid)
            if (session.targets.isEmpty()) activeSessions.remove(session)
        }
    }

    /** Pause is a whole-session operation - a shared cursor can't pause for only one listener. */
    fun pause(targets: Set<UUID>): Set<PlaybackSession> {
        val affected = LinkedHashSet<PlaybackSession>()
        for (uuid in targets) {
            val session = sessionsByTarget[uuid] ?: continue
            if (session.state == PlaybackState.PLAYING) {
                // Freeze the instant-timing clock so it doesn't keep accruing real time while paused.
                session.instantElapsedMicrosBase = instantElapsedMicros(session)
                session.state = PlaybackState.PAUSED
            }
            affected += session
        }
        return affected
    }

    fun resume(targets: Set<UUID>): Set<PlaybackSession> {
        val affected = LinkedHashSet<PlaybackSession>()
        for (uuid in targets) {
            val session = sessionsByTarget[uuid] ?: continue
            if (session.state == PlaybackState.PAUSED) {
                session.instantAnchorNanos = System.nanoTime()
                session.state = PlaybackState.PLAYING
            }
            affected += session
        }
        return affected
    }

    fun statusFor(uuid: UUID): PlaybackSession? = sessionsByTarget[uuid]

    /**
     * Moves playback to [tick] (clamped to the song's length) for exactly [targets] - unlike
     * [pause]/[resume] (whole-session operations, since a shared cursor can't pause for only one
     * listener), seeking is meant to work per individual, so any requested target that doesn't
     * cover its whole session's listener set gets split off into its own session first, leaving the
     * rest of the group's position untouched.
     */
    fun seek(targets: Set<UUID>, tick: Int): Set<PlaybackSession> {
        val bySession = LinkedHashMap<PlaybackSession, MutableSet<UUID>>()
        for (uuid in targets) {
            val session = sessionsByTarget[uuid] ?: continue
            bySession.getOrPut(session) { LinkedHashSet() } += uuid
        }
        val affected = LinkedHashSet<PlaybackSession>()
        for ((session, uuids) in bySession) {
            val target = if (uuids.size == session.targets.size) session else splitOff(session, uuids)
            applySeek(target, tick.coerceIn(0, target.song.totalTicks))
            affected += target
        }
        return affected
    }

    /** Carves [uuids] out of [session] into a brand-new session at the same playback position, so seeking them doesn't move the listeners left behind. */
    private fun splitOff(session: PlaybackSession, uuids: Set<UUID>): PlaybackSession {
        session.targets.removeAll(uuids)
        val newSession = PlaybackSession(UUID.randomUUID(), uuids.toMutableSet(), session.song, session.mode, session.playlistName, session.songIndex)
        newSession.state = session.state
        newSession.cursorTick = session.cursorTick
        newSession.nextEventIndex = session.nextEventIndex
        newSession.instantNextEventIndex = session.instantNextEventIndex
        for (uuid in uuids) sessionsByTarget[uuid] = newSession
        activeSessions += newSession
        return newSession
    }

    private fun applySeek(session: PlaybackSession, tick: Int) {
        session.cursorTick = tick
        val idx = firstIndexAfterTick(session.song, tick)
        session.nextEventIndex = idx
        session.instantNextEventIndex = idx
        session.instantElapsedMicrosBase = tick.toLong() * MC_TICK_MICROS
        session.instantAnchorNanos = System.nanoTime()
    }

    /** First index in [song]'s tick-ordered arrays whose event fires after [tick] - i.e. everything up to and including [tick] is treated as already played. */
    private fun firstIndexAfterTick(song: ParsedSong, tick: Int): Int {
        var lo = 0
        var hi = song.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (song.tick[mid] <= tick) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Real elapsed song-micros for [session]'s current song, per [PlaybackSession.instantAnchorNanos]. */
    private fun instantElapsedMicros(session: PlaybackSession): Long =
        session.instantElapsedMicrosBase + (System.nanoTime() - session.instantAnchorNanos) / 1000

    private fun tick() {
        val iterator = activeSessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.state != PlaybackState.PLAYING) continue // PAUSED / LOADING: silently skip

            val song = session.song
            while (session.nextEventIndex < song.size && song.tick[session.nextEventIndex] <= session.cursorTick) {
                val idx = session.nextEventIndex
                batchAdd(song.sound[idx], song.vanillaPitch[idx], song.customPitch[idx], song.volume[idx], song.durationMicros[idx])
                session.nextEventIndex++
            }
            if (batchSize > 0) {
                val tickTargets = session.targets.filterTo(LinkedHashSet()) { playModeManager.modeOf(it) != PlayMode.INSTANT }
                flushBatch(tickTargets)
            }

            // Real-time sub-tick dispatch for PlayMode.INSTANT targets, driven by each note's true
            // unquantized onset (song.tickMicros) instead of the tick-locked/collision-spread
            // song.tick above - kept on its own cursor so it stays correct even if a listener
            // switches modes mid-session. The window is anchored to actual elapsed wall-clock time
            // (instantElapsedMicros), not cursorTick*MC_TICK_MICROS, so instant dispatch keeps pace
            // with real time even when the server's actual tick rate falls below 20 TPS and this
            // tick() method itself is being invoked less often than every 50ms - see
            // PlaybackSession.instantAnchorNanos. Cheap to always advance; only schedules when the
            // window actually has something due, and only bothers snapshotting targets when it does.
            val nowMicros = instantElapsedMicros(session)
            val windowEndMicros = nowMicros + MC_TICK_MICROS
            if (session.instantNextEventIndex < song.size && song.tickMicros[session.instantNextEventIndex] < windowEndMicros) {
                val instantTargets = session.targets.filterTo(LinkedHashSet()) { playModeManager.modeOf(it) == PlayMode.INSTANT }
                while (session.instantNextEventIndex < song.size && song.tickMicros[session.instantNextEventIndex] < windowEndMicros) {
                    val idx = session.instantNextEventIndex
                    if (instantTargets.isNotEmpty()) scheduleInstantNote(instantTargets, song, idx, nowMicros)
                    session.instantNextEventIndex++
                }
            }

            session.cursorTick++

            if (session.mode == SessionMode.PLAYLIST && !session.prefetched &&
                song.totalTicks - session.cursorTick <= PREFETCH_LEAD_TICKS
            ) {
                session.prefetched = true
                prefetchNextSong(session)
            }

            if (session.cursorTick > song.totalTicks) {
                if (session.mode == SessionMode.SINGLE_SONG) {
                    endSessionViaIterator(session, iterator)
                } else {
                    loadNextPlaylistSong(session) { endSessionViaIterator(session, iterator) }
                }
            }
        }
    }

    private fun batchAdd(slot: InstrumentSlot, vanillaPitch: Float, customPitch: Float, volume: Float, durationMicros: Long) {
        // Dedupe key is (slot, vanillaPitch): slot already distinguishes octave bucket, and within
        // a bucket vanillaPitch and customPitch move together (both derived from the same note),
        // so this can't merge two notes that would actually use different customPitch values.
        for (i in 0 until batchSize) {
            if (batchSlot[i] === slot && batchVanillaPitch[i] == vanillaPitch) {
                if (volume > batchVolume[i]) {
                    batchVolume[i] = volume
                    batchDurationMicros[i] = durationMicros
                }
                return
            }
        }
        if (batchSlot.size <= batchSize) {
            val newSize = batchSlot.size * 2
            batchSlot = batchSlot.copyOf(newSize)
            batchVanillaPitch = batchVanillaPitch.copyOf(newSize)
            batchCustomPitch = batchCustomPitch.copyOf(newSize)
            batchVolume = batchVolume.copyOf(newSize)
            batchDurationMicros = batchDurationMicros.copyOf(newSize)
        }
        batchSlot[batchSize] = slot
        batchVanillaPitch[batchSize] = vanillaPitch
        batchCustomPitch[batchSize] = customPitch
        batchVolume[batchSize] = volume
        batchDurationMicros[batchSize] = durationMicros
        batchSize++
    }

    private fun flushBatch(targets: Set<UUID>) {
        if (batchSize > MAX_NOTES_PER_TICK) {
            val order = (0 until batchSize).sortedByDescending { batchVolume[it] }
            for (i in 0 until MAX_NOTES_PER_TICK) {
                val idx = order[i]
                playToTargets(targets, batchSlot[idx]!!, batchVanillaPitch[idx], batchCustomPitch[idx], batchVolume[idx], batchDurationMicros[idx])
            }
        } else {
            for (i in 0 until batchSize) {
                playToTargets(targets, batchSlot[i]!!, batchVanillaPitch[i], batchCustomPitch[i], batchVolume[i], batchDurationMicros[i])
            }
        }
        batchSize = 0
    }

    private fun playToTargets(targets: Set<UUID>, slot: InstrumentSlot, vanillaPitch: Float, customPitch: Float, volume: Float, durationMicros: Long) {
        // Resolved once per note (not per target) - the active soundpack can't change mid-flush.
        val customKey = soundPackManager.resolve(slot)
        val releaseKey = customKey?.let { soundPackManager.resolveRelease(slot, durationMicros.coerceAtLeast(0) / 1000) }
        val voice = allocateVoice(customKey ?: slot.vanilla, slot, durationMicros)
        val locations = LinkedHashMap<Player, Location>(targets.size)
        for (uuid in targets) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val location = player.location
            locations[player] = location
            if (customKey != null) {
                player.playSound(location, customKey, voice.category, volume, customPitch)
            } else {
                player.playSound(location, slot.vanilla, voice.category, volume, vanillaPitch)
            }
        }
        if (locations.isNotEmpty()) scheduleRelease(voice, 0, locations, customKey, slot.vanilla, releaseKey, customPitch, volume)
    }

    /**
     * Reserves a "voice" - the (sound identity, category) pair a note will actually play on, plus
     * whether/when it needs an explicit stopSound cutoff - the core of treating this like a
     * polyphonic virtual instrument instead of one shared channel per instrument:
     *
     * 1. Category selection prefers one not currently owned by another still-ringing note that
     *    uses the exact same sound identity (same custom sound key, or - for vanilla fallback -
     *    same [InstrumentSlot.vanilla], which Minecraft's 16 note block timbres share across an
     *    entire GM instrument *family*, not just one instrument). Falls back to plain round-robin
     *    only once every category is already claimed for that sound (dense chords/runs exceeding
     *    [ROTATING_CATEGORIES]'s size) - a rare, graceful degradation rather than a bug.
     * 2. A cutoff is only ever scheduled for [InstrumentSlot.needsExplicitCutoff] slots - Minecraft's
     *    stopSound has no fade, so it's only worth an instant hard silence for instruments that
     *    would otherwise stay stuck ringing at a flat, undecayed volume for their whole multi-second
     *    capture (organ/strings/brass/pad/...). Everything else's own recorded decay/release tail
     *    ends it naturally, like a sampler's one-shot voice - trying to hard-cut *those* on every
     *    note's real MIDI duration (most notes, most of the time) is what made playback sound
     *    constantly "choppy" rather than actually more precise. Also skipped whenever the duration
     *    is unknown or already at least as long as the sample.
     * 3. [scheduleRelease] re-checks ownership before actually calling stopSound, so a stale/late
     *    cutoff can never reach out and silence a *different*, newer note that has since taken
     *    over the same category+sound slot - that cross-talk (one track's release cutting off an
     *    unrelated note, worse the more concurrent notes/tracks there are) was the actual source
     *    of the "choppy" playback this replaces.
     */
    private fun allocateVoice(soundIdentity: Any, slot: InstrumentSlot, durationMicros: Long): Voice {
        val category = allocateCategory(soundIdentity)
        val voiceId = nextVoiceId++
        voiceOwners[VoiceKey(soundIdentity, category)] = voiceId
        val naturalMicros = (slot.naturalSampleSeconds() * 1_000_000).toLong()
        val explicitStop = slot.needsExplicitCutoff() && durationMicros in 0 until naturalMicros
        val releaseDelayMillis = (if (explicitStop) durationMicros else naturalMicros) / 1000
        return Voice(soundIdentity, category, voiceId, explicitStop, releaseDelayMillis.coerceAtLeast(0))
    }

    private fun allocateCategory(soundIdentity: Any): SoundCategory {
        for (i in ROTATING_CATEGORIES.indices) {
            val idx = (categoryCursor + i) % ROTATING_CATEGORIES.size
            val category = ROTATING_CATEGORIES[idx]
            if (!voiceOwners.containsKey(VoiceKey(soundIdentity, category))) {
                categoryCursor = (idx + 1) % ROTATING_CATEGORIES.size
                return category
            }
        }
        val category = ROTATING_CATEGORIES[categoryCursor]
        categoryCursor = (categoryCursor + 1) % ROTATING_CATEGORIES.size
        return category
    }

    /**
     * Releases [voice]'s bookkeeping after [extraDelayMillis] + its own release delay, cutting the
     * sound short only if it's still the current owner and actually needs one - see [allocateVoice].
     * When [releaseKey] is available (the active soundpack has a release-tail sample for this slot,
     * see [SoundPackManager.resolveRelease]/`GmNames.RELEASE_*_SECONDS`), a short decaying tail is
     * triggered at the exact same instant as the hard `stopSound`, masking its zero-fade artifact -
     * it's short and self-terminating so nothing ever needs to stop *it*, hence no voice/category
     * bookkeeping for it (see [allocateVoice]'s doc for how voice identity works).
     *
     * Runs on [instantExecutor]'s dedicated thread, never the main thread - like
     * [scheduleInstantNote], it only ever reads the pre-captured [locations] snapshot (built on the
     * main thread by both call sites), never touches live [Player] state itself.
     */
    private fun scheduleRelease(
        voice: Voice,
        extraDelayMillis: Long,
        locations: Map<Player, Location>,
        customKey: String?,
        vanillaSound: Sound,
        releaseKey: String?,
        releasePitch: Float,
        releaseVolume: Float,
    ) {
        val executor = instantExecutor ?: return
        val voiceKey = VoiceKey(voice.soundIdentity, voice.category)
        executor.schedule({
            if (voiceOwners.remove(voiceKey, voice.id) && voice.explicitStop) {
                for ((player, location) in locations) {
                    if (!player.isOnline) continue
                    try {
                        if (customKey != null) {
                            player.stopSound(customKey, voice.category)
                        } else {
                            player.stopSound(vanillaSound, voice.category)
                        }
                    } catch (_: Exception) {
                        // Best-effort, same reasoning as scheduleInstantNote's playSound try/catch.
                    }
                    if (releaseKey != null) {
                        try {
                            player.playSound(location, releaseKey, voice.category, releaseVolume, releasePitch)
                        } catch (_: Exception) {
                            // Best-effort, same reasoning as scheduleInstantNote's playSound try/catch.
                        }
                    }
                }
            }
        }, extraDelayMillis + voice.releaseDelayMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Dispatches one note for [PlayMode.INSTANT] targets at its true sub-tick onset rather than
     * waiting for the next server tick. Everything Bukkit-touching that the eventual send needs -
     * the [org.bukkit.entity.Player] references and their current location - is captured here on
     * the main thread; the scheduled callback below only reads that already-captured snapshot.
     *
     * Calling [org.bukkit.entity.Player.playSound] from [instantExecutor]'s thread is outside
     * Paper's documented thread-safety contract for [org.bukkit.entity.Player]. It works in
     * practice because it only ever enqueues an outbound packet write - the same class of
     * operation Minecraft's own networking code performs off the main thread - but a future
     * server version could tighten that, hence the defensive try/catch: one dropped note should
     * never surface as an error, just a missed sound.
     */
    private fun scheduleInstantNote(targets: Set<UUID>, song: ParsedSong, idx: Int, nowMicros: Long) {
        val executor = instantExecutor ?: return
        val players = targets.mapNotNull { Bukkit.getPlayer(it) }
        if (players.isEmpty()) return
        val locations = players.associateWith { it.location }
        val slot = song.sound[idx]
        val customKey = soundPackManager.resolve(slot)
        val vanillaPitch = song.vanillaPitch[idx]
        val customPitch = song.customPitch[idx]
        val volume = song.volume[idx]
        val durationMicros = song.durationMicros[idx]
        val releaseKey = customKey?.let { soundPackManager.resolveRelease(slot, durationMicros.coerceAtLeast(0) / 1000) }
        val voice = allocateVoice(customKey ?: slot.vanilla, slot, durationMicros)
        // Notes already due (song.tickMicros[idx] <= nowMicros, e.g. right after a lag spike) fire
        // with ~0 delay instead of waiting for a false "next tick" - see instantElapsedMicros.
        val delayMillis = (song.tickMicros[idx] - nowMicros).coerceAtLeast(0) / 1000

        executor.schedule({
            for (player in players) {
                if (!player.isOnline) continue
                val location = locations.getValue(player)
                try {
                    if (customKey != null) {
                        player.playSound(location, customKey, voice.category, volume, customPitch)
                    } else {
                        player.playSound(location, slot.vanilla, voice.category, volume, vanillaPitch)
                    }
                } catch (_: Exception) {
                    // Best-effort: a mid-flight disconnect or send failure shouldn't kill the scheduler thread.
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
        scheduleRelease(voice, delayMillis, locations, customKey, slot.vanilla, releaseKey, customPitch, volume)
    }

    private fun prefetchNextSong(session: PlaybackSession) {
        val playlistName = session.playlistName ?: return
        val playlist = playlistManager.get(playlistName) ?: return
        if (playlist.songs.isEmpty()) return
        val nextIndex = (session.songIndex + 1) % playlist.songs.size
        val file = File(midiFolder, playlist.songs[nextIndex])
        if (file.isFile) songCache.get(file)
    }

    private fun loadNextPlaylistSong(session: PlaybackSession, onExhausted: () -> Unit) {
        val playlistName = session.playlistName ?: return onExhausted()
        val playlist = playlistManager.get(playlistName)
        if (playlist == null || playlist.songs.isEmpty()) {
            onExhausted()
            return
        }

        val songs = playlist.songs
        var index = session.songIndex
        var attempts = 0
        while (attempts < songs.size) {
            index = (index + 1) % songs.size
            attempts++
            val filename = songs[index]
            val file = File(midiFolder, filename)
            if (!file.isFile) {
                plugin.logger.warning("Playlist '$playlistName': skipping missing song '$filename'")
                continue
            }
            session.songIndex = index
            session.state = PlaybackState.LOADING
            session.prefetched = false
            attachNextSong(session, file, playlistName)
            return
        }
        onExhausted()
    }

    private fun attachNextSong(session: PlaybackSession, file: File, playlistName: String) {
        val future = songCache.get(file)
        if (future.isDone) {
            // Cache hit (or the prefetch already finished) - apply with zero stall, no thread hop.
            try {
                applyNextSong(session, future.join())
            } catch (e: Exception) {
                plugin.logger.warning("Playlist '$playlistName': failed to parse '${file.name}': ${e.message}")
                retryLoadNextPlaylistSongNextTick(session)
            }
            return
        }
        future.whenCompleteAsync({ song, throwable ->
            if (session !in activeSessions) return@whenCompleteAsync // stopped while we were waiting
            if (throwable != null || song == null) {
                plugin.logger.warning("Playlist '$playlistName': failed to parse '${file.name}': ${throwable?.message}")
                retryLoadNextPlaylistSongNextTick(session)
            } else {
                applyNextSong(session, song)
            }
        }, mainThreadExecutor)
    }

    /**
     * Defers a playlist failure-retry to the next server tick. This is required (not just an
     * optimization) whenever the retry could otherwise be triggered synchronously from within
     * [tick]'s own iterator walk (via the `future.isDone` fast path above) - [endSessionByRemoval]
     * mutates [activeSessions] directly, which would throw ConcurrentModificationException if it
     * ran nested inside that iterator's active loop.
     */
    private fun retryLoadNextPlaylistSongNextTick(session: PlaybackSession) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (session in activeSessions) loadNextPlaylistSong(session) { endSessionByRemoval(session) }
        })
    }

    private fun applyNextSong(session: PlaybackSession, song: ParsedSong) {
        session.song = song
        session.cursorTick = 0
        session.nextEventIndex = 0
        session.instantNextEventIndex = 0
        session.instantAnchorNanos = System.nanoTime()
        session.instantElapsedMicrosBase = 0L
        session.prefetched = false
        session.state = PlaybackState.PLAYING
    }

    private fun cleanupTargets(session: PlaybackSession) {
        for (uuid in session.targets) sessionsByTarget.remove(uuid, session)
    }

    private fun endSessionViaIterator(session: PlaybackSession, iterator: MutableIterator<PlaybackSession>) {
        iterator.remove()
        cleanupTargets(session)
    }

    private fun endSessionByRemoval(session: PlaybackSession) {
        activeSessions.remove(session)
        cleanupTargets(session)
    }
}
