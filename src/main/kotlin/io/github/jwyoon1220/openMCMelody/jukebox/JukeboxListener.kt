package io.github.jwyoon1220.openMCMelody.jukebox

import io.github.jwyoon1220.openMCMelody.Permissions
import io.github.jwyoon1220.openMCMelody.midi.SongFiles
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.io.File

private const val MENU_ROWS = 6
private const val MENU_SIZE = MENU_ROWS * 9
private const val STOP_SLOT = 0

// How far away players see the vanilla-style "now playing" action bar when a song starts - roughly
// matching a real jukebox's own broadcast range, not tied to actual sound audibility.
private const val ACTION_BAR_RADIUS = 64.0

/** Marks a GUI [Inventory] as this plugin's jukebox song picker for [block], so [JukeboxListener.onClick] can identify it without title-string matching. */
private class JukeboxMenuHolder(val block: Block) : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

/**
 * Wires the "hold a diamond, right-click a jukebox" conversion and the song-picker GUI it opens on
 * subsequent right-clicks - see [SpecialJukeboxManager]/[JukeboxPlaybackManager]. Sound itself is
 * broadcast from the block's location via [JukeboxPlaybackManager], audible to every nearby player
 * (not just the one who picked the song), matching how a real jukebox works.
 */
class JukeboxListener(
    plugin: Plugin,
    private val jukeboxManager: SpecialJukeboxManager,
    private val jukeboxPlaybackManager: JukeboxPlaybackManager,
    private val midiFolder: File,
) : Listener {

    private val songKey = NamespacedKey(plugin, "jukebox-song")

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return // avoid a second fire from the off-hand variant of the same physical click
        val block = event.clickedBlock ?: return
        if (block.type != Material.JUKEBOX) return
        val player = event.player
        if (!player.hasPermission(Permissions.JUKEBOX)) return

        if (jukeboxManager.isSpecial(block)) {
            event.isCancelled = true
            openMenu(player, block)
            return
        }

        val heldItem = event.item ?: return
        if (heldItem.type != Material.DIAMOND) return
        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) heldItem.amount -= 1
        jukeboxManager.markSpecial(block)
        player.sendMessage(Component.text("이 주크박스가 MIDI 재생용으로 바뀌었습니다 - 다시 우클릭해서 곡을 고르세요.", NamedTextColor.GREEN))
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.JUKEBOX) return
        if (jukeboxManager.unmarkSpecial(block)) jukeboxPlaybackManager.stop(block)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? JukeboxMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val block = holder.block
        if (block.type != Material.JUKEBOX || !jukeboxManager.isSpecial(block)) {
            player.closeInventory()
            return
        }

        if (event.slot == STOP_SLOT) {
            val wasPlaying = jukeboxPlaybackManager.stop(block)
            player.sendMessage(
                if (wasPlaying) Component.text("주크박스를 정지했습니다.", NamedTextColor.YELLOW)
                else Component.text("재생 중인 곡이 없습니다.", NamedTextColor.GRAY),
            )
            player.closeInventory()
            return
        }

        val clicked = event.currentItem ?: return
        val filename = clicked.itemMeta?.persistentDataContainer?.get(songKey, PersistentDataType.STRING) ?: return
        val file = File(midiFolder, filename)
        if (!file.isFile) {
            player.sendMessage(Component.text("'$filename' 파일을 찾을 수 없습니다.", NamedTextColor.RED))
            return
        }
        jukeboxPlaybackManager.play(block, file)
        player.sendMessage(Component.text("주크박스에서 '$filename' 재생을 시작합니다.", NamedTextColor.GREEN))
        broadcastNowPlaying(block, filename)
        player.closeInventory()
    }

    /** Shows a brief, vanilla-jukebox-style "now playing" action bar to every player near [block] - see [ACTION_BAR_RADIUS]. */
    private fun broadcastNowPlaying(block: Block, filename: String) {
        val center = block.location
        val radiusSquared = ACTION_BAR_RADIUS * ACTION_BAR_RADIUS
        val message = Component.text("♪ $filename", NamedTextColor.WHITE)
        for (nearby in block.world.players) {
            if (nearby.location.distanceSquared(center) <= radiusSquared) nearby.sendActionBar(message)
        }
    }

    private fun openMenu(player: Player, block: Block) {
        val holder = JukeboxMenuHolder(block)
        val title = jukeboxPlaybackManager.currentSongName(block)?.let { Component.text(it, NamedTextColor.WHITE) }
            ?: Component.text("주크박스 - MIDI 선택")
        val inventory = Bukkit.createInventory(holder, MENU_SIZE, title)
        holder.backing = inventory

        val stopItem = ItemStack(Material.BARRIER)
        stopItem.itemMeta = stopItem.itemMeta?.also { it.displayName(Component.text("정지", NamedTextColor.RED)) }
        inventory.setItem(STOP_SLOT, stopItem)

        val songs = midiFolder.listFiles { f -> f.isFile && SongFiles.isPlayable(f.name) }
            ?.map { it.name }?.sorted() ?: emptyList()
        val capacity = MENU_SIZE - 1
        for ((i, name) in songs.take(capacity).withIndex()) {
            val item = ItemStack(Material.MUSIC_DISC_13)
            item.itemMeta = item.itemMeta?.also { meta ->
                meta.displayName(Component.text(name, NamedTextColor.YELLOW))
                meta.persistentDataContainer.set(songKey, PersistentDataType.STRING, name)
            }
            inventory.setItem(i + 1, item)
        }

        player.openInventory(inventory)
    }
}
