import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    alias(libs.plugins.kotlin.jvm) // Kotlin support
    alias(libs.plugins.kotlin.serialization) // Kotlin serialization
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    alias(libs.plugins.shadow) // Shadow plugin for shading Kotlin/Ktor dependencies
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Configuration for dependencies that will be shaded (relocated) into the plugin jar
val shaded: Configuration by configurations.creating
val shadedLibraries = listOf(
    libs.ktor.client.cio,
    libs.ktor.server.cio,
    libs.kotlinx.datetime,
    libs.kotlinx.serialization.json,
)

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    // Kotlin/Ktor dependencies are shaded into the plugin jar to avoid classloader conflicts
    // with IntelliJ platform-provided Kotlin libraries.
    shadedLibraries.forEach { dependencyNotation ->
        shaded(dependencyNotation)
        compileOnly(dependencyNotation)
        testImplementation(dependencyNotation)
    }

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(project.changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Gradle Kover Plugin - read more: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#configuration-details
kover {
    reports {
        filters {
            excludes {
                classes("*.Test*", "*.test*", "*.Tests")
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    named("buildSearchableOptions") {
        enabled = providers.gradleProperty("skipSearchableOptions")
            .map { it.toBoolean().not() }
            .getOrElse(true)
    }

    // Shadow jar that takes the composedJar (instrumented plugin classes) and merges
    // relocated Kotlin/Ktor dependencies into it, avoiding classloader conflicts.
    val shadedJar by registering(ShadowJar::class) {
        group = "shadow"
        description = "Creates plugin jar with shaded Kotlin/Ktor dependencies"

        dependsOn(named("composedJar"))
        val composedJarTask = named<AbstractArchiveTask>("composedJar")
        from(composedJarTask.map { zipTree(it.archiveFile) })
        configurations = listOf(shaded)

        relocate("io.ktor", "de.moritzf.quota.shadow.io.ktor")
        relocate("kotlinx.serialization", "de.moritzf.quota.shadow.kotlinx.serialization")
        relocate("kotlinx.datetime", "de.moritzf.quota.shadow.kotlinx.datetime")
        relocate("kotlinx.coroutines", "de.moritzf.quota.shadow.kotlinx.coroutines")
        relocate("kotlinx.io", "de.moritzf.quota.shadow.kotlinx.io")
        relocate("kotlinx.atomicfu", "de.moritzf.quota.shadow.kotlinx.atomicfu")
        // Relocate kotlin.time so ktor uses its own Duration class instead of IntelliJ's
        // (internal method signatures differ between kotlin-stdlib versions)
        relocate("kotlin.time", "de.moritzf.quota.shadow.kotlin.time")

        dependencies {
            exclude(dependency("org.jetbrains.kotlin:kotlin-reflect"))
        }

        mergeServiceFiles()
        archiveClassifier.set("shaded")
    }

    // Use the shaded plugin jar for main sandbox/distribution builds only.
    named<PrepareSandboxTask>("prepareSandbox") {
        dependsOn(shadedJar)
        pluginJar.set(shadedJar.flatMap { it.archiveFile })
    }

    // Ensure shaded jar is available for plugin distribution.
    named("buildPlugin") {
        dependsOn(shadedJar)
    }
}
