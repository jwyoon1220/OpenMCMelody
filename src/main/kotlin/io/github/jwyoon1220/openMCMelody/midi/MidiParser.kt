package io.github.jwyoon1220.openMCMelody.midi

import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

private const val PERCUSSION_CHANNEL = 9
private const val DEFAULT_TEMPO_US = 500_000L // 120 BPM, per the MIDI spec's implied default
private const val SET_TEMPO_META_TYPE = 0x51

// Minimum enforced spacing (in mc ticks) between two distinct onsets that would otherwise land on
// the same mc tick. 1.0 (a full 50ms tick) fully separates every colliding note but stretches dense
// runs 1:1 with however many notes collided. Using < 1.0 here lets a fraction of those onsets still
// land back on the same tick (a partial chord) so a dense run resolves faster than that -
// specifically ~1.3x faster at 1/1.3, which is enough to keep notes audibly distinct without
// dragging a fast arpeggio out as long as pure 1-tick separation would.
private const val MIN_ARPEGGIO_SPACING_TICKS = 1.0 / 1.3

/**
 * Parses a Standard MIDI File into a [ParsedSong] ready for playback.
 *
 * Never touches Bukkit API and does no I/O beyond reading [file], so callers are expected
 * to run this off the main thread (see [SongCache]) and hop back before using the result.
 */
object MidiParser {

    private data class Indexed(val tick: Long, val trackIndex: Int, val event: MidiEvent)

