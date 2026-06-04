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
        // usb-serial-for-android (mik3y) — CDC-ACM host driver for the u-blox
        // GNSS receiver plugged into the phone's USB port. JitPack-only.
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "MovementLogger"
include(":app")
