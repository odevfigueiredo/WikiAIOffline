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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WikiOperation(
    val label: String,
    val progress: Int? = null,
    val cancellable: Boolean = true,
    val answer: Boolean = false
)

internal data class ArticlePreview(
    val source: ArticleEvidence,
    val text: String? = null,
    val shortened: Boolean = false,
    val images: List<ZimImage> = emptyList()
)

class WikiViewModel(application: Application) : AndroidViewModel(application) {
    private val zim = MultiLibraryRepository(application)
    private val model = ModelRepository(application)
    private val queryEngine = WikiQueryEngine(zim, model, PortugueseLexicon {
        application.assets.open("english-portuguese.tsv")
    })
    private val history = ChatHistory(application)
    private val prefs = application.getSharedPreferences("wiki_preferences", 0)
    private var currentJob: Job? = null
    private var previewJob: Job? = null
    private var imageSearchJob: Job? = null
    private var progressKey: Any? = null
    private var lanServer: LanHttpServer? = null
    private var lanStartJob: Job? = null
    private var lanStopJob: Job? = null
    private var lanWatchJob: Job? = null
    private var lanRevision = 0L
    private val lanBackend = LanCatalogBackend(zim, queryEngine) {
        viewModelScope.launch {
            libraries = zim.libraries()
        }
    }

    internal var lanEndpoint by mutableStateOf<LanEndpoint?>(null)
        private set
    internal var lanStarting by mutableStateOf(false)
        private set
    internal var lanNotice by mutableStateOf<String?>(null)
        private set

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
    internal var imageResults by mutableStateOf(emptyList<ZimImage>())
        private set
    internal var imageSearching by mutableStateOf(false)
        private set
    internal var imageSearchStatus by mutableStateOf("")
        private set
    internal var imageSearchNotice by mutableStateOf<String?>(null)
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

    internal fun startLan() {
        if (lanEndpoint != null || lanStarting) return
        lanStarting = true
        lanNotice = null
        val revision = ++lanRevision
        val closing = lanStopJob
        lanStartJob = viewModelScope.launch {
            var candidate: LanHttpServer? = null
            try {
                closing?.join()
                val (target, endpoint) = withContext(Dispatchers.IO + NonCancellable) {
                    val context = getApplication<Application>()
                    val target = LanNetwork.targets(context).firstOrNull()
                        ?: error("Conecte o Android a uma rede Wi-Fi ou Ethernet privada com endereço IPv4.")
                    val server = LanHttpServer(target.address, lanBackend) { name -> context.assets.open(name) }
                    candidate = server
                    target to server.start()
                }
                if (revision != lanRevision) return@launch
                lanServer = candidate
                candidate = null
                lanEndpoint = endpoint
                lanWatchJob = viewModelScope.launch {
                    while (true) {
                        delay(2_000L)
                        if (target !in LanNetwork.targets(getApplication<Application>())) {
                            stopLan()
                            lanNotice = "A rede local mudou. Ative o acesso novamente quando estiver conectado."
                            break
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (revision == lanRevision) lanNotice = error.details()
            } finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    candidate?.stop()
                }
                if (revision == lanRevision) lanStarting = false
            }
        }
    }

    internal fun renewLanCode() {
        val server = lanServer ?: return
        runCatching { server.refreshCode() }.onSuccess {
            lanEndpoint = it
            lanNotice = null
        }.onFailure {
            lanNotice = "Não foi possível gerar um novo código."
        }
    }

    internal fun stopLan() {
        lanRevision++
        lanWatchJob?.cancel()
        val opening = lanStartJob
        opening?.cancel()
        lanStartJob = null
        val server = lanServer
        server?.revoke()
        lanServer = null
        lanEndpoint = null
        lanStarting = false
        if (server != null || opening?.isCompleted == false) {
            val closing = lanStopJob
            lanStopJob = CoroutineScope(Dispatchers.IO).launch {
                closing?.join()
                opening?.join()
                server?.stop()
            }
        }
    }

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
                val key = Any().also { progressKey = it }
                val status: (String, Int?) -> Unit = { label, progress ->
                    reportProgress(key, label, progress, answer = true)
                }
                val found = queryEngine.search(asked, limit, english, expandQuery = true, status = status)
                sources = found.articles
                val primary = sources.firstOrNull()
                if (primary != null) {
                    previewReply = reply.copy(
                        content = WikiRag.extractive(sources, excerptLimit = 190),
                        sources = sources
                    )
                }
                val content = if (primary == null) {
                    "Nenhum trecho relevante foi encontrado. Tente o título do artigo ou palavras-chave mais específicas."
                } else {
                    val synthesis = queryEngine.synthesize(asked, primary, status)
                    synthesis.answer ?: listOfNotNull(synthesis.notice, WikiRag.extractive(sources)).joinToString("\n\n")
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
            try {
                val entry = zim.readArticleDetails(source)
                val previewLimit = 48_000
                article = ArticlePreview(source, entry.text.take(previewLimit), entry.text.length > previewLimit, entry.images)
            } catch (e: Exception) {
                article = ArticlePreview(source, "Falha ao abrir a fonte. ${e.details()}")
            }
        }
    }

    internal fun closeArticle() {
        previewJob?.cancel()
        article = null
    }

    internal fun searchImages(query: String) {
        val text = query.trim().take(WikiRag.MAX_QUESTION_LENGTH)
        if (text.isEmpty() || imageSearching || !hasSearchableLibrary) return
        imageSearching = true
        imageResults = emptyList()
        imageSearchNotice = null
        imageSearchStatus = "Procurando imagens nos acervos"
        val english = includeEnglish
        imageSearchJob = viewModelScope.launch {
            try {
                val result = queryEngine.searchImages(text, english) { status, _ -> imageSearchStatus = status }
                imageResults = result.images
                imageSearchNotice = if (result.images.isEmpty()) {
                    "Nenhuma imagem encontrada. Tente outro assunto ou um acervo com imagens."
                } else result.notices.firstOrNull()
            } catch (cancelled: CancellationException) {
                imageSearchNotice = "Busca de imagens interrompida."
                throw cancelled
            } catch (_: Exception) {
                imageSearchNotice = "Não foi possível buscar imagens neste acervo."
            } finally {
                imageSearching = false
            }
        }
    }

    internal fun cancelImageSearch() { imageSearchJob?.cancel() }

    internal suspend fun loadImage(image: ZimImage, maxEdge: Int) =
        renderWikiImage(zim.readImage(image.libraryId, image.path), maxEdge)

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
        stopLan()
        val active = currentJob
        val preview = previewJob
        val imageSearch = imageSearchJob
        val closing = lanStopJob
        CoroutineScope(Dispatchers.IO).launch {
            closing?.join()
            active?.join()
            preview?.join()
            imageSearch?.join()
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
