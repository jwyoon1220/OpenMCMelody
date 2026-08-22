package io.github.jwyoon1220.openMCMelody.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.jwyoon1220.openMCMelody.Permissions
import io.github.jwyoon1220.openMCMelody.audio.AudioFiles
import io.github.jwyoon1220.openMCMelody.audio.AudioLibrary
import io.github.jwyoon1220.openMCMelody.audio.AudioPlaybackManager
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

/**
 * Builds the `/openmcmelody` (and `/ommc` alias) Brigadier command tree - see [build]. Registered
 * twice by [io.github.jwyoon1220.openMCMelody.OpenMCMelody.onEnable] under both root names,
 * sharing this one instance and its wiring to every other manager.
 */
class ScoreCommand(
    private val plugin: Plugin,
    private val scoresFolder: File,
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
    private val audioFolder: File,
    private val audioLibrary: AudioLibrary,
    private val audioPlaybackManager: AudioPlaybackManager,
) {
    private val mainThreadExecutor: Executor = BukkitExecutors.main(plugin)

    fun build(rootName: String = "openmcmelody"): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(rootName)
            .then(
                Commands.literal("list")
                    .requires { it.sender.hasPermission(Permissions.ADMIN) }
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
                    .requires { it.sender.hasPermission(Permissions.ADMIN) }
                    .then(
                        Commands.argument("filename", StringArgumentType.string())
                            .suggests(ScoreSuggestions.scoreFiles(scoresFolder))
                            .executes { handlePlay(it, hasTargetArg = false) },
                    )
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .then(
                                Commands.argument("filename", StringArgumentType.greedyString())
                                    .suggests(ScoreSuggestions.scoreFiles(scoresFolder))
                                    .executes { handlePlay(it, hasTargetArg = true) },
                            ),
                    ),
            )
            .then(
                Commands.literal("stop")
                    .requires { it.sender.hasPermission(Permissions.ADMIN) }
                    .executes { handleStop(it, hasTargetArg = false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .executes { handleStop(it, hasTargetArg = true) },
                    ),
            )
            .then(
                Commands.literal("status")
                    .requires { it.sender.hasPermission(Permissions.STATUS) }
                    .executes { handleStatus(it, hasArg = false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .executes { handleStatus(it, hasArg = true) },
                    ),
            )
            .then(
                Commands.literal("pause")
                    .requires { it.sender.hasPermission(Permissions.STATUS) }
                    .executes { handlePause(it, hasArg = false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .executes { handlePause(it, hasArg = true) },
                    ),
            )
            .then(
                Commands.literal("resume")
                    .requires { it.sender.hasPermission(Permissions.STATUS) }
                    .executes { handleResume(it, hasArg = false) }
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .executes { handleResume(it, hasArg = true) },
                    ),
            )
            .then(
                Commands.literal("seek")
                    .requires { it.sender.hasPermission(Permissions.STATUS) }
                    .then(
                        Commands.argument("position", StringArgumentType.word())
                            .executes { handleSeek(it, hasTargetArg = false) },
                    )
                    .then(
                        Commands.argument("target", ArgumentTypes.players())
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .then(
                                Commands.argument("position", StringArgumentType.word())
                                    .executes { handleSeek(it, hasTargetArg = true) },
                            ),
                    ),
            )
            .then(
                Commands.literal("mode")
                    .requires { it.sender.hasPermission(Permissions.STATUS) }
                    .executes { handleShowMode(it) }
                    .then(Commands.literal("tick").executes { handleSetMode(it, PlayMode.TICK) })
                    .then(Commands.literal("instant").executes { handleSetMode(it, PlayMode.INSTANT) }),
            )
            .then(
                Commands.literal("playlist")
                    .requires { it.sender.hasPermission(Permissions.PLAYLIST) }
                    .then(Commands.literal("list").executes { handlePlaylistList(it) })
                    .then(
                        Commands.literal("show").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.playlistNames(playlistManager))
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
                                .suggests(ScoreSuggestions.playlistNames(playlistManager))
                                .executes { handlePlaylistDelete(it) },
                        ),
                    )
                    .then(
                        Commands.literal("add").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.playlistNames(playlistManager))
                                .then(
                                    Commands.argument("filename", StringArgumentType.string())
                                        .suggests(ScoreSuggestions.scoreFiles(scoresFolder))
                                        .executes { handlePlaylistAdd(it) },
                                ),
                        ),
                    )
                    .then(
                        Commands.literal("remove").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.playlistNames(playlistManager))
                                .then(
                                    Commands.argument("entry", StringArgumentType.string())
                                        .executes { handlePlaylistRemove(it) },
                                ),
                        ),
                    )
                    .then(
                        Commands.literal("play")
                            .then(
                                Commands.argument("name", StringArgumentType.string())
                                    .suggests(ScoreSuggestions.playlistNames(playlistManager))
                                    .executes { handlePlaylistPlay(it, hasTargetArg = false) },
                            )
                            .then(
                                Commands.argument("target", ArgumentTypes.players())
                                    .then(
                                        Commands.argument("name", StringArgumentType.string())
                                            .suggests(ScoreSuggestions.playlistNames(playlistManager))
                                            .executes { handlePlaylistPlay(it, hasTargetArg = true) },
                                    ),
                            ),
                    ),
            )
            .then(
                Commands.literal("soundpack")
                    .requires { it.sender.hasPermission(Permissions.ADMIN) }
                    .then(Commands.literal("list").executes { handleSoundpackList(it) })
                    .then(
                        Commands.literal("build").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackBuild(it) },
                        ),
                    )
                    .then(
                        Commands.literal("activate").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackActivate(it) },
                        ),
                    )
                    .then(Commands.literal("deactivate").executes { handleSoundpackDeactivate(it) })
                    .then(
                        Commands.literal("fromsf2").then(
                            Commands.argument("sf2file", StringArgumentType.string())
                                .suggests(ScoreSuggestions.soundFonts(soundfontsFolder))
                                .executes { handleSoundpackFromSf2(it) },
                        ),
                    )
                    .then(
                        Commands.literal("rebuild").then(
                            Commands.argument("name", StringArgumentType.string())
                                .suggests(ScoreSuggestions.soundPacks(soundPackManager))
                                .executes { handleSoundpackRebuild(it) },
                        ),
                    ),
            )
            .then(
                Commands.literal("audio")
                    .then(
                        Commands.literal("list")
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .executes { handleAudioList(it) },
                    )
                    .then(
                        Commands.literal("play")
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .then(
                                Commands.argument("filename", StringArgumentType.string())
                                    .suggests(ScoreSuggestions.audioFiles(audioFolder))
                                    .executes { handleAudioPlay(it, hasTargetArg = false) },
                            )
                            .then(
                                Commands.argument("target", ArgumentTypes.players())
                                    .then(
                                        Commands.argument("filename", StringArgumentType.greedyString())
                                            .suggests(ScoreSuggestions.audioFiles(audioFolder))
                                            .executes { handleAudioPlay(it, hasTargetArg = true) },
                                    ),
                            ),
                    )
                    .then(
                        Commands.literal("stop")
                            .requires { it.sender.hasPermission(Permissions.ADMIN) }
                            .executes { handleAudioStop(it, hasTargetArg = false) }
                            .then(
                                Commands.argument("target", ArgumentTypes.players())
                                    .executes { handleAudioStop(it, hasTargetArg = true) },
                            ),
                    )
                    .then(
                        Commands.literal("status")
                            .requires { it.sender.hasPermission(Permissions.STATUS) }
                            .executes { handleAudioStatus(it, hasArg = false) }
                            .then(
                                Commands.argument("target", ArgumentTypes.players())
                                    .requires { it.sender.hasPermission(Permissions.ADMIN) }
                                    .executes { handleAudioStatus(it, hasArg = true) },
                            ),
                    ),
            )
            .build()

    private fun handleList(ctx: CommandContext<CommandSourceStack>): Int {
        val files = listSongFiles()
        val sender = ctx.source.sender
        if (files.isEmpty()) {
            sender.sendMessage("No song files found in the music/ folder.")
        } else {
            sender.sendMessage("Song files (${files.size}): ${files.joinToString(", ")}")
        }
        return 1
    }

    private fun handleVerify(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("Only an in-game player can verify a web UI login.")
            return 1
        }
        val auth = webAuthManager
        if (auth == null) {
            sender.sendMessage("The web UI is disabled on this server.")
            return 1
        }
        val code = StringArgumentType.getString(ctx, "code")
        if (auth.verifyCode(player.uniqueId, player.name, code)) {
            sender.sendMessage("Web UI login confirmed. You can return to the browser now.")
        } else {
            sender.sendMessage("That code is invalid or expired. Request a new one from the web UI.")
        }
        return 1
    }

    private fun handlePlay(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val filename = StringArgumentType.getString(ctx, "filename")
        val file = File(scoresFolder, filename)
        if (!file.isFile) {
            sender.sendMessage("Song file not found: $filename")
            return 1
        }
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        sender.sendMessage("Loading '$filename'...")
        consumeOnMainThread(songCache.get(file)) { song, throwable ->
            if (throwable != null || song == null) {
                sender.sendMessage("Failed to parse '$filename': ${throwable?.message}")
            } else {
                playbackManager.startSession(targets, song, SessionMode.SINGLE_SONG)
                sender.sendMessage("Now playing '$filename' to ${targets.size} target(s).")
            }
        }
        return 1
    }

    private fun handleStop(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        playbackManager.stop(targets)
        sender.sendMessage("Stopped playback for ${targets.size} target(s).")
        return 1
    }

    private fun handleStatus(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val target = if (hasArg) {
            resolvePlayers(ctx, "target").firstOrNull()?.let { Bukkit.getPlayer(it) }
        } else {
            sender as? Player
        }
        if (target == null) {
            sender.sendMessage(if (hasArg) "Player not found or offline." else "Console has no playback status; specify a player.")
            return 1
        }
        val session = playbackManager.statusFor(target.uniqueId)
        if (session == null) {
            sender.sendMessage("${target.name} is not listening to anything right now.")
            return 1
        }
        val elapsedSec = session.cursorTick / 20
        val totalSec = session.song.totalTicks / 20
        val percent = if (session.song.totalTicks > 0) {
            (session.cursorTick * 100 / session.song.totalTicks).coerceIn(0, 100)
        } else {
            100
        }
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
        return 1
    }

    private fun handlePause(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        val affected = playbackManager.pause(targets)
        sender.sendMessage(pauseResumeMessage("Paused", affected, targets))
        return 1
    }

    private fun handleResume(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        val affected = playbackManager.resume(targets)
        sender.sendMessage(pauseResumeMessage("Resumed", affected, targets))
        return 1
    }

    private fun pauseResumeMessage(verb: String, affected: Set<PlaybackSession>, targets: Set<UUID>): String {
        if (affected.isEmpty()) return "Nothing is playing for that target."
        val totalListeners = affected.sumOf { it.targets.size }
        val extra = (totalListeners - targets.size).coerceAtLeast(0)
        return "$verb playback for ${targets.size} target(s)." +
            if (extra > 0) " (affects $extra other listener(s) sharing the same session)" else ""
    }

    private fun handleSeek(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        val positionArg = StringArgumentType.getString(ctx, "position")
        val seconds = positionArg.toIntOrNull()
        if (seconds == null) {
            sender.sendMessage("Invalid position '$positionArg' - use an absolute number of seconds (e.g. 90) or a relative offset (e.g. +15, -30).")
            return 1
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
        return 1
    }

    private fun handleShowMode(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("Only an in-game player has a playback mode.")
            return 1
        }
        sender.sendMessage("Your playback mode: ${modeLabel(playModeManager.modeOf(player.uniqueId))}. Change with /openmcmelody mode <tick|instant>.")
        return 1
    }

    private fun handleSetMode(ctx: CommandContext<CommandSourceStack>, mode: PlayMode): Int {
        val sender = ctx.source.sender
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("Only an in-game player can set a playback mode.")
            return 1
        }
        playModeManager.setMode(player.uniqueId, mode)
        sender.sendMessage("Playback mode set to ${modeLabel(mode)}.")
        return 1
    }

    private fun modeLabel(mode: PlayMode): String = when (mode) {
        PlayMode.TICK -> "tick (default - locked to the server's 50ms tick)"
        PlayMode.INSTANT -> "instant (fires each note at its real timing, not tick-locked)"
    }

    private fun handlePlaylistList(ctx: CommandContext<CommandSourceStack>): Int {
        val names = playlistManager.list()
        ctx.source.sender.sendMessage(if (names.isEmpty()) "No playlists yet." else "Playlists: ${names.joinToString(", ")}")
        return 1
    }

    private fun handlePlaylistShow(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        val playlist = playlistManager.get(name)
        if (playlist == null) {
            sender.sendMessage("Playlist '$name' not found.")
            return 1
        }
        val message = if (playlist.songs.isEmpty()) {
            "Playlist '$name' is empty."
        } else {
            val entries = playlist.songs.mapIndexed { i, song -> "${i + 1}. $song" }
            "Playlist '$name': ${entries.joinToString(", ")}"
        }
        sender.sendMessage(message)
        return 1
    }

    private fun handlePlaylistCreate(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        sender.sendMessage(if (playlistManager.create(name)) "Created playlist '$name'." else "Playlist '$name' already exists.")
        return 1
    }

    private fun handlePlaylistDelete(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val sender = ctx.source.sender
        sender.sendMessage(if (playlistManager.delete(name)) "Deleted playlist '$name'." else "Playlist '$name' not found.")
        return 1
    }

    private fun handlePlaylistAdd(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val filename = StringArgumentType.getString(ctx, "filename")
        val sender = ctx.source.sender
        if (!File(scoresFolder, filename).isFile) {
            sender.sendMessage("Song file not found: $filename")
            return 1
        }
        sender.sendMessage(if (playlistManager.addSong(name, filename)) "Added '$filename' to playlist '$name'." else "Playlist '$name' not found.")
        return 1
    }

    private fun handlePlaylistRemove(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val entry = StringArgumentType.getString(ctx, "entry")
        val sender = ctx.source.sender
        sender.sendMessage(
            if (playlistManager.removeSong(name, entry)) "Removed '$entry' from playlist '$name'." else "Could not find '$entry' in playlist '$name'.",
        )
        return 1
    }

    private fun handlePlaylistPlay(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        val playlist = playlistManager.get(name)
        if (playlist == null || playlist.songs.isEmpty()) {
            sender.sendMessage(if (playlist == null) "Playlist '$name' not found." else "Playlist '$name' is empty.")
            return 1
        }
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        val firstSongName = playlist.songs[0]
        val firstFile = File(scoresFolder, firstSongName)
        if (!firstFile.isFile) {
            sender.sendMessage("First song '$firstSongName' is missing from music/.")
            return 1
        }
        sender.sendMessage("Loading playlist '$name'...")
        consumeOnMainThread(songCache.get(firstFile)) { song, throwable ->
            if (throwable != null || song == null) {
                sender.sendMessage("Failed to parse '$firstSongName': ${throwable?.message}")
            } else {
                playbackManager.startSession(targets, song, SessionMode.PLAYLIST, name)
                sender.sendMessage("Now playing playlist '$name' to ${targets.size} target(s).")
            }
        }
        return 1
    }

    private fun handleSoundpackList(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val names = soundPackManager.listAvailable()
        if (names.isEmpty()) {
            sender.sendMessage("No soundpacks found under soundpacks/ (each needs its own folder with a pack.yml).")
            return 1
        }
        val active = soundPackManager.activeName
        sender.sendMessage("Soundpacks: " + names.joinToString(", ") { if (it == active) "$it (active)" else it })
        return 1
    }

    private fun handleSoundpackBuild(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        sender.sendMessage("Building soundpack '$name'...")
        consumeOnMainThread(buildSoundpackAsync(name)) { result, throwable ->
            if (throwable != null) {
                sender.sendMessage("Failed to build '$name': ${throwable.message}")
            } else {
                sender.sendMessage("Built '$name' (${result!!.definition.slotFiles.size} sound(s)) -> soundpacks/$name.zip")
            }
        }
        return 1
    }

    private fun handleSoundpackActivate(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "name")
        if (publicUrl.isBlank()) {
            sender.sendMessage("Set 'web.public-url' in config.yml first (the URL players' clients can reach this server at) before activating a soundpack.")
            return 1
        }
        sender.sendMessage("Building and activating soundpack '$name'...")
        consumeOnMainThread(buildSoundpackAsync(name)) { _, throwable ->
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
        return 1
    }

    private fun handleSoundpackDeactivate(ctx: CommandContext<CommandSourceStack>): Int {
        soundPackManager.deactivate()
        ctx.source.sender.sendMessage("Soundpack deactivated - new notes will use vanilla note block sounds.")
        return 1
    }

    private fun buildSoundpackAsync(name: String): CompletableFuture<SoundPackManager.BuildResult> =
        CompletableFuture.supplyAsync({ soundPackManager.build(name) }, BukkitExecutors.async(plugin))

    private fun handleSoundpackFromSf2(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val sf2Name = StringArgumentType.getString(ctx, "sf2file")
        val sf2File = File(soundfontsFolder, sf2Name)
        if (!sf2File.isFile) {
            sender.sendMessage("Soundfont file not found in soundfonts/: $sf2Name")
            return 1
        }
        val packName = sf2File.nameWithoutExtension
        sender.sendMessage("Extracting instruments from '$sf2Name' into soundpack '$packName'... this can take a minute.")
        runSoundFontConversion(sender, sf2File, packName)
        return 1
    }

    private fun handleSoundpackRebuild(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val packName = StringArgumentType.getString(ctx, "name")
        val packFolder = File(soundpacksFolder, packName)
        if (!packFolder.isDirectory) {
            sender.sendMessage("No soundpack folder named '$packName' under soundpacks/")
            return 1
        }
        val sf2Name = SoundPackLoader.sourceSoundfont(packFolder)
        if (sf2Name == null) {
            sender.sendMessage(
                "Soundpack '$packName' has no recorded source soundfont - it wasn't auto-generated by /openmcmelody soundpack fromsf2, so it can't be rebuilt automatically.",
            )
            return 1
        }
        val sf2File = File(soundfontsFolder, sf2Name)
        if (!sf2File.isFile) {
            sender.sendMessage("Source soundfont '$sf2Name' for '$packName' is no longer in soundfonts/, so it can't be rebuilt.")
            return 1
        }
        sender.sendMessage("Re-extracting instruments for soundpack '$packName' from '$sf2Name'... this can take a minute.")
        runSoundFontConversion(sender, sf2File, packName)
        return 1
    }

    private fun runSoundFontConversion(sender: CommandSender, sf2File: File, packName: String) {
        val mainExecutor = BukkitExecutors.main(plugin)
        val future = CompletableFuture.supplyAsync(
            {
                soundFontConverter.convert(sf2File, File(soundpacksFolder, packName), packName) { percent, message ->
                    mainExecutor.execute { sender.sendActionBar(Component.text("$percent%: $message")) }
                }
            },
            BukkitExecutors.async(plugin),
        )
        consumeOnMainThread(future) { result, throwable ->
            if (throwable != null) {
                sender.sendMessage("Failed to convert '${sf2File.name}': ${throwable.message}")
            } else if (result != null) {
                sender.sendMessage(
                    "Built soundpack '$packName' from '${sf2File.name}': ${result.mainSlotCount} instrument(s)" +
                        (if (result.releaseSlotCount > 0) " (${result.releaseSlotCount} with a release tail)" else "") +
                        (if (result.failedConversions > 0) ", ${result.failedConversions} failed to encode" else "") +
                        ". Run /openmcmelody soundpack build $packName to verify, then activate it.",
                )
            }
        }
    }

    private fun handleAudioList(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val files = audioLibrary.listSources()
        if (files.isEmpty()) {
            sender.sendMessage("No audio files found in the audio/ folder.")
        } else {
            sender.sendMessage("Audio files (${files.size}): ${files.joinToString(", ")}")
        }
        return 1
    }

    private fun handleAudioPlay(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val filename = StringArgumentType.getString(ctx, "filename")
        val file = File(audioFolder, filename)
        if (!file.isFile || !AudioFiles.isPlayable(filename)) {
            sender.sendMessage("Audio file not found: $filename")
            return 1
        }
        if (publicUrl.isBlank()) {
            sender.sendMessage("Set 'web.public-url' in config.yml first (the URL players' clients can reach this server at) before playing real audio.")
            return 1
        }
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        sender.sendMessage("Converting/loading '$filename'...")
        val future = CompletableFuture.supplyAsync({ audioLibrary.ensureConverted(file) }, BukkitExecutors.async(plugin))
        consumeOnMainThread(future) { track, throwable ->
            if (throwable != null || track == null) {
                sender.sendMessage("Failed to prepare '$filename': ${throwable?.message}")
            } else {
                audioPlaybackManager.play(targets, track, publicUrl)
                sender.sendMessage("Now playing '$filename' to ${targets.size} target(s).")
            }
        }
        return 1
    }

    private fun handleAudioStop(ctx: CommandContext<CommandSourceStack>, hasTargetArg: Boolean): Int {
        val sender = ctx.source.sender
        val targets = resolveTargetsOrSelf(ctx, "target", hasTargetArg, sender)
        if (targets == null) {
            sender.sendMessage("You must specify a target (console cannot target itself).")
            return 1
        }
        audioPlaybackManager.stop(targets)
        sender.sendMessage("Stopped audio playback for ${targets.size} target(s).")
        return 1
    }

    private fun handleAudioStatus(ctx: CommandContext<CommandSourceStack>, hasArg: Boolean): Int {
        val sender = ctx.source.sender
        val target = if (hasArg) {
            resolvePlayers(ctx, "target").firstOrNull()?.let { Bukkit.getPlayer(it) }
        } else {
            sender as? Player
        }
        if (target == null) {
            sender.sendMessage(if (hasArg) "Player not found or offline." else "Console has no playback status; specify a player.")
            return 1
        }
        val session = audioPlaybackManager.statusFor(target.uniqueId)
        if (session == null) {
            sender.sendMessage("${target.name} is not listening to any audio track right now.")
            return 1
        }
        sender.sendMessage(
            "${target.name}: ${session.track.sourceFile.name} - ${formatTime(session.elapsedSeconds)}/${formatTime(session.track.durationSeconds)}",
        )
        return 1
    }

    private fun listSongFiles(): List<String> =
        scoresFolder.listFiles { f -> f.isFile && SongFiles.isPlayable(f.name) }?.map { it.name }?.sorted() ?: emptyList()

    private fun resolvePlayers(ctx: CommandContext<CommandSourceStack>, name: String): Set<UUID> {
        val resolver = ctx.getArgument(name, PlayerSelectorArgumentResolver::class.java)
        return resolver.resolve(ctx.source).map { it.uniqueId }.toSet()
    }

    /** Explicit `target` selector when [hasArg], else the sender itself if it's a player - null (console with no target) means "caller must supply one". */
    private fun resolveTargetsOrSelf(ctx: CommandContext<CommandSourceStack>, argName: String, hasArg: Boolean, sender: CommandSender): Set<UUID>? {
        val targets = if (hasArg) {
            resolvePlayers(ctx, argName)
        } else {
            (sender as? Player)?.let { setOf(it.uniqueId) }
        }
        return targets?.takeIf { it.isNotEmpty() }
    }

    private fun formatTime(totalSeconds: Int): String = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

    /** Runs [action] on the main thread with [future]'s result once it completes - synchronously with zero hop if it's already done. */
    private fun <T> consumeOnMainThread(future: CompletableFuture<T>, action: (T?, Throwable?) -> Unit) {
        if (future.isDone) {
            try {
                action(future.join(), null)
            } catch (e: Exception) {
                action(null, e)
            }
            return
        }
        future.whenCompleteAsync({ value, throwable -> action(value, throwable) }, mainThreadExecutor)
    }
}
