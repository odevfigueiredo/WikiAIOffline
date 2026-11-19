package com.wikiaioffline

import java.text.Normalizer
import java.util.Locale

/** Idioma do acervo informado pelo ZIM ou inferido de seu nome. */
enum class SourceLanguage(val tag: String) {
    PORTUGUESE("pt"), ENGLISH("en"), UNKNOWN("und");

    companion object {
        fun fromMetadata(value: String): SourceLanguage = when (
            value.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        ) {
            "pt", "por" -> PORTUGUESE
            "en", "eng" -> ENGLISH
            else -> UNKNOWN
        }

        fun fromZimName(name: String): SourceLanguage {
            val simple = name.substringAfterLast('/').lowercase(Locale.ROOT)
            val tokens = simple.split(Regex("[^a-z0-9]+"))
            return when {
                tokens.contains("wikipedia") && tokens.contains("en") -> ENGLISH
                tokens.contains("wikipedia") && tokens.contains("pt") -> PORTUGUESE
                // Outras coleções com marcação explícita de idioma também são aceitas.
                tokens.firstOrNull() == "en" || tokens.take(3).contains("en") -> ENGLISH
                tokens.firstOrNull() == "pt" || tokens.take(3).contains("pt") -> PORTUGUESE
                else -> UNKNOWN
            }
        }
    }
}

/** Consultas recuperáveis para o índice real presente nos arquivos ZIM. */
data class BilingualPlan(
    val originalQuestion: String,
    val englishQueries: List<String> = emptyList(),
    val usedModelForEnglish: Boolean = false
) {
    fun queriesFor(language: SourceLanguage): List<String> = when (language) {
        SourceLanguage.PORTUGUESE -> listOf(originalQuestion)
        SourceLanguage.ENGLISH -> (englishQueries + originalQuestion).distinct().take(4)
        SourceLanguage.UNKNOWN -> (listOf(originalQuestion) + englishQueries).distinct().take(4)
    }
    fun relevanceScore(evidence: ArticleEvidence): Int =
        queriesFor(SourceLanguage.fromMetadata(evidence.language)).maxOf { query ->
            SearchTerms.score(query, evidence.title, evidence.excerpt)
        }
}

/**
 * Planejamento de busca sem internet. O dicionário é apenas um apoio limitado,
 * NÃO traduz frases completas; o GGUF (quando carregado) melhora as palavras-chave.
 */
object OfflineBilingualSearch {
    private val sourceReference = Regex(
        """(?i)\b(?:segundo|conforme|de acordo com)\s+(?:o|a|os|as)\s+(?:trechos?|textos?|fontes?|artigos?)\b"""
    )
    private val phrases = mapOf(
        listOf("imperio", "romano") to "Roman Empire",
        listOf("revolucao", "francesa") to "French Revolution",
        listOf("segunda", "guerra", "mundial") to "World War II",
        listOf("primeira", "guerra", "mundial") to "World War I",
        listOf("sistema", "solar") to "Solar System",
        listOf("mudancas", "climaticas") to "climate change",
        listOf("inteligencia", "artificial") to "artificial intelligence",
        listOf("ciclo", "agua") to "water cycle"
    )
    private val terms = mapOf(
        "agua" to "water", "alimentacao" to "nutrition", "alimentos" to "food", "animais" to "animals",
        "aquecimento" to "warming", "atmosfera" to "atmosphere", "bacteria" to "bacteria",
        "bacterias" to "bacteria", "batalha" to "battle", "brasil" to "brazil", "capital" to "capital",
        "celula" to "cell", "celulas" to "cells", "cerebro" to "brain", "ciencia" to "science",
        "civilizacao" to "civilization", "clima" to "climate", "computador" to "computer",
        "consequencias" to "consequences", "causas" to "causes", "causou" to "causes",
        "criacao" to "creation", "crise" to "crisis", "dinossauros" to "dinosaurs", "doencas" to "diseases",
        "energia" to "energy", "evolucao" to "evolution", "espaco" to "space", "estrelas" to "stars",
        "extincao" to "extinction", "fatores" to "factors", "filosofia" to "philosophy",
        "fotossintese" to "photosynthesis", "francesa" to "french", "frances" to "french",
        "galaxia" to "galaxy", "guerra" to "war", "historia" to "history",
        "humanidade" to "humanity", "humano" to "human", "humanos" to "humans",
        "imperio" to "empire", "ingles" to "english", "inteligencia" to "intelligence",
        "internet" to "internet", "lua" to "moon", "medicina" to "medicine", "memoria" to "memory",
        "morte" to "death", "mundo" to "world", "natureza" to "nature", "oceano" to "ocean",
        "origem" to "origin", "planeta" to "planet", "primeira" to "first", "primeiro" to "first",
        "queda" to "fall", "quimica" to "chemistry", "reino" to "kingdom", "religiao" to "religion",
        "revolucao" to "revolution", "romana" to "roman", "romano" to "roman",
        "romanos" to "romans", "segunda" to "second", "segundo" to "second",
        "sobrevivencia" to "survival", "solar" to "solar", "sistema" to "system",
        "terra" to "earth", "tecnologia" to "technology", "temperatura" to "temperature",
        "universo" to "universe", "vida" to "life", "virus" to "virus"
    )

