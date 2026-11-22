package com.wikiaioffline

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun renderWikiImage(content: ZimImageContent, maxEdge: Int): ImageBitmap =
    withContext(Dispatchers.Default) {
        val bitmap = if (content.mimeType == SVG_MIME_TYPE) {
            val vector = SVG.getFromInputStream(content.bytes.inputStream())
            val ratio = vector.documentAspectRatio.takeIf { it.isFinite() && it > 0 } ?: 1f
            val width = (maxEdge * ratio.coerceAtMost(1f)).roundToInt().coerceAtLeast(1)
            val height = (maxEdge / ratio.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)
            if (vector.documentViewBox == null) {
                val originalWidth = vector.documentWidth.takeIf { it.isFinite() && it > 0 } ?: width.toFloat()
                val originalHeight = vector.documentHeight.takeIf { it.isFinite() && it > 0 } ?: height.toFloat()
                vector.setDocumentViewBox(0f, 0f, originalWidth, originalHeight)
            }
            vector.setDocumentWidth("100%")
            vector.setDocumentHeight("100%")
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                vector.renderToCanvas(Canvas(it))
            }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(content.bytes))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = maxEdge.toFloat() / maxOf(width, height).coerceAtLeast(1)
                if (scale < 1f) {
                    decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        }
        bitmap.asImageBitmap()
    }

internal suspend fun readDeviceImage(resolver: ContentResolver, uri: Uri): ZimImageContent =
    withContext(Dispatchers.IO) {
        val mime = zimImageMimeType(resolver.getType(uri).orEmpty())
        val svg = mime == SVG_MIME_TYPE || uri.lastPathSegment.orEmpty().endsWith(".svg", ignoreCase = true)
        val limit = zimImageByteLimits.getValue(if (svg) SVG_MIME_TYPE else "image/png")
        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(limit + 1) }
            ?: throw IOException("O arquivo não está disponível.")
        if (bytes.isEmpty() || bytes.size > limit) throw IOException("A imagem está vazia ou excede o limite de tamanho.")
        if (svg) ZimImageContent(SVG_MIME_TYPE, sanitizeSvg(bytes)) else ZimImageContent(mime, bytes)
    }
