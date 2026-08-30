package com.arloor.tuke.feature.agent

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectState
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.arloor.tuke.core.agent.AgentEvent
import com.arloor.tuke.core.agent.AgentPart
import com.arloor.tuke.core.agent.AgentUsage
import com.arloor.tuke.core.agent.HostedToolKind
import com.arloor.tuke.core.agent.agentHostedToolKind
import com.arloor.tuke.core.agent.agentHostedToolStatusFailed
import com.arloor.tuke.core.agent.agentHostedToolStatusLabel
import com.arloor.tuke.core.agent.agentHostedToolStatusSucceeded
import com.arloor.tuke.core.agent.agentHostedToolStatusTerminal
import com.arloor.tuke.core.agent.agentModelSupportsImages
import com.arloor.tuke.core.agent.agentModelSupportsFiles
import com.arloor.tuke.core.agent.agentImageMimeType
import com.arloor.tuke.core.agent.decodeAgentImageDataUri
import com.arloor.tuke.core.agent.ChatSession
import com.arloor.tuke.core.agent.agentModelIcon
import com.arloor.tuke.core.agent.agentModelLabel
import com.arloor.tuke.core.agent.agentModelSubtitle
import com.arloor.tuke.engine.EngineController
import com.arloor.tuke.ui.BannerTone
import com.arloor.tuke.ui.Danger
import com.arloor.tuke.ui.LoadingState
import com.arloor.tuke.ui.NoticeBanner
import com.arloor.tuke.ui.PageHorizontalPadding
import com.arloor.tuke.ui.Primary
import com.arloor.tuke.ui.PrimaryContainer
import com.arloor.tuke.ui.RequireAuth
import com.arloor.tuke.ui.SectionCard
import com.arloor.tuke.ui.SpacingMd
import com.arloor.tuke.ui.SpacingLg
import com.arloor.tuke.ui.SpacingSm
import com.arloor.tuke.ui.SpacingXs
import com.arloor.tuke.ui.TextMuted
import com.arloor.tuke.ui.TextPrimary
import com.arloor.tuke.ui.TextSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import java.io.File

