package com.tailnet.agenthub.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailnet.agenthub.AgentHubApp
import com.tailnet.agenthub.AppConfig
import com.tailnet.agenthub.ConfigStore
import com.tailnet.agenthub.DeviceIdentityStore
import com.tailnet.agenthub.OpenClawClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 单条对话消息 */
data class ChatMessage(
    val role: String,
    val content: String,
    /** 助手回复耗时（毫秒），仅助手消息有 */
    val durationMs: Long? = null,
    /** agent 执行步骤（工具/命令/补丁），仅助手消息有 */
    val activity: List<String> = emptyList(),
    /** 本轮用量元数据（模型/token/缓存），仅助手 final 消息有 */
    val meta: OpenClawClient.ChatMeta? = null,
    /** 消息附带的图片（base64 data URI，可直接渲染） */
    val images: List<String> = emptyList(),
    /** 消息附带的文件（文本类嵌入内容，二进制类嵌入 base64 data URI） */
    val files: List<AttachedFile> = emptyList(),
)

/** 毫秒耗时格式化为「x分xx秒」/「xx秒」 */
fun formatDuration(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min <= 0) "${sec}秒" else "${min}分${sec}秒"
}

/**
 * 把内容 Uri 压缩转成 JPEG base64 data URI（直接塞进 <img src> 可渲染）。
 * 最长边缩到 1024px，避免 base64 过大撑爆消息存储。
 */
private suspend fun uriToBase64Image(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val bmp = BitmapFactory.decodeStream(ins) ?: return@withContext null
            // 缩放：最长边 <= 1024
            val maxSide = 1024
            val scale = if (bmp.width > bmp.height) {
                (maxSide.toFloat() / bmp.width).coerceAtMost(1f)
            } else {
                (maxSide.toFloat() / bmp.height).coerceAtMost(1f)
            }
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt(),
                    (bmp.height * scale).toInt(),
                    true
                )
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            "data:image/jpeg;base64,$b64"
        }
    } catch (_: Throwable) {
        null
    }
}

/** data URI 解析成 Bitmap（用于 Image composable 渲染） */
private fun base64ImageToBitmap(dataUri: String): android.graphics.Bitmap? {
    val base64 = dataUri.substringAfter("base64,", "")
    if (base64.isEmpty()) return null
    return try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Throwable) {
        null
    }
}

/** 用户附加的文件：文本类直接嵌入内容，二进制类嵌入 base64 data URI */
data class AttachedFile(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    /** 文本类文件直接嵌入内容；二进制类为 null */
    val textContent: String? = null,
    /** 二进制类文件嵌入 base64 data URI；文本类为 null */
    val dataUri: String? = null,
    /** 文件过大未嵌入内容时为 true（仅记录元数据告诉 agent） */
    val tooLarge: Boolean = false,
)

/** 常见文本类文件扩展名（命中则按文本处理，直接嵌入内容便于 agent 阅读） */
private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "csv", "xml", "html", "htm",
    "js", "ts", "jsx", "tsx", "kt", "kts", "java", "c", "cpp", "cc", "h", "hpp",
    "py", "rb", "go", "rs", "swift", "css", "scss", "less", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "sh", "bash", "zsh", "log", "sql", "svg",
    "env", "properties", "gradle", "pl", "lua", "vim", "r", "diff", "patch",
    "bat", "ps1", "gitignore", "editorconfig", "dockerfile", "makefile",
)

/** 单个文件最大嵌入大小（超过则只记录元数据，避免消息撑爆） */
private const val MAX_EMBED_FILE_SIZE = 256 * 1024L

/** 字节数格式化为人类可读的文件大小 */
fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        bytes < 1024 -> "${bytes}B"
        mb >= 1.0 -> "%.1fMB".format(mb)
        else -> "%.0fKB".format(kb)
    }
}

/**
 * 把内容 Uri 转成 AttachedFile：
 * - 文本类文件直接嵌入内容
 * - 二进制类文件转 base64 data URI
 * - 超过 MAX_EMBED_FILE_SIZE 只记录元数据（agent 可通过工具读取）
 */
