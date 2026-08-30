package com.arloor.tuke.feature.agent

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arloor.tuke.core.agent.AgentEvent
import com.arloor.tuke.core.agent.AgentFileInput
import com.arloor.tuke.core.agent.AgentImageInput
import com.arloor.tuke.core.agent.AgentPart
import com.arloor.tuke.core.agent.AgentRepository
import com.arloor.tuke.core.agent.AgentStreamKeepAlive
import com.arloor.tuke.core.agent.AgentStreamPacket
import com.arloor.tuke.core.agent.AGENT_MODEL_DEEPSEEK
import com.arloor.tuke.core.agent.agentModelSupportsImages
import com.arloor.tuke.core.agent.agentModelSupportsFiles
import com.arloor.tuke.core.agent.agentDocumentMimeType
import com.arloor.tuke.core.agent.ChatSession
import com.arloor.tuke.core.agent.RunEventsResponse
import com.arloor.tuke.core.agent.SessionDetailResponse
import com.arloor.tuke.engine.EngineController
import com.arloor.tuke.core.network.ApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val EMPTY_SESSION_TITLE = "新对话"

data class AgentUiState(
    val sessions: List<ChatSession> = emptyList(),
    val sessionsLoading: Boolean = true,
    val sessionsLoadingMore: Boolean = false,
    val sessionsHasMore: Boolean = false,
    val sidebarError: String? = null,
    /** null 表示正在撰写新对话(默认状态),与 Web 端 agent.tsx 一致。 */
    val activeSessionId: String? = null,
    val events: List<AgentEvent> = emptyList(),
    val conversationLoading: Boolean = false,
    val running: Boolean = false,
    /** 已提交、等待当前回答结束后发送的消息，可在发送前编辑或删除。 */
    val queuedMessages: List<QueuedAgentMessage> = emptyList(),
    /** 原 SSE 已不可用，当前通过活动 run 的临时 event 流同步。 */
    val backgroundSyncing: Boolean = false,
    val streamError: String? = null,
    val sessionQuery: String = "",
    val starredOnly: Boolean = false,
    /** 当前仍在流式生成或后台恢复的会话，用于抽屉状态提示。 */
    val runningSessionIds: Set<String> = emptySet(),
    val defaultModel: String = AGENT_MODEL_DEEPSEEK,
    val selectedModel: String = AGENT_MODEL_DEEPSEEK,
    /** 模型目录来自 `/api/agent/settings`（源头是 tuke）；加载前只兜底 DeepSeek。 */
    val availableModels: List<String> = listOf(AGENT_MODEL_DEEPSEEK),
    val modelSettingsLoading: Boolean = false,
    /** true 表示已拿到服务端设置，或首次请求失败后已确定使用回退值。 */
    val modelSettingsInitialized: Boolean = false,
    val modelSettingsError: String? = null,
    val pendingImages: List<PendingAgentImage> = emptyList(),
    val pendingFiles: List<PendingAgentFile> = emptyList(),
	val imageUploadsInProgress: Int = 0,
	val imageUploadError: String? = null,
) {
    val activeSession: ChatSession? get() = sessions.firstOrNull { it.id == activeSessionId }

    val modelEditable: Boolean
        get() = activeSessionId == null && events.isEmpty() && !running

    /** 箭头是否展示只取决于设置已初始化及会话是否可编辑，不受后台刷新 loading 影响。 */
    val showModelDropdown: Boolean
        get() = modelSettingsInitialized && modelEditable

    val modelSelectorEnabled: Boolean
        get() = showModelDropdown && !modelSettingsLoading

    /** sessions 已由服务端按标题筛选。 */
    val filteredSessions: List<ChatSession> get() = sessions

    val queuedMessageCount: Int get() = queuedMessages.size
}

data class PendingAgentImage(
    val localId: String,
    val name: String,
    val mimeType: String,
    val url: String,
    val size: Long,
    val data: String? = null,
)

data class PendingAgentFile(
    val localId: String,
    val name: String,
    val mimeType: String,
    val url: String,
    val size: Long,
    val data: String? = null,
)

private const val SESSION_KEY_PREFIX = "session:"
private const val DRAFT_KEY_PREFIX = "draft:"

internal data class ConversationView(
    val events: List<AgentEvent> = emptyList(),
    val running: Boolean = false,
    val backgroundSyncing: Boolean = false,
    val streamError: String? = null,
    val queuedMessages: List<QueuedAgentMessage> = emptyList(),
)

data class QueuedAgentMessage(
    val localId: String,
    val text: String,
    val images: List<PendingAgentImage>,
    val files: List<PendingAgentFile>,
    val model: String,
    val isEditing: Boolean = false,
)

/** 按稳定 key 隔离各会话的实时 View State；新会话收到服务端 ID 后可原子迁移。 */
internal class AgentConversationStore {
    private val views = mutableMapOf<String, ConversationView>()

    fun get(key: String): ConversationView = views[key] ?: ConversationView()

    fun ensure(key: String): ConversationView = views.getOrPut(key) { ConversationView() }

    fun put(key: String, view: ConversationView) {
        views[key] = view
    }

    fun update(key: String, transform: (ConversationView) -> ConversationView): ConversationView {
        return transform(get(key)).also { views[key] = it }
    }

    fun keyForQueuedMessage(localId: String): String? {
        return views.entries.firstOrNull { (_, view) ->
            view.queuedMessages.any { it.localId == localId }
        }?.key
    }

    fun move(fromKey: String, toKey: String): ConversationView {
        val view = views.remove(fromKey) ?: ConversationView()
        views[toKey] = view
        return view
    }
}

private class SessionStream(
    var key: String,
    var sessionId: String?,
    val optimisticEventId: String?,
) {
    var job: Job? = null
    var stopRequested: Boolean = false
    var suppressRecovery: Boolean = false
}

private fun sessionKey(sessionId: String): String = "$SESSION_KEY_PREFIX$sessionId"

private fun sessionIdFromKey(key: String): String? =
    key.takeIf { it.startsWith(SESSION_KEY_PREFIX) }?.removePrefix(SESSION_KEY_PREFIX)

