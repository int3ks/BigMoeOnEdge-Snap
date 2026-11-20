package com.bigmoe.onedge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A model behind an OpenAI-compatible server (llama.cpp's llama-server on the PC, LM Studio, a cloud API...).
 * It is "loaded" like a GGUF file under the path [path] ("remote:<id>"), and the chat then talks to the server.
 *
 * The API key is kept in the app's private files and in the user's own OneDrive (chat sync); it is never part of
 * the settings export.
 */
data class RemoteServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Base URL up to and including /v1, e.g. http://192.168.1.20:8080/v1 */
    val baseUrl: String,
    val apiKey: String = "",
    /** Model name sent with each request; blank = the server's default (llama-server ignores it). */
    val model: String = "",
    /** Context size the app plans the history for (the server has its own limit). */
    val contextSize: Int = 32768
) {
    val path: String get() = REMOTE_PREFIX + id

    /** Endpoint for chat completions, tolerant of a base URL with or without /v1 and a trailing slash. */
    fun endpoint(suffix: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val v1 = if (base.endsWith("/v1")) base else "$base/v1"
        return "$v1/$suffix"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("baseUrl", baseUrl).put("apiKey", apiKey)
        .put("model", model).put("contextSize", contextSize)

    companion object {
        const val REMOTE_PREFIX = "remote:"

        fun isRemotePath(path: String?): Boolean = path?.startsWith(REMOTE_PREFIX) == true

        fun fromJson(o: JSONObject): RemoteServer? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            return RemoteServer(
                id = id,
                name = o.optString("name").ifBlank { "Server" },
                baseUrl = o.optString("baseUrl"),
                apiKey = o.optString("apiKey"),
                model = o.optString("model"),
                contextSize = o.optInt("contextSize", 32768).coerceAtLeast(1024)
            )
        }
    }
}

/**
 * The list of server models, in filesDir/remote_servers.json: {"updatedAt": ms, "servers": [...]}.
 * [updatedAt] is the time of the last local change; the chat sync compares it to decide which copy is newer.
 */
class RemoteServerStore(private val file: File) {
    private val _servers = MutableStateFlow(read().second)
    val servers: StateFlow<List<RemoteServer>> = _servers.asStateFlow()

    @Volatile
    var updatedAt: Long = read().first
        private set

    fun byId(id: String): RemoteServer? = _servers.value.firstOrNull { it.id == id }

    fun byPath(path: String): RemoteServer? =
        if (RemoteServer.isRemotePath(path)) byId(path.removePrefix(RemoteServer.REMOTE_PREFIX)) else null

    @Synchronized
    fun save(server: RemoteServer) {
        val list = _servers.value.toMutableList()
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list[i] = server else list.add(server)
        write(list, System.currentTimeMillis())
    }

    @Synchronized
    fun delete(id: String) = write(_servers.value.filterNot { it.id == id }, System.currentTimeMillis())

    /** The file content for the sync. */
    @Synchronized
    fun exportJson(): String = toJson(_servers.value, updatedAt).toString(2)

    /** Takes a synced copy over (it was newer). */
    @Synchronized
    fun importJson(text: String): Boolean {
        val parsed = parse(text) ?: return false
        write(parsed.second, parsed.first)
        return true
    }

    private fun write(list: List<RemoteServer>, at: Long) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(list, at).toString(2))
        tmp.renameTo(file)
        updatedAt = at
        _servers.value = list
    }

    private fun read(): Pair<Long, List<RemoteServer>> =
        runCatching { parse(file.readText()) }.getOrNull() ?: (0L to emptyList())

    companion object {
        fun parse(text: String): Pair<Long, List<RemoteServer>>? = runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("servers") ?: JSONArray()
            o.optLong("updatedAt", 0L) to (0 until arr.length()).mapNotNull { RemoteServer.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()

        private fun toJson(list: List<RemoteServer>, at: Long): JSONObject =
            JSONObject().put("updatedAt", at).put("servers", JSONArray().apply { list.forEach { put(it.toJson()) } })
    }
}
