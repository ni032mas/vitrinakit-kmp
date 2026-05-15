import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.dokka") version "2.2.0"
}

version = "0.1.0"

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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
