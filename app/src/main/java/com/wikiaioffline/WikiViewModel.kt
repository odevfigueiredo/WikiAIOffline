package com.wikiaioffline

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WikiOperation(
    val label: String,
    val progress: Int? = null,
    val cancellable: Boolean = true,
    val answer: Boolean = false
)

internal data class ArticlePreview(
    val source: ArticleEvidence,
    val text: String? = null,
    val shortened: Boolean = false
)

class WikiViewModel(application: Application) : AndroidViewModel(application) {
    private val zim = MultiLibraryRepository(application)
    private val model = ModelRepository(application)
    private val history = ChatHistory(application)
    private val prefs = application.getSharedPreferences("wiki_preferences", 0)
    private var currentJob: Job? = null
    private var previewJob: Job? = null
    private var progressKey: Any? = null

    internal var historyUnreadable by mutableStateOf(false)
        private set

    internal var messages by mutableStateOf(emptyList<ChatMessage>())
        private set
    internal var previewReply by mutableStateOf<ChatMessage?>(null)
        private set
    internal var libraries by mutableStateOf(emptyList<ZimLibraryInfo>())
        private set
    internal var libraryNotices by mutableStateOf(emptyList<String>())
        private set
    internal var notice by mutableStateOf<String?>(null)
        private set
    internal var modelName by mutableStateOf<String?>(null)
        private set
    internal var hasSavedModel by mutableStateOf(false)
        private set
    internal var recentLibraryId by mutableStateOf<String?>(null)
        private set
    internal var operation by mutableStateOf<WikiOperation?>(null)
        private set
    internal var article by mutableStateOf<ArticlePreview?>(null)
        private set
    internal var includeEnglish by mutableStateOf(prefs.getBoolean("include_english", true))
        private set
    internal var maxSources by mutableIntStateOf(prefs.getInt("max_sources", 4).coerceIn(2, 6))
        private set
    internal val hasSearchableLibrary: Boolean
        get() = libraries.any { it.available && (includeEnglish || it.language != SourceLanguage.ENGLISH) }

    init {
        start(WikiOperation("Reabrindo o acervo e o histórico", cancellable = false)) {
            try {
                messages = withContext(Dispatchers.IO) { history.load() }
            } catch (e: Exception) {
                historyUnreadable = true
                notice = "Falha ao carregar o histórico. ${e.details()}"
            }
            libraryNotices = try {
                zim.restore()
            } catch (e: Exception) {
                listOf("Falha ao reabrir o acervo. ${e.details()}")
            }
            libraries = zim.libraries()
            model.restore()
        }
    }

    internal fun setSearchEnglish(value: Boolean) {
        includeEnglish = value
        prefs.edit().putBoolean("include_english", value).apply()
    }

    internal fun setSourceCount(value: Int) {
        maxSources = value.coerceIn(2, 6)
        prefs.edit().putInt("max_sources", maxSources).apply()
    }

    internal fun dismissNotice() { notice = null }

    internal fun addLibraries(uris: List<Uri>) {
        if (uris.isEmpty()) return
        start(WikiOperation("Abrindo arquivos ZIM")) {
            libraryNotices = emptyList()
            val chosen = uris.distinct()
            chosen.forEachIndexed { index, uri ->
                operation = WikiOperation("Abrindo ZIM ${index + 1} de ${chosen.size}")
                val key = Any().also { progressKey = it }
                val availableBefore = libraries.filter { it.available }.mapTo(HashSet()) { it.id }
                try {
                    zim.openOrCopy(uri) { progress ->
                        reportProgress(key, "Copiando ZIM ${index + 1} de ${chosen.size}", progress)
                    }
                    libraries = zim.libraries()
                    libraries.lastOrNull { it.available && it.id !in availableBefore }?.let {
                        recentLibraryId = it.id
                    }
                } catch (e: Exception) {
                    libraryNotices = libraryNotices + "ZIM ${index + 1}. ${e.details()}"
                }
            }
            notice = libraryNotices.firstOrNull()
        }
    }

    internal fun removeLibrary(id: String) = start(WikiOperation("Removendo ZIM", cancellable = false)) {
        zim.remove(id)
        libraryNotices = emptyList()
    }

    internal fun chooseModel(uri: Uri) = start(WikiOperation("Importando modelo GGUF")) {
        val key = Any().also { progressKey = it }
        model.importAndLoad(uri) { progress ->
            reportProgress(
                key,
                if (progress == 100) "Carregando o modelo em memória" else "Importando modelo GGUF",
                progress?.takeUnless { it == 100 }
            )
        }
    }

    internal fun removeModel() = start(WikiOperation("Removendo modelo GGUF", cancellable = false)) {
        model.remove()
    }

    internal fun clearHistory() = start(WikiOperation("Limpando o histórico", cancellable = false)) {
        withContext(Dispatchers.IO) { history.clear() }
        historyUnreadable = false
        messages = emptyList()
    }

    internal fun cancel() {
        if (operation?.cancellable == true) currentJob?.cancel()
    }