/**
 * AI 助手单页 ViewModel:会话列表、当前对话与流式回复都集中在这里,
 * 交互行为对照 Web 端 app/routes/agent.tsx。
 *
 * 生命周期:Activity 级作用域,切底部 tab 不会销毁;流式生成期间通过
 * [AgentStreamKeepAlive] 起前台服务 + wake lock 保活,切后台后流尽量实时继续;
 * 流异常中断(切后台断网等)后,回前台经 [onForeground] 自动对齐服务端。
 */
class AgentViewModel(
    private val engineController: EngineController,
    private val agentRepository: AgentRepository,
    private val keepAlive: AgentStreamKeepAlive,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var detailJob: Job? = null
    private var sessionSearchJob: Job? = null
    private var sessionListGeneration = 0L
    private var sessionListCursor: String? = null
    private var localEventSeq = 0L
    private var localDraftSeq = 0L
    private var keepAliveHolders = 0
    private var modelSettingsGeneration = 0L
	private var imageUploadGeneration = 0L
	private var reservedImageUploadBytes = 0L

    private val conversationStore = AgentConversationStore()
    private val sessionStreams = mutableMapOf<String, SessionStream>()
    private val syncJobs = mutableMapOf<String, Job>()
    private var activeConversationKey = newDraftKey().also {
        conversationStore.put(it, ConversationView())
    }

    /** 未收到 Done 的 session；切换会话后仍保留，以便切回来时继续对账。 */
    private val interruptedSessionIds = mutableSetOf<String>()

    init {
        // ViewModel 在应用层提前创建；登录态恢复完成后立即预取默认模型，避免首次进入
        // 先渲染内置的 DeepSeek 选项，再与引擎返回的模型目录对齐。
        viewModelScope.launch {
            engineController.state
                .map { it.ready }
                .distinctUntilChanged()
                .collect { ready ->
                    if (!ready) {
                        modelSettingsGeneration++
                        _uiState.update {
                            it.copy(
                                defaultModel = AGENT_MODEL_DEEPSEEK,
                                selectedModel = AGENT_MODEL_DEEPSEEK,
                                availableModels = listOf(AGENT_MODEL_DEEPSEEK),
                                modelSettingsLoading = false,
                                modelSettingsInitialized = false,
                                modelSettingsError = null,
                            )
                        }
                    } else {
                        loadModelSettings()
                        loadSessions()
                    }
                }
        }
    }

    fun loadSessions(quiet: Boolean = false) {
        if (!isReady()) return
        loadModelSettings()
        val generation = ++sessionListGeneration
        val query = _uiState.value.sessionQuery.trim()
        if (!quiet) {
            _uiState.update { it.copy(sessionsLoading = true, sessionsLoadingMore = false) }
        }
        viewModelScope.launch {
            val starredOnly = _uiState.value.starredOnly
            runCatching { agentRepository.listSessions(query = query, starredOnly = starredOnly) }
                .onSuccess { response ->
                    if (generation != sessionListGeneration) return@onSuccess
                    sessionListCursor = response.nextCursor
                    _uiState.update {
                        it.copy(
                            sessions = response.sessions,
                            sessionsLoading = if (quiet) it.sessionsLoading else false,
                            sessionsLoadingMore = false,
                            sessionsHasMore = response.hasMore,
                            sidebarError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != sessionListGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            sessionsLoading = if (quiet) it.sessionsLoading else false,
                            sessionsLoadingMore = false,
                            sidebarError = error.message ?: "暂时无法加载会话。",
                        )
                    }
                }
        }
    }

    fun loadModelSettings() {
        if (!isReady() || _uiState.value.modelSettingsLoading) return
        val generation = ++modelSettingsGeneration
        _uiState.update { it.copy(modelSettingsLoading = true, modelSettingsError = null) }
        viewModelScope.launch {
            runCatching { agentRepository.getSettings() }
                .onSuccess { settings ->
                    if (generation != modelSettingsGeneration) {
                        return@onSuccess
                    }
                    _uiState.update { state ->
                        state.copy(
                            defaultModel = settings.defaultModel,
                            selectedModel = if (state.modelEditable && state.pendingImages.isEmpty() && state.pendingFiles.isEmpty()) {
                                settings.defaultModel
                            } else {
                                state.selectedModel
                            },
                            availableModels = settings.models.ifEmpty { state.availableModels },
                            modelSettingsLoading = false,
                            modelSettingsInitialized = true,
                            modelSettingsError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != modelSettingsGeneration) {
                        return@onFailure
                    }
                    _uiState.update {
                        it.copy(
                            modelSettingsLoading = false,
                            modelSettingsInitialized = true,
                            modelSettingsError = error.message ?: "无法加载模型设置。",
                        )
                    }
                }
        }
    }

    fun selectModel(model: String) {
        val current = _uiState.value
        if (current.activeSessionId != null || current.events.isNotEmpty() || current.running || model !in current.availableModels) {
            return
        }
        if (!agentModelSupportsImages(model)) {
            discardPendingAttachments()
        } else if (!agentModelSupportsFiles(model)) {
            discardPendingFiles()
        }
        _uiState.update { state ->
            state.copy(selectedModel = model)
        }
    }

    fun loadMoreSessions() {
        if (!isReady()) return
        val state = _uiState.value
        val cursor = sessionListCursor
        if (state.sessionsLoading || state.sessionsLoadingMore || !state.sessionsHasMore || cursor.isNullOrBlank()) {
            return
        }
        val generation = sessionListGeneration
        val query = state.sessionQuery.trim()
        _uiState.update { it.copy(sessionsLoadingMore = true) }
        viewModelScope.launch {
            runCatching { agentRepository.listSessions(query = query, cursor = cursor, starredOnly = state.starredOnly) }
                .onSuccess { response ->
                    if (generation != sessionListGeneration) return@onSuccess
                    sessionListCursor = response.nextCursor
                    _uiState.update { current ->
                        current.copy(
                            sessions = (current.sessions + response.sessions).distinctBy { it.id },
                            sessionsLoadingMore = false,
                            sessionsHasMore = response.hasMore,
                            sidebarError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != sessionListGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            sessionsLoadingMore = false,
                            sidebarError = error.message ?: "暂时无法加载更多会话。",
                        )
                    }
                }
        }
    }

    fun setSessionQuery(query: String) {
        if (query == _uiState.value.sessionQuery) return
        sessionSearchJob?.cancel()
        sessionListGeneration++
        sessionListCursor = null
        _uiState.update {
            it.copy(
                sessionQuery = query,
                sessions = emptyList(),
                sessionsLoading = true,
                sessionsLoadingMore = false,
                sessionsHasMore = false,
                sidebarError = null,
            )
        }
        sessionSearchJob = viewModelScope.launch {
            delay(SESSION_SEARCH_DEBOUNCE_MS)
            loadSessions()
        }
    }

    fun setStarredOnly(starredOnly: Boolean) {
        if (starredOnly == _uiState.value.starredOnly) return
        sessionSearchJob?.cancel()
        sessionListGeneration++
        sessionListCursor = null
        _uiState.update {
            it.copy(
                starredOnly = starredOnly,
                sessions = emptyList(),
                sessionsLoading = true,
                sessionsLoadingMore = false,
                sessionsHasMore = false,
                sidebarError = null,
            )
        }
        loadSessions()
    }

    private fun updateConversation(
        key: String,
        transform: (ConversationView) -> ConversationView,
    ): ConversationView {
        val next = conversationStore.update(key, transform)
        val sessionId = sessionIdFromKey(key)
        _uiState.update { state ->
            val runningIds = when {
                sessionId == null -> state.runningSessionIds
                next.running -> state.runningSessionIds + sessionId
                else -> state.runningSessionIds - sessionId
            }
            if (activeConversationKey != key) {
                state.copy(runningSessionIds = runningIds)
            } else {
                state.copy(
                    events = next.events,
                    running = next.running,
                    queuedMessages = next.queuedMessages,
                    backgroundSyncing = next.backgroundSyncing,
                    streamError = next.streamError,
                    runningSessionIds = runningIds,
                )
            }
        }
        return next
    }

    private fun activateConversation(
        key: String,
        sessionId: String?,
        loading: Boolean,
    ) {
        activeConversationKey = key
        val view = conversationStore.ensure(key)
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                events = view.events,
                conversationLoading = loading,
                running = view.running,
                queuedMessages = view.queuedMessages,
                backgroundSyncing = view.backgroundSyncing,
                streamError = view.streamError,
            )
        }
    }

    private fun acquireStreamKeepAlive() {
        keepAliveHolders++
        if (keepAliveHolders == 1) keepAlive.acquire()
    }

    private fun releaseStreamKeepAlive() {
        if (keepAliveHolders == 0) return
        keepAliveHolders--
        if (keepAliveHolders == 0) keepAlive.release()
    }

    /** 回到新对话：只切换视图，其他会话的 SSE 继续在后台接收。 */
    fun startNewConversation() {
        discardPendingAttachments()
        detailJob?.cancel()
        detailJob = null
        val hadSearch = _uiState.value.sessionQuery.isNotEmpty()
        val key = newDraftKey()
        conversationStore.put(key, ConversationView())
        activateConversation(key, sessionId = null, loading = false)
        if (hadSearch) {
            setSessionQuery("")
        } else {
            _uiState.update { it.copy(sessionQuery = "") }
        }

        _uiState.update { it.copy(selectedModel = it.defaultModel) }
    }

    /** 选中会话：已有实时流或 event 同步时直接切换缓存，不中断后台任务。 */
    fun selectSession(sessionId: String) {
        discardPendingAttachments()
        val current = _uiState.value
        if (sessionId == current.activeSessionId && !current.conversationLoading) return
        detailJob?.cancel()
        val key = sessionKey(sessionId)
        val hasLiveState = sessionStreams.containsKey(key) || syncJobs[key]?.isActive == true
        activateConversation(key, sessionId, loading = !hasLiveState)
        current.sessions.firstOrNull { it.id == sessionId }?.model?.let { model ->
            _uiState.update { it.copy(selectedModel = model) }
        }
        if (hasLiveState) {
            detailJob = null
            return
        }
        detailJob = viewModelScope.launch {
            runCatching { agentRepository.getSession(sessionId) }
                .onSuccess { detail ->
                    applySessionDetail(sessionId, detail)
                    if (detail.running) {
                        interruptedSessionIds += sessionId
                        startSessionSync(sessionId)
                    } else {
                        interruptedSessionIds -= sessionId
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        if (state.activeSessionId != sessionId) return@update state
                        state.copy(
                            conversationLoading = false,
                            streamError = error.message ?: "加载会话失败。",
                        )
                    }
                }
        }
    }

    fun addImage(name: String, mimeType: String, bytes: ByteArray) {
        val state = _uiState.value
        val supportedMimeType = mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        val totalBytes = (state.pendingImages.sumOf { it.size } + state.pendingFiles.sumOf { it.size } +
            reservedImageUploadBytes + bytes.size)
        if (!agentModelSupportsImages(state.selectedModel) ||
            state.pendingImages.size + state.pendingFiles.size + state.imageUploadsInProgress >= 4 || !supportedMimeType ||
            bytes.isEmpty() || bytes.size > 10 * 1024 * 1024 || totalBytes > 20 * 1024 * 1024) {
            return
        }
        val localId = "upload-${++localEventSeq}"
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val preview = "data:$mimeType;base64,$encoded"
        val pending = PendingAgentImage(localId, name, mimeType, preview, bytes.size.toLong(), encoded)
        _uiState.update { it.copy(pendingImages = it.pendingImages + pending, imageUploadError = null) }
    }

    fun removeImage(localId: String) {
		_uiState.update { it.copy(pendingImages = it.pendingImages.filterNot { image -> image.localId == localId }, imageUploadError = null) }
    }

    fun addFile(name: String, bytes: ByteArray) {
        val state = _uiState.value
        val mimeType = agentDocumentMimeType(name)
        val totalBytes = (state.pendingImages.sumOf { it.size } + state.pendingFiles.sumOf { it.size } +
            reservedImageUploadBytes + bytes.size)
        if (!agentModelSupportsFiles(state.selectedModel) || mimeType == null ||
            state.pendingImages.size + state.pendingFiles.size + state.imageUploadsInProgress >= 4 ||
            bytes.isEmpty() || bytes.size > 10 * 1024 * 1024 || totalBytes > 20 * 1024 * 1024) {
            val message = if (!agentModelSupportsFiles(state.selectedModel)) {
                "当前模型仅支持上传图片。"
            } else {
                "文件格式不受支持，或附件数量/大小超过限制。"
            }
            _uiState.update { it.copy(imageUploadError = message) }
            return
        }
        val localId = "upload-${++localEventSeq}"
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val pending = PendingAgentFile(localId, name, mimeType, "", bytes.size.toLong(), encoded)
        _uiState.update { it.copy(pendingFiles = it.pendingFiles + pending, imageUploadError = null) }
    }

    fun removeFile(localId: String) {
        _uiState.update { it.copy(pendingFiles = it.pendingFiles.filterNot { file -> file.localId == localId }, imageUploadError = null) }
    }

    private fun discardPendingAttachments() {
		imageUploadGeneration++
		_uiState.update { it.copy(pendingImages = emptyList(), pendingFiles = emptyList(), imageUploadError = null) }
    }

    private fun discardPendingFiles() {
        imageUploadGeneration++
        _uiState.update { it.copy(pendingFiles = emptyList(), imageUploadError = null) }
    }

    fun send(rawText: String) {
        val message = rawText.trim()
        val state = _uiState.value
        val images = state.pendingImages
        val files = state.pendingFiles
        val key = activeConversationKey
        val selectedModel = state.selectedModel
        val view = conversationStore.get(key)
        if ((message.isEmpty() && images.isEmpty() && files.isEmpty()) ||
            state.conversationLoading || state.imageUploadsInProgress > 0 || !isReady()) return
        if (files.isNotEmpty() && !agentModelSupportsFiles(selectedModel)) {
            _uiState.update { it.copy(imageUploadError = "当前模型仅支持上传图片。") }
            return
        }
        val queuedMessage = QueuedAgentMessage(
            localId = "queue-${++localEventSeq}",
            text = message,
            images = images,
            files = files,
            model = selectedModel,
        )
        _uiState.update { it.copy(pendingImages = emptyList(), pendingFiles = emptyList(), imageUploadError = null) }
        if (view.running || view.queuedMessages.isNotEmpty()) {
            updateConversation(key) {
                it.copy(queuedMessages = it.queuedMessages + queuedMessage, streamError = null)
            }
            drainQueuedMessage(key)
            return
        }
        startMessage(key, queuedMessage)
    }

    fun beginQueuedMessageEdit(localId: String) {
        val key = conversationStore.keyForQueuedMessage(localId) ?: return
        updateConversation(key) { view ->
            view.copy(
                queuedMessages = view.queuedMessages.map { item ->
                    if (item.localId == localId) item.copy(isEditing = true) else item
                },
            )
        }
    }

    fun cancelQueuedMessageEdit(localId: String) {
        val key = conversationStore.keyForQueuedMessage(localId) ?: return
        updateConversation(key) { view ->
            view.copy(
                queuedMessages = view.queuedMessages.map { item ->
                    if (item.localId == localId) item.copy(isEditing = false) else item
                },
            )
        }
        drainQueuedMessage(key)
    }

    fun updateQueuedMessage(localId: String, rawText: String) {
        val key = conversationStore.keyForQueuedMessage(localId) ?: return
        val queuedMessage = conversationStore.get(key).queuedMessages
            .firstOrNull { it.localId == localId } ?: return
        val message = rawText.trim()
        if (message.isEmpty() && queuedMessage.images.isEmpty() && queuedMessage.files.isEmpty()) return
        updateConversation(key) { view ->
            view.copy(
                queuedMessages = view.queuedMessages.map { item ->
                    if (item.localId == localId) {
                        item.copy(text = message, isEditing = false)
                    } else {
                        item
                    }
                },
            )
        }
        drainQueuedMessage(key)
    }

    fun deleteQueuedMessage(localId: String) {
        val key = conversationStore.keyForQueuedMessage(localId) ?: return
        updateConversation(key) { view ->
            view.copy(
                queuedMessages = view.queuedMessages.filterNot { it.localId == localId },
            )
        }
        drainQueuedMessage(key)
    }

    private fun startMessage(key: String, queuedMessage: QueuedAgentMessage) {
        val sessionId = sessionIdFromKey(key)
        val message = queuedMessage.text
        val images = queuedMessage.images
        val files = queuedMessage.files
        val selectedModel = queuedMessage.model
        val imageInputs = images.map { image ->
            AgentImageInput(name = image.name, mimeType = image.mimeType, data = image.data)
        }
        val imageParts = images.map { image ->
            AgentPart(type = "image", name = image.name, mimeType = image.mimeType, url = image.url)
        }
        val fileInputs = files.map { file ->
            AgentFileInput(name = file.name, mimeType = file.mimeType, data = file.data)
        }
        val fileParts = files.map { file ->
            AgentPart(type = "file", name = file.name, mimeType = file.mimeType, url = file.url)
        }
        val textParts = message.takeIf { it.isNotEmpty() }?.let { listOf(AgentPart(type = "text", text = it)) }.orEmpty()

        val optimisticEvent = AgentEvent(
            id = newLocalId(),
            author = "user",
            partial = false,
            parts = imageParts + fileParts + textParts,
            local = true,
        )
        updateConversation(key) {
            it.copy(
                events = it.events + optimisticEvent,
                running = true,
                backgroundSyncing = false,
                streamError = null,
            )
        }
        val stream = SessionStream(
            key = key,
            sessionId = sessionId,
            optimisticEventId = optimisticEvent.id,
        )
        sessionStreams[key] = stream
        stopSessionSync(key)
        var completed = false
        var streamAccepted = false
        var streamReportedFailure = false
        var failureMessage: String? = null
        sessionId?.let { interruptedSessionIds -= it }

        // 流式期间前台服务 + wake lock 保活,切后台后流尽量实时继续。
        acquireStreamKeepAlive()
        stream.job = viewModelScope.launch {
            try {
                agentRepository.streamMessage(
                    sessionId = sessionId,
                    message = message,
                    model = selectedModel.takeIf { sessionId == null },
                    images = imageInputs,
                    files = fileInputs,
                ).collect { packet ->
                    streamAccepted = true
                    when (packet) {
                        is AgentStreamPacket.Session -> {
                            adoptStreamSession(stream, packet)
                            if (stream.stopRequested) {
                                cancelBoundStream(stream)
                                return@collect
                            }
                        }
                        is AgentStreamPacket.Event -> mergeEvent(stream.key, packet.event)
                        is AgentStreamPacket.Failure -> {
                            streamReportedFailure = true
                            failureMessage = packet.message
                            updateConversation(stream.key) {
                                it.copy(
                                    events = it.events.filter { event -> !event.partial },
                                    streamError = packet.message,
                                )
                            }
                        }
                        is AgentStreamPacket.Done -> {
                            completed = true
                            (packet.sessionId ?: stream.sessionId)?.let { interruptedSessionIds -= it }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failureMessage = streamErrorMessage(error)
            } finally {
                if (sessionStreams[stream.key] === stream) {
                    sessionStreams.remove(stream.key)
                }
                val shouldRecover =
                    streamAccepted &&
                        !streamReportedFailure &&
                        !completed &&
                        !stream.stopRequested &&
                        !stream.suppressRecovery &&
                        stream.sessionId != null
                when {
                    shouldRecover -> {
                        val id = checkNotNull(stream.sessionId)
                        interruptedSessionIds += id
                        updateConversation(stream.key) {
                            it.copy(
                                events = it.events.filter { event -> !event.partial },
                                running = true,
                                backgroundSyncing = true,
                                // 已切换到 run_events 自动恢复，过程中无需打扰用户。
                                streamError = null,
                            )
                        }
                        startSessionSync(id, pollImmediately = true)
                    }
                    !completed && !stream.stopRequested && !stream.suppressRecovery -> {
                        updateConversation(stream.key) {
                            it.copy(
                                events = it.events.filter { event ->
                                    !event.partial &&
                                        (streamAccepted || event.id != optimisticEvent.id)
                                },
                                running = false,
                                backgroundSyncing = false,
                                streamError = failureMessage
                                    ?: "连接意外中断，未完成的回答已丢弃，请重试。",
                            )
                        }
                        drainQueuedMessage(stream.key)
                    }
                    completed -> {
                        updateConversation(stream.key) {
                            it.copy(running = false, backgroundSyncing = false)
                        }
                        drainQueuedMessage(stream.key)
                    }
                }
                releaseStreamKeepAlive()
                loadSessions(quiet = true)
            }
        }
    }

    private fun drainQueuedMessage(key: String) {
        val view = conversationStore.get(key)
        if (view.running || sessionStreams.containsKey(key) || syncJobs[key]?.isActive == true) return
        val next = view.queuedMessages.firstOrNull() ?: return
        if (next.isEditing) return
        updateConversation(key) { it.copy(queuedMessages = it.queuedMessages.drop(1)) }
        startMessage(key, next)
    }

    /**
     * 停止生成:先显式取消服务端 run(断连后服务端仍会跑完并持久化,取消失败
     * 静默、不影响本地停止),再中断流、丢弃 partial 事件,稍后与服务端对齐。
     */
    fun stopGeneration() {
        val key = activeConversationKey
        val sessionId = sessionIdFromKey(key)
        val stream = sessionStreams[key]
        val hadRun = stream != null || syncJobs[key]?.isActive == true ||
            conversationStore.get(key).running
        if (!hadRun) return

        stopSessionSync(key)
        sessionId?.let { interruptedSessionIds -= it }
        updateConversation(key) {
            it.copy(
                running = false,
                backgroundSyncing = false,
                events = it.events.filter { event -> !event.partial },
                streamError = "已停止本次回答。",
            )
        }

        if (stream != null && stream.sessionId == null) {
            // 新会话尚未收到服务端 ID：保持连接到 session 首帧，拿到 ID 后再精确取消。
            stream.stopRequested = true
            return
        }
        stream?.stopRequested = true
        stream?.let(::cancelBoundStream)
        if (stream == null && sessionId != null) {
            cancelServerRun(sessionId, key)
        }
    }

    /**
     * 回前台时对齐服务端:上次流异常中断且当前未在生成时,重新拉取当前会话。
     * 中断标志保留到下次 Done 或切换会话(服务端可能还在跑),因此每次回前台
     * 都会对齐一次,GET 很轻。
     */
    fun onForeground() {
        if (!isReady()) return
        for (sessionId in interruptedSessionIds.toList()) {
            val key = sessionKey(sessionId)
            if (!sessionStreams.containsKey(key)) {
                startSessionSync(sessionId, pollImmediately = true)
            }
        }
    }

    fun dismissStreamError() {
        updateConversation(activeConversationKey) { it.copy(streamError = null) }
    }

    fun renameSession(sessionId: String, title: String) {
        val normalized = title.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            runCatching { agentRepository.renameSession(sessionId, normalized) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            sidebarError = null,
                            sessions = state.sessions.map {
                                if (it.id == sessionId) it.copy(title = normalized) else it
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(sidebarError = error.message ?: "重命名失败，请重试。")
                    }
                }
        }
    }

    fun setSessionStarred(sessionId: String, starred: Boolean) {
        val previous = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(starred = starred) else it
            }.let { items ->
                if (state.starredOnly) items.filter { it.starred } else items
            }
            state.copy(sidebarError = null, sessions = sessions)
        }
        viewModelScope.launch {
            runCatching { agentRepository.setSessionStarred(sessionId, starred) }
                .onFailure { error ->
                    _uiState.update { state ->
                        val restored = state.sessions
                            .map { if (it.id == sessionId) previous else it }
                            .let { items ->
                                if (items.any { it.id == sessionId }) items else listOf(previous) + items
                            }
                        state.copy(
                            sessions = restored.distinctBy { it.id },
                            sidebarError = error.message ?: "收藏失败，请重试。",
                        )
                    }
                    loadSessions(quiet = true)
                }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { agentRepository.deleteSession(sessionId) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            sidebarError = null,
                            sessions = state.sessions.filterNot { it.id == sessionId },
                        )
                    }
                    if (_uiState.value.activeSessionId == sessionId) {
                        startNewConversation()
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(sidebarError = error.message ?: "删除会话失败，请重试。")
                    }
                }
        }
    }

    /** 流式首帧携带会话 ID：新会话缓存和 Job 在此原子迁移到真实 session key。 */
    private fun adoptStreamSession(stream: SessionStream, packet: AgentStreamPacket.Session) {
        val previousKey = stream.key
        val nextKey = sessionKey(packet.sessionId)
        stream.sessionId = packet.sessionId
        if (previousKey != nextKey) {
            conversationStore.move(previousKey, nextKey)
            if (sessionStreams[previousKey] === stream) {
                sessionStreams.remove(previousKey)
                sessionStreams[nextKey] = stream
            }
            stream.key = nextKey
            if (activeConversationKey == previousKey) {
                activeConversationKey = nextKey
            }
        }
        sessionListGeneration++
        _uiState.update { state ->
            val existing = state.sessions.firstOrNull { it.id == packet.sessionId }
            val session = ChatSession(
                id = packet.sessionId,
                title = packet.title.ifBlank { existing?.title ?: EMPTY_SESSION_TITLE },
                updatedAt = existing?.updatedAt,
                model = packet.model ?: existing?.model ?: _uiState.value.selectedModel,
                starred = existing?.starred ?: false,
            )
            state.copy(
                sessionsLoading = false,
                sessionsLoadingMore = false,
                activeSessionId = if (activeConversationKey == nextKey) {
                    packet.sessionId
                } else {
                    state.activeSessionId
                },
                sessions = (listOf(session) + state.sessions.filterNot { it.id == packet.sessionId })
                    .let { items -> if (state.starredOnly) items.filter { it.starred } else items },
            )
        }
        updateConversation(nextKey) { it.copy(running = !stream.stopRequested) }
    }

    private fun mergeEvent(key: String, incoming: AgentEvent) {
        updateConversation(key) {
            it.copy(events = mergeIncomingEvent(it.events, incoming))
        }
    }

    private fun applySessionDetail(
        sessionId: String,
        detail: SessionDetailResponse,
        clearErrorOnSuccess: Boolean = false,
    ) {
        val key = sessionKey(sessionId)
        updateConversation(key) { view ->
            view.copy(
                events = normalizePersistedEvents(detail.events),
                running = detail.running,
                backgroundSyncing = detail.running,
                streamError = if (clearErrorOnSuccess) null else view.streamError,
            )
        }
        _uiState.update { state ->
            state.copy(
                conversationLoading = if (activeConversationKey == key) false else state.conversationLoading,
                sessions = detail.session?.let { session ->
                    state.sessions.map { if (it.id == session.id) session else it }
                } ?: state.sessions,
                selectedModel = detail.session?.model ?: state.selectedModel,
            )
        }
        if (!detail.running) drainQueuedMessage(key)
    }

    /**
     * 原 SSE 无法重连时，按 cursor 拉取活动 run 的临时 event 流（含 partial 与
     * non-partial）。临时流结束后再读取一次持久 Session，完成最终权威对账。
     */
    private fun startSessionSync(sessionId: String, pollImmediately: Boolean = false) {
        val key = sessionKey(sessionId)
        if (sessionStreams.containsKey(key) || syncJobs[key]?.isActive == true) return
        updateConversation(key) { it.copy(running = true, backgroundSyncing = true) }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val shouldContinue = {
                    isReady() && !sessionStreams.containsKey(key)
                }
                val onFailure: (Throwable) -> Unit = { _ ->
                    updateConversation(key) {
                        it.copy(
                            running = true,
                            backgroundSyncing = true,
                            // 轮询会继续重试，保持恢复过程静默。
                            streamError = null,
                        )
                    }
                }
                runSessionRecoveryLoop(
                    pollImmediately = pollImmediately,
                    intervalMs = SESSION_SYNC_INTERVAL_MS,
                    shouldContinue = shouldContinue,
                    fetchEvents = { cursor -> agentRepository.getRunEvents(sessionId, cursor) },
                    fetchSession = { agentRepository.getSession(sessionId) },
                    onFailure = onFailure,
                    onBatch = { batch ->
                        updateConversation(key) { view ->
                            val merged = batch.events.fold(view.events) { events, event ->
                                mergeIncomingEvent(events, event)
                            }
                            view.copy(
                                events = merged,
                                running = batch.running,
                                backgroundSyncing = batch.running,
                                streamError = null,
                            )
                        }
                    },
                    onSessionDetail = { finalDetail ->
                        applySessionDetail(sessionId, finalDetail, clearErrorOnSuccess = true)
                        if (!finalDetail.running) {
                            interruptedSessionIds -= sessionId
                            loadSessions(quiet = true)
                        }
                    },
                )
            } finally {
                if (syncJobs[key] === job) {
                    syncJobs.remove(key)
                    if (!conversationStore.get(key).running) {
                        drainQueuedMessage(key)
                    }
                }
            }
        }
        syncJobs[key] = job
        job.start()
    }

    /** 连接级异常(切后台断网、网络切换等)映射为友好文案;HTTP/服务端错误保留原始文案。 */
    private fun streamErrorMessage(error: Throwable): String {
        if (error is ApiException) return error.message ?: "发送消息失败，请重试。"
        return "网络连接中断，正在尝试恢复…"
    }

    private fun cancelBoundStream(stream: SessionStream) {
        val sessionId = stream.sessionId ?: return
        sessionStreams.remove(stream.key, stream)
        stream.job?.cancel()
        cancelServerRun(sessionId, stream.key)
    }

    private fun cancelServerRun(sessionId: String, key: String) {
        viewModelScope.launch {
            runCatching { agentRepository.cancelRun(sessionId) }
            delay(150)
            runCatching { agentRepository.getSession(sessionId) }
                .onSuccess { detail ->
                    updateConversation(key) { view ->
                        view.copy(
                            events = normalizePersistedEvents(detail.events),
                            running = false,
                            backgroundSyncing = false,
                            streamError = "已停止本次回答。",
                        )
                    }
                    drainQueuedMessage(key)
                }
        }
    }

    private fun stopSessionSync(key: String) {
        syncJobs.remove(key)?.cancel()
    }

    private fun isReady(): Boolean = engineController.state.value.ready

    private fun newLocalId(): String = "local-${++localEventSeq}"

    private fun newDraftKey(): String = "$DRAFT_KEY_PREFIX${++localDraftSeq}"

    override fun onCleared() {
        sessionStreams.values.toSet().forEach {
            it.suppressRecovery = true
            it.job?.cancel()
        }
        sessionStreams.clear()
        syncJobs.values.toSet().forEach { it.cancel() }
        syncJobs.clear()
        detailJob?.cancel()
        sessionSearchJob?.cancel()
        if (keepAliveHolders > 0) {
            keepAliveHolders = 0
            keepAlive.release()
        }
    }

    companion object {
        private const val SESSION_SEARCH_DEBOUNCE_MS = 300L
        private const val SESSION_SYNC_INTERVAL_MS = 1_500L
    }
}

