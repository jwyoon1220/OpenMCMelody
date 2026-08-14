package io.github.jwyoon1220.openMCMelody.playback

import io.github.jwyoon1220.openMCMelody.midi.ParsedSong
import java.util.UUID

class PlaybackSession(
    val id: UUID,
    val targets: MutableSet<UUID>,
    var song: ParsedSong,
    val mode: SessionMode,
    var playlistName: String? = null,
    var songIndex: Int = 0,
) {
    var cursorTick: Int = 0
    var nextEventIndex: Int = 0
    /** Separate cursor into [song]'s parallel arrays for [PlayMode.INSTANT] targets - see [PlaybackManager.tick]. */
    var instantNextEventIndex: Int = 0
    /**
     * Wall-clock anchor for [PlayMode.INSTANT] timing, kept independent of [cursorTick] so instant
     * dispatch tracks real elapsed time instead of "one tick's worth per [PlaybackManager.tick]
     * call" - the latter drifts arbitrarily far behind real time once the server's actual tick rate
     * drops below 20 TPS. Elapsed song-micros at any instant = [instantElapsedMicrosBase] +
     * (System.nanoTime() - [instantAnchorNanos]) / 1000; see [PlaybackManager.instantElapsedMicros].
     */
    var instantAnchorNanos: Long = System.nanoTime()
    /** Frozen elapsed song-micros as of the last pause/resume/song-switch anchor reset. */
    var instantElapsedMicrosBase: Long = 0L
    var state: PlaybackState = PlaybackState.PLAYING
    /** Whether the next playlist song has already been speculatively warmed into [io.github.jwyoon1220.openMCMelody.midi.SongCache]. */
    var prefetched: Boolean = false
}
