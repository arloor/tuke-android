package com.arloor.tuke.core.agent

import com.arloor.tuke.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 本地 Agent harness 返回的会话摘要。 */
@Serializable
data class ChatSession(
    val id: String,
    val title: String = "",
    val updatedAt: String? = null,
    val model: String? = null,
    val starred: Boolean = false,
)

const val AGENT_MODEL_DEEPSEEK = "deepseek"

fun agentModelLabel(model: String): String = if (model == AGENT_MODEL_DEEPSEEK) "DeepSeek" else model

/** 模型选择面板中的简介文案；未知模型不展示简介。 */
fun agentModelSubtitle(model: String): String =
    if (model == AGENT_MODEL_DEEPSEEK) "深度推理，支持图像理解" else ""

/** 模型品牌 Logo 资源。 */
fun agentModelIcon(model: String): Int = R.drawable.ic_model_deepseek

/**
 * `/api/agent/settings` 响应。模型列表由 tuke 的 `GET /api/chat/models` 提供，
 * 客户端不再内置完整目录；请求失败前的兜底只保留 DeepSeek。
 */
@Serializable
data class AgentSettings(
    val defaultModel: String = AGENT_MODEL_DEEPSEEK,
    val models: List<String> = listOf(AGENT_MODEL_DEEPSEEK),
)

fun agentModelSupportsImages(model: String): Boolean =
    model == AGENT_MODEL_DEEPSEEK

fun agentModelSupportsFiles(@Suppress("UNUSED_PARAMETER") model: String): Boolean = false

fun agentImageMimeType(name: String, declaredMimeType: String?): String? {
    val declared = declaredMimeType?.trim()?.lowercase().orEmpty()
    if (declared in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) return declared
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

@Serializable
data class SessionListResponse(
    val sessions: List<ChatSession> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class SessionDetailResponse(
    val session: ChatSession? = null,
    val events: List<AgentEvent> = emptyList(),
    /** 瞬时运行态，由 tuke 的活动 run 注册表提供，不属于持久化 Session。 */
    val running: Boolean = false,
)

/** 活动 run 的临时 event 流快照；cursor 只在该 run 生命周期内有效。 */
@Serializable
data class RunEventsResponse(
    val events: List<AgentEvent> = emptyList(),
    val nextCursor: Long = 0,
    val running: Boolean = false,
)

/** tuke EventView 的 part:text / thinking / tool_call / tool_result / hosted_tool_status。 */
@Serializable
data class AgentPart(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val callId: String? = null,
    val status: String? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
    val data: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
) {
    val isText: Boolean get() = type == "text"
    val isThinking: Boolean get() = type == "thinking"
    val isToolCall: Boolean get() = type == "tool_call"
    val isToolResult: Boolean get() = type == "tool_result"
    val isHostedToolStatus: Boolean get() = type == "hosted_tool_status"
    val isImage: Boolean get() = type == "image"
    fun previewUri(): String? = url ?: data?.takeIf { it.isNotBlank() }?.let { encoded ->
        "data:${mimeType ?: "image/jpeg"};base64,$encoded"
    }
    val isFile: Boolean get() = type == "file"
}

enum class HostedToolKind {
    WebSearch,
    XSearch,
    ImageSearch,
    ImageUnderstanding,
    XVideoUnderstanding,
    FileSearch,
    CodeInterpreter,
    ImageGeneration,
    ComputerUse,
    Mcp,
    Shell,
    ApplyPatch,
    Other,
}

/** 将 provider 的 hosted tool 名称归一成稳定的前端展示类型。 */
fun agentHostedToolKind(name: String?): HostedToolKind {
    val normalized = name
        ?.trim()
        ?.lowercase()
        ?.removeSuffix("_call")
        ?.removeSuffix("_preview")
        .orEmpty()
    return when (normalized) {
        "web_search" -> HostedToolKind.WebSearch
        "x_search" -> HostedToolKind.XSearch
        "search_images", "image_search" -> HostedToolKind.ImageSearch
        "view_image", "image_understanding" -> HostedToolKind.ImageUnderstanding
        "view_x_video", "x_video_understanding" -> HostedToolKind.XVideoUnderstanding
        "file_search" -> HostedToolKind.FileSearch
        "code_interpreter" -> HostedToolKind.CodeInterpreter
        "image_generation" -> HostedToolKind.ImageGeneration
        "computer", "computer_use" -> HostedToolKind.ComputerUse
        "mcp" -> HostedToolKind.Mcp
        "shell", "local_shell" -> HostedToolKind.Shell
        "apply_patch" -> HostedToolKind.ApplyPatch
        else -> HostedToolKind.Other
    }
}

fun agentHostedToolStatusLabel(name: String?, status: String?): String {
    val kind = agentHostedToolKind(name)
    val action = when (kind) {
        HostedToolKind.WebSearch -> "网页搜索"
        HostedToolKind.XSearch -> "X 搜索"
        HostedToolKind.ImageSearch -> "图片搜索"
        HostedToolKind.ImageUnderstanding -> "图片理解"
        HostedToolKind.XVideoUnderstanding -> "X 视频理解"
        HostedToolKind.FileSearch -> "文件搜索"
        HostedToolKind.CodeInterpreter -> "代码执行"
        HostedToolKind.ImageGeneration -> "图片生成"
        HostedToolKind.ComputerUse -> "电脑操作"
        HostedToolKind.Mcp -> "MCP 工具"
        HostedToolKind.Shell -> "命令执行"
        HostedToolKind.ApplyPatch -> "文件修改"
        HostedToolKind.Other -> name?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "托管工具 $it" }
            ?: "托管工具"
    }
    return when (status?.trim()?.lowercase()) {
        "completed", "succeeded", "success" -> "${action}完成"
        "failed", "error", "incomplete", "expired" -> "${action}失败"
        "cancelled", "canceled" -> "${action}已取消"
        "pending", "queued" -> "正在准备$action"
        else -> when (kind) {
            HostedToolKind.WebSearch -> if (status == "searching") "正在搜索网页" else "正在准备联网搜索"
            HostedToolKind.XSearch -> "正在搜索 X"
            HostedToolKind.ImageSearch -> "正在搜索图片"
            HostedToolKind.ImageUnderstanding -> "正在理解图片"
            HostedToolKind.XVideoUnderstanding -> "正在理解 X 视频"
            HostedToolKind.FileSearch -> "正在搜索文件"
            HostedToolKind.CodeInterpreter -> "正在执行代码"
            HostedToolKind.ImageGeneration -> "正在生成图片"
            HostedToolKind.ComputerUse -> "正在操作电脑"
            HostedToolKind.Mcp -> "正在调用 MCP 工具"
            HostedToolKind.Shell -> "正在执行命令"
            HostedToolKind.ApplyPatch -> "正在修改文件"
            HostedToolKind.Other -> "正在使用$action"
        }
    }
}

fun agentHostedToolStatusSucceeded(status: String?): Boolean =
    status?.trim()?.lowercase() in setOf("completed", "succeeded", "success")

fun agentHostedToolStatusFailed(status: String?): Boolean =
    status?.trim()?.lowercase() in setOf(
        "failed",
        "error",
        "incomplete",
        "expired",
        "cancelled",
        "canceled",
    )

fun agentHostedToolStatusTerminal(status: String?): Boolean =
    agentHostedToolStatusSucceeded(status) || agentHostedToolStatusFailed(status)

data class AgentImageInput(
    val name: String,
    val mimeType: String,
    val url: String? = null,
    val data: String? = null,
)

data class AgentFileInput(
    val name: String,
    val mimeType: String,
    val url: String? = null,
    val data: String? = null,
)

fun agentDocumentMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "tsv" -> "text/tsv"
    "json" -> "application/json"
    "xml" -> "text/xml"
    "html", "htm" -> "text/html"
    "yaml", "yml" -> "application/yaml"
    "txt", "text", "log", "conf", "ini", "toml",
    "c", "cc", "cpp", "cxx", "h", "hpp", "java", "kt", "kts",
    "go", "rs", "py", "rb", "php", "swift", "dart", "lua", "r",
    "js", "mjs", "jsx", "ts", "tsx", "css", "sql", "sh", "bash",
    "zsh", "ps1", "tex" -> "text/plain"
    else -> null
}

