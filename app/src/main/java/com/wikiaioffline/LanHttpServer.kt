package com.wikiaioffline

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject

internal interface LanBackend {
    suspend fun catalog(): JSONObject
    suspend fun search(query: String, includeEnglish: Boolean, withAnswer: Boolean): JSONObject
    suspend fun searchImages(query: String, includeEnglish: Boolean): JSONObject
    suspend fun article(libraryId: String, path: String): JSONObject
    suspend fun image(libraryId: String, path: String): ZimImageContent
    suspend fun remove(id: String): JSONObject
}

internal data class LanEndpoint(val url: String, val code: String, val codeExpiresAtMillis: Long)

/** An expected error whose user-facing message is safe to send to a paired browser. */
internal class LanApiException(val status: Int, message: String) : Exception(message)

/** An opt-in HTTP listener bound to a single private IPv4 address. */
internal class LanHttpServer(
    private val address: Inet4Address,
    private val backend: LanBackend,
    private val openAsset: (String) -> InputStream
) {
    @Volatile
    private var listener: LanListener? = null

    @Synchronized
    fun start(): LanEndpoint {
        require(address.isSiteLocalAddress) { "Selecione um endereço IPv4 privado da rede local." }
        listener?.let { return it.endpoint() }
        val active = try {
            listen(8765)
        } catch (_: BindException) {
            listen(0)
        }
        listener = active
        return active.endpoint()
    }

    @Synchronized
    fun refreshCode(): LanEndpoint {
        val active = checkNotNull(listener) { "O acesso pela rede está desligado." }
        active.pairing.rotate()
        return active.endpoint()
    }

    /** Immediately invalidate pairing and cancel request work without waiting for socket shutdown. */
    fun revoke() {
        listener?.revoke()
    }

    @Synchronized
    fun stop() {
        val active = listener ?: return
        active.close()
        listener = null
    }

    private fun listen(port: Int): LanListener {
        val candidate = LanListener(address.hostAddress ?: error("Endereço IPv4 inválido"), port, backend, openAsset)
        try {
            candidate.start(5_000)
            return candidate
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }
}

private class LanListener(
    private val host: String,
    port: Int,
    private val backend: LanBackend,
    private val openAsset: (String) -> InputStream
) : NanoHTTPD(host, port) {
    val pairing = LanPairing()
    private val work = SupervisorJob()
    private val canonicalHost get() = "$host:$listeningPort"
    private val origin get() = "http://$canonicalHost"

    init {
        setAsyncRunner(BoundedClients())
    }

    fun endpoint() = pairing.endpoint(origin)

    fun revoke() {
        pairing.revoke()
        work.cancel()
    }

    fun close() {
        revoke()
        stop()
    }

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun createClientHandler(socket: Socket, input: InputStream): ClientHandler =
        object : ClientHandler(input, socket) {
            override fun run() {
                try {
                    val peer = socket.inetAddress
                    if (peer !is Inet4Address || !peer.isSiteLocalAddress) return
                    // NanoHTTPD uses getHostName in its session. Supply the literal to avoid reverse DNS.
                    val literalPeer = InetAddress.getByAddress(peer.hostAddress, peer.address)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    val timedInput = object : FilterInputStream(input) {
                        private fun checkDeadline() {
                            if (System.nanoTime() - deadline >= 0) throw SocketTimeoutException()
                        }

                        override fun read(): Int {
                            checkDeadline()
                            return super.read()
                        }

                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            checkDeadline()
                            return super.read(bytes, offset, length)
                        }
                    }
                    HTTPSession(NoTempFiles, timedInput, socket.getOutputStream(), literalPeer).execute()
                } catch (_: IOException) {
                    // An incomplete request, timeout or a browser disconnect has no work left to send.
                } finally {
                    close()
                    asyncRunner.closed(this)
                }
            }
        }

    override fun serve(request: IHTTPSession): Response {
        val response = try {
            route(request)
        } catch (error: LanApiException) {
            failure(Status.lookup(error.status) ?: Status.INTERNAL_ERROR, error.message.orEmpty())
        } catch (_: JSONException) {
            failure(Status.BAD_REQUEST, "O corpo da solicitação é inválido.")
        } catch (_: TimeoutCancellationException) {
            failure(Status.SERVICE_UNAVAILABLE, "A operação excedeu o tempo disponível. Tente novamente.")
        } catch (_: CancellationException) {
            failure(Status.SERVICE_UNAVAILABLE, "O acesso pela rede foi interrompido.")
        } catch (_: Exception) {
            failure(Status.INTERNAL_ERROR, "O WikiAI Offline não pôde atender à solicitação.")
        }
        response.closeConnection(true)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        val policy = if (request.uri == "/api/image") IMAGE_POLICY else PANEL_POLICY
        response.addHeader("Content-Security-Policy", policy)
        return response
    }

    private fun route(request: IHTTPSession): Response {
        if (!work.isActive) return failure(Status.SERVICE_UNAVAILABLE, "O acesso pela rede está desligado.")
        if (request.headers["host"] != canonicalHost) {
            return failure(Status.FORBIDDEN, "Use o endereço local mostrado no WikiAI Offline.")
        }
        val requestOrigin = request.headers["origin"]
        if ((request.method == Method.POST || requestOrigin != null) && requestOrigin != origin) {
            return failure(Status.FORBIDDEN, "A origem da solicitação precisa ser o painel local.")
        }
        assets[request.uri]?.let { asset ->
            if (request.method != Method.GET) return methodNotAllowed(Method.GET)
            return newChunkedResponse(Status.OK, asset.second, openAsset(asset.first))
        }

        val token = sessionToken(request.headers["cookie"])
        val paired = pairing.isPaired(token)
        return when (request.uri) {
            "/api/session" -> request.only(Method.GET) { success(JSONObject().put("paired", paired)) }
            "/api/pair" -> request.only(Method.POST) { pair(request) }
            "/api/logout" -> request.only(Method.POST) {
                if (!paired) return needPairing()
                request.jsonBody()
                pairing.forget(token)
                success(JSONObject().put("paired", false)).also { it.addHeader("Set-Cookie", cookie("", 0)) }
            }
            "/api/catalog" -> request.only(Method.GET) {
                if (!paired) return needPairing()
                success(call(10_000) { backend.catalog() })
            }
            "/api/search", "/api/answer", "/api/images/search" -> request.only(Method.POST) {
                if (!paired) return needPairing()
                search(request)
            }
            "/api/article", "/api/image" -> request.only(Method.GET) {
                if (!paired) return needPairing()
                val id = checkField(request.parameters["libraryId"]?.singleOrNull(), 128)
                val path = checkField(request.parameters["path"]?.singleOrNull(), 2_048, trim = false)
                if (request.uri == "/api/image") {
                    imageResponse(call(25_000) { backend.image(id, path) })
                } else {
                    success(call(25_000) { backend.article(id, path) })
                }
            }
            "/api/libraries/remove" -> request.only(Method.POST) {
                if (!paired) return needPairing()
                val id = checkField(request.jsonBody().opt("id"), 128)
                success(call(25_000) { backend.remove(id) })
            }
            else -> failure(Status.NOT_FOUND, "Este recurso não está disponível.")
        }
    }

    private fun pair(request: IHTTPSession): Response {
        val code = checkField(request.jsonBody().opt("code"), 8)
        if (!codePattern.matches(code)) throw LanApiException(400, "Digite o código de oito números.")
        return when (val result = pairing.pair(request.remoteIpAddress.orEmpty(), code)) {
            LanPairing.Result.Expired -> failure(Status.GONE, "O código expirou. Gere um novo no Android.")
            LanPairing.Result.Invalid -> failure(Status.FORBIDDEN, "Código de pareamento incorreto.")
            LanPairing.Result.Limited -> failure(Status.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde um minuto.")
                .also { it.addHeader("Retry-After", "60") }
            is LanPairing.Result.Paired -> success(JSONObject().put("paired", true)).also {
                it.addHeader("Set-Cookie", cookie(result.token, LanPairing.SESSION_AGE_SECONDS))
            }
        }
    }

    private fun search(request: IHTTPSession): Response {
        val body = request.jsonBody()
        val query = checkField(body.opt("query"), 600)
        val includeEnglish = body.opt("includeEnglish") as? Boolean
            ?: throw LanApiException(400, "Informe se deseja consultar também o acervo em inglês.")
        val result = when (request.uri) {
            "/api/images/search" -> call(35_000) { backend.searchImages(query, includeEnglish) }
            "/api/answer" -> call(145_000) { backend.search(query, includeEnglish, withAnswer = true) }
            else -> call(35_000) { backend.search(query, includeEnglish, withAnswer = false) }
        }
        return success(result)
    }

    private fun imageResponse(image: ZimImageContent): Response {
        val mime = zimImageMimeType(image.mimeType)
        val limit = zimImageByteLimits[mime]
            ?: return failure(Status.UNSUPPORTED_MEDIA_TYPE, "Formato de imagem indisponível.")
        if (image.bytes.isEmpty()) return failure(Status.NOT_FOUND, "A imagem não está disponível neste acervo.")
        if (image.bytes.size > limit) return failure(Status.PAYLOAD_TOO_LARGE, "A imagem é grande demais.")
        return newFixedLengthResponse(Status.OK, mime, image.bytes.inputStream(), image.bytes.size.toLong())
    }

    private fun IHTTPSession.jsonBody(): JSONObject {
        if (headers.containsKey("transfer-encoding")) throw LanApiException(400, "Formato de envio inválido.")
        if (!headers["content-type"].orEmpty().substringBefore(';').trim().equals("application/json", true)) {
            throw LanApiException(415, "Envie os dados no formato JSON.")
        }
        val size = headers["content-length"]?.toIntOrNull() ?: throw LanApiException(411, "Tamanho não informado.")
        if (size > 8_192) throw LanApiException(413, "O conteúdo enviado é muito grande.")
        if (size <= 0) throw LanApiException(400, "Envie um objeto JSON.")
        val bytes = inputStream.readNBytes(size)
        if (bytes.size != size) throw LanApiException(400, "O envio veio incompleto.")
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun checkField(value: Any?, maxLength: Int, trim: Boolean = true): String {
        val rawValue = value as? String ?: throw LanApiException(400, "Os dados enviados são inválidos.")
        val field = if (trim) rawValue.trim() else rawValue
        if (field.isBlank() || field.length > maxLength || field.any(Char::isISOControl)) {
            throw LanApiException(400, "Os dados enviados são inválidos.")
        }
        return field
    }

    private inline fun IHTTPSession.only(method: Method, block: () -> Response): Response =
        if (this.method == method) block() else methodNotAllowed(method)

    private fun methodNotAllowed(method: Method) = failure(Status.METHOD_NOT_ALLOWED, "Método indisponível.")
        .also { it.addHeader("Allow", method.name) }

    private fun needPairing() = failure(Status.UNAUTHORIZED, "Pareie este navegador com o Android.")

    private fun <T> call(timeout: Long, block: suspend () -> T): T =
        runBlocking(work) { withTimeout(timeout) { block() } }

    private fun success(data: JSONObject): Response = json(Status.OK, data)

    private fun failure(status: Status, message: String): Response = json(status, JSONObject().put("error", message))

    private fun json(status: Status, data: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=UTF-8", data.toString())

    private fun sessionToken(header: String?): String? = header?.split(';')
        ?.map(String::trim)?.filter { it.startsWith("$COOKIE_NAME=") }
        ?.singleOrNull()?.substringAfter('=')?.takeIf { tokenPattern.matches(it) }

    private fun cookie(value: String, seconds: Long): String =
        "$COOKIE_NAME=$value; Path=/api; Max-Age=$seconds; HttpOnly; SameSite=Strict"

    private companion object {
        const val COOKIE_NAME = "wikiai_lan_session"
        const val PANEL_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; " +
            "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        // Direct navigation can render an SVG as a document. Keep it isolated from the panel origin.
        const val IMAGE_POLICY = "sandbox; default-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
        val codePattern = Regex("[0-9]{8}")
        val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
        val assets = mapOf(
            "/" to ("lan/index.html" to "text/html; charset=UTF-8"),
            "/app.css" to ("lan/app.css" to "text/css; charset=UTF-8"),
            "/app.js" to ("lan/app.js" to "text/javascript; charset=UTF-8"),
            "/library-art.webp" to ("lan/library-art.webp" to "image/webp")
        )
    }
}

private class LanPairing {
    sealed interface Result {
        data object Invalid : Result
        data object Expired : Result
        data object Limited : Result
        data class Paired(val token: String) : Result
    }

    private val random = SecureRandom()
    private val attempts = ArrayDeque<Pair<String, Long>>()
    private val sessions = LinkedHashMap<String, Long>()
    private var code = nextCode()
    private var deadline = System.nanoTime() + CODE_DURATION_NANOS
    private var expiresAtMillis = System.currentTimeMillis() + CODE_DURATION_MILLIS
    private var active = true

    @Synchronized
    fun endpoint(url: String) = LanEndpoint(url, code, expiresAtMillis)

    @Synchronized
    fun pair(peer: String, candidate: String): Result {
        val now = System.nanoTime()
        if (!active || now - deadline >= 0) return Result.Expired
        while (attempts.isNotEmpty() && now - attempts.first().second >= ATTEMPT_WINDOW) attempts.removeFirst()
        if (attempts.size >= 24 || attempts.count { it.first == peer } >= 6) return Result.Limited
        attempts.addLast(peer to now)
        if (!MessageDigest.isEqual(code.toByteArray(), candidate.toByteArray())) return Result.Invalid
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        sessions.entries.removeAll { now - it.value >= 0 }
        if (sessions.size >= 24) sessions.remove(sessions.keys.first())
        sessions[token] = now + TimeUnit.SECONDS.toNanos(SESSION_AGE_SECONDS)
        return Result.Paired(token)
    }

    @Synchronized
    fun isPaired(token: String?): Boolean {
        if (!active || token == null) return false
        val until = sessions[token] ?: return false
        if (System.nanoTime() - until < 0) return true
        sessions.remove(token)
        return false
    }

    @Synchronized
    fun forget(token: String?) {
        sessions.remove(token)
    }

    @Synchronized
    fun rotate() {
        check(active)
        val previous = code
        do { code = nextCode() } while (code == previous)
        deadline = System.nanoTime() + CODE_DURATION_NANOS
        expiresAtMillis = System.currentTimeMillis() + CODE_DURATION_MILLIS
        attempts.clear()
        sessions.clear()
    }

    @Synchronized
    fun revoke() {
        active = false
        attempts.clear()
        sessions.clear()
    }

    private fun nextCode() = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000))

    companion object {
        const val SESSION_AGE_SECONDS = 3_600L
        private val ATTEMPT_WINDOW = TimeUnit.MINUTES.toNanos(1)
        private val CODE_DURATION_NANOS = TimeUnit.MINUTES.toNanos(5)
        private val CODE_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(5)
    }
}

private class BoundedClients : NanoHTTPD.AsyncRunner {
    private val active = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue()) { job ->
        Thread(job, "WikiAI LAN").apply { isDaemon = true }
    }

    override fun exec(client: NanoHTTPD.ClientHandler) {
        active.add(client)
        try {
            workers.execute(client)
        } catch (_: RejectedExecutionException) {
            active.remove(client)
            client.close()
        }
    }

    override fun closed(client: NanoHTTPD.ClientHandler) {
        active.remove(client)
    }

    override fun closeAll() {
        active.forEach { it.close() }
        workers.shutdownNow()
    }
}

private object NoTempFiles : NanoHTTPD.TempFileManager {
    override fun clear() = Unit
    override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile = throw IOException("Envio de arquivo indisponível.")
}
