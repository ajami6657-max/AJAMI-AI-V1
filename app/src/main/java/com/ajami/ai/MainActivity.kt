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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    val prefs = remember { context.getSharedPreferences("ajami_settings", 0) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://api.openai.com/v1") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var studioOpen by remember { mutableStateOf(false) }
    var posterTitle by remember { mutableStateOf("عنوان پوستر شما") }
    var posterSubtitle by remember { mutableStateOf("متن توضیحات را اینجا بنویسید") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("مدل آفلاین هنوز دانلود نشده است.") }
    var modelDownloading by remember { mutableStateOf(false) }
    var offlineModel by remember { mutableStateOf<LlamaModel?>(null) }
    var offlineLoading by remember { mutableStateOf(false) }
    var offlineMode by remember { mutableStateOf(false) }
    val modelFileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val modelFile = remember(context) {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), modelFileName)
    }
    LaunchedEffect(Unit) {
        if (modelFile.exists() && modelFile.length() > 900_000_000L) {
            modelStatus = "مدل دانلود شده است؛ برای شروع چت آفلاین، آن را بارگذاری کن."
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    val prefsDownload = context.getSharedPreferences("ajami_downloads", 0)
                    if (id == prefsDownload.getLong("model_download_id", -2L)) {
                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val query = DownloadManager.Query().setFilterById(id)
                        manager.query(query).use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                                if (status == DownloadManager.STATUS_SUCCESSFUL && modelFile.exists()) {
                                    modelStatus = "مدل با موفقیت دانلود شد؛ دکمه بارگذاری را بزن تا چت آفلاین آماده شود."
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
    val gold = Color(0xFFFFC857)

    MaterialTheme(colorScheme = darkColorScheme(primary = gold, background = Color(0xFF071A35), surface = Color(0xFF10294A))) {
        Scaffold(
            containerColor = Color(0xFF071A35),
            topBar = {
                TopAppBar(
                    title = { Text("AJAMI-AI", color = gold) },
                    actions = { TextButton(onClick = { studioOpen = !studioOpen }) { Text(if (studioOpen) "چت" else "استودیو") }; TextButton(onClick = { showSettings = true }) { Text("تنظیمات") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B2140))
                )
            },
            bottomBar = {
                Row(
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
                            if (offlineMode) {
                                val loaded = offlineModel
                                if (loaded == null) {
                                    messages.add(ChatMessage("assistant", "ابتدا مدل را دانلود و بارگذاری کن."))
                                } else {
                                    busy = true
                                    val history = messages.toList()
                                    scope.launch {
                                        val answer = withContext(Dispatchers.Default) {
                                            runCatching {
                                                val transcript = history.takeLast(12).joinToString("\\n") { m ->
                                                    (if (m.role == "user") "کاربر" else "دستیار") + ": " + m.text
                                                }
                                                Llama.complete(loaded, prompt = transcript, systemPrompt = "تو دستیار هوش مصنوعی فارسی AJAMI-AI هستی. پاسخ‌ها را دقیق، مفید و به زبان فارسی بده.", maxTokens = 256).text
                                            }.getOrElse { "خطا در پاسخ آفلاین: ${it.localizedMessage ?: "خطای ناشناخته"}" }
                                        }
                                        messages.add(ChatMessage("assistant", answer))
                                        busy = false
                                    }
                                }
                            } else if (apiKey.isBlank()) {
                                messages.add(ChatMessage("assistant", "برای پاسخ آنلاین، ابتدا کلید API را در تنظیمات وارد کن."))
                            } else {
                                busy = true
                                val history = messages.toList()
                                scope.launch {
                                    val answer = withContext(Dispatchers.IO) {
                                        requestChat(endpoint, apiKey, model, history)
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
                if (studioOpen) {
                    PosterStudio(
                        title = posterTitle,
                        onTitleChange = { posterTitle = it },
                        subtitle = posterSubtitle,
                        onSubtitleChange = { posterSubtitle = it },
                        onExport = { exportPoster(context, posterTitle, posterSubtitle) }
                    )
                }
                Text("دستیار فارسی • نسخه 1.0.0", color = gold, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مدل هوش مصنوعی آفلاین", color = gold, style = MaterialTheme.typography.titleMedium)
                        Text("Qwen 2.5 • 1.5B • Q4_K_M • حدود ۱٫۱۲ گیگابایت", color = Color.White)
                        Text(modelStatus, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        if (modelFile.exists() && offlineModel == null) {
                            Button(enabled = !offlineLoading, onClick = {
                                offlineLoading = true
                                modelStatus = "در حال بارگذاری مدل در حافظه گوشی..."
                                scope.launch {
                                    try {
                                        if (!modelFile.exists() || modelFile.length() < 900_000_000L) {
                                            error("فایل مدل ناقص است یا دانلود نشده؛ مدل را دوباره دانلود کن.")
                                        }
                                        val magic = modelFile.inputStream().use { stream ->
                                            ByteArray(4).also { if (stream.read(it) != 4) error("خواندن فایل مدل ممکن نیست.") }
                                        }
                                        if (!magic.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))) {
                                            error("فایل دانلودشده مدل GGUF معتبر نیست؛ آن را حذف و دوباره دانلود کن.")
                                        }
                                        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                        val memoryInfo = ActivityManager.MemoryInfo()
                                        memory.getMemoryInfo(memoryInfo)
                                        if (memoryInfo.lowMemory || memoryInfo.availMem < 1_200_000_000L) {
                                            error("حافظه آزاد گوشی برای بارگذاری مدل کافی نیست. برنامه‌های دیگر را ببند و دوباره تلاش کن.")
                                        }
                                        val loaded = withContext(Dispatchers.Default) {
                                            Llama.loadModel(
                                                modelFile.absolutePath,
                                                LlamaConfig(contextSize = 1024, threads = 2, gpuLayers = 0)
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
                            enabled = !modelDownloading && !modelFile.exists(),
                            onClick = {
                                try {
                                    val request = DownloadManager.Request(
                                        Uri.parse("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true")
                                    )
                                        .setTitle("دانلود مدل آفلاین AJAMI-AI")
                                        .setDescription("مدل زبانی Qwen 2.5 • حدود ۱٫۱۲ گیگابایت")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setAllowedOverMetered(true)
                                        .setAllowedOverRoaming(false)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, modelFileName)
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val id = manager.enqueue(request)
                                    context.getSharedPreferences("ajami_downloads", 0).edit()
                                        .putLong("model_download_id", id).apply()
                                    modelDownloading = true
                                    modelStatus = "دانلود آغاز شد؛ می‌توانی از اعلان‌های گوشی پیشرفت آن را ببینی."
                                } catch (e: Exception) {
                                    modelStatus = "شروع دانلود ممکن نشد: ${e.localizedMessage ?: "خطای ناشناخته"}"
                                }
                            }
                        ) { Text(if (modelDownloading) "در حال دانلود..." else if (modelFile.exists()) "مدل موجود است" else "دانلود مدل آفلاین") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
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

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("تنظیمات اتصال آنلاین") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("API Base URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("نام مدل") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true
                    )
                    Text(
                        "کلید API روی همین دستگاه ذخیره می‌شود؛ آن را با کسی به اشتراک نگذار.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit()
                            .putString("endpoint", endpoint.trim())
                            .putString("model", model.trim())
                            .putString("api_key", apiKey.trim())
                            .apply()
                        showSettings = false
                    }
                ) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("بستن") }
            }
        )
    }
}

private fun requestChat(
    base: String,
    key: String,
    model: String,
    history: List<ChatMessage>
): String {
    return try {
        val url = URL(base.trim().trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }

        val jsonMessages = JSONArray()
        history.takeLast(30).forEach { message ->
            jsonMessages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.text)
            )
        }

        val requestBody = JSONObject()
            .put("model", model)
            .put("messages", jsonMessages)
            .put("temperature", 0.7)

        connection.outputStream.use { output ->
            output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }
        connection.disconnect()

        if (statusCode !in 200..299) {
            "خطای سرویس ($statusCode): " +
                runCatching { JSONObject(responseText).optString("error", responseText) }
                    .getOrDefault(responseText).take(500)
        } else {
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    } catch (exception: Exception) {
        "اتصال ناموفق بود: " +
            (exception.localizedMessage ?: "خطای ناشناخته") +
            ". آدرس API و اینترنت را بررسی کن."
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
