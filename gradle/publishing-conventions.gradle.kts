import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

enum class VitrinaKitPublication(
    val propertyValue: String,
    val artifactSuffix: String,
    val apiBaseUrl: String,
    val environment: String,
) {
    Production(
        propertyValue = "production",
        artifactSuffix = "",
        apiBaseUrl = "https://api.vitrinakit.ru",
        environment = "PRODUCTION",
    ),
    Development(
        propertyValue = "development",
        artifactSuffix = "-dev",
        apiBaseUrl = "https://api.dev.vitrinakit.ru",
        environment = "SANDBOX",
    ),
}

val vitrinaKitPublicationName = providers.gradleProperty("vitrinaKitPublication")
    .orElse(providers.environmentVariable("VITRINAKIT_PUBLICATION"))
    .orElse(VitrinaKitPublication.Production.propertyValue)
    .get()
    .lowercase()
val vitrinaKitPublication = VitrinaKitPublication.entries.firstOrNull { publication ->
    publication.propertyValue == vitrinaKitPublicationName
} ?: error(
    "Unsupported VitrinaKit publication '$vitrinaKitPublicationName'. " +
        "Use 'production' or 'development'.",
)
val vitrinaKitArtifactId = findProperty("vitrinaKitArtifactId")?.toString() ?: project.name

abstract class GenerateVitrinaKitPublicationConfigTask : DefaultTask() {
    @get:Input
    abstract val apiBaseUrl: Property<String>

    @get:Input
    abstract val environment: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputFile = outputDirectory
            .file("ru/vitrina/sdk/VitrinaKitPublicationConfig.kt")
            .get()
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package ru.vitrina.sdk

            import ru.vitrina.sdk.model.VitrinaEnvironment

            internal const val VitrinaKitApiBaseUrl = "${apiBaseUrl.get()}"
            internal val VitrinaKitApiEnvironment: VitrinaEnvironment = VitrinaEnvironment.${environment.get()}
            """.trimIndent() + "\n",
        )
    }
}

val generateVitrinaKitPublicationConfig = tasks.register(
    "generateVitrinaKitPublicationConfig",
    GenerateVitrinaKitPublicationConfigTask::class,
) {
    apiBaseUrl.set(vitrinaKitPublication.apiBaseUrl)
    environment.set(vitrinaKitPublication.environment)
    outputDirectory.set(
        layout.buildDirectory.dir(
            "generated/vitrinakit-publication-config/${vitrinaKitPublication.propertyValue}/commonMain/kotlin",
        ),
    )
}

extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "Build"
            url = uri(rootProject.layout.buildDirectory.dir("repository"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ni032mas/vitrinakit-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
    publications.withType(MavenPublication::class.java).configureEach {
        artifactId = when (name) {
            "kotlinMultiplatform" -> vitrinaKitArtifactId
            else -> "$vitrinaKitArtifactId-${name.lowercase()}"
        } + vitrinaKitPublication.artifactSuffix
        pom {
            name = "VitrinaKit KMP SDK"
            description = "Kotlin Multiplatform SDK for VitrinaKit mobile subscription integrations"
            url = "https://github.com/ni032mas/vitrinakit-kmp"
            licenses {
                license {
                    name = "Proprietary"
                    url = "https://github.com/ni032mas/vitrinakit-kmp"
                }
            }
            developers {
                developer {
                    id = "ni032mas"
                    name = "VitrinaKit"
                    url = "https://github.com/ni032mas"
                }
            }
            scm {
                connection = "scm:git:git://github.com/ni032mas/vitrinakit-kmp.git"
                developerConnection = "scm:git:ssh://git@github.com:ni032mas/vitrinakit-kmp.git"
                url = "https://github.com/ni032mas/vitrinakit-kmp"
            }
        }
    }
}

afterEvaluate {
    extensions.getByType(PublishingExtension::class.java)
        .publications
        .withType(MavenPublication::class.java)
        .forEach { publication ->
            publication.artifactId = when (publication.name) {
                "kotlinMultiplatform" -> vitrinaKitArtifactId
                else -> "$vitrinaKitArtifactId-${publication.name.lowercase()}"
            } + vitrinaKitPublication.artifactSuffix
        }
}

tasks.matching { task -> task.name.endsWith("ToGitHubPackagesRepository") }.configureEach {
    dependsOn(rootProject.tasks.named("verifySdk"))
}

tasks.matching { task -> task.name.endsWith("ToBuildRepository") }.configureEach {
    dependsOn(rootProject.tasks.named("prepareReleaseArtifactRepository"))
}
