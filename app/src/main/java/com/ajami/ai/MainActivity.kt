package com.ajami.ai

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.widget.Toast
import android.app.DownloadManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel

private data class ChatMessage(val role: String, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AjamiApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AjamiApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var studioOpen by remember { mutableStateOf(false) }
    var imageStudioOpen by remember { mutableStateOf(false) }
    var videoStudioOpen by remember { mutableStateOf(false) }
    var audioStudioOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var assistantMode by remember { mutableStateOf("گفت‌وگوی عمومی") }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var posterTitle by remember { mutableStateOf("عنوان پوستر شما") }
    var posterSubtitle by remember { mutableStateOf("متن توضیحات را اینجا بنویسید") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("مدل آفلاین هنوز دانلود نشده است.") }
    var modelDownloading by remember { mutableStateOf(false) }
    var offlineModel by remember { mutableStateOf<LlamaModel?>(null) }
    var offlineLoading by remember { mutableStateOf(false) }
    var offlineMode by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("4B") }
    val modelFileName = "Qwen3-4B-Q4_K_M.gguf"
    val modelFile = remember(context) {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), modelFileName)
    }
    val selectedModelFile = modelFile
    val selectedModelFileName = modelFileName
    LaunchedEffect(Unit) {
        selectedModel = "4B"
        if (modelFile.exists() && modelFile.length() > 2_300_000_000L) {
            modelStatus = "مدل قدرتمند Qwen3-4B روی گوشی موجود است؛ آن را بارگذاری کن."
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    val prefsDownload = context.getSharedPreferences("ajami_downloads", 0)
                    if (id == prefsDownload.getLong("model_download_id", -2L)) {
                        val downloadedName = prefsDownload.getString("model_download_filename", modelFileName) ?: modelFileName
                        val downloadedFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), downloadedName)
                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val query = DownloadManager.Query().setFilterById(id)
                        manager.query(query).use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                                if (status == DownloadManager.STATUS_SUCCESSFUL && downloadedFile.exists()) {
                                    modelStatus = "مدل $downloadedName با موفقیت دانلود شد؛ آن را انتخاب و بارگذاری کن."
                                } else {
                                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                    modelStatus = "دانلود ناموفق بود (کد $reason). اینترنت و فضای ذخیره‌سازی را بررسی کن."
                                }
                            }
                        }
                        modelDownloading = false
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val messages = remember {
        mutableStateListOf(ChatMessage("assistant", "سلام! برای چت آفلاین، مدل را دانلود و بارگذاری کن. پس از بارگذاری، حالت آفلاین فعال می‌شود و نیازی به API نیست."))
    }
    val scope = rememberCoroutineScope()
    val chatPrefs = remember(context) { context.getSharedPreferences("ajami_chat_local", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        selectedModel = "4B"
        val saved = chatPrefs.getString("messages_json", null)
        if (!saved.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(saved)
                messages.clear()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val role = item.optString("role", "assistant")
                    val text = item.optString("text", "")
                    if (text.isNotBlank()) messages.add(ChatMessage(role, text))
                }
            }.onFailure {
                messages.clear()
                messages.add(ChatMessage("assistant", "تاریخچه قبلی قابل خواندن نبود؛ گفت‌وگوی تازه شروع شد."))
            }
        }
    }
    LaunchedEffect(messages.size) {
        val array = org.json.JSONArray()
        messages.forEach { msg ->
            array.put(org.json.JSONObject().put("role", msg.role).put("text", msg.text))
        }
        chatPrefs.edit().putString("messages_json", array.toString()).apply()
    }
    LaunchedEffect(selectedModel) {
        chatPrefs.edit().putString("selected_model", selectedModel).apply()
    }
    val gold = Color(0xFFFFC857)

    MaterialTheme(colorScheme = darkColorScheme(primary = gold, background = Color(0xFF071A35), surface = Color(0xFF10294A))) {
        Scaffold(
            containerColor = Color(0xFF071A35),
            topBar = {
                TopAppBar(
                    title = { Text("AJAMI-AI", color = gold) },
                    actions = { Row { TextButton(onClick = { studioOpen = !studioOpen; imageStudioOpen = false; videoStudioOpen = false }) { Text(if (studioOpen) "چت" else "پوستر") }; TextButton(onClick = { imageStudioOpen = !imageStudioOpen; studioOpen = false; videoStudioOpen = false }) { Text(if (imageStudioOpen) "چت" else "تصویرساز") }; TextButton(onClick = { videoStudioOpen = !videoStudioOpen; studioOpen = false; imageStudioOpen = false; audioStudioOpen = false; diagnosticsOpen = false }) { Text(if (videoStudioOpen) "چت" else "فیلم") }; TextButton(onClick = { audioStudioOpen = !audioStudioOpen; studioOpen = false; imageStudioOpen = false; videoStudioOpen = false; diagnosticsOpen = false }) { Text(if (audioStudioOpen) "چت" else "صدا") }; TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen; studioOpen = false; imageStudioOpen = false; videoStudioOpen = false; audioStudioOpen = false }) { Text(if (diagnosticsOpen) "چت" else "سازگاری") } } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B2140))
                )
            },
            bottomBar = {
                if (!studioOpen && !imageStudioOpen && !videoStudioOpen && !audioStudioOpen && !diagnosticsOpen) Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("پیامت را بنویس...") },
                        maxLines = 4
                    )
                    Button(
                        enabled = !busy && input.isNotBlank(),
                        onClick = {
                            val prompt = input.trim()
                            input = ""
                            messages.add(ChatMessage("user", prompt))
                            val loaded = offlineModel
                            if (loaded == null || !offlineMode) {
                                messages.add(ChatMessage("assistant", "برای استفاده از AJAMI-AI، ابتدا مدل آفلاین را دانلود و بارگذاری کن. این برنامه فقط آفلاین کار می‌کند."))
                            } else {
                                busy = true
                                val history = messages.toList()
                                scope.launch {
                                    val answer = withContext(Dispatchers.Default) {
                                        runCatching {
                                            val transcript = history.takeLast(12).joinToString("\\n") { m ->
                                                (if (m.role == "user") "کاربر" else "دستیار") + ": " + m.text
                                            }
                                            val taskInstruction = when (assistantMode) {
                                                "برنامه‌نویسی" -> "نقش تو دستیار برنامه‌نویسی آفلاین هستی. کد درست، کامل و قابل اجرا بنویس؛ زبان و نسخه را مشخص کن، خطاها را توضیح بده و از ساختن کتابخانه یا API خیالی خودداری کن."
                                                "ترجمه" -> "نقش تو مترجم دقیق هستی. متن را به زبان درخواستی ترجمه کن، معنی و لحن را حفظ کن و اگر زبان مقصد مشخص نیست سؤال کوتاه بپرس."
                                                "خلاصه‌سازی" -> "متن ورودی را بدون افزودن اطلاعات ساختگی خلاصه کن. نکات کلیدی را منظم و کوتاه ارائه بده."
                                                "نگارش و تولید متن" -> "در نگارش فارسی حرفه‌ای کمک کن؛ متن روشن، منسجم و متناسب با مخاطب تولید کن و اطلاعات نامعلوم را جعل نکن."
                                                "آموزش و حل مسئله" -> "مانند معلم صبور عمل کن؛ مسئله را مرحله‌به‌مرحله توضیح بده، مثال ساده بزن و در صورت نامشخص بودن داده‌ها فرضیات را روشن کن."
                                                else -> "به‌عنوان دستیار عمومی فارسی پاسخ دقیق، مفید و روشن بده."
                                            }
                                            Llama.complete(loaded, prompt = transcript, systemPrompt = "تو دستیار هوش مصنوعی فارسی AJAMI-AI هستی. $taskInstruction ${if (assistantMode == "برنامه‌نویسی" || assistantMode == "آموزش و حل مسئله") "/think" else "/no_think"} اگر پاسخ را نمی‌دانی، صادقانه بگو. بدون اینترنت و API کار می‌کنی.", maxTokens = 512).text
                                        }.getOrElse { "خطا در پاسخ آفلاین: ${it.localizedMessage ?: "خطای ناشناخته"}" }
                                    }
                                    messages.add(ChatMessage("assistant", answer))
                                    busy = false
                                }
                            }
                        }
                    ) {
                        Text("ارسال")
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp)
            ) {
                if (diagnosticsOpen) {
                    DeviceDiagnostics()
                } else if (audioStudioOpen) {
                    OfflineAudioStudio()
                } else if (videoStudioOpen) {
                    OfflineVideoStudio(offlineModel)
                } else if (imageStudioOpen) {
                    AIImageStudio(offlineModel)
                } else if (studioOpen) {
                    PosterStudio(
                        title = posterTitle,
                        onTitleChange = { posterTitle = it },
                        subtitle = posterSubtitle,
                        onSubtitleChange = { posterSubtitle = it },
                        onExport = { exportPoster(context, posterTitle, posterSubtitle) }
                    )
                }
                if (!videoStudioOpen && !imageStudioOpen && !studioOpen && !diagnosticsOpen && !audioStudioOpen) {
                Text("دستیار فارسی • نسخه 1.0.0", color = gold, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مدل هوش مصنوعی آفلاین", color = gold, style = MaterialTheme.typography.titleMedium)
                        Text("مدل زبانی قدرتمند Qwen3 با ۴ میلیارد پارامتر؛ کاملاً محلی و بدون API یا اینترنت هنگام گفتگو.", color = Color.White)
                        Text(modelStatus, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("Qwen3-4B • Q4_K_M • حدود ۲٫۵ گیگابایت • پنجره زمینه ۲۰۴۸ توکن", color = Color.White)
                        if (selectedModelFile.exists() && offlineModel == null) {
                            Button(enabled = !offlineLoading, onClick = {
                                offlineLoading = true
                                modelStatus = "در حال بارگذاری مدل در حافظه گوشی..."
                                scope.launch {
                                    try {
                                        val minimumSize = 2_300_000_000L
                                        if (!selectedModelFile.exists() || selectedModelFile.length() < minimumSize) {
                                            error("فایل مدل انتخاب‌شده ناقص است یا دانلود نشده؛ آن را دوباره دانلود کن.")
                                        }
                                        val magic = selectedModelFile.inputStream().use { stream ->
                                            ByteArray(4).also { if (stream.read(it) != 4) error("خواندن فایل مدل ممکن نیست.") }
                                        }
                                        if (!magic.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))) {
                                            error("فایل دانلودشده مدل GGUF معتبر نیست؛ آن را حذف و دوباره دانلود کن.")
                                        }
                                        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                        val memoryInfo = ActivityManager.MemoryInfo()
                                        memory.getMemoryInfo(memoryInfo)
                                        val requiredFreeMemory = 2_800_000_000L
                                        if (memoryInfo.lowMemory || memoryInfo.availMem < requiredFreeMemory) {
                                            error("برای بارگذاری Qwen3-4B حدود ۲٫۸ گیگابایت RAM آزاد لازم است. برنامه‌های پس‌زمینه را ببند و دوباره تلاش کن.")
                                        }
                                        val loadPath = selectedModelFile.absolutePath
                                        val contextSize = 2048
                                        val loaded = withContext(Dispatchers.Default) {
                                            Llama.loadModel(
                                                loadPath,
                                                LlamaConfig(contextSize = contextSize, threads = 4, gpuLayers = 0)
                                            )
                                        }
                                        offlineModel = loaded
                                        offlineMode = true
                                        modelStatus = "مدل بارگذاری شد؛ حالت آفلاین فعال است. برای پاسخ‌گویی نیازی به اینترنت یا API نیست."
                                    } catch (e: Throwable) {
                                        val detail = e.localizedMessage ?: e.javaClass.simpleName
                                        modelStatus = "بارگذاری مدل انجام نشد: $detail. اگر برنامه بسته شد، ممکن است موتور بومی یا حافظه گوشی دچار خطا شده باشد."
                                    } finally { offlineLoading = false }
                                }
                            }) { Text(if (offlineLoading) "در حال بارگذاری..." else "بارگذاری مدل برای چت آفلاین") }
                        }
                        if (offlineModel != null) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("حالت آفلاین", color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = offlineMode, onCheckedChange = { offlineMode = it })
                            }
                        }
                        Button(
                            enabled = !modelDownloading && !selectedModelFile.exists(),
                            onClick = {
                                try {
                                    val modelUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true"
                                    val request = DownloadManager.Request(Uri.parse(modelUrl))
                                        .setTitle("دانلود مدل آفلاین AJAMI-AI")
                                        .setDescription("دانلود مدل قدرتمند Qwen3-4B Q4_K_M به‌صورت محلی")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setAllowedOverMetered(true)
                                        .setAllowedOverRoaming(false)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, selectedModelFileName)
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val id = manager.enqueue(request)
                                    context.getSharedPreferences("ajami_downloads", 0).edit()
                                        .putLong("model_download_id", id)
                                        .putString("model_download_filename", selectedModelFileName)
                                        .apply()
                                    modelDownloading = true
                                    modelStatus = "دانلود آغاز شد؛ می‌توانی از اعلان‌های گوشی پیشرفت آن را ببینی."
                                } catch (e: Exception) {
                                    modelStatus = "شروع دانلود ممکن نشد: ${e.localizedMessage ?: "خطای ناشناخته"}"
                                }
                            }
                        ) { Text(if (modelDownloading) "در حال دانلود..." else if (selectedModelFile.exists() && selectedModelFile.length() > 2_300_000_000L) "مدل Qwen3-4B موجود است" else "دانلود مدل قدرتمند Qwen3-4B") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("حالت دستیار:", color = gold)
                    Box {
                        OutlinedButton(onClick = { modeMenuExpanded = true }) {
                            Text(assistantMode)
                        }
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false }
                        ) {
                            listOf("گفت‌وگوی عمومی", "برنامه‌نویسی", "ترجمه", "خلاصه‌سازی", "نگارش و تولید متن", "آموزش و حل مسئله").forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode) },
                                    onClick = {
                                        assistantMode = mode
                                        modeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("تاریخچه روی همین گوشی ذخیره می‌شود", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = {
                        messages.clear()
                        messages.add(ChatMessage("assistant", "تاریخچه پاک شد. گفت‌وگوی تازه آماده است."))
                    }) { Text("پاک‌کردن تاریخچه", color = gold) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.role == "user") Color(0xFF173B63) else Color(0xFF10294A)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (msg.role == "user") "شما" else "AJAMI-AI",
                                    color = gold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = msg.text, color = Color.White)
                            }
                        }
                    }
                    if (busy) {
                        item { CircularProgressIndicator(color = gold) }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PosterStudio(
    title: String,
    onTitleChange: (String) -> Unit,
    subtitle: String,
    onSubtitleChange: (String) -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("استودیوی طراحی پوستر آفلاین", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleMedium)
            Text("طراحی محلی بدون API؛ متن فارسی را وارد کن و تصویر PNG بساز.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("عنوان پوستر") }, modifier = Modifier.fillMaxWidth(), singleLine = false)
            OutlinedTextField(value = subtitle, onValueChange = onSubtitleChange, label = { Text("توضیحات") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("ساخت و ذخیره پوستر PNG") }
        }
    }
}

private fun exportPoster(context: Context, title: String, subtitle: String) {
    try {
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val canvas = NativeCanvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(7, 26, 53))
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(255, 200, 87); style = Paint.Style.FILL }
        canvas.drawRoundRect(70f, 70f, 1010f, 1280f, 42f, 42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(16, 41, 74); style = Paint.Style.FILL })
        canvas.drawRoundRect(110f, 180f, 970f, 194f, 7f, 7f, accent)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 76f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(220, 230, 242)
            textSize = 44f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        drawCenteredLines(canvas, title.ifBlank { "پوستر جدید" }, titlePaint, 540f, 530f, 96f, 3)
        drawCenteredLines(canvas, subtitle, bodyPaint, 540f, 760f, 68f, 7)
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 200, 87)
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("طراحی شده با AJAMI-AI", 540f, 1190f, footer)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AJAMI_Poster_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AJAMI-AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("ساخت فایل در گالری ناموفق بود")
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("ذخیره تصویر ناموفق بود")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AJAMI-AI").apply { mkdirs() }
            File(dir, "AJAMI_Poster_${System.currentTimeMillis()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
        Toast.makeText(context, "پوستر PNG ساخته و ذخیره شد.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "خطا در ساخت پوستر: ${e.localizedMessage ?: "نامشخص"}", Toast.LENGTH_LONG).show()
    }
}

private fun drawCenteredLines(canvas: NativeCanvas, text: String, paint: Paint, centerX: Float, startY: Float, lineHeight: Float, maxLines: Int) {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val lines = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > 850f && line.isNotEmpty()) {
            lines.add(line)
            line = word
        } else line = candidate
    }
    if (line.isNotEmpty()) lines.add(line)
    lines.take(maxLines).forEachIndexed { index, value -> canvas.drawText(value, centerX, startY + index * lineHeight, paint) }
}
