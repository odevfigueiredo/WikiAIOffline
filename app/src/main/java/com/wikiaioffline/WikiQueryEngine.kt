package com.wikiaioffline

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal class WikiQueryEngine(
    private val libraries: MultiLibraryRepository,
    private val model: ModelRepository,
    private val lexicon: PortugueseLexicon
) {
    data class Synthesis(val answer: String? = null, val notice: String? = null)
    data class ImageSearch(val images: List<ZimImage>, val notices: List<String>)

    private val portugueseTitles = LruCache<String, String>(24)
    private val translation = Mutex()

    suspend fun search(
        question: String,
        limit: Int,
        includeEnglish: Boolean,
        expandQuery: Boolean,
        translatePortuguese: Boolean = true,
        status: (String, Int?) -> Unit = { _, _ -> }
    ): RetrievalResult {
        var plan = OfflineBilingualSearch.plan(question)
        var result = libraries.search(plan, limit, includeEnglish)
        val english = OfflineBilingualSearch.looksEnglish(question)
        var translatedByFallback = false
        if (translatePortuguese && (english || result.articles.isEmpty()) &&
            !result.articles.any { it.language == SourceLanguage.PORTUGUESE.tag }) {
            retryPortuguese(plan, status, { libraries.search(it, limit, includeEnglish) }) { found ->
                found.articles.any { it.language != SourceLanguage.ENGLISH.tag }
            }?.let { (expanded, found) ->
                plan = expanded
                result = found
                translatedByFallback = true
            }
        }
        if (result.articles.isEmpty() && !english && expandQuery && includeEnglish && libraries.hasEnglishSource()) {
            val translated = if (prepareModel(status)) {
                status("Buscando o assunto em inglês", null)
                withTimeoutOrNull(25_000L) { translate(OfflineBilingualSearch.modelQueryPrompt(question)) }
            } else null
            if (translated != null) {
                val fallback = OfflineBilingualSearch.plan(question, translated)
                val retryPlan = plan.copy(englishQueries = fallback.englishQueries, usedModelForEnglish = fallback.usedModelForEnglish)
                if (retryPlan.englishQueries != plan.englishQueries) {
                    status("Pesquisando com termos em inglês", null)
                    val retry = libraries.search(retryPlan, limit, includeEnglish)
                    if (retry.articles.isNotEmpty()) {
                        plan = retryPlan
                        result = retry
                    }
                }
            }
        }
        val first = result.articles.maxByOrNull(plan::relevanceScore) ?: return result
        val translated = portugueseNotice(plan).takeIf {
            (english || translatedByFallback) && result.articles.any { it.language != SourceLanguage.ENGLISH.tag }
        }
        return result.copy(
            articles = listOf(first) + result.articles.filterNot { it === first },
            notices = listOfNotNull(translated) + result.notices
        )
    }

    suspend fun searchImages(
        question: String,
        includeEnglish: Boolean,
        status: (String, Int?) -> Unit = { _, _ -> }
    ): ImageSearch {
        val books = libraries.libraries()
        val portugueseIds = books.filter { it.available && it.language != SourceLanguage.ENGLISH }.map { it.id }.toSet()
        var plan = OfflineBilingualSearch.plan(question)
        var images = libraries.searchImages(plan, includeEnglish = includeEnglish)
        val english = OfflineBilingualSearch.looksEnglish(question)
        var translatedByFallback = false
        if ((english || images.isEmpty()) && images.none { it.libraryId in portugueseIds }) {
            retryPortuguese(plan, status, { libraries.searchImages(it, includeEnglish = includeEnglish) }) { found ->
                found.any { it.libraryId in portugueseIds }
            }?.let { (expanded, found) ->
                plan = expanded
                images = found
                translatedByFallback = true
            }
        }
        val translated = portugueseNotice(plan).takeIf { (english || translatedByFallback) && images.any { it.libraryId in portugueseIds } }
        return ImageSearch(images, listOfNotNull(translated))
    }

    private fun portugueseNotice(plan: BilingualPlan): String? =
        plan.portugueseQueries.firstOrNull()?.let { "Termos usados na busca em português: $it" }

    private suspend fun <T> retryPortuguese(
        plan: BilingualPlan,
        status: (String, Int?) -> Unit,
        search: suspend (BilingualPlan) -> T,
        hasPortuguese: (T) -> Boolean
    ): Pair<BilingualPlan, T>? {
        if (libraries.libraries().none { it.available && it.language != SourceLanguage.ENGLISH }) return null
        val dictionary = OfflineBilingualSearch.withPortugueseKeywords(plan, lexicon.hints(plan.originalQuestion))
        if (dictionary != plan) {
            val result = search(dictionary)
            if (hasPortuguese(result)) return dictionary to result
        }
        val model = expandPortuguese(dictionary, status)
        if (model != dictionary) {
            val result = search(model)
            if (hasPortuguese(result)) return model to result
        }
        return null
    }

    private suspend fun expandPortuguese(plan: BilingualPlan, status: (String, Int?) -> Unit): BilingualPlan {
        val key = plan.originalQuestion
        portugueseTitles.get(key)?.let { return OfflineBilingualSearch.withPortugueseModel(plan, it) }
        if (!translation.tryLock()) return plan
        return try {
            withTimeoutOrNull(15_000L) {
                if (!prepareModel(status)) return@withTimeoutOrNull plan
                status("Traduzindo a consulta para português", null)
                val title = translate(OfflineBilingualSearch.portugueseQueryPrompt(key)) ?: return@withTimeoutOrNull plan
                OfflineBilingualSearch.withPortugueseModel(plan, title).also { retry ->
                    if (retry != plan) portugueseTitles.put(key, title)
                }
            } ?: plan
        } finally {
            translation.unlock()
        }
    }

    suspend fun synthesize(
        question: String,
        source: ArticleEvidence,
        status: (String, Int?) -> Unit = { _, _ -> }
    ): Synthesis {
        if (!prepareModel(status)) return Synthesis(notice = "A IA local não pôde iniciar.")
        status("Preparando a resposta em português", null)
        return try {
            val generated = withTimeoutOrNull(90_000L) {
                val buffer = StringBuilder()
                model.answerStream(WikiRag.makeBriefPrompt(question, source), maxTokens = 140)
                    .collect { buffer.append(it) }
                buffer.toString()
            } ?: return Synthesis(notice = "A IA excedeu o tempo de resposta.")
            Synthesis(answer = WikiRag.answerForSingleSource(generated, question))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Synthesis(notice = "A IA local encontrou um erro durante a geração.")
        }
    }

    private suspend fun prepareModel(status: (String, Int?) -> Unit): Boolean {
        if (model.isReady) return true
        val label = "Preparando a IA integrada"
        status(label, null)
        return try {
            model.ensureReady { percent -> status(label, percent?.takeUnless { it == 100 }) }
            model.isReady
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun translate(prompt: String): String? {
        return try {
            val buffer = StringBuilder()
            model.answerStream(prompt, maxTokens = 28)
                .takeWhile { token ->
                    buffer.append(token.take(240 - buffer.length))
                    buffer.length < 240 && buffer.trimStart().indexOf('\n') < 0
                }
                .collect()
            buffer.toString()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
