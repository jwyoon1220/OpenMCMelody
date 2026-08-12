package io.github.jwyoon1220.openMCMelody.soundfont

import org.bukkit.plugin.Plugin
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Turns a `.sf2`/`.dls` soundfont into a full soundpack folder (`pack.yml` + one `.ogg` per GM
 * instrument the font actually defines) - the automated equivalent of how the bundled `ms-gm`
 * sample pack was built by hand from `gm.dls`.
 *
 * Two external processes do the real work, both off the calling thread (callers must run
 * [convert] via [org.bukkit.scheduler.BukkitScheduler.runTaskAsynchronously], never on the main
 * thread - this can easily take tens of seconds):
 * 1. [SoundFontExtractorMain] as a *separate* `java` process (with `--add-exports
 *    java.desktop/com.sun.media.sound=ALL-UNNAMED`, which the plugin's own server JVM does not
 *    have and should not need) renders every matched instrument to a WAV file.
 * 2. `ffmpeg` (must be installed and reachable - see `soundfont.ffmpeg-path` in config.yml)
 *    converts each WAV to Ogg Vorbis, since Minecraft resource packs require that format and the
 *    JDK has no Vorbis encoder.
 */
class SoundFontConverter(private val plugin: Plugin, private val ffmpegPath: String) {

    class ConversionException(message: String) : Exception(message)
    class ConversionResult(val renderedSlots: List<String>, val failedConversions: Int)

    fun convert(soundFontFile: File, packFolder: File, packName: String): ConversionResult {
        if (!soundFontFile.isFile) throw ConversionException("Soundfont file not found: ${soundFontFile.name}")

        val tempDir = Files.createTempDirectory("omm-sf2-").toFile()
        try {
            runExtractor(soundFontFile, tempDir)

            val manifestFile = File(tempDir, "manifest.txt")
            if (!manifestFile.isFile) throw ConversionException("Soundfont extraction produced no manifest (unknown failure)")
            val entries = manifestFile.readLines()
                .filter { it.isNotBlank() }
                .map { line -> line.split('\t', limit = 2).let { it[0] to it[1] } }
            if (entries.isEmpty()) throw ConversionException("'${soundFontFile.name}' contains no usable General MIDI instruments (bank 0, programs 0-127)")

            packFolder.mkdirs()
            val renderedSlots = mutableListOf<String>()
            for ((slot, wavName) in entries) {
                if (runFfmpeg(File(tempDir, wavName), File(packFolder, "$slot.ogg"))) renderedSlots += slot
            }
            if (renderedSlots.isEmpty()) {
                throw ConversionException("ffmpeg failed to convert any samples - check 'soundfont.ffmpeg-path' in config.yml points at a working ffmpeg")
            }

            writePackYml(packFolder, packName, soundFontFile.name, renderedSlots)
            return ConversionResult(renderedSlots, entries.size - renderedSlots.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun runExtractor(soundFontFile: File, outputDir: File) {
        val javaExe = File(System.getProperty("java.home"), if (isWindows()) "bin/java.exe" else "bin/java")
        val pluginJar = File(javaClass.protectionDomain.codeSource.location.toURI())

        val process = try {
            ProcessBuilder(
                javaExe.absolutePath,
                "--add-exports", "java.desktop/com.sun.media.sound=ALL-UNNAMED",
                "-cp", pluginJar.absolutePath,
                "io.github.jwyoon1220.openMCMelody.soundfont.SoundFontExtractorMain",
                soundFontFile.absolutePath,
                outputDir.absolutePath,
            ).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw ConversionException("Could not launch the soundfont extraction process: ${e.message}")
        }

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ConversionException("Soundfont extraction timed out after 120s")
        }
        if (process.exitValue() != 0) {
            throw ConversionException("Soundfont extraction failed (exit ${process.exitValue()}): ${output.take(500)}")
        }
        plugin.logger.info("Soundfont extraction: ${output.trim()}")
    }

    private fun runFfmpeg(wavFile: File, oggFile: File): Boolean {
        val process = try {
            ProcessBuilder(ffmpegPath, "-y", "-i", wavFile.absolutePath, "-c:a", "libvorbis", "-q:a", "4", oggFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw ConversionException("Could not run ffmpeg at '$ffmpegPath': ${e.message} - set 'soundfont.ffmpeg-path' in config.yml")
        }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0 && oggFile.isFile
    }

    private fun writePackYml(packFolder: File, packName: String, sourceName: String, slots: List<String>) {
        val sb = StringBuilder()
        sb.append("name: ").append(yamlQuote(packName)).append('\n')
        sb.append("description: ").append(yamlQuote("Auto-generated from $sourceName")).append('\n')
        sb.append("sounds:\n")
        for (slot in slots) sb.append("  ").append(slot).append(": ").append(slot).append(".ogg\n")
        File(packFolder, "pack.yml").writeText(sb.toString())
    }

    private fun yamlQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
}
