package io.github.jwyoon1220.openMCMelody.playback

enum class PlaybackState {
    PLAYING,
    PAUSED,
    /** Waiting on an async parse (e.g. a playlist advancing to a not-yet-cached song). Produces silence. */
    LOADING,
}

enum class SessionMode { SINGLE_SONG, PLAYLIST }
