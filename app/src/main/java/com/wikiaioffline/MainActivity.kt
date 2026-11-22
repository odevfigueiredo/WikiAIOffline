package com.wikiaioffline

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import kotlin.math.roundToInt

private val Ink = Color(0xFF101219)
private val Panel = Color(0xFF1A1D27)
private val TextMain = Color(0xFFF4F1F0)
private val Muted = Color(0xFFB6BBC8)
private val Accent = Color(0xFFFF777E)
private val Good = Color(0xFF98D8B2)

private class OpenLocalDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class OpenLocalDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

class MainActivity : ComponentActivity() {
    private lateinit var wiki: WikiViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        wiki = ViewModelProvider(this)[WikiViewModel::class.java]
        setContent {
            val sharing = wiki.lanEndpoint != null
            LaunchedEffect(sharing) {
                val screenFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (sharing) window.addFlags(screenFlag) else window.clearFlags(screenFlag)
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF25070E), background = Ink,
                    surface = Panel, onSurface = TextMain, onSurfaceVariant = Muted
                )
            ) {
                WikiApp(wiki)
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) wiki.stopLan()
        super.onStop()
    }
}

@Composable
private fun WikiApp(wiki: WikiViewModel) {
    var question by rememberSaveable { mutableStateOf("") }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var showLan by rememberSaveable { mutableStateOf(false) }
    var showImages by rememberSaveable { mutableStateOf(false) }
    var imageTarget by remember { mutableStateOf<WikiImageTarget?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    val openZims = rememberLauncherForActivityResult(remember { OpenLocalDocuments() }) {
        wiki.addLibraries(it)
    }
    val selectModel = rememberLauncherForActivityResult(remember { OpenLocalDocument() }) {
        if (it != null) wiki.chooseModel(it)
    }
    val selectImage = rememberLauncherForActivityResult(remember { OpenLocalDocument() }) {
        if (it != null) imageTarget = WikiImageTarget.Device(it)
    }
    val scroll = rememberLazyListState()
    val work = wiki.operation
    val hasZim = wiki.hasSearchableLibrary
    val books = wiki.libraries.filter { it.available }
    val currentBook = books.find { it.id == wiki.recentLibraryId } ?: books.lastOrNull()
    val visibleMessages = wiki.messages + listOfNotNull(wiki.previewReply)
    LaunchedEffect(visibleMessages.lastOrNull()?.id) {
        if (visibleMessages.isNotEmpty()) scroll.scrollToItem(visibleMessages.lastIndex)
    }

    Scaffold(containerColor = Ink) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.AutoStories, null, tint = Accent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("WikiAI Offline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("IA offline integrada", style = MaterialTheme.typography.labelMedium, color = Good)
                }
                IconButton(onClick = { showLibrary = true }) {
                    Icon(Icons.Filled.FolderOpen, "Gerenciar acervo", tint = Accent)
                }
                IconButton(onClick = { showLan = true }) {
                    Icon(Icons.Filled.Wifi, "Acesso pela rede", tint = if (wiki.lanEndpoint != null) Good else Muted)
                }
                IconButton(
                    enabled = (wiki.messages.isNotEmpty() || wiki.historyUnreadable) && work == null,
                    onClick = {
                        confirm = Confirmation("Limpar histórico?", "As conversas salvas neste aparelho serão apagadas.") {
                            wiki.clearHistory()
                        }
                    }
                ) {
                    Icon(Icons.Filled.Delete, "Limpar histórico")
                }
            }
            HorizontalDivider(color = Color(0xFF2E3240))
            if (currentBook != null) {
                Surface(
                    onClick = { showLibrary = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Panel
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            currentBook.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                        currentBook.description?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (books.size > 1) {
                            Text("${books.size} acervos abertos", color = Good, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else if (visibleMessages.isNotEmpty()) {
                Button(
                    onClick = { openZims.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    enabled = work == null
                ) { Text("Abrir arquivo ZIM") }
            }
            if (wiki.historyUnreadable) {
                Text(
                    "O histórico salvo não pôde ser lido. Use a lixeira para voltar a gravar conversas.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Accent,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            wiki.notice?.let { notice ->
                Row(
                    Modifier.fillMaxWidth().background(Panel).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(notice, Modifier.weight(1f), color = Accent, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = wiki::dismissNotice) { Icon(Icons.Filled.Close, "Fechar aviso") }
                }
            }
            if (visibleMessages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 370.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.AutoStories, null, tint = Accent, modifier = Modifier.size(46.dp))
                        if (hasZim) {
                            Text("Seu acervo está pronto", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Faça uma pergunta sobre o conteúdo do ZIM.",
                                color = Muted
                            )
                        } else {
                            Text("Abra seu acervo offline", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                if (books.isNotEmpty()) "Ative os acervos em inglês ou abra um ZIM em português."
                                else "Escolha um arquivo .zim do aparelho. Se o Android exigir, o app fará uma cópia local.",
                                color = Muted
                            )
                            Button(onClick = { openZims.launch(arrayOf("*/*")) }, enabled = work == null) {
                                Text("Abrir arquivo ZIM")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = scroll,
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleMessages, key = { it.id }) { message ->
                        ChatBubble(message, wiki::openArticle)
                    }
                }
            }
            if (work != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    Text(work.label, color = Muted, style = MaterialTheme.typography.bodySmall)
                    WorkProgress(work, Modifier.fillMaxWidth().padding(top = 5.dp))
                    if (!work.answer && work.cancellable) {
                        TextButton(onClick = wiki::cancel) { Text("Cancelar") }
                    }
                }
            }
            TextButton(onClick = { showImages = true }, modifier = Modifier.padding(start = 14.dp)) {
                Icon(Icons.Filled.Collections, null, modifier = Modifier.size(18.dp))
                Text("Imagens", Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(WikiRag.MAX_QUESTION_LENGTH) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Pergunte ao acervo") },
                    enabled = hasZim && work == null,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp)
                )
                val answering = work?.answer == true
                IconButton(
                    enabled = answering || (hasZim && work == null && question.isNotBlank()),
                    onClick = {
                        if (answering) wiki.cancel() else {
                            wiki.ask(question)
                            question = ""
                        }
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        if (answering) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        if (answering) "Interromper resposta" else "Enviar pergunta"
                    )
                }
            }
        }
    }

    if (showLibrary) {
        LibraryDialog(
            wiki = wiki,
            onClose = { showLibrary = false },
            openZim = {
                showLibrary = false
                openZims.launch(arrayOf("*/*"))
            },
            openModel = { selectModel.launch(arrayOf("*/*")) },
            confirm = { confirm = it }
        )
    }
    if (showLan) LanAccessDialog(wiki, onClose = { showLan = false })
    if (showImages) {
        WikiImageSearchDialog(
            wiki, onPick = { selectImage.launch(arrayOf("image/*")) },
            onPreview = { imageTarget = WikiImageTarget.Archive(it) },
            onClose = { showImages = false; wiki.cancelImageSearch() }
        )
    }
    wiki.article?.let { preview ->
        ArticleDialog(preview, onImage = { imageTarget = WikiImageTarget.Archive(it) }, onClose = wiki::closeArticle)
    }
    imageTarget?.let { target ->
        WikiImagePreviewDialog(wiki, target, onOpenArticle = { source ->
            imageTarget = null
            showImages = false
            wiki.openArticle(source)
        }, onClose = { imageTarget = null })
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(item.title) },
            text = { Text(item.message) },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(onClick = { confirm = null; item.action() }) { Text("Confirmar") }
            }
        )
    }
}

