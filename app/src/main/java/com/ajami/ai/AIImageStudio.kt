package com.ajami.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AIImageStudio(languageModel: LlamaModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelName = "v1-5-pruned_Q4_0.gguf"
    val modelFile = remember { File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), modelName) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("مدل محلی تصویرساز حدود ۳٫۱ گیگابایت است. پس از دریافت، تولید تصویر بدون اینترنت انجام می‌شود.") }
    var downloading by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val gold = Color(0xFFFFC857)

    LaunchedEffect(Unit) {
        if (modelFile.exists() && modelFile.length() > 2_800_000_000L) status = "مدل تصویرساز روی گوشی موجود است."
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val ok = index >= 0 && cursor.getInt(index) == DownloadManager.STATUS_SUCCESSFUL
                        status = if (ok && modelFile.exists() && modelFile.length() > 2_800_000_000L) {
                            "مدل دانلود شد؛ اکنون می‌توانی آفلاین تصویر بسازی."
                        } else {
                            "دانلود ناموفق یا ناقص بود. فضای خالی و اینترنت را بررسی کن."
                        }
                    }
                }
                downloading = false
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تصویرساز هوش مصنوعی آفلاین", color = gold, style = MaterialTheme.typography.titleMedium)
                Text("Stable Diffusion 1.5 • مدل Q4_0 • خروجی اولیه 512×512", color = Color.White)
                Text("مدل حدود ۳٫۱ گیگابایت فضا می‌خواهد. پس از دانلود، هیچ API یا سروری استفاده نمی‌شود. برای درک بهتر توصیف‌ها، مدل چت آفلاین Qwen را نیز بارگذاری کن.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall)

                if (!modelFile.exists() || modelFile.length() < 2_800_000_000L) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !downloading,
                        onClick = {
                            try {
                                val request = DownloadManager.Request(
                                    Uri.parse("https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q4_0.gguf?download=true")
                                )
                                    .setTitle("مدل تصویرساز آفلاین AJAMI-AI")
                                    .setDescription("Stable Diffusion 1.5 Q4_0 • حدود ۳٫۱ گیگابایت")
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setAllowedOverMetered(true)
                                    .setAllowedOverRoaming(false)
                                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, modelName)
                                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                downloadId = manager.enqueue(request)
                                downloading = true
                                status = "دانلود شروع شد؛ پیشرفت را از اعلان‌های گوشی دنبال کن."
                            } catch (e: Exception) {
                                status = "شروع دانلود ناموفق بود: " + (e.localizedMessage ?: "خطای نامشخص")
                            }
                        }
                    ) { Text(if (downloading) "در حال دانلود مدل..." else "دانلود مدل تصویرساز") }
                } else {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("توصیف تصویر را فارسی یا انگلیسی بنویس") },
                        minLines = 3
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !generating && prompt.isNotBlank(),
                        onClick = {
                            generating = true
                            resultBitmap = null
                            status = "در حال اجرای مدل روی پردازنده گوشی..."
                            scope.launch {
                                try {
                                    val bitmap = withContext(Dispatchers.IO) {
                                        val engine = File(context.applicationInfo.nativeLibraryDir, "libsd.so")
                                        if (!engine.exists()) error("موتور تصویرسازی در این APK موجود نیست.")
                                        if (!modelFile.exists() || modelFile.length() < 2_800_000_000L) error("فایل مدل کامل نیست.")
                                        engine.setExecutable(true)
                                        val output = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ajami_image_" + System.currentTimeMillis() + ".png")
                                        val rawPrompt = prompt.trim()
                                        val hasPersian = Regex("[\\u0600-\\u06FF]").containsMatchIn(rawPrompt)
                                        val imagePrompt = if (hasPersian) {
                                            val translator = languageModel ?: error("برای تبدیل توصیف فارسی به انگلیسی، ابتدا مدل چت آفلاین Qwen را بارگذاری کن؛ یا توصیف را انگلیسی بنویس.")
                                            Llama.complete(
                                                translator,
                                                prompt = rawPrompt,
                                                systemPrompt = "Translate the user's image description into a detailed natural English text-to-image prompt. Return only the translated prompt, no explanations.",
                                                maxTokens = 160
                                            ).text.trim()
                                        } else rawPrompt
                                        val process = ProcessBuilder(
                                            engine.absolutePath,
                                            "-m", modelFile.absolutePath,
                                            "-p", imagePrompt,
                                            "-o", output.absolutePath,
                                            "-W", "512", "-H", "512",
                                            "--steps", "15",
                                            "--cfg-scale", "7.0",
                                            "--backend", "cpu",
                                            "--flash-attn", "on"
                                        ).redirectErrorStream(true).start()
                                        val log = StringBuilder()
                                        process.inputStream.bufferedReader().useLines { lines ->
                                            lines.forEach { line ->
                                                if (log.length > 5000) log.delete(0, 2500)
                                                log.append(line).append('\n')
                                            }
                                        }
                                        val exitCode = process.waitFor()
                                        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
                                            error("موتور تصویرسازی خطا داد: " + log.takeLast(700))
                                        }
                                        BitmapFactory.decodeFile(output.absolutePath) ?: error("خواندن تصویر خروجی ناموفق بود.")
                                    }
                                    resultBitmap = bitmap
                                    status = "تصویر روی گوشی تولید شد و در پوشه تصاویر برنامه ذخیره شده است."
                                } catch (e: Throwable) {
                                    status = "ساخت تصویر انجام نشد: " + (e.localizedMessage ?: e.javaClass.simpleName)
                                } finally {
                                    generating = false
                                }
                            }
                        }
                    ) { Text(if (generating) "در حال ساخت تصویر..." else "تولید تصویر آفلاین") }
                }

                if (generating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("این کار ممکن است چند دقیقه طول بکشد. گوشی را خنک نگه دار و برنامه را باز بگذار.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                resultBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "تصویر تولیدشده",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
