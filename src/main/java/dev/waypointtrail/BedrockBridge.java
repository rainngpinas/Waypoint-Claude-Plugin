package dev.waypointtrail;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Lets the main plugin talk to Floodgate without loading Floodgate classes when it isn't installed. */
public interface BedrockBridge {
    boolean isBedrock(UUID id);

    void openMain(Player p);
}
