import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    `maven-publish`
}

extra["vitrinaKitArtifactId"] = "vitrinakit-kmp-sdk"
extra["vitrinaKitGeneratesPublicationConfig"] = "true"
apply(from = rootProject.file("gradle/publishing-conventions.gradle.kts"))

kotlin {
    jvmToolchain(rootProject.extra["vitrinaKitJvmTargetVersion"] as Int)

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
        commonMain {
            kotlin.srcDir(tasks.named("generateVitrinaKitPublicationConfig"))
        }
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
