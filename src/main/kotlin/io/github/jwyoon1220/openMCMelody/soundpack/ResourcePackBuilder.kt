package io.github.jwyoon1220.openMCMelody.soundpack

import io.github.jwyoon1220.openMCMelody.web.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs a [SoundPackDefinition] into a Minecraft resource pack zip in memory: `pack.mcmeta`,
 * `assets/openmcmelody/sounds.json`, and one `assets/openmcmelody/sounds/<slot>.ogg` per defined
 * slot. The sound event name for slot `harp` ends up being `openmcmelody:harp` in-game.
 */
object ResourcePackBuilder {

    class BuiltPack(val bytes: ByteArray, val sha1: ByteArray)

    fun build(definition: SoundPackDefinition): BuiltPack {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            writeEntry(zip, "pack.mcmeta", packMcmeta(definition))
            writeEntry(zip, "assets/openmcmelody/sounds.json", soundsJson(definition))
            for ((slot, file) in definition.slotFiles) {
                zip.putNextEntry(ZipEntry("assets/openmcmelody/sounds/${slot.key}.ogg"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val bytes = buffer.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
        return BuiltPack(bytes, sha1)
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun packMcmeta(definition: SoundPackDefinition): String =
        Json.stringify(mapOf("pack" to mapOf("pack_format" to definition.packFormat, "description" to definition.description)))

    // A "sounds" entry with no "namespace:" prefix resolves against the "minecraft" namespace,
    // NOT the current pack's own namespace - confirmed from a real client log ("File
    // minecraft:sounds/harp.ogg does not exist"). Must explicitly prefix with our own namespace
    // to point at assets/openmcmelody/sounds/<slot>.ogg (where build() above places the file).
    private fun soundsJson(definition: SoundPackDefinition): String =
        Json.stringify(
            definition.slotFiles.keys.associate { slot ->
                slot.key to mapOf("sounds" to listOf(mapOf("name" to "openmcmelody:${slot.key}", "stream" to false)))
            },
        )
}
