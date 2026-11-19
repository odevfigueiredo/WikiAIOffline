package com.wikiaioffline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Histórico local no armazenamento PRIVADO da aplicação. Sem nuvem, sem permissões de rede. */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val mine: Boolean,
    val content: String,
    val sources: List<ArticleEvidence> = emptyList()
)

class ChatHistory(context: Context) {
    private val file = File(context.filesDir, "wikiai_history_v03.json")

    @Synchronized
    fun load(): List<ChatMessage> {
        val data = try {
            JSONArray(file.readText(Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            return emptyList()
        }
        return (maxOf(0, data.length() - 100) until data.length()).mapNotNull { n ->
            val json = data.optJSONObject(n) ?: return@mapNotNull null
            val refs = json.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until refs.length()).mapNotNull source@{ i ->
                val ref = refs.optJSONObject(i) ?: return@source null
                val title = ref.optString("title")
                if (title.isBlank()) return@source null
                ArticleEvidence(
                    title, ref.optString("path"), "", ref.optString("book"),
                    ref.optString("language", "und"), ref.optString("libraryId")
                )
            }
            ChatMessage(
                id = json.optLong("id", System.nanoTime() + n),
                mine = json.optBoolean("mine"),
                content = json.optString("content").take(25_000),
                sources = sources
            )
        }
    }

    @Synchronized
    fun save(items: List<ChatMessage>) {
        val json = JSONArray()
        for (item in items.takeLast(100)) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("mine", item.mine)
            obj.put("content", item.content.take(25_000))
            val sources = JSONArray()
            item.sources.take(6).forEach { src ->
                sources.put(JSONObject().put("title", src.title)
                    .put("path", src.path).put("book", src.book).put("language", src.language)
                    .put("libraryId", src.libraryId))
            }
            obj.put("sources", sources)
            json.put(obj)
        }
        val staging = File.createTempFile("history-", ".new", file.parentFile)
        try {
            FileOutputStream(staging).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    fun clear() = save(emptyList())
}
