pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Local development: when uxm-lib is checked out as a local reference or sibling directory,
// build against it directly as a composite build so library changes are picked up without a publish step.
// An explicit property (-Puxm.composite=false) can disable this behavior for clean standalone builds.
// Without local checkout, the published com.uxplima.uxmlib artifacts resolve from repo.uxplima.com.
val compositeEnabled = providers.gradleProperty("uxm.composite").map { it != "false" }.getOrElse(true)
val uxmLibDir = if (compositeEnabled) {
    listOf("references/uxm-lib", "../uxm-lib", "../uxmLib", "../../uxm-lib")
        .map(::file)
        .firstOrNull { it.isDirectory }
} else {
    null
}

if (uxmLibDir != null) {
    includeBuild(uxmLibDir) {
        dependencySubstitution {
            listOf(
                "uxmlib-bom", "uxmlib-common", "uxmlib-item", "uxmlib-gui",
                "uxmlib-menu", "uxmlib-command", "uxmlib-storage", "uxmlib-hud",
                "uxmlib-bedrock", "uxmlib-condition", "uxmlib-integration", "uxmlib-redis",
                "uxmlib-schematic", "uxmlib-packet", "uxmlib-pipeline"
            ).forEach { mod ->
                substitute(module("com.github.UXPLIMA.uxm-lib:$mod")).using(project(":$mod"))
                substitute(module("com.uxplima.uxmlib:$mod")).using(project(":$mod"))
            }
        }
    }
}

rootProject.name = "uxmSkyblock"

include(
    ":api",
    ":core",
    ":persistence-adapter",
    ":bukkit-adapter",
    ":rest-adapter"
)
