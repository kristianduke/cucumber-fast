import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

/** Stand-in host, so a forgotten `-PpluginRepositoryUrl` produces an obviously wrong URL, not a plausible one. */
val PLACEHOLDER_REPOSITORY_URL = "https://REPLACE-WITH-YOUR-HOST.invalid/idea-plugins"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            type = libs.versions.platformType.get().let(IntelliJPlatformType::fromCode),
            version = libs.versions.platformVersion.get(),
        )

        // Feature-file language support (lexer, parser, PSI) is reused from JetBrains' Gherkin
        // plugin; this plugin supplies the step indexing and resolution on top of it.
        plugin("gherkin", libs.versions.gherkin.get())
        bundledPlugin("com.intellij.java")

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
    // Required at runtime by the platform test framework, but not exposed transitively.
    testImplementation(libs.opentest4j)
}

kotlin {
    jvmToolchain(libs.versions.javaVersion.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.javaVersion.get())
        // Stay within the language/API level of the Kotlin runtime bundled with the target IDE.
        // Language features may be current; the API surface stays at what the IDE's bundled
        // Kotlin runtime provides.
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

intellijPlatform {
    // Nothing in this plugin is written in Java or uses GUI forms, so the ant-based
    // NotNull-instrumentation pass has nothing to do.
    instrumentCode = false

    pluginConfiguration {
        id = "dev.kristian.cucumberfast"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "251"
            // Open-ended: the plugin uses no experimental APIs, so don't pin an upper bound.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}

/**
 * Generates the `updatePlugins.xml` that turns a plain HTTPS directory into an IntelliJ plugin
 * repository. Upload it next to the plugin zip; developers add its URL once under
 * Settings | Plugins | ⚙ | Manage Plugin Repositories and get update notifications from then on.
 *
 * The download URL is `pluginRepositoryUrl` + the zip's file name. Hosts that do not serve the
 * archive from a predictable path — a GitHub release asset, a signed S3 link — can override the
 * whole thing with `pluginDownloadUrl`.
 */
val generateUpdatePlugins by tasks.registering {
    // Everything the execution phase needs is captured here, as values: the configuration cache
    // cannot serialize references back into this script.
    val placeholderUrl = PLACEHOLDER_REPOSITORY_URL
    val buildPluginTask = tasks.named<Zip>("buildPlugin")
    val archiveFileName = buildPluginTask.flatMap { it.archiveFileName }
    val repositoryUrl = providers.gradleProperty("pluginRepositoryUrl").orElse(placeholderUrl)
    val downloadUrlOverride = providers.gradleProperty("pluginDownloadUrl")
    val pluginId = providers.gradleProperty("pluginGroup").map { "$it.cucumberfast" }
    val pluginName = providers.gradleProperty("pluginName")
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")

    inputs.property("downloadUrl", downloadUrlOverride.orElse(repositoryUrl))
    inputs.property("version", pluginVersion)
    outputs.file(output)

    doLast {
        val downloadUrl = downloadUrlOverride.orNull
            ?: repositoryUrl.get().trimEnd('/') + '/' + archiveFileName.get()
        output.get().asFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugins>
              <plugin id="${pluginId.get()}" url="$downloadUrl" version="${pluginVersion.get()}">
                <name>${pluginName.get()}</name>
                <vendor>Kristian</vendor>
                <idea-version since-build="251"/>
                <description><![CDATA[Fast navigation between Gherkin feature files and their Java step definitions.]]></description>
              </plugin>
            </plugins>

            """.trimIndent(),
        )
        if (downloadUrlOverride.orNull == null && repositoryUrl.get() == placeholderUrl) {
            logger.warn(
                "updatePlugins.xml was written with a placeholder host. Rebuild with " +
                    "-PpluginRepositoryUrl=https://your-host/path before uploading it.",
            )
        }
    }
}

tasks.named("buildPlugin") {
    finalizedBy(generateUpdatePlugins)
}
