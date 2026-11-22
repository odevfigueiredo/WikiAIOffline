package com.wikiaioffline

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException

internal sealed interface WikiImageTarget {
    data class Archive(val image: ZimImage) : WikiImageTarget
    data class Device(val uri: Uri) : WikiImageTarget
}

@Composable
internal fun WikiImageSearchDialog(
    wiki: WikiViewModel,
    onPick: () -> Unit,
    onPreview: (ZimImage) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val canSearch = wiki.hasSearchableLibrary && !wiki.imageSearching && query.isNotBlank()
        val search = {
            if (canSearch) {
                keyboard?.hide()
                focus.clearFocus()
                wiki.searchImages(query)
            }
        }
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Imagens do acervo", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fechar busca de imagens") }
                }
                OutlinedTextField(
                    query, { query = it.take(WikiRag.MAX_QUESTION_LENGTH) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Assunto da imagem") },
                    placeholder = { Text("Ex.: fórmula de Bhaskara") },
                    singleLine = true,
                    enabled = wiki.hasSearchableLibrary,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() })
                )
                Button(onClick = search, enabled = canSearch) { Text("Buscar imagens") }
                if (wiki.imageSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(wiki.imageSearchStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = wiki::cancelImageSearch) { Text("Cancelar") }
                    }
                }
                if (!wiki.hasSearchableLibrary) {
                    Text("Abra um acervo ZIM para pesquisar imagens.", style = MaterialTheme.typography.bodySmall)
                }
                wiki.imageSearchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (wiki.imageResults.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(wiki.imageResults, key = { it.libraryId + ":" + it.path }) { image ->
                            Surface(onClick = { onPreview(image) }, shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    WikiImageThumbnail(wiki, image, Modifier.size(72.dp))
                                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                        Text(image.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(image.articleTitle ?: image.book, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onPick) { Text("Abrir imagem do dispositivo") }
            }
        }
    }
}

@Composable
internal fun WikiImagePreviewDialog(
    wiki: WikiViewModel,
    target: WikiImageTarget,
    onOpenArticle: (ArticleEvidence) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val image = (target as? WikiImageTarget.Archive)?.image
    val title = image?.title ?: "Imagem do dispositivo"
    val article = image?.articleEvidence()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WikiLoadedImage(target, title, Modifier.fillMaxWidth().height(240.dp)) {
                    when (target) {
                        is WikiImageTarget.Archive -> wiki.loadImage(target.image, 1600)
                        is WikiImageTarget.Device -> renderWikiImage(readDeviceImage(context.contentResolver, target.uri), 1600)
                    }
                }
                image?.let { Text(it.book, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = {
            if (article != null) TextButton(onClick = { onOpenArticle(article) }) { Text("Abrir artigo de origem") }
        }
    )
}

@Composable
internal fun WikiImageThumbnail(wiki: WikiViewModel, image: ZimImage, modifier: Modifier) {
    WikiLoadedImage(image, null, modifier) { wiki.loadImage(image, 300) }
}

private fun ZimImage.articleEvidence(): ArticleEvidence? = articlePath?.let { path ->
    ArticleEvidence(articleTitle.orEmpty(), path, "", book = book, libraryId = libraryId)
}

private data class LoadedImage(val bitmap: ImageBitmap?)

@Composable
private fun WikiLoadedImage(key: Any, description: String?, modifier: Modifier, load: suspend () -> ImageBitmap) {
    val loaded by produceState<LoadedImage?>(null, key) {
        value = null
        value = try {
            LoadedImage(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LoadedImage(null)
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF2F5F7)), contentAlignment = Alignment.Center) {
        val state = loaded
        val bitmap = state?.bitmap
        when {
            bitmap != null -> Image(bitmap, description, Modifier.fillMaxWidth().padding(6.dp), contentScale = ContentScale.Fit)
            state == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Text("Imagem indisponível", Modifier.padding(4.dp), color = Color(0xFF233039),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
