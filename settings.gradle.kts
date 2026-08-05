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

rootProject.name = "vitrinakit-kmp-sdk"

include(
    ":vitrinakit-core",
    ":vitrinakit-googleplay",
    ":vitrinakit-hosted",
    ":vitrinakit-rustore",
)
