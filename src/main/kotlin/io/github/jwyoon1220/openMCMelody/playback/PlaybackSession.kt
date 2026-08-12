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
    var state: PlaybackState = PlaybackState.PLAYING
    /** Whether the next playlist song has already been speculatively warmed into [io.github.jwyoon1220.openMCMelody.midi.SongCache]. */
    var prefetched: Boolean = false
}
