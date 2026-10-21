import java.util.zip.ZipFile

plugins {
    id("uxmskyblock.java-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":rest-adapter"))
    implementation(project(":persistence-adapter"))
    api(project(":api"))

    compileOnly(libs.paper.api)
    compileOnly(libs.bundles.adventure)
    testImplementation(libs.paper.api)

    // uxmLib modules (Platform UI, commands, HUD, bedrock, integrations)
    implementation(libs.uxmlib.common)
    implementation(libs.uxmlib.item)
    implementation(libs.uxmlib.gui)
    implementation(libs.uxmlib.menu)
    implementation(libs.uxmlib.command)
    implementation(libs.uxmlib.hud)
    implementation(libs.uxmlib.bedrock)
    implementation(libs.uxmlib.condition)
    implementation(libs.uxmlib.integration)
    implementation(libs.uxmlib.redis)
    implementation(libs.uxmlib.schematic)
    // Holograms a player reads are sent per viewer and never spawned.
    implementation(libs.uxmlib.packet)
    implementation(libs.lettuce.core)
    implementation(libs.bstats.bukkit)

    // Testing harness
    testImplementation(libs.mockbukkit)
    // A booted test server needs a real wallet: without one the island bank refuses every move.
    testImplementation(libs.vault.api) {
        exclude(group = "org.bukkit")
    }
    // A restore test runs the real relational snapshot and vault store over an in-memory SQLite.
    testImplementation(libs.uxmlib.storage)
    testImplementation(libs.archunit.junit)
    testImplementation(libs.jqwik)
    // The packet hologram's test kit: a recording packet sink and a probe that reads back what a client holds.
    testImplementation(testFixtures(libs.uxmlib.packet)) {
        exclude(group = "org.spongepowered")
    }
    // A real Redis, for the test that Lettuce runs on the server's netty.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
}

// The server's netty is the only one this plugin runs on, so the tests run on the same version.
configurations.testRuntimeClasspath {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            useVersion(libs.versions.netty.get())
            because("the jar bundles no netty, and the server carries this one")
        }
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}

tasks.shadowJar {
    // Paper owns these. A second copy of slf4j with no binding behind it does not fail: it makes
    // this plugin's logging go quiet, which is worse. Adventure is the server's too.
    exclude("org/slf4j/**")
    exclude("net/kyori/**")
    // Annotations a compiler reads and a runtime never loads.
    dependencies {
        exclude(dependency("com.google.errorprone:.*:.*"))
        exclude(dependency("org.checkerframework:.*:.*"))
        exclude(dependency("org.jspecify:.*:.*"))
        exclude(dependency("org.jetbrains:annotations:.*"))
        exclude(dependency("io.netty:.*:.*"))
    }
    // uxmlib-packet is compiled against the Mojang mapped server. This attribute tells Paper's runtime
    // not to remap the jar, so its references to the server's own classes resolve as written.
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }
    // Everything bundled carries this plugin's namespace, so two plugins that shade the same library
    // never share a class, and a copy the server has on its own class path never stands in for ours.
    // uxmLib shipped un-relocated here while every other plugin of the family relocated it.
    // org.sqlite is never relocated: the driver finds its native library by its own package name.
    // Netty is not bundled at all. The library's packet pipeline speaks to the server's own channels,
    // and relocating a bundled copy would rewrite those references with the rest. Lettuce 7 is built on
    // the same 4.2 line the server carries, and redisIntegrationTest runs it on the server's version.
    for (pkg in listOf(
        "com.uxplima.uxmlib" to "uxmlib",
        "org.bstats" to "bstats",
        "com.typesafe.config" to "typesafe",
        "org.spongepowered.configurate" to "configurate",
        "io.leangen.geantyref" to "geantyref",
        "com.zaxxer.hikari" to "hikari",
        "com.github.benmanes.caffeine" to "caffeine",
        "com.google.gson" to "gson",
        "io.lettuce" to "lettuce",
        "reactor" to "reactor",
        "org.reactivestreams" to "reactivestreams",
        "redis.clients" to "jedis",
        "io.javalin" to "javalin",
        "org.eclipse.jetty" to "jetty",
        "jakarta.servlet" to "jakarta.servlet",
        "javax.servlet" to "javax.servlet",
        "kotlin" to "kotlin",
        "org.intellij" to "intellij",
        "org.jetbrains" to "jetbrains",
        "org.postgresql" to "postgresql",
        "org.mariadb" to "mariadb",
    )) {
        relocate(pkg.first, "com.uxplima.uxmskyblock.libs." + pkg.second)
    }
    // Shadow keeps the first copy of a duplicate path unless told otherwise, and its service merge
    // only sees the copies it is given. A services file is a duplicate by design, one per JDBC driver,
    // and kept first only MariaDB's survived: a server pointed at PostgreSQL found no driver.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    mergeServiceFiles()
}

