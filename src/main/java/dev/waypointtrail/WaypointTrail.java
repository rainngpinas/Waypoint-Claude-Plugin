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
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * WaypointTrail: private or shared waypoints, tracked with a boss bar,
 * managed with commands, Java chest menus and Bedrock (Floodgate) forms.
 * Only the owner of a waypoint can share, unshare, rename or delete it.
 */
public class WaypointTrail extends JavaPlugin implements Listener {

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

    // every waypoint by its permanent ID
    private final Map<UUID, Waypoint> all = new LinkedHashMap<>();
    // active boss bars (only while the player is online)
    private final Map<UUID, Track> tracking = new HashMap<>();
    // player -> tracked waypoint ID, remembered across logout and restarts
    private final Map<UUID, UUID> savedTracking = new HashMap<>();
    private File dataFile;
    private File trackingFile;
    private BedrockBridge bedrock;
    private JavaMenus javaMenus;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "waypoints.yml");
        trackingFile = new File(getDataFolder(), "tracking.yml");
        load();
        loadTracking();
        getServer().getPluginManager().registerEvents(this, this);

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

    /** Reads a YAML file. If it is damaged, sets it aside (never overwrites it) and tries the .bak copy. */
    private YamlConfiguration readYaml(File f) {
        if (!f.exists()) return null;
        try {
            YamlConfiguration yml = new YamlConfiguration();
            yml.load(f);
            return yml;
        } catch (Exception e) {
            getLogger().severe("Could not read " + f.getName() + ": " + e.getMessage());
            File bad = new File(f.getParentFile(), f.getName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(f.toPath(), bad.toPath());
                getLogger().severe("The damaged file was kept as " + bad.getName());
            } catch (IOException ignored) {
            }
            File bak = new File(f.getParentFile(), f.getName() + ".bak");
            if (bak.exists()) {
                try {
                    YamlConfiguration yml = new YamlConfiguration();
                    yml.load(bak);
                    getLogger().warning("Restored " + f.getName() + " from its backup.");
                    return yml;
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /** Writes to a temp file first, then swaps it in, so a crash mid-save can't corrupt the real file. */
    private void safeSave(File target, YamlConfiguration yml, boolean keepBackup) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            yml.save(tmp);
            if (keepBackup && target.exists()) {
                Files.copy(target.toPath(), new File(target.getParentFile(), target.getName() + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().warning("Could not save " + target.getName() + ": " + e.getMessage());
        }
    }

    private void load() {
        all.clear();
        YamlConfiguration yml = readYaml(dataFile);
        if (yml == null) return;
        ConfigurationSection root = yml.getConfigurationSection("waypoints");
        if (root == null) return;
        boolean migrated = false;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Waypoint w = new Waypoint();
                String id = s.getString("id");
                if (id != null) w.id = UUID.fromString(id);
                else migrated = true; // older file without IDs: a new one is generated
                w.owner = UUID.fromString(s.getString("owner", ""));
                w.ownerName = s.getString("ownerName", "?");
                w.name = s.getString("name", "");
                w.world = s.getString("world", "");
                w.x = s.getDouble("x");
                w.y = s.getDouble("y");
                w.z = s.getDouble("z");
                w.shared = s.getBoolean("shared");
                all.put(w.id, w);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (migrated) save();
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Waypoint w : all.values()) {
            String p = "waypoints." + (i++) + ".";
            yml.set(p + "id", w.id.toString());
            yml.set(p + "owner", w.owner.toString());
            yml.set(p + "ownerName", w.ownerName);
            yml.set(p + "name", w.name);
            yml.set(p + "world", w.world);
            yml.set(p + "x", w.x);
            yml.set(p + "y", w.y);
            yml.set(p + "z", w.z);
            yml.set(p + "shared", w.shared);
        }
        safeSave(dataFile, yml, true);
    }

    private void loadTracking() {
        savedTracking.clear();
        YamlConfiguration yml = readYaml(trackingFile);
        if (yml == null) return;
        ConfigurationSection root = yml.getConfigurationSection("tracking");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                savedTracking.put(UUID.fromString(key), UUID.fromString(root.getString(key, "")));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveTracking() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, UUID> e : savedTracking.entrySet()) {
            yml.set("tracking." + e.getKey(), e.getValue().toString());
        }
        safeSave(trackingFile, yml, false);
    }

    private void forgetTracking(UUID player) {
        if (savedTracking.remove(player) != null) saveTracking();
    }

    // ================= join / quit =================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        // keep the shown owner name current if the player changed their Minecraft name
        boolean changed = false;
        for (Waypoint w : all.values()) {
            if (w.owner.equals(p.getUniqueId()) && !w.ownerName.equals(p.getName())) {
                w.ownerName = p.getName();
                changed = true;
            }
        }
        if (changed) save();

        // pick tracking back up where they left off
        UUID wid = savedTracking.get(p.getUniqueId());
        if (wid != null) {
            Waypoint w = all.get(wid);
            if (w != null && (w.shared || w.owner.equals(p.getUniqueId()))) begin(p, w, true);
            else forgetTracking(p.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // the boss bar disappears with the player; the saved tracking target stays for next login
        tracking.remove(e.getPlayer().getUniqueId());
    }

    // ================= lookup =================

    boolean exists(Waypoint w) {
        return all.get(w.id) == w;
    }

    private Waypoint findByOwnerAndName(UUID owner, String name) {
        for (Waypoint w : all.values()) {
            if (w.owner.equals(owner) && w.name.equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    Waypoint findOwn(Player p, String name) {
        return findByOwnerAndName(p.getUniqueId(), name);
    }

    /** Own waypoint first, then a shared one with that name. Also accepts "owner:name". */
    Waypoint findVisible(Player p, String name) {
        if (name.contains(":")) {
            String[] parts = name.split(":", 2);
            for (Waypoint w : all.values()) {
                if (w.shared && w.ownerName.equalsIgnoreCase(parts[0]) && w.name.equalsIgnoreCase(parts[1])) return w;
            }
        }
        Waypoint own = findOwn(p, name);
        if (own != null) return own;
        for (Waypoint w : all.values()) {
            if (w.shared && w.name.equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    /** The player's own waypoints first, then everyone else's shared ones. */
    List<Waypoint> visibleTo(Player p) {
        List<Waypoint> mine = new ArrayList<>();
        List<Waypoint> others = new ArrayList<>();
        for (Waypoint w : all.values()) {
            if (w.owner.equals(p.getUniqueId())) mine.add(w);
            else if (w.shared) others.add(w);
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
        if (findOwn(p, name) != null) return "You already have a waypoint called '" + name + "'.";
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
        all.put(w.id, w);
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
        if (!exists(w)) return "That waypoint no longer exists.";
        Waypoint clash = findByOwnerAndName(w.owner, name);
        if (clash != null && clash != w) return "You already have a waypoint called '" + name + "'.";
        w.name = name;
        save();
        return null;
    }

    String remove(Player p, Waypoint w) {
        if (!w.owner.equals(p.getUniqueId())) return notOwner(w);
        if (all.remove(w.id) == null) return "That waypoint no longer exists.";
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
        begin(p, w, false);
    }

    private void begin(Player p, Waypoint w, boolean resumed) {
        Track old = tracking.remove(p.getUniqueId());
        if (old != null) p.hideBossBar(old.bar);
        BossBar bar = BossBar.bossBar(legacy("§b" + w.name), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        Track t = new Track(w, bar);
        tracking.put(p.getUniqueId(), t);
        p.showBossBar(bar);
        if (!w.id.equals(savedTracking.get(p.getUniqueId()))) {
            savedTracking.put(p.getUniqueId(), w.id);
            saveTracking();
        }
        tell(p, (resumed ? "§aResumed tracking '" : "§aTracking '") + w.name + "' §7- §f" + posText(w)
                + " §7(" + dimName(w.world) + "). Use /wp stop to cancel.");
        updateTrack(p, t);
    }

    void stopTracking(Player p, boolean message) {
        forgetTracking(p.getUniqueId());
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
                forgetTracking(p.getUniqueId());
                it.remove();
                continue;
            }
            if (!updateTrack(p, t)) {
                p.hideBossBar(t.bar);
                forgetTracking(p.getUniqueId());
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
                                .executes(ctx -> run(ctx, p -> cmdRemove(p, StringArgumentType.getString(ctx, "name"))))))
                // console-only cleanup: players (admins included) can't use this
                .then(Commands.literal("admin")
                        .requires(src -> !(src.getSender() instanceof Player))
                        .then(Commands.literal("list").executes(ctx -> {
                            adminList(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("target", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            adminRemove(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "target"));
                                            return Command.SINGLE_SUCCESS;
                                        }))));
    }

    private void adminList(CommandSender s) {
        if (all.isEmpty()) {
            s.sendMessage("No waypoints saved.");
            return;
        }
        for (Waypoint w : all.values()) {
            s.sendMessage("[" + w.id.toString().substring(0, 8) + "] " + w.name + " - " + w.ownerName
                    + " (" + (w.shared ? "shared" : "private") + ") " + posText(w) + " in " + w.world);
        }
        s.sendMessage("Remove one with: wp admin remove <id> or wp admin remove <owner>:<name>");
    }

    private void adminRemove(CommandSender s, String target) {
        String t = target.trim();
        List<Waypoint> matches = new ArrayList<>();
        for (Waypoint w : all.values()) {
            boolean idMatch = t.length() >= 4 && w.id.toString().toLowerCase().startsWith(t.toLowerCase());
            boolean nameMatch = t.contains(":") && (w.ownerName + ":" + w.name).equalsIgnoreCase(t);
            if (idMatch || nameMatch) matches.add(w);
        }
        if (matches.size() != 1) {
            s.sendMessage(matches.isEmpty() ? "No waypoint matches '" + t + "'." : "More than one waypoint matches, use a longer id.");
            return;
        }
        Waypoint w = matches.get(0);
        all.remove(w.id);
        save();
        s.sendMessage("Removed '" + w.name + "' owned by " + w.ownerName + ".");
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
