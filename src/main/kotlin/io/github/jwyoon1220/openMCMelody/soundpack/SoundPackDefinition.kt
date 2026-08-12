package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import java.io.File

class SoundPackDefinition(
    val folderName: String,
    val name: String,
    val description: String,
    val packFormat: Int,
    val slotFiles: Map<InstrumentSlot, File>,
)
