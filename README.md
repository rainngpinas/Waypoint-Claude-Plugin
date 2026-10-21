# WaypointTrail 1.1.0

Paper plugin for 26.3 (Java 25). Works with Geyser; Floodgate is optional and gives Bedrock players native form menus.

- /wp  -> menu (chest menu for Java, forms for Bedrock via Floodgate)
- /wp add <name> [shared|private]
- /wp list
- /wp track <name>   (shared waypoints from others: owner:name)
- /wp stop
- /wp share <name> / /wp unshare <name>   (owner only)
- /wp rename <name> <new name>            (owner only)
- /wp remove <name>                       (owner only)

Tracking shows a boss bar (name, distance, direction) and the exact X/Y/Z on the action bar.

## Build the jar
Upload to a GitHub repo -> Actions tab -> Build -> download the WaypointTrail artifact.
Or locally: JDK 25 + Gradle 9.1+, `gradle build`, jar is in build/libs/.