@Serializable
data class AgentUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * 对话事件。流式期间 partial 事件按 responseId 合并;非 partial 事件是同一份
 * 回复的权威快照。reset 撤销失败 attempt 的 partial preview。local 标记仅用于
 * 发送中的本地乐观消息,不参与序列化。
 */
@Serializable
data class AgentEvent(
    val id: String? = null,
    val responseId: String? = null,
    val invocationId: String? = null,
    val author: String = "assistant",
    val partial: Boolean = false,
    val reset: Boolean = false,
    val timestamp: String? = null,
    val parts: List<AgentPart> = emptyList(),
    val usage: AgentUsage? = null,
    val error: JsonElement? = null,
    val local: Boolean = false,
) {
    val isUser: Boolean
        get() = author.equals("user", ignoreCase = true) || author.equals("human", ignoreCase = true)

    /** 拼接所有正式文本 part;思考与工具 part 不包含在内。 */
    val text: String
        get() = parts.filter { it.isText }.mapNotNull { it.text }.joinToString("")

    val stableId: String
        get() = id ?: responseId ?: "event-${hashCode()}"
}

/** `/api/agent/run_sse` SSE 帧。 */
sealed interface AgentStreamPacket {
    /** 新会话或既有会话的标识帧,流式回复开始前到达。 */
    data class Session(
        val sessionId: String,
        val title: String,
        val isNew: Boolean,
        val model: String? = null,
    ) : AgentStreamPacket

    data class Event(val event: AgentEvent) : AgentStreamPacket

    /** 上游以 error 帧通报失败(模型错误、限流等),流随后结束。 */
    data class Failure(val message: String) : AgentStreamPacket

    data class Done(val sessionId: String?) : AgentStreamPacket
}
