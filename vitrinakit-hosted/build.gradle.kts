import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

apply(from = rootProject.file("gradle/publishing-conventions.gradle.kts"))

kotlin {
    jvmToolchain(rootProject.extra["vitrinaKitJvmTargetVersion"] as Int)

    val vitrinaKitHostedXCFramework = XCFramework("VitrinaKitHosted")

    jvm()
    iosArm64 {
        binaries.framework {
            baseName = "VitrinaKitHosted"
            export(project(":vitrinakit-core"))
            vitrinaKitHostedXCFramework.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "VitrinaKitHosted"
            export(project(":vitrinakit-core"))
            vitrinaKitHostedXCFramework.add(this)
        }
    }
    androidLibrary {
        namespace = "ru.vitrina.sdk.hosted"
        compileSdk = 36
        minSdk = 23
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vitrinakit-core"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("io.ktor:ktor-http:3.3.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
