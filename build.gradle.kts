import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Delete
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.1" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

group = "ru.vitrina"
version = "0.1.0-rc.6"
extra["googlePlayBillingVersion"] = "9.1.0"
extra["kotlinxCoroutinesVersion"] = "1.10.2"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

private val moduleProjectPaths = setOf(
    ":vitrinakit-core",
    ":vitrinakit-googleplay",
    ":vitrinakit-hosted",
    ":vitrinakit-rustore",
)
private val moduleArtifactIds = setOf(
    "vitrinakit-kmp-sdk",
    "vitrinakit-googleplay",
    "vitrinakit-hosted",
    "vitrinakit-rustore",
)
private val coreArtifactIds = setOf(
    "vitrinakit-kmp-sdk",
    "vitrinakit-kmp-sdk-jvm",
    "vitrinakit-kmp-sdk-iosarm64",
    "vitrinakit-kmp-sdk-iossimulatorarm64",
)
private val expectedArtifactBaseIds = coreArtifactIds + setOf(
    "vitrinakit-googleplay",
    "vitrinakit-googleplay-android",
    "vitrinakit-hosted",
    "vitrinakit-hosted-android",
    "vitrinakit-hosted-jvm",
    "vitrinakit-rustore",
    "vitrinakit-rustore-android",
)
private val vitrinaKitPublicationName = providers.gradleProperty("vitrinaKitPublication")
    .orElse(providers.environmentVariable("VITRINAKIT_PUBLICATION"))
    .orElse("production")
    .get()
    .lowercase()
private val vitrinaKitPublicationSuffix = when (vitrinaKitPublicationName) {
    "production" -> ""
    "development" -> "-dev"
    else -> error(
        "Unsupported VitrinaKit publication '$vitrinaKitPublicationName'. " +
            "Use 'production' or 'development'.",
    )
}
private val requiredArtifactIds = expectedArtifactBaseIds.map { artifactId ->
    "$artifactId$vitrinaKitPublicationSuffix"
}.toSet()
private val requiredCoreArtifactIds = coreArtifactIds.map { artifactId ->
    "$artifactId$vitrinaKitPublicationSuffix"
}.toSet()
private val requiredProviderArtifactIds = requiredArtifactIds - requiredCoreArtifactIds

abstract class VerifyReleaseArtifactsTask : DefaultTask() {
    @get:Input
    abstract val expectedArtifactIds: ListProperty<String>

    @get:InputDirectory
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val coreArtifactIds: ListProperty<String>

    @get:Input
    abstract val providerArtifactIds: ListProperty<String>

    @get:Input
    abstract val googlePlayAndroidArtifactId: Property<String>

    @get:Input
    abstract val googlePlayBillingVersion: Property<String>

    @get:Input
    abstract val kotlinxCoroutinesVersion: Property<String>

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        expectedArtifactIds.get().forEach { artifactId ->
            val artifactDirectory = repository.resolve("ru/vitrina/$artifactId")
            if (!artifactDirectory.isDirectory) {
                throw GradleException("Missing published artifact directory: $artifactDirectory")
            }
            if (artifactDirectory.walkTopDown().none { file -> file.extension == "pom" }) {
                throw GradleException("Missing POM for published artifact: $artifactId")
            }
        }

        val providerArtifactIds = providerArtifactIds.get()
        coreArtifactIds.get().forEach { coreArtifactId ->
            val corePomDirectory = repository.resolve("ru/vitrina/$coreArtifactId")
            corePomDirectory.walkTopDown()
                .filter { file -> file.extension == "pom" }
                .forEach { pomFile ->
                    val pom = pomFile.readText()
                    providerArtifactIds.forEach { providerArtifactId ->
                        if (pom.contains("<artifactId>$providerArtifactId</artifactId>")) {
                            throw GradleException(
                                "Core artifact POM must not depend on provider artifact " +
                                    "$providerArtifactId: $pomFile",
                            )
                        }
                    }
                    if (pom.contains("<groupId>com.android.billingclient</groupId>")) {
                        throw GradleException(
                            "Core artifact POM must not depend on Google Play Billing: $pomFile",
                        )
                    }
                }
        }

