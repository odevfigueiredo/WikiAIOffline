package com.wikiaioffline

object WikiRag {
    const val MAX_QUESTION_LENGTH = 600
    private const val MAX_EVIDENCE_CHARS = 850
    private val citation = Regex("\\[(\\d[\\d\\s,;–-]*)]")
    private val citationSeparator = Regex("[,;\\s]+")
    private val reasoning = Regex("(?is)<think>.*?</think>")
    private val reasoningTag = Regex("(?i)</?think\\b")
    private val answerPrefix = Regex("(?i)^resposta(?: em portugu[eê]s(?: do brasil| brasileiro)?)?\\s*:\\s*")
    private val danglingReference = Regex("\\[\\s*[0-9]")

    fun makeBriefPrompt(question: String, evidence: ArticleEvidence): String = buildString {
        require(evidence.excerpt.isNotBlank())
        val label = when (evidence.language) {
            "en" -> "Trecho em inglês"
            "pt" -> "Trecho em português"
            else -> "Trecho original"
        }
        appendLine("$label: ${evidence.excerpt.take(MAX_EVIDENCE_CHARS).asData()}")
        appendLine("Pergunta: ${question.take(MAX_QUESTION_LENGTH).asData()}")
        append("Responda à pergunta em uma única frase em português brasileiro, usando somente o trecho. ")
        append("Escreva só a resposta final, sem rótulo.")
    }

    /** A associação a [1] identifica o único trecho fornecido ao modelo. */
    fun answerForSingleSource(generated: String, question: String = ""): String? {
        val text = answerPrefix.replaceFirst(reasoning.replace(generated, "").trim(), "")
            .trim().removeSurrounding("\"")
        val prose = citation.replace(text, "")
        if (text.lineSequence().count(String::isNotBlank) != 1 ||
            reasoningTag.containsMatchIn(text) || danglingReference.containsMatchIn(prose) ||
            prose.none(Char::isLetterOrDigit)) return null
        val answerTerms = SearchTerms.terms(prose)
        val questionTerms = SearchTerms.terms(question)
        if (answerTerms.isNotEmpty() && answerTerms.all { it in questionTerms }) return null
        val cited = citation.findAll(text).flatMap { match ->
            match.groupValues[1].trim().split(citationSeparator).asSequence().map(String::toIntOrNull)
        }.toList()
        return when {
            cited.isEmpty() -> "$text [1]"
            cited.all { it == 1 } -> text
            else -> null
        }
    }

    fun extractive(evidences: List<ArticleEvidence>, excerptLimit: Int = 650): String = buildString {
        append("Trechos originais encontrados")
        if (evidences.any { it.language == "en" }) {
            append(" · as fontes em inglês aparecem no idioma original")
        }
        evidences.take(6).forEachIndexed { index, evidence ->
            append("\n\n[${index + 1}] ${evidence.title}\n")
            append(evidence.excerpt.take(excerptLimit))
            if (evidence.excerpt.length > excerptLimit) append("…")
        }
    }

    private fun String.asData(): String = buildString {
        for (char in this@asData) {
            append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '[' -> "&#91;"
                ']' -> "&#93;"
                else -> if (char.isISOControl()) " " else char.toString()
            })
        }
    }
}
