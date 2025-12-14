plugins {
    id("java")
    id("maven-publish")
    id("com.gradleup.shadow") version "8.3.0"
}

repositories {
    mavenLocal()
    maven("https://repo.codemc.io/repository/maven-snapshots/") //anvilgui
    maven("https://repo.codemc.io/repository/maven-releases/") //packetevents
    maven("https://repo.dmulloy2.net/repository/public/") // ProtocolLib
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") // PlaceholderAPI
    maven("https://repo.papermc.io/repository/maven-public/") // PaperMC
    maven("https://jitpack.io")
    maven("https://repo.aikar.co/content/groups/aikar/") //aikar

    maven("https://repo.mikeprimm.com/") //dynmap
    maven("https://maven.enginehub.org/repo/") //worldguard
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.mikeprimm.com/")
    maven("https://nexus.wesjd.net/repository/thirdparty/")
    maven("https://repo.opencollab.dev/main/")

    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    mavenCentral()
}
dependencies {
    // Custom Utils
     implementation("com.github.CrashCraftNetwork:CrashUtils:master-SNAPSHOT")
     compileOnly("com.github.Chasewhip8:CrashPayment:master-SNAPSHOT")

    // Paper
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")

    // Adventure
    val adventure = "4.19.0"
    implementation("net.kyori:adventure-api:${adventure}")
    implementation("net.kyori:adventure-platform-bukkit:4.3.2")
    implementation("net.kyori:adventure-text-minimessage:${adventure}")

    // Other
    implementation("co.aikar:taskchain-bukkit:3.7.2")
    implementation("net.wesjd:anvilgui:1.10.8-SNAPSHOT")
    implementation("co.aikar:fastutil-base:3.0-SNAPSHOT")
    implementation("co.aikar:fastutil-longbase:3.0-SNAPSHOT")
    implementation("co.aikar:fastutil-longhashmap:3.0-SNAPSHOT")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    implementation("io.papermc:paperlib:1.0.7")
    implementation("co.aikar:idb-core:1.0.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("org.bstats:bstats-bukkit:3.0.1")

    implementation("com.github.retrooper:packetevents-spigot:2.11.0")

    compileOnly("com.google.guava:guava:31.1-jre")

    compileOnly("com.comphenix.protocol:ProtocolLib:5.4.0-SNAPSHOT")
    compileOnly( "net.milkbowl.vault:VaultAPI:1.7"){
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly( "com.sk89q.worldguard:worldguard-bukkit:7.0.14")
    compileOnly( "com.github.TechFortress:GriefPrevention:16.16.0")
    compileOnly("com.ghostchu:quickshop-bukkit:6.1.0.0-SNAPSHOT")
    compileOnly("com.ghostchu:quickshop-common:6.1.0.0-SNAPSHOT")
    compileOnly("com.ghostchu:quickshop-api:6.1.0.0-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.5")
    compileOnly("us.dynmap:dynmap-api:3.2-SNAPSHOT") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("net.luckperms:api:5.4")
    implementation("commons-lang:commons-lang:2.6")
    // Cache2k
    val cache2kVersion = "2.6.1.Final"

    implementation("org.cache2k:cache2k-api:${cache2kVersion}")
    runtimeOnly("org.cache2k:cache2k-core:${cache2kVersion}")
}

tasks {
    shadowJar {
        archiveFileName.set("${project.name}-${project.version}.jar")

        relocate("co.aikar.commands", "net.crashcraft.crashclaim.acf")
        relocate("co.aikar.idb", "net.crashcraft.crashclaim.idb")
        relocate("dev.whip.crashutils", "net.crashcraft.crashclaim.crashutils")
        relocate("co.aikar.taskchain", "net.crashcraft.crashclaim.taskchain")
        relocate("io.papermc.lib", "net.crashcraft.crashclaim.paperlib")
        relocate("org.bstats", "net.crashcraft.crashclaim.bstats")
        relocate("it.unimi.dsi", "net.crashcraft.crashclaim.fastutil")
        relocate("org.cache2k.IntCache", "net.crashcraft.crashclaim.cache2k")
        relocate("com.zaxxer.hikari", "net.crashcraft.crashclaim.hikari")
        relocate("com.github.retrooper.packetevents", "net.crashcraft.crashclaim.packetevents.api")
        relocate("io.github.retrooper.packetevents", "net.crashcraft.crashclaim.packetevents.plugin")
    }

    register<Copy>("buildToServer") {
        from(shadowJar)
        into("./testServer/plugins")
    }

    build {
        dependsOn(shadowJar)
        dependsOn(publishToMavenLocal)
    }

    compileJava {
        options.encoding = "UTF-8"
        dependsOn(clean)
    }

    processResources {
        expand(project.properties)
    }
}

group = "net.crashcraft"
version = findProperty("version")!!
description = "CrashClaim"
java.sourceCompatibility = JavaVersion.VERSION_21

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}