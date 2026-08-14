package io.github.jwyoon1220.openMCMelody.midi

/**
 * Extension-based dispatch shared by every place that lists playable song files (tab-complete,
 * `/midi list`, the web control panel, the jukebox GUI) and by [SongCache], so the recognized
 * extension set lives in exactly one place.
 */
object SongFiles {
    fun isMusicXml(name: String): Boolean =
        name.endsWith(".musicxml", ignoreCase = true) || name.endsWith(".mxl", ignoreCase = true)

    fun isPlayable(name: String): Boolean =
        name.endsWith(".mid", ignoreCase = true) || name.endsWith(".midi", ignoreCase = true) || isMusicXml(name)
}
