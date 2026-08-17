package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

private const val DEFAULT_PACK_FORMAT = 34

// Assumed checkpoint hold length for a pack.yml written before release checkpoints existed (a
// flat "baseKey: filename" release_sounds entry instead of the current nested
// "baseKey: { holdMillis: filename }" - matches the single hold length that version always used.
private const val LEGACY_RELEASE_HOLD_MILLIS = 150L

/**
 * Reads a `soundpacks/<name>/pack.yml`:
 * ```yaml
 * name: My Cool Pack
 * description: Real instrument samples
 * pack_format: 34   # optional
 * sounds:
 *   harp: harp.ogg
 *   bass: bass.ogg
 * ```
 * A pack may define any subset of the 128 General MIDI instrument [InstrumentSlot]s (keyed by
 * their standard GM1 name, e.g. `acoustic_grand_piano`, `orchestral_harp`, `banjo`, ...) plus the
 * percussion slots `basedrum`/`snare`/`hat`/`cow_bell` - undefined slots fall back to the vanilla
 * note block sound at playback time. Sound files must already be Ogg Vorbis (`.ogg`): Minecraft
 * resource packs don't support other audio formats, and this plugin does not transcode.
 */
object SoundPackLoader {

    class SoundPackLoadException(message: String) : Exception(message)

    fun load(folder: File): SoundPackDefinition {
        val yamlFile = File(folder, "pack.yml")
        if (!yamlFile.isFile) throw SoundPackLoadException("Missing pack.yml in soundpacks/${folder.name}/")

        val config = YamlConfiguration.loadConfiguration(yamlFile)
        val name = config.getString("name") ?: folder.name
        val description = config.getString("description") ?: ""
        val packFormat = if (config.isInt("pack_format")) config.getInt("pack_format") else DEFAULT_PACK_FORMAT

        val soundsSection = config.getConfigurationSection("sounds")
            ?: throw SoundPackLoadException("pack.yml for '${folder.name}' has no 'sounds:' section")
        val slotFiles = parseSoundSection(soundsSection, folder, "sounds")
        if (slotFiles.isEmpty()) throw SoundPackLoadException("pack.yml for '${folder.name}' defines no sounds")

        // Optional, additive section (see SoundFontConverter.writePackYml / GmNames.RELEASE_*_SECONDS)
        // - absent entirely for hand-authored/old packs, which just get no release layer.
        val releaseSoundsSection = config.getConfigurationSection("release_sounds")
        val releaseSlotFiles = if (releaseSoundsSection != null) parseReleaseSection(releaseSoundsSection, folder) else emptyMap()
        for (slot in releaseSlotFiles.keys) {
            if (slot !in slotFiles) {
                throw SoundPackLoadException(
                    "'release_sounds' entry '${slot.key}' in soundpacks/${folder.name}/pack.yml has no matching 'sounds:' " +
                        "entry - a release layer with no main custom sound would never be triggered",
                )
            }
        }

        return SoundPackDefinition(folder.name, name, description, packFormat, slotFiles, releaseSlotFiles)
    }

    private fun parseSoundSection(section: ConfigurationSection, folder: File, sectionName: String): LinkedHashMap<InstrumentSlot, File> {
        val slotFiles = LinkedHashMap<InstrumentSlot, File>()
        for (key in section.getKeys(false)) {
            val slot = InstrumentSlot.byKey(key)
                ?: throw SoundPackLoadException(
                    "Unknown instrument slot '$key' in soundpacks/${folder.name}/pack.yml ('$sectionName:') - " +
                        "must be a standard GM1 instrument name (e.g. 'acoustic_grand_piano', 'orchestral_harp') " +
                        "or one of basedrum/snare/hat/cow_bell",
                )
            val filename = section.getString(key)
                ?: throw SoundPackLoadException("Slot '$key' in soundpacks/${folder.name}/pack.yml ('$sectionName:') has no filename")
            if (!filename.endsWith(".ogg", ignoreCase = true)) {
                throw SoundPackLoadException(
                    "'$filename' (slot '$key' in soundpacks/${folder.name}/, '$sectionName:') must be an .ogg file - " +
                        "Minecraft resource packs only support Ogg Vorbis audio, and this plugin does not convert formats",
                )
            }
            val soundFile = File(folder, filename)
            if (!soundFile.isFile) {
                throw SoundPackLoadException("Sound file '$filename' for slot '$key' not found in soundpacks/${folder.name}/")
            }
            slotFiles[slot] = soundFile
        }
        return slotFiles
    }

