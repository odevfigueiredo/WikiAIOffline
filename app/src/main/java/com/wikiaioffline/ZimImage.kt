package com.wikiaioffline

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

data class ZimImage(
    val libraryId: String,
    val path: String,
    val title: String,
    val articleTitle: String?,
    val articlePath: String?,
    val book: String
)

data class ZimImageContent(val mimeType: String, val bytes: ByteArray)

internal data class RankedZimImage(val image: ZimImage, val score: Int)

private const val RASTER_BYTES = 8 * 1024 * 1024
private const val SVG_BYTES = 512 * 1024
internal const val SVG_MIME_TYPE = "image/svg+xml"

internal val zimImageByteLimits: Map<String, Int> = mapOf(
    "image/png" to RASTER_BYTES,
    "image/jpeg" to RASTER_BYTES,
    "image/webp" to RASTER_BYTES,
    "image/gif" to RASTER_BYTES,
    "image/avif" to RASTER_BYTES,
    SVG_MIME_TYPE to SVG_BYTES
)

internal fun zimImageMimeType(raw: String): String =
    when (val mime = raw.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> mime
    }

private const val MAX_ZIM_PATH = 2048
private val uriScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

internal fun validZimImagePath(path: String): Boolean =
    path.length in 1..MAX_ZIM_PATH && '\\' !in path && path.none(Char::isISOControl) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** Resolve URLs do HTML no índice do acervo. Nenhuma URL é aberta pelo sistema. */