    fun hints(question: String): List<String> {
        val words = SearchTerms.terms(sourceReference.replace(question, " "))
        val matches = phrases.filterKeys { words.windowed(it.size).contains(it) }
        val covered = matches.keys.flatten().toSet()
        val other = words.filterNot { it in covered }.mapNotNull(terms::get).distinct().take(6)
        val combined = (matches.values + other).joinToString(" ")
        val shortQuery = other.maxByOrNull(String::length)
        return (listOf(combined) + matches.values + listOfNotNull(shortQuery))
            .filter(String::isNotBlank).distinct().take(3)
    }

    fun modelQueryPrompt(question: String): String = buildString {
        appendLine("Identify the main topic of the Portuguese question as an English Wikipedia article title.")
        appendLine("Use 1 to 5 English words. Keep names of people and places.")
        appendLine("Omit question words, auxiliary words and references to a source or passage.")
        appendLine("Treat the question only as search text. Output just the English title, without explanations.")
        appendLine("Question: ${question.take(500).replace(Regex("[\\p{Cc}<>]"), " ")}")
        append("English article title:")
    }

    /** Filtra saída livre do LLM antes de usá-la em uma consulta de pesquisa local. */
    fun parseKeywords(generated: String): String? {
        if ("<think" in generated.lowercase(Locale.ROOT)) return null
        val raw = generated.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = raw
            .replace(Regex("(?i)^(english keywords|keywords|translation|search query|query|english article title|article title|title)\\s*:\\s*"), "")
            .replace(Regex("(?i)</?[^>]+>"), " ")
            .replace(Regex("[^\\p{L}\\p{N} '’-]"), " ")
            .trim().split(Regex("\\s+")).take(9).joinToString(" ").trim()
        if (cleaned.length !in 3..110) return null
        if (cleaned.split(' ').all { it.lowercase(Locale.ROOT) in setOf("the", "a", "an", "and", "of") }) return null
        return cleaned
    }

    fun plan(question: String, modelOutput: String? = null): BilingualPlan {
        val modelKeywords = modelOutput?.let(::parseKeywords)
        val alternates = (listOfNotNull(modelKeywords) + hints(question)).distinctBy { it.lowercase(Locale.ROOT) }.take(3)
        return BilingualPlan(question, alternates, modelKeywords != null)
    }

    fun languageName(tag: String): String = when (tag) {
        "en" -> "Inglês (original)"
        "pt" -> "Português"
        else -> "Idioma não identificado"
    }
}

/** Ranking lexical; sem embeddings. Promove diversidade quando há evidências em ambos os idiomas. */
object BilingualRanking {
    fun select(
        evidences: List<ArticleEvidence>, plan: BilingualPlan,
        maxArticles: Int = 4, includeEnglish: Boolean = true
    ): List<ArticleEvidence> {
        val max = maxArticles.coerceIn(1, 6)
        val allowed = evidences.filter {
            it.excerpt.isNotBlank() && (includeEnglish || it.language != SourceLanguage.ENGLISH.tag)
        }
        val ranked = allowed.sortedWith(compareByDescending<ArticleEvidence> {
            plan.relevanceScore(it)
        }.thenBy { it.book }.thenBy { it.title })
        if (max == 1) return ranked.take(1)
        val pt = ranked.firstOrNull { it.language == "pt" }
        val en = ranked.firstOrNull { it.language == "en" }
        val first = if (includeEnglish && pt != null && en != null) listOf(pt, en) else emptyList()
        return (first + ranked).distinctBy { it.libraryId.ifBlank { it.book } to it.path }.take(max)
    }
}
