package io.github.jwyoon1220.openMCMelody.midi

import org.bukkit.Sound

/**
 * One slot per (General MIDI melodic instrument, octave bucket) pair, plus a handful of
 * percussion slots. A soundpack (see the `soundpack` package) may override any subset by [key];
 * anything not overridden falls back to [vanilla], one of Minecraft's 16 native note block sounds.
 *
 * Splitting each instrument into per-octave slots (instead of one slot covering the whole
 * playable range) exists so a custom soundpack can supply a distinct sample per octave - a single
 * sample pitch-shifted across, say, a full 88-key piano range starts sounding artificial the
 * farther a note is from the sample's native pitch, whereas a sample per ~12-semitone bucket only
 * ever needs to shift by at most half an octave.
 *
 * Not an enum - over a thousand hand-written constants would be unwieldy. Instances are vended
 * from a single interned array/map instead, so reference equality (`===`, used by
 * `PlaybackManager`'s per-tick note dedup) stays valid: the same (program, octave) or percussion
 * note always resolves to the exact same object.
 */
class InstrumentSlot private constructor(val key: String, val vanilla: Sound) {

    companion object {
        // Vanilla Sound fallback per GM instrument family (program ranges of 8) - unchanged from
        // the original single-slot-per-instrument mapping; vanilla note blocks only ever have one
        // sample regardless of octave, so every octave bucket of the same instrument shares it.
        private val FAMILY_VANILLA = arrayOf(
            Sound.BLOCK_NOTE_BLOCK_HARP, // 0-7 Piano
            Sound.BLOCK_NOTE_BLOCK_BELL, // 8-15 Chromatic Percussion
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, // 16-23 Organ
            Sound.BLOCK_NOTE_BLOCK_GUITAR, // 24-31 Guitar
            Sound.BLOCK_NOTE_BLOCK_BASS, // 32-39 Bass
            Sound.BLOCK_NOTE_BLOCK_PLING, // 40-47 Strings
            Sound.BLOCK_NOTE_BLOCK_CHIME, // 48-55 Ensemble/Choir
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, // 56-63 Brass
            Sound.BLOCK_NOTE_BLOCK_FLUTE, // 64-71 Reed
            Sound.BLOCK_NOTE_BLOCK_FLUTE, // 72-79 Pipe
            Sound.BLOCK_NOTE_BLOCK_BIT, // 80-87 Synth Lead
            Sound.BLOCK_NOTE_BLOCK_CHIME, // 88-95 Synth Pad
            Sound.BLOCK_NOTE_BLOCK_BELL, // 96-103 Synth Effects
            Sound.BLOCK_NOTE_BLOCK_BANJO, // 104-111 Ethnic
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, // 112-119 Percussive
            Sound.BLOCK_NOTE_BLOCK_HAT, // 120-127 Sound Effects
        )

        // MELODIC[program][octaveBucket]
        private val MELODIC: Array<Array<InstrumentSlot>> = Array(128) { program ->
            Array(GmNames.OCTAVE_COUNT) { octave ->
                InstrumentSlot(GmNames.octaveSlotKey(GmNames.MELODIC[program], octave), FAMILY_VANILLA[program / 8])
            }
        }

        val BASEDRUM = InstrumentSlot("basedrum", Sound.BLOCK_NOTE_BLOCK_BASEDRUM)
        val SNARE = InstrumentSlot("snare", Sound.BLOCK_NOTE_BLOCK_SNARE)
        val HAT = InstrumentSlot("hat", Sound.BLOCK_NOTE_BLOCK_HAT)
        val COW_BELL = InstrumentSlot("cow_bell", Sound.BLOCK_NOTE_BLOCK_COW_BELL)

        val entries: List<InstrumentSlot> = MELODIC.flatMap { it.toList() } + listOf(BASEDRUM, SNARE, HAT, COW_BELL)

        private val BY_KEY: Map<String, InstrumentSlot> = entries.associateBy { it.key }

        /** Which octave bucket MIDI note [note] falls into - see [GmNames.octaveOf]. */
        fun octaveOf(note: Int): Int = GmNames.octaveOf(note)

        /** The reference MIDI note (bucket center) a sample for octave bucket [octave] is rendered/pitched at. */
        fun octaveCenterNote(octave: Int): Int = GmNames.octaveCenterNote(octave)

        /** The slot for GM melodic program [program] (0-127) in the given octave bucket. */
        fun melodic(program: Int, octave: Int): InstrumentSlot =
            MELODIC[program.coerceIn(0, 127)][octave.coerceIn(0, GmNames.OCTAVE_COUNT - 1)]

        fun byKey(key: String): InstrumentSlot? = BY_KEY[key]
    }
}