internal fun localZimImagePaths(articlePath: String, source: String): List<String> {
    val ref = source.trim().substringBefore('#').substringBefore('?')
    if (ref.length !in 1..MAX_ZIM_PATH || source.trimStart().startsWith('#')) return emptyList()
    val decoded = try {
        URLDecoder.decode(ref.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    fun external(path: String): Boolean = path.startsWith("//") || uriScheme.containsMatchIn(path) ||
        '\\' in path || path.any(Char::isISOControl)
    if (external(ref) || external(decoded)) return emptyList()
    val base = articlePath.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty)
    fun resolve(path: String): String? {
        val segments = if (path.startsWith('/')) mutableListOf() else base.toMutableList()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/").takeIf(::validZimImagePath)
    }
    val canonical = resolve(decoded) ?: return emptyList()
    return listOfNotNull(canonical, resolve(ref)).distinct()
}

private val svgPrimitives = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")
private val svgTextTags = setOf("text", "tspan", "title", "desc")
private val svgTags = svgPrimitives + svgTextTags + setOf(
    "svg", "g", "defs", "symbol", "use", "linearGradient", "radialGradient", "stop",
    "clipPath", "mask", "pattern"
)
private val svgPresentation = setOf(
    "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap",
    "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "opacity", "color",
    "clip-path", "clip-rule", "mask", "stop-color", "stop-opacity", "font-family", "font-size",
    "font-style", "font-weight", "text-anchor", "dominant-baseline", "visibility", "display"
)
private val svgAttributes = svgPresentation + setOf(
    "id", "d", "points", "x", "y", "x1", "x2", "y1", "y2", "dx", "dy", "rx", "ry", "r", "cx", "cy",
    "fx", "fy", "fr", "width", "height", "viewBox", "preserveAspectRatio", "transform", "offset",
    "gradientUnits", "gradientTransform", "spreadMethod", "clipPathUnits", "maskUnits", "maskContentUnits",
    "patternUnits", "patternContentUnits", "patternTransform", "lengthAdjust", "textLength"
)
private val fragment = Regex("#[A-Za-z_][A-Za-z0-9_.:-]{0,127}")
private val localPaint = Regex("""url\(\s*(['"]?)(#[A-Za-z_][A-Za-z0-9_.:-]{0,127})\1\s*\)""", RegexOption.IGNORE_CASE)
private val svgPaintTargets = mapOf(
    "fill" to setOf("linearGradient", "radialGradient", "pattern"),
    "stroke" to setOf("linearGradient", "radialGradient", "pattern"),
    "clip-path" to setOf("clipPath"),
    "mask" to setOf("mask")
)
private val svgLength = Regex("""([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?)(?:px|pt|pc|mm|cm|in|em|ex|%)?""")
private const val MAX_SVG_ELEMENTS = 3000
private const val MAX_SVG_DEPTH = 48

/** Recria o XML com elementos gráficos seguros. Nunca retorna o XML original. */
internal fun sanitizeSvg(bytes: ByteArray): ByteArray {
    if (bytes.size !in 1..SVG_BYTES) throw IOException("A imagem SVG excede o limite permitido.")
    val parsed = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
    val original = parsed.children().singleOrNull()?.takeIf { it.tagName() == "svg" }
        ?: throw IOException("A imagem SVG está inválida.")
    val clean = Document("").parser(Parser.xmlParser())
    clean.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
    val root = clean.appendElement("svg")
        .attr("xmlns", "http://www.w3.org/2000/svg")
        .attr("xmlns:xlink", "http://www.w3.org/1999/xlink")
    var elements = 0

    fun copy(source: Element, target: Element, depth: Int) {
        if (++elements > MAX_SVG_ELEMENTS || depth > MAX_SVG_DEPTH) {
            throw IOException("A imagem SVG possui elementos demais.")
        }
        for (attr in source.attributes()) {
            when {
                attr.key in svgAttributes && safeSvgAttribute(attr.key, attr.value) -> target.attr(attr.key, attr.value)
                attr.key == "style" -> {
                    for (rule in attr.value.split(';').take(40)) {
                        val key = rule.substringBefore(':').trim()
                        val value = rule.substringAfter(':', "").trim()
                        if (key in svgPresentation && safeSvgAttribute(key, value)) target.attr(key, value)
                    }
                }
                attr.key in setOf("href", "xlink:href") && source.tagName() == "use" && fragment.matches(attr.value) ->
                    target.attr(attr.key, attr.value)
            }
        }
        for (child in source.childNodes()) {
            if (child is Element && child.tagName() in svgTags) {
                copy(child, target.appendElement(child.tagName()), depth + 1)
            } else if (child is TextNode && target.tagName() in svgTextTags) {
                target.appendChild(TextNode(child.wholeText.take(4096)))
            }
        }
    }

    copy(original, root, 0)
    // Limita referências clonáveis a formas sem filhos gráficos, como os glifos de fórmulas.
    val ids = root.select("[id]").filter { it.id().isNotBlank() }.groupBy { it.id() }
    val cloned = mutableSetOf<Element>()
    for (use in root.getElementsByTag("use")) {
        val ref = use.attr("href").ifBlank { use.attr("xlink:href") }
        val target = ids[ref.removePrefix("#")]?.singleOrNull()
        if (target == null || target.tagName() !in svgPrimitives || target.children().any { it.tagName() !in svgTextTags }) {
            use.remove()
        } else {
            use.removeAttr("href").attr("xlink:href", ref)
            cloned.add(target)
        }
    }
    val paintable = ids.mapNotNull { (id, elements) ->
        val element = elements.singleOrNull() ?: return@mapNotNull null
        if (svgPaintTargets.values.none { element.tagName() in it }) return@mapNotNull null
        val nestedReference = element.getAllElements().any { descendant ->
            descendant.attributes().any { localPaint.matches(it.value.trim()) }
        }
        if (nestedReference) null else id to element.tagName()
    }.toMap()
    for (element in root.getAllElements()) {
        for ((attr, tags) in svgPaintTargets) {
            val value = localPaint.matchEntire(element.attr(attr).trim()) ?: continue
            if (element in cloned || paintable[value.groupValues[2].removePrefix("#")] !in tags) {
                element.removeAttr(attr)
            }
        }
    }
    val sanitized = clean.outerHtml().toByteArray(Charsets.UTF_8)
    if (sanitized.size > SVG_BYTES) throw IOException("A imagem SVG excede o limite permitido.")
    return sanitized
}

private fun safeSvgAttribute(name: String, value: String): Boolean {
    if (value.isBlank() || value.length > 128_000 || value.any { it in "<>\\{};" || it.isISOControl() }) return false
    if (name == "id") return fragment.matches("#" + value)
    if (name in setOf("width", "height")) {
        val magnitude = svgLength.matchEntire(value.trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: return false
        return magnitude > 0 && magnitude <= 8192
    }
    if (name == "viewBox") {
        val parts = value.trim().split(Regex("[,\\s]+")).map(String::toDoubleOrNull)
        return parts.size == 4 && parts.all { it != null && it in -1e7..1e7 } &&
            (parts[2] ?: 0.0) > 0.0 && (parts[3] ?: 0.0) > 0.0
    }
    if (value.contains("url", ignoreCase = true)) {
        return name in svgPaintTargets && localPaint.matches(value.trim())
    }
    return true
}
