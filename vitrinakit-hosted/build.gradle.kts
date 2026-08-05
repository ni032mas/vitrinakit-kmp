plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

apply(from = rootProject.file("gradle/publishing-conventions.gradle.kts"))

kotlin {
    jvm()
    androidLibrary {
        namespace = "ru.vitrina.sdk.hosted"
        compileSdk = 36
        minSdk = 23
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vitrinakit-core"))
        }
    }
}
