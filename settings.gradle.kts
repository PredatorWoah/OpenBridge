pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenBridge"
include(":protocol", ":desktop")
// The Windows build machine has no Android SDK: `-PdesktopOnly` leaves the Android app out.
if (!providers.gradleProperty("desktopOnly").isPresent) include(":android:app")
