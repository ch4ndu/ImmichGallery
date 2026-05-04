import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

abstract class GenerateLocalLoginDefaultsTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val props = Properties()
        val localPropertiesFile = localProperties.asFile.get()
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use(props::load)
        }
        val serverUrl = props.getProperty("immichGallery.loginServerUrl", "")
        val apiKey = props.getProperty("immichGallery.loginApiKey", "")
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

val generateLocalLoginDefaults by tasks.registering(GenerateLocalLoginDefaultsTask::class) {
    localProperties.set(rootProject.layout.projectDirectory.file("local.properties"))
    outputDir.set(layout.buildDirectory.dir("generated/source/localLoginDefaults/commonMain/kotlin"))
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
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

            // Media Player — exclude unused webview/kcef chain (pulls jogamp deps not on Maven Central)
            implementation(libs.compose.media.player.get().toString()) {
                exclude(group = "io.github.kevinnzou")
                exclude(group = "dev.datlag")
                exclude(group = "org.jogamp.gluegen")
                exclude(group = "org.jogamp.jogl")
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
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.java)
        }
    }
}

android {
    namespace = "com.udnahc.immichgallery"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.udnahc.immichgallery"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "com.udnahc.immichgallery.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.udnahc.immichgallery"
            packageVersion = "1.0.0"
        }
    }
}
