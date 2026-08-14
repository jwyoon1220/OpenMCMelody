package io.github.jwyoon1220.openMCMelody.midi

import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture

private const val MAX_CACHED_SONGS = 20

/**
 * Caches parsed songs keyed by filename + last-modified time, so repeat plays and every
 * playlist loop skip re-parsing. Backed by [CompletableFuture]s (not plain values) so
 * concurrent requests for the same not-yet-cached file share a single in-flight parse
 * instead of double-parsing, and callers always get something to chain a main-thread
 * continuation off of regardless of whether it was a cache hit.
 */
class SongCache(plugin: Plugin) {

    private val asyncExecutor = BukkitExecutors.async(plugin)

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CompletableFuture<ParsedSong>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompletableFuture<ParsedSong>>): Boolean =
                size > MAX_CACHED_SONGS
        }
    )

    /** Returns a future for the parsed song at [file], parsing off-thread only on a cache miss. */
    fun get(file: File): CompletableFuture<ParsedSong> {
        val key = "${file.name}:${file.lastModified()}"
        val future = cache.computeIfAbsent(key) {
            CompletableFuture.supplyAsync({
                if (SongFiles.isMusicXml(file.name)) MusicXmlParser.parse(file) else MidiParser.parse(file)
            }, asyncExecutor)
        }
        // A failed parse (bad file, transient IO error) shouldn't be pinned forever - let a later retry re-parse.
        future.whenComplete { _, throwable -> if (throwable != null) cache.remove(key, future) }
        return future
    }

    fun clear() = cache.clear()
}
