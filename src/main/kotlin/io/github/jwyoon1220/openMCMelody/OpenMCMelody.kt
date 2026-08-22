package io.github.jwyoon1220.openMCMelody

import io.github.jwyoon1220.openMCMelody.audio.AudioLibrary
import io.github.jwyoon1220.openMCMelody.audio.AudioPackManager
import io.github.jwyoon1220.openMCMelody.audio.AudioPlaybackManager
import io.github.jwyoon1220.openMCMelody.command.ScoreCommand
import io.github.jwyoon1220.openMCMelody.jukebox.JukeboxListener
import io.github.jwyoon1220.openMCMelody.jukebox.JukeboxPlaybackManager
import io.github.jwyoon1220.openMCMelody.jukebox.SpecialJukeboxManager
import io.github.jwyoon1220.openMCMelody.listener.PlayerConnectionListener
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.playback.BukkitPacketSender
import io.github.jwyoon1220.openMCMelody.playback.PacketEventsPacketSender
import io.github.jwyoon1220.openMCMelody.playback.PacketSender
import io.github.jwyoon1220.openMCMelody.playback.PingTracker
import io.github.jwyoon1220.openMCMelody.playback.PlaybackManager
import io.github.jwyoon1220.openMCMelody.playback.PlayModeManager
import io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager
import io.github.jwyoon1220.openMCMelody.soundfont.SoundFontConverter
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import io.github.jwyoon1220.openMCMelody.web.WebAuthManager
import io.github.jwyoon1220.openMCMelody.web.WebServer
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class OpenMCMelody : JavaPlugin() {

    private var playbackManager: PlaybackManager? = null
    private var jukeboxPlaybackManager: JukeboxPlaybackManager? = null
    private var webServer: WebServer? = null

    override fun onEnable() {
        try {
            Class.forName("javax.sound.midi.MidiSystem")
        } catch (e: ClassNotFoundException) {
            logger.severe("javax.sound.midi (java.desktop module) is unavailable on this JVM - OpenMCMelody cannot parse MIDI files. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }

        saveDefaultConfig()
        val publicUrl = config.getString("web.public-url", "")!!.trimEnd('/')

        val scoresFolder = File(dataFolder, "music")
        migrateLegacyFolders(scoresFolder)
        scoresFolder.mkdirs()
        val soundpacksFolder = File(dataFolder, "soundpacks")
        soundpacksFolder.mkdirs()
        val soundfontsFolder = File(dataFolder, "soundfonts")
        soundfontsFolder.mkdirs()
        val audioFolder = File(dataFolder, "audio")
        audioFolder.mkdirs()

        val ffmpegPath = config.getString("soundfont.ffmpeg-path", "ffmpeg")!!
        val audioLibrary = AudioLibrary(audioFolder, ffmpegPath)
        val audioPackManager = AudioPackManager()
        val audioPlaybackManager = AudioPlaybackManager(audioPackManager)

        val playlistManager = PlaylistManager(this, File(dataFolder, "playlists.yml"))
        val songCache = SongCache(this)
        val soundPackManager = SoundPackManager(soundpacksFolder, File(dataFolder, "active-soundpack.txt"))
        val restoredPack = soundPackManager.restoreActiveFromDisk()
        if (restoredPack != null) logger.info("Restored active soundpack '$restoredPack'.")
        val playModeManager = PlayModeManager(this, File(dataFolder, "playmodes.yml"))
        val pingCompensationEnabled = config.getBoolean("ping-compensation.enabled", true)
        val pingTracker = PingTracker(
            jitterSafetyFactor = config.getDouble("ping-compensation.jitter-safety-factor", 1.0),
            maxLeadMillis = if (pingCompensationEnabled) config.getLong("ping-compensation.max-lead-millis", 250L) else 0L,
        )
        val packetSender = createPacketSender()
        val playbackManager = PlaybackManager(this, playlistManager, songCache, scoresFolder, soundPackManager, playModeManager, pingTracker, packetSender)
        playbackManager.enable()
        this.playbackManager = playbackManager

        server.pluginManager.registerEvents(PlayerConnectionListener(playbackManager, soundPackManager, publicUrl), this)

        val jukeboxManager = SpecialJukeboxManager(this, File(dataFolder, "jukeboxes.yml"))
        val jukeboxPlaybackManager = JukeboxPlaybackManager(this, songCache, soundPackManager)
        jukeboxPlaybackManager.enable()
        this.jukeboxPlaybackManager = jukeboxPlaybackManager
        server.pluginManager.registerEvents(JukeboxListener(this, jukeboxManager, jukeboxPlaybackManager, scoresFolder), this)

        val webAuthManager = if (config.getBoolean("web.enabled", true)) {
            startWebServer(scoresFolder, songCache, playbackManager, playlistManager, soundPackManager, audioPackManager)
        } else {
            null
        }

        val soundFontConverter = SoundFontConverter(this, ffmpegPath)

        val scoreCommand = ScoreCommand(
            this, scoresFolder, songCache, playbackManager, playlistManager, webAuthManager,
            soundPackManager, publicUrl, soundfontsFolder, soundpacksFolder, soundFontConverter, playModeManager,
            audioFolder, audioLibrary, audioPlaybackManager,
        )
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(scoreCommand.build("openmcmelody"), "Play song files as note block music")
            event.registrar().register(scoreCommand.build("ommc"), "Alias for /openmcmelody")
        }
    }

    /**
     * Older installs stored songs under `midi/`, then `scores/`; the folder is now `music/`. Move
     * any leftover files across on first launch after an upgrade so server owners don't have to do
     * it by hand - tries the most recent legacy name first.
     */
    private fun migrateLegacyFolders(musicFolder: File) {
        if (musicFolder.exists()) return
        for (legacyName in listOf("scores", "midi")) {
            val legacyFolder = File(dataFolder, legacyName)
            if (!legacyFolder.isDirectory) continue
            if (legacyFolder.renameTo(musicFolder)) {
                logger.info("Migrated ${legacyFolder.path} to ${musicFolder.path}.")
            } else {
                logger.warning("Found a legacy $legacyName/ folder but could not rename it to music/ - please move its contents manually.")
            }
            return
        }
    }

    /**
     * Picks [PacketEventsPacketSender] only when the `packetevents` plugin is actually present and
     * loads cleanly (declared as a `softdepend` in plugin.yml so it loads before us if present) -
     * otherwise falls back to [BukkitPacketSender], identical to how OpenMCMelody always sent
     * sound before packet-level dispatch existed. The try/catch guards against a PacketEvents
     * version whose API shape doesn't match what this class expects (e.g. a method renamed
     * upstream); a broken optional integration should never take the whole plugin down with it.
     */
    private fun createPacketSender(): PacketSender {
        if (server.pluginManager.getPlugin("packetevents") == null) return BukkitPacketSender
        return try {
            PacketEventsPacketSender().also { logger.info("PacketEvents detected - using direct packet dispatch for note playback.") }
        } catch (e: Throwable) {
            logger.warning("PacketEvents is installed but its API didn't initialize as expected (${e.message}) - falling back to Player.playSound().")
            BukkitPacketSender
        }
    }

    private fun startWebServer(
        scoresFolder: File,
        songCache: SongCache,
        playbackManager: PlaybackManager,
        playlistManager: PlaylistManager,
        soundPackManager: SoundPackManager,
        audioPackManager: AudioPackManager,
    ): WebAuthManager? {
        val bind = config.getString("web.bind", "0.0.0.0")!!
        val port = config.getInt("web.port", 8080)
        val authManager = WebAuthManager()
        val server = WebServer(this, authManager, scoresFolder, songCache, playbackManager, playlistManager, soundPackManager, audioPackManager)
        return try {
            server.start(bind, port)
            webServer = server
            logger.info("OpenMCMelody web UI listening on http://$bind:$port")
            authManager
        } catch (e: Exception) {
            logger.warning("Failed to start OpenMCMelody web UI on $bind:$port (${e.message}) - continuing without it.")
            null
        }
    }

    override fun onDisable() {
        webServer?.stop()
        webServer = null
        playbackManager?.disable()
        playbackManager = null
        jukeboxPlaybackManager?.disable()
        jukeboxPlaybackManager = null
    }
}
