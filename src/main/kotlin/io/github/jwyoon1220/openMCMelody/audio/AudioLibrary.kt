package io.github.jwyoon1220.openMCMelody.audio

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private val DURATION_REGEX = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")

/** One real-audio track: its raw source file, the stable sound key it's registered under, the converted (or reused) ogg file, and its length. */
class AudioTrack(val sourceFile: File, val key: String, val oggFile: File, val durationSeconds: Int)

/**
 * Owns `audio/` (raw FLAC/WAV/MP3/... source files dropped in by an admin) and `audio/audio.yml`
 * (a conversion-cache manifest keyed by source filename: assigned sound key, source
 * `lastModified`, converted filename, probed duration) - the real-audio equivalent of how
 * [io.github.jwyoon1220.openMCMelody.soundfont.SoundFontConverter] turns a soundfont into
 * per-instrument samples, except here the whole file is one track played back as-is rather than
 * resynthesized into note-block music.
 *
 * [ensureConverted] does real file I/O and shells out to `ffmpeg` - callers must run it off the
 * main thread, same contract as `SoundFontConverter.convert`.
 */
class AudioLibrary(private val audioFolder: File, private val ffmpegPath: String) {

    class ConversionException(message: String) : Exception(message)

    private val manifestFile = File(audioFolder, "audio.yml")
    private val convertedFolder = File(audioFolder, ".converted")
    private val config: YamlConfiguration = YamlConfiguration.loadConfiguration(manifestFile)

    @Synchronized
    fun listSources(): List<String> =
        audioFolder.listFiles { f -> f.isFile && AudioFiles.isPlayable(f.name) }?.map { it.name }?.sorted() ?: emptyList()

    /** Every track this library currently knows about whose source and converted file both still exist - used to build the audio resource pack. */
    @Synchronized
    fun knownTracks(): List<AudioTrack> {
        val section = config.getConfigurationSection("tracks") ?: return emptyList()
        return section.getKeys(false).mapNotNull { filename ->
            val sourceFile = File(audioFolder, filename)
            if (!sourceFile.isFile) return@mapNotNull null
            val key = section.getString("$filename.key") ?: return@mapNotNull null
            val convertedName = section.getString("$filename.converted") ?: return@mapNotNull null
            val convertedFile = if (convertedName == filename) sourceFile else File(convertedFolder, convertedName)
            if (!convertedFile.isFile) return@mapNotNull null
            AudioTrack(sourceFile, key, convertedFile, section.getInt("$filename.duration-seconds"))
        }
    }

    /** Reuses the cached conversion if [sourceFile] hasn't changed since it was last converted, otherwise (re-)converts it. Must be called off the main thread. */
    @Synchronized
    fun ensureConverted(sourceFile: File): AudioTrack {
        val filename = sourceFile.name
        val lastModified = sourceFile.lastModified()
        val section = config.getConfigurationSection("tracks.$filename")
        val cachedKey = section?.getString("key")
        if (section != null && section.getLong("source-modified") == lastModified) {
            val convertedName = section.getString("converted")
            if (convertedName != null && cachedKey != null) {
                val convertedFile = if (convertedName == filename) sourceFile else File(convertedFolder, convertedName)
                if (convertedFile.isFile) {
                    return AudioTrack(sourceFile, cachedKey, convertedFile, section.getInt("duration-seconds"))
                }
            }
        }

        val key = cachedKey ?: assignKey(filename)
        val durationSeconds = probeDurationSeconds(sourceFile)
        val convertedFile = if (filename.endsWith(".ogg", ignoreCase = true)) {
            sourceFile
        } else {
            convertedFolder.mkdirs()
            val target = File(convertedFolder, "$key.ogg")
            transcode(sourceFile, target)
            target
        }

        config.set("tracks.$filename.key", key)
        config.set("tracks.$filename.source-modified", lastModified)
        config.set("tracks.$filename.converted", convertedFile.name)
        config.set("tracks.$filename.duration-seconds", durationSeconds)
        manifestFile.parentFile?.mkdirs()
        config.save(manifestFile)

        return AudioTrack(sourceFile, key, convertedFile, durationSeconds)
    }

    private fun assignKey(sourceFilename: String): String {
        val base = sanitizeKey(sourceFilename)
        val used = config.getConfigurationSection("tracks")?.getKeys(false)
            ?.mapNotNull { config.getString("tracks.$it.key") }?.toSet() ?: emptySet()
        if (base !in used) return base
        var i = 2
        while ("${base}_$i" in used) i++
        return "${base}_$i"
    }

    private fun sanitizeKey(filename: String): String {
        val base = filename.substringBeforeLast('.').lowercase(Locale.ROOT)
        val cleaned = base.replace(Regex("[^a-z0-9_-]"), "_")
        return cleaned.ifBlank { "track" }
    }

    private fun transcode(sourceFile: File, targetOgg: File) {
        val process = try {
            ProcessBuilder(ffmpegPath, "-y", "-i", sourceFile.absolutePath, "-c:a", "libvorbis", "-q:a", "4", targetOgg.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw ConversionException("Could not run ffmpeg at '$ffmpegPath': ${e.message} - set 'soundfont.ffmpeg-path' in config.yml")
        }
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ConversionException("Converting '${sourceFile.name}' timed out after 300s")
        }
        if (process.exitValue() != 0 || !targetOgg.isFile) {
            throw ConversionException("ffmpeg failed to convert '${sourceFile.name}': ${output.take(500)}")
        }
    }

    /** Parses ffmpeg's own `Duration: HH:MM:SS.ss` line out of `ffmpeg -i <file>`'s stderr - avoids depending on a separate ffprobe binary/config entry. */
    private fun probeDurationSeconds(file: File): Int {
        val process = try {
            ProcessBuilder(ffmpegPath, "-i", file.absolutePath).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw ConversionException("Could not run ffmpeg at '$ffmpegPath': ${e.message} - set 'soundfont.ffmpeg-path' in config.yml")
        }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        val match = DURATION_REGEX.find(output)
            ?: throw ConversionException("Could not determine the length of '${file.name}' (ffmpeg produced no 'Duration:' line)")
        val (h, m, s) = match.destructured
        return (h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()).toInt()
    }
}
