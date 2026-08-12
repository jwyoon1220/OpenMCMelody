package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.midi.InstrumentSlot
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

private const val DEFAULT_PACK_FORMAT = 34

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

        val slotFiles = LinkedHashMap<InstrumentSlot, File>()
        for (key in soundsSection.getKeys(false)) {
            val slot = InstrumentSlot.byKey(key)
                ?: throw SoundPackLoadException(
                    "Unknown instrument slot '$key' in soundpacks/${folder.name}/pack.yml - " +
                        "must be a standard GM1 instrument name (e.g. 'acoustic_grand_piano', 'orchestral_harp') " +
                        "or one of basedrum/snare/hat/cow_bell",
                )
            val filename = soundsSection.getString(key)
                ?: throw SoundPackLoadException("Slot '$key' in soundpacks/${folder.name}/pack.yml has no filename")
            if (!filename.endsWith(".ogg", ignoreCase = true)) {
                throw SoundPackLoadException(
                    "'$filename' (slot '$key' in soundpacks/${folder.name}/) must be an .ogg file - " +
                        "Minecraft resource packs only support Ogg Vorbis audio, and this plugin does not convert formats",
                )
            }
            val soundFile = File(folder, filename)
            if (!soundFile.isFile) {
                throw SoundPackLoadException("Sound file '$filename' for slot '$key' not found in soundpacks/${folder.name}/")
            }
            slotFiles[slot] = soundFile
        }
        if (slotFiles.isEmpty()) throw SoundPackLoadException("pack.yml for '${folder.name}' defines no sounds")

        return SoundPackDefinition(folder.name, name, description, packFormat, slotFiles)
    }
}
