package com.wikiaioffline

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class LanHttpServerTest {
    @Test
    fun pairThenSearchAndReadWithSameOriginSession() = localServer { _, endpoint, browser ->
        val page = browser.get("/")
        assertEquals(200, page.status)
        assertEquals("lan/index.html", page.body)
        assertTrue(page.header("Content-Security-Policy").orEmpty().contains("frame-ancestors 'none'"))
        assertFalse(JSONObject(browser.get("/api/session").body).getBoolean("paired"))
        assertEquals(401, browser.get("/api/catalog").status)
        assertEquals(404, browser.get("/built_in_model.gguf").status)

        val pairBody = JSONObject().put("code", endpoint.code)
        assertEquals(403, browser.post("/api/pair", pairBody, origin = "https://example.test").status)
        val pair = browser.post("/api/pair", pairBody)
        assertEquals(200, pair.status)
        val setCookie = checkNotNull(pair.header("Set-Cookie"))
        assertTrue(setCookie.contains("HttpOnly"))
        assertTrue(setCookie.contains("SameSite=Strict"))
        val cookie = setCookie.substringBefore(';')
        assertTrue(JSONObject(browser.get("/api/session", cookie).body).getBoolean("paired"))
        assertEquals(200, browser.get("/api/catalog", cookie).status)

        val question = JSONObject().put("query", "Água").put("includeEnglish", false)
        val search = browser.post("/api/search", question, cookie = cookie)
        assertEquals(200, search.status)
        assertEquals("Água", JSONObject(search.body).getString("query"))
        assertFalse(JSONObject(search.body).getBoolean("withAnswer"))
        assertTrue(JSONObject(browser.post("/api/answer", question, cookie = cookie).body)
            .getBoolean("withAnswer"))

        val path = "A/Água limpa?"
        val article = browser.get("/api/article?libraryId=book&path=" + URLEncoder.encode(path, "UTF-8"), cookie)
        assertEquals(path, JSONObject(article.body).getString("path"))
        assertEquals(403, browser.post("/api/libraries/remove", JSONObject().put("id", "book"),
            cookie = cookie, origin = "https://example.test").status)
        assertEquals("book", JSONObject(browser.post("/api/libraries/remove", JSONObject().put("id", "book"),
            cookie = cookie).body).getString("id"))
        assertEquals(200, browser.post("/api/logout", JSONObject(), cookie = cookie).status)
        assertEquals(401, browser.get("/api/catalog", cookie).status)
    }

    @Test
    fun limitPairingAndRevokeSessionsWhenCodeRotates() = localServer { server, endpoint, browser ->
        val incorrect = if (endpoint.code == "99999999") "11111111" else "99999999"
        val incorrectBody = JSONObject().put("code", incorrect)
        repeat(6) { assertEquals(403, browser.post("/api/pair", incorrectBody).status) }
        assertEquals(429, browser.post("/api/pair", JSONObject().put("code", endpoint.code)).status)

        val first = server.refreshCode()
        assertNotEquals(endpoint.code, first.code)
        val paired = browser.post("/api/pair", JSONObject().put("code", first.code))
        assertEquals(200, paired.status)
        val cookie = checkNotNull(paired.header("Set-Cookie")).substringBefore(';')
        assertEquals(200, browser.get("/api/catalog", cookie).status)

        server.refreshCode()
        assertEquals(401, browser.get("/api/catalog", cookie).status)
        assertEquals(403, browser.post("/api/pair", JSONObject().put("code", first.code)).status)
        assertEquals(413, browser.post("/api/pair", JSONObject().put("code", "a".repeat(9_000))).status)
    }

    @Test
    fun revokeCancelsRequestsAndSessionsBeforeSocketShutdown() {
        val entered = CountDownLatch(1)
        val backend = object : LanBackend by FakeLanBackend {
            override suspend fun search(query: String, includeEnglish: Boolean, withAnswer: Boolean): JSONObject {
                entered.countDown()
                awaitCancellation()
            }
        }
        localServer(backend) { server, endpoint, browser ->
            val pair = browser.post("/api/pair", JSONObject().put("code", endpoint.code))
            val cookie = checkNotNull(pair.header("Set-Cookie")).substringBefore(';')
            val pending = CompletableFuture.supplyAsync {
                val question = JSONObject().put("query", "Água").put("includeEnglish", false)
                browser.post("/api/answer", question, cookie = cookie)
            }
            assertTrue(entered.await(4, TimeUnit.SECONDS))

            server.revoke()

            assertEquals(503, pending.get(4, TimeUnit.SECONDS).status)
            assertEquals(503, browser.get("/api/catalog", cookie).status)
            assertEquals(503, browser.post("/api/pair", JSONObject().put("code", endpoint.code)).status)
        }
    }

    private fun localServer(
        backend: LanBackend = FakeLanBackend,
        block: (LanHttpServer, LanEndpoint, Browser) -> Unit
    ) {
        val address = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
        assumeNotNull(address)
        val server = LanHttpServer(checkNotNull(address), backend) { it.byteInputStream() }
        val endpoint = server.start()
        try {
            block(server, endpoint, Browser(endpoint.url))
        } finally {
            server.stop()
        }
    }

    private data class HttpResult(val status: Int, private val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    private class Browser(private val url: String) {
        private val endpoint = URI.create(url)

        fun get(path: String, cookie: String? = null): HttpResult = request("GET", path, cookie, null, null)

        fun post(path: String, body: JSONObject, cookie: String? = null, origin: String = url): HttpResult =
            request("POST", path, cookie, body.toString().toByteArray(Charsets.UTF_8), origin)

        // The JDK's HttpURLConnection omits Origin by default. Send it without altering JVM-wide properties.
        private fun request(
            method: String, path: String, cookie: String?, body: ByteArray?, origin: String?
        ): HttpResult = Socket(Proxy.NO_PROXY).use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 4_000)
            socket.soTimeout = 5_000
            val headers = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${endpoint.authority}\r\nConnection: close\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                if (body != null) {
                    append("Origin: $origin\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n")
                }
                append("\r\n")
            }
            val output = socket.getOutputStream()
            output.write(headers.toByteArray(Charsets.US_ASCII))
            if (body != null) output.write(body)
            output.flush()
            val input = DataInputStream(socket.getInputStream().buffered())
            val status = input.readAsciiLine().split(' ')[1].toInt()
            val responseHeaders = generateSequence { input.readAsciiLine() }.takeWhile(String::isNotEmpty)
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val bytes = if (responseHeaders["transfer-encoding"] == "chunked") {
                ByteArrayOutputStream().apply {
                    var size = input.readAsciiLine().toInt(16)
                    while (size > 0) {
                        write(ByteArray(size).also { input.readFully(it) })
                        check(input.readAsciiLine().isEmpty())
                        size = input.readAsciiLine().toInt(16)
                    }
                }.toByteArray()
            } else {
                val size = checkNotNull(responseHeaders["content-length"]).toInt()
                ByteArray(size).also { input.readFully(it) }
            }
            HttpResult(status, responseHeaders, bytes.toString(Charsets.UTF_8))
        }

        private fun DataInputStream.readAsciiLine(): String = buildString {
            var byte = readUnsignedByte()
            while (byte != '\n'.code) {
                if (byte != '\r'.code) append(byte.toChar())
                byte = readUnsignedByte()
            }
        }
    }

    private object FakeLanBackend : LanBackend {
        override suspend fun catalog(): JSONObject = JSONObject().put("libraries", JSONArray())

        override suspend fun search(query: String, includeEnglish: Boolean, withAnswer: Boolean): JSONObject =
            JSONObject().put("query", query).put("includeEnglish", includeEnglish).put("withAnswer", withAnswer)

        override suspend fun article(libraryId: String, path: String): JSONObject =
            JSONObject().put("libraryId", libraryId).put("path", path)

        override suspend fun remove(id: String): JSONObject = JSONObject().put("id", id)
    }
}