internal fun isUserEvent(event: AgentEvent): Boolean = event.isUser

internal fun eventText(event: AgentEvent): String = event.text

/**
 * 会话恢复轮询的可测试核心。首次请求可立即执行；任何普通请求失败都会等待后重试，
 * 仅会话终态、调用方失效或协程取消会结束循环。
 */
internal suspend fun runEventSyncLoop(
    pollImmediately: Boolean,
    intervalMs: Long,
    shouldContinue: () -> Boolean,
    fetch: suspend () -> RunEventsResponse,
    onFailure: (Throwable) -> Unit,
    onBatch: (RunEventsResponse) -> Unit,
) {
    var waitBeforeAttempt = !pollImmediately
    while (shouldContinue()) {
        if (waitBeforeAttempt) delay(intervalMs)
        waitBeforeAttempt = true
        if (!shouldContinue()) break

        val batch = try {
            fetch()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            continue
        }
        onBatch(batch)
        if (!batch.running) break
    }
}

/**
 * 完整的断线恢复状态机：消费一个 run 的 cursor 流，终止后用 Session 对账；
 * 若对账发现同一会话已开始新的 run，则 cursor 从 0 开始进入下一轮。
 */
internal suspend fun runSessionRecoveryLoop(
    pollImmediately: Boolean,
    intervalMs: Long,
    shouldContinue: () -> Boolean,
    fetchEvents: suspend (cursor: Long) -> RunEventsResponse,
    fetchSession: suspend () -> SessionDetailResponse,
    onFailure: (Throwable) -> Unit,
    onBatch: (RunEventsResponse) -> Unit,
    onSessionDetail: (SessionDetailResponse) -> Unit,
) {
    var cursor = 0L
    var immediate = pollImmediately
    while (shouldContinue()) {
        runEventSyncLoop(
            pollImmediately = immediate,
            intervalMs = intervalMs,
            shouldContinue = shouldContinue,
            fetch = { fetchEvents(cursor) },
            onFailure = onFailure,
            onBatch = { batch ->
                cursor = batch.nextCursor
                onBatch(batch)
            },
        )
        if (!shouldContinue()) break

        val detail = try {
            fetchSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            delay(intervalMs)
            immediate = true
            continue
        }
        onSessionDetail(detail)
        if (!detail.running) break

        // Another run started after the previous run_events snapshot settled.
        cursor = 0
        immediate = true
    }
}

