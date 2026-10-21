package dev.waypointtrail;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chest-style menus for Java players. */
public class JavaMenus implements Listener {

    private static class Holder implements InventoryHolder {
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private record Pending(boolean rename, Waypoint wp) {
    }

    private final WaypointTrail plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public JavaMenus(WaypointTrail plugin) {
        this.plugin = plugin;
    }

    // ---------- helpers ----------

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(legacy(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(legacy(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private Holder newMenu(int size, String title) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, size, Component.text(title));
        return h;
    }

    private void set(Holder h, int slot, ItemStack it, Runnable action) {
        h.inv.setItem(slot, it);
        if (action != null) h.actions.put(slot, action);
    }

    // ---------- main list ----------

    public void openMain(Player p, int page) {
        List<Waypoint> list = plugin.visibleTo(p);
        int perPage = 45;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        int pg = Math.max(0, Math.min(page, pages - 1));

        Holder h = newMenu(54, "Waypoints (" + (pg + 1) + "/" + pages + ")");

        int start = pg * perPage;
        for (int i = 0; i < perPage && start + i < list.size(); i++) {
            Waypoint w = list.get(start + i);
            boolean mine = w.owner.equals(p.getUniqueId());
            Material icon = mine ? (w.shared ? Material.ENDER_EYE : Material.COMPASS) : Material.ENDER_PEARL;
            set(h, i, item(icon, "§b" + w.name,
                    "§7Position: §f" + plugin.posText(w),
                    "§7World: §f" + plugin.dimName(w.world),
                    "§7Owner: §f" + (mine ? "you" : w.ownerName),
                    "§7Visible to: §f" + (w.shared ? "everyone" : "only you"),
                    "",
                    "§eClick to open"), () -> openDetail(p, w));
        }

        if (pg > 0) set(h, 45, item(Material.ARROW, "§ePrevious page"), () -> openMain(p, pg - 1));
        if (pg < pages - 1) set(h, 53, item(Material.ARROW, "§eNext page"), () -> openMain(p, pg + 1));
        set(h, 48, item(Material.EMERALD, "§aNew waypoint here", "§7Saves where you stand,", "§7you'll type the name in chat."),
                () -> promptAdd(p));
        if (plugin.isTracking(p)) {
            set(h, 49, item(Material.REDSTONE, "§cStop tracking"), () -> {
                plugin.stopTracking(p, true);
                openMain(p, pg);
            });
        }
        set(h, 50, item(Material.BARRIER, "§cClose"), p::closeInventory);

        p.openInventory(h.inv);
    }

    // ---------- one waypoint ----------

    public void openDetail(Player p, Waypoint w) {
        if (!plugin.exists(w)) {
            plugin.tell(p, "§eThat waypoint no longer exists.");
            openMain(p, 0);
            return;
        }
        boolean mine = w.owner.equals(p.getUniqueId());
        Holder h = newMenu(27, w.name);

        set(h, 4, item(Material.PAPER, "§b" + w.name,
                "§7Position: §f" + plugin.posText(w),
                "§7World: §f" + plugin.dimName(w.world),
                "§7Owner: §f" + (mine ? "you" : w.ownerName),
                "§7Visible to: §f" + (w.shared ? "everyone" : "only the owner")), null);

        set(h, 10, item(Material.COMPASS, "§aTrack this waypoint", "§7Shows a boss bar with", "§7distance and direction."), () -> {
            plugin.startTracking(p, w);
            p.closeInventory();
        });

        if (mine) {
            set(h, 12, item(w.shared ? Material.LIME_DYE : Material.GRAY_DYE,
                    w.shared ? "§aShared with everyone" : "§7Private",
                    "§eClick to make it " + (w.shared ? "private" : "shared")), () -> {
                String err = plugin.setShared(p, w, !w.shared);
                if (err != null) plugin.tell(p, err);
                openDetail(p, w);
            });
            set(h, 14, item(Material.NAME_TAG, "§eRename", "§7You'll type the new name in chat."), () -> promptRename(p, w));
            set(h, 16, item(Material.TNT, "§cDelete"), () -> openConfirm(p, w));
        }

        set(h, 22, item(Material.ARROW, "§eBack"), () -> openMain(p, 0));
        p.openInventory(h.inv);
    }

    private void openConfirm(Player p, Waypoint w) {
        Holder h = newMenu(27, "Delete '" + w.name + "'?");
        set(h, 11, item(Material.RED_CONCRETE, "§cYes, delete it", "§7This can't be undone."), () -> {
            String err = plugin.remove(p, w);
            if (err != null) plugin.tell(p, err);
            else plugin.tell(p, "§aDeleted '" + w.name + "'.");
            openMain(p, 0);
        });
        set(h, 15, item(Material.LIME_CONCRETE, "§aCancel"), () -> openDetail(p, w));
        p.openInventory(h.inv);
    }

    // ---------- chat prompts (chests can't take text input) ----------

    private void promptAdd(Player p) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(false, null));
        plugin.tell(p, "§eType a name for the new waypoint in chat. It saves where you are when you send it. Type 'cancel' to abort.");
    }

    private void promptRename(Player p, Waypoint w) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(true, w));
        plugin.tell(p, "§eType the new name for '" + w.name + "' in chat. Type 'cancel' to abort.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (text.equalsIgnoreCase("cancel")) {
                plugin.tell(p, "§7Cancelled.");
                return;
            }
            if (pd.rename()) {
                String err = plugin.rename(p, pd.wp(), text);
                if (err != null) plugin.tell(p, "§c" + err);
                else plugin.tell(p, "§aRenamed to '" + pd.wp().name + "'.");
            } else {
                String err = plugin.createWaypoint(p, text, false);
                if (err != null) plugin.tell(p, "§c" + err);
                else plugin.tell(p, "§aWaypoint '" + text + "' saved (private). Open /wp menu to share it.");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ---------- click handling ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        Runnable r = h.actions.get(e.getSlot());
        if (r != null) Bukkit.getScheduler().runTask(plugin, r);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
