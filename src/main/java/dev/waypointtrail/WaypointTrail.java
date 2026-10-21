package dev.waypointtrail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * WaypointTrail: private or shared waypoints, with a particle trail to follow.
 * Only uses commands + particles + action bar, so it works the same for Java and Geyser/Bedrock players.
 */
public class WaypointTrail extends JavaPlugin implements TabExecutor {

    private static final int MAX_PER_PLAYER = 50;
    private static final double TRAIL_LENGTH = 14;   // blocks of trail shown ahead of the player
    private static final double TRAIL_SPACING = 1.0; // blocks between particles
    private static final double ARRIVE_DISTANCE = 3.0;

    private static class Waypoint {
        UUID owner;
        String ownerName;
        String name;
        String world;
        double x, y, z;
        boolean shared;
    }

    // owner -> (lowercase name -> waypoint)
    private final Map<UUID, Map<String, Waypoint>> waypoints = new HashMap<>();
    // player -> currently tracked waypoint
    private final Map<UUID, Waypoint> tracking = new HashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "waypoints.yml");
        getDataFolder().mkdirs();
        load();
        Objects.requireNonNull(getCommand("wp")).setExecutor(this);
        Objects.requireNonNull(getCommand("wp")).setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickTrails, 10L, 5L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- storage ----------

    private void load() {
        waypoints.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yml.getConfigurationSection("waypoints");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Waypoint w = new Waypoint();
                w.owner = UUID.fromString(s.getString("owner", ""));
                w.ownerName = s.getString("ownerName", "?");
                w.name = s.getString("name", "");
                w.world = s.getString("world", "");
                w.x = s.getDouble("x");
                w.y = s.getDouble("y");
                w.z = s.getDouble("z");
                w.shared = s.getBoolean("shared");
                waypoints.computeIfAbsent(w.owner, k -> new LinkedHashMap<>()).put(w.name.toLowerCase(), w);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Map<String, Waypoint> map : waypoints.values()) {
            for (Waypoint w : map.values()) {
                String p = "waypoints." + (i++) + ".";
                yml.set(p + "owner", w.owner.toString());
                yml.set(p + "ownerName", w.ownerName);
                yml.set(p + "name", w.name);
                yml.set(p + "world", w.world);
                yml.set(p + "x", w.x);
                yml.set(p + "y", w.y);
                yml.set(p + "z", w.z);
                yml.set(p + "shared", w.shared);
            }
        }
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save waypoints: " + e.getMessage());
        }
    }

    // ---------- lookup ----------

    private Waypoint findOwn(Player p, String name) {
        Map<String, Waypoint> map = waypoints.get(p.getUniqueId());
        return map == null ? null : map.get(name.toLowerCase());
    }

    /** Own waypoint first, then any shared waypoint with that name. Also accepts "owner:name". */
    private Waypoint findVisible(Player p, String name) {
        if (name.contains(":")) {
            String[] parts = name.split(":", 2);
            for (Map<String, Waypoint> map : waypoints.values()) {
                for (Waypoint w : map.values()) {
                    if (w.shared && w.ownerName.equalsIgnoreCase(parts[0]) && w.name.equalsIgnoreCase(parts[1])) return w;
                }
            }
            return null;
        }
        Waypoint own = findOwn(p, name);
        if (own != null) return own;
        for (Map<String, Waypoint> map : waypoints.values()) {
            for (Waypoint w : map.values()) {
                if (w.shared && w.name.equalsIgnoreCase(name)) return w;
            }
        }
        return null;
    }

    private List<Waypoint> visibleTo(Player p) {
        List<Waypoint> list = new ArrayList<>();
        for (Map<String, Waypoint> map : waypoints.values()) {
            for (Waypoint w : map.values()) {
                if (w.owner.equals(p.getUniqueId()) || w.shared) list.add(w);
            }
        }
        return list;
    }

    // ---------- trail ----------

    private void tickTrails() {
        Iterator<Map.Entry<UUID, Waypoint>> it = tracking.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waypoint> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Waypoint w = e.getValue();
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            // waypoint was deleted or made private by someone else
            boolean stillValid = waypoints.getOrDefault(w.owner, Map.of()).get(w.name.toLowerCase()) == w
                    && (w.shared || w.owner.equals(p.getUniqueId()));
            if (!stillValid) {
                p.sendMessage("§eThat waypoint is no longer available. Tracking stopped.");
                it.remove();
                continue;
            }
            if (!p.getWorld().getName().equals(w.world)) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("§7Waypoint '" + w.name + "' is in another world (" + w.world + ")"));
                continue;
            }
            Location from = p.getLocation().add(0, 0.8, 0);
            Vector to = new Vector(w.x, w.y, w.z).subtract(from.toVector());
            double dist = to.length();
            if (dist <= ARRIVE_DISTANCE) {
                p.sendMessage("§aYou arrived at '" + w.name + "'.");
                p.sendActionBar(net.kyori.adventure.text.Component.text("§aArrived!"));
                it.remove();
                continue;
            }
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "§b" + w.name + " §7- §f" + (int) dist + " blocks"));

            Vector dir = to.clone().normalize();
            double max = Math.min(TRAIL_LENGTH, dist - 1);
            World world = p.getWorld();
            for (double d = 2; d <= max; d += TRAIL_SPACING) {
                Vector pos = from.toVector().add(dir.clone().multiply(d));
                p.spawnParticle(Particle.END_ROD, pos.getX(), pos.getY(), pos.getZ(), 1, 0, 0, 0, 0);
            }
            // beacon-like marker at the destination when it's within view range
            if (dist < 64) {
                for (double dy = 0; dy < 6; dy += 1.0) {
                    p.spawnParticle(Particle.END_ROD, w.x, w.y + dy, w.z, 1, 0.1, 0, 0.1, 0);
                }
            }
        }
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add", "create", "set" -> {
                if (args.length < 2) {
                    p.sendMessage("§cUsage: /wp add <name> [shared]");
                    return true;
                }
                String name = args[1];
                if (name.contains(":") || name.length() > 24) {
                    p.sendMessage("§cName can't contain ':' and must be 24 characters or less.");
                    return true;
                }
                Map<String, Waypoint> mine = waypoints.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
                if (mine.containsKey(name.toLowerCase())) {
                    p.sendMessage("§cYou already have a waypoint called '" + name + "'. Remove it first.");
                    return true;
                }
                if (mine.size() >= MAX_PER_PLAYER) {
                    p.sendMessage("§cYou reached the limit of " + MAX_PER_PLAYER + " waypoints.");
                    return true;
                }
                Location l = p.getLocation();
                Waypoint w = new Waypoint();
                w.owner = p.getUniqueId();
                w.ownerName = p.getName();
                w.name = name;
                w.world = l.getWorld().getName();
                w.x = l.getBlockX() + 0.5;
                w.y = l.getBlockY();
                w.z = l.getBlockZ() + 0.5;
                w.shared = args.length >= 3 && args[2].equalsIgnoreCase("shared");
                mine.put(name.toLowerCase(), w);
                save();
                p.sendMessage("§aWaypoint '" + name + "' created" + (w.shared ? " §7(shared)" : " §7(private)") + "§a.");
            }
            case "list" -> {
                List<Waypoint> list = visibleTo(p);
                if (list.isEmpty()) {
                    p.sendMessage("§7No waypoints yet. Use /wp add <name>");
                    return true;
                }
                p.sendMessage("§6--- Waypoints ---");
                for (Waypoint w : list) {
                    boolean mine = w.owner.equals(p.getUniqueId());
                    String who = mine ? "you" : w.ownerName;
                    p.sendMessage("§e" + w.name + " §7[" + (w.shared ? "shared" : "private") + ", by " + who + "] §f"
                            + (int) w.x + " " + (int) w.y + " " + (int) w.z + " §7(" + w.world + ")");
                }
            }
            case "remove", "delete" -> {
                if (args.length < 2) {
                    p.sendMessage("§cUsage: /wp remove <name>");
                    return true;
                }
                Map<String, Waypoint> mine = waypoints.get(p.getUniqueId());
                Waypoint w = mine == null ? null : mine.remove(args[1].toLowerCase());
                if (w == null) {
                    p.sendMessage("§cYou don't have a waypoint called '" + args[1] + "'. (You can only remove your own.)");
                    return true;
                }
                save();
                p.sendMessage("§aRemoved '" + w.name + "'.");
            }
            case "track", "go", "navigate" -> {
                if (args.length < 2) {
                    p.sendMessage("§cUsage: /wp track <name>");
                    return true;
                }
                Waypoint w = findVisible(p, args[1]);
                if (w == null) {
                    p.sendMessage("§cNo waypoint called '" + args[1] + "'. Try /wp list");
                    return true;
                }
                tracking.put(p.getUniqueId(), w);
                p.sendMessage("§aFollow the particles to '" + w.name + "'. Use /wp stop to cancel.");
            }
            case "stop", "untrack" -> {
                if (tracking.remove(p.getUniqueId()) != null) p.sendMessage("§eTracking stopped.");
                else p.sendMessage("§7You're not tracking anything.");
            }
            case "share", "unshare" -> {
                if (args.length < 2) {
                    p.sendMessage("§cUsage: /wp " + sub + " <name>");
                    return true;
                }
                Waypoint w = findOwn(p, args[1]);
                if (w == null) {
                    p.sendMessage("§cYou don't have a waypoint called '" + args[1] + "'.");
                    return true;
                }
                w.shared = sub.equals("share");
                save();
                p.sendMessage("§a'" + w.name + "' is now " + (w.shared ? "shared with everyone." : "private."));
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage("§6--- WaypointTrail ---");
        p.sendMessage("§e/wp add <name> [shared] §7- save your current spot");
        p.sendMessage("§e/wp list §7- your waypoints + shared ones");
        p.sendMessage("§e/wp track <name> §7- show a particle trail to it");
        p.sendMessage("§e/wp stop §7- stop the trail");
        p.sendMessage("§e/wp share|unshare <name> §7- change who can see it");
        p.sendMessage("§e/wp remove <name> §7- delete one of yours");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player p)) return List.of();
        if (args.length == 1) {
            return filter(List.of("add", "list", "track", "stop", "share", "unshare", "remove"), args[0]);
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            switch (sub) {
                case "track", "go", "navigate" -> {
                    List<String> names = new ArrayList<>();
                    for (Waypoint w : visibleTo(p)) {
                        names.add(w.owner.equals(p.getUniqueId()) ? w.name : w.ownerName + ":" + w.name);
                    }
                    return filter(names, args[1]);
                }
                case "remove", "delete", "share", "unshare" -> {
                    Map<String, Waypoint> mine = waypoints.getOrDefault(p.getUniqueId(), Map.of());
                    List<String> names = new ArrayList<>();
                    for (Waypoint w : mine.values()) names.add(w.name);
                    return filter(names, args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && sub.equals("add")) return filter(List.of("shared"), args[2]);
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
