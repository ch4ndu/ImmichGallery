import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.util.Properties
import javax.inject.Inject

@CacheableTask
abstract class PrepareDesktopVlcBundleTask : DefaultTask() {
    @get:Input
    abstract val configuredBundlePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleContents: ConfigurableFileCollection

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Input
    abstract val targetId: Property<String>

    @get:OutputDirectory
    abstract val outputRoot: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        val outputDirectory = outputRoot.get().asFile
        fileSystemOperations.delete { delete(outputDirectory) }

        val configuredPath = configuredBundlePath.get().trim()
        if (configuredPath.isEmpty()) {
            outputDirectory.resolve("common").mkdirs()
            return
        }

        val configuredFile = File(configuredPath)
        val configuredRoot = if (configuredFile.isAbsolute) {
            configuredFile
        } else {
            projectRoot.get().asFile.resolve(configuredPath)
        }
        if (!configuredRoot.isDirectory) {
            throw GradleException(
                "The configured VLC bundle root does not exist or is not a directory. " +
                    "Set -PimmichGallery.vlcBundleDir or IMMICH_GALLERY_VLC_BUNDLE_DIR to a valid bundle root."
            )
        }

        val layout = desktopVlcTargetLayout(targetId.get())
        val targetDirectory = configuredRoot.resolve(layout.targetId)
        validateDesktopVlcBundle(targetDirectory, layout)
        fileSystemOperations.sync {
            from(targetDirectory)
            into(outputDirectory.resolve("common/vlc"))
        }
    }

    private fun desktopVlcTargetLayout(targetId: String): DesktopVlcTargetLayout =
        when {
            targetId.startsWith("windows-") -> DesktopVlcTargetLayout(
                targetId = targetId,
                nativeDirectory = ".",
                libVlcPattern = Regex("libvlc\\.dll", RegexOption.IGNORE_CASE),
                libVlcCorePattern = Regex("libvlccore\\.dll", RegexOption.IGNORE_CASE),
                pluginBinaryPattern = Regex(".+\\.dll", RegexOption.IGNORE_CASE),
                pluginDirectories = listOf("plugins", "vlc/plugins"),
            )
            targetId.startsWith("macos-") -> DesktopVlcTargetLayout(
                targetId = targetId,
                nativeDirectory = "lib",
                libVlcPattern = Regex("libvlc\\.dylib"),
                libVlcCorePattern = Regex("libvlccore\\.dylib"),
                pluginBinaryPattern = Regex(".+\\.dylib"),
                pluginDirectories = listOf("plugins"),
            )
            targetId.startsWith("linux-") -> DesktopVlcTargetLayout(
                targetId = targetId,
                nativeDirectory = ".",
                libVlcPattern = Regex("libvlc\\.so(?:\\.\\d+)*"),
                libVlcCorePattern = Regex("libvlccore\\.so(?:\\.\\d+)*"),
                pluginBinaryPattern = Regex(".+\\.so(?:\\.\\d+)*"),
                pluginDirectories = listOf("plugins", "vlc/plugins"),
            )
            else -> throw GradleException("Unsupported VLC bundle target: $targetId")
        }

    private fun validateDesktopVlcBundle(
        targetDirectory: java.io.File,
        layout: DesktopVlcTargetLayout,
    ) {
        if (!targetDirectory.isDirectory) {
            throw GradleException(
                "Missing VLC bundle target '${layout.targetId}' under the configured bundle root. " +
                    "Set -PimmichGallery.vlcBundleDir or IMMICH_GALLERY_VLC_BUNDLE_DIR to a root containing that directory."
            )
        }
        val nativeDirectory = targetDirectory.resolve(layout.nativeDirectory)
        val nativeFiles = nativeDirectory.listFiles()?.filter(File::isFile).orEmpty()
        if (nativeFiles.none { layout.libVlcPattern.matches(it.name) }) {
            throw GradleException("VLC bundle target '${layout.targetId}' is missing libVLC in its native directory")
        }
        if (nativeFiles.none { layout.libVlcCorePattern.matches(it.name) }) {
            throw GradleException("VLC bundle target '${layout.targetId}' is missing libvlccore in its native directory")
        }
        val firstExistingPluginDirectory = layout.pluginDirectories
            .map(targetDirectory::resolve)
            .firstOrNull(File::exists)
        val hasPlugins = firstExistingPluginDirectory?.isDirectory == true &&
            firstExistingPluginDirectory.walkTopDown().any { candidate ->
                candidate.isFile && layout.pluginBinaryPattern.matches(candidate.name)
            }
        if (!hasPlugins) {
            throw GradleException(
                "VLC bundle target '${layout.targetId}' is missing an OS-appropriate plugin binary " +
                    "in its first existing plugins directory"
            )
        }
    }

    private data class DesktopVlcTargetLayout(
        val targetId: String,
        val nativeDirectory: String,
        val libVlcPattern: Regex,
        val libVlcCorePattern: Regex,
        val pluginBinaryPattern: Regex,
        val pluginDirectories: List<String>,
    )
}

