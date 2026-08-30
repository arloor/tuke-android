package com.arloor.tuke.core.agent

import com.arloor.tuke.engine.EngineEndpoint
import com.arloor.tuke.core.network.ApiException
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.BufferedSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 只访问应用内置 harness 的回环 HTTP 接口。 */
class AgentRepository(
    httpClient: OkHttpClient,
    private val json: Json,
    private val endpoint: suspend () -> EngineEndpoint,
) {
    private suspend fun baseUrl(): String {
        return try {
            endpoint().baseUrl
        } catch (error: IllegalStateException) {
            throw ApiException(error.message ?: "本地引擎启动失败", error)
        }
    }

    private val httpClient = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /** SSE 需要无限读取超时，单独派生一个 client。 */
    private val sseClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun listSessions(
        query: String = "",
        cursor: String? = null,
        limit: Int = 20,
        starredOnly: Boolean = false,
    ): SessionListResponse = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/api/chat/sessions".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query.trim())
                if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor)
                if (starredOnly) addQueryParameter("starred", "true")
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val body = execute(request)
        json.decodeFromString<SessionListResponse>(body)
    }

    suspend fun getSession(sessionId: String): SessionDetailResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}")
            .get()
            .build()
        val body = execute(request)
        json.decodeFromString<SessionDetailResponse>(body)
    }

    suspend fun getSettings(): AgentSettings = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/models")
            .get()
            .build()
        val body = json.parseToJsonElement(execute(request)).jsonObject
        val defaultModel = body["defaultModel"]?.jsonPrimitive?.contentOrNull ?: AGENT_MODEL_DEEPSEEK
        val names = body["models"]?.jsonArray?.mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        AgentSettings(
            defaultModel = defaultModel,
            models = names.ifEmpty { listOf(AGENT_MODEL_DEEPSEEK) },
        )
    }

    suspend fun updateDefaultModel(model: String): AgentSettings = getSettings()

    suspend fun getRunEvents(sessionId: String, cursor: Long): RunEventsResponse =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}/run_events"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("cursor", cursor.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            val body = execute(request)
            json.decodeFromString<RunEventsResponse>(body)
        }

    suspend fun renameSession(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("title", title) }.toString()
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}")
            .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
        Unit
    }

    suspend fun setSessionStarred(sessionId: String, starred: Boolean) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("starred", starred) }.toString()
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}")
            .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
        Unit
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}")
            .delete()
            .build()
        execute(request)
        Unit
    }

    /**
     * 显式取消服务端 run:断连后 run 仍会跑完并持久化,需调用该端点真正停止。
     * 幂等,始终返回 204 无 body。
     */
    suspend fun cancelRun(sessionId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/sessions/${sessionId.urlPathSegment()}/cancel")
            .post("".toRequestBody(null))
            .build()
        execute(request)
        Unit
    }

    /**
     * 发送消息并流式返回 SSE 帧。sessionId 为空时开启新会话,首帧 Session 携带
     * 服务端分配的会话 ID。
     */
    fun streamMessage(
        sessionId: String?,
        message: String,
        model: String? = null,
        images: List<AgentImageInput> = emptyList(),
        files: List<AgentFileInput> = emptyList(),
    ): Flow<AgentStreamPacket> = callbackFlow {
        val payload = buildJsonObject {
            sessionId?.let { put("sessionId", it) }
            put("message", message)
            model?.let { put("model", it) }
            if (images.isNotEmpty()) {
                putJsonArray("images") {
                    images.forEach { image ->
                        add(buildJsonObject {
                            put("name", image.name)
                            put("mimeType", image.mimeType)
                            if (!image.data.isNullOrBlank()) put("data", image.data)
                            if (!image.url.isNullOrBlank()) put("url", image.url)
                        })
                    }
                }
            }
            if (files.isNotEmpty()) {
                putJsonArray("files") {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("name", file.name)
                            put("mimeType", file.mimeType)
                            if (!file.data.isNullOrBlank()) put("data", file.data)
                            if (!file.url.isNullOrBlank()) put("url", file.url)
                        })
                    }
                }
            }
        }.toString()
        val request = Request.Builder()
            .url("${baseUrl()}/api/chat/run_sse")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = sseClient.newCall(request)
        val thread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // 透传服务端错误文案（如「该会话使用的模型已下线」），而不是笼统的状态码。
                        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        close(ApiException(errorMessage(body, response.code)))
                        return@Thread
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        close(ApiException("响应为空"))
                        return@Thread
                    }
                    readAgentSse(source, json) { packet -> trySend(packet) }
                    close()
                }
            } catch (error: Exception) {
                if (call.isCanceled()) {
                    close()
                } else {
                    close(error)
                }
            }
        }
        thread.start()
        awaitClose {
            call.cancel()
            thread.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    private fun execute(request: Request): String {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(errorMessage(body, response.code))
                }
                body
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException("网络请求失败", error)
        }
    }

    /** 与 Web 端 responseError 对齐:优先取响应 JSON 里的 error/message/detail。 */
    private fun errorMessage(body: String, code: Int): String {
        val record = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val fromBody = listOf("error", "message", "detail")
            .firstNotNullOfOrNull { key ->
                record?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        return fromBody ?: "请求失败($code)"
    }

    private fun String.urlPathSegment(): String {
        return java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 按 SSE 帧解析 `/api/chat/run_sse` 响应,事件分发与 Web 端 decodeSseBlock 对齐:
 * 命名事件(session/event/error/done)按名分发;无名 message 帧若内嵌 type 字段
 * 则按内嵌类型分发。
 */
internal fun readAgentSse(
    source: BufferedSource,
    json: Json,
    onPacket: (AgentStreamPacket) -> Unit,
) {
    var eventName = "message"
    val dataLines = mutableListOf<String>()

    fun resetFrame() {
        eventName = "message"
        dataLines.clear()
    }

    fun dispatchFrame() {
        val name = eventName
        val data = dataLines.joinToString("\n")
        resetFrame()
        if (data.isEmpty()) {
            return
        }
        if (data == "[DONE]") {
            onPacket(AgentStreamPacket.Done(null))
            return
        }
        val record = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()

        var type = name
        var payload = record
        if (type == "message" && record != null) {
            val embedded = record["type"]?.jsonPrimitive?.contentOrNull
                ?: record["event"]?.jsonPrimitive?.contentOrNull
            if (!embedded.isNullOrBlank()) {
                type = embedded
                payload = (record["data"] ?: record["payload"])?.jsonObject ?: record
            }
        }

        when (type) {
            "session" -> {
                val sessionPayload = payload
                val sessionId = sessionPayload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: sessionPayload?.get("session_id")?.jsonPrimitive?.contentOrNull
                    ?: sessionPayload?.get("id")?.jsonPrimitive?.contentOrNull
                if (!sessionId.isNullOrBlank() && sessionPayload != null) {
                    onPacket(
                        AgentStreamPacket.Session(
                            sessionId = sessionId,
                            title = sessionPayload["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            isNew = sessionPayload["isNew"]?.jsonPrimitive?.contentOrNull == "true",
                            model = sessionPayload["model"]?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                }
            }
            "error" -> {
                val message = payload?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: payload?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: payload?.get("detail")?.jsonPrimitive?.contentOrNull
                    ?: data
                onPacket(AgentStreamPacket.Failure(message))
            }
            "done" -> {
                onPacket(
                    AgentStreamPacket.Done(
                        payload?.get("sessionId")?.jsonPrimitive?.contentOrNull,
                    ),
                )
            }
            else -> {
                val event = runCatching {
                    json.decodeFromString<AgentEvent>(data)
                }.getOrNull()
                if (event != null) {
                    onPacket(AgentStreamPacket.Event(event))
                }
            }
        }
    }

    while (true) {
        val line = try {
            source.readUtf8Line()
        } catch (_: EOFException) {
            null
        }
        if (line == null) {
            dispatchFrame()
            break
        }
        if (line.isEmpty()) {
            dispatchFrame()
            continue
        }
        if (line.startsWith(":")) {
            continue
        }

        val separator = line.indexOf(':')
        val field = if (separator >= 0) line.substring(0, separator) else line
        val rawValue = if (separator >= 0) line.substring(separator + 1) else ""
        val value = rawValue.removePrefix(" ")
        when (field) {
            "event" -> eventName = value.ifBlank { "message" }
            "data" -> dataLines += value
        }
    }
}
