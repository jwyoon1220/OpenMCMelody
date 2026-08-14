package io.github.jwyoon1220.openMCMelody.midi

// Minimum enforced spacing (in mc ticks) between two distinct onsets that would otherwise land on
// the same mc tick. 1.0 (a full 50ms tick) fully separates every colliding note but stretches dense
// runs 1:1 with however many notes collided. Using < 1.0 here lets a fraction of those onsets still
// land back on the same tick (a partial chord) so a dense run resolves faster than that -
// specifically ~1.3x faster at 1/1.3, which is enough to keep notes audibly distinct without
// dragging a fast arpeggio out as long as pure 1-tick separation would.
private const val MIN_ARPEGGIO_SPACING_TICKS = 1.0 / 1.3

/**
 * Spreads note onsets that round onto the same mc tick (20Hz - Minecraft's sound-delivery rate)
 * apart from each other so a fast arpeggio/grace note/trill doesn't get batched into what sounds
 * like a single chord. Notes that share a real source-format onset (same [sourceKey] - an actual
 * chord, MIDI notes at the same tick or MusicXML notes at the same divisions position) still
 * collapse onto one mc tick together; distinct onsets are pushed forward by at least
 * [MIN_ARPEGGIO_SPACING_TICKS]. Shared between [MidiParser] and [MusicXmlParser] since this logic
 * only depends on a time-ordered stream of (source onset identity, elapsed microseconds) pairs,
 * not on the source file format.
 */
class TickQuantizer {
    private var lastSourceKey = -1L
    private var virtualTick = -1.0
    private var currentGroupMcTick = -1

    var maxTick = 0
        private set

    fun quantize(sourceKey: Long, rawMicros: Long): Int {
        val mcTick: Int
        if (sourceKey == lastSourceKey) {
            mcTick = currentGroupMcTick
        } else {
            val rawTick = rawMicros / MC_TICK_MICROS.toDouble()
            val candidateTick = maxOf(rawTick, virtualTick + MIN_ARPEGGIO_SPACING_TICKS)
            mcTick = Math.round(candidateTick).toInt()
            virtualTick = candidateTick
            lastSourceKey = sourceKey
            currentGroupMcTick = mcTick
        }
        if (mcTick > maxTick) maxTick = mcTick
        return mcTick
    }
}
