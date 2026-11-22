package com.wikiaioffline

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Entry
import org.kiwix.libzim.EntryNotFoundException
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

internal data class ZimMetadata(
    val title: String? = null,
    val description: String? = null,
    val edition: String? = null,
    val date: String? = null
)

private val metadataWhitespace = Regex("[\\p{Cc}\\p{Z}]+")
private val bidiControls = Regex("[\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")

internal fun cleanZimMetadata(value: String?, limit: Int): String? {
    val text = value?.replace(bidiControls, "")?.replace(metadataWhitespace, " ")?.trim()
        ?.take(limit) ?: return null
    val complete = if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text
    return complete.trimEnd().takeIf(String::isNotBlank)
}

internal fun zimMetadataDate(value: String?): String? {
    val date = cleanZimMetadata(value, 11)?.takeIf { it.length == 10 } ?: return null
    return try {
        LocalDate.parse(date).toString()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Uma biblioteca aberta com a interface JNI da versão de libkiwix usada pelo app. */
internal class ZimRepository private constructor(
    private val archive: Archive,
    private val descriptor: AssetFileDescriptor?,
    val name: String
) {
    val id: String = archive.uuid.trim().lowercase(Locale.ROOT).also {
        require(it.isNotBlank()) { "O ZIM não possui um identificador válido." }
    }
    private val metadataKeys = optionalMetadata { archive.metadataKeys.toSet() }
    val language: SourceLanguage = readMetadata("Language", 80)
        ?.let { SourceLanguage.fromMetadata(it) } ?: SourceLanguage.fromZimName(name)
    val metadata = ZimMetadata(
        title = readMetadata("Title", 160),
        description = readMetadata("LongDescription", 600) ?: readMetadata("Description", 600),
        edition = readMetadata("Flavour", 80),
        date = zimMetadataDate(readMetadata("Date", 11))
    )
    val title: String get() = metadata.title ?: cleanZimMetadata(name, 220) ?: "biblioteca.zim"

    private fun readMetadata(key: String, textLimit: Int): String? {
        if (metadataKeys?.contains(key) == false) return null
        return optionalMetadata {
            val item = archive.getMetadataItem(key)
            if (item.size !in 1..MAX_METADATA_BYTES) return@optionalMetadata null
            val blob = item.data
            val value = try { blob.data.toString(Charsets.UTF_8) } finally { blob.dispose() }
            cleanZimMetadata(value, textLimit)
        }
    }

    private inline fun <T> optionalMetadata(read: () -> T): T? = try {
        read()
    } catch (error: Exception) {
        // O JNI pode usar java.lang.Exception mesmo para uma chave ausente.
        if (error is CancellationException) throw error
        null
    }

    suspend fun findEvidenceSet(queries: List<String>, maxItems: Int): RetrievalResult {
        val selectedQueries = queries.map(String::trim).filter(String::isNotBlank).distinct().take(4)
        val articles = LinkedHashMap<String, ArticleEvidence>()
        val indexedPaths = mutableSetOf<String>()
        val notices = mutableListOf<String>()
        var readFailure: String? = null

        suspend fun collect(entry: Entry, indexed: Boolean) {
            currentCoroutineContext().ensureActive()
            try {
                val entryPath = entry.path
                if (entryPath in articles) {
                    if (indexed) indexedPaths.add(entryPath)
                } else {
                    val (title, path, text) = read(entry) ?: return
                    val excerpt = extractExcerpt(selectedQueries, text)
                    if (excerpt.isBlank()) return
                    articles.putIfAbsent(path, ArticleEvidence(title, path, excerpt, name, language.tag, id))
                    if (indexed) indexedPaths.add(path)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (readFailure == null) readFailure = error.zimDetail()
            }
        }

        if (archive.hasFulltextIndex()) {
            val indexQueries = selectedQueries.flatMap {
                val terms = SearchTerms.terms(it)
                listOf(SearchTerms.query(it), terms.take(2).joinToString(" "))
            }.filter(String::isNotBlank).distinct().take(8)
            try {
                if (indexQueries.isNotEmpty()) {
                    searchIndex(indexQueries, maxItems) { collect(it, true) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.add("Não foi possível consultar todo o índice de texto completo. " + error.zimDetail())
            }
        } else {
            notices.add("O ZIM não tem índice de texto completo. A pesquisa usou títulos.")
        }

        val titleQueries = selectedQueries.flatMap(::candidateTitles).distinct().take(12)
        for (title in titleQueries) {
            currentCoroutineContext().ensureActive()
            try {
                if (archive.hasEntryByTitle(title)) collect(archive.getEntryByTitle(title), false)
            } catch (_: EntryNotFoundException) {
                // Uma consulta pode ter apenas correspondências aproximadas no índice de títulos.
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (readFailure == null) readFailure = error.zimDetail()
            }
        }
        if (articles.isEmpty() && titleQueries.isNotEmpty()) {
            try {
                suggestTitles(titleQueries.take(4), maxItems) { collect(it, false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.add("Não foi possível consultar as sugestões de título. " + error.zimDetail())
            }
        }
        readFailure?.let { notices.add("Algumas entradas do acervo não puderam ser lidas. " + it) }
        val ranked = articles.values.sortedWith(compareByDescending<ArticleEvidence> { article ->
            selectedQueries.maxOfOrNull { SearchTerms.score(it, article.title, article.excerpt) } ?: 0
        }.thenBy { it.title }).take(maxItems)
        return RetrievalResult(ranked, ranked.any { it.path in indexedPaths }, notices)
    }

    fun readArticle(path: String): ArticleText {
        val entry = try {
            archive.getEntryByPath(path)
        } catch (error: EntryNotFoundException) {
            throw IOException("O artigo não foi encontrado neste acervo.", error)
        }
        return read(entry) ?: throw IOException("O artigo não contém texto legível ou é grande demais.")
    }

    fun close() {
        try {
            archive.dispose()
        } finally {
            descriptor?.close()
        }
    }

    private suspend fun searchIndex(
        queries: List<String>,
        limit: Int,
        collect: suspend (Entry) -> Unit
    ) {
        val seen = HashSet<String>()
        val searcher = Searcher(archive)
        try {
            for (text in queries) {
                currentCoroutineContext().ensureActive()
                val query = Query(text)
                try {
                    val search = searcher.search(query)
                    try {
                        val results = search.getResults(0, limit)
                        try {
                            while (results.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                val entry = results.next()
                                if (seen.add(entry.path)) collect(entry)
                            }
                        } finally {
                            results.dispose()
                        }
                    } finally {
                        search.dispose()
                    }
                } finally {
                    query.dispose()
                }
            }
        } finally {
            searcher.dispose()
        }
    }

    private suspend fun suggestTitles(
        queries: List<String>,
        limit: Int,
        collect: suspend (Entry) -> Unit
    ) {
        val seen = HashSet<String>()
        val searcher = SuggestionSearcher(archive)
        try {
            for (text in queries) {
                currentCoroutineContext().ensureActive()
                val search = searcher.suggest(text)
                try {
                    val results = search.getResults(0, limit)
                    try {
                        while (results.hasNext() && seen.size < limit) {
                            currentCoroutineContext().ensureActive()
                            val path = results.next().path
                            if (!seen.add(path)) continue
                            try {
                                collect(archive.getEntryByPath(path))
                            } catch (_: EntryNotFoundException) {
                                // Ignore uma sugestão sem entrada correspondente.
                            }
                        }
                    } finally {
                        results.dispose()
                    }
                } finally {
                    search.dispose()
                }
                if (seen.size >= limit) break
            }
        } finally {
            searcher.dispose()
        }
    }

    data class ArticleText(val title: String, val path: String, val text: String)

    private fun read(entry: Entry): ArticleText? {
        val item = entry.getItem(true)
        val mimeType = item.mimetype.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mimeType !in readableMimeTypes || item.size !in 1..MAX_ARTICLE_BYTES) return null
        val blob = item.data
        val source = try { blob.data.toString(Charsets.UTF_8) } finally { blob.dispose() }
        val text = if (mimeType == "text/plain") source.trim() else articleText(source)
        if (text.none(Char::isLetterOrDigit)) return null
        return ArticleText(item.title.ifBlank { entry.title }, item.path, text)
    }

    companion object {
        private const val MAX_ARTICLE_BYTES = 4L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 32L * 1024
        private val readableMimeTypes = setOf("text/plain", "text/html", "application/xhtml+xml")
        private val askPrefix = Regex(
            "(?i)^(o que ([ée]|foi|era|s[ãa]o|foram)|quem ([ée]|foi|era)|me (explique|fale sobre|conte sobre)|" +
                "explique|fale sobre|resuma|defina|qual ([ée]|foi) (a |o )?|what is|who is|tell me about)\\s+"
        )
        private val leadingArticle = Regex("(?i)^(a|o|os|as|um|uma|sobre|de|do|da|the)\\s+")
        private val extraMarkup = Regex("(?is)<(head|script|style|nav|footer|noscript)\\b[^>]*>.*?</\\1\\s*>")
        private val excessBlankLines = Regex("\\n[\\t ]*\\n(?:[\\t ]*\\n)+")
        private val excessSpace = Regex("[\\t\\u00a0 ]+")
        @Volatile private var nativeReady = false

        fun openFromUri(context: Context, uri: Uri, name: String): ZimRepository {
            initialize(context)
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("O seletor não conseguiu abrir o arquivo.")
            try {
                val length = descriptor.declaredLength
                val archive = if (length == AssetFileDescriptor.UNKNOWN_LENGTH) {
                    Archive(descriptor.fileDescriptor)
                } else {
                    Archive(descriptor.fileDescriptor, descriptor.startOffset, length)
                }
                return opened(archive, descriptor, name)
            } catch (error: Exception) {
                runCatching { descriptor.close() }
                throw error
            }
        }

        fun openFromFile(context: Context, file: File, name: String): ZimRepository {
            initialize(context)
            return opened(Archive(file.path), null, name)
        }

        private fun opened(archive: Archive, descriptor: AssetFileDescriptor?, name: String): ZimRepository =
            try {
                ZimRepository(archive, descriptor, name)
            } catch (error: Exception) {
                runCatching { archive.dispose() }
                throw error
            }

        @Synchronized
        private fun initialize(context: Context) {
            if (nativeReady) return
            try {
                JNIKiwix(context.applicationContext)
            } catch (error: LinkageError) {
                throw IOException("O leitor ZIM nativo não pôde ser iniciado neste Android.", error)
            }
            nativeReady = true
        }

        private fun candidateTitles(question: String): List<String> {
            val clean = question.trim().trimEnd('?', '!', '.', ' ')
            val topic = clean.replace(askPrefix, "").trim()
            val withoutArticle = topic.replace(leadingArticle, "")
            return listOf(clean, topic, withoutArticle)
                .flatMap { listOf(it, it.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }) }
                .filter(String::isNotBlank).distinct()
        }

        private fun articleText(html: String): String =
            Html.fromHtml(html.replace(extraMarkup, " "), Html.FROM_HTML_MODE_LEGACY)
                .toString().replace(excessSpace, " ").replace(excessBlankLines, "\n\n").trim()

        private fun extractExcerpt(queries: List<String>, text: String): String {
            val paragraphs = text.split("\n\n").asSequence().map(String::trim)
                .filter { it.length >= 35 && it.any(Char::isLetterOrDigit) }.take(350).toList()
            if (paragraphs.isEmpty()) return text.take(1800)
            val best = paragraphs.withIndex()
                .sortedByDescending { paragraph ->
                    val score = queries.maxOfOrNull { SearchTerms.score(it, "", paragraph.value) } ?: 0
                    score * 10 + if (paragraph.index < 3) 1 else 0
                }.take(3).sortedBy { it.index }
            return best.joinToString("\n\n") { it.value }.take(1800)
        }
    }
}

internal fun Exception.zimDetail(): String = message?.takeIf(String::isNotBlank)?.take(240)
    ?: javaClass.simpleName
