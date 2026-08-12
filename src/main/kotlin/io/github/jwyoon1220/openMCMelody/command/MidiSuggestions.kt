package io.github.jwyoon1220.openMCMelody.command

import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.io.File

object MidiSuggestions {

    fun midiFiles(midiFolder: File): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        val remaining = builder.remaining.lowercase()
        midiFolder.listFiles { f -> f.isFile && f.name.endsWith(".mid", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?.filter { it.lowercase().startsWith(remaining) }
            ?.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    fun playlistNames(playlistManager: PlaylistManager): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        val remaining = builder.remaining.lowercase()
        playlistManager.list()
            .filter { it.lowercase().startsWith(remaining) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    fun soundPacks(soundPackManager: SoundPackManager): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        val remaining = builder.remaining.lowercase()
        soundPackManager.listAvailable()
            .filter { it.lowercase().startsWith(remaining) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    fun soundFonts(soundfontsFolder: File): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        val remaining = builder.remaining.lowercase()
        soundfontsFolder.listFiles { f -> f.isFile && (f.name.endsWith(".sf2", ignoreCase = true) || f.name.endsWith(".dls", ignoreCase = true)) }
            ?.map { it.name }
            ?.sorted()
            ?.filter { it.lowercase().startsWith(remaining) }
            ?.forEach { builder.suggest(it) }
        builder.buildFuture()
    }
}
