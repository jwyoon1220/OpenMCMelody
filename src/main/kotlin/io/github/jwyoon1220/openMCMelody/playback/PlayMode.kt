package io.github.jwyoon1220.openMCMelody.playback

/**
 * Per-player choice of how [PlaybackManager] delivers their notes.
 *
 * [TICK] is the default: notes fire in lockstep with the server's 1-tick (50ms) heartbeat, so
 * anything closer together than that in the source MIDI gets nudged apart onto separate ticks
 * (see [io.github.jwyoon1220.openMCMelody.midi.MidiParser]).
 *
 * [INSTANT] dispatches each note's true, unquantized onset via a dedicated real-time scheduler
 * instead of waiting for the next server tick, so it isn't bound by the 50ms floor - at the cost of
 * sending packets off the main thread, which is outside Paper's normal thread-safety contract for
 * [org.bukkit.entity.Player] calls (see [PlaybackManager.scheduleInstantNote]).
 */
enum class PlayMode {
    TICK,
    INSTANT,
}
