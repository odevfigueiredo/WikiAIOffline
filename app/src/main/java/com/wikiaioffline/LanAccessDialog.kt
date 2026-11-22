package com.wikiaioffline

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

@Composable
internal fun LanAccessDialog(wiki: WikiViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 570.dp)
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Acesso pela rede", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fechar opções de acesso pela rede") }
                }
                val endpoint = wiki.lanEndpoint
                if (endpoint == null) {
                    Text("Abra o acervo e consulte fontes em outro dispositivo na mesma rede.")
                    Text(
                        "O painel usa HTTP. Ative apenas em uma rede privada de confiança e mantenha o WikiAI aberto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = wiki::startLan, enabled = !wiki.lanStarting) {
                        if (wiki.lanStarting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Ativar acesso local")
                        }
                    }
                } else {
                    LanPairingDetails(endpoint, wiki)
                }
                wiki.lanNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun LanPairingDetails(endpoint: LanEndpoint, wiki: WikiViewModel) {
    val context = LocalContext.current
    var now by remember(endpoint.codeExpiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endpoint.codeExpiresAtMillis) {
        while (now < endpoint.codeExpiresAtMillis) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val qr = remember(endpoint.url) { addressQr(endpoint.url) }
    Text("Leia o QR ou digite o endereço no navegador.")
    Image(qr, "QR com o endereço do painel local", Modifier.size(144.dp).padding(4.dp))
    SelectionContainer { Text(endpoint.url, fontFamily = FontFamily.Monospace) }
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Painel WikiAI Offline", endpoint.url))
    }) { Text("Copiar endereço") }
    Text("Código de pareamento", style = MaterialTheme.typography.labelLarge)
    if (now < endpoint.codeExpiresAtMillis) {
        Text(endpoint.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall)
        Text("Digite este código no navegador dentro de cinco minutos.",
            style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Código expirado. Gere outro para conectar um novo dispositivo.")
    }
    TextButton(onClick = wiki::renewLanCode) { Text("Gerar código e desconectar navegadores") }
    OutlinedButton(onClick = wiki::stopLan) { Text("Encerrar acesso local") }
}

private fun addressQr(url: String): androidx.compose.ui.graphics.ImageBitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