private data class Confirmation(val title: String, val message: String, val action: () -> Unit)

@Composable
private fun LibraryDialog(
    wiki: WikiViewModel,
    onClose: () -> Unit,
    openZim: () -> Unit,
    openModel: () -> Unit,
    confirm: (Confirmation) -> Unit
) {
    val idle = wiki.operation == null
    var advanced by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(22.dp), color = Panel) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Acervo offline", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fechar acervo") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Arquivos ZIM", fontWeight = FontWeight.SemiBold)
                    Button(onClick = openZim, enabled = idle) {
                        Text("Abrir arquivo ZIM")
                    }
                    for (book in wiki.libraries) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.SemiBold)
                                book.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                                if (book.title != book.name) {
                                    Text(book.name, style = MaterialTheme.typography.labelSmall, color = Muted)
                                }
                                val status = when {
                                    !book.available -> "Indisponível"
                                    book.localCopy -> "Cópia no aplicativo"
                                    else -> "Arquivo original"
                                }
                                Text(
                                    listOfNotNull(
                                        OfflineBilingualSearch.languageName(book.language.tag), book.edition, book.date, status
                                    ).joinToString(" · "),
                                    color = if (book.available) Good else Accent,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            IconButton(
                                enabled = idle,
                                onClick = {
                                    confirm(Confirmation(
                                        "Remover ZIM?",
                                        if (book.localCopy) "A cópia de ${book.name} será apagada do aplicativo."
                                        else "${book.name} sairá do acervo. O arquivo original será mantido."
                                    ) { wiki.removeLibrary(book.id) })
                                }
                            ) { Icon(Icons.Filled.Delete, "Remover ${book.name}") }
                        }
                    }
                    wiki.libraryNotices.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = wiki.includeEnglish,
                            role = Role.Switch,
                            onValueChange = wiki::setSearchEnglish
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Incluir acervos em inglês", Modifier.weight(1f))
                        Switch(checked = wiki.includeEnglish, onCheckedChange = null)
                    }
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Ocultar opções avançadas" else "Opções avançadas")
                    }
                    if (advanced) {
                        Text("Resultados por pergunta · ${wiki.maxSources}", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = wiki.maxSources.toFloat(),
                            onValueChange = { wiki.setSourceCount(it.roundToInt()) },
                            valueRange = 2f..6f,
                            steps = 3
                        )
                        Text("Modelo alternativo", fontWeight = FontWeight.SemiBold)
                        Text(
                            wiki.modelName?.let { "Modelo em uso: $it" } ?: "A IA integrada carrega ao pesquisar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = openModel, enabled = idle, modifier = Modifier.weight(1f)) {
                                Text(if (wiki.hasSavedModel) "Trocar GGUF" else "Usar outro GGUF")
                            }
                            if (wiki.hasSavedModel) {
                                IconButton(enabled = idle, onClick = {
                                    confirm(Confirmation(
                                        "Voltar à IA integrada?",
                                        "A cópia do modelo alternativo será apagada."
                                    ) { wiki.removeModel() })
                                }) { Icon(Icons.Filled.Delete, "Remover modelo alternativo") }
                            }
                        }
                    }
                }
                wiki.notice?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
                wiki.operation?.let { work ->
                    Text(work.label, color = Muted, style = MaterialTheme.typography.bodySmall)
                    WorkProgress(work, Modifier.fillMaxWidth().padding(top = 4.dp))
                    if (work.cancellable) TextButton(onClick = wiki::cancel) { Text("Cancelar") }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, openSource: (ArticleEvidence) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            Modifier.fillMaxWidth(if (message.mine) 0.87f else 1f),
            color = if (message.mine) Color(0xFF4D242E) else Panel,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
                message.sources.forEachIndexed { index, source ->
                    TextButton(onClick = { openSource(source) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("[${index + 1}] ${source.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${source.book} · ${OfflineBilingualSearch.languageName(source.language)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleDialog(preview: ArticlePreview, onImage: (ZimImage) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(preview.source.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            val content = preview.text
            val paragraphs = remember(content) {
                content?.split("\n\n")?.filter(String::isNotBlank)?.flatMap { it.chunked(3_000) }.orEmpty()
            }
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(preview.source.book, style = MaterialTheme.typography.labelMedium, color = Muted)
                    SelectionContainer {
                        Text(preview.source.path, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
                if (content == null) {
                    item { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                items(paragraphs) { paragraph ->
                    SelectionContainer { Text(paragraph) }
                }
                if (preview.shortened) {
                    item { Text("A prévia foi abreviada. Consulte o ZIM no Kiwix para ler o restante.", color = Muted) }
                }
                if (preview.images.isNotEmpty()) {
                    item { Text("Imagens da fonte", style = MaterialTheme.typography.titleSmall) }
                    items(preview.images) { image ->
                        TextButton(onClick = { onImage(image) }) { Text(image.title) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } }
    )
}

@Composable
private fun WorkProgress(work: WikiOperation, modifier: Modifier) {
    val progress = work.progress
    if (progress == null) LinearProgressIndicator(modifier)
    else LinearProgressIndicator(progress = { progress / 100f }, modifier = modifier)
}
