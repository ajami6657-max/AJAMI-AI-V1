package com.ajami.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val messages = remember { mutableStateListOf(ChatMessage("assistant", "سلام! برای گفت‌وگوی آنلاین، اتصال API را در تنظیمات وارد کن.")) }
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
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("پیامت را بنویس...") }, maxLines = 4)
                    Button(enabled = !busy && input.isNotBlank(), onClick = {
                        val prompt = input.trim()
                        input = ""
                        messages.add(ChatMessage("user", prompt))
                        if (apiKey.isBlank()) {
                            messages.add(ChatMessage("assistant", "برای پاسخ آنلاین، ابتدا کلید API را در تنظیمات وارد کن. موتور آفلاین هنوز به مدل محلی نیاز دارد."))
                        } else {
                            busy = true
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) { requestChat(endpoint, apiKey, model, messages.toList()) }
                                messages.add(ChatMessage("assistant", answer))
                                busy = false
                            }
                        }
                    }) { Text("ارسال") }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                Text("دستیار فارسی • نسخه 1.0.0", color = gold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = if (msg.role == "user") Color(0xFF173B63) else Color(0xFF10294A))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (msg.role == "user") "شما" else "AJAMI-AI", color = gold, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(msg.text, color = Color.White)
                            }
                        }
                    }
                    if (busy) item { CircularProgressIndicator(color = gold) }
                }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("تنظیمات اتصال آنلاین") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API Base URL") }, singleLine = true)
                    OutlinedTextField(model, { model = it }, label = { Text("نام مدل") }, singleLine = true)
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true)
                    Text("کلید API روی همین دستگاه ذخیره می‌شود؛ آن را با کسی به اشتراک نگذار.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("api_key", apiKey.trim()).apply()
                    showSettings = false
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("بستن") } }
        )
    }
}

private fun requestChat(base: String, key: String, model: String, history: List<ChatMessage>): String {
    return try {
        val url = URL(base.trim().trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        val msgs = JSONArray()
        history.takeLast(30).forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.7)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            "خطای سرویس ($responseCode): " + runCatching { JSONObject(response).optString("error", response) }.getOrDefault(response).take(500)
        } else {
            JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    } catch (e: Exception) {
        "اتصال ناموفق بود: " + (e.localizedMessage ?: "خطای ناشناخته") + ". آدرس API و اینترنت را بررسی کن."
    }
}
