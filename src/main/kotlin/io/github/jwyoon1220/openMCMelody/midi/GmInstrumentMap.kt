package io.github.jwyoon1220.openMCMelody.midi

import kotlin.math.pow

/**
 * The result of resolving one MIDI note-on: which slot to play, and two different pitch values
 * for it - which one is actually used is decided at *playback* time (the active soundpack can
 * change while a song is cached), not here:
 * - [vanillaPitch]: relative to a single global reference note, octave-wrapped into the vanilla
 *   note block's native [0.5, 2.0] range. Used when no soundpack overrides this slot.
 * - [customPitch]: relative to the *center of this slot's own octave bucket* (see
 *   [InstrumentSlot.octaveCenterNote]) - always within about half an octave, since a custom
 *   soundpack sample only needs to cover its own bucket, not the whole playable range. Used when
 *   a soundpack does override this slot.
 */
data class NoteResolution(val slot: InstrumentSlot, val vanillaPitch: Float, val customPitch: Float)

/**
 * Maps General MIDI program numbers / percussion key numbers to note block [InstrumentSlot]s
 * and pitches. Used only during [MidiParser] parsing, never on the playback hot path.
 */
object GmInstrumentMap {

    // F#4 (MIDI note 66) is the note block's natural/unpowered pitch (pitch = 1.0).
    private const val BASE_MIDI_NOTE = 66

    /**
     * Converts a MIDI note number to a vanilla note-block pitch in [0.5, 2.0].
     * Notes outside the natural +/-12 semitone window are octave-wrapped (not clamped),
     * so distant notes keep their pitch class instead of collapsing to the range boundary.
     */
    private fun vanillaPitch(midiNote: Int): Float {
        var note = midiNote
        while (note - BASE_MIDI_NOTE > 12) note -= 12
        while (note - BASE_MIDI_NOTE < -12) note += 12
        return 2.0.pow((note - BASE_MIDI_NOTE) / 12.0).toFloat()
    }

    /** Resolves a melodic (non-percussion) note-on to its own (instrument, octave) slot + both pitches. */
    fun melodic(program: Int, note: Int): NoteResolution {
        val octave = InstrumentSlot.octaveOf(note)
        val slot = InstrumentSlot.melodic(program, octave)
        val center = InstrumentSlot.octaveCenterNote(octave)
        val customPitch = 2.0.pow((note - center) / 12.0).toFloat()
        return NoteResolution(slot, vanillaPitch(note), customPitch)
    }

    private val percussionSlots: Map<Int, InstrumentSlot> = buildMap {
        for (n in intArrayOf(35, 36, 41, 43, 45, 47, 48, 50)) put(n, InstrumentSlot.BASEDRUM)
        for (n in intArrayOf(37, 38, 39, 40)) put(n, InstrumentSlot.SNARE)
        for (n in intArrayOf(42, 44, 46, 49, 51, 52, 55, 57, 59)) put(n, InstrumentSlot.HAT)
        put(56, InstrumentSlot.COW_BELL)
    }

    /** Resolves a MIDI channel-10 (percussion) note-on to an instrument slot; pitch is always neutral. */
    fun percussion(note: Int): NoteResolution {
        val slot = percussionSlots[note] ?: InstrumentSlot.HAT
        return NoteResolution(slot, 1.0f, 1.0f)
    }
}
