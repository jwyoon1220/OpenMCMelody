package io.github.jwyoon1220.openMCMelody.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jwyoon1220.openMCMelody.Permissions
import io.github.jwyoon1220.openMCMelody.audio.AudioPackManager
import io.github.jwyoon1220.openMCMelody.midi.SongCache
import io.github.jwyoon1220.openMCMelody.midi.SongFiles
import io.github.jwyoon1220.openMCMelody.playback.PlaybackManager
import io.github.jwyoon1220.openMCMelody.playback.SessionMode
import io.github.jwyoon1220.openMCMelody.playlist.PlaylistManager
import io.github.jwyoon1220.openMCMelody.soundpack.SoundPackManager
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves the web control panel over plain HTTP using the JDK's built-in [HttpServer] - no extra
 * dependency. Every route runs on a small worker thread pool, never the Bukkit main thread;
 * anything that touches [PlaybackManager] or live [org.bukkit.entity.Player] state is bounced
 * through [MainThreadBridge]. [PlaylistManager] and [SongCache] are already thread-safe on their
 * own, so those are called directly.
 */
class WebServer(
    private val plugin: Plugin,
    private val authManager: WebAuthManager,
    private val scoresFolder: File,
    private val songCache: SongCache,
    private val playbackManager: PlaybackManager,
    private val playlistManager: PlaylistManager,
    private val soundPackManager: SoundPackManager,
    private val audioPackManager: AudioPackManager,
) {
    private var httpServer: HttpServer? = null
    private var pool: ExecutorService? = null

    private val indexHtml: String by lazy {
        javaClass.classLoader.getResourceAsStream("web/index.html")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: "<h1>OpenMCMelody</h1><p>web/index.html resource is missing from the plugin jar.</p>"
    }

    fun start(bind: String, port: Int) {
        val server = HttpServer.create(InetSocketAddress(bind, port), 0)
        val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "OpenMCMelody-Web").apply { isDaemon = true } }
        server.executor = workers

        server.createContext("/") { route(it, ::handleIndex) }
        server.createContext("/api/login/start") { route(it, ::handleLoginStart) }
        server.createContext("/api/login/status") { route(it, ::handleLoginStatus) }
        server.createContext("/api/logout") { route(it, ::handleLogout) }
        server.createContext("/api/me") { route(it, ::handleMe) }
        server.createContext("/api/songs") { route(it, ::handleSongs) }
        server.createContext("/api/players") { route(it, ::handlePlayers) }
        server.createContext("/api/playlists") { route(it, ::handlePlaylistsRoute) }
        server.createContext("/api/status") { route(it, ::handleStatus) }
        server.createContext("/api/play") { route(it, ::handlePlay) }
        server.createContext("/api/stop") { route(it, ::handleStop) }
        server.createContext("/api/pause") { route(it, ::handlePauseResume) }
        server.createContext("/api/resume") { route(it, ::handlePauseResume) }
        server.createContext("/api/seek") { route(it, ::handleSeek) }
        // No session auth here - this is fetched by the Minecraft client itself, not a browser.
        server.createContext("/resourcepack") { route(it, ::handleResourcePack) }
        server.createContext("/audiopack") { route(it, ::handleAudioPack) }

        server.start()
        httpServer = server
        pool = workers
    }

    fun stop() {
        httpServer?.stop(0)
        pool?.shutdownNow()
        httpServer = null
        pool = null
    }

    // ---- dispatch / error handling ----

    private fun route(exchange: HttpExchange, handler: (HttpExchange) -> Unit) {
        try {
            handler(exchange)
        } catch (e: MainThreadBridge.MainThreadUnavailableException) {
            respondJson(exchange, 503, mapOf("error" to "Server is busy or shutting down, try again"))
        } catch (e: Exception) {
            plugin.logger.warning("Web UI request error on ${exchange.requestURI}: ${e.message}")
            respondJson(exchange, 500, mapOf("error" to "Internal error"))
        } finally {
            exchange.close()
        }
    }

    // ---- auth routes ----

    private fun handleIndex(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        respondHtml(exchange, 200, indexHtml)
    }

    private fun handleLoginStart(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val username = readJsonBody(exchange).jsonString("username")?.trim()
        if (username.isNullOrEmpty() || !username.matches(Regex("^\\w{1,16}$"))) {
            return respondJson(exchange, 400, mapOf("error" to "Invalid Minecraft username"))
        }
        val pending = authManager.startLogin(username)
        setCookie(exchange, "omm_login", pending.loginId, 300)
        respondJson(exchange, 200, mapOf("code" to pending.code, "expiresInSeconds" to 300))
    }

    private fun handleLoginStatus(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val pending = authManager.pendingLogin(parseCookies(exchange)["omm_login"])
            ?: return respondJson(exchange, 200, mapOf("status" to "expired"))

        val verifiedId = pending.verifiedPlayerId ?: return respondJson(exchange, 200, mapOf("status" to "pending"))

        val token = authManager.createSession(verifiedId, pending.verifiedPlayerName!!)
        authManager.removePendingLogin(pending.loginId)
        clearCookie(exchange, "omm_login")
        setCookie(exchange, "omm_session", token, SESSION_TTL_SECONDS)
        respondJson(exchange, 200, mapOf("status" to "verified", "playerName" to pending.verifiedPlayerName))
    }

    private fun handleLogout(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        authManager.invalidateSession(parseCookies(exchange)["omm_session"])
        clearCookie(exchange, "omm_session")
        respondJson(exchange, 200, mapOf("ok" to true))
    }

    private fun handleMe(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val session = requireSession(exchange) ?: return
        val isAdmin = MainThreadBridge.run(plugin) { Bukkit.getPlayer(session.playerId)?.hasPermission(Permissions.ADMIN) ?: false }
        respondJson(exchange, 200, mapOf("playerName" to session.playerName, "uuid" to session.playerId.toString(), "isAdmin" to isAdmin))
    }

    // ---- browsing ----

    private fun handleSongs(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        requireSession(exchange) ?: return
        respondJson(exchange, 200, listSongFiles())
    }

    private fun handlePlayers(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        requireSession(exchange) ?: return
        val players = MainThreadBridge.run(plugin) {
            Bukkit.getOnlinePlayers().map { mapOf("uuid" to it.uniqueId.toString(), "name" to it.name) }
        }
        respondJson(exchange, 200, players)
    }

    // ---- resource pack download (public - the Minecraft client fetches this directly) ----

    private fun handleResourcePack(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val name = exchange.requestURI.path.removePrefix("/resourcepack/").removeSuffix(".zip")
        val result = soundPackManager.buildResult(name) ?: return respondJson(exchange, 404, mapOf("error" to "Unknown or unbuilt soundpack"))
        exchange.responseHeaders.add("Content-Type", "application/zip")
        exchange.sendResponseHeaders(200, result.pack.bytes.size.toLong())
        exchange.responseBody.use { it.write(result.pack.bytes) }
    }

    private fun handleAudioPack(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val built = audioPackManager.current ?: return respondJson(exchange, 404, mapOf("error" to "No audio pack has been built yet"))
        exchange.responseHeaders.add("Content-Type", "application/zip")
        exchange.sendResponseHeaders(200, built.bytes.size.toLong())
        exchange.responseBody.use { it.write(built.bytes) }
    }

    // ---- playlists (open to any logged-in session, mirrors in-game permission) ----

    private fun handlePlaylistsRoute(exchange: HttpExchange) {
        val session = requireSession(exchange) ?: return
        val segments = exchange.requestURI.path.removePrefix("/api/playlists").trim('/')
            .let { if (it.isEmpty()) emptyList() else it.split("/") }
        val method = exchange.requestMethod

        when {
            segments.isEmpty() && method == "GET" -> {
                val names = playlistManager.list()
                respondJson(exchange, 200, names.map { name -> mapOf("name" to name, "songCount" to (playlistManager.get(name)?.songs?.size ?: 0)) })
            }
            segments.isEmpty() && method == "POST" -> {
                val name = readJsonBody(exchange).jsonString("name")?.trim()
                if (name.isNullOrEmpty()) return respondJson(exchange, 400, mapOf("error" to "Missing 'name'"))
                val created = playlistManager.create(name)
                respondJson(exchange, if (created) 200 else 409, mapOf("ok" to created))
            }
            segments.size == 1 && method == "GET" -> {
                val playlist = playlistManager.get(segments[0])
                if (playlist == null) respondJson(exchange, 404, mapOf("error" to "Not found"))
                else respondJson(exchange, 200, mapOf("name" to playlist.name, "songs" to playlist.songs))
            }
            segments.size == 1 && method == "DELETE" -> {
                val ok = playlistManager.delete(segments[0])
                respondJson(exchange, if (ok) 200 else 404, mapOf("ok" to ok))
            }
            segments.size == 2 && segments[1] == "songs" && method == "POST" -> {
                val filename = readJsonBody(exchange).jsonString("filename")
                if (filename.isNullOrEmpty() || !File(scoresFolder, filename).isFile) {
                    return respondJson(exchange, 400, mapOf("error" to "Unknown song file"))
                }
                val ok = playlistManager.addSong(segments[0], filename)
                respondJson(exchange, if (ok) 200 else 404, mapOf("ok" to ok))
            }
            segments.size == 3 && segments[1] == "songs" && method == "DELETE" -> {
                val ok = playlistManager.removeSong(segments[0], URLDecoder.decode(segments[2], "UTF-8"))
                respondJson(exchange, if (ok) 200 else 404, mapOf("ok" to ok))
            }
            segments.size == 2 && segments[1] == "play" && method == "POST" -> {
                playPlaylist(exchange, segments[0], session)
            }
            else -> respondJson(exchange, 404, mapOf("error" to "Not found"))
        }
    }

    private fun playPlaylist(exchange: HttpExchange, name: String, session: WebAuthManager.Session) {
        val playlist = playlistManager.get(name)
        if (playlist == null || playlist.songs.isEmpty()) {
            return respondJson(exchange, 404, mapOf("error" to "Playlist not found or empty"))
        }
        val targets = resolveTargets(readJsonBody(exchange), session)
        if (targets.isEmpty()) return respondJson(exchange, 400, mapOf("error" to "No valid targets"))

        val firstFile = File(scoresFolder, playlist.songs[0])
        if (!firstFile.isFile) return respondJson(exchange, 400, mapOf("error" to "First song missing from music/"))

        val song = try {
            songCache.get(firstFile).join()
        } catch (e: Exception) {
            return respondJson(exchange, 500, mapOf("error" to "Failed to parse '${playlist.songs[0]}'"))
        }
        MainThreadBridge.run(plugin) { playbackManager.startSession(targets, song, SessionMode.PLAYLIST, name) }
        respondJson(exchange, 200, mapOf("ok" to true))
    }

    // ---- status (self open to all, others require admin) ----

    private fun handleStatus(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val session = requireSession(exchange) ?: return
        val targetParam = queryParams(exchange)["target"] ?: "self"
        val targetId = if (targetParam == "self") session.playerId else targetParam.toUuidOrNull()
            ?: return respondJson(exchange, 400, mapOf("error" to "Invalid target"))
        if (targetId != session.playerId && !requireAdmin(exchange, session)) return

        val (playerName, pbSession) = MainThreadBridge.run(plugin) {
            val name = Bukkit.getPlayer(targetId)?.name ?: Bukkit.getOfflinePlayer(targetId).name
            name to playbackManager.statusFor(targetId)
        }
        if (pbSession == null) return respondJson(exchange, 200, mapOf("playerName" to playerName, "playing" to false))

        respondJson(
            exchange, 200,
            mapOf(
                "playerName" to playerName,
                "playing" to true,
                "song" to pbSession.song.sourceFileName,
                "mode" to pbSession.mode.name,
                "playlistName" to pbSession.playlistName,
                "playlistIndex" to (pbSession.songIndex + 1),
                "playlistSize" to (if (pbSession.mode == SessionMode.PLAYLIST) playlistManager.get(pbSession.playlistName ?: "")?.songs?.size else null),
                "state" to pbSession.state.name,
                "elapsedTicks" to pbSession.cursorTick,
                "totalTicks" to pbSession.song.totalTicks,
            ),
        )
    }

    // ---- play / stop (always admin) ----

    private fun handlePlay(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val session = requireSession(exchange) ?: return
        if (!requireAdmin(exchange, session)) return

        val body = readJsonBody(exchange)
        val filename = body.jsonString("filename")
        if (filename.isNullOrEmpty()) return respondJson(exchange, 400, mapOf("error" to "Missing 'filename'"))
        val file = File(scoresFolder, filename)
        if (!file.isFile) return respondJson(exchange, 404, mapOf("error" to "Song file not found"))
        val targets = resolveTargets(body, session)
        if (targets.isEmpty()) return respondJson(exchange, 400, mapOf("error" to "No valid targets"))

        val song = try {
            songCache.get(file).join()
        } catch (e: Exception) {
            return respondJson(exchange, 500, mapOf("error" to "Failed to parse '$filename'"))
        }
        MainThreadBridge.run(plugin) { playbackManager.startSession(targets, song, SessionMode.SINGLE_SONG) }
        respondJson(exchange, 200, mapOf("ok" to true))
    }

    private fun handleStop(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val session = requireSession(exchange) ?: return
        if (!requireAdmin(exchange, session)) return
        val targets = resolveTargets(readJsonBody(exchange), session)
        if (targets.isEmpty()) return respondJson(exchange, 400, mapOf("error" to "No valid targets"))
        MainThreadBridge.run(plugin) { playbackManager.stop(targets) }
        respondJson(exchange, 200, mapOf("ok" to true))
    }

    // ---- pause / resume (self open to all, others require admin) ----

    private fun handlePauseResume(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val pause = exchange.requestURI.path.endsWith("/pause")
        val session = requireSession(exchange) ?: return
        val targets = resolveTargets(readJsonBody(exchange), session)
        if (targets.isEmpty()) return respondJson(exchange, 400, mapOf("error" to "No valid targets"))
        val onlySelf = targets.size == 1 && targets.single() == session.playerId
        if (!onlySelf && !requireAdmin(exchange, session)) return
        val affected = MainThreadBridge.run(plugin) { if (pause) playbackManager.pause(targets) else playbackManager.resume(targets) }
        respondJson(exchange, 200, mapOf("ok" to true, "affectedSessions" to affected.size))
    }

    // ---- seek (self open to all, others require admin - mirrors pause/resume) ----

    private fun handleSeek(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respondJson(exchange, 405, mapOf("error" to "Method not allowed"))
        val session = requireSession(exchange) ?: return
        val body = readJsonBody(exchange)
        val targets = resolveTargets(body, session)
        if (targets.isEmpty()) return respondJson(exchange, 400, mapOf("error" to "No valid targets"))
        val onlySelf = targets.size == 1 && targets.single() == session.playerId
        if (!onlySelf && !requireAdmin(exchange, session)) return
        val ticks = body.jsonNumber("ticks")?.toInt() ?: return respondJson(exchange, 400, mapOf("error" to "Missing 'ticks'"))
        val affected = MainThreadBridge.run(plugin) { playbackManager.seek(targets, ticks) }
        respondJson(exchange, 200, mapOf("ok" to true, "affectedSessions" to affected.size))
    }

    // ---- helpers ----

    private fun listSongFiles(): List<String> =
        scoresFolder.listFiles { f -> f.isFile && SongFiles.isPlayable(f.name) }
            ?.map { it.name }?.sorted() ?: emptyList()

    private fun resolveTargets(body: Map<*, *>, session: WebAuthManager.Session): Set<UUID> =
        body.jsonStringList("targets").mapNotNull { if (it == "self") session.playerId else it.toUuidOrNull() }.toSet()

    private fun String.toUuidOrNull(): UUID? = try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun requireSession(exchange: HttpExchange): WebAuthManager.Session? {
        val session = authManager.sessionFor(parseCookies(exchange)["omm_session"])
        if (session == null) respondJson(exchange, 401, mapOf("error" to "Not logged in"))
        return session
    }

    private fun requireAdmin(exchange: HttpExchange, session: WebAuthManager.Session): Boolean {
        val isAdmin = MainThreadBridge.run(plugin) { Bukkit.getPlayer(session.playerId)?.hasPermission(Permissions.ADMIN) ?: false }
        if (!isAdmin) respondJson(exchange, 403, mapOf("error" to "This action requires admin permission, and you must be online in-game"))
        return isAdmin
    }

    private fun readJsonBody(exchange: HttpExchange): Map<*, *> {
        val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return emptyMap<String, Any?>()
        return Json.parse(text) as? Map<*, *> ?: emptyMap<String, Any?>()
    }

    private fun queryParams(exchange: HttpExchange): Map<String, String> {
        val query = exchange.requestURI.rawQuery ?: return emptyMap()
        return query.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun parseCookies(exchange: HttpExchange): Map<String, String> {
        val header = exchange.requestHeaders.getFirst("Cookie") ?: return emptyMap()
        return header.split(";").mapNotNull {
            val part = it.trim()
            val idx = part.indexOf('=')
            if (idx < 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()
    }

    private fun setCookie(exchange: HttpExchange, name: String, value: String, maxAgeSeconds: Long) {
        // Plain HTTP (no TLS) by design here, so no `Secure` flag - see config.yml's caveat comment.
        exchange.responseHeaders.add("Set-Cookie", "$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=$maxAgeSeconds")
    }

    private fun clearCookie(exchange: HttpExchange, name: String) {
        exchange.responseHeaders.add("Set-Cookie", "$name=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.stringify(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondHtml(exchange: HttpExchange, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
