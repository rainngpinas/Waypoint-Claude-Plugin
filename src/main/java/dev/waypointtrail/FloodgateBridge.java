package dev.waypointtrail;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native Bedrock forms through Floodgate. Only loaded when Floodgate is installed. */
public class FloodgateBridge implements BedrockBridge {

    private final WaypointTrail plugin;
    private final FloodgateApi api;

    public FloodgateBridge(WaypointTrail plugin) {
        this.plugin = plugin;
        this.api = FloodgateApi.getInstance();
    }

    @Override
    public boolean isBedrock(UUID id) {
        return api.isFloodgatePlayer(id);
    }

    /** Form callbacks come from another thread, so hop back to the main server thread. */
    private void sync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @Override
    public void openMain(Player p) {
        List<Waypoint> list = plugin.visibleTo(p);
        List<Runnable> actions = new ArrayList<>();

        SimpleForm.Builder form = SimpleForm.builder();
        form.title("Waypoints");
        form.content(list.isEmpty()
                ? "You have no waypoints yet. Tap 'New waypoint' to save your current spot."
                : "Tap a waypoint to track or manage it.");

        form.button("New waypoint here");
        actions.add(() -> openAdd(p));

        if (plugin.isTracking(p)) {
            form.button("Stop tracking");
            actions.add(() -> plugin.stopTracking(p, true));
        }

        for (Waypoint w : list) {
            boolean mine = w.owner.equals(p.getUniqueId());
            String sub = mine ? (w.shared ? "Yours - shared" : "Yours - private") : "Shared by " + w.ownerName;
            form.button(w.name + "\n" + sub);
            actions.add(() -> openDetail(p, w));
        }

        form.validResultHandler(r -> sync(() -> {
            int id = r.clickedButtonId();
            if (id >= 0 && id < actions.size()) actions.get(id).run();
        }));
        api.sendForm(p.getUniqueId(), form);
    }

    private void openDetail(Player p, Waypoint w) {
        if (!plugin.exists(w)) {
            plugin.tell(p, "§eThat waypoint no longer exists.");
            openMain(p);
            return;
        }
        boolean mine = w.owner.equals(p.getUniqueId());
        List<Runnable> actions = new ArrayList<>();

        SimpleForm.Builder form = SimpleForm.builder();
        form.title(w.name);
        form.content("Position: " + plugin.posText(w)
                + "\nWorld: " + plugin.dimName(w.world)
                + "\nOwner: " + (mine ? "you" : w.ownerName)
                + "\nVisible to: " + (w.shared ? "everyone" : "only the owner"));

        form.button("Track this waypoint");
        actions.add(() -> plugin.startTracking(p, w));

        if (mine) {
            form.button(w.shared ? "Make private" : "Share with everyone");
            actions.add(() -> {
                String err = plugin.setShared(p, w, !w.shared);
                if (err != null) plugin.tell(p, err);
                else plugin.tell(p, "§a'" + w.name + "' is now " + (w.shared ? "shared with everyone." : "private."));
                openDetail(p, w);
            });
            form.button("Rename");
            actions.add(() -> openRename(p, w));
            form.button("Delete");
            actions.add(() -> openDelete(p, w));
        }

        form.button("Back");
        actions.add(() -> openMain(p));

        form.validResultHandler(r -> sync(() -> {
            int id = r.clickedButtonId();
            if (id >= 0 && id < actions.size()) actions.get(id).run();
        }));
        api.sendForm(p.getUniqueId(), form);
    }

    private void openAdd(Player p) {
        CustomForm.Builder form = CustomForm.builder();
        form.title("New waypoint");
        form.input("Name", "e.g. Home");
        form.toggle("Share with everyone", false);
        form.validResultHandler(r -> sync(() -> {
            String name = r.asInput(0);
            boolean shared = Boolean.TRUE.equals(r.asToggle(1));
            String err = plugin.createWaypoint(p, name, shared);
            if (err != null) {
                plugin.tell(p, "§c" + err);
                return;
            }
            plugin.tell(p, "§aWaypoint '" + name.trim() + "' saved" + (shared ? " (shared)." : " (private)."));
        }));
        api.sendForm(p.getUniqueId(), form);
    }

    private void openRename(Player p, Waypoint w) {
        CustomForm.Builder form = CustomForm.builder();
        form.title("Rename waypoint");
        form.input("New name", "Waypoint name", w.name);
        form.validResultHandler(r -> sync(() -> {
            String name = r.asInput(0);
            String err = plugin.rename(p, w, name);
            if (err != null) plugin.tell(p, "§c" + err);
            else plugin.tell(p, "§aRenamed to '" + w.name + "'.");
            if (plugin.exists(w)) openDetail(p, w);
        }));
        api.sendForm(p.getUniqueId(), form);
    }

    private void openDelete(Player p, Waypoint w) {
        ModalForm.Builder form = ModalForm.builder();
        form.title("Delete waypoint");
        form.content("Delete '" + w.name + "'? This can't be undone.");
        form.button1("Yes, delete");
        form.button2("Cancel");
        form.validResultHandler(r -> sync(() -> {
            if (r.clickedFirst()) {
                String err = plugin.remove(p, w);
                if (err != null) plugin.tell(p, err);
                else plugin.tell(p, "§aDeleted '" + w.name + "'.");
                openMain(p);
            } else {
                openDetail(p, w);
            }
        }));
        api.sendForm(p.getUniqueId(), form);
    }
}