/**
 * AI 助手单页:默认展示新对话,会话列表通过左上角按钮以抽屉展开并支持搜索,
 * 交互对照 Web 端 app/routes/agent.tsx。
 */
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    engineController: EngineController,
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectState()
    val engineState by engineController.state.collectState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    // 回前台时对齐服务端:流异常中断(切后台断网等)后服务端可能已跑完并持久化。
    LifecycleStartEffect(Unit) {
        viewModel.onForeground()
        onStopOrDispose { }
    }

    // 登录态下首次进入请求通知权限(API 33+),用于前台服务通知;
    // 结果忽略——拒绝也能跑前台服务,只是通知不可见。
    val context = LocalContext.current
    val drawerModifier = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Modifier.width(420.dp)
    } else {
        Modifier.fillMaxWidth(0.95f)
    }
    val handleAttachmentsSelected: (List<Uri>) -> Unit = { uris ->
        uris.take(4).forEach { uri ->
            scope.launch {
                val selected = withContext(Dispatchers.IO) {
                    var size: Long? = null
                    val declaredMimeType = context.contentResolver.getType(uri)
                    val name = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        } else {
                            null
                        }
                    } ?: "file-${System.currentTimeMillis()}.txt"
                    val bytes = if (size != null && size!! > 10 * 1024 * 1024) {
                        null
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    AttachmentSelection(name, declaredMimeType, size, bytes)
                }
                val bytes = selected.bytes
                val imageMimeType = agentImageMimeType(selected.name, selected.declaredMimeType)
                when {
                    selected.size != null && selected.size > 10 * 1024 * 1024 ->
                        Toast.makeText(context, "附件不能超过 10 MiB", Toast.LENGTH_SHORT).show()
                    bytes == null -> Toast.makeText(context, "无法读取附件", Toast.LENGTH_SHORT).show()
                    bytes.size > 10 * 1024 * 1024 -> Toast.makeText(context, "附件不能超过 10 MiB", Toast.LENGTH_SHORT).show()
                    imageMimeType != null -> viewModel.addImage(selected.name, imageMimeType, bytes)
                    else -> viewModel.addFile(selected.name, bytes)
                }
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4),
        handleAttachmentsSelected,
    )
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        handleAttachmentsSelected,
    )
    val pendingCameraUri = remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri.value
        pendingCameraUri.value = null
        if (success && uri != null) {
            handleAttachmentsSelected(listOf(uri))
        }
    }
    val launchCamera: () -> Unit = {
        val photoFile = File(context.cacheDir, "camera/capture-${System.currentTimeMillis()}.jpg")
        photoFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile,
        )
        pendingCameraUri.value = uri
        takePictureLauncher.launch(uri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(engineState.ready) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (engineState.ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(engineState.ready) {
        if (engineState.ready) {
            viewModel.loadSessions()
        }
    }

    RequireAuth(
        isLoggedIn = engineState.ready || (engineState.hasApiKey && engineState.starting),
        isRestoring = engineState.starting,
        loginContent = {
            EngineSetupCard(
                hasApiKey = engineState.hasApiKey,
                error = engineState.error,
                onOpenSettings = onOpenSettings,
            )
        },
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = drawerModifier) {
                    AgentSessionDrawer(
                        uiState = uiState,
                        onNewChat = {
                            viewModel.startNewConversation()
                            scope.launch { drawerState.close() }
                        },
                        onRefresh = { viewModel.loadSessions() },
                        onLoadMore = viewModel::loadMoreSessions,
                        onQueryChange = viewModel::setSessionQuery,
                        onStarredOnlyChange = viewModel::setStarredOnly,
                        onSelect = { sessionId ->
                            viewModel.selectSession(sessionId)
                            scope.launch { drawerState.close() }
                        },
                        onRename = { renameTarget = it },
                        onDelete = { deleteTarget = it },
                        onToggleStarred = { session ->
                            viewModel.setSessionStarred(session.id, !session.starred)
                        },
                    )
                }
            },
        ) {
            AgentChatContent(
                uiState = uiState,
                engineStarting = engineState.starting,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewChat = viewModel::startNewConversation,
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
                onDismissError = viewModel::dismissStreamError,
                onSelectModel = viewModel::selectModel,
                onAttachImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onAttachFile = {
                    attachmentPicker.launch(arrayOf("*/*"))
                },
                onAttachCamera = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        launchCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onRemoveImage = viewModel::removeImage,
                onRemoveFile = viewModel::removeFile,
                onBeginQueuedMessageEdit = viewModel::beginQueuedMessageEdit,
                onCancelQueuedMessageEdit = viewModel::cancelQueuedMessageEdit,
                onUpdateQueuedMessage = viewModel::updateQueuedMessage,
                onDeleteQueuedMessage = viewModel::deleteQueuedMessage,
            )
        }

        renameTarget?.let { target ->
            RenameSessionDialog(
                session = target,
                onDismiss = { renameTarget = null },
                onConfirm = { title ->
                    renameTarget = null
                    viewModel.renameSession(target.id, title)
                },
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除这段对话？") },
                text = {
                    Text("「${target.title.ifBlank { EMPTY_SESSION_TITLE }}」及其中的所有消息都将被永久删除，此操作无法撤销。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            viewModel.deleteSession(target.id)
                        },
                    ) {
                        Text("删除对话", color = Danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

private data class AttachmentSelection(
    val name: String,
    val declaredMimeType: String?,
    val size: Long?,
    val bytes: ByteArray?,
)

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun AgentSessionDrawer(
    uiState: AgentUiState,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onQueryChange: (String) -> Unit,
    onStarredOnlyChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRename: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onToggleStarred: (ChatSession) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpacingMd),
    ) {
        Spacer(modifier = Modifier.height(SpacingXs))
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(SpacingXs))
            Text("新建对话")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SpacingMd, bottom = SpacingSm),
            horizontalArrangement = Arrangement.spacedBy(SpacingXs),
        ) {
            SessionFilterChip(
                label = "全部",
                selected = !uiState.starredOnly,
                onClick = { onStarredOnlyChange(false) },
                testTag = "agent.session.filter.all",
            )
            SessionFilterChip(
                label = "收藏",
                selected = uiState.starredOnly,
                onClick = { onStarredOnlyChange(true) },
                testTag = "agent.session.filter.starred",
                icon = if (uiState.starredOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
            )
        }

        if (uiState.sessions.isNotEmpty() || uiState.sessionQuery.isNotEmpty() || uiState.starredOnly) {
            OutlinedTextField(
                value = uiState.sessionQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索对话") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (uiState.sessionQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清除搜索",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )
        }

        val filtered = uiState.filteredSessions
        val sessionListState = rememberLazyListState()
        val pullRefreshState = rememberPullRefreshState(
            refreshing = uiState.sessionsLoading,
            onRefresh = onRefresh,
        )
        val firstSessionId = filtered.firstOrNull()?.id
        LaunchedEffect(uiState.activeSessionId, firstSessionId) {
            if (uiState.activeSessionId != null && uiState.activeSessionId == firstSessionId) {
                sessionListState.requestScrollToItem(0)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .pullRefresh(pullRefreshState),
        ) {
            when {
                uiState.sessionsLoading && uiState.sessions.isEmpty() -> {
                    LoadingState("正在加载会话...")
                }
                uiState.sessions.isEmpty() && uiState.sessionQuery.isEmpty() -> {
                    DrawerEmptyHint(
                        icon = if (uiState.starredOnly) Icons.Filled.StarBorder else Icons.Default.ChatBubbleOutline,
                        title = if (uiState.starredOnly) "还没有收藏的对话" else "还没有对话",
                        detail = if (uiState.starredOnly) {
                            "点会话右侧的星星，就能把它放到这里。"
                        } else {
                            "发送第一条消息后，会话会保存在这里。"
                        },
                    )
                }
                uiState.sessions.isEmpty() -> {
                    DrawerEmptyHint(
                        icon = Icons.Default.Search,
                        title = if (uiState.starredOnly) "收藏里没有匹配的对话" else "没有匹配的对话",
                        detail = "换个关键词试试。",
                    )
                }
                else -> {
                    LazyColumn(
                        state = sessionListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = SpacingSm),
                        verticalArrangement = Arrangement.spacedBy(SpacingXs),
                    ) {
                        items(items = filtered, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                active = session.id == uiState.activeSessionId,
                                running = session.id in uiState.runningSessionIds,
                                onClick = { onSelect(session.id) },
                                onRename = { onRename(session) },
                                onDelete = { onDelete(session) },
                                onToggleStarred = { onToggleStarred(session) },
                            )
                        }
                        if (uiState.sessionsHasMore) {
                            item(key = "load-more-sessions") {
                                LaunchedEffect(filtered.size, uiState.sessionsHasMore) {
                                    onLoadMore()
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = SpacingSm),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.sessionsLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    } else {
                                        TextButton(onClick = onLoadMore) {
                                            Text("加载更多")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PullRefreshIndicator(
                refreshing = uiState.sessionsLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (!uiState.sidebarError.isNullOrBlank()) {
            NoticeBanner(
                text = uiState.sidebarError,
                tone = BannerTone.Error,
                modifier = Modifier.padding(vertical = SpacingSm),
            )
        }
        Spacer(modifier = Modifier.height(SpacingXs))
    }
}

@Composable
private fun DrawerEmptyHint(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingMd * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingXs),
    ) {
        Icon(icon, contentDescription = null, tint = TextSubtle, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SessionFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PrimaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { testTagsAsResourceId = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Primary else TextSubtle,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Primary else TextSubtle,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SessionRow(
    session: ChatSession,
    active: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleStarred: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) PrimaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = SpacingSm, end = 2.dp, top = SpacingXs, bottom = SpacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Primary,
                strokeWidth = 2.dp,
            )
        } else if (session.starred) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color(0xFFD4A017),
                modifier = Modifier.size(18.dp),
            )
        }
        if (running || session.starred) {
            Spacer(modifier = Modifier.width(SpacingSm))
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title.ifBlank { EMPTY_SESSION_TITLE },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleStarred)
                    .testTag("agent.session.star.${session.id}")
                    .semantics { testTagsAsResourceId = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (session.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (session.starred) {
                        "取消收藏 ${session.title}"
                    } else {
                        "收藏 ${session.title}"
                    },
                    tint = if (session.starred) Color(0xFFD4A017) else TextSubtle,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !running, onClick = onRename),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名 ${session.title}",
                    tint = if (running) TextMuted else TextSubtle,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !running, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除 ${session.title}",
                    tint = if (running) TextMuted else TextSubtle,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgentChatContent(
    uiState: AgentUiState,
    engineStarting: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onSelectModel: (String) -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onAttachCamera: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    onBeginQueuedMessageEdit: (String) -> Unit,
    onCancelQueuedMessageEdit: (String) -> Unit,
    onUpdateQueuedMessage: (String, String) -> Unit,
    onDeleteQueuedMessage: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val conversationScrollPositions = remember { mutableMapOf<String, ConversationScrollPosition>() }
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
    val markdownRenderer = rememberMarkdownRenderer()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val conversationKey = uiState.activeSessionId ?: DRAFT_SCROLL_KEY
    var input by remember { mutableStateOf("") }
    var composerFocused by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var followOutput by remember { mutableStateOf(true) }
    var userHasDraggedList by remember { mutableStateOf(false) }
    var restoredConversationKey by remember { mutableStateOf<String?>(null) }
    var editingQueuedMessage by remember(conversationKey) {
        mutableStateOf<QueuedAgentMessage?>(null)
    }
    var queuedMessageDraft by remember(conversationKey) { mutableStateOf("") }
    var viewedImages by remember(conversationKey) {
        mutableStateOf<ImageViewerSelection?>(null)
    }

    LaunchedEffect(uiState.queuedMessages) {
        val editingId = editingQueuedMessage?.localId ?: return@LaunchedEffect
        if (uiState.queuedMessages.none { it.localId == editingId }) {
            onCancelQueuedMessageEdit(editingId)
            editingQueuedMessage = null
            queuedMessageDraft = ""
        }
    }

    // 切走会话时保存其可见位置；再次打开时它比默认锚点拥有更高优先级。
    DisposableEffect(conversationKey, listState) {
        onDispose {
            if (restoredConversationKey == conversationKey && listState.layoutInfo.totalItemsCount > 0) {
                conversationScrollPositions[conversationKey] = ConversationScrollPosition(
                    index = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset,
                    followOutput = !listState.canScrollForward,
                )
            }
        }
    }

    // 首次打开已完成会话时从最后一个用户问题开始；运行中的会话仍展示最新输出。
    // 若该会话已有滚动快照，则精确恢复快照，不应用默认锚点。
    LaunchedEffect(
        conversationKey,
        uiState.conversationLoading,
        uiState.events,
        uiState.running,
    ) {
        if (restoredConversationKey != conversationKey) {
            followOutput = false
            userHasDraggedList = false
        }
        if (
            restoredConversationKey == conversationKey ||
            uiState.conversationLoading ||
            uiState.events.isEmpty()
        ) {
            return@LaunchedEffect
        }

        val savedPosition = conversationScrollPositions[conversationKey]
        if (savedPosition != null) {
            val lastItemIndex = uiState.events.size + if (uiState.running && uiState.events.none { it.partial }) 1 else 0
            followOutput = savedPosition.followOutput
            listState.requestScrollToItem(
                index = savedPosition.index.coerceIn(0, lastItemIndex),
                scrollOffset = savedPosition.offset,
            )
        } else if (uiState.running) {
            followOutput = true
            val anchorIndex = uiState.events.size + if (uiState.events.none { it.partial }) 1 else 0
            listState.requestScrollToItem(anchorIndex, scrollOffset = Int.MAX_VALUE)
        } else {
            val lastUserIndex = lastUserEventIndex(uiState.events)
            if (lastUserIndex != null) {
                followOutput = false
                listState.requestScrollToItem(lastUserIndex)
            } else {
                followOutput = true
                listState.requestScrollToItem(uiState.events.size, scrollOffset = Int.MAX_VALUE)
            }
        }
        restoredConversationKey = conversationKey
    }

    // 手指开始拖动时立即取消自动跟随,避免滚动动画与手势争抢控制权。
    LaunchedEffect(isListDragged) {
        if (isListDragged) {
            userHasDraggedList = true
            followOutput = false
        }
    }

    // 用户主动滚回底部(包括松手后的 fling)后,恢复后续内容的自动跟随。
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(isListDragged, listState.isScrollInProgress, listState.canScrollForward)
        }.collect { (dragged, scrolling, canScrollForward) ->
            if (userHasDraggedList && !dragged && !scrolling && !canScrollForward) {
                followOutput = true
            }
        }
    }

    // 把滚动请求并入下一轮列表重测量,避免先按旧高度滚动、再因新高度二次修正。
    // 键覆盖末条事件内容,流式文本增长时也能持续跟随;目标是列表末尾的锚点项。
    val showTyping = uiState.running && uiState.events.none { it.partial }
    LaunchedEffect(uiState.events.size, uiState.events.lastOrNull(), followOutput, uiState.conversationLoading, showTyping) {
        if (followOutput && uiState.events.isNotEmpty()) {
            val anchorIndex = uiState.events.size + (if (showTyping) 1 else 0)
            listState.requestScrollToItem(anchorIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingXs, vertical = SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "打开会话列表",
                    tint = TextPrimary,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = uiState.activeSession?.title?.ifBlank { EMPTY_SESSION_TITLE }
                        ?: EMPTY_SESSION_TITLE,
                    style = ChatTitleTextStyle,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建对话",
                    tint = TextPrimary,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                uiState.conversationLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(PageHorizontalPadding),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LoadingState("正在载入对话…")
                    }
                }
                uiState.events.isEmpty() -> {
                    AgentEmptyState()
                }
                else -> {
                    val toolExecutions = remember(uiState.events) {
                        buildToolExecutionIndex(uiState.events)
                    }
                    val conversationImageGallery = remember(uiState.events) {
                        val images = mutableListOf<ViewerImage>()
                        val offsets = mutableMapOf<String, Int>()
                        uiState.events.forEach { event ->
                            offsets[event.stableId] = images.size
                            if (event.isUser) {
                                images += event.parts.filter { it.isImage }.mapNotNull { part ->
                                    part.previewUri()?.let { url ->
                                        ViewerImage(url, part.name ?: "上传的图片")
                                    }
                                }
                            }
                        }
                        ConversationImageGallery(images, offsets)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = PageHorizontalPadding,
                            vertical = SpacingMd,
                        ),
                        verticalArrangement = Arrangement.spacedBy(SpacingLg),
                    ) {
                        itemsIndexed(
                            items = uiState.events,
                            key = { _, event -> event.stableId },
                        ) { eventIndex, event ->
                            EventMessage(
                                event = event,
                                eventIndex = eventIndex,
                                toolExecutions = toolExecutions,
                                markdownRenderer = markdownRenderer,
                                onImageOpen = { eventImages, initialIndex ->
                                    val offset = conversationImageGallery.offsets[event.stableId]
                                    viewedImages = if (offset == null) {
                                        ImageViewerSelection(eventImages, initialIndex)
                                    } else {
                                        ImageViewerSelection(
                                            conversationImageGallery.images,
                                            offset + initialIndex,
                                        )
                                    }
                                },
                            )
                        }
                        if (showTyping) {
                            item(key = "agent-typing") {
                                TypingIndicator()
                            }
                        }
                        item(key = BOTTOM_ANCHOR_KEY) {
                            Spacer(modifier = Modifier.height(1.dp))
                        }
                    }
                }
            }

        }

        if (engineStarting) {
            NoticeBanner(
                text = "本地引擎启动中，当前请求正在等待引擎就绪。",
                tone = BannerTone.Info,
                modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            )
        }

        if (!uiState.streamError.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoticeBanner(
                    text = uiState.streamError,
                    tone = if (uiState.streamError == "已停止本次回答。") BannerTone.Info else BannerTone.Error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissError) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭提示",
                        tint = TextSubtle,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        val modelEditable = uiState.modelEditable

        if (!uiState.modelSettingsError.isNullOrBlank() && modelEditable) {
            Text(
                text = uiState.modelSettingsError,
                style = MaterialTheme.typography.labelSmall,
                color = Danger,
                modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            )
        }

        if (uiState.pendingImages.isNotEmpty()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = PageHorizontalPadding, vertical = SpacingXs),
                    horizontalArrangement = Arrangement.spacedBy(SpacingSm),
                ) {
                    uiState.pendingImages.forEachIndexed { imageIndex, image ->
                        PendingImagePreview(
                            image = image,
                            onOpen = {
                                viewedImages = ImageViewerSelection(
                                    images = uiState.pendingImages.map { pendingImage ->
                                        ViewerImage(pendingImage.url, pendingImage.name)
                                    },
                                    initialIndex = imageIndex,
                                )
                            },
                            onRemove = { onRemoveImage(image.localId) },
                        )
                    }
                }
            }
        }
		if (uiState.pendingFiles.isNotEmpty()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState())
					.padding(horizontal = PageHorizontalPadding, vertical = SpacingXs),
				horizontalArrangement = Arrangement.spacedBy(SpacingSm),
			) {
				uiState.pendingFiles.forEach { file ->
					PendingFilePreview(file = file, onRemove = { onRemoveFile(file.localId) })
				}
			}
		}
		if (uiState.imageUploadsInProgress > 0) {
			Row(
				modifier = Modifier.padding(horizontal = PageHorizontalPadding, vertical = SpacingXs),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(SpacingSm),
			) {
				CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
				Text("附件上传中，完成后即可发送", style = MaterialTheme.typography.labelSmall, color = TextSubtle)
			}
		}
		uiState.imageUploadError?.let { error ->
			Text(error, style = MaterialTheme.typography.labelSmall, color = Danger, modifier = Modifier.padding(horizontal = PageHorizontalPadding))
		}
        if (uiState.queuedMessages.isNotEmpty()) {
            QueuedMessagesPanel(
                queuedMessages = uiState.queuedMessages,
                onEdit = { queuedMessage ->
                    onBeginQueuedMessageEdit(queuedMessage.localId)
                    editingQueuedMessage = queuedMessage
                    queuedMessageDraft = queuedMessage.text
                },
                onDelete = onDeleteQueuedMessage,
            )
        }
        val canSend = (input.isNotBlank() || uiState.pendingImages.isNotEmpty() || uiState.pendingFiles.isNotEmpty()) &&
			!uiState.conversationLoading && uiState.imageUploadsInProgress == 0
        val composerShape = RoundedCornerShape(24.dp)
        val attachEnabled = uiState.pendingImages.size + uiState.pendingFiles.size +
            uiState.imageUploadsInProgress < 4 && !uiState.conversationLoading
        val composerBorderColor = if (composerFocused) Color(0xFFA99CEC) else Color(0xFFDEDFE7)
        val composerShadowColor = if (composerFocused) Color(0x2E403489) else Color(0x1F222739)
        val sendBrush = if (canSend) {
            Brush.linearGradient(listOf(Color(0xFF7463E4), Color(0xFF5B49CE)))
        } else {
            SolidColor(Color(0xFFD9DBE2))
        }
        val supportsFiles = agentModelSupportsFiles(uiState.selectedModel)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = SpacingSm),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (composerFocused) 9.dp else 8.dp,
                    shape = composerShape,
                    clip = false,
                    ambientColor = composerShadowColor,
                    spotColor = composerShadowColor,
                )
                .clip(composerShape)
                .background(Color.White.copy(alpha = 0.98f))
                .border(
                    width = 1.dp,
                    color = composerBorderColor,
                    shape = composerShape,
                )
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (agentModelSupportsImages(uiState.selectedModel)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .testTag("agent.attach")
                        .clickable(enabled = attachEnabled) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            attachMenuExpanded = true
                        }
                        .alpha(if (attachEnabled) 1f else 0.38f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileUpload,
                        contentDescription = "添加附件",
                        tint = Color(0xFF6556CF),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp, max = 184.dp)
                    .onFocusChanged { composerFocused = it.isFocused },
                maxLines = 7,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                enabled = !uiState.conversationLoading,
                textStyle = ChatBodyTextStyle.copy(
                    color = Color(0xFF303748),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(Color(0xFF6556CF)),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) {
                            Text(
                                text = when {
                                    agentModelSupportsFiles(uiState.selectedModel) -> "输入消息，或添加附件"
                                    agentModelSupportsImages(uiState.selectedModel) -> "输入消息，或添加图片"
                                    else -> "输入消息"
                                },
                                style = ChatBodyTextStyle.copy(fontSize = 13.sp, lineHeight = 20.sp),
                                color = Color(0xFFAEB3C0),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            AgentModelSelector(
                selectedModel = uiState.selectedModel,
                models = uiState.availableModels,
                initialized = uiState.modelSettingsInitialized,
                enabled = uiState.modelSelectorEnabled,
                showDropdown = uiState.showModelDropdown,
                locked = !modelEditable,
                onSelectModel = onSelectModel,
            )
            if (uiState.running) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                            ambientColor = Color(0x2E242937),
                            spotColor = Color(0x2E242937),
                        )
                        .clip(CircleShape)
                        .background(Color(0xFF353B4B))
                        .semantics { contentDescription = "停止生成" }
                        .clickable(onClick = onStop),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .border(2.dp, Color.White, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .shadow(
                        elevation = if (canSend) 4.dp else 0.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x405644C5),
                        spotColor = Color(0x405644C5),
                    )
                    .clip(CircleShape)
                    .background(brush = sendBrush, shape = CircleShape)
                    .testTag("agent.send")
                    .clickable(enabled = canSend) {
                        followOutput = true
                        onSend(input)
                        input = ""
                        // 发送后收起键盘,避免遮挡刚到达的回复。
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "发送",
                    tint = Color.White.copy(alpha = if (canSend) 1f else 0.88f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
            if (attachMenuExpanded && agentModelSupportsImages(uiState.selectedModel)) {
                Popup(
                    alignment = Alignment.BottomStart,
                    onDismissRequest = { attachMenuExpanded = false },
                    properties = PopupProperties(
                        focusable = true,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                        clippingEnabled = false,
                    ),
                ) {
                    AgentAttachMenu(
                        showFiles = supportsFiles,
                        onCamera = {
                            attachMenuExpanded = false
                            onAttachCamera()
                        },
                        onPhotos = {
                            attachMenuExpanded = false
                            onAttachImage()
                        },
                        onFiles = {
                            attachMenuExpanded = false
                            onAttachFile()
                        },
                    )
                }
            }
        }
    }

    editingQueuedMessage?.let { queuedMessage ->
        val canSave = queuedMessageDraft.isNotBlank() ||
            queuedMessage.images.isNotEmpty() || queuedMessage.files.isNotEmpty()
        AlertDialog(
            onDismissRequest = {
                onCancelQueuedMessageEdit(queuedMessage.localId)
                editingQueuedMessage = null
                queuedMessageDraft = ""
            },
            title = { Text("修改排队消息") },
            text = {
                OutlinedTextField(
                    value = queuedMessageDraft,
                    onValueChange = { queuedMessageDraft = it },
                    label = { Text("消息内容") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        onUpdateQueuedMessage(queuedMessage.localId, queuedMessageDraft)
                        editingQueuedMessage = null
                        queuedMessageDraft = ""
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onCancelQueuedMessageEdit(queuedMessage.localId)
                        editingQueuedMessage = null
                        queuedMessageDraft = ""
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    viewedImages?.let { selection ->
        FullscreenImageViewer(
            images = selection.images,
            initialIndex = selection.initialIndex,
            onDismiss = { viewedImages = null },
        )
    }
}

@Composable
private fun QueuedMessagesPanel(
    queuedMessages: List<QueuedAgentMessage>,
    onEdit: (QueuedAgentMessage) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = SpacingXs)
            .clip(RoundedCornerShape(10.dp))
            .background(PrimaryContainer.copy(alpha = 0.42f))
            .border(1.dp, Primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = SpacingSm, vertical = SpacingXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "已排队 ${queuedMessages.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
            )
            Text(
                text = "当前回答完成后依次发送",
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 176.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            queuedMessages.forEachIndexed { index, queuedMessage ->
                val attachmentCount = queuedMessage.images.size + queuedMessage.files.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SpacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingXs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = queuedMessage.text.ifBlank { "仅发送附件" },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (attachmentCount > 0) {
                            Text(
                                text = "$attachmentCount 个附件",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtle,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onEdit(queuedMessage) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "修改第 ${index + 1} 条排队消息",
                            tint = Primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { onDelete(queuedMessage.localId) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除第 ${index + 1} 条排队消息",
                            tint = Danger,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentAttachMenu(
    showFiles: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
) {
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .padding(bottom = 4.dp)
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x3D000000),
                spotColor = Color(0x3D000000),
            )
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        AgentAttachMenuItem(
            icon = Icons.Outlined.PhotoCamera,
            label = "相机",
            testTag = "agent.attach-camera",
            onClick = onCamera,
        )
        AgentAttachMenuItem(
            icon = Icons.Outlined.Image,
            label = "照片",
            testTag = "agent.attach-image",
            onClick = onPhotos,
        )
        if (showFiles) {
            AgentAttachMenuItem(
                icon = Icons.Outlined.AttachFile,
                label = "文件",
                testTag = "agent.attach-file",
                onClick = onFiles,
            )
        }
    }
}

@Composable
private fun AgentAttachMenuItem(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFFF3F3F3)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF222222),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            color = Color(0xFF222222),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentModelSelector(
    selectedModel: String,
    models: List<String>,
    initialized: Boolean,
    enabled: Boolean,
    showDropdown: Boolean,
    locked: Boolean,
    onSelectModel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    when {
                        enabled -> expanded = true
                        locked -> Toast.makeText(
                            context,
                            "已有对话不能切换模型",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (initialized) {
                Icon(
                    painter = painterResource(agentModelIcon(selectedModel)),
                    contentDescription = agentModelLabel(selectedModel),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                // 保留最终 Logo 的尺寸，首次设置尚未返回时不绘制错误的默认模型。
                Spacer(modifier = Modifier.size(20.dp))
            }
            if (showDropdown) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择模型",
                    tint = Color(0xFF70798C),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            models.forEach { model ->
                val selected = model == selectedModel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelectModel(model)
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(agentModelIcon(model)),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = agentModelLabel(model),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) Primary else TextPrimary,
                        )
                        val subtitle = agentModelSubtitle(model)
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtle,
                            )
                        }
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private data class ConversationScrollPosition(
    val index: Int,
    val offset: Int,
    val followOutput: Boolean,
)

private data class ViewerImage(
    val url: String,
    val contentDescription: String,
)

private data class ImageViewerSelection(
    val images: List<ViewerImage>,
    val initialIndex: Int,
)

private data class ConversationImageGallery(
    val images: List<ViewerImage>,
    val offsets: Map<String, Int>,
)

private const val DRAFT_SCROLL_KEY = "draft"

internal fun lastUserEventIndex(events: List<AgentEvent>): Int? =
    events.indexOfLast { it.isUser }.takeIf { it >= 0 }

internal data class ToolPartLocation(
    val eventIndex: Int,
    val partIndex: Int,
)

internal data class ToolExecution(
    val call: AgentPart?,
    val result: AgentPart?,
    val callLocation: ToolPartLocation?,
    val resultLocation: ToolPartLocation?,
)

internal data class ToolExecutionIndex(
    val byCallLocation: Map<ToolPartLocation, ToolExecution>,
    val unmatchedResults: Map<ToolPartLocation, ToolExecution>,
    val matchedResultLocations: Set<ToolPartLocation>,
)

private data class MutableToolExecution(
    val call: AgentPart,
    val callLocation: ToolPartLocation,
    var result: AgentPart? = null,
    var resultLocation: ToolPartLocation? = null,
)

/** 使用 invocationId + callId 精确关联调用与返回，避免并行工具或跨轮调用串线。 */
internal fun buildToolExecutionIndex(events: List<AgentEvent>): ToolExecutionIndex {
    val calls = mutableMapOf<ToolPartLocation, MutableToolExecution>()
    val pending = mutableMapOf<Pair<String, String>, MutableList<MutableToolExecution>>()
    val unmatchedResults = mutableMapOf<ToolPartLocation, ToolExecution>()
    val matchedResults = mutableSetOf<ToolPartLocation>()

    events.forEachIndexed { eventIndex, event ->
        event.parts.forEachIndexed { partIndex, part ->
            val location = ToolPartLocation(eventIndex, partIndex)
            val invocationId = event.invocationId?.takeIf { it.isNotBlank() }
            val callId = part.callId?.takeIf { it.isNotBlank() }
            val invocationKey = if (invocationId != null && callId != null) {
                invocationId to callId
            } else {
                null
            }

            when {
                part.isToolCall -> {
                    val execution = MutableToolExecution(part, location)
                    calls[location] = execution
                    if (invocationKey != null) {
                        pending.getOrPut(invocationKey) { mutableListOf() } += execution
                    }
                }
                part.isToolResult -> {
                    val matchingCall = invocationKey
                        ?.let(pending::get)
                        ?.asReversed()
                        ?.firstOrNull { it.result == null }
                    if (matchingCall != null) {
                        matchingCall.result = part
                        matchingCall.resultLocation = location
                        matchedResults += location
                    } else {
                        unmatchedResults[location] = ToolExecution(
                            call = null,
                            result = part,
                            callLocation = null,
                            resultLocation = location,
                        )
                    }
                }
            }
        }
    }

    return ToolExecutionIndex(
        byCallLocation = calls.mapValues { (_, execution) ->
            ToolExecution(
                call = execution.call,
                result = execution.result,
                callLocation = execution.callLocation,
                resultLocation = execution.resultLocation,
            )
        },
        unmatchedResults = unmatchedResults,
        matchedResultLocations = matchedResults,
    )
}

@Composable
private fun AgentEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PageHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(SpacingSm))
        Text(
            text = "今天想一起探索什么？",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(SpacingXs))
        Text(
            text = "我可以思考复杂问题、搜索实时信息、阅读网页，并将结果整理成清晰的答案。",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun PendingImagePreview(
    image: PendingAgentImage,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onOpen)
            .testTag("agent.pending-image"),
    ) {
		StableUrlImage(
			url = image.url,
            contentDescription = "${image.name}，点击放大查看",
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape),
        ) {
            Icon(Icons.Default.Close, contentDescription = "移除 ${image.name}", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }

}

@Composable
private fun PendingFilePreview(
    file: PendingAgentFile,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp)
            .testTag("agent.pending-file"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.Close, contentDescription = "移除 ${file.name}", tint = TextSubtle, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun StableUrlImage(
	url: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	onIntrinsicAspectRatio: ((Float) -> Unit)? = null,
) {
	val context = LocalContext.current
	val request = remember(url) {
		val model = decodeAgentImageDataUri(url) ?: url
		ImageRequest.Builder(context)
			.data(model)
			.crossfade(true)
			.build()
	}
	SubcomposeAsyncImage(
		model = request,
		contentDescription = contentDescription,
		contentScale = contentScale,
		modifier = modifier,
		onSuccess = { state ->
			val drawable = state.result.drawable
			if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
				onIntrinsicAspectRatio?.invoke(drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight)
			}
		},
		loading = { Box(Modifier.fillMaxSize().background(TextMuted.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } },
		error = { Box(Modifier.fillMaxSize().background(TextMuted.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Text("图片加载失败", style = MaterialTheme.typography.labelSmall, color = TextSubtle) } },
	)
}

@Composable
private fun EventMessage(
    event: AgentEvent,
    eventIndex: Int,
    toolExecutions: ToolExecutionIndex,
    markdownRenderer: io.noties.markwon.Markwon,
    onImageOpen: (List<ViewerImage>, Int) -> Unit,
) {
    if (event.isUser) {
        val userImages = event.parts.filter { it.isImage }.mapNotNull { part ->
            part.previewUri()?.let { ViewerImage(it, part.name ?: "上传的图片") }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(SpacingSm),
        ) {
            userImages.forEachIndexed { imageIndex, image ->
                UserMessageImage(
					url = image.url,
                    contentDescription = image.contentDescription,
                    onClick = { onImageOpen(userImages, imageIndex) },
                    modifier = Modifier.testTag("agent.user-image.$eventIndex.$imageIndex"),
                )
            }
            event.parts.filter { it.isFile }.forEachIndexed { fileIndex, part ->
                part.previewUri()?.let { stableUrl ->
                    UserMessageFile(
                        url = stableUrl,
                        name = part.name ?: "文件",
                        modifier = Modifier.testTag("agent.user-file.$eventIndex.$fileIndex"),
                    )
                }
            }
            if (event.text.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 312.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.065f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = SpacingLg, vertical = 10.dp)
                        .testTag("agent.user-text-bubble.$eventIndex"),
                ) {
                    SelectionContainer {
                        Text(text = event.text, style = ChatUserTextStyle, color = TextPrimary)
                    }
                }
            }
        }
        return
    }

    val hasVisiblePart = event.parts.indices.any { partIndex ->
        val part = event.parts[partIndex]
        !part.isToolResult || ToolPartLocation(eventIndex, partIndex) !in toolExecutions.matchedResultLocations
    }
    if (
        !hasVisiblePart &&
        event.error == null &&
        event.usage == null &&
        !(event.partial && event.parts.isEmpty())
    ) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingMd),
    ) {
        if (event.parts.isEmpty() && event.partial) {
            TypingIndicator()
        }
        var partIndex = 0
        while (partIndex < event.parts.size) {
            val part = event.parts[partIndex]
            when {
                part.isThinking -> ThinkingPart(part, streaming = event.partial)
                part.isHostedToolStatus -> HostedToolStatusPart(part)
                part.isToolCall || part.isToolResult -> {
                    val executions = mutableListOf<ToolExecution>()
                    while (partIndex < event.parts.size) {
                        val toolPart = event.parts[partIndex]
                        if (!toolPart.isToolCall && !toolPart.isToolResult) break
                        val location = ToolPartLocation(eventIndex, partIndex)
                        when {
                            toolPart.isToolCall -> toolExecutions.byCallLocation[location]?.let(executions::add)
                            location !in toolExecutions.matchedResultLocations -> {
                                toolExecutions.unmatchedResults[location]?.let(executions::add)
                            }
                        }
                        partIndex += 1
                    }
                    if (executions.isNotEmpty()) {
                        ToolExecutionGroup(executions = executions)
                    }
                    continue
                }
                !part.text.isNullOrEmpty() -> MarkdownText(
                    markdown = part.text,
                    renderer = markdownRenderer,
                    streaming = event.partial,
                )
            }
            partIndex += 1
        }
        if (event.error != null) {
            NoticeBanner(
                text = formatStructured(event.error),
                tone = BannerTone.Error,
            )
        }
        if (!event.partial) {
            AssistantMessageTools(event)
        }
    }
}

@Composable
private fun UserMessageFile(
    url: String,
    name: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = modifier
            .widthIn(max = 312.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PrimaryContainer)
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = Primary, modifier = Modifier.size(17.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UserMessageImage(
	url: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
			.width(260.dp)
			.height(240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            )
			.clickable(onClick = onClick),
    ) {
		StableUrlImage(
			url = url,
            contentDescription = "$contentDescription，点击放大查看",
            modifier = Modifier.fillMaxSize(),
        )
    }

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FullscreenImageViewer(
    images: List<ViewerImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    var imageIndex by remember(images, initialIndex) {
        mutableStateOf(initialIndex.coerceIn(0, images.lastIndex))
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var swipeDistance by remember(imageIndex) { mutableFloatStateOf(0f) }
    val image = images[imageIndex]
    var imageAspectRatio by remember(image.url) { mutableFloatStateOf(1f) }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(imageIndex) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics { testTagsAsResourceId = true }
                .testTag("agent.image-viewer"),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(imageIndex, images.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            if (
                                scale <= 1f &&
                                nextScale <= 1f &&
                                abs(zoom - 1f) < 0.01f &&
                                abs(pan.x) > abs(pan.y)
                            ) {
                                swipeDistance += pan.x
                                when {
                                    swipeDistance <= -swipeThreshold && imageIndex < images.lastIndex -> {
                                        imageIndex += 1
                                        swipeDistance = 0f
                                    }
                                    swipeDistance >= swipeThreshold && imageIndex > 0 -> {
                                        imageIndex -= 1
                                        swipeDistance = 0f
                                    }
                                }
                            } else {
                                swipeDistance = 0f
                                scale = nextScale
                                offset = if (nextScale > 1f) offset + pan else Offset.Zero
                            }
                        }
                    }
                    .testTag("agent.image-viewer.backdrop"),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val availableAspectRatio = maxWidth.value / maxHeight.value
                    val fittedImageModifier = if (imageAspectRatio >= availableAspectRatio) {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(imageAspectRatio)
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(imageAspectRatio)
                    }
                    StableUrlImage(
                        url = image.url,
                        contentDescription = image.contentDescription,
                        contentScale = ContentScale.Fit,
                        onIntrinsicAspectRatio = { imageAspectRatio = it },
                        modifier = fittedImageModifier
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            )
                            .testTag("agent.image-viewer.image"),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .align(Alignment.TopCenter),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .testTag("agent.image-viewer.close"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭大图",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "${imageIndex + 1}/${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("agent.image-viewer.index"),
                )
            }
        }
    }
}

@Composable
private fun HostedToolStatusPart(part: AgentPart) {
    val succeeded = agentHostedToolStatusSucceeded(part.status)
    val failed = agentHostedToolStatusFailed(part.status)
    val terminal = agentHostedToolStatusTerminal(part.status)
    val toolIcon = when (agentHostedToolKind(part.name)) {
        HostedToolKind.WebSearch, HostedToolKind.XSearch, HostedToolKind.ImageSearch -> Icons.Default.Search
        HostedToolKind.ImageUnderstanding, HostedToolKind.XVideoUnderstanding -> Icons.Default.Image
        HostedToolKind.FileSearch -> Icons.Default.FolderOpen
        HostedToolKind.CodeInterpreter -> Icons.Default.Code
        HostedToolKind.ImageGeneration -> Icons.Default.Image
        HostedToolKind.ComputerUse -> Icons.Default.Computer
        HostedToolKind.Mcp -> Icons.Default.Hub
        HostedToolKind.Shell -> Icons.Default.Terminal
        HostedToolKind.ApplyPatch -> Icons.Default.Edit
        HostedToolKind.Other -> Icons.Default.Build
    }
    val accent = when {
        succeeded -> Color(0xFF4F8060)
        failed -> Danger
        else -> Primary
    }
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = when {
                succeeded -> Icons.Default.Check
                failed -> Icons.Default.Close
                else -> toolIcon
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = agentHostedToolStatusLabel(part.name, part.status),
            style = ChatMetaTextStyle,
            color = TextSubtle,
        )
        if (!terminal) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = accent,
                strokeWidth = 1.5.dp,
            )
        }
    }
}

@Composable
private fun ThinkingPart(part: AgentPart, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (streaming) "思考中" else "已完成思考",
                style = ChatMetaTextStyle,
                color = TextSubtle,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                LiveDot()
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Text(
                text = part.text?.takeIf { it.isNotBlank() }
                    ?: if (streaming) "正在分析…" else "已完成思考，模型未返回可展示的文本。",
                style = ChatMetaTextStyle,
                color = TextMuted,
                modifier = Modifier.padding(top = SpacingSm, end = SpacingSm, bottom = SpacingSm),
            )
        }
    }
}

