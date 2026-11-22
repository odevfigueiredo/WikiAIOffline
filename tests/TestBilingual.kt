import com.wikiaioffline.*

fun main() = checkBilingual()

fun checkBilingual() {
    check(SourceLanguage.fromZimName("wikipedia_en_all_maxi_2026-05.zim") == SourceLanguage.ENGLISH)
    check(SourceLanguage.fromZimName("wikipedia_pt_all_nopic_2026-05.zim") == SourceLanguage.PORTUGUESE)
    check(SourceLanguage.fromZimName("meus_documentos.zim") == SourceLanguage.UNKNOWN)
    check(SourceLanguage.fromMetadata("eng") == SourceLanguage.ENGLISH)
    check(SourceLanguage.fromMetadata("pt-BR") == SourceLanguage.PORTUGUESE)
    check(SourceLanguage.fromMetadata("es") == SourceLanguage.UNKNOWN)

    val fallback = OfflineBilingualSearch.plan("Quais as causas da queda do Império Romano?")
    check(fallback.englishQueries.any { "fall" in it && "Roman Empire" in it })
    check(!fallback.usedModelForEnglish)
    val modelPlan = OfflineBilingualSearch.plan("Explique a fotossíntese", "Photosynthesis plants sunlight")
    check(modelPlan.usedModelForEnglish)
    check(modelPlan.englishQueries.first() == "Photosynthesis plants sunlight")
    check(modelPlan.queriesFor(SourceLanguage.PORTUGUESE).size == 1)
    check(modelPlan.queriesFor(SourceLanguage.ENGLISH).any { it.contains("Photosynthesis") })
    check(OfflineBilingualSearch.parseKeywords("English keywords: Roman empire fall") == "Roman empire fall")
    check(OfflineBilingualSearch.parseKeywords("English article title: Photosynthesis") == "Photosynthesis")
    check(OfflineBilingualSearch.parseKeywords(" ") == null)
    check(OfflineBilingualSearch.parseKeywords("<think>draft</think>") == null)
    check(OfflineBilingualSearch.hints("O que foi a Segunda Guerra Mundial?").contains("World War II"))
    check(OfflineBilingualSearch.hints("O que foi o segundo reino romano?").any { "second" in it })
    check(OfflineBilingualSearch.hints("Quais dois materiais são usados na fotossíntese segundo o trecho?") == listOf("photosynthesis"))
    check("photosynthesis" in OfflineBilingualSearch.hints("Compare energia e fotossíntese"))
    check(OfflineBilingualSearch.plan("Newton").queriesFor(SourceLanguage.ENGLISH) == listOf("Newton"))
    val quadratic = OfflineBilingualSearch.plan("Show me the quadratic formula")
    check(quadratic.queriesFor(SourceLanguage.PORTUGUESE).first() == "fórmula de Bhaskara")
    check("equação do segundo grau" in quadratic.queriesFor(SourceLanguage.PORTUGUESE))
    check(quadratic.originalQuestion in quadratic.queriesFor(SourceLanguage.PORTUGUESE))
    check(OfflineBilingualSearch.plan("fórmula de Bhaskara").englishQueries.first() == "quadratic formula")
    check(OfflineBilingualSearch.looksEnglish("Who discovered polonium?"))
    check(!OfflineBilingualSearch.looksEnglish("Quem descobriu o polônio?"))
    val polonium = OfflineBilingualSearch.plan("Who discovered polonium?")
    val translatedPolonium = OfflineBilingualSearch.withPortugueseModel(polonium, "Portuguese title: Polônio")
    check(translatedPolonium.queriesFor(SourceLanguage.PORTUGUESE).first() == "Polônio")
    check(OfflineBilingualSearch.withPortugueseModel(polonium, "Who discovered polonium?") == polonium)
    val singlePortugueseSource = ArticleEvidence(
        "Equação do segundo grau", "quadratica", "Usamos a fórmula de Bhaskara.", language = "pt"
    )
    check(quadratic.relevanceScore(singlePortugueseSource) > 0)

    val evidence = listOf(
        ArticleEvidence("Queda do Império Romano", "A/queda", "A crise acelerou a queda do império romano.", "wikipedia_pt_all.zim", "pt"),
        ArticleEvidence("Fall of the Western Roman Empire", "A/Fall", "Fall of the Western Roman Empire and causes", "wikipedia_en_all.zim", "en"),
        ArticleEvidence("Roman Empire", "A/Roman_Empire", "Roman Empire history", "wikipedia_en_all.zim", "en")
    )
    val chosen = BilingualRanking.select(evidence, fallback, 2)
    check(chosen.size == 2)
    check(chosen.map { it.language }.toSet() == setOf("pt", "en"))
    check(BilingualRanking.select(evidence, fallback, 2, includeEnglish = false).none { it.language == "en" })
    val twins = listOf(evidence.first().copy(libraryId = "a"), evidence.first().copy(libraryId = "b"))
    check(BilingualRanking.select(twins, fallback).size == 2)
    check(BilingualRanking.select(twins.map { it.copy(excerpt = " ") }, fallback).isEmpty())
    val english = chosen.single { it.language == "en" }
    val prompt = WikiRag.makeBriefPrompt("Por que caiu Roma?", english)
    check(prompt.contains("Trecho em inglês: ${english.excerpt}"))
    check(prompt.contains("uma única frase em português brasileiro"))
    check(chosen.single { it.language == "pt" }.excerpt !in prompt)
    val raw = WikiRag.extractive(chosen)
    check(raw.contains("as fontes em inglês aparecem no idioma original"))
    println("OK: busca bilíngue, metadados, glossário, consultas, ranqueamento e modo sem GGUF")
}
