package com.wikiaioffline

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.UnsupportedArchitectureException
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/** Loads the built-in model on demand and serializes access to llama.cpp's process-wide engine. */
class ModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "models-v05")
    private val bundledDirectory = File(appContext.noBackupFilesDir, "bundled-model")
    private val selection = File(directory, "selected.json")
    private val bundledModel by lazy(::readBundledModel)

    val modelName: String? get() = loaded?.takeIf { it.owner === this }?.model?.name
    val isReady: Boolean get() = modelName != null
    val hasBundledModel: Boolean get() = bundledModel != null
    @Volatile var hasSavedModel: Boolean = false
        private set

    /** Describes the preferred model without opening its weights or starting the engine. */
    suspend fun restore(): String? = withContext(Dispatchers.IO) {
        operations.withLock {
            val selected = try {
                readExternalSelection()
            } catch (error: IOException) {
                return@withLock bundledModel?.name ?: throw error
            }
            discardUnusedFiles(selected)
            selected?.name ?: bundledModel?.name
        }
    }

    /** Lazily opens the selected GGUF, or the included model when the selection is unavailable. */
    suspend fun ensureReady(onProgress: (Int?) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            operations.withLock { ensureReadyLocked(onProgress) }
        }

    private suspend fun ensureReadyLocked(onProgress: (Int?) -> Unit): String {
        modelName?.let { return it }
        var externalFailure: Exception? = null
        val selected = try {
            readExternalSelection()
        } catch (error: IOException) {
            externalFailure = error
            null
        }
        if (selected != null) {
            try {
                return activate(selected, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                externalFailure = error
            }
        }
        val included = bundledModel ?: throw externalFailure
            ?: IOException("O modelo de IA integrado não está disponível nesta instalação.")
        return try {
            activate(included, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            externalFailure?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun activate(model: ModelChoice, onProgress: (Int?) -> Unit): String {
        val request = currentCoroutineContext()
        request.ensureActive()
        val previous = loaded
        if (previous?.model == model) {
            loaded = LoadedModel(this, model)
            return model.name
        }
        return withContext(NonCancellable) {
            try {
                unload()
                load(model, request, onProgress)
                request.ensureActive()
                loaded = LoadedModel(this@ModelRepository, model)
                model.name
            } catch (error: Exception) {
                recover(error, previous)
                throw error
            }
        }
    }

    suspend fun importAndLoad(uri: Uri, onProgress: (Int?) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            operations.withLock {
                ensureDirectory()
                val candidate = copy(uri, onProgress)
                val request = currentCoroutineContext()
                val previous = loaded
                var committed = false
                try {
                    request.ensureActive()
                    withContext(NonCancellable) {
                        try {
                            unload()
                            load(candidate)
                            request.ensureActive()
                            writeSelection(candidate)
                            committed = true
                            hasSavedModel = true
                            loaded = LoadedModel(this@ModelRepository, candidate)
                        } catch (error: Exception) {
                            recover(error, previous)
                            throw error
                        }
                    }
                    candidate.name
                } finally {
                    if (committed) discardUnusedFiles(candidate) else fileOf(candidate).delete()
                }
            }
        }

    suspend fun remove() = withContext(Dispatchers.IO) {
        operations.withLock {
            val previous = loaded
            try {
                if (previous?.model is StoredModel) unload()
                writeSelection(null)
                hasSavedModel = false
            } catch (error: Exception) {
                recover(error, previous)
                throw error
            }
            discardUnusedFiles(null)
        }
    }

    fun answerStream(prompt: String, maxTokens: Int = 768): Flow<String> = flow {
        require(maxTokens in 1..1024)
        operations.withLock {
            if (!isReady) withContext(Dispatchers.IO) { ensureReadyLocked {} }
            val activeEngine = checkNotNull(engine)
            try {
                activeEngine.resetConversation()
                activeEngine.sendUserPrompt(prompt, maxTokens).collect { emit(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeEngine.state.value is State.Error) {
                    withContext(NonCancellable + Dispatchers.IO) { recover(error) }
                }
                throw error
            }
        }
    }

    /** Only the current owner can unload. A new Activity may already have restored the same model. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        operations.withLock {
            if (loaded?.owner === this@ModelRepository) unload()
        }
    }

    private suspend fun load(
        model: ModelChoice,
        request: CoroutineContext? = null,
        onProgress: (Int?) -> Unit = {}
    ) {
        when (model) {
            is StoredModel -> loadFile(fileOf(model), model.size, bundled = false)
            is BundledModel -> loadBundled(model, request ?: currentCoroutineContext(), onProgress)
        }
    }

    private suspend fun loadBundled(
        model: BundledModel,
        request: CoroutineContext,
        onProgress: (Int?) -> Unit
    ) {
        val descriptor = try {
            appContext.assets.openFd(BUNDLED_ASSET)
        } catch (_: IOException) {
            null
        }
        descriptor?.use { asset ->
            if (asset.declaredLength != model.size) {
                throw IOException("O tamanho do modelo de IA integrado é inválido.")
            }
            if (asset.startOffset % MIN_ASSET_ALIGNMENT == 0L) {
                request.ensureActive()
                onProgress(null)
                try {
                    initializedEngine().loadEmbeddedModel(
                        asset.parcelFileDescriptor.fd,
                        asset.startOffset,
                        asset.declaredLength
                    )
                    return
                } catch (_: UnsupportedArchitectureException) {
                    // A copy also works with variants of Android that cannot map the embedded file.
                    unload()
                }
            }
        }

        val cached = File(bundledDirectory, "${model.sha256}.gguf")
        if (cached.length() != model.size || !cached.isFile) {
            ensureDirectory(bundledDirectory)
            bundledDirectory.listFiles()?.forEach { file ->
                if (file.extension in setOf("gguf", "tmp")) file.delete()
            }
            copyModelFile(
                { appContext.assets.open(BUNDLED_ASSET) },
                cached,
                model.size,
                request,
                onProgress,
                model.sha256
            )
        }
        request.ensureActive()
        onProgress(null)
        loadFile(cached, model.size, bundled = true)
    }

    private suspend fun loadFile(file: File, size: Long, bundled: Boolean) {
        if (!file.isFile || file.length() != size) {
            val reason = if (bundled) "O modelo de IA integrado está incompleto."
                else "A cópia do GGUF está ausente ou incompleta. Importe o modelo novamente."
            throw IOException(reason)
        }
        file.inputStream().use { readHeader(DataInputStream(it)) }
        try {
            initializedEngine().loadModel(file.absolutePath, bundled)
        } catch (error: UnsupportedArchitectureException) {
            throw IOException("GGUF incompatível ou danificado. Tente um modelo de conversa em arquivo único.", error)
        } catch (error: IOException) {
            throw IOException("Não foi possível carregar o modelo de IA. Confira a memória disponível.", error)
        }
    }

    private suspend fun initializedEngine(): InferenceEngine {
        val activeEngine = engine ?: try {
            AiChat.getInferenceEngine(appContext).also { engine = it }
        } catch (error: LinkageError) {
            throw IOException("O motor de IA deste aparelho não pôde iniciar.", error)
        }
        val state = withTimeoutOrNull(15_000L) {
            activeEngine.state.first { it !is State.Uninitialized && it !is State.Initializing }
        } ?: throw IOException("O motor de IA demorou para iniciar.")
        if (state is State.Error) {
            throw IOException("O motor de IA deste aparelho não pôde iniciar.", state.exception)
        }
        return activeEngine
    }

    private fun unload() {
        try {
            engine?.let {
                val state = it.state.value
                if (state is State.ModelReady || state is State.Error) it.cleanUp()
            }
        } finally {
            loaded = null
        }
    }

    private suspend fun recover(cause: Exception, previous: LoadedModel? = null) {
        try {
            unload()
            if (previous != null) {
                load(previous.model)
                loaded = previous
            }
        } catch (error: Exception) {
            cause.addSuppressed(error)
        }
    }

    private suspend fun copy(uri: Uri, onProgress: (Int?) -> Unit): StoredModel {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) {
            "Selecione um GGUF armazenado no aparelho."
        }
        val resolver = appContext.contentResolver
        val metadata = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val title = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (title >= 0) cursor.getString(title) else null
                val length = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null
                name to length
            } else null
        }
        val name = metadata?.first?.filterNot { it.isISOControl() }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Modelo GGUF"
        val expected = metadata?.second?.takeIf { it >= 0L }
        require(splitModelName.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 1 } != false) {
            "Escolha um modelo GGUF distribuído em um único arquivo."
        }
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.gguf")
        val copied = copyModelFile(
            {
                resolver.openInputStream(uri)
                    ?: throw IOException("O seletor do Android não conseguiu abrir o arquivo.")
            },
            target,
            expected,
            currentCoroutineContext(),
            onProgress
        )
        return StoredModel(id, name.take(160), copied)
    }

    private fun copyModelFile(
        open: () -> InputStream,
        target: File,
        expected: Long?,
        request: CoroutineContext,
        onProgress: (Int?) -> Unit,
        expectedSha256: String? = null
    ): Long {
        val targetDirectory = requireNotNull(target.parentFile)
        if (expected != null && expected > (targetDirectory.usableSpace - RESERVED_BYTES).coerceAtLeast(0)) {
            throw IOException("Espaço insuficiente para copiar o modelo de IA.")
        }
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        val staging = File.createTempFile("copy-", ".tmp", targetDirectory)
        try {
            var copied = 0L
            var progress: Int? = if (expected != null) 0 else null
            onProgress(progress)
            DataInputStream(open().buffered()).use { source ->
                val header = readHeader(source)
                FileOutputStream(staging).use { output ->
                    output.write(header)
                    digest?.update(header)
                    copied += header.size
                    var nextSpaceCheck = 0L
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        request.ensureActive()
                        if (copied >= nextSpaceCheck) {
                            if (targetDirectory.usableSpace < RESERVED_BYTES) {
                                throw IOException("A cópia do GGUF excedeu o espaço disponível.")
                            }
                            nextSpaceCheck = copied + 16L * 1024 * 1024
                        }
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (expected != null && copied > expected) {
                            throw IOException("O tamanho do GGUF mudou durante a cópia.")
                        }
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)
                        if (expected != null && expected > 0L) {
                            val percent = (copied.toDouble() * 100 / expected).toInt().coerceIn(0, 99)
                            if (percent != progress) {
                                progress = percent
                                onProgress(percent)
                            }
                        }
                    }
                    if (copied <= header.size || (expected != null && copied != expected)) {
                        throw IOException("A cópia do GGUF ficou incompleta.")
                    }
                    if (digest != null && digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
                        throw IOException("A cópia do modelo integrado falhou na verificação de integridade.")
                    }
                    output.fd.sync()
                }
            }
            request.ensureActive()
            onProgress(100)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return copied
        } finally {
            staging.delete()
        }
    }

    private fun readHeader(input: DataInputStream): ByteArray {
        val header = ByteArray(24)
        try {
            input.readFully(header)
        } catch (error: EOFException) {
            throw IOException("O arquivo é pequeno demais para conter um cabeçalho GGUF.", error)
        }
        require(header.copyOf(4).contentEquals("GGUF".toByteArray(Charsets.US_ASCII))) {
            "O arquivo selecionado não possui assinatura GGUF."
        }
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val version = fields.getInt(4)
        require(version in 2..3 && fields.getLong(8) > 0L && fields.getLong(16) >= 0L) {
            "O cabeçalho do GGUF está danificado ou usa uma versão incompatível."
        }
        return header
    }

    private fun ensureDirectory(root: File = directory) {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Não foi possível criar o armazenamento local de modelos.")
        }
    }

    private fun fileOf(model: StoredModel) = File(directory, "${model.id}.gguf")

    private fun readBundledModel(): BundledModel? = try {
        val details = Properties()
        appContext.assets.open(BUNDLED_METADATA).bufferedReader(Charsets.UTF_8).use(details::load)
        val name = details.getProperty("name").orEmpty().trim()
        val size = details.getProperty("size")?.toLongOrNull()
        val sha256 = details.getProperty("sha256").orEmpty().lowercase(Locale.ROOT)
        if (name.isNotBlank() && size != null && size > 24L && sha256.matches(sha256Pattern)) {
            BundledModel(name.take(160), size, sha256)
        } else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readExternalSelection(): StoredModel? {
        hasSavedModel = selection.isFile
        val selected = readSelection()
        hasSavedModel = selected != null
        return selected
    }

    private fun readSelection(): StoredModel? {
        if (!selection.exists()) return null
        try {
            val json = JSONObject(selection.readText(Charsets.UTF_8))
            if (json.length() == 0) return null
            val id = json.getString("id")
            require(UUID.fromString(id).toString() == id)
            val name = json.getString("name")
            val size = json.getLong("size")
            require(name.isNotBlank() && size > 24L)
            return StoredModel(id, name, size)
        } catch (error: JSONException) {
            throw IOException("A seleção do modelo está danificada. Importe o GGUF novamente ou remova a seleção.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("A seleção do modelo está danificada. Importe o GGUF novamente ou remova a seleção.", error)
        }
    }

    private fun writeSelection(model: StoredModel?) {
        ensureDirectory()
        val json = JSONObject()
        if (model != null) json.put("id", model.id).put("name", model.name).put("size", model.size)
        val staging = File.createTempFile("selection-", ".tmp", directory)
        try {
            FileOutputStream(staging).use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(staging.toPath(), selection.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    private fun discardUnusedFiles(selected: StoredModel?) {
        val selectedFilename = selected?.let { fileOf(it).name }
        directory.listFiles()?.forEach { entry ->
            if (entry.name != selectedFilename && (entry.extension == "gguf" || entry.extension == "tmp")) {
                entry.delete()
            }
        }
    }

    private sealed interface ModelChoice { val name: String }
    private data class StoredModel(val id: String, override val name: String, val size: Long) : ModelChoice
    private data class BundledModel(override val name: String, val size: Long, val sha256: String) : ModelChoice
    private data class LoadedModel(val owner: ModelRepository, val model: ModelChoice)

    private companion object {
        const val RESERVED_BYTES = 64L * 1024 * 1024
        const val BUNDLED_ASSET = "built_in_model.gguf"
        const val BUNDLED_METADATA = "built_in_model.properties"
        const val MIN_ASSET_ALIGNMENT = 32L
        val sha256Pattern = Regex("[a-f0-9]{64}")
        val splitModelName = Regex(".*-[0-9]{5}-of-([0-9]{5})\\.gguf", RegexOption.IGNORE_CASE)
        val operations = Mutex()
        var engine: InferenceEngine? = null
        @Volatile var loaded: LoadedModel? = null
    }
}
