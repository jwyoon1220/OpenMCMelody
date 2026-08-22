package io.github.jwyoon1220.openMCMelody.audio

/**
 * Extension-based dispatch for real audio source files dropped into `audio/` - mirrors
 * [io.github.jwyoon1220.openMCMelody.midi.SongFiles]'s role for `music/`.
 */
object AudioFiles {
    private val EXTENSIONS = listOf(".flac", ".wav", ".mp3", ".m4a", ".aiff", ".aac", ".ogg")

    fun isPlayable(name: String): Boolean = EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
}
