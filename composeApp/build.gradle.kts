import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${props.getProperty("SUPABASE_URL", "")}" 
                |    const val ANON_KEY = "${props.getProperty("SUPABASE_ANON_KEY", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

abstract class RenameReleaseArtifactTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val artifactExtension: Property<String>

    @get:OutputDirectory
    abstract val artifactDirectory: DirectoryProperty

    @TaskAction
    fun renameArtifact() {
        val artifactDir = artifactDirectory.get().asFile
        val artifactExtension = artifactExtension.get()
        val targetFile = artifactDir.resolve("Nuvio-${versionName.get()}.$artifactExtension")
        val sourceFile = artifactDir.listFiles()
            ?.filter { it.extension.equals(artifactExtension, ignoreCase = true) && it.name.startsWith("Nuvio-") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("No .$artifactExtension output found in ${artifactDir.path}")

        if (sourceFile.absolutePath != targetFile.absolutePath) {
            targetFile.delete()
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

fun encodeResponseFilePath(file: File): String {
    return "\"${file.absolutePath.replace('\\', '/')}\""
}

fun rebuildJpackageInputDir(
    rebuiltInputDir: File,
    proguardDir: File,
    skikoDir: File,
    processedDesktopResourcesDir: File,
) {
    require(proguardDir.exists()) { "ProGuard staging directory ${proguardDir.path} does not exist" }
    require(skikoDir.exists()) { "Skiko staging directory ${skikoDir.path} does not exist" }
    require(processedDesktopResourcesDir.exists()) {
        "Processed desktop resources directory ${processedDesktopResourcesDir.path} does not exist"
    }

    rebuiltInputDir.deleteRecursively()
    rebuiltInputDir.mkdirs()

    proguardDir.listFiles()
        ?.filter { it.isFile }
        ?.forEach { sourceFile ->
            sourceFile.copyTo(rebuiltInputDir.resolve(sourceFile.name), overwrite = true)
        }

    skikoDir.listFiles()
        ?.filter { it.isFile }
        ?.forEach { sourceFile ->
            sourceFile.copyTo(rebuiltInputDir.resolve(sourceFile.name), overwrite = true)
        }

    val resourcesTargetDir = rebuiltInputDir.resolve("resources").apply {
        deleteRecursively()
        mkdirs()
    }

    processedDesktopResourcesDir.listFiles()?.forEach { sourceFile ->
        if (sourceFile.isDirectory) {
            sourceFile.copyRecursively(resourcesTargetDir.resolve(sourceFile.name), overwrite = true)
        } else {
            sourceFile.copyTo(resourcesTargetDir.resolve(sourceFile.name), overwrite = true)
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val isWindowsHost = System.getProperty("os.name").contains("Windows", ignoreCase = true)
if (isWindowsHost) {
    System.setProperty("compose.preserve.working.dir", "true")
}
val joglVersion = libs.versions.jogl.get()
val windowsJoglCore = configurations.detachedConfiguration(
    dependencies.create("org.jogamp.jogl:jogl-all:$joglVersion")
)
val stripWindowsJoglJar = tasks.register<Zip>("stripWindowsJoglJar") {
    destinationDirectory.set(layout.buildDirectory.dir("stripped-jars"))
    archiveFileName.set("jogl-all-$joglVersion-windows.jar")
    archiveExtension.set("jar")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({ zipTree(windowsJoglCore.singleFile) })
    exclude(
        "com/jogamp/nativewindow/javafx/**",
        "com/jogamp/nativewindow/swt/**",
        "com/jogamp/newt/javafx/**",
        "com/jogamp/newt/swt/**",
        "com/jogamp/opengl/swt/**",
        "jogamp/newt/javafx/**",
        "jogamp/newt/swt/**",
    )
}
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                if (isWindowsHost) {
                    implementation(files(stripWindowsJoglJar.flatMap { it.archiveFile }))
                    implementation("org.jogamp.gluegen:gluegen-rt:$joglVersion")
                    implementation("org.jogamp.jogl:jogl-all:$joglVersion:natives-windows-amd64")
                    implementation("org.jogamp.gluegen:gluegen-rt:$joglVersion:natives-windows-amd64")
                } else {
                    implementation(libs.jogl.all)
                    implementation(libs.gluegen.rt)
                }
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:2.0.0-alpha03") {
                exclude(group = "org.jetbrains.compose.ui", module = "ui")
                exclude(group = "org.jetbrains.compose.foundation", module = "foundation")
            }
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
        add("fullImplementation", libs.ksoup)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.DesktopAppKt"
        buildTypes.release.proguard {
            isEnabled.set(true)
            optimize.set(false)
            obfuscate.set(false)
            configurationFiles.from(project.file("desktop-proguard-rules.pro"))
        }
        nativeDistributions {
            packageName = "Nuvio"
            packageVersion = releaseAppVersionName
            description = "Nuvio desktop streaming app"
            vendor = "Nuvio"
            modules("java.net.http")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            macOS {
                dockName = "Nuvio"
                iconFile.set(project.file("desktop-icons/nuvio.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("desktop-icons/nuvio.ico"))
                menu = true
                menuGroup = "Nuvio"
                dirChooser = true
                perUserInstall = true
                shortcut = true
                upgradeUuid = "e4d85c1d-4bb0-4ed2-9a02-dfa497317d20"
            }
        }
    }
}

tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (
        targetFormat == org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi ||
        targetFormat == org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
    ) {
        freeArgs.add("--win-shortcut-prompt")
    }
}

fun registerRerunJpackageTask(
    taskName: String,
    packageTaskName: String,
    argsFilePath: String,
    outputDirPath: String,
    outputExtension: String,
) = tasks.register(taskName) {
    val sourceArgsFile = layout.buildDirectory.file(argsFilePath)
    val skikoTempDir = layout.buildDirectory.dir("compose/tmp/skiko")
    val proguardTempDir = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    val processedDesktopResourcesDir = layout.buildDirectory.dir("processedResources/desktop/main")
    val resourceDir = project.layout.projectDirectory.dir("desktop-package-resources/windows")
    val outputDir = layout.buildDirectory.dir(outputDirPath)
    val packageTask = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>(packageTaskName)

    inputs.file(sourceArgsFile)
    inputs.dir(skikoTempDir)
    inputs.dir(proguardTempDir)
    inputs.dir(processedDesktopResourcesDir)
    inputs.dir(resourceDir)
    outputs.dir(outputDir)
    notCompatibleWithConfigurationCache("Reruns jpackage from generated response files")

    onlyIf {
        isWindowsHost && sourceArgsFile.get().asFile.exists()
    }

    doLast {
        val argsFile = sourceArgsFile.get().asFile
        val rerunDestDir = temporaryDir.resolve("dest").apply {
            deleteRecursively()
            mkdirs()
        }
        val rerunInputDir = temporaryDir.resolve("input").apply {
            rebuildJpackageInputDir(
                rebuiltInputDir = this,
                proguardDir = proguardTempDir.get().asFile,
                skikoDir = skikoTempDir.get().asFile,
                processedDesktopResourcesDir = processedDesktopResourcesDir.get().asFile,
            )
        }
        val filteredArgs = mutableListOf<String>()
        val originalArgs = argsFile.readLines().filter { it.isNotBlank() }
        var index = 0
        while (index < originalArgs.size) {
            val arg = originalArgs[index]
            if (arg == "--resource-dir" || arg == "--dest" || arg == "--input") {
                index += 2
                continue
            }
            if (arg == "--win-shortcut-prompt") {
                index += 1
                continue
            }
            filteredArgs += arg
            index += 1
        }

        val runtimeImageIndex = filteredArgs.indexOf("--runtime-image").takeIf { it >= 0 } ?: 0
        filteredArgs.add(runtimeImageIndex, "--input")
        filteredArgs.add(runtimeImageIndex + 1, encodeResponseFilePath(rerunInputDir))

        val insertionIndex = filteredArgs.indexOf("--type").takeIf { it >= 0 } ?: filteredArgs.size
        filteredArgs.add(insertionIndex, "--win-shortcut-prompt")
        filteredArgs.add(insertionIndex + 1, "--resource-dir")
        filteredArgs.add(insertionIndex + 2, encodeResponseFilePath(resourceDir.asFile))
        filteredArgs.add(insertionIndex + 3, "--dest")
        filteredArgs.add(insertionIndex + 4, encodeResponseFilePath(rerunDestDir))

        val overrideArgsFile = argsFile.resolveSibling("${argsFile.nameWithoutExtension}.override.args.txt")
        overrideArgsFile.writeText(filteredArgs.joinToString(System.lineSeparator()))

        val javaHome = File(System.getProperty("java.home"))
        val jpackageExecutable = listOf(
            javaHome.resolve("bin/jpackage.exe"),
            javaHome.resolve("bin/jpackage"),
        ).firstOrNull { it.exists() } ?: error("Unable to locate jpackage in ${javaHome.path}")

        val currentPath = System.getenv("PATH").orEmpty()
        val wixDir = packageTask.get().wixToolsetDir.asFile.orNull?.absolutePath
        val wixPathPrefix = wixDir?.let { "$it${File.pathSeparator}" }.orEmpty()

        project.exec {
            executable = jpackageExecutable.absolutePath
            args("@${overrideArgsFile.absolutePath}")
            environment("PATH", "$wixPathPrefix$currentPath")
        }

        val finalOutputDir = outputDir.get().asFile.apply { mkdirs() }
        val rebuiltArtifacts = rerunDestDir.listFiles()
            ?.filter { it.extension.equals(outputExtension, ignoreCase = true) && it.name.startsWith("Nuvio-") }
            .orEmpty()
        if (rebuiltArtifacts.isEmpty()) {
            error("No .$outputExtension output found in ${rerunDestDir.path}")
        }

        finalOutputDir.listFiles()
            ?.filter { it.extension.equals(outputExtension, ignoreCase = true) && it.name.startsWith("Nuvio-") }
            ?.forEach { it.delete() }

        rebuiltArtifacts.forEach { artifact ->
            artifact.copyTo(finalOutputDir.resolve(artifact.name), overwrite = true)
        }
    }
}

val rerunReleaseExeJpackage = registerRerunJpackageTask(
    taskName = "rerunReleaseExeJpackage",
    packageTaskName = "packageReleaseExe",
    argsFilePath = "compose/tmp/packageReleaseExe.args.txt",
    outputDirPath = "compose/binaries/main-release/exe",
    outputExtension = "exe",
)

val rerunReleaseMsiJpackage = registerRerunJpackageTask(
    taskName = "rerunReleaseMsiJpackage",
    packageTaskName = "packageReleaseMsi",
    argsFilePath = "compose/tmp/packageReleaseMsi.args.txt",
    outputDirPath = "compose/binaries/main-release/msi",
    outputExtension = "msi",
)

val renameReleaseDmgArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseDmgArtifact") {
    versionName.set(releaseAppVersionName)
    artifactExtension.set("dmg")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
}

val renameReleaseMsiArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseMsiArtifact") {
    versionName.set(releaseAppVersionName)
    artifactExtension.set("msi")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/msi"))
}

val renameReleaseExeArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseExeArtifact") {
    versionName.set(releaseAppVersionName)
    artifactExtension.set("exe")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/exe"))
}

tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
    finalizedBy(renameReleaseDmgArtifact)
}

tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    finalizedBy(rerunReleaseMsiJpackage)
}

tasks.matching { it.name == "packageReleaseExe" }.configureEach {
    finalizedBy(rerunReleaseExeJpackage)
}

rerunReleaseMsiJpackage.configure {
    mustRunAfter(tasks.named("packageReleaseExe"))
    finalizedBy(renameReleaseMsiArtifact)
}

rerunReleaseExeJpackage.configure {
    mustRunAfter(tasks.named("packageReleaseMsi"))
    finalizedBy(renameReleaseExeArtifact)
}

val buildDesktopMpvBridge = tasks.register<Exec>("buildDesktopMpvBridge") {
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    workingDir = rootProject.file("MPVKit")
    commandLine("swift", "build", "-c", "release", "--product", "DesktopMPVBridge")
    inputs.file(rootProject.file("MPVKit/Package.swift"))
    inputs.dir(rootProject.file("MPVKit/Sources/DesktopMPVBridge"))
    outputs.dir(rootProject.file("MPVKit/.build"))
}

tasks.matching { it.name == "run" || it.name == "desktopRun" }.configureEach {
    dependsOn(buildDesktopMpvBridge)
    if (this is org.gradle.process.JavaForkOptions) {
        jvmArgs("-Dnuvio.logging.mode=debug")
    }
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        manifest.srcFile("src/androidFull/AndroidManifest.xml")
        java.srcDir(fullCommonSourceDir)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
