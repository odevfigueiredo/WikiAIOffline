package com.wikiaioffline

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ZimLibraryInfo(
    val id: String,
    /** Nome físico do arquivo, preservado para identificar referências registradas. */
    val name: String,
    val language: SourceLanguage,
    val localCopy: Boolean,
    val available: Boolean,
    val title: String = name,
    val description: String? = null,
    val edition: String? = null,
    val date: String? = null
)

internal class PendingLibraryCopyDeletionException :
    IOException("O acervo saiu do catálogo. O app vai tentar apagar a cópia pendente na próxima abertura.")

/** Gerencia arquivos e permissões locais, serializando acesso aos identificadores nativos. */
class MultiLibraryRepository(context: Context) {
    private data class SavedBook(
        val id: String,
        val name: String,
        val language: SourceLanguage,
        val uri: String,
        val copyName: String? = null,
        val metadata: ZimMetadata = ZimMetadata()
    )

    private data class OpenBook(val uri: String, val repo: ZimRepository, val persistent: Boolean)

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val catalogFile = File(this.context.noBackupFilesDir, "wiki-zim-catalog.json")
    private val copiesDir = File(this.context.filesDir, "zim")
    private val open = LinkedHashMap<String, OpenBook>()
    private var catalog: List<SavedBook>? = null
    private var closed = false
    @Volatile private var snapshot = emptyList<ZimLibraryInfo>()

    fun libraries(): List<ZimLibraryInfo> = snapshot
    fun isOpen(): Boolean = snapshot.any { it.available }
    fun hasEnglishSource(): Boolean =
        snapshot.any { it.available && it.language != SourceLanguage.PORTUGUESE }

    fun labels(): List<String> = snapshot.filter { it.available }.map {
        it.title + " [" + it.language.tag + "]"
    }

    suspend fun restore(): List<String> = operate {
        val saved = savedBooks()
        val notices = mutableListOf<String>()
        try {
            for (book in saved) {
                currentCoroutineContext().ensureActive()
                if (book.id in open) continue
                try {
                    val repo = if (book.copyName != null) {
                        ZimRepository.openFromFile(context, File(copiesDir, book.copyName), book.name)
                    } else {
                        ZimRepository.openFromUri(context, localUri(Uri.parse(book.uri)), book.name)
                    }
                    if (repo.id != book.id) {
                        runCatching { repo.close() }
                        throw IOException("O arquivo contém outro acervo. Adicione-o novamente.")
                    }
                    open[book.id] = OpenBook(book.uri, repo, persistent = true)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notices.add((book.metadata.title ?: book.name) + ": " + error.zimDetail())
                }
            }
            val refreshed = saved.map { book ->
                val repo = open[book.id]?.repo ?: return@map book
                book.copy(language = repo.language, metadata = repo.metadata)
            }
            if (refreshed != saved) {
                try {
                    saveBooks(refreshed)
                } catch (error: IOException) {
                    notices.add("Não foi possível atualizar os detalhes gravados do acervo. " + error.zimDetail())
                }
            }
            removeOrphanedCopies(saved)
        } finally {
            publish()
        }
        notices
    }

    suspend fun addFromUri(uri: Uri): String = operate {
        checkNotNull(openDirect(localUri(uri), requirePersistent = false))
    }

    suspend fun openOrCopy(uri: Uri, onCopyProgress: (Int?) -> Unit): String = operate {
        val source = localUri(uri)
        openDirect(source, requirePersistent = true) ?: copyToCatalog(source, onCopyProgress)
    }