    fun parse(file: File): ParsedSong {
        val sequence = MidiSystem.getSequence(file)

        val merged = ArrayList<Indexed>()
        sequence.tracks.forEachIndexed { trackIndex, track ->
            for (i in 0 until track.size()) {
                merged.add(Indexed(track[i].tick, trackIndex, track[i]))
            }
        }
        merged.sortWith(compareBy({ it.tick }, { it.trackIndex }))

        val isSmpte = sequence.divisionType != Sequence.PPQ
        val resolution = sequence.resolution.coerceAtLeast(1)
        val smpteUsPerTick = if (isSmpte) 1_000_000.0 / (sequence.divisionType.toDouble() * resolution) else 0.0

        var tempoUs = DEFAULT_TEMPO_US
        var tempoChangeTick = 0L
        var tempoChangeElapsedUs = 0.0
        var usPerTick = tempoUs.toDouble() / resolution

        fun elapsedMicrosAt(tick: Long): Double =
            if (isSmpte) tick * smpteUsPerTick
            else tempoChangeElapsedUs + (tick - tempoChangeTick) * usPerTick

        val channelProgram = IntArray(16)
        // Dynamics (셈여림) are frequently authored as CC7/CC11 automation curves rather than
        // per-note velocity - especially in MIDI exported from notation software (crescendo/
        // diminuendo/forte/piano marks), where velocity is often left flat. Both default to full
        // scale (not the sometimes-quoted GM default of 100/127) since a file that never sends
        // either shouldn't be quietened just because we assumed otherwise.
        val channelVolume = FloatArray(16) { 1.0f } // CC7
        val channelExpression = FloatArray(16) { 1.0f } // CC11

        var tickBuf = IntArray(1024)
        var tickMicrosBuf = LongArray(1024)
        var soundBuf = arrayOfNulls<InstrumentSlot>(1024)
        var vanillaPitchBuf = FloatArray(1024)
        var customPitchBuf = FloatArray(1024)
        var volumeBuf = FloatArray(1024)
        var count = 0
        var maxTick = 0

        // Minecraft only delivers sound at 20Hz, so any notes originally spaced closer together
        // than one mc tick (50ms) - a fast arpeggio, a grace note, a trill - would otherwise round
        // onto the exact same mcTick and get batched into what sounds like a single chord. Notes
        // that share a real MIDI onset (same source tick - an actual chord) still collapse onto one
        // mcTick together; distinct onsets are pushed forward by at least MIN_ARPEGGIO_SPACING_TICKS
        // so they keep audibly separating. virtualTick tracks that push-forward in fractional ticks
        // (not the rounded integer mcTick) so the spacing debt doesn't compound into whole extra
        // ticks faster than intended.
        var lastSourceTick = -1L
        var virtualTick = -1.0
        var currentGroupMcTick = -1

        fun ensureCapacity(needed: Int) {
            if (tickBuf.size < needed) {
                val newSize = (tickBuf.size * 2).coerceAtLeast(needed)
                tickBuf = tickBuf.copyOf(newSize)
                tickMicrosBuf = tickMicrosBuf.copyOf(newSize)
                soundBuf = soundBuf.copyOf(newSize)
                vanillaPitchBuf = vanillaPitchBuf.copyOf(newSize)
                customPitchBuf = customPitchBuf.copyOf(newSize)
                volumeBuf = volumeBuf.copyOf(newSize)
            }
        }

        for (indexed in merged) {
            val message = indexed.event.message

            if (!isSmpte && message is MetaMessage && message.type == SET_TEMPO_META_TYPE) {
                val data = message.data
                if (data.size >= 3) {
                    val newTempo = ((data[0].toInt() and 0xFF) shl 16) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        (data[2].toInt() and 0xFF)
                    if (newTempo > 0) {
                        tempoChangeElapsedUs = elapsedMicrosAt(indexed.tick)
                        tempoChangeTick = indexed.tick
                        tempoUs = newTempo.toLong()
                        usPerTick = tempoUs.toDouble() / resolution
                    }
                }
                continue
            }

            if (message !is ShortMessage) continue

            if (message.command == ShortMessage.PROGRAM_CHANGE) {
                channelProgram[message.channel] = message.data1
                continue
            }

            if (message.command == ShortMessage.CONTROL_CHANGE) {
                when (message.data1) {
                    7 -> channelVolume[message.channel] = message.data2 / 127f
                    11 -> channelExpression[message.channel] = message.data2 / 127f
                }
                continue
            }

            if (message.command != ShortMessage.NOTE_ON) continue
            val velocity = message.data2
            if (velocity <= 0) continue // velocity-0 NOTE_ON is a de facto note-off

            val note = message.data1
            val channel = message.channel
            val resolution = if (channel == PERCUSSION_CHANNEL) {
                GmInstrumentMap.percussion(note)
            } else {
                GmInstrumentMap.melodic(channelProgram[channel], note)
            }

            // The note's true, unquantized onset - independent of the spread applied to mcTick below.
            // Playback modes that can schedule at finer-than-tick precision (see PlaybackManager's
            // "instant" play mode) use this instead of mcTick, so they never inherit the artificial
            // stretching that mcTick's collision-avoidance adds for the coarser tick-locked mode.
            val rawMicros = Math.round(elapsedMicrosAt(indexed.tick))

            val mcTick: Int
            if (indexed.tick == lastSourceTick) {
                mcTick = currentGroupMcTick
            } else {
                val rawTick = rawMicros / MC_TICK_MICROS.toDouble()
                val candidateTick = maxOf(rawTick, virtualTick + MIN_ARPEGGIO_SPACING_TICKS)
                mcTick = Math.round(candidateTick).toInt()
                virtualTick = candidateTick
                lastSourceTick = indexed.tick
                currentGroupMcTick = mcTick
            }
            if (mcTick > maxTick) maxTick = mcTick

            val dynamics = (velocity / 127f) * channelVolume[channel] * channelExpression[channel]

            ensureCapacity(count + 1)
            tickBuf[count] = mcTick
            tickMicrosBuf[count] = rawMicros
            soundBuf[count] = resolution.slot
            vanillaPitchBuf[count] = resolution.vanillaPitch
            customPitchBuf[count] = resolution.customPitch
            volumeBuf[count] = dynamics.coerceIn(0.2f, 1.0f)
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
            totalTicks = maxTick,
        )
    }
}
