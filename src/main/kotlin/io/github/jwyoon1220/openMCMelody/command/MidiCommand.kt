package io.github.jwyoon1220.openMCMelody.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.jwyoon1220.openMCMelody.Permissions
import io.github.jwyoon1220.openMCMelody.midi.BukkitExecutors
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.midi.SongFiles
import io.github.jwyoon1220.openMCMelody.playback.PlayMode
import io.github.jwyoon1220.openMCMelody.playback.PlayModeManager
import io.github.jwyoon1220.openMCMelody.playback.PlaybackManager
import io.github.jwyoon1220.openMCMelody.playback.PlaybackSession
import io.github.jwyoon1220.openMCMelody.playback.PlaybackState
import io.github.jwyoon1220.openMCMelody.playback.SessionMode
import io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager
import io.github.jwyoon1220.openMCMelody.soundfont.SoundFontConverter
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackLoader
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import io.github.jwyoon1220.openMCMelody.web.WebAuthManager
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

private const val PERM_ADMIN = Permissions.ADMIN
private const val PERM_PLAYLIST = Permissions.PLAYLIST
private const val PERM_STATUS = Permissions.STATUS

class MidiCommand(
    private val plugin: Plugin,
    private val midiFolder: File,
    private val songCache: SongCache,
    private val playbackManager: PlaybackManager,
    private val playlistManager: PlaylistManager,
    private val webAuthManager: WebAuthManager?,
    private val soundPackManager: SoundPackManager,
    private val publicUrl: String,
    private val soundfontsFolder: File,
    private val soundpacksFolder: File,
    private val soundFontConverter: SoundFontConverter,
    private val playModeManager: PlayModeManager,
) {
    private val mainThreadExecutor: Executor = BukkitExecutors.main(plugin)

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("midi")
            .then(
                Commands.literal("list")
                    .requires { it.sender.hasPermission(PERM_ADMIN) }
                    .executes { handleList(it) },
            )
            .then(
                Commands.literal("verify")
                    .then(
                        Commands.argument("code", StringArgumentType.word())
                            .executes { handleVerify(it) },
                    ),
            )
            .then(
                Commands.literal("play")
                    .requires { it.sender.hasPermission(PERM_ADMIN) }
                    .then(
                        // Deliberately NOT a greedy string here, unlike the targeted branch below.
                        // A greedy filename always consumes the entire remaining input - including
                        // what would otherwise be a target name - so Brigadier's parser matched
                        // this branch for every input and the targeted branch below could never be
                        // reached at all. Quotable single-token string avoids that ambiguity; a
                        // filename with spaces just needs quotes ("My Song.mid") when played to self.
                        Commands.argument("filename", StringArgumentType.string())
                            .suggests(MidiSuggestions.midiFiles(midiFolder))
                            .executes { handlePlay(it, false) },
                    )
                    .then(
                        // Once target is present it consumes the first token unambiguously, so the
                        // trailing filename here can safely be greedy (spaces work without quoting).
                        Commands.argument("target", ArgumentTypes.players())
                            .then(
                                Commands.argument("filename", StringArgumentType.greedyString())
                                    .suggests(MidiSuggestions.midiFiles(midiFolder))
                                    .executes { handlePlay(it, true) },
                            ),
                    ),
            )
            .then(
                Commands.literal("stop")
                    .requires { it.sender.hasPermission(PERM_ADMIN) }
                    .executes { handleStop(it, false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .executes { handleStop(it, true) },
                    ),
            )
            .then(
                Commands.literal("status")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .executes { handleStatus(it, false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(PERM_ADMIN) }
                            .executes { handleStatus(it, true) },
                    ),
            )
            .then(
                Commands.literal("pause")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .executes { handlePause(it, false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(PERM_ADMIN) }
                            .executes { handlePause(it, true) },
                    ),
            )
            .then(
                Commands.literal("resume")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .executes { handleResume(it, false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(PERM_ADMIN) }
                            .executes { handleResume(it, true) },
                    ),
            )
            .then(
                Commands.literal("seek")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .then(
                        Commands.argument("position", StringArgumentType.word())
                            .executes { handleSeek(it, false) },
                    )
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(PERM_ADMIN) }
                            .then(
                                Commands.argument("position", StringArgumentType.word())
                                    .executes { handleSeek(it, true) },
                            ),
                    ),
            )
            .then(
                Commands.literal("mode")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .executes { handleShowMode(it) }
                    .then(Commands.literal("tick").executes { handleSetMode(it, PlayMode.TICK) })
                    .then(Commands.literal("instant").executes { handleSetMode(it, PlayMode.INSTANT) }),
            )
            .then(
                Commands.literal("playlist")
                    .requires { it.sender.hasPermission(PERM_PLAYLIST) }
                    .then(Commands.literal("list").executes { handlePlaylistList(it) })
                    .then(
                        Commands.literal("show").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.playlistNames(playlistManager))
                                .executes { handlePlaylistShow(it) },
                        ),
                    )
                    .then(
                        Commands.literal("create").then(
                            Commands.argument("name", StringArgumentType.string())
                                .executes { handlePlaylistCreate(it) },
                        ),
                    )
                    .then(
                        Commands.literal("delete").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.playlistNames(playlistManager))
                                .executes { handlePlaylistDelete(it) },
                        ),
                    )
                    .then(
                        Commands.literal("add").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.playlistNames(playlistManager))
                                .then(
                                    Commands.argument("filename", StringArgumentType.string())
                                        .suggests(MidiSuggestions.midiFiles(midiFolder))
                                        .executes { handlePlaylistAdd(it) },
                                ),
                        ),
                    )
                    .then(
                        Commands.literal("remove").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.playlistNames(playlistManager))
                                .then(
                                    Commands.argument("entry", StringArgumentType.string())
                                        .executes { handlePlaylistRemove(it) },
                                ),
                        ),
                    )
                    .then(
                        Commands.literal("play")
                            .then(
                                // Target-first, matching vanilla commands like /effect give or
                                // /give (target before the rest of the arguments).
                                Commands.argument("name", StringArgumentType.string())
                                    .suggests(MidiSuggestions.playlistNames(playlistManager))
                                    .executes { handlePlaylistPlay(it, false) },
                            )
                            .then(
                                Commands.argument("target", ArgumentTypes.players())
                                    .then(
                                        Commands.argument("name", StringArgumentType.string())
                                            .suggests(MidiSuggestions.playlistNames(playlistManager))
                                            .executes { handlePlaylistPlay(it, true) },
                                    ),
                            ),
                    ),
            )
            .then(
                Commands.literal("soundpack")
                    .requires { it.sender.hasPermission(PERM_ADMIN) }
                    .then(Commands.literal("list").executes { handleSoundpackList(it) })
                    .then(
                        Commands.literal("build").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackBuild(it) },
                        ),
                    )
                    .then(
                        Commands.literal("activate").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackActivate(it) },
                        ),
                    )
                    .then(Commands.literal("deactivate").executes { handleSoundpackDeactivate(it) })
                    .then(
                        Commands.literal("fromsf2").then(
                            Commands.argument("sf2file", StringArgumentType.string())
                                .suggests(MidiSuggestions.soundFonts(soundfontsFolder))
                                .executes { handleSoundpackFromSf2(it) },
                        ),
                    )
                    .then(
                        Commands.literal("rebuild").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(MidiSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackRebuild(it) },
                        ),
                    ),
            )
            .build()

    // ---- list ----

    private fun handleList(ctx: CommandContext<CommandSourceStack>): Int {
        val files = listMidiFiles()
        val sender = ctx.source.sender
        if (files.isEmpty()) {
            sender.sendMessage("No MIDI files found in the midi/ folder.")
        } else {
            sender.sendMessage("MIDI files (${files.size}): ${files.joinToString(", ")}")
        }
        return Command.SINGLE_SUCCESS
    }

    // ---- web UI login ----

    private fun handleVerify(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val player = sender as? Player ?: run {
            sender.sendMessage("Only an in-game player can verify a web UI login.")
            return Command.SINGLE_SUCCESS
        }
        val auth = webAuthManager ?: run {
            sender.sendMessage("The web UI is disabled on this server.")
            return Command.SINGLE_SUCCESS
        }
        val code = StringArgumentType.getString(ctx, "code")
        if (auth.verifyCode(player.uniqueId, player.name, code)) {
            sender.sendMessage("Web UI login confirmed. You can return to the browser now.")
        } else {
            sender.sendMessage("That code is invalid or expired. Request a new one from the web UI.")
        }
        return Command.SINGLE_SUCCESS
    }

    // ---- play / stop ----

    private fun handlePlay(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val filename = StringArgumentType.getString(ctx, "filename")
        val file = File(midiFolder, filename)
        if (!file.isFile) {
            sender.sendMessage("MIDI file not found: $filename")
            return Command.SINGLE_SUCCESS
        }
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage("Loading '$filename'...")
        songCache.get(file).consumeOnMainThread { song, throwable ->
            if (throwable != null || song == null) {
                sender.sendMessage("Failed to parse '$filename': ${throwable?.message}")
            } else {
                playbackManager.startSession(targets, song, SessionMode.SINGLE_SONG)
                sender.sendMessage("Now playing '$filename' to ${targets.size} target(s).")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun handleStop(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }
        playbackManager.stop(targets)
        sender.sendMessage("Stopped MIDI playback for ${targets.size} target(s).")
        return Command.SINGLE_SUCCESS
    }

    // ---- status ----

    private fun handleStatus(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val target: Player? = if (hasArg) {
            resolvePlayers(ctx, "target").firstOrNull()?.let { Bukkit.getPlayer(it) }
        } else {
            sender as? Player
        }
        if (target == null) {
            sender.sendMessage(if (hasArg) "Player not found or offline." else "Console has no MIDI status; specify a player.")
            return Command.SINGLE_SUCCESS
        }

        val session = playbackManager.statusFor(target.uniqueId)
        if (session == null) {
            sender.sendMessage("${target.name} is not listening to anything right now.")
            return Command.SINGLE_SUCCESS
        }

        val elapsedSec = session.cursorTick / 20
        val totalSec = session.song.totalTicks / 20
        val percent = if (session.song.totalTicks > 0) {
            (session.cursorTick * 100 / session.song.totalTicks).coerceIn(0, 100)
        } else 100
        val stateLabel = when (session.state) {
            PlaybackState.PAUSED -> " (paused)"
            PlaybackState.LOADING -> " (loading next song)"
            PlaybackState.PLAYING -> ""
        }
        val songLabel = if (session.mode == SessionMode.PLAYLIST) {
            val playlistSize = playlistManager.get(session.playlistName ?: "")?.songs?.size ?: 1
            "playlist '${session.playlistName}' (${session.songIndex + 1}/$playlistSize) - ${session.song.sourceFileName}"
        } else {
            session.song.sourceFileName
        }
        sender.sendMessage("${target.name}: $songLabel - ${formatTime(elapsedSec)}/${formatTime(totalSec)} ($percent%)$stateLabel")
        return Command.SINGLE_SUCCESS
    }

    // ---- pause / resume ----

    private fun handlePause(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }
        val affected = playbackManager.pause(targets)
        sender.sendMessage(pauseResumeMessage("Paused", affected, targets))
        return Command.SINGLE_SUCCESS
    }

    private fun handleResume(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }
        val affected = playbackManager.resume(targets)
        sender.sendMessage(pauseResumeMessage("Resumed", affected, targets))
        return Command.SINGLE_SUCCESS
    }

    private fun pauseResumeMessage(verb: String, affected: Set<PlaybackSession>, targets: Set<UUID>): String {
        if (affected.isEmpty()) return "Nothing is playing for that target."
        val totalListeners = affected.sumOf { it.targets.size }
        val extra = (totalListeners - targets.size).coerceAtLeast(0)
        return "$verb playback for ${targets.size} target(s)." + if (extra > 0) " (affects $extra other listener(s) sharing the same session)" else ""
    }

    // ---- seek ----

    /**
     * Position is seconds, either absolute ("90") or signed-relative ("+15"/"-30"). Relative offsets
     * are resolved per-target (each may be at a different position, e.g. after a previous seek split
     * them into separate sessions - see [PlaybackManager.seek]) rather than applying one shared delta.
     */
    private fun handleSeek(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }
        val positionArg = StringArgumentType.getString(ctx, "position")
        val seconds = positionArg.toIntOrNull()
        if (seconds == null) {
            sender.sendMessage("Invalid position '$positionArg' - use an absolute number of seconds (e.g. 90) or a relative offset (e.g. +15, -30).")
            return Command.SINGLE_SUCCESS
        }

        val affected = LinkedHashSet<PlaybackSession>()
        if (positionArg.startsWith("+") || positionArg.startsWith("-")) {
            for (uuid in targets) {
                val current = playbackManager.statusFor(uuid) ?: continue
                affected += playbackManager.seek(setOf(uuid), current.cursorTick + seconds * 20)
            }
        } else {
            affected += playbackManager.seek(targets, seconds * 20)
        }

        sender.sendMessage(if (affected.isEmpty()) "Nothing is playing for that target." else "Seeked playback for ${targets.size} target(s).")
        return Command.SINGLE_SUCCESS
    }

    // ---- play mode ----

    private fun handleShowMode(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val player = sender as? Player ?: run {
            sender.sendMessage("Only an in-game player has a playback mode.")
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage("Your playback mode: ${modeLabel(playModeManager.modeOf(player.uniqueId))}. Change with /midi mode <tick|instant>.")
        return Command.SINGLE_SUCCESS
    }

    private fun handleSetMode(ctx: CommandContext<CommandSourceStack>, mode: PlayMode): Int {
        val sender = ctx.source.sender
        val player = sender as? Player ?: run {
            sender.sendMessage("Only an in-game player can set a playback mode.")
            return Command.SINGLE_SUCCESS
        }
        playModeManager.setMode(player.uniqueId, mode)
        sender.sendMessage("Playback mode set to ${modeLabel(mode)}.")
        return Command.SINGLE_SUCCESS
    }

    private fun modeLabel(mode: PlayMode): String = when (mode) {
        PlayMode.TICK -> "tick (default - locked to the server's 50ms tick)"
        PlayMode.INSTANT -> "instant (fires each note at its real timing, not tick-locked)"
    }

    // ---- playlist ----

    private fun handlePlaylistList(ctx: CommandContext<CommandSourceStack>): Int {
        val names = playlistManager.list()
        ctx.source.sender.sendMessage(if (names.isEmpty()) "No playlists yet." else "Playlists: ${names.joinToString(", ")}")
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistShow(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        val playlist = playlistManager.get(name)
        if (playlist == null) {
            sender.sendMessage("Playlist '$name' not found.")
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage(
            if (playlist.songs.isEmpty()) "Playlist '$name' is empty."
            else "Playlist '$name': " + playlist.songs.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(", "),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistCreate(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        sender.sendMessage(if (playlistManager.create(name)) "Created playlist '$name'." else "Playlist '$name' already exists.")
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistDelete(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        sender.sendMessage(if (playlistManager.delete(name)) "Deleted playlist '$name'." else "Playlist '$name' not found.")
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistAdd(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val filename = StringArgumentType.getString(ctx, "filename")
        val sender = ctx.source.sender
        if (!File(midiFolder, filename).isFile) {
            sender.sendMessage("MIDI file not found: $filename")
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage(if (playlistManager.addSong(name, filename)) "Added '$filename' to playlist '$name'." else "Playlist '$name' not found.")
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistRemove(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val entry = StringArgumentType.getString(ctx, "entry")
        val sender = ctx.source.sender
        sender.sendMessage(
            if (playlistManager.removeSong(name, entry)) "Removed '$entry' from playlist '$name'."
            else "Could not find '$entry' in playlist '$name'.",
        )
        return Command.SINGLE_SUCCESS
    }

    private fun handlePlaylistPlay(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        val playlist = playlistManager.get(name)
        if (playlist == null || playlist.songs.isEmpty()) {
            sender.sendMessage(if (playlist == null) "Playlist '$name' not found." else "Playlist '$name' is empty.")
            return Command.SINGLE_SUCCESS
        }
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender) ?: run {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return Command.SINGLE_SUCCESS
        }
        val firstSongName = playlist.songs[0]
        val firstFile = File(midiFolder, firstSongName)
        if (!firstFile.isFile) {
            sender.sendMessage("First song '$firstSongName' is missing from midi/.")
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage("Loading playlist '$name'...")
        songCache.get(firstFile).consumeOnMainThread { song, throwable ->
            if (throwable != null || song == null) {
                sender.sendMessage("Failed to parse '$firstSongName': ${throwable?.message}")
            } else {
                playbackManager.startSession(targets, song, SessionMode.PLAYLIST, name)
                sender.sendMessage("Now playing playlist '$name' to ${targets.size} target(s).")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    // ---- soundpack ----

    private fun handleSoundpackList(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val names = soundPackManager.listAvailable()
        if (names.isEmpty()) {
            sender.sendMessage("No soundpacks found under soundpacks/ (each needs its own folder with a pack.yml).")
            return Command.SINGLE_SUCCESS
        }
        val active = soundPackManager.activeName
        sender.sendMessage(
            "Soundpacks: " + names.joinToString(", ") { if (it == active) "$it (active)" else it },
        )
        return Command.SINGLE_SUCCESS
    }

    private fun handleSoundpackBuild(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        sender.sendMessage("Building soundpack '$name'...")
        buildSoundpackAsync(name).consumeOnMainThread { result, throwable ->
            if (throwable != null) {
                sender.sendMessage("Failed to build '$name': ${throwable.message}")
            } else {
                sender.sendMessage("Built '$name' (${result!!.definition.slotFiles.size} sound(s)) -> soundpacks/$name.zip")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun handleSoundpackActivate(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        if (publicUrl.isBlank()) {
            sender.sendMessage("Set 'web.public-url' in config.yml first (the URL players' clients can reach this server at) before activating a soundpack.")
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage("Building and activating soundpack '$name'...")
        buildSoundpackAsync(name).consumeOnMainThread { _, throwable ->
            if (throwable != null) {
                sender.sendMessage("Failed to build '$name': ${throwable.message}")
                return@consumeOnMainThread
            }
            try {
                soundPackManager.applyActivation(name, publicUrl)
                sender.sendMessage("Soundpack '$name' activated and pushed to ${Bukkit.getOnlinePlayers().size} online player(s).")
            } catch (e: SoundPackLoader.SoundPackLoadException) {
                sender.sendMessage("Failed to activate '$name': ${e.message}")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun handleSoundpackDeactivate(ctx: CommandContext<CommandSourceStack>): Int {
        soundPackManager.deactivate()
        ctx.source.sender.sendMessage("Soundpack deactivated - new notes will use vanilla note block sounds.")
        return Command.SINGLE_SUCCESS
    }

    private fun buildSoundpackAsync(name: String): CompletableFuture<SoundPackManager.BuildResult> =
        CompletableFuture.supplyAsync({ soundPackManager.build(name) }, BukkitExecutors.async(plugin))

    private fun handleSoundpackFromSf2(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val sf2Name = StringArgumentType.getString(ctx, "sf2file")

        val sf2File = File(soundfontsFolder, sf2Name)
        if (!sf2File.isFile) {
            sender.sendMessage("Soundfont file not found in soundfonts/: $sf2Name")
            return Command.SINGLE_SUCCESS
        }
        val packName = sf2File.nameWithoutExtension

        sender.sendMessage("Extracting instruments from '$sf2Name' into soundpack '$packName'... this can take a minute.")
        runSoundFontConversion(sender, sf2File, packName)
        return Command.SINGLE_SUCCESS
    }

    /**
     * Re-runs extraction for a soundpack that was previously built with `fromsf2`, using whatever
     * the source soundfont/extractor logic currently does - e.g. to pick up longer sustained-note
     * samples after a plugin update, without the caller needing to remember/re-type the original
     * `.sf2`/`.dls` filename (recorded in the pack's pack.yml, see [SoundPackLoader.sourceSoundfont]).
     */
    private fun handleSoundpackRebuild(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val packName = StringArgumentType.getString(ctx, "name")

        val packFolder = File(soundpacksFolder, packName)
        if (!packFolder.isDirectory) {
            sender.sendMessage("No soundpack folder named '$packName' under soundpacks/")
            return Command.SINGLE_SUCCESS
        }
        val sf2Name = SoundPackLoader.sourceSoundfont(packFolder)
        if (sf2Name == null) {
            sender.sendMessage("Soundpack '$packName' has no recorded source soundfont - it wasn't auto-generated by /midi soundpack fromsf2, so it can't be rebuilt automatically.")
            return Command.SINGLE_SUCCESS
        }
        val sf2File = File(soundfontsFolder, sf2Name)
        if (!sf2File.isFile) {
            sender.sendMessage("Source soundfont '$sf2Name' for '$packName' is no longer in soundfonts/, so it can't be rebuilt.")
            return Command.SINGLE_SUCCESS
        }

        sender.sendMessage("Re-extracting instruments for soundpack '$packName' from '$sf2Name'... this can take a minute.")
        runSoundFontConversion(sender, sf2File, packName)
        return Command.SINGLE_SUCCESS
    }

    private fun runSoundFontConversion(sender: CommandSender, sf2File: File, packName: String) {
        val mainExecutor = BukkitExecutors.main(plugin)
        CompletableFuture.supplyAsync(
            {
                soundFontConverter.convert(sf2File, File(soundpacksFolder, packName), packName) { percent, message ->
                    mainExecutor.execute { sender.sendActionBar(Component.text("$percent%: $message")) }
                }
            },
            BukkitExecutors.async(plugin),
        ).consumeOnMainThread { result, throwable ->
            if (throwable != null) {
                sender.sendMessage("Failed to convert '${sf2File.name}': ${throwable.message}")
            } else if (result != null) {
                sender.sendMessage(
                    "Built soundpack '$packName' from '${sf2File.name}': ${result.mainSlotCount} instrument(s)" +
                        (if (result.releaseSlotCount > 0) " (${result.releaseSlotCount} with a release tail)" else "") +
                        (if (result.failedConversions > 0) ", ${result.failedConversions} failed to encode" else "") +
                        ". Run /midi soundpack build $packName to verify, then activate it.",
                )
            }
        }
    }

    // ---- helpers ----

    private fun listMidiFiles(): List<String> =
        midiFolder.listFiles { f -> f.isFile && SongFiles.isPlayable(f.name) }
            ?.map { it.name }?.sorted() ?: emptyList()

    private fun resolvePlayers(ctx: CommandContext<CommandSourceStack>, name: String): Set<UUID> {
        val resolver = ctx.getArgument(name, PlayerSelectorArgumentResolver::class.java)
        return resolver.resolve(ctx.source).map { it.uniqueId }.toSet()
    }

    private fun resolveTargetsOrSelf(ctx: CommandContext<CommandSourceStack>, argName: String, hasArg: Boolean, sender: CommandSender): Set<UUID>? {
        val targets = if (hasArg) resolvePlayers(ctx, argName) else (sender as? Player)?.let { setOf(it.uniqueId) }
        return targets?.takeIf { it.isNotEmpty() }
    }

    private fun formatTime(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun <T> CompletableFuture<T>.consumeOnMainThread(action: (T?, Throwable?) -> Unit) {
        if (isDone) {
            try {
                action(join(), null)
            } catch (e: Exception) {
                action(null, e)
            }
            return
        }
        whenCompleteAsync({ value, throwable -> action(value, throwable) }, mainThreadExecutor)
    }
}
