import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

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
        commonMain {
            kotlin.srcDir(tasks.named("generateVitrinaKitPublicationConfig"))
        }
        commonMain.dependencies {
            api(project(":vitrinakit-core"))
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