        val googlePlayPomDirectory = repository.resolve(
            "ru/vitrina/${googlePlayAndroidArtifactId.get()}",
        )
        val googlePlayPoms = googlePlayPomDirectory.walkTopDown()
            .filter { file -> file.extension == "pom" }
            .toList()
        if (googlePlayPoms.size != 1) {
            throw GradleException(
                "Expected one Google Play Android POM, found ${googlePlayPoms.size}: " +
                    googlePlayPomDirectory,
            )
        }
        val googlePlayPom = googlePlayPoms.single().readText()
        val coroutinesAndroidArtifact = "<artifactId>kotlinx-coroutines-android</artifactId>"
        expectedArtifactIds.get()
            .filterNot { artifactId -> artifactId == googlePlayAndroidArtifactId.get() }
            .forEach { artifactId ->
                val artifactDirectory = repository.resolve("ru/vitrina/$artifactId")
                artifactDirectory.walkTopDown()
                    .filter { file -> file.extension == "pom" }
                    .forEach { pomFile ->
                        if (pomFile.readText().contains(coroutinesAndroidArtifact)) {
                            throw GradleException(
                                "Only the Google Play Android POM may depend on " +
                                    "kotlinx-coroutines-android: $pomFile",
                            )
                        }
                    }
            }
        val coroutinesArtifactIndex = googlePlayPom.indexOf(coroutinesAndroidArtifact)
        val coroutinesBlockStart = googlePlayPom.lastIndexOf("<dependency>", coroutinesArtifactIndex)
        val coroutinesBlockEnd = googlePlayPom.indexOf("</dependency>", coroutinesArtifactIndex)
        val coroutinesDependency = if (coroutinesBlockStart >= 0 && coroutinesBlockEnd >= 0) {
            googlePlayPom.substring(coroutinesBlockStart, coroutinesBlockEnd)
        } else {
            ""
        }
        val hasExactRuntimeCoroutinesAndroid =
            coroutinesDependency.contains("<groupId>org.jetbrains.kotlinx</groupId>") &&
                coroutinesDependency.contains(coroutinesAndroidArtifact) &&
                coroutinesDependency.contains("<version>${kotlinxCoroutinesVersion.get()}</version>") &&
                coroutinesDependency.contains("<scope>runtime</scope>") &&
                !coroutinesDependency.contains("<scope>compile</scope>")
        if (!hasExactRuntimeCoroutinesAndroid) {
            throw GradleException(
                "Google Play Android POM must contain kotlinx-coroutines-android " +
                    "${kotlinxCoroutinesVersion.get()} with runtime scope: ${googlePlayPoms.single()}",
            )
        }
        if (coroutinesArtifactIndex != googlePlayPom.lastIndexOf(coroutinesAndroidArtifact)) {
            throw GradleException(
                "Google Play Android POM must contain exactly one kotlinx-coroutines-android " +
                    "dependency: ${googlePlayPoms.single()}",
            )
        }
        val billingGroup = "<groupId>com.android.billingclient</groupId>"
        val billingGroupIndex = googlePlayPom.indexOf(billingGroup)
        val billingBlockStart = googlePlayPom.lastIndexOf("<dependency>", billingGroupIndex)
        val billingBlockEnd = googlePlayPom.indexOf("</dependency>", billingGroupIndex)
        val billingDependency = if (billingBlockStart >= 0 && billingBlockEnd >= 0) {
            googlePlayPom.substring(billingBlockStart, billingBlockEnd)
        } else {
            ""
        }
        val hasExactRuntimeBilling = billingDependency.contains("<artifactId>billing</artifactId>") &&
            billingDependency.contains("<version>${googlePlayBillingVersion.get()}</version>") &&
            billingDependency.contains("<scope>runtime</scope>") &&
            !billingDependency.contains("<scope>compile</scope>")
        if (!hasExactRuntimeBilling) {
            throw GradleException(
                "Google Play Android POM must contain Billing " +
                    "${googlePlayBillingVersion.get()} with runtime scope: ${googlePlayPoms.single()}",
            )
        }
        if (billingGroupIndex != googlePlayPom.lastIndexOf(billingGroup)) {
            throw GradleException(
                "Google Play Android POM must contain exactly one Billing dependency: " +
                    googlePlayPoms.single(),
            )
        }
    }
}

