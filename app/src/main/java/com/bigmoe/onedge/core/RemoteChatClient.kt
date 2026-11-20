package com.bigmoe.onedge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams one chat turn from an OpenAI-compatible server (POST /v1/chat/completions, stream=true) and reports it
 * with the same [EngineEvent]s as the local engine, so the agent loop (tool protocol in the system prompt, parser,
 * Termux tools) works unchanged. The server is stateless: every turn sends the whole (budgeted) history.
 */
class RemoteChatClient(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {

    private val active = AtomicReference<HttpURLConnection?>(null)

    @Volatile
    private var stopRequested = false

    fun stop() {
        stopRequested = true
        runCatching { active.get()?.disconnect() }
    }

    /** GET /v1/models: null when reachable, otherwise the error text. */
    fun check(server: RemoteServer): String? = try {
        val conn = open(server, server.endpoint("models"), "GET")
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.close(); null
        } else {
            "HTTP $code: ${errorBody(conn).take(300)}"
        }
    } catch (t: Throwable) {
        "Server not reachable: ${t.message ?: t.javaClass.simpleName}"
    }

    fun generate(server: RemoteServer, request: GenerationRequest, history: List<ChatTurn>, prompt: String): Flow<EngineEvent> = flow {
        stopRequested = false
        val messages = JSONArray()
        if (request.system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", request.system))
        history.forEach { turn ->
            val role = if (turn.role == ChatTurn.ASSISTANT) "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = JSONObject()
            .put("messages", messages)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            // llama-server (and other template-based servers): switch the model's thinking on/off.
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", request.think))
        if (server.model.isNotBlank()) body.put("model", server.model)
        if (request.maxTokens > 0) body.put("max_tokens", request.maxTokens)
        request.sampling?.let { s ->
            body.put("temperature", s.temperature.toDouble())
            body.put("top_p", s.topP.toDouble())
            if (s.topK > 0) body.put("top_k", s.topK)
            if (s.minP > 0f) body.put("min_p", s.minP.toDouble())
            if (s.repeatPenalty != 1f) body.put("repeat_penalty", s.repeatPenalty.toDouble())
            if (s.frequencyPenalty != 0f) body.put("frequency_penalty", s.frequencyPenalty.toDouble())
            if (s.presencePenalty != 0f) body.put("presence_penalty", s.presencePenalty.toDouble())
            if (s.seed >= 0) body.put("seed", s.seed)
        }

        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var generated = 0
        var promptTokens = 0
        var serverTps = 0.0
        var serverPrefillMs = 0.0
        val started = System.nanoTime()
        var firstTokenNs = 0L
        var conn: HttpURLConnection? = null
        try {
            conn = open(server, server.endpoint("chat/completions"), "POST")
            conn.connectTimeout = 15000
            conn.readTimeout = 600_000 // a big model on the PC may prefill a long time before the first byte
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            active.set(conn)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                emit(finished(false, false, "Server: HTTP $code ${errorBody(conn).take(500)}", answer, reasoning, 0, 0, 0.0, 0.0))
                return@flow
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (stopRequested) break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    o.optJSONObject("error")?.let { err ->
                        emit(finished(false, false, "Server: " + err.optString("message", err.toString()), answer, reasoning, generated, promptTokens, 0.0, 0.0))
                        return@flow
                    }
                    o.optJSONObject("usage")?.let { u ->
                        promptTokens = u.optInt("prompt_tokens", promptTokens)
                        u.optInt("completion_tokens", 0).takeIf { it > 0 }?.let { generated = it }
                    }
                    // llama-server's own timings (last chunk).
                    o.optJSONObject("timings")?.let { t ->
                        serverTps = t.optDouble("predicted_per_second", serverTps).takeIf { !it.isNaN() } ?: serverTps
                        serverPrefillMs = t.optDouble("prompt_ms", serverPrefillMs).takeIf { !it.isNaN() } ?: serverPrefillMs
                    }
                    val choices = o.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val r = delta.optStringOrEmpty("reasoning_content").ifEmpty { delta.optStringOrEmpty("reasoning") }
                    val c = delta.optStringOrEmpty("content")
                    if (r.isEmpty() && c.isEmpty()) continue
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    generated++
                    emit(EngineEvent.Progress(generated, promptTokens + generated))
                    if (r.isNotEmpty()) { reasoning.append(r); emit(EngineEvent.Reasoning(r)) }
                    if (c.isNotEmpty()) { answer.append(c); emit(EngineEvent.Answer(c)) }
                }
            }
            val end = System.nanoTime()
            val prefillS = if (serverPrefillMs > 0) serverPrefillMs / 1000.0
                else if (firstTokenNs > 0) (firstTokenNs - started) / 1e9 else 0.0
            val tps = if (serverTps > 0) serverTps
                else if (firstTokenNs > 0 && end > firstTokenNs && generated > 1) (generated - 1) / ((end - firstTokenNs) / 1e9) else 0.0
            emit(finished(true, stopRequested, "", answer, reasoning, generated, promptTokens, tps, prefillS))
        } catch (t: Throwable) {
            if (stopRequested) {
                emit(finished(true, true, "", answer, reasoning, generated, promptTokens, 0.0, 0.0))
            } else {
                emit(finished(false, false, "Server: ${t.message ?: t.javaClass.simpleName}", answer, reasoning, generated, promptTokens, 0.0, 0.0))
            }
        } finally {
            active.compareAndSet(conn, null)
            runCatching { conn?.disconnect() }
        }
    }.flowOn(ioDispatcher)

    private fun finished(
        ok: Boolean, cancelled: Boolean, error: String, answer: CharSequence, reasoning: CharSequence,
        generated: Int, promptTokens: Int, tps: Double, prefillS: Double
    ) = EngineEvent.Finished(
        ok = ok, cancelled = cancelled, error = error, answer = answer.toString(), reasoning = reasoning.toString(),
        stats = GenerationStats(generated, promptTokens, promptTokens + generated, tps, prefillS, -1.0)
    )

    private fun open(server: RemoteServer, url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (server.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${server.apiKey.trim()}")
        }

    private fun errorBody(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()

    private fun JSONObject.optStringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key, "")
}
