package io.github.jwyoon1220.openMCMelody.midi

import org.bukkit.plugin.Plugin
import java.util.concurrent.Executor

/** Small [Executor] adapters over Bukkit's scheduler, for use with [java.util.concurrent.CompletableFuture]. */
object BukkitExecutors {
    fun main(plugin: Plugin): Executor = Executor { task -> plugin.server.scheduler.runTask(plugin, task) }
    fun async(plugin: Plugin): Executor = Executor { task -> plugin.server.scheduler.runTaskAsynchronously(plugin, task) }
}
