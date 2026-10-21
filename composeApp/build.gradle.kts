import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.provider.Property
import java.util.Properties

// MediaMP publishes desktop mpv runtimes for these hosts only; Linux arm64 has none.
private fun currentDesktopMpvRuntimeId(): String? {
    val os = System.getProperty("os.name").orEmpty()
    val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> return null
    }
    val osId = when {
        os.equals("Mac OS X", ignoreCase = true) -> "macos"
        os.startsWith("Win", ignoreCase = true) -> "windows"
        os.startsWith("Linux", ignoreCase = true) && arch == "x64" -> "linux"
        else -> return null
    }
    return "$osId-$arch"
}

abstract class GenerateLocalLoginDefaultsTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val loginServerUrl: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val loginApiKey: Property<String>

    @TaskAction
    fun generate() {
        val props = Properties()
        val localPropertiesFile = localProperties.asFile.get()
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use(props::load)
        }
        val serverUrl = props.getProperty("immichGallery.loginServerUrl")
            ?: loginServerUrl.get()
        val apiKey = props.getProperty("immichGallery.loginApiKey")
            ?: loginApiKey.get()
        val packageDir = outputDir.file("com/udnahc/immichgallery").get().asFile
        packageDir.mkdirs()
        packageDir.resolve("LocalLoginDefaults.kt").writeText(
            """
            package com.udnahc.immichgallery

            object LocalLoginDefaults {
                const val SERVER_URL: String = ${serverUrl.kotlinLiteral()}
                const val API_KEY: String = ${apiKey.kotlinLiteral()}
            }
            """.trimIndent()
        )
    }

    private fun String.kotlinLiteral(): String =
        buildString {
            append('"')
            this@kotlinLiteral.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
}

val generateLocalLoginDefaults = tasks.register<GenerateLocalLoginDefaultsTask>("generateLocalLoginDefaults") {
    localProperties.set(rootProject.layout.projectDirectory.file("local.properties"))
    loginServerUrl.set(
        providers.gradleProperty("immichGallery.loginServerUrl")
            .orElse(providers.environmentVariable("IMMICH_GALLERY_LOGIN_SERVER_URL"))
            .orElse("")
    )
    loginApiKey.set(
        providers.gradleProperty("immichGallery.loginApiKey")
            .orElse(providers.environmentVariable("IMMICH_GALLERY_LOGIN_API_KEY"))
            .orElse("")
    )
    outputDir.set(layout.buildDirectory.dir("generated/source/localLoginDefaults/commonMain/kotlin"))
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Only this host's packaged mpv runtime ships; there is no all-platform aggregator or system fallback.
val desktopMpvRuntimeId = currentDesktopMpvRuntimeId()

kotlin {
    android {
        namespace = "com.udnahc.immichgallery"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        androidResources {
            enable = true
        }

        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLocalLoginDefaults)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coil.network.okhttp)

            // MediaMP ExoPlayer backend; media3 is declared for its ExoPlayer/PlayerView API types.
            implementation(libs.mediamp.exoplayer.get().toString()) {
                exclude(group = "org.jetbrains.compose.material")
            }
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.room.testing)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            // Serialization
            implementation(libs.kotlinx.serialization.json)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            // Material Kolor
            implementation(libs.material.kolor)

            // ZoomImage
            implementation(libs.zoomimage.compose.coil3)

            // Media Player API — Android/JVM backends are platform dependencies; iOS keeps AVPlayer.
            // MediaMP's unused extended Material icons are excluded on every MediaMP edge.
            implementation(libs.mediamp.api.get().toString()) {
                exclude(group = "org.jetbrains.compose.material")
            }

            // Navigation
            implementation(libs.navigation.compose)

            // DateTime
            implementation(libs.kotlinx.datetime)

            // Room
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)

            // Settings
            implementation(libs.multiplatform.settings)

            // Logging
            implementation(libs.kmlogging)

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTestJUnit4)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.jna.jpms)
            implementation(libs.jna.platform.jpms)
            // MediaMP's native loader satisfies JNA with the app's JPMS pair above; excluding its
            // plain JNA on these edges avoids duplicate com.sun.jna classes.
            implementation(libs.mediamp.mpv.get().toString()) {
                exclude(group = "net.java.dev.jna")
                exclude(group = "org.jetbrains.compose.material")
            }
            desktopMpvRuntimeId?.let { runtimeId ->
                runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-$runtimeId:${libs.versions.mediamp.get()}") {
                    exclude(group = "net.java.dev.jna")
                    exclude(group = "org.jetbrains.compose.material")
                }
            }
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.java)
        }
    }
}

dependencies {
    add("androidRuntimeClasspath", libs.compose.uiTooling)
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Native distributions must contain this host's mpv runtime; fail instead of packaging without video.
tasks.withType<AbstractJPackageTask>().configureEach {
    val runtimeId = desktopMpvRuntimeId
    doFirst {
        if (runtimeId == null) {
            throw GradleException(
                "MediaMP publishes no desktop mpv runtime for this host. Package on macOS arm64/x64, " +
                    "Windows arm64/x64 or Linux x64."
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.udnahc.immichgallery.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.udnahc.immichgallery"
            packageVersion = "1.2.0"
        }
    }
}
