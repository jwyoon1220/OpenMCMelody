package io.github.jwyoon1220.openMCMelody.midi

/**
 * Plain General MIDI naming data - deliberately has ZERO Bukkit dependency (no `org.bukkit.Sound`,
 * nothing that touches a live server registry). [InstrumentSlot] wraps this with vanilla-sound
 * fallbacks for use inside the plugin; the standalone soundfont-extraction subprocess (see the
 * `soundfont` package, which runs as a bare `java` process outside any Paper server and therefore
 * cannot load Bukkit registry-backed classes at all) uses this object directly.
 */
object GmNames {

    // Official General MIDI Level 1 instrument names, program 0-127, as snake_case keys
    // (used as pack.yml field names and as the resource pack sound event name).
    val MELODIC: Array<String> = arrayOf(
        "acoustic_grand_piano", "bright_acoustic_piano", "electric_grand_piano", "honky_tonk_piano",
        "electric_piano_1", "electric_piano_2", "harpsichord", "clavinet",
        "celesta", "glockenspiel", "music_box", "vibraphone",
        "marimba", "xylophone", "tubular_bells", "dulcimer",
        "drawbar_organ", "percussive_organ", "rock_organ", "church_organ",
        "reed_organ", "accordion", "harmonica", "tango_accordion",
        "acoustic_guitar_nylon", "acoustic_guitar_steel", "electric_guitar_jazz", "electric_guitar_clean",
        "electric_guitar_muted", "overdriven_guitar", "distortion_guitar", "guitar_harmonics",
        "acoustic_bass", "electric_bass_finger", "electric_bass_pick", "fretless_bass",
        "slap_bass_1", "slap_bass_2", "synth_bass_1", "synth_bass_2",
        "violin", "viola", "cello", "contrabass",
        "tremolo_strings", "pizzicato_strings", "orchestral_harp", "timpani",
        "string_ensemble_1", "string_ensemble_2", "synth_strings_1", "synth_strings_2",
        "choir_aahs", "voice_oohs", "synth_voice", "orchestra_hit",
        "trumpet", "trombone", "tuba", "muted_trumpet",
        "french_horn", "brass_section", "synth_brass_1", "synth_brass_2",
        "soprano_sax", "alto_sax", "tenor_sax", "baritone_sax",
        "oboe", "english_horn", "bassoon", "clarinet",
        "piccolo", "flute", "recorder", "pan_flute",
        "blown_bottle", "shakuhachi", "whistle", "ocarina",
        "lead_square", "lead_sawtooth", "lead_calliope", "lead_chiff",
        "lead_charang", "lead_voice", "lead_fifths", "lead_bass_and_lead",
        "pad_new_age", "pad_warm", "pad_polysynth", "pad_choir",
        "pad_bowed", "pad_metallic", "pad_halo", "pad_sweep",
        "fx_rain", "fx_soundtrack", "fx_crystal", "fx_atmosphere",
        "fx_brightness", "fx_goblins", "fx_echoes", "fx_sci_fi",
        "sitar", "banjo", "shamisen", "koto",
        "kalimba", "bag_pipe", "fiddle", "shanai",
        "tinkle_bell", "agogo", "steel_drums", "woodblock",
        "taiko_drum", "melodic_tom", "synth_drum", "reverse_cymbal",
        "guitar_fret_noise", "breath_noise", "seashore", "bird_tweet",
        "telephone_ring", "helicopter", "applause", "gunshot",
    )

    // GM percussion (channel 10) key -> slot name, for the handful of percussion slots we support.
    val PERCUSSION: Map<Int, String> = mapOf(
        36 to "basedrum",
        38 to "snare",
        42 to "hat",
        56 to "cow_bell",
    )

