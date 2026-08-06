plugins {
    id("com.android.application")
}

android {
    namespace = "ru.vitrina.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.vitrina.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "billing"
    productFlavors {
        create("global") {
            dimension = "billing"
        }
        create("ru") {
            dimension = "billing"
        }
    }
}

val sdkVersion = providers.gradleProperty("vitrinaKitSampleVersion").orElse("0.1.0-rc.7")
val sourceSdkBuild = rootProject.findProject(":vitrinakit-core") != null

dependencies {
    if (sourceSdkBuild) {
        implementation(project(":vitrinakit-core"))
        "globalImplementation"(project(":vitrinakit-googleplay"))
        "ruImplementation"(project(":vitrinakit-hosted"))
    } else {
        implementation("ru.vitrina:vitrinakit-kmp-sdk:${sdkVersion.get()}")
        "globalImplementation"("ru.vitrina:vitrinakit-googleplay:${sdkVersion.get()}")
        "ruImplementation"("ru.vitrina:vitrinakit-hosted:${sdkVersion.get()}")
    }
}
