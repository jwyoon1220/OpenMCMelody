package io.github.jwyoon1220.openMCMelody.web

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val PENDING_TTL: Duration = Duration.ofMinutes(5)
val SESSION_TTL_SECONDS: Long = Duration.ofHours(12).seconds
private val SESSION_TTL: Duration = Duration.ofSeconds(SESSION_TTL_SECONDS)

/**
 * In-game code login: a browser claims a Minecraft username, the plugin shows a one-time code,
 * and only the real player with that username (proven by running `/midi verify <code>` in-game)
 * can complete the login. This never trusts the browser's claimed identity on its own.
 *
 * Thread-safe: called from HTTP worker threads (start/poll) and the Bukkit main thread
 * (verification, via the in-game command) concurrently.
 */
class WebAuthManager {

    class PendingLogin(val loginId: String, val username: String, val code: String, val createdAt: Instant) {
        @Volatile var verifiedPlayerId: UUID? = null
        @Volatile var verifiedPlayerName: String? = null
        fun isExpired(now: Instant) = createdAt.plus(PENDING_TTL).isBefore(now)
    }

    class Session(val playerId: UUID, val playerName: String, val createdAt: Instant) {
        fun isExpired(now: Instant) = createdAt.plus(SESSION_TTL).isBefore(now)
    }

    private val pendingLogins = ConcurrentHashMap<String, PendingLogin>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun startLogin(username: String): PendingLogin {
        purgeExpired()
        val loginId = randomToken()
        val code = String.format("%06d", random.nextInt(1_000_000))
        val pending = PendingLogin(loginId, username, code, Instant.now())
        pendingLogins[loginId] = pending
        return pending
    }

    fun pendingLogin(loginId: String?): PendingLogin? {
        if (loginId == null) return null
        val pending = pendingLogins[loginId] ?: return null
        if (pending.isExpired(Instant.now())) {
            pendingLogins.remove(loginId)
            return null
        }
        return pending
    }

    /** Called from the `/midi verify <code>` command. Only the player actually named [playerName] can ever call this as themselves. */
    fun verifyCode(playerId: UUID, playerName: String, code: String): Boolean {
        purgeExpired()
        val now = Instant.now()
        for (pending in pendingLogins.values) {
            if (pending.isExpired(now)) continue
            if (pending.verifiedPlayerId != null) continue
            if (pending.code != code) continue
            if (!pending.username.equals(playerName, ignoreCase = true)) continue
            // Name before id: readers (HTTP thread) check id first, so this ordering plus both
            // fields being @Volatile guarantees a reader that sees a non-null id also sees the name.
            pending.verifiedPlayerName = playerName
            pending.verifiedPlayerId = playerId
            return true
        }
        return false
    }

    fun createSession(playerId: UUID, playerName: String): String {
        purgeExpired()
        val token = randomToken()
        sessions[token] = Session(playerId, playerName, Instant.now())
        return token
    }

    fun sessionFor(token: String?): Session? {
        if (token == null) return null
        val session = sessions[token] ?: return null
        if (session.isExpired(Instant.now())) {
            sessions.remove(token)
            return null
        }
        return session
    }

    fun invalidateSession(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun removePendingLogin(loginId: String) {
        pendingLogins.remove(loginId)
    }

    private fun purgeExpired() {
        val now = Instant.now()
        pendingLogins.entries.removeIf { it.value.isExpired(now) }
        sessions.entries.removeIf { it.value.isExpired(now) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