    /**
     * Parses `release_sounds:`, which nests one sub-map of `holdMillis: filename` per base slot
     * (see [SoundFontConverter.writePackYml][io.github.jwyoon1220.openMCMelody.soundfont.SoundFontConverter]).
     * Also accepts the older, pre-checkpoint flat shape (`baseKey: filename` directly, a plain
     * string value instead of a nested section) for packs built before multiple checkpoints
     * existed, treating it as a single checkpoint at [LEGACY_RELEASE_HOLD_MILLIS] - so an
     * already-built pack keeps working without forcing an immediate rebuild.
     */
    private fun parseReleaseSection(section: ConfigurationSection, folder: File): LinkedHashMap<InstrumentSlot, List<ReleaseCheckpoint>> {
        val result = LinkedHashMap<InstrumentSlot, List<ReleaseCheckpoint>>()
        for (key in section.getKeys(false)) {
            val slot = InstrumentSlot.byKey(key)
                ?: throw SoundPackLoadException(
                    "Unknown instrument slot '$key' in soundpacks/${folder.name}/pack.yml ('release_sounds:') - " +
                        "must be a standard GM1 instrument name or one of basedrum/snare/hat/cow_bell",
                )
            val subSection = section.getConfigurationSection(key)
            val checkpoints = subSection?.getKeys(false)?.map { holdKey ->
                val holdMillis = holdKey.toLongOrNull()
                    ?: throw SoundPackLoadException("'release_sounds.$key' in soundpacks/${folder.name}/pack.yml has a non-numeric checkpoint key '$holdKey'")
                ReleaseCheckpoint(holdMillis, resolveReleaseFile(subSection.getString(holdKey), folder, key))
            }
                ?: listOf(ReleaseCheckpoint(LEGACY_RELEASE_HOLD_MILLIS, resolveReleaseFile(section.getString(key), folder, key)))
            if (checkpoints.isEmpty()) throw SoundPackLoadException("'release_sounds.$key' in soundpacks/${folder.name}/pack.yml defines no checkpoints")
            result[slot] = checkpoints.sortedBy { it.holdMillis }
        }
        return result
    }

    private fun resolveReleaseFile(filename: String?, folder: File, key: String): File {
        if (filename == null) throw SoundPackLoadException("Slot '$key' in soundpacks/${folder.name}/pack.yml ('release_sounds:') has no filename")
        if (!filename.endsWith(".ogg", ignoreCase = true)) {
            throw SoundPackLoadException(
                "'$filename' (slot '$key' in soundpacks/${folder.name}/, 'release_sounds:') must be an .ogg file - " +
                    "Minecraft resource packs only support Ogg Vorbis audio, and this plugin does not convert formats",
            )
        }
        val soundFile = File(folder, filename)
        if (!soundFile.isFile) throw SoundPackLoadException("Sound file '$filename' for slot '$key' not found in soundpacks/${folder.name}/")
        return soundFile
    }

    /**
     * Reads only pack.yml's `source-soundfont` field (the sf2/dls filename
     * [io.github.jwyoon1220.openMCMelody.soundfont.SoundFontConverter] recorded when it
     * auto-generated this pack), skipping the sound-file validation [load] does - used by
     * `/midi soundpack rebuild` to find what to re-extract from. Returns null for hand-authored
     * packs (no such field) or a folder with no pack.yml at all.
     */
    fun sourceSoundfont(folder: File): String? {
        val yamlFile = File(folder, "pack.yml")
        if (!yamlFile.isFile) return null
        return YamlConfiguration.loadConfiguration(yamlFile).getString("source-soundfont")
    }
}
