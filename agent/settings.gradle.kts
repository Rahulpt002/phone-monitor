pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu (root shell) is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.topjohnwu.*") }
        }
    }
}

rootProject.name = "hotspot-agent"
include(":app")
