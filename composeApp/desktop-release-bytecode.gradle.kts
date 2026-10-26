import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

val desktopJarFile = tasks.named<Jar>("desktopJar").flatMap { it.archiveFile }
val proguardReleaseDir = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
val releaseAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Kino/app")
val verifierSource = rootProject.layout.projectDirectory.file("desktop/scripts/VerifyBytecode.java")
val keptAppClassPrefix = "com/nuvio/app/"

tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val originalJarProvider = desktopJarFile
    val outputDirProvider = proguardReleaseDir
    val prefix = keptAppClassPrefix
    doLast {
        val originalJar = originalJarProvider.get().asFile
        val processedJar = outputDirProvider.get().file(originalJar.name).asFile
        check(processedJar.isFile) { "ProGuard output jar not found: $processedJar" }
        val rewrittenJar = File(processedJar.parentFile, "${processedJar.name}.restored")
        var restored = 0
        ZipFile(originalJar).use { original ->
            ZipFile(processedJar).use { processed ->
                ZipOutputStream(rewrittenJar.outputStream().buffered()).use { output ->
                    for (entry in processed.entries()) {
                        val source = original.getEntry(entry.name)
                            ?.takeIf { entry.name.startsWith(prefix) && entry.name.endsWith(".class") }
                        val bytes = if (source != null) {
                            restored++
                            original.getInputStream(source).use { it.readBytes() }
                        } else {
                            processed.getInputStream(entry).use { it.readBytes() }
                        }
                        output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
        }
        check(restored > 0) { "No $prefix classes were restored into $processedJar" }
        processedJar.delete()
        check(rewrittenJar.renameTo(processedJar)) { "Could not replace $processedJar" }
        logger.lifecycle("Restored $restored compiler-generated $prefix classes into ${processedJar.name}")
    }
}

val verifyReleaseBytecode by tasks.registering(Exec::class) {
    group = "verification"
    description = "Links every class in the packaged release app with the JVM bytecode verifier."
    dependsOn("createReleaseDistributable")
    val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
    val appDir = releaseAppDir
    val source = verifierSource
    inputs.file(source)
    executable = javaExecutable
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(source.asFile.absolutePath, appDir.get().asFile.absolutePath)
    })
}