tasks.test {
    // The tests run bStats unshaded, and its relocation check would refuse every enabled plugin.
    // The shaded jar is still relocated and a real server still runs the check.
    systemProperty("bstats.relocatecheck", "false")
    useJUnitPlatform {
        excludeTags("redis-integration")
    }
}

// Needs Docker. Lettuce against a real Redis, on the server's netty.
val redisIntegrationTest by tasks.registering(Test::class) {
    description = "Runs the tests that need a real Redis."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("redis-integration")
    }
    shouldRunAfter(tasks.test)
}

/**
 * The jar is audited rather than trusted.
 *
 * The REST module brought an HTTP server into the plugin and with it a second slf4j, and nothing in
 * the build said so. A hand count is right on the day somebody runs it and never again.
 */
val verifyJar by tasks.registering {
    description = "Fails when the shaded jar holds a package the server already owns."
    group = "verification"
    dependsOn(tasks.shadowJar)
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    doLast {
        val forbidden =
            listOf(
                "org/slf4j/",
                "net/kyori/",
                "org/bstats/",
                "com/uxplima/uxmlib/",
                "com/typesafe/",
                "org/spongepowered/",
                "io/leangen/",
                "com/zaxxer/",
                "com/github/benmanes/",
                "com/google/",
                "io/lettuce/",
                "reactor/",
                "org/reactivestreams/",
                "io/netty/",
                "redis/",
                "io/javalin/",
                "org/eclipse/",
                "jakarta/",
                "javax/",
                "kotlin/",
                "org/intellij/",
                "org/jetbrains/",
                "org/postgresql/",
                "org/mariadb/",
                "org/checkerframework/",
            )
        val found = mutableListOf<String>()
        ZipFile(jar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                for (prefix in forbidden) {
                    if (name.startsWith(prefix) && name.endsWith(".class")) {
                        found.add(prefix)
                    }
                }
            }
        }
        if (found.isNotEmpty()) {
            throw GradleException(
                "The shaded jar holds packages the server owns or that must carry this plugin's " +
                    "namespace: " + found.distinct().joinToString(", ") +
                    ". A second copy of these does not fail loudly: it makes logging or text go wrong, " +
                    "or another plugin's copy of a library answers for ours.",
            )
        }
        // The list above names the libraries bundled today, and a library added tomorrow is on no
        // list. So every class must also sit in this plugin's namespace, or be the SQLite driver,
        // which finds its native library by its own package name, and the same for the copies a
        // multi-release jar keeps for later Java versions.
        val allowed = listOf("com/uxplima/uxmskyblock/", "org/sqlite/")
        val stray = sortedSetOf<String>()
        ZipFile(jar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                val path = Regex("^META-INF/versions/\\d+/").replace(entry.name, "")
                if (path.endsWith(".class") &&
                    !path.endsWith("module-info.class") &&
                    allowed.none { path.startsWith(it) }
                ) {
                    stray.add(path.substringBeforeLast('/'))
                }
            }
        }
        if (stray.isNotEmpty()) {
            throw GradleException(
                "The shaded jar holds classes outside this plugin's namespace: " +
                    stray.take(10).joinToString(", ") +
                    ". Relocate their library in the shadowJar block, or say there why it may not move.",
            )
        }
        // A JDBC driver is found through the services file, not by its class being present. The
        // PostgreSQL driver sat in the jar unlisted once, and a server pointed at PostgreSQL did not
        // start: "No suitable driver".
        val drivers =
            mapOf(
                "com/uxplima/uxmskyblock/libs/mariadb/jdbc/Driver.class" to
                    "com.uxplima.uxmskyblock.libs.mariadb.jdbc.Driver",
                "com/uxplima/uxmskyblock/libs/postgresql/Driver.class" to
                    "com.uxplima.uxmskyblock.libs.postgresql.Driver",
            )
        ZipFile(jar.get().asFile).use { zip ->
            val listed =
                zip
                    .getEntry("META-INF/services/java.sql.Driver")
                    ?.let { entry ->
                        zip
                            .getInputStream(entry)
                            .bufferedReader()
                            .readLines()
                            .map { it.trim() }
                    }.orEmpty()
            val unlisted =
                drivers
                    .filter { (file, _) -> zip.getEntry(file) != null }
                    .values
                    .filterNot { it in listed }
            if (unlisted.isNotEmpty()) {
                throw GradleException(
                    "The shaded jar holds JDBC drivers its services file does not list: " +
                        unlisted.joinToString(", ") +
                        ". A server configured for one of them will not find it.",
                )
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyJar) }