private suspend fun uriToAttachedFile(context: Context, uri: Uri): AttachedFile? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name = "file"
            var size = 0L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val ext = name.substringAfterLast('.', "").lowercase()
            val isText = mime.startsWith("text/") || ext in TEXT_FILE_EXTENSIONS

            if (size > MAX_EMBED_FILE_SIZE) {
                return@withContext AttachedFile(name, mime, size, tooLarge = true)
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            if (isText) {
                val text = String(bytes, Charsets.UTF_8)
                AttachedFile(name, mime, size, textContent = text)
            } else {
                val b64 = android.util.Base64.encodeToString(
                    bytes, android.util.Base64.NO_WRAP
                )
                AttachedFile(
                    name, mime, size,
                    dataUri = "data:$mime;base64,$b64",
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

/**
 * 基于全部对话内容总结标题。
 * 优先级：
 *   1. 如果有用户提问，取首个用户提问的前 15 字（干净、无歧义、完全符合用户预期）
 *   2. 否则合并所有用户/助手文本，取开头有意义的一段，去 Markdown，截断到 ~15 字
 */
private fun summarizeTitleFromConversation(messages: List<ChatMessage>): String {
    val firstUserText = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content
    if (!firstUserText.isNullOrBlank()) {
        // 提取核心内容：去掉标点、多余空白，压缩到 ~10 字
        val clean = firstUserText.trim()
            .replace(Regex("[\\s\\n\\r]+"), " ")
            .replace(Regex("[\uff0c\u3002\uff1f\uff01.,?!;:\uff1a\uff1b\u201c\u201d\u2018\u2019\"'()\uff08\uff09\\[\\]\u3010\u3011{}\u2026~\u2014\\-]+"), "")
            .trim()
        // 截断到 10 字
        val title = if (clean.length > 10) clean.take(10) + "\u2026" else clean
        if (title.isNotBlank()) return title
    }
    // 兜底：拼全部文本再提取
    val all = messages.joinToString(" ") { it.content }
    if (all.isBlank()) return "\u65b0\u5bf9\u8bdd"
    val cleanAll = all.trim()
        .replace(Regex("[\\s\\n\\r]+"), " ")
        .replace(Regex("[\uff0c\u3002\uff1f\uff01.,?!;:\uff1a\uff1b\u201c\u201d\u2018\u2019\"'()\uff08\uff09\\[\\]\u3010\u3011{}\u2026~\u2014\\-]+"), "")
        .trim()
    if (cleanAll.isBlank()) return "\u65b0\u5bf9\u8bdd"
    return if (cleanAll.length > 10) cleanAll.take(10) + "\u2026" else cleanAll
}

/**
 * 一个对话：独立的消息列表 + 服务端会话键。
 * key 是 sessionKey 的 mainKey 部分（agent:<agentId>:<key>），不同 key 即不同会话。
 */
class ConversationState(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    title: String = "新对话",
    initial: List<ChatMessage> = emptyList(),
) {
    var title by mutableStateOf(title)
    /** 标记标题待 LLM 总结覆盖（临时兜底标题不应阻止 LLM 生成） */
    var titlePending by mutableStateOf(false)
    val messages = mutableStateListOf<ChatMessage>().apply { addAll(initial) }
    var busy by mutableStateOf(false)
    var phase by mutableStateOf("")

    /** 当前进行中回复的计时起点与执行步骤（回复完成后写入消息） */
    var replyStartedAt = 0L
    val currentActivity = mutableStateListOf<String>()

    /** 最近一轮回复的用量元数据，底部状态栏展示。
     *  初始化时从最后一条助手消息的 meta 恢复，切换对话也能看到历史用量 */
    var lastMeta by mutableStateOf<OpenClawClient.ChatMeta?>(
        initial.lastOrNull { it.role == "assistant" }?.meta
    )

    /** 当前进行中的聊天 Job，用于「终止回复」取消 */
    var activeChatJob: Job? = null
}

/** 对话列表：本地 JSON 持久化，重启后保留 */
class ConversationStore(context: Context) {
    private val file = File(context.filesDir, "openclaw/conversations.json")

    val conversations = mutableStateListOf<ConversationState>()
    var currentId by mutableStateOf<String?>(null)

    fun current(): ConversationState? = conversations.firstOrNull { it.id == currentId }

    fun newConversation(): ConversationState {
        // mainKey 需匹配服务端 VALID_ID_RE（小写字母数字开头，可含 - _）
        val conv = ConversationState(key = "chat-" + UUID.randomUUID().toString().replace("-", "").take(8))
        conversations.add(0, conv)
        currentId = conv.id
        save()
        return conv
    }

    fun select(id: String) {
        currentId = id
        save()
    }

    fun delete(id: String) {
        conversations.removeAll { it.id == id }
        if (currentId == id) currentId = conversations.firstOrNull()?.id
        save()
    }

    fun load() {
        try {
            if (!file.exists()) return
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("conversations") ?: return
            val list = mutableListOf<ConversationState>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val msgs = mutableListOf<ChatMessage>()
                val mArr = c.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until mArr.length()) {
                    val m = mArr.optJSONObject(j) ?: continue
                    val activity = mutableListOf<String>()
                    val aArr = m.optJSONArray("activity") ?: JSONArray()
                    for (k in 0 until aArr.length()) activity.add(aArr.optString(k))
                    val images = mutableListOf<String>()
                    val iArr = m.optJSONArray("images") ?: JSONArray()
                    for (k in 0 until iArr.length()) images.add(iArr.optString(k))
                    val files = mutableListOf<AttachedFile>()
                    val fArr = m.optJSONArray("files") ?: JSONArray()
                    for (k in 0 until fArr.length()) {
                        val fo = fArr.optJSONObject(k) ?: continue
                        files.add(
                            AttachedFile(
                                name = fo.optString("name"),
                                mime = fo.optString("mime"),
                                sizeBytes = fo.optLong("size"),
                                textContent = fo.optString("text").takeIf { it.isNotEmpty() },
                                dataUri = fo.optString("dataUri").takeIf { it.isNotEmpty() },
                                tooLarge = fo.optBoolean("tooLarge", false),
                            )
                        )
                    }
                    msgs.add(
                        ChatMessage(
                            role = m.optString("role"),
                            content = m.optString("content"),
                            durationMs = if (m.has("durationMs")) m.optLong("durationMs") else null,
                            activity = activity,
                            meta = m.optJSONObject("meta")?.let { metaObj ->
                                OpenClawClient.ChatMeta(
                                    model = metaObj.optString("model"),
                                    inputTokens = metaObj.optInt("inputTokens"),
                                    outputTokens = metaObj.optInt("outputTokens"),
                                    cacheReadTokens = metaObj.optInt("cacheReadTokens"),
                                    cacheCreationTokens = metaObj.optInt("cacheCreationTokens"),
                                )
                            },
                            images = images,
                            files = files,
                        )
                    )
                }
                list.add(
                    ConversationState(
                        id = c.optString("id").ifBlank { UUID.randomUUID().toString() },
                        key = c.optString("key").ifBlank { UUID.randomUUID().toString().replace("-", "").take(8) },
                        title = c.optString("title").ifBlank { "新对话" },
                        initial = msgs,
                    )
                )
            }
            conversations.clear()
            conversations.addAll(list)
            currentId = obj.optString("currentId").ifBlank { null }
                ?.let { id -> conversations.firstOrNull { it.id == id }?.id }
        } catch (_: Throwable) {
            // 损坏则视为无历史
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            conversations.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("key", c.key)
                        put("title", c.title)
                        put("messages", JSONArray().apply {
                            c.messages.forEach { m ->
                                put(
                                    JSONObject().apply {
                                        put("role", m.role)
                                        put("content", m.content)
                                        m.durationMs?.let { put("durationMs", it) }
                                        if (m.activity.isNotEmpty()) {
                                            put("activity", JSONArray(m.activity))
                                        }
                                        if (m.images.isNotEmpty()) {
                                            put("images", JSONArray(m.images))
                                        }
                                        if (m.files.isNotEmpty()) {
                                            put("files", JSONArray().apply {
                                                m.files.forEach { f ->
                                                    put(JSONObject().apply {
                                                        put("name", f.name)
                                                        put("mime", f.mime)
                                                        put("size", f.sizeBytes)
                                                        f.textContent?.let { put("text", it) }
                                                        f.dataUri?.let { put("dataUri", it) }
                                                        if (f.tooLarge) put("tooLarge", true)
                                                    })
                                                }
                                            })
                                        }
                                        m.meta?.let { meta ->
                                            put("meta", JSONObject().apply {
                                                put("model", meta.model)
                                                put("inputTokens", meta.inputTokens)
                                                put("outputTokens", meta.outputTokens)
                                                put("cacheReadTokens", meta.cacheReadTokens)
                                                put("cacheCreationTokens", meta.cacheCreationTokens)
                                            })
                                        }
                                    }
                                )
                            }
                        })
                    }
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject()
                    .put("conversations", arr)
                    .put("currentId", currentId.orEmpty())
                    .toString()
            )
        } catch (_: Throwable) {
            // 持久化失败不阻断会话
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val configStore = remember { ConfigStore(context) }
    var config by remember { mutableStateOf(configStore.load()) }
    var selectedTab by remember { mutableStateOf(0) }

    // 会话存储与协程作用域提升到 App 层：切换页面不中断进行中的对话
    val conversationStore = remember { ConversationStore(context).also { it.load() } }
    val chatScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    val darkTheme = when (config.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                val emoji = item.emoji
                                if (emoji != null) {
                                    Text(emoji, fontSize = 20.sp)
                                } else {
                                    Icon(item.icon!!, contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when (selectedTab) {
                    0 -> OpenClawScreen(config, conversationStore, chatScope)
                    1 -> SkillsScreen(config)
                    2 -> SettingsScreen(config) { updated ->
                        config = updated
                        configStore.save(updated)
                    }
                }
            }
        }
    }
}

private data class TabItem(
    val label: String,
    val icon: ImageVector? = null,
    val emoji: String? = null,
)

private val tabItems = listOf(
    TabItem("OpenClaw", emoji = "🦞"),
    TabItem("技能", icon = Icons.Filled.Build),
    TabItem("设置", icon = Icons.Filled.Settings),
)

// ---------------- OpenClaw 聊天页 ----------------

@Composable
fun OpenClawScreen(config: AppConfig, store: ConversationStore, chatScope: CoroutineScope) {
    val context = LocalContext.current

    // ---- 请求 POST_NOTIFICATIONS 权限（Android 13+）----
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _: Boolean -> /* 不论用户同意与否，都不阻塞使用 */ }
    val notifPermissionAlreadyRequested = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notifPermissionAlreadyRequested.value &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionAlreadyRequested.value = true
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 带设备身份（Ed25519 签名）连接：v2026.4+ 服务端要求签名设备才保留 scopes
    val client = remember { OpenClawClient(context, DeviceIdentityStore(context)) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var input by remember { mutableStateOf("") }
    var showList by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pendingImages = remember { mutableStateListOf<String>() }
    val pendingFiles = remember { mutableStateListOf<AttachedFile>() }

    // ---- 编辑提问对话框状态 ----
    var editingMsgIndex by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }

    // ---- 图片选择器 ----
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            chatScope.launch {
                uriToBase64Image(context, uri)?.let { pendingImages.add(it) }
            }
        }
    }

    // ---- 文件选择器（任意类型文件，文本类直接嵌入内容） ----
    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            chatScope.launch {
                uriToAttachedFile(context, uri)?.let { pendingFiles.add(it) }
            }
        }
    }

    // 首启动确保至少有一个对话
    remember { if (store.conversations.isEmpty()) store.newConversation() }
    val conversation = store.current() ?: return

    // ---- 发送一条消息（含图片/文件）并发起一轮 agent 回复 ----
    fun sendMessage(
        textIn: String,
        imagesIn: List<String> = emptyList(),
        filesIn: List<AttachedFile> = emptyList(),
        preAddIndex: Int? = null,
    ) {
        val text = textIn.trim()
        if (text.isEmpty() && imagesIn.isEmpty() && filesIn.isEmpty()) return
        if (conversation.busy) return
        // 如果是「修改后重发」，需要把该条用户消息之后的全部消息截掉
        if (preAddIndex != null) {
            while (conversation.messages.size > preAddIndex) conversation.messages.removeLast()
        }
        conversation.messages.add(ChatMessage("user", text, images = imagesIn, files = filesIn))
        // 标题在回复完成后由 LLM 总结生成，此处不预设
        conversation.messages.add(ChatMessage("assistant", ""))
        store.save()
        input = ""
        pendingImages.clear()
        pendingFiles.clear()
        conversation.phase = ""
        if (config.openclawBaseUrl.isBlank()) {
            conversation.messages[conversation.messages.size - 1] =
                ChatMessage("assistant", "错误：请先在「设置」填写 OpenClaw 地址")
            store.save()
            return
        }
        conversation.busy = true
        conversation.replyStartedAt = android.os.SystemClock.elapsedRealtime()
        conversation.currentActivity.clear()
        val target = conversation
        // 把图片/文件描述拼到发送文本里（简单多模态：直接以文本形式告诉 agent）
        val combinedMsg = buildString {
            append(text)
            imagesIn.forEachIndexed { i, dataUri ->
                if (isNotBlank()) append('\n')
                append("\n[图片 ${i + 1}，长度 ${dataUri.length} 字符的 base64 JPEG 已附加]")
            }
            filesIn.forEach { f ->
                if (isNotBlank()) append("\n\n")
                append("[文件：${f.name}（${formatFileSize(f.sizeBytes)}，类型 ${f.mime}）]")
                when {
                    f.tooLarge -> append("\n（文件过大，未嵌入内容；agent 可通过工具读取）")
                    f.textContent != null -> {
                        append("\n内容：\n")
                        append(f.textContent)
                        append("\n[/文件]")
                    }
                    f.dataUri != null -> {
                        append("\nbase64 数据：")
                        append(f.dataUri)
                        append("\n[/文件]")
                    }
                }
            }
        }
        val job: Job = chatScope.launch {
            try {
                val sessionKey = "agent:main:" + target.key
                
                // 构建包含历史上下文的消息
                val contextMessage = buildString {
                    // 添加历史消息作为上下文
                    val historyMessages = target.messages.dropLast(2) // 排除当前用户消息和空助手消息
                    if (historyMessages.isNotEmpty()) {
                        append("=== 对话历史上下文 ===\n")
                        for (msg in historyMessages) {
                            when (msg.role) {
                                "user" -> append("用户: ${msg.content}\n")
                                "assistant" -> append("助手: ${msg.content}\n\n")
                            }
                        }
                        append("=== 当前问题 ===\n")
                    }
                    append(combinedMsg.ifBlank { text })
                }
                
                val reply = client.chat(
                    baseUrl = config.openclawBaseUrl,
                    token = config.openclawToken,
                    agentId = null,
                    message = contextMessage,
                    sessionKey = sessionKey,
                    onDelta = { t, r -> mainHandler.post { appendStreaming(target, t, r) } },
                    onPhase = { p -> mainHandler.post { target.phase = p } },
                    onActivity = { entry -> mainHandler.post { target.currentActivity.add(entry) } },
                    onMeta = { meta -> mainHandler.post { target.lastMeta = meta } },
                )
                mainHandler.post { finalizeReply(target, reply, target.lastMeta, context) }
            } catch (e: CancellationException) {
                mainHandler.post {
                    val idx = target.messages.size - 1
                    if (idx >= 0 && target.messages[idx].role == "assistant") {
                        val cur = target.messages[idx]
                        val markText = if (cur.content.isBlank()) "（已终止）" else cur.content + "\n\n_（已终止）_"
                        target.messages[idx] = cur.copy(content = markText)
                    }
                }
                throw e
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("CodingPlan") == true -> 
                        "订阅错误：您的 OpenClaw 账户没有有效的 CodingPlan 订阅或订阅已过期。\n" +
                        "请访问 https://console.volcengine.com/ark/region:ark+cn-beijing/openManagement?OpenModelVisible=false&tab=CodingPlan 检查订阅状态。"
                    e.message?.contains("subscription") == true ->
                        "订阅错误：您的订阅可能已过期或无效。"
                    else -> "错误：${e.message}"
                }
                mainHandler.post { finalizeReply(target, errorMsg, context = context) }
            } finally {
                mainHandler.post {
                    target.busy = false
                    target.phase = ""
                    target.activeChatJob = null
                    store.save()
                }
            }
            // 首轮回复完成后，用 LLM 总结对话主题作为标题（不阻塞 UI）
            if (target.titlePending || target.title == "新对话" || target.title.isBlank()) {
                try {
                    val titleReply = client.chat(
                        baseUrl = config.openclawBaseUrl,
                        token = config.openclawToken,
                        agentId = null,
                        message = "请用不超过10个中文字总结以上对话的主题作为标题。只输出标题文字，不要标点符号、引号、冒号或换行。",
                        sessionKey = "agent:main:" + target.key,
                    )
                    val cleanTitle = titleReply.trim()
                        .replace(Regex("[\\s\\n\\r]+"), "")
                        .replace(Regex("[\uff0c\u3002\uff1f\uff01.,?!;:\uff1a\uff1b\u201c\u201d\u2018\u2019\"'()\uff08\uff09\\[\\]\u3010\u3011{}\u2026~\u2014\\-]+"), "")
                        .take(10)
                    if (cleanTitle.isNotBlank()) {
                        mainHandler.post {
                            target.title = cleanTitle
                            target.titlePending = false
                            store.save()
                        }
                    }
                } catch (_: Exception) {
                    // LLM 总结失败：用用户首条消息兜底
                    val fallback = summarizeTitleFromConversation(target.messages)
                    mainHandler.post {
                        target.title = fallback
                        target.titlePending = false
                        store.save()
                    }
                }
            }
        }
        conversation.activeChatJob = job
        job.invokeOnCompletion {
            mainHandler.post {
                if (conversation.activeChatJob === job) conversation.activeChatJob = null
            }
        }
    }

    val send: () -> Unit = {
        sendMessage(input, pendingImages.toList(), pendingFiles.toList())
    }

    // 新消息/内容变化时滚动到底部
    LaunchedEffect(
        conversation.id,
        conversation.messages.size,
        conversation.messages.lastOrNull()?.content?.length
    ) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // 会话切换 + 新建
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showList = true }) {
                Text(
                    conversation.title.ifBlank { "新对话" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "对话列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${store.conversations.size} 个对话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { store.newConversation() }) {
                Icon(Icons.Filled.Add, contentDescription = "新对话")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            if (conversation.messages.isEmpty()) {
                item {
                    Text(
                        "输入消息开始对话。回复为流式显示，agent 执行工具可能需要一些时间。\n" +
                            "右上角「＋」可开新对话，旧对话保留可随时切换。",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(conversation.messages) { idx, msg ->
                val isLast = msg === conversation.messages.last()
                MessageBubble(
                    msg = msg,
                    streaming = conversation.busy && isLast,
                    liveActivity = if (conversation.busy && isLast) conversation.currentActivity else null,
                    // 仅当不忙且是用户消息时允许编辑
                    showEdit = !conversation.busy && msg.role == "user",
                    onEdit = {
                        editingMsgIndex = idx
                        editingText = msg.content
                    },
                )
            }
        }
        if (conversation.busy) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            if (conversation.phase.isNotBlank()) {
                Text(
                    conversation.phase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }
        // 待发图片预览
        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pendingImages.forEachIndexed { i, dataUri ->
                    Box(Modifier.size(72.dp)) {
                        base64ImageToBitmap(dataUri)?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "待发图片 ${i + 1}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        IconButton(
                            onClick = { pendingImages.removeAt(i) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除图片",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
        // 待发文件预览（可横向滚动，文件名 + 大小 + 移除按钮）
        if (pendingFiles.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pendingFiles.forEachIndexed { i, f ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    f.name.ifBlank { "未命名" },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    formatFileSize(f.sizeBytes) +
                                        if (f.tooLarge) " · 过大未嵌入" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除文件",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { pendingFiles.removeAt(i) }
                            )
                        }
                    }
                }
            }
        }
        // 输入行：图片按钮 / 文件按钮 / 输入框 / 停止按钮 或 发送按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = {
                    pickImageLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                enabled = !conversation.busy,
            ) {
                Icon(Icons.Filled.Image, contentDescription = "添加图片")
            }
            IconButton(
                onClick = { pickFileLauncher.launch(arrayOf("*/*")) },
                enabled = !conversation.busy,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "添加文件")
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("消息…（同一对话保持上下文）") },
                enabled = !conversation.busy,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (conversation.busy) {
                FilledIconButton(
                    onClick = {
                        conversation.activeChatJob?.cancel()
                        conversation.phase = "已终止"
                    },
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "终止回复",
                         tint = MaterialTheme.colorScheme.error)
                }
            } else {
                FilledIconButton(
                    onClick = send,
                    enabled = input.isNotBlank() ||
                        pendingImages.isNotEmpty() ||
                        pendingFiles.isNotEmpty()
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    // ---- 对话列表对话框 ----
    if (showList) {
        ConversationListDialog(
            store = store,
            currentId = conversation.id,
            onSelect = { store.select(it); showList = false },
            onDelete = { store.delete(it) },
            onNew = { store.newConversation(); showList = false },
            onDismiss = { showList = false }
        )
    }

    // ---- 修改提问对话框 ----
    val editIdx = editingMsgIndex
    if (editIdx != null) {
        AlertDialog(
            onDismissRequest = { editingMsgIndex = null },
            title = { Text("修改提问") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        label = { Text("修改后的提问内容") },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "保存后，该条用户消息及其之后的所有回复将被替换，并基于新提问重新生成回答。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = editIdx
                    editingMsgIndex = null
                    val msgSnapshot = conversation.messages.getOrNull(idx)
                    val imgSnapshot = msgSnapshot?.images.orEmpty()
                    val fileSnapshot = msgSnapshot?.files.orEmpty()
                    sendMessage(editingText, imgSnapshot, fileSnapshot, preAddIndex = idx)
                }) {
                    Text("保存并重发")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMsgIndex = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ConversationListDialog(
    store: ConversationStore,
    currentId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对话") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (store.conversations.isEmpty()) {
                    item {
                        Text(
                            "暂无对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(store.conversations, key = { it.id }) { conv ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(conv.id) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                conv.title.ifBlank { "新对话" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (conv.id == currentId) FontWeight.Bold else null
                            )
                            Text(
                                "${conv.messages.count { it.role == "user" }} 条消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (conv.busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = { onDelete(conv.id) },
                            enabled = !conv.busy
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("新建对话") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 流式更新最后一条助手消息 */
private fun appendStreaming(conversation: ConversationState, text: String, replace: Boolean) {
    val idx = conversation.messages.size - 1
    if (idx >= 0 && conversation.messages[idx].role == "assistant") {
        val cur = conversation.messages[idx]
        conversation.messages[idx] = cur.copy(
            content = if (replace) text else cur.content + text
        )
    }
}

/** 用最终回复覆盖最后一条助手消息（含耗时、执行步骤与用量元数据） */
private fun finalizeReply(
    conversation: ConversationState,
    text: String,
    meta: OpenClawClient.ChatMeta? = null,
    context: Context? = null,
) {
    val duration = if (conversation.replyStartedAt > 0) {
        android.os.SystemClock.elapsedRealtime() - conversation.replyStartedAt
    } else null
    val activity = conversation.currentActivity.toList()
    conversation.currentActivity.clear()
    conversation.replyStartedAt = 0L
    val idx = conversation.messages.size - 1
    val effectiveMeta = meta ?: conversation.lastMeta ?: OpenClawClient.ChatMeta()
    conversation.lastMeta = effectiveMeta
    val finalMsg = ChatMessage("assistant", text, duration, activity, effectiveMeta)
    if (idx >= 0 && conversation.messages[idx].role == "assistant") {
        conversation.messages[idx] = finalMsg
    } else {
        conversation.messages.add(finalMsg)
    }
    // 标题由 LLM 总结生成（在 sendMessage 协程中异步执行），
    // 此处仅在标题为默认时做一个临时兜底，LLM 总结完成后会覆盖
    if (conversation.title == "新对话" || conversation.title.isBlank()) {
        val fallback = summarizeTitleFromConversation(conversation.messages)
        conversation.title = fallback.ifBlank { "新对话" }
        conversation.titlePending = true
    }
    // 后台回复通知：应用不在前台时弹出系统通知
    if (context != null && text.isNotBlank() && !text.startsWith("错误") && !text.startsWith("连接中断")) {
        val app = context.applicationContext
        if (app is AgentHubApp) {
            app.notifyAgentReply(
                title = conversation.title.ifBlank { "Agent 新回复" },
                content = text,
                convId = conversation.id,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    streaming: Boolean,
    liveActivity: List<String>? = null,
    showEdit: Boolean = false,
    onEdit: () -> Unit = {},
) {
    val isUser = msg.role == "user"
    val activity = liveActivity ?: msg.activity
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // 用户消息左侧：编辑按钮
        if (isUser && showEdit) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.padding(end = 2.dp).size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "修改提问",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(if (isUser && showEdit) 0.82f else 0.88f)
        ) {
            Column(Modifier.padding(12.dp)) {
                // 消息内图片（用户发送的图片预览）
                if (msg.images.isNotEmpty()) {
                    val cols = if (msg.images.size == 1) 1 else 2
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val itemWidth = (maxWidth - (if (cols == 2) 6.dp else 0.dp)) / cols
                        Column {
                            msg.images.chunked(cols).forEachIndexed { rowIdx, rowImgs ->
                                if (rowIdx > 0) Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rowImgs.forEachIndexed { colIdx, dataUri ->
                                        val padEnd = if (cols == 2 && colIdx == 0) 0.dp else 0.dp
                                        androidx.compose.foundation.layout.Box(
                                            modifier = Modifier
                                                .width(itemWidth - padEnd)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                        ) {
                                            base64ImageToBitmap(dataUri)?.let { bmp ->
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = "图片",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 消息内附带文件（用户发送的文件标签）
                if (msg.files.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        msg.files.forEach { f ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            f.name.ifBlank { "未命名" },
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            formatFileSize(f.sizeBytes) +
                                                if (f.tooLarge) " · 过大未嵌入" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 可折叠的执行过程（agent 调用了哪些工具/命令）
                if (activity.isNotEmpty()) {
                    ActivitySection(
                        entries = activity,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        live = liveActivity != null,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 可长按选择文本（系统选择手柄 + 复制菜单）
                SelectionContainer {
                    val shown = msg.content.ifBlank { if (streaming) "…" else "" }
                    if (isUser) {
                        Text(shown, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        // 助手回复按 Markdown 渲染：表格/代码块/列表/标题/行内样式
                        MarkdownText(shown)
                    }
                }
                if (streaming && msg.content.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "▍",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 助手回复耗时
                if (!isUser && msg.durationMs != null && !streaming) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⏱ ${formatDuration(msg.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 可折叠执行过程区：默认收起，点击标题展开/收起 */
@Composable
private fun ActivitySection(
    entries: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    live: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (live) "执行中" else "执行过程") + "（${entries.size} 步）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "收起 ▲" else "展开 ▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            entries.forEachIndexed { index, entry ->
                Text(
                    "${index + 1}. $entry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

// ---------------- 技能页 ----------------

/** 常见技能的中文功能描述（按 skillKey/name 匹配；未收录的显示服务端原文） */
private val SKILL_ZH: Map<String, String> = mapOf(
    // OpenClaw 内置技能（skills/ 目录）
    "1password" to "1Password 密码管理：查询和填充密码凭证",
    "apple-notes" to "Apple 备忘录：读写 macOS 备忘录",
    "apple-reminders" to "Apple 提醒事项：管理待办提醒",
    "bear-notes" to "Bear 笔记：读写 Bear 笔记本",
    "blogwatcher" to "博客监控：订阅并追踪网站更新",
    "blucli" to "蓝牙设备管理",
    "bluebubbles" to "BlueBubbles：通过服务器收发 iMessage",
    "camsnap" to "摄像头拍照：抓取摄像头画面",
    "canvas" to "画布：创建和编辑富文档、图表",
    "clawhub" to "ClawHub：搜索和安装社区技能市场",
    "coding-agent" to "编程代理：调用子代理执行编码任务",
    "discord" to "Discord：收发消息、管理频道",
    "eightctl" to "Eight Sleep 智能床垫控制",
    "gemini" to "Google Gemini：调用 Gemini 模型",
    "gh-issues" to "GitHub Issues：查看和处理议题",
    "gifgrep" to "GIF 搜索：按语义查找 GIF 表情图",
    "github" to "GitHub：仓库、PR、Issue 全套操作",
    "gog" to "GOG 游戏平台：查询游戏库",
    "goplaces" to "地点推荐：搜索餐厅、咖啡馆等场所",
    "healthcheck" to "健康检查：定时检测服务可用性",
    "himalaya" to "命令行邮件：收发和管理邮箱",
    "imsg" to "iMessage：发送苹果消息",
    "mcporter" to "Minecraft 服务器管理",
    "model-usage" to "模型用量：统计 token 消耗情况",
    "nano-pdf" to "PDF 处理：读取和生成 PDF 文件",
    "node-connect" to "远程节点：连接其他 OpenClaw 节点",
    "notion" to "Notion：读写页面、数据库",
    "obsidian" to "Obsidian 笔记库管理",
    "openai-whisper" to "本地语音转文字（Whisper）",
    "openai-whisper-api" to "OpenAI 语音转文字 API",
    "openhue" to "飞利浦 Hue 智能灯控制",
    "oracle" to "Oracle 数据库查询",
    "ordercli" to "订单管理",
    "peekaboo" to "屏幕截图：查看和截取屏幕内容",
    "sag" to "SAG：流式 agent 编排",
    "session-logs" to "会话日志：检索历史对话记录",
    "sherpa-onnx-tts" to "本地语音合成（TTS）",
    "skill-creator" to "技能创建：生成新技能脚手架",
    "slack" to "Slack：收发消息、管理频道",
    "songsee" to "听歌识曲：识别正在播放的音乐",
    "sonoscli" to "Sonos 音箱控制",
    "spotify-player" to "Spotify 播放控制：播放、搜索、队列",
    "summarize" to "内容摘要：总结网页和文档",
    "taskflow" to "任务流：多步骤任务编排",
    "taskflow-inbox-triage" to "收件箱分诊：自动归类处理待办",
    "things-mac" to "Things 待办事项管理（macOS）",
    "tmux" to "tmux 终端会话管理",
    "trello" to "Trello 看板：管理卡片和列表",
    "video-frames" to "视频帧分析：提取和分析视频画面",
    "voice-call" to "语音通话：发起和接听电话",
    "wacli" to "WhatsApp 消息收发",
    "weather" to "天气查询：获取天气预报",
    "xurl" to "网页抓取：提取 URL 内容",
    // 常见托管/社区技能
    "memory" to "记忆：跨会话记住与回忆重要信息",
    "web-search" to "联网搜索：检索最新网页信息",
    "browser" to "浏览器：自动打开并操作网页",
    "git" to "Git 版本控制操作",
    "code-index" to "代码索引：快速检索代码库",
    "doctor" to "诊断：自动检查并修复运行问题",
    "push" to "推送：主动发送通知消息",
    "voice-wake" to "语音唤醒：监听唤醒词",
    "heartbeat" to "心跳：定时自动唤醒执行任务",
    "automation" to "自动化：定时任务编排",
    "calendar" to "日历：查看和管理日程",
    "email" to "邮件：收发电子邮件",
    "screenshot" to "屏幕截图",
    "files" to "文件管理：读写本地文件",
    "terminal" to "终端：执行 shell 命令",
)

/** 技能中文描述：先精确/包含匹配词典，未命中则返回服务端原文 */
private fun skillZhDescription(skill: SkillInfo): String {
    val key = skill.skillKey.lowercase().trim()
    val name = skill.name.lowercase().trim()
    SKILL_ZH[key]?.let { return it }
    SKILL_ZH[name]?.let { return it }
    // 前缀/包含匹配（如 web-search-pro）
    for ((k, v) in SKILL_ZH) {
        if (key == k || name == k || key.startsWith("$k-") || name.startsWith("$k-")) return v
    }
    return skill.description
}

/** skills.status 返回的技能条目（UI 需要的字段） */
data class SkillInfo(
    val name: String,
    val description: String,
    val skillKey: String,
    val source: String,
    val emoji: String?,
    val bundled: Boolean,
    val disabled: Boolean,
    val eligible: Boolean,
)

@Composable
fun SkillsScreen(config: AppConfig) {
    val context = LocalContext.current
    val client = remember { OpenClawClient(context, DeviceIdentityStore(context)) }
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var togglingKey by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (config.openclawBaseUrl.isBlank()) {
            error = "请先在「设置」中填写并保存 OpenClaw 地址"
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val arr = client.skillsStatus(config.openclawBaseUrl, config.openclawToken)
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    SkillInfo(
                        name = o.optString("name"),
                        description = o.optString("description"),
                        skillKey = o.optString("skillKey").ifBlank { o.optString("name") },
                        source = o.optString("source"),
                        emoji = o.optString("emoji").ifBlank { null },
                        bundled = o.optBoolean("bundled", false),
                        disabled = o.optBoolean("disabled", false),
                        eligible = o.optBoolean("eligible", true),
                    )
                }
                skills = list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    // 进入页面 / 配置变更时自动加载
    LaunchedEffect(config.openclawBaseUrl, config.openclawToken) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("技能管理", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh() }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("加载技能列表…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { refresh() }) { Text("重试") }
                    }
                }
            }
            skills.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "未发现已安装技能",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                    items(skills, key = { it.skillKey }) { skill ->
                        SkillRow(
                            skill = skill,
                            toggling = togglingKey == skill.skillKey,
                            enabled = togglingKey == null,
                            onToggle = { enabled ->
                                togglingKey = skill.skillKey
                                scope.launch {
                                    try {
                                        client.skillsSetEnabled(
                                            config.openclawBaseUrl, config.openclawToken,
                                            skill.skillKey, enabled
                                        )
                                        // 本地同步开关状态
                                        skills = skills.map {
                                            if (it.skillKey == skill.skillKey) {
                                                it.copy(disabled = !enabled)
                                            } else it
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = "更新失败：${e.message}"
                                    } finally {
                                        togglingKey = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillInfo,
    toggling: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.emoji ?: "🧩", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val zhDesc = skillZhDescription(skill)
                if (zhDesc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        zhDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    SkillBadge(skill.source.ifBlank { "unknown" })
                    if (skill.disabled) SkillBadge("已停用")
                    if (!skill.eligible) SkillBadge("缺少依赖")
                    if (skill.bundled) SkillBadge("内置")
                }
            }
            Spacer(Modifier.width(8.dp))
            if (toggling) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = !skill.disabled,
                    enabled = enabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

@Composable
private fun SkillBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ---------------- 设置页 ----------------

@Composable
fun SettingsScreen(config: AppConfig, onSave: (AppConfig) -> Unit) {
    val context = LocalContext.current
    val oc = remember { OpenClawClient() }
    val scope = rememberCoroutineScope()
    var ocUrl by remember { mutableStateOf(config.openclawBaseUrl) }
    var ocToken by remember { mutableStateOf(config.openclawToken) }
    var themeMode by remember { mutableStateOf(config.themeMode) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("未测试") }
    val versionInfo = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "AgentHub v${pi.versionName}（构建 ${pi.longVersionCode}）"
        } catch (_: Exception) {
            "AgentHub"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("连接设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "填写另一台设备上 OpenClaw 的地址（Tailscale IP 或 MagicDNS 域名）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Text("OpenClaw（默认端口 18789）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ocUrl,
            onValueChange = { ocUrl = it; saved = false },
            label = { Text("OpenClaw 地址") },
            placeholder = { Text("http://100.x.y.z:18789") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ocToken,
            onValueChange = { ocToken = it; saved = false },
            label = { Text("Gateway 令牌 / 密码（无认证可留空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("测试中…", style = MaterialTheme.typography.bodyMedium)
            } else {
                FilledTonalButton(
                    onClick = {
                        if (ocUrl.isBlank()) {
                            testStatus = "请先填写地址"
                        } else {
                            testing = true
                            testStatus = "测试中…"
                            scope.launch {
                                testStatus = try {
                                    "连接成功：" + oc.health(ocUrl.trim())
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "失败：${e.message}"
                                }
                                testing = false
                            }
                        }
                    }
                ) { Text("测试连接") }
            }
        }
        if (testStatus.isNotBlank() && testStatus != "未测试") {
            Spacer(Modifier.height(6.dp))
            Text(
                testStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------------- 配置持久化（卸载不丢失） ----------------
        Spacer(Modifier.height(24.dp))
        Text("配置持久化", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "授权后配置会同时保存到 /sdcard/AgentHub/config.json，" +
                "卸载重装后自动恢复，无需重新填写连接信息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val configStore = remember { ConfigStore(context) }
        var extGranted by remember { mutableStateOf(configStore.hasExternalAccess()) }
        var backupMsg by remember { mutableStateOf("") }
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { extGranted = configStore.hasExternalAccess() }
        val runtimePermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> extGranted = granted && configStore.hasExternalAccess() }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+：跳系统「所有文件访问权限」页
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        ).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        permLauncher.launch(intent)
                    } else {
                        // Android 10 及以下：运行时申请 WRITE_EXTERNAL_STORAGE
                        runtimePermLauncher.launch(
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (extGranted) "已授权" else "授予存储权限")
            }
            if (extGranted) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val ok = configStore.saveToExternal(
                            AppConfig(ocUrl, ocToken, themeMode)
                        )
                        backupMsg = if (ok) "已备份到 ${configStore.externalFile.absolutePath}"
                            else "备份失败"
                    }
                ) { Text("立即备份") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val importedConfig = configStore.importFromExternal()
                        if (importedConfig != null) {
                            ocUrl = importedConfig.openclawBaseUrl
                            ocToken = importedConfig.openclawToken
                            themeMode = importedConfig.themeMode
                            saved = false
                            backupMsg = "已从外部文件导入配置"
                        } else {
                            backupMsg = "导入失败：文件不存在或格式错误"
                        }
                    }
                ) { Text("导入配置") }
            }
        }
        if (extGranted) {
            Spacer(Modifier.height(4.dp))
            Text(
                "配置文件：${configStore.externalFile.absolutePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (backupMsg.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                backupMsg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ---------------- 主题模式 ----------------
        Spacer(Modifier.height(24.dp))
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val themeOptions = listOf(0 to "跟随系统", 1 to "浅色模式", 2 to "深色模式")
        themeOptions.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { themeMode = value; saved = false }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = themeMode == value,
                    onClick = { themeMode = value; saved = false }
                )
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                onSave(AppConfig(ocUrl, ocToken, themeMode))
                saved = true
                testStatus = "未测试"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }
        if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("已保存", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "服务端准备：\n" +
                "· OpenClaw：开启 Gateway（默认端口 18789），如设置了 token/密码请填到上方；\n" +
                "· 首次对话需在 OpenClaw 设备上批准一次设备配对（openclaw devices approve）；\n" +
                "· 手机与本机加入同一 Tailscale 网络即可互通。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Text(
            versionInfo,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // ---------------- 更新日志 ----------------
        Spacer(Modifier.height(20.dp))
        var showChangelog by remember { mutableStateOf(false) }
        TextButton(onClick = { showChangelog = !showChangelog }) {
            Text(
                if (showChangelog) "收起更新日志" else "查看更新日志",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (showChangelog) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        CHANGELOG_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/** 内嵌更新日志（与 workspace/CHANGELOG.txt 同步） */
private const val CHANGELOG_TEXT = """AgentHub 版本更新日志
================================================================

v1.15 (versionCode 20)
----------------------------------------------------------------
[改进] 错误处理：订阅相关错误不重试，提供更清晰的错误提示
[新增] 订阅错误解决指南：帮助用户解决 CodingPlan 订阅问题

v1.14 (versionCode 19)
----------------------------------------------------------------
[修复] 对话混乱问题：添加会话键过滤，防止不同对话的消息交叉
[修复] 上下文遗忘问题：发送历史对话上下文，保持长对话的连贯性
[新增] 配置导入功能：设置页「配置持久化」模块新增导入按钮，
  可从外部文件导入配置

v1.13 (versionCode 18)
----------------------------------------------------------------
[移除] 底部状态栏（模型 / token 用量 / 缓存命中率）：该栏
  总是不能正确显示模型与 token 状态，故删除
[新增] 后台回复通知：当应用不在前台（按 Home 切换出去）时，
  Agent 完成一轮回复，会弹出系统通知，点击通知跳转回对话
  - Android 13+ 首次启动会询问通知权限（拒绝仍可正常使用）
  - 应用回到前台时自动清除所有未读回复通知

v1.12 (versionCode 17)
----------------------------------------------------------------
[新增] 上传文件功能：输入栏新增附件按钮（📎），可选任意类型
  文件；文本类（.txt/.md/.json/.kt/.py 等）直接嵌入内容，
  二进制类转 base64；超过 256KB 仅记录元数据。气泡内显示
  文件标签，重启后保留
[新增] 配置持久化到外部存储：设置页「配置持久化」模块，
  授权后配置同时保存到 /sdcard/AgentHub/config.json，
  卸载重装自动恢复连接信息；manifest 加 hasFragileUserData
  让系统卸载时询问保留数据
[修复] 等待回复超时（超过 300 秒）频繁出现：
  - CHAT_TIMEOUT_MS 5 分钟 → 10 分钟
  - 自动重连次数 2 → 3
  - 新增静默断连看门狗：120 秒无任何事件即主动重连
    （防止 NAT/WiFi 省电导致的"看似在线实则断开"）

v1.10 (versionCode 15)
----------------------------------------------------------------
[新增] 发送图片功能：相册选图→压缩至 1024px JPEG→Base64
  附加消息；发送前可预览/移除；气泡内 1~2 列缩略渲染
[新增] Agent 回复终止功能：回复进行中发送按钮切换为红色
  停止图标，点击取消协程并标记「（已终止）」
[新增] 提问修改功能：用户消息左侧新增编辑图标；弹窗修改
  后截断该条之后的所有消息并基于新提问重新生成
[改进] 对话标题自动总结：优先取首个用户提问前 15 字，
  不再用助手回复首行；无用户提问时再从全文抽取
[改进] 底部状态栏：只要有 lastMeta 就强制显示；模型名
  缺失时显示「模型:—」；缓存命中率始终显示
[新增] 夜间模式切换并持久化：设置页单选 跟随系统/浅色/深色
[修复] 本地持久化遗漏 images 字段（此前图片重启后丢失）
[修复] 设置页保存时遗漏 themeMode（此前主题不生效）

v1.9 (versionCode 14)
----------------------------------------------------------------
[新增] 设置页更新日志模块（可折叠查看）
[新增] 对话标题自动总结：首轮回复后取首行有用文本，
  去掉 Markdown 标记，截断到约 15 字
[新增] 聊天界面底部状态栏：显示当前模型、token 用量
  （输入↓/输出↑）、缓存命中率
[新增] 用量元数据持久化：重启后保留历史 token 统计

v1.8 (versionCode 13)
----------------------------------------------------------------
[修复] 启动闪退（v1.4~v1.7 均受影响）
  根因：OkHttp ConnectionPool(0, 0, ...) 非法，keepAliveDuration
  必须 > 0，首次构建客户端即崩溃。已移除该配置。

v1.7 (versionCode 12)
----------------------------------------------------------------
[新增] 崩溃日志捕获页：崩溃后重开可复制堆栈反馈
[优化] APK 体积 9.9MB → 3.4MB（R8 代码压缩）
[加固] Markdown 解析异常降级纯文本

v1.6 (versionCode 11)
----------------------------------------------------------------
[新增] 助手回复 Markdown 渲染：标题/粗斜体/行内代码/
  代码块/表格/列表/引用，流式未闭合代码块即时渲染

v1.5 (versionCode 10)
----------------------------------------------------------------
[修复] "Software caused connection abort"：
  - 连接错误无条件自动重试（复用 idempotencyKey 幂等）
  - WiFi 高性能锁 + CPU 唤醒锁防省电断连
  - WebSocket ping 加密至 20 秒

v1.4 (versionCode 9)
----------------------------------------------------------------
[尝试] 连接中断修复（引入闪退 bug，v1.8 修复）

v1.3 及更早 (versionCode ≤ 8)
----------------------------------------------------------------
[基础功能] WebSocket + Ed25519 签名连接、流式聊天、
  多对话管理、技能管理、设置页"""
