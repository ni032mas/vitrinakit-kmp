plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

apply(from = rootProject.file("gradle/publishing-conventions.gradle.kts"))

val googlePlayBillingVersion = rootProject.extra["googlePlayBillingVersion"] as String
val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String

kotlin {
    androidLibrary {
        namespace = "ru.vitrina.sdk.googleplay"
        compileSdk = 36
        minSdk = 23
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vitrinakit-core"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
        }
        androidMain.dependencies {
            implementation("com.android.billingclient:billing:$googlePlayBillingVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
        }
        named("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
        }
    }
}
