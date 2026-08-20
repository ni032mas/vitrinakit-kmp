import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Delete
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("com.android.application") version "9.0.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.1" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

group = "ru.vitrina"
version = "0.1.0-rc.13"
extra["googlePlayBillingVersion"] = "9.1.0"
extra["kotlinxCoroutinesVersion"] = "1.10.2"
extra["ruStoreBomVersion"] = "2026.07.01"
extra["ruStorePayVersion"] = "11.0.0"

// Class file major version 61 == Java 17 (major = Java SE version + 44 for SE 9+).
// https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.1
private val vitrinaKitJvmTargetVersion = 17
private val vitrinaKitJvmTargetClassFileMajorVersion = 61
extra["vitrinaKitJvmTargetVersion"] = vitrinaKitJvmTargetVersion

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
private val coreArtifactIds = setOf(
    "vitrinakit-kmp-sdk",
    "vitrinakit-kmp-sdk-jvm",
    "vitrinakit-kmp-sdk-android",
    "vitrinakit-kmp-sdk-iosarm64",
    "vitrinakit-kmp-sdk-iossimulatorarm64",
)
private val expectedArtifactBaseIds = coreArtifactIds + setOf(
    "vitrinakit-googleplay",
    "vitrinakit-googleplay-android",
    "vitrinakit-hosted",
    "vitrinakit-hosted-android",
    "vitrinakit-hosted-iosarm64",
    "vitrinakit-hosted-iossimulatorarm64",
    "vitrinakit-hosted-jvm",
    "vitrinakit-rustore",
    "vitrinakit-rustore-android",
)