/** 工具参数/结果展开后最多显示的行数，超出滚动查看。 */
private const val TOOL_DETAIL_MAX_LINES = 8

@Composable
private fun ToolExecutionGroup(executions: List<ToolExecution>) {
    val executing = executions.any { it.result == null }
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (executing) Icons.Default.Build else Icons.Default.Check,
                contentDescription = null,
                tint = if (executing) Primary else Color(0xFF2F8A69),
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(SpacingXs))
            Text(
                text = if (executing) "工具执行中" else "工具调用完成",
                style = ChatMetaTextStyle,
                color = TextMuted,
                modifier = Modifier.weight(1f),
            )
            if (executing) {
                LiveDot()
            }
            Spacer(modifier = Modifier.width(SpacingXs))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = SpacingMd, end = SpacingSm, bottom = SpacingSm),
                verticalArrangement = Arrangement.spacedBy(SpacingXs),
            ) {
                executions.forEach { execution ->
                    ToolExecutionItem(execution = execution, executing = executing)
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionItem(execution: ToolExecution, executing: Boolean) {
    var expanded by remember { mutableStateOf(true) }
    val name = execution.call?.name ?: execution.result?.name ?: "工具"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                tint = TextSubtle,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(SpacingXs))
            Text(
                text = "调用工具",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.width(SpacingXs))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起工具详情" else "展开工具详情",
                tint = TextSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = SpacingMd),
                verticalArrangement = Arrangement.spacedBy(SpacingXs),
            ) {
                execution.call?.let { call ->
                    ToolDetailSection(label = "参数", content = formatStructured(call.args).ifBlank { "无参数" })
                }
                val resultText = execution.result?.let { result ->
                    formatStructured(result.result).ifBlank { "无可展示结果" }
                } ?: if (executing) {
                    "等待工具返回…"
                } else {
                    "未返回可展示结果"
                }
                ToolDetailSection(label = "结果", content = resultText)
            }
        }
    }
}

