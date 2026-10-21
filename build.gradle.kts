plugins {
    java
}

group = "dev.waypointtrail"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // If this exact build can't be found, change it to "26.3.build.+" or "26.2.build.+"
    compileOnly("io.papermc.paper:paper-api:26.3.build.143-beta")
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
