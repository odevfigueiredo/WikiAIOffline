package com.wikiaioffline

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Entry
import org.kiwix.libzim.EntryNotFoundException
import org.kiwix.libzim.Item
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
        return read(entry, includeImages = true)
            ?: throw IOException("O artigo não contém conteúdo legível ou é grande demais.")
    }

    fun readImage(path: String): ZimImageContent {
        val image = imageAt(path) ?: throw IOException("A imagem não está disponível neste acervo.")
        val bytes = itemBytes(image.item)
        val limit = zimImageByteLimits.getValue(image.mimeType)
        if (bytes.size !in 1..limit) throw IOException("A imagem do acervo excede o tamanho permitido.")
        if (image.mimeType == SVG_MIME_TYPE) {
            return ZimImageContent(image.mimeType, sanitizeSvg(bytes))
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_IMAGE_DIMENSION || bounds.outHeight !in 1..MAX_IMAGE_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS ||
            zimImageMimeType(bounds.outMimeType.orEmpty()) != image.mimeType
        ) throw IOException("A imagem do acervo está inválida ou possui dimensões excessivas.")
        return ZimImageContent(image.mimeType, bytes)
    }

    suspend fun findImages(queries: List<String>, limit: Int): List<RankedZimImage> {
        val search = queries.map(String::trim).filter(String::isNotBlank).distinct().take(4)
        if (search.isEmpty()) return emptyList()
        val candidates = LinkedHashMap<String, Entry>()
        val images = LinkedHashMap<String, RankedZimImage>()
        fun addCandidate(entry: Entry) {
            if (candidates.size < MAX_IMAGE_ARTICLES) candidates.putIfAbsent(entry.path, entry)
        }

        val titles = search.flatMap(::candidateTitles).distinct().take(12)
        for (candidate in titles) {
            currentCoroutineContext().ensureActive()
            availableEntry { if (archive.hasEntryByTitle(candidate)) archive.getEntryByTitle(candidate) else null }
                ?.let(::addCandidate)
        }
        if (archive.hasFulltextIndex() && candidates.size < MAX_IMAGE_ARTICLES) {
            val indexQueries = search.flatMap {
                listOf(SearchTerms.query(it), SearchTerms.terms(it).take(2).joinToString(" "))
            }.filter(String::isNotBlank).distinct().take(6)
            try {
                searchIndex(indexQueries, 4) { addCandidate(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
        if (candidates.size < 4) {
            try {
                suggestTitles(titles.take(3), 4) { addCandidate(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
        for (entry in candidates.values) {
            currentCoroutineContext().ensureActive()
            availableEntry { articleBody(entry) }?.takeIf { it.html }?.let { article ->
                for (result in imagesFromArticle(article, search)) {
                    val previous = images[result.image.path]
                    if (previous == null || result.score > previous.score) images[result.image.path] = result
                }
            }
            availableEntry { imageItem(entry) }?.let { item ->
                val result = directImage(item, search)
                images.putIfAbsent(result.image.path, result)
            }
        }
        if (images.size < limit) {
            for (prefix in imageSearchPrefixes(search)) {
                currentCoroutineContext().ensureActive()
                val matches = availableEntry { archive.findByPath(prefix) } ?: continue
                try {
                    for (index in 0 until 4) {
                        currentCoroutineContext().ensureActive()
                        if (!matches.hasNext()) break
                        val item = availableEntry { imageItem(matches.next()) } ?: continue
                        val result = directImage(item, search)
                        images.putIfAbsent(result.image.path, result)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                } finally {
                    matches.dispose()
                }
                if (images.size >= limit) break
            }
        }
        return images.values.sortedByDescending { it.score }.take(limit)
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

    data class ArticleText(
        val title: String,
        val path: String,
        val text: String,
        val images: List<ZimImage> = emptyList()
    )

    private data class ArticleBody(val title: String, val path: String, val source: String, val html: Boolean)

    private data class ImageItem(val item: Item, val mimeType: String)

    private fun articleBody(entry: Entry): ArticleBody? {
        val item = entry.getItem(true)
        val mimeType = item.mimetype.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mimeType !in readableMimeTypes || item.size !in 1..MAX_ARTICLE_BYTES) return null
        val source = itemBytes(item).toString(Charsets.UTF_8)
        return ArticleBody(item.title.ifBlank { entry.title }, item.path, source, mimeType != "text/plain")
    }

    private fun read(entry: Entry, includeImages: Boolean = false): ArticleText? {
        val article = articleBody(entry) ?: return null
        val text = if (article.html) articleText(article.source) else article.source.trim()
        val images = if (includeImages && article.html) {
            imagesFromArticle(article, emptyList()).take(MAX_ARTICLE_PREVIEW_IMAGES).map { it.image }
        } else emptyList()
        if (text.none(Char::isLetterOrDigit)) {
            if (images.isEmpty()) return null
            return ArticleText(article.title, article.path, "Este artigo contém imagens.", images)
        }
        return ArticleText(article.title, article.path, text, images)
    }

    private fun imagesFromArticle(article: ArticleBody, queries: List<String>): List<RankedZimImage> {
        val found = LinkedHashMap<String, RankedZimImage>()
        val contextScore = queries.maxOfOrNull { SearchTerms.score(it, article.title, "") } ?: 0
        val inspected = mutableMapOf<String, ImageItem?>()
        for (element in Jsoup.parse(article.source).select("img").take(MAX_IMG_TAGS_PER_ARTICLE)) {
            val item = imageSources(element).asSequence()
                .flatMap { localZimImagePaths(article.path, it).asSequence() }
                .firstNotNullOfOrNull { path ->
                    if (path in inspected) inspected[path] else imageAt(path).also { inspected[path] = it }
                } ?: continue
            val path = item.item.path
            val caption = element.closest("figure, .thumbinner, .thumb, .gallerybox")
                ?.selectFirst("figcaption, .thumbcaption, .gallerytext")?.text()
            val labels = listOf(caption, element.attr("alt"), element.attr("title"))
                .mapNotNull { cleanZimMetadata(it, 240) }.distinct()
            val title = labels.firstOrNull() ?: imageLabel(item.item)
            val searchableText = (labels + imageLabel(item.item) + path).joinToString(" ")
            val score = queries.maxOfOrNull { SearchTerms.score(it, title, searchableText) } ?: 0
            val candidate = ZimImage(id, path, title, article.title, article.path, this.title)
            val result = RankedZimImage(candidate, score * 4 + contextScore + 2)
            val previous = found[path]
            if (previous == null || result.score > previous.score) found[path] = result
            if (queries.isEmpty() && found.size >= MAX_ARTICLE_PREVIEW_IMAGES) break
        }
        return found.values.toList()
    }

    private fun imageSources(element: Element): List<String> {
        val srcsets = listOf(element.attr("srcset"), element.attr("data-srcset")) +
            element.closest("picture")?.select("source[srcset]").orEmpty().take(3).map { it.attr("srcset") }
        val variants = srcsets.filterNot { it.trimStart().startsWith("data:", ignoreCase = true) }
            .flatMap { it.split(',').take(3).map { candidate -> candidate.trim().substringBefore(' ') } }
        val candidates = listOf(element.attr("data-src"), element.attr("src")) + variants
        return candidates.filter(String::isNotBlank).distinct()
    }

    private fun directImage(item: ImageItem, queries: List<String>): RankedZimImage {
        val label = imageLabel(item.item)
        val path = item.item.path
        val score = queries.maxOfOrNull { SearchTerms.score(it, label, path) } ?: 0
        return RankedZimImage(ZimImage(id, path, label, null, null, title), score * 4)
    }

    private fun imageSearchPrefixes(queries: List<String>): List<String> {
        val stems = queries.flatMap {
            val words = SearchTerms.terms(it)
            listOf(words.take(3).joinToString("_"), words.firstOrNull().orEmpty())
        }.filter { it.length >= 3 }.distinct().take(3)
        return stems.flatMap { stem ->
            val capitalized = stem.replaceFirstChar { it.titlecase(Locale.ROOT) }
            listOf(stem, capitalized).distinct().flatMap { path ->
                listOf(path, "I/$path", "images/$path", "media/$path", "assets/$path")
            }
        }
    }

    private fun imageLabel(item: Item): String {
        val fallback = item.path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
        return cleanZimMetadata(item.title, 160)?.takeUnless { it == item.path || it == item.path.substringAfterLast('/') }
            ?: cleanZimMetadata(fallback, 160) ?: "Imagem do acervo"
    }

    private fun imageAt(path: String): ImageItem? = availableEntry {
        if (!validZimImagePath(path) || !archive.hasEntryByPath(path)) null
        else imageItem(archive.getEntryByPath(path))
    }

    private fun imageItem(entry: Entry): ImageItem? {
        val item = entry.getItem(true)
        val mimeType = zimImageMimeType(item.mimetype)
        val limit = zimImageByteLimits[mimeType] ?: return null
        if (!validZimImagePath(item.path) || item.size !in 1L..limit.toLong()) return null
        return ImageItem(item, mimeType)
    }

    private fun itemBytes(item: Item): ByteArray {
        val blob = item.data
        return try { blob.data } finally { blob.dispose() }
    }

    private inline fun <T> availableEntry(read: () -> T): T? = try {
        read()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    companion object {
        private const val MAX_ARTICLE_BYTES = 4L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 32L * 1024
        private const val MAX_IMAGE_ARTICLES = 8
        private const val MAX_IMG_TAGS_PER_ARTICLE = 80
        private const val MAX_ARTICLE_PREVIEW_IMAGES = 12
        private const val MAX_IMAGE_DIMENSION = 8192
        private const val MAX_IMAGE_PIXELS = 16_000_000L
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
