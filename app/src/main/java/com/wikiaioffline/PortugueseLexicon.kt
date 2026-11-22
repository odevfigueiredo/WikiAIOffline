package com.wikiaioffline

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PortugueseLexicon(open: () -> InputStream) {
    private val dictionary by lazy {
        try {
            open().bufferedReader(Charsets.UTF_8).useLines { lines ->
                val entries = HashMap<String, MutableList<String>>()
                lines.filterNot { it.isBlank() || it.startsWith('#') }.forEach { line ->
                    val columns = line.split('\t', limit = 4)
                    val english = SearchTerms.query(columns.first())
                    if (english.isNotEmpty() && columns.size > 1) {
                        val variants = entries.getOrPut(english) { mutableListOf() }
                        for (translation in columns.drop(1).map(String::trim).filter(String::isNotEmpty)) {
                            if (variants.size < 3 && translation !in variants) variants.add(translation)
                        }
                    }
                }
                entries
            }
        } catch (_: IOException) {
            emptyMap()
        }
    }

    suspend fun hints(question: String): List<String> = withContext(Dispatchers.IO) {
        val input = SearchTerms.terms(question)
        val ignoreQuestionWords = input.size > 1 && questionStart.containsMatchIn(question)
        val matches = mutableListOf<Pair<String, List<String>>>()
        var cursor = 0
        while (cursor < input.size) {
            val phrases = (minOf(4, input.size - cursor) downTo 1).asSequence().map { width ->
                input.subList(cursor, cursor + width).joinToString(" ")
            }
            val match = phrases.firstNotNullOfOrNull { key ->
                if (ignoreQuestionWords && key in questionWords) null else dictionary[key]?.let { key to it }
            }
            if (match == null) {
                cursor++
            } else {
                matches.add(match)
                cursor += match.first.count { it == ' ' } + 1
            }
        }
        val main = matches.joinToString(" ") { it.second.first() }
        val alternatives = matches.sortedByDescending { it.first.length }.flatMap { it.second }
        (listOf(main) + alternatives).filter(String::isNotBlank).distinct().take(3)
    }

    private companion object {
        val questionStart = Regex("""(?i)^\s*(?:how|who|what|why|where|when|which|can|does|do|show me)\b""")
        val questionWords = setOf(
            "are", "can", "could", "did", "does", "explain", "tell", "show", "was", "were", "you",
            "discovered", "invented", "created", "called", "means", "work", "works"
        )
    }
}
