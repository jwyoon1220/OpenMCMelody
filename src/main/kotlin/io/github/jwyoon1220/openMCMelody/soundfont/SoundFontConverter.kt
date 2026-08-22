package io.github.jwyoon1220.openMCMelody.soundfont

import io.github.jwyoon1220.openMCMelody.midi.GmNames
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem

/** Matches the "_release_<holdMillis>" suffix SoundFontExtractorMain encodes into a release-layer slot key. */
private val RELEASE_CHECKPOINT_SUFFIX = Regex("_release_(\\d+)$")

/** How much of the 0-100 progress range extraction consumes before ffmpeg encoding takes the rest. */
private const val EXTRACTION_WEIGHT_PERCENT = 80

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
    class ConversionResult(val renderedSlots: List<String>, val failedConversions: Int) {
        val mainSlotCount: Int get() = renderedSlots.count { !RELEASE_CHECKPOINT_SUFFIX.containsMatchIn(it) }
        val releaseSlotCount: Int get() = renderedSlots.count { RELEASE_CHECKPOINT_SUFFIX.containsMatchIn(it) }
    }

    /**
     * @param onProgress called (from whatever thread [convert] itself runs on, or - during the
     * encoding phase - from whichever of [encodeAll]'s virtual threads finishes a file, so callers
     * must be thread-safe/re-entrant) with a 0-100 percent and a short human-readable status, e.g.
     * `(37, "Extracting c_1 from gm.sf2")`. Extraction is weighted [EXTRACTION_WEIGHT_PERCENT] of
     * the bar and ffmpeg encoding the remainder, since extraction dominates wall-clock time.
     */
    fun convert(
        soundFontFile: File,
        packFolder: File,
        packName: String,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): ConversionResult {
        if (!soundFontFile.isFile) throw ConversionException("Soundfont file not found: ${soundFontFile.name}")

        val tempDir = Files.createTempDirectory("omm-sf2-").toFile()
        try {
            runExtractor(soundFontFile, tempDir) { percent, slot ->
                onProgress(percent, "Extracting $slot from ${soundFontFile.name}")
            }

            val manifestFile = File(tempDir, "manifest.txt")
            if (!manifestFile.isFile) throw ConversionException("Soundfont extraction produced no manifest (unknown failure)")
            val entries = manifestFile.readLines()
                .filter { it.isNotBlank() }
                .map { line -> line.split('\t', limit = 2).let { it[0] to it[1] } }
            if (entries.isEmpty()) throw ConversionException("'${soundFontFile.name}' contains no usable General MIDI instruments (bank 0, programs 0-127)")

            packFolder.mkdirs()
            val renderedSlots = encodeAll(entries, tempDir, packFolder, onProgress)
            if (renderedSlots.isEmpty()) {
                throw ConversionException("ffmpeg failed to convert any samples - check 'soundfont.ffmpeg-path' in config.yml points at a working ffmpeg")
            }

            writePackYml(packFolder, packName, soundFontFile.name, renderedSlots)
            return ConversionResult(renderedSlots, entries.size - renderedSlots.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun runExtractor(soundFontFile: File, outputDir: File, onProgress: (Int, String) -> Unit) {
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

        val output = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            output.append(line).append('\n')
            val fields = line.split('\t')
            if (fields.size == 4 && fields[0] == "PROGRESS") {
                val index = fields[1].toIntOrNull()
                val total = fields[2].toIntOrNull()
                if (index != null && total != null && total > 0) {
                    onProgress(index * EXTRACTION_WEIGHT_PERCENT / total, fields[3])
                }
            }
        }
        val finished = process.waitFor(420, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ConversionException("Soundfont extraction timed out after 420s")
        }
        if (process.exitValue() != 0) {
            throw ConversionException("Soundfont extraction failed (exit ${process.exitValue()}): ${output.take(500)}")
        }
        plugin.logger.info("Soundfont extraction: ${output.trim()}")
    }

    /**
     * Runs [runFfmpeg] for every extracted [entries] file concurrently, one virtual thread per
     * file, instead of blocking through them one at a time. Each call is dominated by waiting on
     * the external `ffmpeg` process's own I/O, not CPU work in this JVM, so it's a textbook fit
     * for virtual threads: hundreds can be in flight waiting on their own subprocess at once
     * without pinning hundreds of platform threads, and wall-clock time for the whole encoding
     * phase drops to roughly the slowest single file instead of the sum of all of them.
     *
     * Returns the successfully-encoded slot keys in [entries]' original order (order has no
     * functional effect on the pack - only cosmetic ordering in the written `pack.yml`).
     */
    private fun encodeAll(
        entries: List<Pair<String, String>>,
        tempDir: File,
        packFolder: File,
        onProgress: (Int, String) -> Unit,
    ): List<String> {
        val results = arrayOfNulls<String>(entries.size)
        val completed = AtomicInteger(0)
        val progressLock = Any()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val futures = try {
            entries.mapIndexed { index, (slot, wavName) ->
                executor.submit {
                    val fadeOut = RELEASE_CHECKPOINT_SUFFIX.containsMatchIn(slot)
                    if (runFfmpeg(File(tempDir, wavName), File(packFolder, "$slot.ogg"), fadeOut)) {
                        results[index] = slot
                    }
                    val done = completed.incrementAndGet()
                    val percent = EXTRACTION_WEIGHT_PERCENT + done * (100 - EXTRACTION_WEIGHT_PERCENT) / entries.size
                    // onProgress isn't guaranteed thread-safe by callers - serialize calls into it
                    // rather than letting concurrent virtual threads race into it directly.
                    synchronized(progressLock) { onProgress(percent, "Encoding $slot.ogg") }
                }
            }
        } finally {
            executor.shutdown()
        }
        var firstError: Throwable? = null
        for (future in futures) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                if (firstError == null) firstError = e.cause ?: e
            }
        }
        firstError?.let { throw (it as? ConversionException) ?: ConversionException("ffmpeg encoding failed: ${it.message}") }
        return results.filterNotNull()
    }

    private fun runFfmpeg(wavFile: File, oggFile: File, fadeOut: Boolean): Boolean {
        val args = mutableListOf(ffmpegPath, "-y", "-i", wavFile.absolutePath)
        if (fadeOut) {
            // Guarantees a release sample always tapers to true silence by EOF regardless of what
            // the raw synth capture happened to do - see GmNames.RELEASE_*_SECONDS's doc comment.
            // Probed from the actual WAV rather than a shared constant since each checkpoint's
            // total length differs (checkpointHoldSeconds + GmNames.RELEASE_TAIL_SECONDS).
            val totalSeconds = probeWavSeconds(wavFile)
            if (totalSeconds != null) {
                val fadeStart = (totalSeconds - GmNames.RELEASE_FADE_SECONDS).coerceAtLeast(0.0)
                args += listOf("-af", "afade=t=out:st=$fadeStart:d=${GmNames.RELEASE_FADE_SECONDS}")
            }
        }
        args += listOf("-c:a", "libvorbis", "-q:a", "4", oggFile.absolutePath)
        val process = try {
            ProcessBuilder(args)
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
        val mainSlots = slots.filterNot { RELEASE_CHECKPOINT_SUFFIX.containsMatchIn(it) }
        // (baseKey, holdMillis, slot) - one row per release checkpoint, grouped by baseKey below.
        val releaseRows = slots.mapNotNull { slot ->
            val match = RELEASE_CHECKPOINT_SUFFIX.find(slot) ?: return@mapNotNull null
            val baseKey = slot.removeRange(match.range)
            Triple(baseKey, match.groupValues[1], slot)
        }

        val sb = StringBuilder()
        sb.append("name: ").append(yamlQuote(packName)).append('\n')
        sb.append("description: ").append(yamlQuote("Auto-generated from $sourceName")).append('\n')
        // Read back by SoundPackLoader.sourceSoundfont() so `/midi soundpack rebuild` can find the
        // original file without the caller having to re-specify it.
        sb.append("source-soundfont: ").append(yamlQuote(sourceName)).append('\n')
        sb.append("sounds:\n")
        for (slot in mainSlots) sb.append("  ").append(slot).append(": ").append(slot).append(".ogg\n")
        // Optional, additive, nested section (see SoundPackLoader/GmNames.RELEASE_*_SECONDS) - one
        // sub-map per base slot (base key has the "_release_<holdMillis>" suffix stripped so
        // InstrumentSlot.byKey() resolves it), keyed by that checkpoint's hold length in
        // milliseconds so PlaybackManager can pick whichever is closest to a note's real duration.
        // Old loaders that don't know this section simply never read it.
        if (releaseRows.isNotEmpty()) {
            sb.append("release_sounds:\n")
            for ((baseKey, rowsForKey) in releaseRows.groupBy({ it.first }, { it.second to it.third })) {
                sb.append("  ").append(baseKey).append(":\n")
                for ((holdMillis, slot) in rowsForKey) {
                    sb.append("    '").append(holdMillis).append("': ").append(slot).append(".ogg\n")
                }
            }
        }
        File(packFolder, "pack.yml").writeText(sb.toString())
    }

    private fun probeWavSeconds(wavFile: File): Double? = try {
        val format = AudioSystem.getAudioFileFormat(wavFile)
        val frames = format.frameLength.toDouble()
        if (frames < 0) null else frames / format.format.frameRate
    } catch (e: Exception) {
        null
    }

    private fun yamlQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
}
