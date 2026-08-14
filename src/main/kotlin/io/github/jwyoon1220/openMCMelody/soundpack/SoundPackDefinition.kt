package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import java.io.File

/** One release-tail checkpoint (see GmNames.RELEASE_HOLD_CHECKPOINTS_SECONDS) - [holdMillis] is how long the note was held before release in the captured sample. */
class ReleaseCheckpoint(val holdMillis: Long, val file: File)

class SoundPackDefinition(
    val folderName: String,
    val name: String,
    val description: String,
    val packFormat: Int,
    val slotFiles: Map<InstrumentSlot, File>,
    /**
     * Optional per-slot release-tail checkpoints (see GmNames.RELEASE_*_SECONDS), sorted ascending
     * by [ReleaseCheckpoint.holdMillis] - empty for hand-authored/old packs. Playback picks
     * whichever checkpoint's hold length is closest to a note's real MIDI duration, see
     * [SoundPackManager.resolveRelease].
     */
    val releaseSlotFiles: Map<InstrumentSlot, List<ReleaseCheckpoint>> = emptyMap(),
)