private fun sameResponse(current: AgentEvent, incoming: AgentEvent): Boolean {
    val responseId = current.responseId ?: return false
    if (responseId != incoming.responseId) return false
    if (!current.author.equals(incoming.author, ignoreCase = true)) return false
    if (
        current.invocationId != null &&
        incoming.invocationId != null &&
        current.invocationId != incoming.invocationId
    ) {
        return false
    }
    return true
}

/** partial 增量按 UI 语义合并:仅连续活动段内聚合思考/hosted tool,其余规则保持不变。 */
private fun mergeParts(current: List<AgentPart>, incoming: List<AgentPart>): List<AgentPart> {
    val parts = current.toMutableList()

    fun trailingActivityIndex(predicate: (AgentPart) -> Boolean): Int {
        var matchingIndex = -1
        for (index in parts.lastIndex downTo 0) {
            val candidate = parts[index]
            val isActivity = candidate.isThinking || candidate.isHostedToolStatus
            if (!isActivity) break
            if (predicate(candidate)) matchingIndex = index
        }
        return matchingIndex
    }

    for (part in incoming) {
        if (part.isThinking) {
            val hasHostedToolStatus = parts.any { it.isHostedToolStatus }
            val thinkingIndex = if (hasHostedToolStatus) {
                parts.indexOfLast { it.isThinking }
            } else {
                trailingActivityIndex { it.isThinking }
            }
            if (thinkingIndex >= 0) {
                val thinking = parts[thinkingIndex]
                parts[thinkingIndex] = thinking.copy(
                    text = (thinking.text ?: "") + (part.text ?: ""),
                )
            } else {
                parts += part
            }
            continue
        }
        if (part.isText) {
            val last = parts.lastOrNull()
            if (last != null && last.type == part.type) {
                parts[parts.lastIndex] = last.copy(text = (last.text ?: "") + (part.text ?: ""))
            } else {
                parts += part
            }
            continue
        }
        if (part.isHostedToolStatus) {
            val hostedToolIndex = parts.indexOfLast {
                it.isHostedToolStatus && it.name == part.name
            }
            if (hostedToolIndex >= 0) {
                parts[hostedToolIndex] = part
            } else {
                // Keep provider output_text visible, while anchoring a late
                // hosted lifecycle event directly below nearby thinking.
                val thinkingIndex = parts.indexOfLast { it.isThinking }
                if (thinkingIndex >= 0) {
                    var insertIndex = thinkingIndex + 1
                    while (insertIndex < parts.size && parts[insertIndex].isHostedToolStatus) {
                        insertIndex += 1
                    }
                    parts.add(insertIndex, part)
                } else {
                    parts += part
                }
            }
            continue
        }
        val matchIndex = parts.indexOfFirst {
            it.type == part.type && part.callId != null && it.callId == part.callId
        }
        if (matchIndex >= 0) {
            parts[matchIndex] = part
        } else {
            parts += part
        }
    }
    return aggregateTurnParts(parts)
}

