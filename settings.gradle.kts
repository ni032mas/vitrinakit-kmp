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
        maven {
            url = uri("https://artifactory-external.vkpartner.ru/artifactory/maven-rustore-exposed/")
        }
    }
}

rootProject.name = "vitrinakit-kmp-sdk"

include(
    ":vitrinakit-core",
    ":vitrinakit-googleplay",
    ":vitrinakit-hosted",
    ":vitrinakit-rustore",
)
