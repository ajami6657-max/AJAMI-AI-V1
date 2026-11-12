package com.ajami.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaModel
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OfflineFilmMaker(languageModel: LlamaModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelFile = remember { File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "v1-5-pruned_Q4_0.gguf") }
    var prompt by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("1") }
    var status by remember { mutableStateOf("برای ساخت خودکار، مدل چت Qwen و مدل تصویرساز را یک‌بار دانلود و بارگذاری کن. پس از آن پردازش روی گوشی انجام می‌شود.") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("فیلم‌ساز خودکار آفلاین", style = MaterialTheme.typography.titleMedium)
            Text("موضوع را بنویس؛ Qwen فیلمنامه و ۶ صحنه را می‌سازد، تصویرساز محلی برای هر صحنه تصویر تولید می‌کند و در پایان یک فیلم MP4 با حرکت نرم و زوم آهسته ساخته می‌شود. حداکثر ۵ دقیقه.")
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("موضوع یا داستان فیلم") },
                minLines = 3
            )
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit).take(1) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("مدت فیلم (۱ تا ۵ دقیقه)") },
                singleLine = true
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    val model = languageModel
                    if (model == null) {
                        status = "ابتدا به صفحه چت برو و مدل Qwen آفلاین را بارگذاری کن."
                    } else if (!modelFile.exists() || modelFile.length() < 2_800_000_000L) {
                        status = "ابتدا مدل تصویرساز Stable Diffusion را در بخش تصویرساز دانلود کن."
                    } else {
                        val filmMinutes = (minutes.toIntOrNull() ?: 1).coerceIn(1, 5)
                        val topic = prompt.trim()
                        busy = true
                        result = null
                        status = "مرحله ۱ از ۳: در حال نوشتن فیلمنامه و طراحی صحنه‌ها..."
                        scope.launch {
                            try {
                                val images = withContext(Dispatchers.Default) {
                                    val script = Llama.complete(
                                        model,
                                        prompt = "Create exactly 6 distinct cinematic scene descriptions for this film idea: $topic. Return exactly 6 lines, one per scene. Each line must be a detailed ENGLISH visual image prompt, 15 to 30 words, describing visible subjects, setting, lighting and camera framing. Keep the same characters visually consistent. No numbering, no headings, no commentary.",
                                        systemPrompt = "You are a concise professional film storyboard artist. Output only six English image prompts, one per line. Never include dialogue or explanations.",
                                        maxTokens = 420
                                    ).text
                                    val scenes = script.lines()
                                        .map { it.trim().replace(Regex("^(Scene\\s*)?\\d+[.): -]+", RegexOption.IGNORE_CASE), "").trim() }
                                        .filter { it.length > 15 }
                                        .take(6)
                                    if (scenes.size < 4) error("مدل داستان نتوانست صحنه‌های کافی بسازد. دوباره تلاش کن یا موضوع را ساده‌تر بنویس.")
                                    val engine = File(context.applicationInfo.nativeLibraryDir, "libsd.so")
                                    if (!engine.exists()) error("موتور تصویرسازی libsd.so در این APK پیدا نشد.")
                                    engine.setExecutable(true)
                                    val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AJAMI-Film-" + System.currentTimeMillis()).apply { mkdirs() }
                                    val generated = mutableListOf<File>()
                                    scenes.forEachIndexed { index, scene ->
                                        withContext(Dispatchers.Main) {
                                            status = "مرحله ۲ از ۳: ساخت تصویر صحنه ${index + 1} از ${scenes.size}..."
                                        }
                                        val image = File(outputDir, "scene_${index + 1}.png")
                                        val process = ProcessBuilder(
                                            engine.absolutePath,
                                            "-m", modelFile.absolutePath,
                                            "-p", scene,
                                            "-o", image.absolutePath,
                                            "-W", "512", "-H", "512",
                                            "--steps", "15",
                                            "--cfg-scale", "7.0",
                                            "--backend", "cpu",
                                            "--flash-attn", "on"
                                        ).redirectErrorStream(true).start()
                                        val log = StringBuilder()
                                        process.inputStream.bufferedReader().useLines { lines ->
                                            lines.forEach { line ->
                                                if (log.length > 3000) log.delete(0, 1500)
                                                log.append(line).append('\n')
                                            }
                                        }
                                        val exit = process.waitFor()
                                        if (exit != 0 || !image.exists() || image.length() == 0L) {
                                            error("ساخت تصویر صحنه ${index + 1} ناموفق بود: " + log.takeLast(400))
                                        }
                                        if (BitmapFactory.decodeFile(image.absolutePath) == null) error("تصویر صحنه ${index + 1} معتبر نیست.")
                                        generated.add(image)
                                    }
                                    generated
                                }
                                status = "مرحله ۳ از ۳: در حال تدوین فیلم و افزودن حرکت و زوم..."
                                val output = File(context.cacheDir, "AJAMI_AI_Film_${System.currentTimeMillis()}.mp4")
                                val seconds = filmMinutes * 60 / images.size
                                val width = 720
                                val height = 1280
                                val filters = images.indices.map { i ->
                                    "[${i}:v]scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,zoompan=z='min(zoom+0.0008,1.12)':d=1:s=${width}x${height}:fps=25,trim=duration=$seconds,setpts=PTS-STARTPTS,format=yuv420p[v$i]"
                                }
                                val joined = images.indices.joinToString("") { "[v$it]" }
                                val graph = (filters + (joined + "concat=n=${images.size}:v=1:a=0[outv]")).joinToString(";")
                                val command = buildString {
                                    append("-y ")
                                    images.forEach { append("-loop 1 -t $seconds -i '${it.absolutePath}' ") }
                                    append("-filter_complex \"$graph\" -map '[outv]' -an -c:v mpeg4 -q:v 5 -r 25 -pix_fmt yuv420p -movflags +faststart '${output.absolutePath}'")
                                }
                                FFmpegKit.executeAsync(command) { session ->
                                    scope.launch {
                                        if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L) {
                                            result = output
                                            status = "فیلم ساخته شد؛ مدت تقریبی ${filmMinutes} دقیقه. برای ذخیره در گالری دکمه زیر را بزن."
                                        } else {
                                            status = "تدوین فیلم ناموفق بود: " + (session.failStackTrace ?: "خطای FFmpeg")
                                        }
                                        busy = false
                                    }
                                }
                            } catch (e: Throwable) {
                                status = "ساخت فیلم انجام نشد: " + (e.localizedMessage ?: e.javaClass.simpleName)
                                busy = false
                            }
                        }
                    }
                }
            ) { Text(if (busy) "در حال ساخت فیلم..." else "ساخت فیلم خودکار آفلاین") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
            result?.let { file ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { saveFilmToGallery(context, file) }
                ) { Text("ذخیره فیلم در گالری") }
            }
            Text("نکته: خروجی این نسخه فیلمی از تصاویر تولیدشده با حرکت و زوم دوربین است، نه ویدئوی مولد با حرکت طبیعی پیوسته. تولید چند تصویر روی گوشی ممکن است زمان‌بر و سنگین باشد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun saveFilmToGallery(context: Context, source: File) {
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "AJAMI_AI_Film_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AJAMI-AI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("ساخت فایل در گالری ناموفق بود.")
            context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("نوشتن فایل ناموفق بود.")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AJAMI-AI").apply { mkdirs() }
            source.copyTo(File(dir, source.name), overwrite = true)
        }
        Toast.makeText(context, "فیلم در پوشه Movies/AJAMI-AI ذخیره شد.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "ذخیره فیلم ناموفق بود: ${e.localizedMessage ?: "خطا"}", Toast.LENGTH_LONG).show()
    }
}
