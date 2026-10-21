package dev.waypointtrail;

import java.util.UUID;

public class Waypoint {
    UUID id = UUID.randomUUID(); // permanent hidden ID, survives renames
    UUID owner;
    String ownerName;
    String name;
    String world;
    double x, y, z;
    boolean shared;
}
