import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose")
}

object ModelArchiveFiles {
    fun readProperties(file: File) = Properties().apply { file.inputStream().use(::load) }

    fun sha256(stream: java.io.InputStream): String = stream.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(512 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        HexFormat.of().formatHex(digest.digest())
    }
}

abstract class PrepareBuiltInModel : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val specification: RegularFileProperty

    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val localModel: RegularFileProperty

    @get:Input
    abstract val offline: Property<Boolean>

    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun prepare() {
        val descriptor = specification.get().asFile
        val model = ModelArchiveFiles.readProperties(descriptor)
        val expectedHash = model.getProperty("sha256")
        val expectedSize = model.getProperty("size").toLong()
        check(expectedHash.matches(Regex("[a-f0-9]{64}")) && expectedSize > 0)
        fun verified(file: File): Boolean =
            file.isFile && file.length() == expectedSize && ModelArchiveFiles.sha256(file.inputStream()) == expectedHash

        val given = localModel.orNull?.asFile
        val source = if (given != null) {
            check(verified(given)) { "O arquivo de -PwikiModelFile não corresponde ao modelo fixado." }
            given
        } else {
            val cached = cacheDirectory.file("$expectedHash.gguf").get().asFile
            if (!verified(cached)) {
                check(!offline.get()) {
                    "Modelo integrado indisponível no cache. Informe -PwikiModelFile ou rode a primeira compilação sem --offline."
                }
                val uri = URI(model.getProperty("source"))
                check(uri.scheme == "https" && uri.host == "huggingface.co")
                cached.parentFile.mkdirs()
                val staged = Files.createTempFile(cached.parentFile.toPath(), "model-", ".partial")
                try {
                    val connection = uri.toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 30_000
                        connection.readTimeout = 120_000
                        connection.inputStream.use { input ->
                            Files.newOutputStream(staged).use { output ->
                                val buffer = ByteArray(512 * 1024)
                                var written = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    check(written <= expectedSize) { "Download maior que o modelo fixado." }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    check(verified(staged.toFile())) { "O modelo baixado não passou na verificação de integridade." }
                    Files.move(staged, cached.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(staged)
                }
            }
            cached
        }
        val directory = assets.get().asFile
        directory.mkdirs()
        source.copyTo(directory.resolve("built_in_model.gguf"), overwrite = true)
        descriptor.copyTo(directory.resolve("built_in_model.properties"), overwrite = true)
    }
}

abstract class AlignOfflineApk @Inject constructor(private val process: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val apk: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipalign: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val apksigner: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val debugKeystore: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun align() {
        val input = apk.get().asFile
        val outputFile = output.get().asFile
        val zipalignPath = zipalign.get().asFile.absolutePath
        val signerPath = apksigner.get().asFile.absolutePath
        val java = File(System.getProperty("java.home"), if (File.separatorChar == '\\') "bin/java.exe" else "bin/java")
        fun run(vararg args: String) = process.exec { commandLine(args.toList()) }.assertNormalExitValue()

        outputFile.parentFile.mkdirs()
        val aligned = Files.createTempFile(outputFile.parentFile.toPath(), "wikiai-", ".apk")
        try {
            // zipalign base de 64 bytes também cobre o alinhamento dos tensores GGUF para mmap.
            run(zipalignPath, "-f", "-P", "16", "64", input.absolutePath, aligned.toString())
            run(
                java.absolutePath, "-jar", signerPath, "sign",
                "--ks", debugKeystore.get().asFile.absolutePath,
                "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                "--key-pass", "pass:android", "--alignment-preserved", "true",
                "--v4-signing-enabled", "false", aligned.toString()
            )
            run(zipalignPath, "-c", "-P", "16", "64", aligned.toString())
            run(java.absolutePath, "-jar", signerPath, "verify", aligned.toString())
            ZipFile(aligned.toFile()).use { zip ->
                val properties = checkNotNull(zip.getEntry("assets/built_in_model.properties"))
                val descriptor = Properties().apply { zip.getInputStream(properties).use(::load) }
                val modelEntry = checkNotNull(zip.getEntry("assets/built_in_model.gguf"))
                check(zip.getEntry("assets/" + descriptor.getProperty("licenseAsset")) != null)
                check(modelEntry.method == ZipEntry.STORED && modelEntry.size == descriptor.getProperty("size").toLong())
                check(ModelArchiveFiles.sha256(zip.getInputStream(modelEntry)) == descriptor.getProperty("sha256"))
            }
            Files.move(aligned, outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(aligned)
        }
    }
}

val wikiAbis = providers.gradleProperty("wikiAbis").orElse("arm64-v8a").get()
    .split(',').map(String::trim).distinct()
require(wikiAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "wikiAbis aceita arm64-v8a e x86_64, separados por vírgula."
}

android {
    namespace = "com.wikiaioffline"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.wikiaioffline"
        minSdk = 33 // O módulo Android atual do llama.cpp exige Android 13+.
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        ndk { abiFilters += wikiAbis }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources.noCompress += "gguf"
    packaging {
        jniLibs {
            useLegacyPackaging = true // Os backends de CPU do GGML são carregados do diretório nativo.
            pickFirsts += "**/libc++_shared.so" // Prioriza o runtime do NDK do subprojeto llama.cpp sobre a cópia do AAR.
        }
    }
    sourceSets.getByName("test").java.srcDir(rootProject.file("tests"))
}

val prepareBuiltInModel = tasks.register<PrepareBuiltInModel>("prepareBuiltInModel") {
    specification.set(rootProject.layout.projectDirectory.file("gradle/built-in-model.properties"))
    val workspace = rootProject.layout.projectDirectory.asFile
    localModel.set(layout.file(providers.gradleProperty("wikiModelFile").map(workspace::resolve)))
    offline.set(gradle.startParameter.isOffline)
    cacheDirectory.set(gradle.gradleUserHomeDir.resolve("caches/wikiai-models"))
    assets.set(layout.buildDirectory.dir("generated/wikiai-model/assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareBuiltInModel, PrepareBuiltInModel::assets)
}

tasks.register<AlignOfflineApk>("assembleOfflineDebug") {
    group = "build"
    description = "Gera e confere o APK de teste com modelo integrado alinhado para leitura direta."
    dependsOn("assembleDebug")
    apk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    val windows = File.separatorChar == '\\'
    val tools = androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/36.0.0") }
    zipalign.set(tools.map { it.file(if (windows) "zipalign.exe" else "zipalign") })
    apksigner.set(tools.map { it.file("lib/apksigner.jar") })
    debugKeystore.fileValue(checkNotNull(android.signingConfigs.getByName("debug").storeFile))
    output.set(layout.buildDirectory.file("outputs/apk/offline/WikiAI-Offline-v0.6.0-${wikiAbis.joinToString("-")}-debug.apk"))
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.kiwix:libkiwix:2.6.0")
    implementation(project(":lib")) // binding JNI de inferência local oficial do llama.cpp
    testImplementation(libs.junit)
}