@Composable
private fun ToolDetailSection(label: String, content: String) {
    val detailStyle = MaterialTheme.typography.bodySmall
    val maxDetailHeight = with(LocalDensity.current) {
        (detailStyle.lineHeight * TOOL_DETAIL_MAX_LINES).toDp()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.7f))
                .heightIn(max = maxDetailHeight)
                .verticalScroll(rememberScrollState())
                .padding(SpacingSm),
        ) {
            Text(
                text = content,
                style = detailStyle,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun MessageCopyButton(copyableText: String, contentDescription: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var copyFeedbackTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(copyFeedbackTrigger) {
        if (copyFeedbackTrigger > 0) {
            delay(2_000)
            copied = false
        }
    }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(copyableText))
            copied = true
            copyFeedbackTrigger += 1
        },
        contentPadding = PaddingValues(horizontal = SpacingSm, vertical = 0.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = TextSubtle,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = if (copied) "已复制" else "复制",
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle,
        )
    }
}

@Composable
private fun AssistantMessageTools(event: AgentEvent) {
    val copyableText = event.text.trim()
    val usageLabel = formatUsageTokens(event.usage)
    if (copyableText.isEmpty() && usageLabel.isNullOrBlank()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingSm),
    ) {
        if (copyableText.isNotEmpty()) {
            MessageCopyButton(copyableText, contentDescription = "复制回复内容")
        }
        if (!usageLabel.isNullOrBlank()) {
            Text(
                text = usageLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(TextSubtle, CircleShape),
            )
        }
    }
}