/** responseId 对应 agent loop 的一次模型调用；轮内聚合正文，轮间保持独立。 */
private fun aggregateTurnParts(parts: List<AgentPart>): List<AgentPart> {
    val thinkingText = StringBuilder()
    val bodyText = StringBuilder()
    val hostedStatuses = mutableListOf<AgentPart>()
    val toolCalls = mutableListOf<AgentPart>()
    val otherParts = mutableListOf<AgentPart>()
    for (part in parts) {
        when {
            part.isThinking -> thinkingText.append(part.text.orEmpty())
            part.isHostedToolStatus -> {
                val index = hostedStatuses.indexOfFirst { it.name == part.name }
                if (index >= 0) hostedStatuses[index] = part else hostedStatuses += part
            }
            part.isToolCall -> toolCalls += part
            part.isText -> bodyText.append(part.text.orEmpty())
            else -> otherParts += part
        }
    }
    return buildList {
        if (thinkingText.isNotEmpty()) add(AgentPart(type = "thinking", text = thinkingText.toString()))
        addAll(hostedStatuses)
        addAll(toolCalls)
        if (bodyText.isNotEmpty()) add(AgentPart(type = "text", text = bodyText.toString()))
        addAll(otherParts)
    }
}

/**
 * Session replay 只含 canonical non-partial 事件，必须与 SSE final 走同一展示归并。
 * partial 保持原始增量，确保正文在工具调用完成前仍然逐 chunk 可见。
 */