    private suspend fun openDirect(source: Uri, requirePersistent: Boolean): String? {
        val saved = savedBooks()
        val key = source.toString()
        open.values.firstOrNull { it.uri == key }?.let {
            return if (it.persistent || !requirePersistent) "Biblioteca já aberta: " + it.repo.title else null
        }
        val name = displayName(source)
        val repo = try {
            ZimRepository.openFromUri(context, source, name)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (requirePersistent) return null
            throw IOException(
                "Não foi possível abrir $name diretamente. " +
                    "Se o arquivo estiver neste dispositivo, tente importar uma cópia. " + error.zimDetail(),
                error
            )
        }
        var registered = false
        try {
            currentCoroutineContext().ensureActive()
            open[repo.id]?.let {
                return if (it.persistent || !requirePersistent) "Biblioteca já aberta: " + it.repo.title else null
            }
            val persistent = keepAccess(source)
            if (!persistent && requirePersistent) return null
            val previousCopy = saved.firstOrNull { it.id == repo.id }?.copyName
            if (persistent) {
                saveBooks(updated(saved, SavedBook(repo.id, name, repo.language, key, metadata = repo.metadata)))
                previousCopy?.let(::deleteCopy)
            }
            open[repo.id] = OpenBook(key, repo, persistent)
            registered = true
            publish()
            val status = if (persistent) "aberta sem copiar" else "aberta nesta sessão. Importe uma cópia para manter o acesso"
            return "${repo.title}: $status [${repo.language.tag}]."
        } finally {
            if (!registered) runCatching { repo.close() }
        }
    }

    suspend fun copyAndAdd(uri: Uri, onProgress: (Int?) -> Unit): String = operate {
        copyToCatalog(localUri(uri), onProgress)
    }

    private suspend fun copyToCatalog(source: Uri, onProgress: (Int?) -> Unit): String {
        currentCoroutineContext().ensureActive()
        val key = source.toString()
        val saved = savedBooks()
        saved.firstOrNull { it.uri == key && it.copyName != null && it.id in open }?.let {
            return "Cópia já importada: " + (it.metadata.title ?: it.name)
        }
        val name = displayName(source)
        if (!copiesDir.isDirectory && !copiesDir.mkdirs()) throw IOException("O armazenamento de acervos está indisponível.")
        val declaredSize = queryColumn(source, OpenableColumns.SIZE) { it.getLong(0) }
        if (declaredSize != null && declaredSize > (copiesDir.usableSpace - COPY_RESERVE).coerceAtLeast(0)) {
            throw IOException("Espaço insuficiente para copiar $name.")
        }
        val temp = File.createTempFile("pending-", ".zim", copiesDir)
        return try {
            copyTo(source, temp, declaredSize, onProgress)
            val staged = ZimRepository.openFromFile(context, temp, name)
            val id = try { staged.id } finally { staged.close() }
            currentCoroutineContext().ensureActive()
            val previous = saved.firstOrNull { it.id == id }
            if (previous?.copyName != null && id in open) {
                return "Cópia já importada: " + (previous.metadata.title ?: previous.name)
            }
            val destination = File(copiesDir, UUID.randomUUID().toString() + ".zim")
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            var repo: ZimRepository? = null
            var registered = false
            try {
                repo = ZimRepository.openFromFile(context, destination, name)
                currentCoroutineContext().ensureActive()
                val item = SavedBook(id, name, repo.language, key, destination.name, repo.metadata)
                saveBooks(updated(saved, item))
                val replaced = open.put(id, OpenBook(key, repo, persistent = true))
                registered = true
                publish()
                replaced?.let { runCatching { it.repo.close() } }
                previous?.copyName?.let(::deleteCopy)
                previous?.takeIf { it.copyName == null }?.let { releaseAccessIfUnused(it.uri) }
                "${repo.title}: cópia importada [${repo.language.tag}]."
            } finally {
                if (!registered) {
                    repo?.let { runCatching { it.close() } }
                    destination.delete()
                }
            }
        } finally {
            temp.delete()
        }
    }

    suspend fun remove(libraryId: String): Boolean = operate {
        val saved = savedBooks()
        val previous = saved.firstOrNull { it.id == libraryId }
        if (previous == null && libraryId !in open) return@operate false
        if (previous != null) saveBooks(saved.filterNot { it.id == libraryId })
        val active = open.remove(libraryId)
        publish()
        active?.let { runCatching { it.repo.close() } }
        previous?.copyName?.let { copy ->
            if (!deleteCopy(copy)) throw PendingLibraryCopyDeletionException()
        }
        previous?.takeIf { it.copyName == null }?.let { releaseAccessIfUnused(it.uri) }
        true
    }

