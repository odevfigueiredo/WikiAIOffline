package com.wikiaioffline

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

internal class LanCatalogBackend(
    private val library: MultiLibraryRepository,
    private val queryEngine: WikiQueryEngine,
    private val catalogChanged: () -> Unit
) : LanBackend {
    private val generation = Mutex()

    override suspend fun catalog(): JSONObject =
        JSONObject().put("libraries", JSONArray().apply {
            for (book in library.libraries()) {
                put(JSONObject()
                    .put("id", book.id)
                    .put("name", book.name)
                    .put("title", book.title)
                    .put("description", book.description ?: JSONObject.NULL)
                    .put("language", book.language.tag)
                    .put("edition", book.edition ?: JSONObject.NULL)
                    .put("date", book.date ?: JSONObject.NULL)
                    .put("available", book.available)
                    .put("localCopy", book.localCopy))
            }
        })

    override suspend fun search(query: String, includeEnglish: Boolean, withAnswer: Boolean): JSONObject {
        val busy = withAnswer && !generation.tryLock()
        return try {
            val found = queryEngine.search(
                query, limit = 5, includeEnglish = includeEnglish, expandQuery = withAnswer && !busy,
                translatePortuguese = !busy
            )
            val notices = found.notices.toMutableList()
            val first = found.articles.firstOrNull()
            val answer = when {
                busy -> {
                    notices.add("A IA local está preparando outra resposta. Tente novamente em instantes.")
                    null
                }
                withAnswer && first != null -> {
                    val result = queryEngine.synthesize(query, first)
                    result.notice?.let(notices::add)
                    if (result.answer == null && result.notice == null) {
                        notices.add("A síntese não ficou disponível. Os trechos originais continuam acessíveis.")
                    }
                    result.answer
                }
                else -> null
            }
            JSONObject()
                .put("sources", JSONArray().apply {
                    found.articles.forEach { article ->
                        put(JSONObject()
                            .put("libraryId", article.libraryId)
                            .put("path", article.path)
                            .put("title", article.title)
                            .put("excerpt", article.excerpt.take(650))
                            .put("book", article.book)
                            .put("language", article.language))
                    }
                })
                .put("notices", JSONArray(notices.take(5)))
                .put("answer", answer ?: JSONObject.NULL)
        } finally {
            if (withAnswer && !busy) generation.unlock()
        }
    }

    override suspend fun searchImages(query: String, includeEnglish: Boolean): JSONObject {
        val result = queryEngine.searchImages(query, includeEnglish)
        return JSONObject()
            .put("images", JSONArray(result.images.map(ZimImage::panelFields)))
            .put("notices", JSONArray(result.notices))
    }

    override suspend fun image(libraryId: String, path: String): ZimImageContent = try {
        library.readImage(libraryId, path)
    } catch (_: IOException) {
        throw LanApiException(404, "A imagem não está disponível neste acervo.")
    }

    override suspend fun article(libraryId: String, path: String): JSONObject {
        val source = ArticleEvidence(title = "", path = path, excerpt = "", libraryId = libraryId)
        val article = try {
            library.readArticleDetails(source)
        } catch (_: IOException) {
            throw LanApiException(404, "A fonte não está disponível neste acervo.")
        }
        return JSONObject()
            .put("title", article.title)
            .put("text", article.text.take(48_000))
            .put("shortened", article.text.length > 48_000)
            .put("images", JSONArray(article.images.map(ZimImage::panelFields)))
    }

    override suspend fun remove(id: String): JSONObject {
        try {
            if (!library.remove(id)) throw LanApiException(404, "O acervo já saiu do catálogo.")
            return JSONObject().put("removed", true)
        } catch (error: PendingLibraryCopyDeletionException) {
            return JSONObject().put("removed", true).put("notice", error.message)
        } finally {
            catalogChanged()
        }
    }
}

private fun ZimImage.panelFields() = JSONObject()
    .put("libraryId", libraryId)
    .put("path", path)
    .put("title", title)
    .put("articleTitle", articleTitle ?: JSONObject.NULL)
    .put("articlePath", articlePath ?: JSONObject.NULL)
    .put("book", book)
