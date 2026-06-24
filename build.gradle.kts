import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.dokka") version "2.2.0"
    `maven-publish`
}

group = "ru.vitrina"
version = "0.1.0-rc.2"

kotlin {
    val vitrinaKitXCFramework = XCFramework("VitrinaKit")

    jvm()
    iosArm64 {
        binaries.framework {
            baseName = "VitrinaKit"
            vitrinaKitXCFramework.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "VitrinaKit"
            vitrinaKitXCFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("io.ktor:ktor-client-core:3.3.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.3.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.3.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "Build"
            url = uri(layout.buildDirectory.dir("repository"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ni032mas/vitrinakit-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "VitrinaKit KMP SDK"
            description = "Kotlin Multiplatform SDK for VitrinaKit mobile subscription integrations"
            url = "https://github.com/ni032mas/vitrinakit-kmp"
            licenses {
                license {
                    name = "Proprietary"
                    url = "https://github.com/ni032mas/vitrinakit-kmp"
                }
            }
            developers {
                developer {
                    id = "ni032mas"
                    name = "VitrinaKit"
                    url = "https://github.com/ni032mas"
                }
            }
            scm {
                connection = "scm:git:git://github.com/ni032mas/vitrinakit-kmp.git"
                developerConnection = "scm:git:ssh://git@github.com:ni032mas/vitrinakit-kmp.git"
                url = "https://github.com/ni032mas/vitrinakit-kmp"
            }
        }
    }
}

dokka {
    dokkaPublications.html {
        moduleName.set("VitrinaKit KMP SDK")
        moduleVersion.set(project.version.toString())
        failOnWarning.set(true)
    }
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        suppressGeneratedFiles.set(true)
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the SDK JVM test suite."
    dependsOn("jvmTest")
}

tasks.register("verifySdk") {
    group = "verification"
    description = "Runs SDK tests and generated documentation checks."
    dependsOn("test", "dokkaGenerate")
}

tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Builds and publishes SDK release artifacts into the local Maven dry-run repository."
    dependsOn("verifySdk", "publishAllPublicationsToBuildRepository")
}

tasks.matching { task -> task.name.endsWith("ToGitHubPackagesRepository") }.configureEach {
    dependsOn("verifySdk")
}
