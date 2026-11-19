package com.wikiaioffline

import java.text.Normalizer
import java.util.Locale

/** Trecho e referência da entrada original em um acervo ZIM. */
data class ArticleEvidence(
    val title: String,
    val path: String,
    val excerpt: String,
    val book: String = "",
    val language: String = "und",
    val libraryId: String = ""
)

data class RetrievalResult(
    val articles: List<ArticleEvidence>,
    val usedFullText: Boolean,
    val notices: List<String> = emptyList()
)

/** Normaliza perguntas naturais antes de consultar o índice de texto completo. */
object SearchTerms {
    private val stop = setOf(
        "que", "qual", "quais", "quem", "onde", "como", "quando", "porque", "por", "para", "com",
        "uma", "uns", "umas", "sobre", "voce", "pode", "me", "explicar", "explique",
        "resuma", "fale", "diga", "conte", "quero", "saber", "traduza", "traduzir", "trecho", "trechos",
        "dos", "das", "sao", "era", "foi", "foram", "do", "da", "de", "no", "na", "ao", "se",
        "as", "os", "o", "a", "um", "e", "em", "and", "the", "is", "of", "to", "in", "an",
        "by", "at", "on", "or", "for", "with", "about", "who", "what", "when", "why", "where", "how"
    )
    private val marks = Regex("\\p{M}+")
    private val word = Regex("[\\p{L}\\p{N}]+")

    fun terms(text: String): List<String> = termSequence(text).distinct().take(10).toList()

    fun query(text: String): String = terms(text).take(8).joinToString(" ")

    fun score(question: String, title: String, excerpt: String): Int {
        val questionTerms = terms(question)
        if (questionTerms.isEmpty()) return 0
        val titleTokens = termSequence(title).toList()
        val titleTerms = titleTokens.toSet()
        val excerptTerms = termSequence(excerpt).toSet()
        val titleMatches = questionTerms.count { it in titleTerms }
        val excerptMatches = questionTerms.count { it in excerptTerms }
        val exactTitle = if (questionTerms == titleTokens) 12 else 0
        return exactTitle + ((8 * titleMatches + 2 * excerptMatches) * 10 / questionTerms.size)
    }

    private fun termSequence(text: String): Sequence<String> {
        val normalized = marks.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
        return word.findAll(normalized).map { it.value }.filter {
            (it.length >= 2 || it.singleOrNull()?.isDigit() == true) && it !in stop
        }
    }
}
