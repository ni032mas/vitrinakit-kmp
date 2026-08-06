plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

apply(from = rootProject.file("gradle/publishing-conventions.gradle.kts"))

val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String
val ruStoreBomVersion = rootProject.extra["ruStoreBomVersion"] as String

kotlin {
    androidLibrary {
        namespace = "ru.vitrina.sdk.rustore"
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
            implementation(project.dependencies.platform("ru.rustore.sdk:bom:$ruStoreBomVersion"))
            implementation("ru.rustore.sdk:pay")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
        }
        named("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
        }
    }
}
