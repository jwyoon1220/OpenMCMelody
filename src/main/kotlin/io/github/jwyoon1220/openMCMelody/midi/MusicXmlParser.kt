package io.github.jwyoon1220.openMCMelody.midi

import org.audiveris.proxymusic.Articulations
import org.audiveris.proxymusic.Attributes
import org.audiveris.proxymusic.Backup
import org.audiveris.proxymusic.Direction
import org.audiveris.proxymusic.Fermata
import org.audiveris.proxymusic.Forward
import org.audiveris.proxymusic.MidiInstrument
import org.audiveris.proxymusic.Note
import org.audiveris.proxymusic.Notations
import org.audiveris.proxymusic.Ornaments
import org.audiveris.proxymusic.Pitch
import org.audiveris.proxymusic.ScorePart
import org.audiveris.proxymusic.ScorePartwise
import org.audiveris.proxymusic.Step
import org.audiveris.proxymusic.StartStop
import org.audiveris.proxymusic.Tied
import org.audiveris.proxymusic.TiedType
import org.audiveris.proxymusic.Tremolo
import org.audiveris.proxymusic.TremoloType
import org.audiveris.proxymusic.Unpitched
import org.audiveris.proxymusic.util.Marshalling
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

private const val DEFAULT_TEMPO_US_PER_QUARTER = 500_000.0 // 120 BPM, matches MidiParser's default

// MusicXML dynamics marks -> conventional GM velocity equivalents. MusicXML's own convention treats
// an unmarked passage as implicitly "mf" (unlike MIDI's CC7/CC11, which default to full scale
// because MIDI's convention is "unset means untouched" - see MidiParser), so DEFAULT_VELOCITY is used
// whenever no dynamics mark/attribute is in effect, not 1.0.
private val DYNAMICS_VELOCITY = mapOf(
    "ppp" to 16f, "pp" to 33f, "p" to 49f, "mp" to 64f,
    "mf" to 80f, "f" to 96f, "ff" to 112f, "fff" to 126f,
)
private const val DEFAULT_VELOCITY = 80f // mf

// Articulation marks that meaningfully change how a note sounds (as opposed to purely visual ones
// like fingering/bowing) - applied multiplicatively so multiple marks on one note compound.
private val ARTICULATION_VELOCITY_MULTIPLIER = mapOf(
    "accent" to 1.25f, "strong-accent" to 1.4f, "soft-accent" to 1.1f,
)
private val ARTICULATION_DURATION_MULTIPLIER = mapOf(
    "staccato" to 0.5, "staccatissimo" to 0.3, "spiccato" to 0.3,
)
private const val FERMATA_DURATION_MULTIPLIER = 2.0

private val DIATONIC_STEPS = listOf(Step.C, Step.D, Step.E, Step.F, Step.G, Step.A, Step.B)

// Major-key circle-of-fifths accidental order: the Nth sharp/flat added to a key signature always
// alters this letter next, regardless of mode - used to spell a diatonically-correct upper/lower
// neighbor tone for trill/mordent/turn ornaments instead of a fixed chromatic step (which would be
// wrong roughly half the time, e.g. C major's trill neighbor is D, not C#).
private val SHARP_ORDER = listOf(Step.F, Step.C, Step.G, Step.D, Step.A, Step.E, Step.B)
private val FLAT_ORDER = listOf(Step.B, Step.E, Step.A, Step.D, Step.G, Step.C, Step.F)

/**
 * Parses a MusicXML score (plain `.musicxml` or zip-compressed `.mxl`) into a [ParsedSong] ready
 * for playback - the same shape [MidiParser] produces, so [SongCache] and
 * [io.github.jwyoon1220.openMCMelody.playback.PlaybackManager] never need to know which format a
 * song came from.
 *
 * Never touches Bukkit API and does no I/O beyond reading [file], so callers are expected to run
 * this off the main thread, same as [MidiParser].
 */
object MusicXmlParser {

    private class PartInfo(val gmProgram: Int)

    private data class TieKey(val partIndex: Int, val voice: String, val midiNote: Int, val percussion: Boolean)

    private data class RawNoteEvent(
        val quarterPos: Double,
        val quarterDuration: Double,
        val voice: String,
        val midiNote: Int,
        val percussion: Boolean,
        val gmProgram: Int,
        val velocity: Float,
        val order: Int,
    )