    suspend fun search(
        plan: BilingualPlan,
        maxArticles: Int = 4,
        includeEnglish: Boolean = true
    ): RetrievalResult = operate {
        if (open.isEmpty()) return@operate RetrievalResult(emptyList(), false, listOf("Nenhuma biblioteca aberta."))
        val books = open.values.filter { includeEnglish || it.repo.language != SourceLanguage.ENGLISH }
        if (books.isEmpty()) {
            return@operate RetrievalResult(
                emptyList(), false, listOf("Ative os acervos em inglês para pesquisar nas bibliotecas abertas.")
            )
        }
        val limit = maxArticles.coerceIn(1, 6)
        val found = mutableListOf<ArticleEvidence>()
        val notices = mutableListOf<String>()
        var usedFullText = false
        for ((_, repo) in books) {
            currentCoroutineContext().ensureActive()
            try {
                val queries = if (includeEnglish) plan.queriesFor(repo.language) else listOf(plan.originalQuestion)
                val result = repo.findEvidenceSet(queries, limit * 2)
                found.addAll(result.articles)
                notices.addAll(result.notices.map { repo.name + ": " + it })
                usedFullText = usedFullText || result.usedFullText
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.add(repo.name + ": " + error.zimDetail())
            }
        }
        RetrievalResult(BilingualRanking.select(found, plan, limit, includeEnglish), usedFullText, notices.distinct())
    }

    suspend fun readArticle(source: ArticleEvidence): String = readArticleDetails(source).text

    internal suspend fun readArticleDetails(source: ArticleEvidence): ZimRepository.ArticleText = operate {
        val book = if (source.libraryId.isNotBlank()) {
            open[source.libraryId]
        } else {
            snapshot.singleOrNull { it.name == source.book }?.let { open[it.id] }
        } ?: throw IOException("O acervo desta fonte não está aberto ou não pôde ser identificado.")
        book.repo.readArticle(source.path)
    }

    suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        catalogLock.withLock {
            closed = true
            open.values.forEach { runCatching { it.repo.close() } }
            open.clear()
            publish()
        }
    }

    private suspend fun <T> operate(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        catalogLock.withLock {
            check(!closed) { "O catálogo de acervos foi fechado." }
            action()
        }
    }

    private fun savedBooks(): List<SavedBook> {
        return try {
            val stored = if (catalogFile.exists()) JSONArray(catalogFile.readText()) else JSONArray()
            (0 until stored.length()).map { index ->
                val row = stored.getJSONObject(index)
                val copyName = row.optString("copy").ifBlank { null }
                require(copyName == null || copyName.matches(COPY_NAME)) { "Nome de cópia inválido no catálogo ZIM." }
                SavedBook(
                    row.getString("id"), row.getString("name"),
                    SourceLanguage.fromMetadata(row.getString("language")), row.getString("uri"), copyName,
                    ZimMetadata(
                        title = cleanZimMetadata(row.optString("title"), 160),
                        description = cleanZimMetadata(row.optString("description"), 600),
                        edition = cleanZimMetadata(row.optString("edition"), 80),
                        date = zimMetadataDate(row.optString("date"))
                    )
                )
            }.also { catalog = it }
        } catch (error: Exception) {
            throw IOException("Não foi possível ler o catálogo local de acervos.", error)
        }
    }

    private fun updated(saved: List<SavedBook>, book: SavedBook): List<SavedBook> =
        if (saved.any { it.id == book.id }) saved.map { if (it.id == book.id) book else it }
        else saved + book

    private fun saveBooks(items: List<SavedBook>) {
        val json = JSONArray()
        for (book in items) {
            json.put(JSONObject().put("id", book.id).put("name", book.name)
                .put("language", book.language.tag).put("uri", book.uri).put("copy", book.copyName.orEmpty())
                .put("title", book.metadata.title).put("description", book.metadata.description)
                .put("edition", book.metadata.edition).put("date", book.metadata.date))
        }
        val staging = File(catalogFile.parentFile, catalogFile.name + ".pending")
        try {
            FileOutputStream(staging).use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(staging.toPath(), catalogFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
        catalog = items
    }

    private fun publish() {
        val saved = catalog.orEmpty()
        val entries = saved.map { book ->
            val current = open[book.id]?.repo
            val details = current?.metadata ?: book.metadata
            ZimLibraryInfo(
                id = book.id, name = current?.name ?: book.name, language = current?.language ?: book.language,
                localCopy = book.copyName != null, available = current != null,
                title = current?.title ?: details.title ?: cleanZimMetadata(book.name, 220) ?: "biblioteca.zim",
                description = details.description,
                edition = details.edition, date = details.date
            )
        }
        val savedIds = saved.mapTo(HashSet()) { it.id }
        snapshot = entries + open.values.filterNot { it.repo.id in savedIds }.map { (_, repo) ->
            ZimLibraryInfo(
                id = repo.id, name = repo.name, language = repo.language, localCopy = false, available = true,
                title = repo.title, description = repo.metadata.description, edition = repo.metadata.edition,
                date = repo.metadata.date
            )
        }
    }

    private suspend fun copyTo(uri: Uri, file: File, declaredSize: Long?, onProgress: (Int?) -> Unit) {
        val knownSize = declaredSize?.takeIf { it > 0 }
        onProgress(if (knownSize == null) null else 0)
        val input = resolver.openInputStream(uri) ?: throw IOException("O seletor não conseguiu ler o arquivo.")
        input.use { source ->
            FileOutputStream(file).use { sink ->
                val magic = source.readNBytes(ZIM_MAGIC.size)
                if (!magic.contentEquals(ZIM_MAGIC)) throw IOException("O arquivo selecionado não é um ZIM.")
                sink.write(magic)
                var total = magic.size.toLong()
                var reported = 0
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    total += count
                    if (knownSize != null) {
                        val percent = (100.0 * total / knownSize).toInt().coerceAtMost(99)
                        if (percent != reported) onProgress(percent)
                        reported = percent
                    }
                }
                if (knownSize != null && total != knownSize) throw IOException("O tamanho mudou durante a cópia do ZIM.")
                currentCoroutineContext().ensureActive()
                sink.fd.sync()
            }
        }
        onProgress(100)
    }

    private fun displayName(uri: Uri): String =
        queryColumn(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }?.takeIf(String::isNotBlank)?.take(220)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.take(220)?.takeIf(String::isNotBlank)
            ?: "biblioteca.zim"

    private fun <T> queryColumn(uri: Uri, column: String, read: (android.database.Cursor) -> T): T? =
        try {
            resolver.query(uri, arrayOf(column), null, null, null)?.use {
                if (it.moveToFirst() && it.columnCount > 0 && !it.isNull(0)) read(it) else null
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun localUri(uri: Uri): Uri {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) {
            "Selecione um arquivo local pelo seletor do Android."
        }
        return uri
    }

    private fun keepAccess(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return true
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    }

    private fun releaseAccessIfUnused(key: String) {
        if (catalog.orEmpty().any { it.uri == key && it.copyName == null }) return
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(key), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun deleteCopy(name: String): Boolean =
        File(copiesDir, name).let { !it.exists() || it.delete() }

    private fun removeOrphanedCopies(items: List<SavedBook>) {
        val kept = items.mapNotNullTo(HashSet()) { it.copyName }
        copiesDir.listFiles()?.filter {
            it.name !in kept && (it.name.matches(COPY_NAME) || it.name.matches(TEMP_NAME))
        }?.forEach { it.delete() }
    }

    private companion object {
        val catalogLock = Mutex()
        const val COPY_RESERVE = 32L * 1024 * 1024
        val ZIM_MAGIC = byteArrayOf(0x5a, 0x49, 0x4d, 0x04)
        val COPY_NAME = Regex("[0-9a-f-]{36}\\.zim")
        val TEMP_NAME = Regex("pending-[0-9]+\\.zim")
    }
}
