package io.github.jwyoon1220.openMCMelody.midi

import kotlin.math.pow

/**
 * Maps General MIDI program numbers / percussion key numbers to note block [InstrumentSlot]s
 * and pitches. Used only during [MidiParser] parsing, never on the playback hot path. Resolving
 * an [InstrumentSlot] to an actual playable sound (vanilla or soundpack-overridden) happens later,
 * at playback time, in `PlaybackManager`.
 */
object GmInstrumentMap {

    // F#4 (MIDI note 66) is the note block's natural/unpowered pitch (pitch = 1.0).
    private const val BASE_MIDI_NOTE = 66

    /**
     * Converts a MIDI note number to a note-block pitch in [0.5, 2.0].
     * Notes outside the natural +/-12 semitone window are octave-wrapped (not clamped),
     * so distant notes keep their pitch class instead of collapsing to the range boundary.
     */
    fun midiNoteToPitch(midiNote: Int): Float {
        var note = midiNote
        while (note - BASE_MIDI_NOTE > 12) note -= 12
        while (note - BASE_MIDI_NOTE < -12) note += 12
        return 2.0.pow((note - BASE_MIDI_NOTE) / 12.0).toFloat()
    }

    /** Resolves a melodic (non-percussion) note-on to its own GM instrument slot + pitch. */
    fun melodic(program: Int, note: Int): Pair<InstrumentSlot, Float> {
        return InstrumentSlot.melodic(program) to midiNoteToPitch(note)
    }

    private val percussionSlots: Map<Int, InstrumentSlot> = buildMap {
        for (n in intArrayOf(35, 36, 41, 43, 45, 47, 48, 50)) put(n, InstrumentSlot.BASEDRUM)
        for (n in intArrayOf(37, 38, 39, 40)) put(n, InstrumentSlot.SNARE)
        for (n in intArrayOf(42, 44, 46, 49, 51, 52, 55, 57, 59)) put(n, InstrumentSlot.HAT)
        put(56, InstrumentSlot.COW_BELL)
    }

    /** Resolves a MIDI channel-10 (percussion) note-on to an instrument slot; pitch is always neutral. */
    fun percussion(note: Int): Pair<InstrumentSlot, Float> {
        return (percussionSlots[note] ?: InstrumentSlot.HAT) to 1.0f
    }
}