    internal fun ask(question: String) {
        val asked = question.trim().take(WikiRag.MAX_QUESTION_LENGTH)
        if (asked.isBlank() || operation != null || !hasSearchableLibrary) return
        val english = includeEnglish
        val limit = maxSources
        start(WikiOperation("Pesquisando no acervo", answer = true)) {
            val user = ChatMessage(mine = true, content = asked)
            val reply = ChatMessage(mine = false, content = "")
            var sources = emptyList<ArticleEvidence>()
            messages = messages.takeLast(98) + user
            try {
                var plan = OfflineBilingualSearch.plan(asked)
                var found = zim.search(plan, limit, english)
                if (found.articles.isEmpty() && english && zim.hasEnglishSource()) {
                    val translated = if (prepareModel()) translateQuery(asked) else null
                    if (translated != null) {
                        val retryPlan = OfflineBilingualSearch.plan(asked, translated)
                        if (retryPlan.englishQueries != plan.englishQueries) {
                            operation = WikiOperation("Pesquisando com termos em inglês", answer = true)
                            val retry = zim.search(retryPlan, limit, english)
                            if (retry.articles.isNotEmpty()) {
                                plan = retryPlan
                                found = retry
                            }
                        }
                    }
                }
                val primary = found.articles.maxByOrNull(plan::relevanceScore)
                if (primary != null) {
                    sources = listOf(primary) + found.articles.filterNot { it === primary }
                    previewReply = reply.copy(
                        content = WikiRag.extractive(sources, excerptLimit = 190),
                        sources = sources
                    )
                }
                val content = when {
                    primary == null -> "Nenhum trecho relevante foi encontrado. Tente o título do artigo ou palavras-chave mais específicas."
                    prepareModel() -> generateAnswer(asked, primary, sources)
                    else -> "A IA local não pôde iniciar.\n\n${WikiRag.extractive(sources)}"
                }
                previewReply = null
                messages = messages + reply.copy(content = addSearchNotices(content, found.notices), sources = sources)
            } catch (e: CancellationException) {
                val content = when {
                    sources.isEmpty() -> "Consulta interrompida."
                    else -> WikiRag.extractive(sources)
                }
                previewReply = null
                messages = messages + reply.copy(content = content, sources = sources)
                throw e
            } catch (e: Exception) {
                messages = messages + reply.copy(content = "Falha ao consultar o acervo. ${e.details()}")
            } finally {
                previewReply = null
                withContext(NonCancellable) { saveHistory() }
            }
        }
    }

    internal fun openArticle(source: ArticleEvidence) {
        closeArticle()
        article = ArticlePreview(source)
        previewJob = viewModelScope.launch {
            val text = try {
                zim.readArticle(source)
            } catch (e: Exception) {
                "Falha ao abrir a fonte. ${e.details()}"
            }
            val previewLimit = 48_000
            article = ArticlePreview(source, text.take(previewLimit), text.length > previewLimit)
        }
    }

    internal fun closeArticle() {
        previewJob?.cancel()
        article = null
    }

    private suspend fun prepareModel(): Boolean {
        if (model.isReady) return true
        val label = "Preparando a IA integrada"
        operation = WikiOperation(label, answer = true)
        val key = Any().also { progressKey = it }
        return try {
            model.ensureReady { percent ->
                reportProgress(key, label, percent?.takeUnless { it == 100 }, answer = true)
            }
            model.isReady
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            progressKey = null
        }
    }

    private suspend fun translateQuery(question: String): String? = try {
        operation = WikiOperation("Buscando o assunto em inglês", answer = true)
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
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun generateAnswer(
        question: String,
        primary: ArticleEvidence,
        sources: List<ArticleEvidence>
    ): String {
        operation = WikiOperation("Preparando a resposta em português", answer = true)
        val fallback = WikiRag.extractive(sources)
        return try {
            val generated = withTimeoutOrNull(90_000L) {
                val buffer = StringBuilder()
                model.answerStream(WikiRag.makeBriefPrompt(question, primary), maxTokens = 140)
                    .collect { buffer.append(it) }
                buffer.toString()
            }
            val validated = generated?.let(WikiRag::answerForSingleSource)
            when {
                validated != null -> validated
                generated == null -> "A IA excedeu o tempo de resposta.\n\n$fallback"
                else -> fallback
            }
        } catch (e: Exception) {
            "A IA falhou durante a geração. ${e.details()}\n\n$fallback"
        }
    }

    private suspend fun saveHistory() {
        if (historyUnreadable) return
        val snapshot = messages
        try {
            withContext(Dispatchers.IO) { history.save(snapshot) }
        } catch (e: Exception) {
            notice = "Falha ao salvar o histórico. ${e.details()}"
        }
    }

    private fun start(work: WikiOperation, block: suspend () -> Unit) {
        if (operation != null) return
        operation = work
        notice = null
        currentJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (!work.answer) notice = "Operação interrompida."
                throw e
            } catch (e: Exception) {
                notice = e.details()
            } finally {
                progressKey = null
                libraries = zim.libraries()
                modelName = model.modelName
                hasSavedModel = model.hasSavedModel
                operation = null
            }
        }
    }

    private fun reportProgress(key: Any, label: String, progress: Int?, answer: Boolean = false) {
        viewModelScope.launch {
            if (progressKey === key) operation = WikiOperation(label, progress, answer = answer)
        }
    }

    override fun onCleared() {
        val active = currentJob
        val preview = previewJob
        CoroutineScope(Dispatchers.IO).launch {
            active?.join()
            preview?.join()
            runCatching { zim.close() }
            runCatching { model.close() }
        }
        super.onCleared()
    }

    private fun Exception.details(): String {
        if (this is CancellationException) throw this
        return message?.take(300) ?: "Erro inesperado ao concluir a operação."
    }

    private fun addSearchNotices(content: String, notices: List<String>): String =
        if (notices.isEmpty()) content else content + "\n\n" + notices.take(4).joinToString("\n")
}