    private class TempoBreakpoint(val quarterPos: Double, val elapsedUsAtStart: Double, val usPerQuarter: Double)

    fun parse(file: File): ParsedSong {
        val score = unmarshal(file)
        val partInfo = buildPartInfo(score)

        val tempoEvents = ArrayList<Pair<Double, Double>>() // quarterPos, bpm
        val noteEvents = ArrayList<RawNoteEvent>()
        var order = 0

        score.part.forEachIndexed { partIndex, part ->
            val info = partInfo[part.id as? ScorePart] ?: PartInfo(gmProgram = 0)
            var divisionsPerQuarter = 1.0
            var cursorDivisions = 0.0
            var lastNoteOnsetDivisions = 0.0
            var currentVelocity = DEFAULT_VELOCITY
            // Transposing instruments (Bb clarinet, F horn, etc.) notate written pitch, not the
            // pitch that actually sounds - <transpose> gives the semitone offset to recover the
            // concert/sounding pitch. Without this, every transposing part in a multi-instrument
            // score plays at the wrong pitch relative to the others (dissonant when combined),
            // even though each part's own relative motion still sounds "correct" in isolation.
            var transposeSemitones = 0
            var currentFifths = 0
            val openTies = HashMap<TieKey, Int>()

            for (measure in part.measure) {
                for (item in measure.noteOrBackupOrForward) {
                    when (item) {
                        is Attributes -> {
                            val divisions = item.divisions
                            if (divisions != null && divisions.toDouble() > 0.0) {
                                divisionsPerQuarter = divisions.toDouble()
                            }
                            val transpose = item.transpose.firstOrNull()
                            if (transpose != null) {
                                val chromatic = transpose.chromatic?.toInt() ?: 0
                                val octaveChange = (transpose.octaveChange?.toInt() ?: 0) * 12
                                transposeSemitones = chromatic + octaveChange
                            }
                            val fifths = item.key.firstOrNull()?.fifths
                            if (fifths != null) currentFifths = fifths.toInt()
                        }

                        is Direction -> {
                            val sound = item.sound
                            if (sound?.tempo != null && sound.tempo.toDouble() > 0.0) {
                                val quarterPos = cursorDivisions / divisionsPerQuarter
                                tempoEvents.add(quarterPos to sound.tempo.toDouble())
                            }
                            val soundDynamics = sound?.dynamics
                            if (soundDynamics != null) {
                                currentVelocity = (soundDynamics.toDouble() / 100.0 * DEFAULT_VELOCITY.toDouble()).toFloat()
                            } else {
                                for (directionType in item.directionType) {
                                    for (dynamics in directionType.dynamics) {
                                        val markName = dynamics.pOrPpOrPpp.firstOrNull()?.name?.localPart
                                        if (markName != null) {
                                            currentVelocity = DYNAMICS_VELOCITY[markName] ?: DEFAULT_VELOCITY
                                        }
                                    }
                                }
                            }
                        }

                        is Backup -> cursorDivisions -= (item.duration?.toDouble() ?: 0.0)
                        is Forward -> cursorDivisions += (item.duration?.toDouble() ?: 0.0)

                        is Note -> {
                            val durationDivisions = item.duration?.toDouble() ?: 0.0
                            val isChordMember = item.chord != null
                            val onsetDivisions = if (isChordMember) lastNoteOnsetDivisions else cursorDivisions
                            if (!isChordMember) {
                                lastNoteOnsetDivisions = onsetDivisions
                                cursorDivisions += durationDivisions
                            }

                            if (item.rest != null) continue

                            // Percussion-ness is decided by whether *this note* is authored as
                            // <unpitched>, never by the part's declared midi-channel: many notation
                            // programs export the whole percussion staff group on channel 10,
                            // including pitched percussion like timpani/mallets that use <pitch> and
                            // a real GM program (e.g. 47 = Timpani) - trusting channel 10 there would
                            // route real pitches through the fixed drum-key map instead of playing
                            // their actual pitch.
                            val percussion = item.unpitched != null
                            val midiNote = when {
                                item.pitch != null -> (pitchToMidiNote(item.pitch) + transposeSemitones).coerceIn(0, 127)
                                item.unpitched != null -> unpitchedToMidiNote(item.unpitched)
                                else -> continue // neither pitched, unpitched, nor rest - malformed, skip
                            }

                            val voice = item.voice ?: "1"
                            var noteVelocity = item.dynamics?.let {
                                (it.toDouble() / 100.0 * DEFAULT_VELOCITY.toDouble()).toFloat()
                            } ?: currentVelocity
                            for (name in articulationNames(item.notations)) {
                                ARTICULATION_VELOCITY_MULTIPLIER[name]?.let { noteVelocity *= it }
                            }

                            val quarterPos = onsetDivisions / divisionsPerQuarter
                            var quarterDuration = durationDivisions / divisionsPerQuarter
                            for (name in articulationNames(item.notations)) {
                                ARTICULATION_DURATION_MULTIPLIER[name]?.let { quarterDuration *= it }
                            }
                            if (hasFermata(item.notations)) quarterDuration *= FERMATA_DURATION_MULTIPLIER

                            val expansion = if (item.pitch != null) {
                                ornamentExpansion(item.notations, item.pitch, currentFifths, transposeSemitones, midiNote, quarterDuration)
                            } else {
                                null
                            }

                            if (expansion != null) {
                                // Ornamented notes (trill/mordent/turn/tremolo) are realized as several
                                // short sub-notes and don't participate in tie-merging - ties landing on
                                // an ornamented note are a rare combination not worth the bookkeeping.
                                var subPos = quarterPos
                                for ((subNote, subDuration) in expansion) {
                                    noteEvents.add(
                                        RawNoteEvent(
                                            quarterPos = subPos,
                                            quarterDuration = subDuration,
                                            voice = voice,
                                            midiNote = subNote,
                                            percussion = percussion,
                                            gmProgram = info.gmProgram,
                                            velocity = noteVelocity,
                                            order = order++,
                                        ),
                                    )
                                    subPos += subDuration
                                }
                            } else {
                                val tiedNotations = item.notations.flatMap { it.tiedOrSlurOrTuplet }.filterIsInstance<Tied>()
                                val tieStart = item.tie.any { it.type == StartStop.START } ||
                                    tiedNotations.any { it.type == TiedType.START }
                                val tieStop = item.tie.any { it.type == StartStop.STOP } ||
                                    tiedNotations.any { it.type == TiedType.STOP }

                                val tieKey = TieKey(partIndex, voice, midiNote, percussion)
                                val openIdx = openTies[tieKey]
                                if (tieStop && openIdx != null) {
                                    // Tie continuation - extend the original note's span instead of emitting a new one.
                                    val original = noteEvents[openIdx]
                                    noteEvents[openIdx] = original.copy(
                                        quarterDuration = (quarterPos + quarterDuration) - original.quarterPos,
                                    )
                                    if (tieStart) openTies[tieKey] = openIdx else openTies.remove(tieKey)
                                } else {
                                    noteEvents.add(
                                        RawNoteEvent(
                                            quarterPos = quarterPos,
                                            quarterDuration = quarterDuration,
                                            voice = voice,
                                            midiNote = midiNote,
                                            percussion = percussion,
                                            gmProgram = info.gmProgram,
                                            velocity = noteVelocity,
                                            order = order++,
                                        ),
                                    )
                                    if (tieStart) openTies[tieKey] = noteEvents.size - 1
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }

        val tempoTimeline = buildTempoTimeline(tempoEvents)

        val resolved = noteEvents
            .map { it to Math.round(elapsedMicrosAt(tempoTimeline, it.quarterPos)) }
            .sortedWith(compareBy({ it.second }, { it.first.order }))

        val quantizer = TickQuantizer()
        var tickBuf = IntArray(1024)
        var tickMicrosBuf = LongArray(1024)
        var soundBuf = arrayOfNulls<InstrumentSlot>(1024)
        var vanillaPitchBuf = FloatArray(1024)
        var customPitchBuf = FloatArray(1024)
        var volumeBuf = FloatArray(1024)
        var durationMicrosBuf = LongArray(1024)
        var rawNoteBuf = IntArray(1024)
        var count = 0

        fun ensureCapacity(needed: Int) {
            if (tickBuf.size < needed) {
                val newSize = (tickBuf.size * 2).coerceAtLeast(needed)
                tickBuf = tickBuf.copyOf(newSize)
                tickMicrosBuf = tickMicrosBuf.copyOf(newSize)
                soundBuf = soundBuf.copyOf(newSize)
                vanillaPitchBuf = vanillaPitchBuf.copyOf(newSize)
                customPitchBuf = customPitchBuf.copyOf(newSize)
                volumeBuf = volumeBuf.copyOf(newSize)
                durationMicrosBuf = durationMicrosBuf.copyOf(newSize)
                rawNoteBuf = rawNoteBuf.copyOf(newSize)
            }
        }

        var lastOnsetMicros = Long.MIN_VALUE
        var sourceKeyCounter = -1L
        for ((event, onsetMicros) in resolved) {
            val resolution = if (event.percussion) {
                GmInstrumentMap.percussion(event.midiNote)
            } else {
                GmInstrumentMap.melodic(event.gmProgram, event.midiNote)
            }

            if (onsetMicros != lastOnsetMicros) {
                sourceKeyCounter++
                lastOnsetMicros = onsetMicros
            }
            val mcTick = quantizer.quantize(sourceKeyCounter, onsetMicros)

            val endMicros = Math.round(elapsedMicrosAt(tempoTimeline, event.quarterPos + event.quarterDuration))
            val durationMicros = if (event.quarterDuration > 0.0) (endMicros - onsetMicros).coerceAtLeast(0L) else -1L

            ensureCapacity(count + 1)
            tickBuf[count] = mcTick
            tickMicrosBuf[count] = onsetMicros
            soundBuf[count] = resolution.slot
            vanillaPitchBuf[count] = resolution.vanillaPitch
            customPitchBuf[count] = resolution.customPitch
            volumeBuf[count] = (event.velocity / 127f).coerceIn(0.2f, 1.0f)
            durationMicrosBuf[count] = durationMicros
            rawNoteBuf[count] = event.midiNote
            count++
        }

        return ParsedSong(
            sourceFileName = file.name,
            tick = tickBuf.copyOf(count),
            tickMicros = tickMicrosBuf.copyOf(count),
            sound = Array(count) { soundBuf[it]!! },
            vanillaPitch = vanillaPitchBuf.copyOf(count),
            customPitch = customPitchBuf.copyOf(count),
            volume = volumeBuf.copyOf(count),
            durationMicros = durationMicrosBuf.copyOf(count),
            rawNote = rawNoteBuf.copyOf(count),
            totalTicks = quantizer.maxTick,
        )
    }

    private fun buildPartInfo(score: ScorePartwise): Map<ScorePart, PartInfo> {
        val scoreParts = score.partList?.partGroupOrScorePart?.filterIsInstance<ScorePart>() ?: emptyList()
        return scoreParts.associateWith { scorePart ->
            val midiInstrument = scorePart.midiDeviceAndMidiInstrument.filterIsInstance<MidiInstrument>().firstOrNull()
            val program = ((midiInstrument?.midiProgram ?: 1) - 1).coerceIn(0, 127)
            PartInfo(program)
        }
    }

    private fun stepSemitone(step: Step): Int = when (step) {
        Step.C -> 0
        Step.D -> 2
        Step.E -> 4
        Step.F -> 5
        Step.G -> 7
        Step.A -> 9
        Step.B -> 11
    }

    private fun pitchToMidiNote(pitch: Pitch): Int {
        val alter = pitch.alter?.toInt() ?: 0
        return ((pitch.octave + 1) * 12 + stepSemitone(pitch.step) + alter).coerceIn(0, 127)
    }

    private fun unpitchedToMidiNote(unpitched: Unpitched): Int {
        val step = unpitched.displayStep ?: Step.C
        val octave = unpitched.displayOctave ?: 5
        return ((octave + 1) * 12 + stepSemitone(step)).coerceIn(0, 127)
    }

    private fun articulationNames(notations: List<Notations>): List<String> =
        notations.flatMap { it.tiedOrSlurOrTuplet }.filterIsInstance<Articulations>()
            .flatMap { it.accentOrStrongAccentOrStaccato }.mapNotNull { it.name?.localPart }

    private fun hasFermata(notations: List<Notations>): Boolean =
        notations.flatMap { it.tiedOrSlurOrTuplet }.filterIsInstance<Fermata>().isNotEmpty()

    private fun keyAlterForStep(step: Step, fifths: Int): Int = when {
        fifths > 0 -> if (SHARP_ORDER.take(fifths).contains(step)) 1 else 0
        fifths < 0 -> if (FLAT_ORDER.take(-fifths).contains(step)) -1 else 0
        else -> 0
    }

    /** The diatonic upper/lower neighbor of [pitch] under the key signature [fifths], as a MIDI note. */
    private fun diatonicNeighborMidiNote(pitch: Pitch, fifths: Int, up: Boolean, transposeSemitones: Int): Int {
        val idx = DIATONIC_STEPS.indexOf(pitch.step)
        val newIdx = if (up) (idx + 1) % 7 else (idx + 6) % 7
        val octaveDelta = when {
            up && newIdx < idx -> 1
            !up && newIdx > idx -> -1
            else -> 0
        }
        val newStep = DIATONIC_STEPS[newIdx]
        val alter = keyAlterForStep(newStep, fifths)
        val octave = pitch.octave + octaveDelta
        return (((octave + 1) * 12 + stepSemitone(newStep) + alter) + transposeSemitones).coerceIn(0, 127)
    }

    /**
     * Realizes ornament/tremolo notation on a note as a sequence of (midiNote, quarterDuration)
     * sub-notes spanning the note's own [quarterDuration] - or `null` if the note carries no
     * ornament that changes what's actually sounded (most notations, e.g. slurs/dynamics/fingering,
     * don't).
     */
    private fun ornamentExpansion(
        notations: List<Notations>,
        pitch: Pitch,
        fifths: Int,
        transposeSemitones: Int,
        mainMidiNote: Int,
        quarterDuration: Double,
    ): List<Pair<Int, Double>>? {
        if (quarterDuration <= 0.0) return null
        val ornamentEntries = notations.flatMap { it.tiedOrSlurOrTuplet }.filterIsInstance<Ornaments>()
            .flatMap { it.trillMarkOrTurnOrDelayedTurn }
        if (ornamentEntries.isEmpty()) return null

        val tremolo = ornamentEntries.firstOrNull { it.name?.localPart == "tremolo" }?.value as? Tremolo
        if (tremolo != null && (tremolo.type == null || tremolo.type == TremoloType.SINGLE)) {
            val repeatDur = 1.0 / Math.pow(2.0, tremolo.value.toDouble())
            val count = (quarterDuration / repeatDur).toInt().coerceAtLeast(1)
            return List(count) { mainMidiNote to (quarterDuration / count) }
        }

        val marks = ornamentEntries.mapNotNull { it.name?.localPart }.toSet()
        val upper = diatonicNeighborMidiNote(pitch, fifths, up = true, transposeSemitones)
        val lower = diatonicNeighborMidiNote(pitch, fifths, up = false, transposeSemitones)

        return when {
            "trill-mark" in marks || "shake" in marks || "wavy-line" in marks -> {
                val repeatDur = (1.0 / 8.0).coerceAtMost(quarterDuration)
                val count = (quarterDuration / repeatDur).toInt().coerceAtLeast(2)
                List(count) { i -> (if (i % 2 == 0) mainMidiNote else upper) to (quarterDuration / count) }
            }
            "mordent" in marks -> mordentFigure(mainMidiNote, upper, quarterDuration)
            "inverted-mordent" in marks -> mordentFigure(mainMidiNote, lower, quarterDuration)
            "turn" in marks || "vertical-turn" in marks -> turnFigure(mainMidiNote, upper, lower, quarterDuration, delayed = false)
            "inverted-turn" in marks || "inverted-vertical-turn" in marks ->
                turnFigure(mainMidiNote, lower, upper, quarterDuration, delayed = false)
            "delayed-turn" in marks -> turnFigure(mainMidiNote, upper, lower, quarterDuration, delayed = true)
            "delayed-inverted-turn" in marks -> turnFigure(mainMidiNote, lower, upper, quarterDuration, delayed = true)
            else -> null // glissando/schleifer (continuous pitch slides) and purely-visual marks: no discrete-note realization
        }
    }

    /** A quick main-neighbor-main figure at the start of the note, then the remainder sustains [main]. */
    private fun mordentFigure(main: Int, neighbor: Int, quarterDuration: Double): List<Pair<Int, Double>> {
        val figureDur = (quarterDuration * 0.5).coerceAtMost(0.25)
        val each = figureDur / 3.0
        val remainder = quarterDuration - figureDur
        val figure = listOf(main to each, neighbor to each, main to each)
        return if (remainder > 0.0) figure + (main to remainder) else figure
    }

    /** A four-note turn figure ([first]-main-[second]-main), either up front or delayed to the note's end. */
    private fun turnFigure(main: Int, first: Int, second: Int, quarterDuration: Double, delayed: Boolean): List<Pair<Int, Double>> {
        val figureDur = (quarterDuration * 0.5).coerceAtMost(0.5)
        val each = figureDur / 4.0
        val remainder = quarterDuration - figureDur
        val figure = listOf(first to each, main to each, second to each, main to each)
        return if (remainder <= 0.0) {
            figure
        } else if (delayed) {
            listOf(main to remainder) + figure
        } else {
            figure + (main to remainder)
        }
    }

    /**
     * Builds a piecewise tempo timeline from unsorted (quarterPos, bpm) tempo-change events
     * collected across every part (MusicXML tempo directions apply globally regardless of which
     * part they're notated in, same as MIDI tempo meta-events conventionally on track 0).
     */
    private fun buildTempoTimeline(tempoEvents: List<Pair<Double, Double>>): List<TempoBreakpoint> {
        val breakpoints = ArrayList<TempoBreakpoint>()
        var elapsedUs = 0.0
        var usPerQuarter = DEFAULT_TEMPO_US_PER_QUARTER
        var pos = 0.0
        breakpoints.add(TempoBreakpoint(0.0, 0.0, usPerQuarter))
        for ((quarterPos, bpm) in tempoEvents.sortedBy { it.first }) {
            elapsedUs += (quarterPos - pos) * usPerQuarter
            usPerQuarter = 60_000_000.0 / bpm
            pos = quarterPos
            breakpoints.add(TempoBreakpoint(pos, elapsedUs, usPerQuarter))
        }
        return breakpoints
    }

    private fun elapsedMicrosAt(timeline: List<TempoBreakpoint>, quarterPos: Double): Double {
        var bp = timeline[0]
        for (candidate in timeline) {
            if (candidate.quarterPos <= quarterPos) bp = candidate else break
        }
        return bp.elapsedUsAtStart + (quarterPos - bp.quarterPos) * bp.usPerQuarter
    }

    private fun unmarshal(file: File): ScorePartwise {
        val bytes = openScoreBytes(file)

        // JAXBContext's provider lookup (and jaxb-runtime's own internal class loading) falls back
        // to the calling thread's context classloader in places, which on Paper's main thread is
        // not this plugin's own classloader - the one that actually has proxymusic/jaxb-runtime
        // bundled via shadowJar. Without this swap, that lookup fails with a ClassNotFoundException
        // for jaxb-runtime's internal classes even though they're plainly present in the jar.
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = MusicXmlParser::class.java.classLoader
        val result = try {
            Marshalling.unmarshal(ByteArrayInputStream(bytes))
        } finally {
            thread.contextClassLoader = previousClassLoader
        }

        return result as? ScorePartwise
            ?: error("Unsupported MusicXML document in ${file.name}: expected score-partwise, got ${result.javaClass.simpleName}")
    }

    /** Resolves a `.musicxml`/`.mxl` [file] to its raw MusicXML document bytes, unzipping `.mxl` containers. */
    private fun openScoreBytes(file: File): ByteArray {
        if (!file.name.endsWith(".mxl", ignoreCase = true)) return file.readBytes()
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml")
            val rootPath = containerEntry?.let { entry ->
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                Regex("""full-path="([^"]+)"""").find(text)?.groupValues?.get(1)
            } ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith(".xml", ignoreCase = true) && !it.name.startsWith("META-INF/")
            }?.name ?: error("No MusicXML root document found in ${file.name}")
            val entry = zip.getEntry(rootPath) ?: error("MusicXML root entry '$rootPath' not found in ${file.name}")
            return zip.getInputStream(entry).readBytes()
        }
    }
}