tasks.register("verifyModuleTopology") {
    group = "verification"
    description = "Verifies the provider-neutral SDK artifact topology."

    doLast {
        val actualProjectPaths = rootProject.subprojects.map { subproject -> subproject.path }.toSet()
        val missingProjectPaths = moduleProjectPaths - actualProjectPaths
        if (missingProjectPaths.isNotEmpty()) {
            throw GradleException(
                "Missing SDK artifact projects: ${missingProjectPaths.sorted().joinToString()}",
            )
        }

        val publications = rootProject.subprojects.flatMap { subproject ->
            subproject.extensions.findByType(PublishingExtension::class.java)
                ?.publications
                ?.withType(MavenPublication::class.java)
                ?.map { publication -> publication.artifactId }
                .orEmpty()
        }
        if (publications.size != publications.toSet().size) {
            throw GradleException("SDK artifact IDs must be unique: ${publications.joinToString()}")
        }

        val providerProjectPaths = moduleProjectPaths - ":vitrinakit-core"
        val coreProject = project(":vitrinakit-core")
        val providerDependencies = coreProject.configurations
            .flatMap { configuration -> configuration.dependencies }
            .filterIsInstance<ProjectDependency>()
            .map { dependency -> dependency.path }
            .filter { dependencyPath -> dependencyPath in providerProjectPaths }
        if (providerDependencies.isNotEmpty()) {
            throw GradleException(
                "vitrinakit-core must not depend on provider projects: " +
                    providerDependencies.distinct().sorted().joinToString(),
            )
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs all SDK unit test targets."
    dependsOn(
        ":vitrinakit-core:jvmTest",
        ":vitrinakit-hosted:jvmTest",
        ":vitrinakit-googleplay:testAndroidHostTest",
        ":vitrinakit-rustore:testAndroidHostTest",
    )
}

tasks.register("verifySdk") {
    group = "verification"
    description = "Runs SDK tests and generated documentation checks."
    dependsOn(
        "test",
        ":vitrinakit-core:dokkaGenerate",
        ":vitrinakit-googleplay:dokkaGenerate",
        ":vitrinakit-hosted:dokkaGenerate",
        ":vitrinakit-rustore:dokkaGenerate",
    )
}

val prepareReleaseArtifactRepository = tasks.register<Delete>("prepareReleaseArtifactRepository") {
    group = "verification"
    description = "Removes stale local Maven artifacts before release verification."
    delete(layout.buildDirectory.dir("repository"))
}

tasks.register("verifyReleaseArtifacts", VerifyReleaseArtifactsTask::class) {
    group = "verification"
    description = "Publishes all SDK artifacts locally and verifies POM dependency isolation."
    expectedArtifactIds.set(requiredArtifactIds.sorted())
    repositoryDirectory.set(layout.buildDirectory.dir("repository"))
    coreArtifactIds.set(requiredCoreArtifactIds.sorted())
    providerArtifactIds.set(requiredProviderArtifactIds.sorted())
    googlePlayAndroidArtifactId.set("vitrinakit-googleplay-android$vitrinaKitPublicationSuffix")
    googlePlayBillingVersion.set(rootProject.extra["googlePlayBillingVersion"] as String)
    kotlinxCoroutinesVersion.set(rootProject.extra["kotlinxCoroutinesVersion"] as String)
    dependsOn(
        "verifySdk",
        prepareReleaseArtifactRepository,
        ":vitrinakit-core:publishAllPublicationsToBuildRepository",
        ":vitrinakit-googleplay:publishAllPublicationsToBuildRepository",
        ":vitrinakit-hosted:publishAllPublicationsToBuildRepository",
        ":vitrinakit-rustore:publishAllPublicationsToBuildRepository",
    )
}

tasks.register("verifyReleaseArtifactContract") {
    group = "verification"
    description = "Verifies the complete release artifact contract."
    dependsOn("verifyReleaseArtifacts")

    doLast {
        val releaseTask = tasks.named("verifyReleaseArtifacts", VerifyReleaseArtifactsTask::class).get()
        val violations = buildList {
            if (releaseTask.expectedArtifactIds.get().toSet() != requiredArtifactIds) {
                add("Release verification must require every root and target artifact.")
            }
            if (releaseTask.coreArtifactIds.get().toSet() != requiredCoreArtifactIds) {
                add("Release verification must inspect every core platform POM.")
            }
            if (releaseTask.providerArtifactIds.get().toSet() != requiredProviderArtifactIds) {
                add("Release verification must reject every provider root and target artifact ID.")
            }

            val generatedConfigPath = "generated/vitrinakit-publication-config/$vitrinaKitPublicationName/commonMain/kotlin/" +
                "ru/vitrina/sdk/VitrinaKitPublicationConfig.kt"
            val providerProjects = rootProject.subprojects.filter { project ->
                project.path != ":vitrinakit-core"
            }
            val duplicateConfigProjects = providerProjects.filter { project ->
                project.layout.buildDirectory.file(generatedConfigPath).get().asFile.isFile
            }
            if (duplicateConfigProjects.isNotEmpty()) {
                add(
                    "Generated publication config must only be compiled by core: " +
                        duplicateConfigProjects.joinToString { project -> project.path },
                )
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString(separator = "\n"))
        }
    }
}

tasks.register("assembleVitrinaKitXCFramework") {
    group = "build"
    description = "Builds the VitrinaKit iOS XCFramework from the core SDK."
    dependsOn(":vitrinakit-core:assembleVitrinaKitXCFramework")
}