internal fun normalizePersistedEvents(events: List<AgentEvent>): List<AgentEvent> =
    events.map(::normalizeCompletedEvent)

private fun normalizeCompletedEvent(event: AgentEvent): AgentEvent {
    if (event.partial || event.isUser) return event
    return event.copy(parts = aggregateTurnParts(event.parts))
}

private fun completeResponseEvent(current: AgentEvent, incoming: AgentEvent): AgentEvent {
    val parts = incoming.parts.toMutableList()
    current.parts.filter { it.isHostedToolStatus }.forEach { status ->
        val completed = status.copy(status = "completed")
        val index = parts.indexOfFirst { it.isHostedToolStatus && it.name == status.name }
        if (index >= 0) parts[index] = completed else parts += completed
    }
    return normalizeCompletedEvent(incoming.copy(id = current.id, parts = parts))
}

/**
 * 合并一个到达的事件,行为对照 Web 端 mergeIncomingEvent:
 * 同 id 的非 partial 快照覆盖 partial;同 responseId 的 partial 做增量合并;
 * 本地乐观用户消息被服务端回显替换。
 */
internal fun mergeIncomingEvent(
    currentEvents: List<AgentEvent>,
    incoming: AgentEvent,
): List<AgentEvent> {
    if (incoming.reset) {
        return currentEvents.filterNot { it.partial && sameResponse(it, incoming) }
    }
    val duplicateIndex = currentEvents.indexOfFirst { it.id != null && it.id == incoming.id }
    if (duplicateIndex >= 0) {
        val current = currentEvents[duplicateIndex]
        if (current.partial && !incoming.partial) {
            return currentEvents.toMutableList().also {
                it[duplicateIndex] = completeResponseEvent(current, incoming)
            }
        }
        return currentEvents
    }

    val responseIndex = currentEvents.indexOfFirst { sameResponse(it, incoming) }
    if (responseIndex >= 0) {
        val current = currentEvents[responseIndex]
        if (!current.partial && incoming.partial) return currentEvents
        val merged = if (incoming.partial) {
            current.copy(
                parts = mergeParts(current.parts, incoming.parts),
                partial = true,
                timestamp = incoming.timestamp ?: current.timestamp,
                usage = incoming.usage ?: current.usage,
                error = incoming.error ?: current.error,
                local = false,
            )
        } else {
            completeResponseEvent(current, incoming)
        }
        return currentEvents.toMutableList().also { it[responseIndex] = merged }
    }

    if (isUserEvent(incoming)) {
        val incomingText = eventText(incoming).trim()
        val incomingImages = incoming.parts.filter { it.isImage }.map { "${it.name}|${it.mimeType}" }
        val optimisticIndex = currentEvents.indexOfLast {
            it.local && isUserEvent(it) && eventText(it).trim() == incomingText &&
                it.parts.filter { part -> part.isImage }.map { part -> "${part.name}|${part.mimeType}" } == incomingImages
        }
        if (optimisticIndex >= 0) {
            return currentEvents.toMutableList().also { it[optimisticIndex] = incoming }
        }
    }

    return currentEvents + normalizeCompletedEvent(incoming)
}

/** 会话列表里的相对时间,与 Web 端 formatSessionTime 对齐。 */
internal fun formatSessionTime(value: String?, now: Long = System.currentTimeMillis()): String {
    if (value.isNullOrBlank()) return ""
    val time = runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val text = value.substringBefore('.').ifBlank { value }
        parser.parse(text)?.time
    }.getOrNull() ?: return ""
    val elapsed = now - time
    if (elapsed in 0 until 60_000) return "刚刚"
    if (elapsed in 0 until 60 * 60_000) return "${(elapsed / 60_000).coerceAtLeast(1)} 分钟前"
    val sameDay = run {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.format(Date(time)) == fmt.format(Date(now))
    }
    return if (sameDay) {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
    } else {
        SimpleDateFormat("M月d日", Locale.US).format(Date(time))
    }
}
