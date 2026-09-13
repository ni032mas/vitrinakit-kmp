pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    ":samples:android-flavors:app",
    ":vitrinakit-core",
    ":vitrinakit-googleplay",
    ":vitrinakit-hosted",
    ":vitrinakit-rustore",
)

project(":samples:android-flavors:app").projectDir = file("samples/android-flavors/app")