@org.gradle.work.DisableCachingByDefault(because = "Validation-only task with no outputs")
abstract class RequireDesktopVlcBundleTask : DefaultTask() {
    @get:Input
    abstract val configuredBundlePath: Property<String>

    @get:Input
    abstract val targetId: Property<String>

    @TaskAction
    fun requireBundle() {
        if (configuredBundlePath.get().isEmpty()) {
            throw GradleException(
                "Native Desktop distributions require a builder-supplied VLC bundle. " +
                    "Set -PimmichGallery.vlcBundleDir or IMMICH_GALLERY_VLC_BUNDLE_DIR."
            )
        }
    }
}

private fun currentDesktopVlcTargetId(): String {
    val os = System.getProperty("os.name").orEmpty()
    val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> throw GradleException("VLC bundling does not support this Desktop architecture")
    }
    val osId = when {
        os.equals("Mac OS X", ignoreCase = true) -> "macos"
        os.startsWith("Win", ignoreCase = true) -> "windows"
        os.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> throw GradleException("VLC bundling does not support this Desktop operating system")
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
            implementation(libs.vlcj)
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

val desktopVlcBundlePath = providers.gradleProperty("immichGallery.vlcBundleDir")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .orElse(
        providers.environmentVariable("IMMICH_GALLERY_VLC_BUNDLE_DIR")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    )
    .orElse("")
val desktopVlcTargetId = providers.provider(::currentDesktopVlcTargetId)
val prepareDesktopVlcBundle = tasks.register<PrepareDesktopVlcBundleTask>("prepareDesktopVlcBundle") {
    description = "Validates and stages the builder-supplied VLC bundle for the current Desktop target."
    group = "distribution"
    configuredBundlePath.set(desktopVlcBundlePath)
    val configuredPath = desktopVlcBundlePath.get()
    if (configuredPath.isNotEmpty()) {
        bundleContents.from(rootProject.file(configuredPath))
    }
    projectRoot.set(rootProject.layout.projectDirectory)
    targetId.set(desktopVlcTargetId)
    outputRoot.set(layout.buildDirectory.dir("generated/desktopVlc/appResources"))
}
val requireDesktopVlcBundle = tasks.register<RequireDesktopVlcBundleTask>("requireDesktopVlcBundle") {
    description = "Requires a complete builder-supplied VLC bundle before creating a native Desktop distribution."
    group = "distribution"
    dependsOn(prepareDesktopVlcBundle)
    configuredBundlePath.set(desktopVlcBundlePath)
    targetId.set(desktopVlcTargetId)
}

tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(requireDesktopVlcBundle)
    inputs.dir(prepareDesktopVlcBundle.flatMap { it.outputRoot })
        .withPropertyName("desktopVlcAppResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run" || name == "runRelease") {
        dependsOn(prepareDesktopVlcBundle)
        inputs.dir(prepareDesktopVlcBundle.flatMap { it.outputRoot })
            .withPropertyName("desktopVlcAppResources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

compose.desktop {
    application {
        mainClass = "com.udnahc.immichgallery.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(prepareDesktopVlcBundle.flatMap { it.outputRoot })
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.udnahc.immichgallery"
            packageVersion = "1.2.0"
        }
    }
}

// Compose registers and configures JPackage tasks from its own afterEvaluate
// action. Add the packaged-mode marker afterward so its launcherJvmArgs.set(...)
// cannot replace this value.
afterEvaluate {
    tasks.withType<AbstractJPackageTask>().configureEach {
        launcherJvmArgs.add("-DimmichGallery.vlcBundleRequired=true")
    }
}
