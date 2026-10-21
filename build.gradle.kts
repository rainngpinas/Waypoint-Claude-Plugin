plugins {
    java
}

group = "dev.waypointtrail"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // If this exact build can't be found, change it to "26.3.build.+" or "26.2.build.+"
    compileOnly("io.papermc.paper:paper-api:26.3.build.143-beta")
    // Floodgate is optional at runtime (used for Bedrock form menus)
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("WaypointTrail.jar")
}
