package dev.waypointtrail;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * WaypointTrail: private or shared waypoints, tracked with a boss bar,
 * managed with commands, Java chest menus and Bedrock (Floodgate) forms.
 * Only the owner of a waypoint can share, unshare, rename or delete it.
 */
public class WaypointTrail extends JavaPlugin {

    private static final double ARRIVE_DISTANCE = 3.0;

    private static class Track {
        final Waypoint wp;
        final BossBar bar;
        double maxDist = 1;

        Track(Waypoint wp, BossBar bar) {
            this.wp = wp;
            this.bar = bar;
        }
    }

    // owner -> (lowercase name -> waypoint)
    private final Map<UUID, Map<String, Waypoint>> waypoints = new HashMap<>();
    private final Map<UUID, Track> tracking = new HashMap<>();
    private File dataFile;
    private BedrockBridge bedrock;
    private JavaMenus javaMenus;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "waypoints.yml");
        load();

        javaMenus = new JavaMenus(this);
        getServer().getPluginManager().registerEvents(javaMenus, this);

        if (getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrock = new FloodgateBridge(this);
                getLogger().info("Floodgate found: Bedrock players get form menus.");
            } catch (Throwable t) {
                getLogger().warning("Floodgate found, but its API could not be used: " + t);
            }
        } else {
            getLogger().info("Floodgate not found: everyone gets the chest menu.");
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(buildCommand().build(), "Create waypoints and track them",
                        List.of("waypoint", "waypoints")));

        Bukkit.getScheduler().runTaskTimer(this, this::tickTracking, 10L, 5L);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Track> e : tracking.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue().bar);
        }
        tracking.clear();
        save();
    }

    // ================= text helpers =================

    static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    void tell(Player p, String s) {
        p.sendMessage(legacy(s));
    }

    String posText(Waypoint w) {
        return "X: " + (int) Math.floor(w.x) + "  Y: " + (int) Math.floor(w.y) + "  Z: " + (int) Math.floor(w.z);
    }

    String dimName(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return worldName;
        return switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            case NORMAL -> w.equals(Bukkit.getWorlds().get(0)) ? "Overworld" : worldName;
            default -> worldName;
        };
    }

    // ================= storage =================

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

    // ================= lookup =================

    boolean exists(Waypoint w) {
        return waypoints.getOrDefault(w.owner, Map.of()).get(w.name.toLowerCase()) == w;
    }

    Waypoint findOwn(Player p, String name) {
        Map<String, Waypoint> map = waypoints.get(p.getUniqueId());
        return map == null ? null : map.get(name.toLowerCase());
    }

    /** Own waypoint first, then a shared one with that name. Also accepts "owner:name". */
    Waypoint findVisible(Player p, String name) {
        if (name.contains(":")) {
            String[] parts = name.split(":", 2);
            for (Map<String, Waypoint> map : waypoints.values()) {
                for (Waypoint w : map.values()) {
                    if (w.shared && w.ownerName.equalsIgnoreCase(parts[0]) && w.name.equalsIgnoreCase(parts[1])) return w;
                }
            }
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

    /** The player's own waypoints first, then everyone else's shared ones. */
    List<Waypoint> visibleTo(Player p) {
        List<Waypoint> mine = new ArrayList<>();
        List<Waypoint> others = new ArrayList<>();
        for (Map<String, Waypoint> map : waypoints.values()) {
            for (Waypoint w : map.values()) {
                if (w.owner.equals(p.getUniqueId())) mine.add(w);
                else if (w.shared) others.add(w);
            }
        }
        Comparator<Waypoint> byName = Comparator.comparing(w -> w.name.toLowerCase());
        mine.sort(byName);
        others.sort(byName);
        mine.addAll(others);
        return mine;
    }

    // ================= actions (shared by commands and menus) =================

    private String validateName(String name) {
        if (name.isEmpty()) return "The name can't be empty.";
        if (name.length() > 32) return "The name must be 32 characters or less.";
        if (name.contains(":") || name.contains("§")) return "The name can't contain ':' or '§'.";
        return null;
    }

    private String notOwner(Waypoint w) {
        return "§cOnly the owner (" + w.ownerName + ") can change that waypoint.";
    }

    /** Returns an error message, or null on success. */
    String createWaypoint(Player p, String raw, boolean shared) {
        String name = raw == null ? "" : raw.trim();
        String err = validateName(name);
        if (err != null) return err;
        Map<String, Waypoint> mine = waypoints.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        if (mine.containsKey(name.toLowerCase())) return "You already have a waypoint called '" + name + "'.";
        Location l = p.getLocation();
        Waypoint w = new Waypoint();
        w.owner = p.getUniqueId();
        w.ownerName = p.getName();
        w.name = name;
        w.world = l.getWorld().getName();
        w.x = l.getBlockX() + 0.5;
        w.y = l.getBlockY();
        w.z = l.getBlockZ() + 0.5;
        w.shared = shared;
        mine.put(name.toLowerCase(), w);
        save();
        return null;
    }

    String setShared(Player p, Waypoint w, boolean shared) {
        if (!w.owner.equals(p.getUniqueId())) return notOwner(w);
        w.shared = shared;
        save();
        return null;
    }

    String rename(Player p, Waypoint w, String raw) {
        if (!w.owner.equals(p.getUniqueId())) return notOwner(w);
        String name = raw == null ? "" : raw.trim();
        String err = validateName(name);
        if (err != null) return err;
        Map<String, Waypoint> mine = waypoints.get(w.owner);
        if (mine == null || mine.get(w.name.toLowerCase()) != w) return "That waypoint no longer exists.";
        Waypoint clash = mine.get(name.toLowerCase());
        if (clash != null && clash != w) return "You already have a waypoint called '" + name + "'.";
        mine.remove(w.name.toLowerCase());
        w.name = name;
        mine.put(name.toLowerCase(), w);
        save();
        return null;
    }

    String remove(Player p, Waypoint w) {
        if (!w.owner.equals(p.getUniqueId())) return notOwner(w);
        Map<String, Waypoint> mine = waypoints.get(w.owner);
        if (mine == null || mine.remove(w.name.toLowerCase()) == null) return "That waypoint no longer exists.";
        save();
        return null;
    }

    // ================= menus =================

    void openMenu(Player p) {
        if (bedrock != null && bedrock.isBedrock(p.getUniqueId())) bedrock.openMain(p);
        else javaMenus.openMain(p, 0);
    }

    // ================= tracking (boss bar) =================

    boolean isTracking(Player p) {
        return tracking.containsKey(p.getUniqueId());
    }

    void startTracking(Player p, Waypoint w) {
        stopTracking(p, false);
        BossBar bar = BossBar.bossBar(legacy("§b" + w.name), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        Track t = new Track(w, bar);
        tracking.put(p.getUniqueId(), t);
        p.showBossBar(bar);
        tell(p, "§aTracking '" + w.name + "' §7- §f" + posText(w) + " §7(" + dimName(w.world) + "). Use /wp stop to cancel.");
        updateTrack(p, t);
    }

    void stopTracking(Player p, boolean message) {
        Track t = tracking.remove(p.getUniqueId());
        if (t == null) {
            if (message) tell(p, "§7You're not tracking anything.");
            return;
        }
        p.hideBossBar(t.bar);
        p.sendActionBar(Component.empty());
        if (message) tell(p, "§eTracking stopped.");
    }

    private void tickTracking() {
        Iterator<Map.Entry<UUID, Track>> it = tracking.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Track> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Track t = e.getValue();
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            // deleted, or made private by its owner
            if (!exists(t.wp) || !(t.wp.shared || t.wp.owner.equals(p.getUniqueId()))) {
                p.hideBossBar(t.bar);
                p.sendActionBar(Component.empty());
                tell(p, "§eThat waypoint is no longer available. Tracking stopped.");
                it.remove();
                continue;
            }
            if (!updateTrack(p, t)) {
                p.hideBossBar(t.bar);
                it.remove();
            }
        }
    }

    /** Returns false when the player arrived and tracking should end. */
    private boolean updateTrack(Player p, Track t) {
        Waypoint w = t.wp;
        String pos = posText(w);
        String dim = dimName(w.world);

        if (!p.getWorld().getName().equals(w.world)) {
            t.bar.name(legacy("§b" + w.name + " §7| §cin another world: §f" + dim));
            t.bar.progress(0f);
            t.bar.color(BossBar.Color.RED);
            p.sendActionBar(legacy("§7Position: §f" + pos + " §7(" + dim + ")"));
            return true;
        }

        Location loc = p.getLocation();
        double dx = w.x - loc.getX();
        double dy = w.y - loc.getY();
        double dz = w.z - loc.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (horiz <= ARRIVE_DISTANCE && Math.abs(dy) <= 6) {
            tell(p, "§aYou arrived at '" + w.name + "'.");
            p.sendActionBar(legacy("§aArrived at " + w.name + "!"));
            return false;
        }

        t.maxDist = Math.max(t.maxDist, horiz);
        float progress = (float) Math.max(0, Math.min(1, 1 - horiz / t.maxDist));
        t.bar.progress(progress);
        t.bar.color(BossBar.Color.GREEN);

        StringBuilder title = new StringBuilder("§b" + w.name + " §7| §f" + (int) horiz + " blocks §7| §e" + direction(p, dx, dz));
        if (dy > 3) title.append(" §7| §d").append((int) dy).append(" up");
        else if (dy < -3) title.append(" §7| §d").append((int) -dy).append(" down");
        t.bar.name(legacy(title.toString()));

        p.sendActionBar(legacy("§7Position: §f" + pos + " §7(" + dim + ")"));
        return true;
    }

    /** Where the waypoint is relative to where the player is facing. */
    private String direction(Player p, double dx, double dz) {
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = targetYaw - p.getLocation().getYaw();
        rel = ((rel + 180) % 360 + 360) % 360 - 180;
        double a = Math.abs(rel);
        String side = rel > 0 ? "right" : "left";
        if (a < 22.5) return "straight ahead";
        if (a < 67.5) return "ahead " + side;
        if (a < 112.5) return side;
        if (a < 157.5) return "behind " + side;
        return "behind";
    }

    // ================= commands (Brigadier, so Bedrock gets suggestions) =================

    private int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) action.accept(p);
        else ctx.getSource().getSender().sendMessage("Players only.");
        return Command.SINGLE_SUCCESS;
    }

    private List<String> trackNames(CommandContext<CommandSourceStack> ctx) {
        List<String> names = new ArrayList<>();
        if (ctx.getSource().getSender() instanceof Player p) {
            for (Waypoint w : visibleTo(p)) {
                names.add(w.owner.equals(p.getUniqueId()) ? w.name : w.ownerName + ":" + w.name);
            }
        }
        return names;
    }

    private List<String> ownNames(CommandContext<CommandSourceStack> ctx) {
        List<String> names = new ArrayList<>();
        if (ctx.getSource().getSender() instanceof Player p) {
            for (Waypoint w : visibleTo(p)) if (w.owner.equals(p.getUniqueId())) names.add(w.name);
        }
        return names;
    }

    private CompletableFuture<Suggestions> suggest(SuggestionsBuilder b, Collection<String> names, boolean quoteSpaces) {
        String rem = b.getRemainingLowerCase();
        for (String n : names) {
            String s = (quoteSpaces && n.contains(" ")) ? "\"" + n + "\"" : n;
            if (s.toLowerCase().startsWith(rem)) b.suggest(s);
        }
        return b.buildFuture();
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("wp")
                .executes(ctx -> run(ctx, this::openMenu))
                .then(Commands.literal("menu").executes(ctx -> run(ctx, this::openMenu)))
                .then(Commands.literal("help").executes(ctx -> run(ctx, this::help)))
                .then(Commands.literal("list").executes(ctx -> run(ctx, this::list)))
                .then(Commands.literal("stop").executes(ctx -> run(ctx, p -> stopTracking(p, true))))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> run(ctx, p -> cmdAdd(p, StringArgumentType.getString(ctx, "name"), false)))
                                .then(Commands.literal("private")
                                        .executes(ctx -> run(ctx, p -> cmdAdd(p, StringArgumentType.getString(ctx, "name"), false))))
                                .then(Commands.literal("shared")
                                        .executes(ctx -> run(ctx, p -> cmdAdd(p, StringArgumentType.getString(ctx, "name"), true))))))
                .then(Commands.literal("track")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> suggest(b, trackNames(ctx), false))
                                .executes(ctx -> run(ctx, p -> cmdTrack(p, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("share")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> suggest(b, ownNames(ctx), false))
                                .executes(ctx -> run(ctx, p -> cmdShare(p, StringArgumentType.getString(ctx, "name"), true)))))
                .then(Commands.literal("unshare")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> suggest(b, ownNames(ctx), false))
                                .executes(ctx -> run(ctx, p -> cmdShare(p, StringArgumentType.getString(ctx, "name"), false)))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, b) -> suggest(b, ownNames(ctx), true))
                                .then(Commands.argument("newname", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, p -> cmdRename(p,
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "newname")))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> suggest(b, ownNames(ctx), false))
                                .executes(ctx -> run(ctx, p -> cmdRemove(p, StringArgumentType.getString(ctx, "name"))))));
    }

    private void cmdAdd(Player p, String name, boolean shared) {
        String err = createWaypoint(p, name, shared);
        if (err != null) tell(p, "§c" + err);
        else tell(p, "§aWaypoint '" + name.trim() + "' created §7(" + (shared ? "shared" : "private") + ")§a.");
    }

    private void cmdTrack(Player p, String name) {
        Waypoint w = findVisible(p, name.trim());
        if (w == null) tell(p, "§cNo waypoint called '" + name + "'. Try /wp list");
        else startTracking(p, w);
    }

    private void cmdShare(Player p, String name, boolean shared) {
        Waypoint w = findVisible(p, name.trim());
        if (w == null) {
            tell(p, "§cNo waypoint called '" + name + "'.");
            return;
        }
        String err = setShared(p, w, shared);
        if (err != null) tell(p, err);
        else tell(p, "§a'" + w.name + "' is now " + (shared ? "shared with everyone." : "private."));
    }

    private void cmdRename(Player p, String name, String newName) {
        Waypoint w = findVisible(p, name.trim());
        if (w == null) {
            tell(p, "§cNo waypoint called '" + name + "'.");
            return;
        }
        String err = rename(p, w, newName);
        if (err != null) tell(p, err.startsWith("§") ? err : "§c" + err);
        else tell(p, "§aRenamed to '" + w.name + "'.");
    }

    private void cmdRemove(Player p, String name) {
        Waypoint w = findVisible(p, name.trim());
        if (w == null) {
            tell(p, "§cNo waypoint called '" + name + "'.");
            return;
        }
        String err = remove(p, w);
        if (err != null) tell(p, err);
        else tell(p, "§aRemoved '" + w.name + "'.");
    }

    private void list(Player p) {
        List<Waypoint> list = visibleTo(p);
        if (list.isEmpty()) {
            tell(p, "§7No waypoints yet. Use /wp add <name> or /wp menu");
            return;
        }
        tell(p, "§6--- Waypoints ---");
        for (Waypoint w : list) {
            String who = w.owner.equals(p.getUniqueId()) ? "you" : w.ownerName;
            tell(p, "§e" + w.name + " §7[" + (w.shared ? "shared" : "private") + ", by " + who + "] §f"
                    + posText(w) + " §7(" + dimName(w.world) + ")");
        }
    }

    private void help(Player p) {
        tell(p, "§6--- WaypointTrail ---");
        tell(p, "§e/wp §7- open the menu");
        tell(p, "§e/wp add <name> [shared] §7- save your current spot");
        tell(p, "§e/wp list §7- your waypoints + shared ones");
        tell(p, "§e/wp track <name> §7- boss bar to the waypoint");
        tell(p, "§e/wp stop §7- stop tracking");
        tell(p, "§e/wp share|unshare <name> §7- owner only");
        tell(p, "§e/wp rename <name> <new name> §7- owner only");
        tell(p, "§e/wp remove <name> §7- owner only");
    }
}