    // GM program numbers (0-indexed, matching MELODIC above) whose real instrument holds a tone
    // for as long as a performer keeps blowing/bowing/pressing a key - organs, bowed strings, brass,
    // reed/pipe woodwinds, vocal pads, synth leads/pads - as opposed to one that's struck/plucked
    // and decays on its own (piano, guitar, mallets, most "ethnic"/percussive instruments). Used by
    // [io.github.jwyoon1220.openMCMelody.soundfont.SoundFontExtractorMain] to capture a longer
    // sample for these, since Minecraft's playSound has no loop/sustain of its own - a note held
    // longer than the captured clip simply runs out of recorded audio.
    //
    // Deliberately excludes a few instruments that share a GM range with sustaining ones but are
    // themselves struck/plucked: pizzicato_strings(45), orchestral_harp(46), timpani(47) within the
    // strings range, and orchestra_hit(55) within the ensemble range.
    val SUSTAINING_PROGRAMS: Set<Int> = (
        (16..23) + // organ, accordion, harmonica
            (40..44) + // bowed strings: violin, viola, cello, contrabass, tremolo_strings
            (48..54) + // string/synth ensembles + choir_aahs/voice_oohs/synth_voice
            (56..95) + // brass, sax, reed woodwinds, pipe, synth lead, synth pad
            listOf(109, 110, 111) // bag_pipe, fiddle, shanai
        ).toSet()

    fun sustains(program: Int): Boolean = program in SUSTAINING_PROGRAMS

    // Full length (seconds) of a captured sample per [SoundFontExtractorMain] classification - how
    // long a note can ring before it simply runs out of recorded audio. Shared with
    // [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager], which uses these same numbers
    // to decide whether a note's real MIDI duration already fits inside the sample (nothing to do)
    // or needs an explicit stopSound cutoff (duration shorter than the sample) - the two must never
    // drift apart, hence living here rather than being duplicated as a private constant in each.
    const val MELODIC_SAMPLE_SECONDS = 1.8
    const val PERCUSSION_SAMPLE_SECONDS = 1.2
    const val SUSTAINED_SAMPLE_SECONDS = 5.5

    // Release-layer samples: capture only the natural decay-after-note-off phase for
    // SUSTAINING_PROGRAMS, played *alongside* the hard stopSound cutoff (see
    // PlaybackManager.scheduleRelease) to mask its zero-fade artifact, since each is short and
    // self-terminating so nothing ever needs to stopSound it. Multiple checkpoints (not just one)
    // exist because many soundfont patches have an ADSR decay stage that keeps ramping toward the
    // sustain level well past a fraction of a second - a release captured from a single short hold
    // point audibly mismatches the amplitude/timbre of a note actually cut after it settled into
    // sustain. PlaybackManager/SoundPackManager.resolveRelease picks whichever checkpoint's hold
    // length is closest to the real MIDI note's duration, so the splice matches much more closely.
    // Shared between SoundFontExtractorMain (how long to hold/capture each checkpoint) and
    // SoundFontConverter (ffmpeg fade timing must line up with what was actually captured, and
    // pack.yml must record each checkpoint's hold length for PlaybackManager to pick against) - the
    // three must never drift apart, hence living here rather than duplicated per file.
    val RELEASE_HOLD_CHECKPOINTS_SECONDS: List<Double> = listOf(0.15, 1.0, 2.5, 4.5)
    const val RELEASE_TAIL_SECONDS = 1.35 // captured decay length after note-off, same for every checkpoint
    const val RELEASE_FADE_SECONDS = 0.5

    // Each melodic instrument is split into per-octave sample slots (see InstrumentSlot's class
    // doc) - a 12-semitone bucket only ever needs to pitch-shift by at most half an octave,
    // unlike one sample stretched across the whole playable range (e.g. 88 piano keys).
    const val OCTAVE_SIZE = 12
    const val OCTAVE_COUNT = 11 // covers the full MIDI note range 0-127

    fun octaveOf(note: Int): Int = (note.coerceIn(0, 127) / OCTAVE_SIZE).coerceIn(0, OCTAVE_COUNT - 1)

    fun octaveCenterNote(octave: Int): Int = octave * OCTAVE_SIZE + OCTAVE_SIZE / 2

    fun octaveSlotKey(instrumentKey: String, octave: Int): String = "${instrumentKey}_oct$octave"

    init {
        check(MELODIC.size == 128) { "Expected 128 GM instrument names, found ${MELODIC.size}" }
    }
}
