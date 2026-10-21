# WaypointTrail

Paper plugin for 26.3 (Java 25). Works with Geyser: only commands, particles and the action bar are used, so Bedrock players get the same experience.

Commands (/wp, /waypoint):
- /wp add <name> [shared]
- /wp list
- /wp track <name>   (shared waypoints from others: owner:name)
- /wp stop
- /wp share <name> / /wp unshare <name>
- /wp remove <name>

## Build the jar
Upload this folder to a GitHub repo -> Actions tab -> Build -> download the WaypointTrail artifact.
Or locally: install JDK 25 + Gradle 9.1+, run `gradle build`, jar is in build/libs/.
