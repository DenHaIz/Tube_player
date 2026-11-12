pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Только NewPipeExtractor и его nanojson — остальное не берётся с JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "Tube"
include(":core-byedpi", ":core-strategy", ":core-tunnel", ":core-ui", ":app", ":client")
