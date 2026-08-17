package io.github.jwyoon1220.openMCMelody

import io.github.jwyoon1220.openMCMelody.command.ScoreCommand
import io.github.jwyoon1220.openMCMelody.jukebox.JukeboxListener
import io.github.jwyoon1220.openMCMelody.jukebox.JukeboxPlaybackManager
import io.github.jwyoon1220.openMCMelody.jukebox.SpecialJukeboxManager
import io.github.jwyoon1220.openMCMelody.listener.PlayerConnectionListener
import io.github.jwyoon1220.openMCMelody.midi.SongCache
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

        val scoresFolder = File(dataFolder, "scores")
        migrateLegacyMidiFolder(scoresFolder)
        scoresFolder.mkdirs()
        val soundpacksFolder = File(dataFolder, "soundpacks")
        soundpacksFolder.mkdirs()
        val soundfontsFolder = File(dataFolder, "soundfonts")
        soundfontsFolder.mkdirs()

        val playlistManager = PlaylistManager(this, File(dataFolder, "playlists.yml"))
        val songCache = SongCache(this)
        val soundPackManager = SoundPackManager(soundpacksFolder, File(dataFolder, "active-soundpack.txt"))
        val restoredPack = soundPackManager.restoreActiveFromDisk()
        if (restoredPack != null) logger.info("Restored active soundpack '$restoredPack'.")
        val playModeManager = PlayModeManager(this, File(dataFolder, "playmodes.yml"))
        val playbackManager = PlaybackManager(this, playlistManager, songCache, scoresFolder, soundPackManager, playModeManager)
        playbackManager.enable()
        this.playbackManager = playbackManager

        server.pluginManager.registerEvents(PlayerConnectionListener(playbackManager, soundPackManager, publicUrl), this)

        val jukeboxManager = SpecialJukeboxManager(this, File(dataFolder, "jukeboxes.yml"))
        val jukeboxPlaybackManager = JukeboxPlaybackManager(this, songCache, soundPackManager)
        jukeboxPlaybackManager.enable()
        this.jukeboxPlaybackManager = jukeboxPlaybackManager
        server.pluginManager.registerEvents(JukeboxListener(this, jukeboxManager, jukeboxPlaybackManager, scoresFolder), this)

        val webAuthManager = if (config.getBoolean("web.enabled", true)) {
            startWebServer(scoresFolder, songCache, playbackManager, playlistManager, soundPackManager)
        } else {
            null
        }

        val ffmpegPath = config.getString("soundfont.ffmpeg-path", "ffmpeg")!!
        val soundFontConverter = SoundFontConverter(this, ffmpegPath)

        val scoreCommand = ScoreCommand(
            this, scoresFolder, songCache, playbackManager, playlistManager, webAuthManager,
            soundPackManager, publicUrl, soundfontsFolder, soundpacksFolder, soundFontConverter, playModeManager,
        )
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(scoreCommand.build("score"), "Play song files as note block music")
            event.registrar().register(scoreCommand.build("midi"), "Alias for /score")
        }
    }

    /**
     * Older installs stored songs under `midi/`; the plugin now supports MusicXML too, so the
     * folder is renamed to `scores/`. Move any leftover files across on first launch after the
     * upgrade so server owners don't have to do it by hand.
     */
    private fun migrateLegacyMidiFolder(scoresFolder: File) {
        val legacyFolder = File(dataFolder, "midi")
        if (!legacyFolder.isDirectory || scoresFolder.exists()) return
        if (legacyFolder.renameTo(scoresFolder)) {
            logger.info("Migrated ${legacyFolder.path} to ${scoresFolder.path}.")
        } else {
            logger.warning("Found a legacy midi/ folder but could not rename it to scores/ - please move its contents manually.")
        }
    }

    private fun startWebServer(
        scoresFolder: File,
        songCache: SongCache,
        playbackManager: PlaybackManager,
        playlistManager: PlaylistManager,
        soundPackManager: SoundPackManager,
    ): WebAuthManager? {
        val bind = config.getString("web.bind", "0.0.0.0")!!
        val port = config.getInt("web.port", 8080)
        val authManager = WebAuthManager()
        val server = WebServer(this, authManager, scoresFolder, songCache, playbackManager, playlistManager, soundPackManager)
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
