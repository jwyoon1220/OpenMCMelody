package io.github.jwyoon1220.openMCMelody.soundfont

import io.github.jwyoon1220.openMCMelody.midi.GmNames
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.midi.MidiChannel
import javax.sound.midi.MidiSystem
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

private const val PERCUSSION_CHANNEL = 9
private const val MELODIC_HOLD_SECONDS = 0.9
private const val MELODIC_TOTAL_SECONDS = 1.8
private const val PERCUSSION_HOLD_SECONDS = 0.15
private const val PERCUSSION_TOTAL_SECONDS = 1.2

/**
 * Standalone entry point (deliberately has NO Bukkit/Paper dependency) that renders every General
 * MIDI instrument a given SoundFont (.sf2) or DLS file actually defines into individual WAV
 * samples, exactly the way `ms-gm`'s samples were manually produced from `gm.dls` earlier.
 *
 * Runs as a *separate* `java` process, launched by [SoundFontConverter] with
 * `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` on its command line - the plugin's
 * own server JVM is never started with that flag, and the module system otherwise blocks casting
 * to the internal `AudioSynthesizer` type needed for offline (non-realtime) rendering.
 *
 * Args: `<soundFontFile> <outputDir>`. Writes `<outputDir>/<slot>.wav` for every (GM instrument,
 * octave bucket) pair the soundfont actually has an instrument for - see [GmNames] - plus
 * `<outputDir>/manifest.txt` (one `slot<TAB>file` line per rendered sample) so the caller knows
 * what succeeded without parsing stdout.
 *
 * Real-world SoundFonts occasionally trigger bugs in the JDK's bundled software synthesizer
 * (e.g. `ArrayIndexOutOfBoundsException` deep in `SoftLinearResampler2` for certain samples/loop
 * points) - confirmed against an actual community soundfont. A single bad instrument must not
 * lose every other one, so each instrument is rendered in isolation: on failure it's skipped and
 * the synth+stream are torn down and reopened before continuing, since an internal DSP exception
 * can leave the shared mixer pipeline in an unknown state for whatever renders next.
 */
object SoundFontExtractorMain {

    private class SynthHandle(val synth: Synthesizer, val stream: AudioInputStream)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("Usage: SoundFontExtractorMain <soundFontFile> <outputDir>")
            kotlin.system.exitProcess(2)
        }
        val soundFontFile = File(args[0])
        val outputDir = File(args[1]).apply { mkdirs() }

        val soundbank = MidiSystem.getSoundbank(soundFontFile)
        val availablePrograms = soundbank.instruments
            .filter { it.patch.bank == 0 }
            .map { it.patch.program }
            .toSet()

        val format = AudioFormat(44100f, 16, 1, true, false)
        var handle = openSynth(soundbank, format)

        val manifest = StringBuilder()
        var rendered = 0
        var failed = 0

        fun renderSlot(slot: String, percussion: Boolean, program: Int, note: Int) {
            try {
                renderOne(handle.synth, handle.stream, format, outputDir, slot, percussion, program, note)
                manifest.append(slot).append('\t').append("$slot.wav").append('\n')
                rendered++
            } catch (e: Exception) {
                System.err.println("Skipping '$slot': ${e.javaClass.simpleName}: ${e.message}")
                failed++
                closeSynth(handle)
                handle = openSynth(soundbank, format)
            }
        }

        // One sample per octave bucket per instrument (not one sample for the whole range) - see
        // GmNames.octaveCenterNote - so no sample ever needs to pitch-shift more than ~half an
        // octave to cover its bucket, which matters across a full 88-key piano range.
        for (program in 0 until 128) {
            if (program !in availablePrograms) continue
            for (octave in 0 until GmNames.OCTAVE_COUNT) {
                val slot = GmNames.octaveSlotKey(GmNames.MELODIC[program], octave)
                val centerNote = GmNames.octaveCenterNote(octave)
                renderSlot(slot, percussion = false, program = program, note = centerNote)
            }
        }
        for ((note, slot) in GmNames.PERCUSSION) {
            renderSlot(slot, percussion = true, program = -1, note = note)
        }

        closeSynth(handle)

        File(outputDir, "manifest.txt").writeText(manifest.toString())
        println("Rendered $rendered instrument(s), skipped $failed, from ${soundFontFile.name}")
    }

    private fun openSynth(soundbank: Soundbank, format: AudioFormat): SynthHandle {
        val synth = MidiSystem.getSynthesizer()
        // Reflection (not a direct cast to com.sun.media.sound.AudioSynthesizer) so this file has
        // no compile-time reference to that non-exported package - only the JVM that actually
        // *runs* this (launched with --add-exports by SoundFontConverter) needs the access grant.
        val openStream = synth.javaClass.getMethod("openStream", AudioFormat::class.java, Map::class.java)
        val stream = openStream.invoke(synth, format, emptyMap<String, Any>()) as AudioInputStream
        synth.unloadAllInstruments(synth.defaultSoundbank)
        synth.loadAllInstruments(soundbank)
        return SynthHandle(synth, stream)
    }

    private fun closeSynth(handle: SynthHandle) {
        try {
            handle.stream.close()
        } catch (e: Exception) {
            // best-effort cleanup
        }
        try {
            handle.synth.close()
        } catch (e: Exception) {
            // best-effort cleanup
        }
    }

    private fun renderOne(
        synth: Synthesizer,
        stream: AudioInputStream,
        format: AudioFormat,
        outputDir: File,
        slotKey: String,
        percussion: Boolean,
        program: Int,
        note: Int,
    ) {
        val channel: MidiChannel = synth.channels[if (percussion) PERCUSSION_CHANNEL else 0]
        if (!percussion) channel.programChange(program)

        val holdSeconds = if (percussion) PERCUSSION_HOLD_SECONDS else MELODIC_HOLD_SECONDS
        val totalSeconds = if (percussion) PERCUSSION_TOTAL_SECONDS else MELODIC_TOTAL_SECONDS
        val totalBytes = (format.frameSize * format.sampleRate * totalSeconds).toLong()
        val holdBytes = (format.frameSize * format.sampleRate * holdSeconds).toLong()

        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        channel.noteOn(note, 100)
        var released = false
        try {
            while (out.size() < totalBytes) {
                val toRead = minOf(buf.size.toLong(), totalBytes - out.size()).toInt()
                val n = stream.read(buf, 0, toRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (!released && out.size() >= holdBytes) {
                    channel.noteOff(note)
                    released = true
                }
            }
        } finally {
            if (!released) try { channel.noteOff(note) } catch (e: Exception) { /* synth may already be broken */ }
        }

        val pcm = out.toByteArray()
        val toWrite = AudioInputStream(pcm.inputStream(), format, (pcm.size / format.frameSize).toLong())
        AudioSystem.write(toWrite, AudioFileFormat.Type.WAVE, File(outputDir, "$slotKey.wav"))
    }
}