// The base artifact IDs above that actually carry JVM bytecode (.jar / .aar classes.jar).
// The root "kotlinMultiplatform" and Apple publications carry no JVM bytecode and are excluded.
private val jvmBytecodeArtifactBaseIds = setOf(
    "vitrinakit-kmp-sdk-jvm",
    "vitrinakit-kmp-sdk-android",
    "vitrinakit-googleplay-android",
    "vitrinakit-hosted-android",
    "vitrinakit-hosted-jvm",
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
private val requiredJvmBytecodeArtifactIds = jvmBytecodeArtifactBaseIds.map { artifactId ->
    "$artifactId$vitrinaKitPublicationSuffix"
}.toSet()

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
    abstract val hostedAppleArtifactIds: ListProperty<String>

    @get:Input
    abstract val coreAppleArtifactIds: ListProperty<String>

    @get:Input
    abstract val jvmBytecodeArtifactIds: ListProperty<String>

    @get:Input
    abstract val expectedClassFileMajorVersion: Property<Int>

    @get:Input
    abstract val googlePlayAndroidArtifactId: Property<String>

    @get:Input
    abstract val googlePlayBillingVersion: Property<String>

    @get:Input
    abstract val kotlinxCoroutinesVersion: Property<String>

    @get:Input
    abstract val ruStoreAndroidArtifactId: Property<String>

    @get:Input
    abstract val ruStoreBomVersion: Property<String>

    @get:Input
    abstract val ruStorePayVersion: Property<String>

    @get:Input
    abstract val ruStoreResolvedPayVersion: Property<String>

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        expectedArtifactIds.get().forEach { artifactId ->
            val artifactDirectory = repository.resolve("ru/vitrina/$artifactId")
            if (!artifactDirectory.isDirectory) {
                throw GradleException("Missing published artifact directory: $artifactDirectory")
            }
            val artifactFiles = artifactDirectory.walkTopDown().filter { file -> file.isFile }.toList()
            val requiredFileKinds = mapOf(
                "POM" to artifactFiles.count { file -> file.extension == "pom" },
                "Gradle module metadata" to artifactFiles.count { file -> file.extension == "module" },
                "sources JAR" to artifactFiles.count { file -> file.name.endsWith("-sources.jar") },
            )
            requiredFileKinds.forEach { (kind, count) ->
                if (count != 1) {
                    throw GradleException(
                        "Expected one $kind for published artifact $artifactId, found $count: " +
                            artifactDirectory,
                    )
                }
            }
        }

        val expectedMajorVersion = expectedClassFileMajorVersion.get()
        jvmBytecodeArtifactIds.get().forEach { artifactId ->
            val artifactDirectory = repository.resolve("ru/vitrina/$artifactId")
            val classBearingFiles = artifactDirectory.walkTopDown()
                .filter { file -> file.extension == "jar" || file.extension == "aar" }
                .filterNot { file -> file.name.endsWith("-sources.jar") }
                .toList()
            if (classBearingFiles.isEmpty()) {
                throw GradleException(
                    "No published JAR or AAR carrying JVM bytecode found for $artifactId: $artifactDirectory",
                )
            }
            var checkedClassFileCount = 0
            classBearingFiles.forEach { archiveFile ->
                val classesJarBytes = if (archiveFile.extension == "aar") {
                    ZipFile(archiveFile).use { aar ->
                        val classesEntry = aar.getEntry("classes.jar")
                            ?: throw GradleException("AAR is missing classes.jar: $archiveFile")
                        aar.getInputStream(classesEntry).use { input -> input.readBytes() }
                    }
                } else {
                    archiveFile.readBytes()
                }
                ZipInputStream(classesJarBytes.inputStream()).use { classesJar ->
                    var entry = classesJar.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(".class")) {
                            val header = classesJar.readNBytes(8)
                            if (header.size != 8) {
                                throw GradleException(
                                    "Truncated class file header for ${entry.name} in $archiveFile",
                                )
                            }
                            val majorVersion =
                                ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                            if (majorVersion != expectedMajorVersion) {
                                throw GradleException(
                                    "Class file ${entry.name} in $archiveFile was compiled to bytecode " +
                                        "major version $majorVersion, but the declared JVM target for " +
                                        "$artifactId requires major version $expectedMajorVersion. The " +
                                        "published bytecode level must match the declared JVM target " +
                                        "regardless of the JDK that ran the build.",
                                )
                            }
                            checkedClassFileCount++
                        }
                        entry = classesJar.nextEntry
                    }
                }
            }
            if (checkedClassFileCount == 0) {
                throw GradleException("No .class files found to verify bytecode level for $artifactId")
            }
        }

        val hostedAppleArtifactIds = hostedAppleArtifactIds.get()
        val coreAppleArtifactIds = coreAppleArtifactIds.get()
        if (hostedAppleArtifactIds.size != coreAppleArtifactIds.size) {
            throw GradleException("Hosted and core Apple artifact contracts must have the same size.")
        }
        hostedAppleArtifactIds.zip(coreAppleArtifactIds)
            .forEach { (hostedArtifactId, coreArtifactId) ->
                val hostedPomDirectory = repository.resolve("ru/vitrina/$hostedArtifactId")
                val hostedPom = hostedPomDirectory.walkTopDown()
                    .single { file -> file.extension == "pom" }
                    .readText()
                if (!hostedPom.contains("<artifactId>$coreArtifactId</artifactId>")) {
                    throw GradleException(
                        "Hosted Apple artifact $hostedArtifactId must depend on matching core " +
                            "artifact $coreArtifactId: $hostedPomDirectory",
                    )
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
                    if (pom.contains("<groupId>ru.rustore.sdk</groupId>")) {
                        throw GradleException(
                            "Core artifact POM must not depend on RuStore Pay: $pomFile",
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
        val androidProviderArtifactIds = setOf(
            googlePlayAndroidArtifactId.get(),
            ruStoreAndroidArtifactId.get(),
        )
        expectedArtifactIds.get()
            .filterNot { artifactId -> artifactId in androidProviderArtifactIds }
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

        val ruStorePomDirectory = repository.resolve(
            "ru/vitrina/${ruStoreAndroidArtifactId.get()}",
        )
        val ruStorePoms = ruStorePomDirectory.walkTopDown()
            .filter { file -> file.extension == "pom" }
            .toList()
        if (ruStorePoms.size != 1) {
            throw GradleException(
                "Expected one RuStore Android POM, found ${ruStorePoms.size}: $ruStorePomDirectory",
            )
        }
        val ruStorePom = ruStorePoms.single().readText()
        val bomArtifact = "<artifactId>bom</artifactId>"
        val bomIndex = ruStorePom.indexOf(bomArtifact)
        val bomBlockStart = ruStorePom.lastIndexOf("<dependency>", bomIndex)
        val bomBlockEnd = ruStorePom.indexOf("</dependency>", bomIndex)
        val bomDependency = if (bomBlockStart >= 0 && bomBlockEnd >= 0) {
            ruStorePom.substring(bomBlockStart, bomBlockEnd)
        } else {
            ""
        }
        val hasExactBomImport = bomDependency.contains("<groupId>ru.rustore.sdk</groupId>") &&
            bomDependency.contains(bomArtifact) &&
            bomDependency.contains("<version>${ruStoreBomVersion.get()}</version>") &&
            bomDependency.contains("<type>pom</type>") &&
            bomDependency.contains("<scope>import</scope>")
        if (!hasExactBomImport || bomIndex != ruStorePom.lastIndexOf(bomArtifact)) {
            throw GradleException(
                "RuStore Android POM must import BOM ${ruStoreBomVersion.get()}: ${ruStorePoms.single()}",
            )
        }
        val payArtifact = "<artifactId>pay</artifactId>"
        val payIndex = ruStorePom.indexOf(payArtifact)
        val payBlockStart = ruStorePom.lastIndexOf("<dependency>", payIndex)
        val payBlockEnd = ruStorePom.indexOf("</dependency>", payIndex)
        val payDependency = if (payBlockStart >= 0 && payBlockEnd >= 0) {
            ruStorePom.substring(payBlockStart, payBlockEnd)
        } else {
            ""
        }
        val hasRuntimePay = payDependency.contains("<groupId>ru.rustore.sdk</groupId>") &&
            payDependency.contains(payArtifact) &&
            payDependency.contains("<scope>runtime</scope>") &&
            !payDependency.contains("<scope>compile</scope>")
        if (!hasRuntimePay || payIndex != ruStorePom.lastIndexOf(payArtifact)) {
            throw GradleException(
                "RuStore Android POM must contain exactly one runtime Pay dependency: " +
                    ruStorePoms.single(),
            )
        }
        val ruStoreModules = ruStorePomDirectory.walkTopDown()
            .filter { file -> file.extension == "module" }
            .toList()
        if (ruStoreModules.size != 1) {
            throw GradleException(
                "Expected one RuStore Android Gradle module, found ${ruStoreModules.size}: " +
                    ruStorePomDirectory,
            )
        }
        val ruStoreModule = ruStoreModules.single().readText()
        val apiVariantName = "\"name\": \"androidApiElements-published\""
        val runtimeVariantName = "\"name\": \"androidRuntimeElements-published\""
        val sourcesVariantName = "\"name\": \"androidSourcesElements-published\""
        val apiVariantStart = ruStoreModule.indexOf(apiVariantName)
        val runtimeVariantStart = ruStoreModule.indexOf(runtimeVariantName)
        val sourcesVariantStart = ruStoreModule.indexOf(sourcesVariantName)
        if (apiVariantStart < 0 || runtimeVariantStart <= apiVariantStart ||
            sourcesVariantStart <= runtimeVariantStart
        ) {
            throw GradleException(
                "RuStore Android metadata must contain ordered API, runtime, and sources variants: " +
                    ruStoreModules.single(),
            )
        }
        val apiVariant = ruStoreModule.substring(apiVariantStart, runtimeVariantStart)
        val runtimeVariant = ruStoreModule.substring(runtimeVariantStart, sourcesVariantStart)
        if (apiVariant.contains("\"group\": \"ru.rustore.sdk\"")) {
            throw GradleException(
                "RuStore Pay must be absent from the Android API variant: ${ruStoreModules.single()}",
            )
        }
        val hasRuntimeModuleBom = runtimeVariant.contains("\"group\": \"ru.rustore.sdk\"") &&
            runtimeVariant.contains("\"module\": \"bom\"")
        val hasRuntimeModulePay = runtimeVariant.contains("\"group\": \"ru.rustore.sdk\"") &&
            runtimeVariant.contains("\"module\": \"pay\"")
        if (!hasRuntimeModuleBom || !hasRuntimeModulePay ||
            ruStoreResolvedPayVersion.get() != ruStorePayVersion.get()
        ) {
            throw GradleException(
                "RuStore Android runtime metadata must import the BOM and resolve Pay " +
                    "${ruStorePayVersion.get()}, found " +
                    "${ruStoreResolvedPayVersion.get()}: ${ruStoreModules.single()}",
            )
        }
        expectedArtifactIds.get()
            .filterNot { artifactId -> artifactId == ruStoreAndroidArtifactId.get() }
            .forEach { artifactId ->
                val artifactDirectory = repository.resolve("ru/vitrina/$artifactId")
                artifactDirectory.walkTopDown()
                    .filter { file -> file.extension == "pom" || file.extension == "module" }
                    .forEach { metadataFile ->
                        if (metadataFile.readText().contains("ru.rustore.sdk")) {
                            throw GradleException(
                                "Only the RuStore Android artifact may depend on RuStore Pay: " +
                                    metadataFile,
                            )
                        }
                    }
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
        val omittedPublication = providers.gradleProperty("vitrinaKitTopologyOmitPublication").orNull
        val additionalPublication = providers.gradleProperty(
            "vitrinaKitTopologyAddPublication",
        ).orNull
        val effectivePublicationIds = (
            publications.filterNot { artifactId -> artifactId == omittedPublication } +
                listOfNotNull(additionalPublication)
            ).toSet()
        val missingPublications = requiredArtifactIds - effectivePublicationIds
        val unexpectedPublications = effectivePublicationIds - requiredArtifactIds
        if (missingPublications.isNotEmpty() || unexpectedPublications.isNotEmpty()) {
            throw GradleException(
                "SDK publication topology mismatch. " +
                    "Missing: ${missingPublications.sorted().joinToString().ifEmpty { "none" }}; " +
                    "unexpected: ${unexpectedPublications.sorted().joinToString().ifEmpty { "none" }}.",
            )
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

tasks.register("verifyAndroidFlavorSample") {
    group = "verification"
    description = "Compiles and verifies the provider-isolated Android flavor sample."
    dependsOn(
        ":samples:android-flavors:app:compileGlobalDebugKotlin",
        ":samples:android-flavors:app:compileRuDebugKotlin",
    )

    doLast {
        val sampleRoot = rootProject.file("samples/android-flavors")
        val requiredFiles = listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/kotlin/ru/vitrina/sample/PurchaseFeature.kt",
            "app/src/global/kotlin/ru/vitrina/sample/PurchaseAdapterFactory.kt",
            "app/src/ru/kotlin/ru/vitrina/sample/PurchaseAdapterFactory.kt",
        )
        val missingFiles = requiredFiles.filterNot { relativePath ->
            sampleRoot.resolve(relativePath).isFile
        }
        if (missingFiles.isNotEmpty()) {
            throw GradleException(
                "Android flavor sample is incomplete: ${missingFiles.sorted().joinToString()}",
            )
        }

        val sampleProject = project(":samples:android-flavors:app")
        fun resolvedComponents(configurationName: String): Set<String> =
            sampleProject.configurations.getByName(configurationName)
                .incoming
                .resolutionResult
                .allComponents
                .map { component -> component.id.displayName }
                .toSet()

        val providerModules = setOf(
            "vitrinakit-googleplay",
            "vitrinakit-hosted",
            "vitrinakit-rustore",
        )
        fun packagedProviders(configurationName: String): Set<String> {
            val components = resolvedComponents(configurationName = configurationName)
            return providerModules.filterTo(mutableSetOf()) { provider ->
                components.any { component -> component.contains(provider) }
            }
        }

        val globalProviders = packagedProviders("globalDebugRuntimeClasspath")
        if (globalProviders != setOf("vitrinakit-googleplay")) {
            throw GradleException(
                "Global sample must package only Google Play, found: " +
                    globalProviders.sorted().joinToString(),
            )
        }
        val ruProviders = packagedProviders("ruDebugRuntimeClasspath")
        if (ruProviders != setOf("vitrinakit-hosted")) {
            throw GradleException(
                "RU sample must package only hosted checkout, found: " +
                    ruProviders.sorted().joinToString(),
            )
        }

        val featureSource = sampleRoot.resolve(
            "app/src/main/kotlin/ru/vitrina/sample/PurchaseFeature.kt",
        ).readText()
        val forbiddenFeatureImports = listOf(
            "ru.vitrina.sdk.googleplay",
            "ru.vitrina.sdk.hosted",
            "ru.vitrina.sdk.rustore",
        ).filter { providerPackage -> featureSource.contains("import $providerPackage") }
        if (forbiddenFeatureImports.isNotEmpty()) {
            throw GradleException(
                "Feature code must import core only: ${forbiddenFeatureImports.joinToString()}",
            )
        }

        val registrationMethods = listOf(
            ".withPurchaseAdapter(",
            ".withHostedCheckoutAdapter(",
            ".withHostedMigrationAdapter(",
        )
        listOf("global", "ru").forEach { flavor ->
            val factorySource = sampleRoot.resolve(
                "app/src/$flavor/kotlin/ru/vitrina/sample/PurchaseAdapterFactory.kt",
            ).readText()
            val registrationCount = registrationMethods.sumOf { method ->
                factorySource.windowed(method.length).count { candidate -> candidate == method }
            }
            if (registrationCount != 1) {
                throw GradleException(
                    "$flavor sample must register exactly one purchase adapter, found $registrationCount.",
                )
            }
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs all SDK unit test targets."
    dependsOn(
        ":vitrinakit-core:jvmTest",
        ":vitrinakit-core:testAndroidHostTest",
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
        "verifyAndroidFlavorSample",
        "verifyModuleTopology",
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
    hostedAppleArtifactIds.set(
        listOf(
            "vitrinakit-hosted-iosarm64$vitrinaKitPublicationSuffix",
            "vitrinakit-hosted-iossimulatorarm64$vitrinaKitPublicationSuffix",
        ),
    )
    coreAppleArtifactIds.set(
        listOf(
            "vitrinakit-kmp-sdk-iosarm64$vitrinaKitPublicationSuffix",
            "vitrinakit-kmp-sdk-iossimulatorarm64$vitrinaKitPublicationSuffix",
        ),
    )
    jvmBytecodeArtifactIds.set(requiredJvmBytecodeArtifactIds.sorted())
    expectedClassFileMajorVersion.set(vitrinaKitJvmTargetClassFileMajorVersion)
    googlePlayAndroidArtifactId.set("vitrinakit-googleplay-android$vitrinaKitPublicationSuffix")
    googlePlayBillingVersion.set(rootProject.extra["googlePlayBillingVersion"] as String)
    kotlinxCoroutinesVersion.set(rootProject.extra["kotlinxCoroutinesVersion"] as String)
    ruStoreAndroidArtifactId.set("vitrinakit-rustore-android$vitrinaKitPublicationSuffix")
    ruStoreBomVersion.set(rootProject.extra["ruStoreBomVersion"] as String)
    ruStorePayVersion.set(rootProject.extra["ruStorePayVersion"] as String)
    ruStoreResolvedPayVersion.set(
        providers.provider {
            // Resolves the dependency graph rather than artifacts: vitrinakit-core now publishes
            // its own Android variant, and that variant's secondary artifacts (lint, art-profile,
            // consumer-proguard-rules, ...) make plain artifact resolution of this classpath
            // ambiguous. The component graph has no such ambiguity and is all this needs.
            project(":vitrinakit-rustore")
                .configurations
                .getByName("androidRuntimeClasspath")
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                .single { id -> id.group == "ru.rustore.sdk" && id.module == "pay" }
                .version
        },
    )
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
            val expectedHostedAppleArtifactIds = setOf(
                "vitrinakit-hosted-iosarm64$vitrinaKitPublicationSuffix",
                "vitrinakit-hosted-iossimulatorarm64$vitrinaKitPublicationSuffix",
            )
            if (releaseTask.hostedAppleArtifactIds.get().toSet() != expectedHostedAppleArtifactIds) {
                add("Release verification must inspect every hosted Apple target POM.")
            }
            val expectedCoreAppleArtifactIds = setOf(
                "vitrinakit-kmp-sdk-iosarm64$vitrinaKitPublicationSuffix",
                "vitrinakit-kmp-sdk-iossimulatorarm64$vitrinaKitPublicationSuffix",
            )
            if (releaseTask.coreAppleArtifactIds.get().toSet() != expectedCoreAppleArtifactIds) {
                add("Release verification must match hosted and core Apple target dependencies.")
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
    description = "Builds the core and hosted VitrinaKit iOS XCFrameworks."
    dependsOn(
        ":vitrinakit-core:assembleVitrinaKitXCFramework",
        ":vitrinakit-hosted:assembleVitrinaKitHostedXCFramework",
    )

    doLast {
        val expectedFrameworks = listOf(
            project(":vitrinakit-core").layout.buildDirectory.dir(
                "XCFrameworks/release/VitrinaKit.xcframework",
            ).get().asFile,
            project(":vitrinakit-hosted").layout.buildDirectory.dir(
                "XCFrameworks/release/VitrinaKitHosted.xcframework",
            ).get().asFile,
        )
        val missingFrameworks = expectedFrameworks.filterNot { framework -> framework.isDirectory }
        if (missingFrameworks.isNotEmpty()) {
            throw GradleException(
                "Missing release XCFrameworks: ${missingFrameworks.joinToString()}",
            )
        }

        val hostedFramework = expectedFrameworks.last()
        val hostedHeaders = listOf(
            "ios-arm64/VitrinaKitHosted.framework/Headers/VitrinaKitHosted.h",
            "ios-arm64-simulator/VitrinaKitHosted.framework/Headers/VitrinaKitHosted.h",
        ).map { relativePath -> hostedFramework.resolve(relativePath) }
        hostedHeaders.forEach { header ->
            if (!header.isFile) {
                throw GradleException("Missing hosted XCFramework public header: $header")
            }
            val headerText = header.readText()
            val requiredSwiftNames = listOf(
                "swift_name(\"HostedCheckoutAdapter\")",
                "swift_name(\"VitrinaKit\")",
            )
            val missingSwiftNames = requiredSwiftNames.filterNot { swiftName ->
                headerText.contains(swiftName)
            }
            if (missingSwiftNames.isNotEmpty()) {
                throw GradleException(
                    "Hosted XCFramework must export hosted and core APIs; missing " +
                        "${missingSwiftNames.joinToString()} in $header",
                )
            }
        }
    }
}