@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .alpha(alpha)
            .background(Primary, CircleShape),
    )
}

@Composable
private fun RenameSessionDialog(
    session: ChatSession,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(session.id) {
        mutableStateOf(session.title.ifBlank { EMPTY_SESSION_TITLE })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("会话名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private const val BOTTOM_ANCHOR_KEY = "agent-chat-bottom-anchor"

private val prettyJson = Json { prettyPrint = true }

/** 与 Web 端 formatStructured 对齐:结构化参数/结果 pretty-print,纯文本原样返回。 */
private fun formatStructured(value: JsonElement?): String {
    if (value == null || value is JsonPrimitive && value.isString && value.content.isEmpty()) {
        return ""
    }
    if (value is JsonPrimitive && value.isString) {
        return value.content
    }
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), value) }
        .getOrElse { value.toString() }
}

private fun formatCount(value: Int): String =
    if (value >= 1000) {
        val divided = value / 1000.0
        val text = if (divided == divided.toLong().toDouble()) {
            "${divided.toLong()}"
        } else {
            String.format(java.util.Locale.US, "%.1f", divided)
        }
        "${text}k"
    } else {
        "$value"
    }

/** Token 明细:输入 · 输出 · 思考 · 缓存命中率。 */
private fun formatUsageTokens(usage: AgentUsage?): String? {
    if (usage == null) return null
    if (
        usage.inputTokens <= 0 &&
        usage.outputTokens <= 0 &&
        usage.thinkingTokens <= 0 &&
        usage.cachedTokens <= 0
    ) return null
    val cacheHitRatio = if (usage.inputTokens > 0) {
        "${(usage.cachedTokens * 100) / usage.inputTokens}%"
    } else {
        "0%"
    }
    return listOf(
        "↑ ${formatCount(usage.inputTokens)}",
        "↓ ${formatCount(usage.outputTokens)}",
        "思考 ${formatCount(usage.thinkingTokens)}",
        "缓存 $cacheHitRatio",
    ).joinToString(" · ")
}


@Composable
private fun EngineSetupCard(
    hasApiKey: Boolean,
    error: String?,
    onOpenSettings: () -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingMd)) {
            Text("配置 DeepSeek API Key", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasApiKey) {
                    error ?: "本地引擎正在启动，请稍候。"
                } else {
                    "下载即可使用。在设置中填入你自己的 DeepSeek API Key 后即可开始对话。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Button(onClick = onOpenSettings) {
                Text("打开设置")
            }
        }
    }
}
