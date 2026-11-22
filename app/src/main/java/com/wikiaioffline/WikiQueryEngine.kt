package com.wikiaioffline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

internal class WikiQueryEngine(
    private val libraries: MultiLibraryRepository,
    private val model: ModelRepository
) {
    data class Synthesis(val answer: String? = null, val notice: String? = null)

    suspend fun search(
        question: String,
        limit: Int,
        includeEnglish: Boolean,
        expandQuery: Boolean,
        status: (String, Int?) -> Unit = { _, _ -> }
    ): RetrievalResult {
        var plan = OfflineBilingualSearch.plan(question)
        var result = libraries.search(plan, limit, includeEnglish)
        if (result.articles.isEmpty() && expandQuery && includeEnglish && libraries.hasEnglishSource()) {
            val translated = if (prepareModel(status)) translate(question, status) else null
            if (translated != null) {
                val retryPlan = OfflineBilingualSearch.plan(question, translated)
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
        return result.copy(articles = listOf(first) + result.articles.filterNot { it === first })
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

    private suspend fun translate(question: String, status: (String, Int?) -> Unit): String? {
        status("Buscando o assunto em inglês", null)
        return try {
            withTimeoutOrNull(25_000L) {
                val buffer = StringBuilder()
                model.answerStream(OfflineBilingualSearch.modelQueryPrompt(question), maxTokens = 28)
                    .takeWhile { token ->
                        buffer.append(token.take(240 - buffer.length))
                        buffer.length < 240 && buffer.trimStart().indexOf('\n') < 0
                    }
                    .collect()
                buffer.toString()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
