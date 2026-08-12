package io.github.jwyoon1220.openMCMelody.midi

/**
 * A fully-resolved, playback-ready song: every MIDI note-on event has already been converted to
 * a Minecraft-tick offset, an [InstrumentSlot], a volume, and *two* pitches - [vanillaPitch] (for
 * when no soundpack overrides the slot) and [customPitch] (for when one does, relative to that
 * slot's own octave bucket - see [InstrumentSlot]/[GmInstrumentMap]). The slot and pitch to
 * actually use are resolved at playback time, not here, since the active soundpack can change
 * while a song is cached/playing.
 *
 * Stored as parallel primitive arrays rather than a `List<NoteEvent>` of boxed objects,
 * since a dense song can carry tens of thousands of events and [SongCache] may hold
 * several parsed songs at once.
 */
class ParsedSong(
    val sourceFileName: String,
    val tick: IntArray,
    val sound: Array<InstrumentSlot>,
    val vanillaPitch: FloatArray,
    val customPitch: FloatArray,
    val volume: FloatArray,
    val totalTicks: Int,
) {
    val size: Int get() = tick.size
}
