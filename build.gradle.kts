import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.excludeCoroutines
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm) // Kotlin support
    alias(libs.plugins.kotlin.serialization) // Kotlin serialization
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Carry the plugin version (gradle.properties pluginVersion) into the runtime classpath
// as a generated resource. A manifest attribute would not survive into the composed
// plugin jar, whose manifest the IntelliJ Platform Gradle Plugin writes itself.
val generateVersionResource by tasks.registering {
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val outputDir = layout.buildDirectory.dir("generated/version-resource")
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("aiproxyoauth-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${pluginVersion.get()}\n")
    }
}

sourceSets {
    main {
        resources.srcDir(generateVersionResource)
    }
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ 2025.3 bundles Kotlin 2.2.x; do not emit calls requiring a newer stdlib.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    implementation(libs.kotlinx.datetime) {
        excludeKotlinStdlib()
        excludeCoroutines()
    }
    implementation(libs.snakeyaml.engine)
    implementation(libs.tomlj)
    implementation(libs.jsoup)
    implementation(libs.javalin) {
        excludeKotlinStdlib()
        excludeCoroutines()
    }
    implementation(libs.jackson.databind)
    implementation(libs.picocli)
    implementation(libs.kotlinx.serialization.json) {
        excludeKotlinStdlib()
        excludeCoroutines()
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

    register<JavaExec>("runStandaloneOpenAiProxy") {
        group = "application"
        description = "Runs the OpenAI-compatible proxy without launching an IntelliJ IDE."
        mainClass.set("de.moritzf.quota.openai.proxy.StandaloneOpenAiProxyKt")
        classpath = sourceSets.main.get().runtimeClasspath
    }

    named("buildSearchableOptions") {
        enabled = providers.gradleProperty("skipSearchableOptions")
            .map { it.toBoolean().not() }
            .getOrElse(true)
    }
}
