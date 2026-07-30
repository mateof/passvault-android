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
    }
}

rootProject.name = "PassVault"
include(":app")

// A test harness, never shipped. It exists because the two import routes can only be exercised
// by a real content:// URI handed over by another application, which adb cannot fabricate.
include(":tools:sender")
