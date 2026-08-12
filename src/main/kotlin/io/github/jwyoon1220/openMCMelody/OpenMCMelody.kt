package io.github.jwyoon1220.openMCMelody

import io.github.jwyoon1220.openMCMelody.command.MidiCommand
import io.github.jwyoon1220.openMCMelody.listener.PlayerConnectionListener
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.playback.PlaybackManager
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

        val midiFolder = File(dataFolder, "midi")
        midiFolder.mkdirs()
        val soundpacksFolder = File(dataFolder, "soundpacks")
        soundpacksFolder.mkdirs()
        val soundfontsFolder = File(dataFolder, "soundfonts")
        soundfontsFolder.mkdirs()

        val playlistManager = PlaylistManager(this, File(dataFolder, "playlists.yml"))
        val songCache = SongCache(this)
        val soundPackManager = SoundPackManager(soundpacksFolder, File(dataFolder, "active-soundpack.txt"))
        val restoredPack = soundPackManager.restoreActiveFromDisk()
        if (restoredPack != null) logger.info("Restored active soundpack '$restoredPack'.")
        val playbackManager = PlaybackManager(this, playlistManager, songCache, midiFolder, soundPackManager)
        playbackManager.enable()
        this.playbackManager = playbackManager

        server.pluginManager.registerEvents(PlayerConnectionListener(playbackManager, soundPackManager, publicUrl), this)

        val webAuthManager = if (config.getBoolean("web.enabled", true)) {
            startWebServer(midiFolder, songCache, playbackManager, playlistManager, soundPackManager)
        } else {
            null
        }

        val ffmpegPath = config.getString("soundfont.ffmpeg-path", "ffmpeg")!!
        val soundFontConverter = SoundFontConverter(this, ffmpegPath)

        val midiCommand = MidiCommand(
            this, midiFolder, songCache, playbackManager, playlistManager, webAuthManager,
            soundPackManager, publicUrl, soundfontsFolder, soundpacksFolder, soundFontConverter,
        )
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(midiCommand.build(), "Play MIDI files as note block music")
        }
    }

    private fun startWebServer(
        midiFolder: File,
        songCache: SongCache,
        playbackManager: PlaybackManager,
        playlistManager: PlaylistManager,
        soundPackManager: SoundPackManager,
    ): WebAuthManager? {
        val bind = config.getString("web.bind", "0.0.0.0")!!
        val port = config.getInt("web.port", 8080)
        val authManager = WebAuthManager()
        val server = WebServer(this, authManager, midiFolder, songCache, playbackManager, playlistManager, soundPackManager)
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
    }
}
