package com.wikiaioffline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PortugueseLexiconTest {
    @Test fun usesPhraseAndThePortugueseSubjectOfAnEnglishQuestion() = runBlocking {
        val lexicon = PortugueseLexicon {
            """
            # termos de teste
            discovered|descoberto
            polonium|polônio|polónio
            steam|vapor
            engine|motor
            steam engine|máquina a vapor
            work|trabalho
            """.trimIndent().replace('|', '\t').byteInputStream()
        }
        assertEquals(listOf("polônio", "polónio"), lexicon.hints("Who discovered polonium?"))
        assertEquals(listOf("máquina a vapor"), lexicon.hints("How does a steam engine work?"))
        assertEquals(listOf("trabalho"), lexicon.hints("work"))
    }
}
