package com.ajami.ai

import android.os.Bundle
import android.app.DownloadManager
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
            modelStatus = "مدل دانلود شده است؛ موتور اجرای آفلاین هنوز در حال توسعه است."
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
                                    modelStatus = "مدل با موفقیت دانلود شد؛ برای پاسخ آفلاین هنوز باید موتور اجرا متصل شود."
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
        mutableStateListOf(ChatMessage("assistant", "سلام! برای گفت‌وگوی آنلاین، اتصال API را از تنظیمات وارد کن."))
    }
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFFFC857)

    MaterialTheme(colorScheme = darkColorScheme(primary = gold, background = Color(0xFF071A35), surface = Color(0xFF10294A))) {
        Scaffold(
            containerColor = Color(0xFF071A35),
            topBar = {
                TopAppBar(
                    title = { Text("AJAMI-AI", color = gold) },
                    actions = { TextButton(onClick = { showSettings = true }) { Text("تنظیمات") } },
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
                                        val loaded = withContext(Dispatchers.Default) {
                                            Llama.loadModel(modelFile.absolutePath, LlamaConfig(contextSize = 2048, threads = 4, gpuLayers = 0))
                                        }
                                        offlineModel = loaded
                                        modelStatus = "مدل بارگذاری شد؛ گفت‌وگوی آفلاین آماده استفاده است."
                                    } catch (e: Exception) {
                                        modelStatus = "بارگذاری مدل ناموفق بود: ${e.localizedMessage ?: "خطای ناشناخته"}"
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
