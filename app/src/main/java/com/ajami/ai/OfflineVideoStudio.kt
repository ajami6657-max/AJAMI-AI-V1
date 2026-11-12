package com.ajami.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OfflineVideoStudio() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputFile by remember { mutableStateOf<File?>(null) }
    var photoFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("برای ساخت فیلم، چند عکس انتخاب کن. همه پردازش‌ها روی گوشی انجام می‌شوند.") }
    var busy by remember { mutableStateOf(false) }
    var trimSeconds by remember { mutableStateOf("15") }
    var lastOutput by remember { mutableStateOf<File?>(null) }
    var aspect by remember { mutableStateOf("عمودی ۹:۱۶") }
    var secondsPerPhoto by remember { mutableStateOf("3") }

    suspend fun copyUri(uri: Uri, prefix: String, fallbackExt: String): File = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") -> "jpg"
            mime.contains("mp3") -> "mp3"
            mime.contains("wav") -> "wav"
            mime.contains("ogg") -> "ogg"
            mime.contains("m4a") || mime.contains("mp4") -> "m4a"
            else -> fallbackExt
        }
        val file = File(context.cacheDir, prefix + "_" + System.currentTimeMillis() + "_" + (0..99999).random() + "." + ext)
        context.contentResolver.openInputStream(uri)?.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        } ?: error("خواندن فایل انتخاب‌شده ممکن نیست.")
        file
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    inputFile = copyUri(uri, "ajami_video", "mp4")
                    lastOutput = null
                    status = "ویدئو آماده است."
                } catch (e: Exception) {
                    status = "بازکردن ویدئو ناموفق بود: " + (e.localizedMessage ?: "خطا")
                } finally { busy = false }
            }
        }
    }

    val photosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            busy = true
            status = "در حال آماده‌سازی عکس‌ها..."
            scope.launch {
                try {
                    val copied = uris.take(30).map { copyUri(it, "ajami_photo", "jpg") }
                    photoFiles = copied
                    lastOutput = null
                    status = copied.size.toString() + " عکس آمادهٔ ساخت فیلم است."
                } catch (e: Exception) {
                    status = "آماده‌سازی عکس‌ها ناموفق بود: " + (e.localizedMessage ?: "خطا")
                } finally { busy = false }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    audioFile = copyUri(uri, "ajami_music", "m4a")
                    status = "موسیقی پس‌زمینه انتخاب شد."
                } catch (e: Exception) {
                    status = "بازکردن صدا ناموفق بود: " + (e.localizedMessage ?: "خطا")
                } finally { busy = false }
            }
        }
    }

    fun runEdit(label: String, extension: String, commandBuilder: (File, File) -> String) {
        val source = inputFile ?: return
        busy = true
        status = "در حال " + label + " روی گوشی؛ برنامه را باز نگه دار..."
        val destination = File(context.cacheDir, "ajami_" + System.currentTimeMillis() + "." + extension)
        val command = commandBuilder(source, destination)
        FFmpegKit.executeAsync(command) { session ->
            scope.launch {
                if (ReturnCode.isSuccess(session.returnCode) && destination.exists() && destination.length() > 0L) {
                    lastOutput = destination
                    status = label + " با موفقیت انجام شد. برای انتقال خروجی به گالری، دکمه ذخیره را بزن."
                } else {
                    status = label + " انجام نشد. فرمت ورودی ممکن است پشتیبانی نشود. " + (session.failStackTrace ?: "")
                }
                busy = false
            }
        }
    }

    fun createSlideshow() {
        if (photoFiles.isEmpty()) return
        val duration = secondsPerPhoto.toIntOrNull()?.coerceIn(1, 20) ?: 3
        val vertical = aspect == "عمودی ۹:۱۶"
        val width = if (vertical) 720 else 1280
        val height = if (vertical) 1280 else 720
        val destination = File(context.cacheDir, "ajami_film_" + System.currentTimeMillis() + ".mp4")
        val filterParts = photoFiles.indices.map { i ->
            "[$i:v]scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=25,format=yuv420p[v$i]"
        }
        val joined = photoFiles.indices.joinToString("") { "[v$it]" }
        val filter = (filterParts + "$joined" + "concat=n=${photoFiles.size}:v=1:a=0[outv]").joinToString(";")
        val command = buildString {
            append("-y ")
            photoFiles.forEach { append("-loop 1 -t $duration -i '${it.absolutePath}' ") }
            audioFile?.let { append("-stream_loop -1 -i '${it.absolutePath}' ") }
            append("-filter_complex \"$filter\" -map '[outv]' ")
            if (audioFile != null) {
                append("-map ${photoFiles.size}:a:0 -c:a aac -b:a 128k -shortest ")
            } else {
                append("-an ")
            }
            append("-c:v mpeg4 -q:v 5 -r 25 -pix_fmt yuv420p -movflags +faststart '${destination.absolutePath}'")
        }
        busy = true
        lastOutput = null
        status = "در حال ساخت فیلم از ${photoFiles.size} عکس؛ ممکن است چند دقیقه طول بکشد..."
        FFmpegKit.executeAsync(command) { session ->
            scope.launch {
                if (ReturnCode.isSuccess(session.returnCode) && destination.exists() && destination.length() > 0L) {
                    lastOutput = destination
                    status = "فیلم از عکس‌ها ساخته شد. اکنون می‌توانی آن را در گالری ذخیره کنی."
                } else {
                    status = "ساخت فیلم ناموفق بود. " + (session.failStackTrace ?: "فرمت یکی از فایل‌ها پشتیبانی نشد.")
                }
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("استودیوی ساخت فیلم آفلاین", style = MaterialTheme.typography.titleLarge)
        Text("ساخت کلیپ از عکس‌ها همراه موسیقی، به‌علاوه ابزارهای تدوین ویدئو؛ بدون اینترنت و API.")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("۱. ساخت فیلم از عکس‌ها", style = MaterialTheme.typography.titleMedium)
                Text("چند عکس انتخاب کن تا به ترتیب به یک فیلم MP4 تبدیل شوند. حداکثر ۳۰ عکس.")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { photosPicker.launch(arrayOf("image/*")) }
                ) { Text(if (photoFiles.isEmpty()) "انتخاب چند عکس" else "تغییر عکس‌ها (${photoFiles.size})") }
                if (photoFiles.isNotEmpty()) Text("${photoFiles.size} عکس انتخاب شده است.")
                Text("قالب فیلم")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = aspect == "عمودی ۹:۱۶", onClick = { aspect = "عمودی ۹:۱۶" }, label = { Text("عمودی ۹:۱۶") })
                    FilterChip(selected = aspect == "افقی ۱۶:۹", onClick = { aspect = "افقی ۱۶:۹" }, label = { Text("افقی ۱۶:۹") })
                }
                OutlinedTextField(
                    value = secondsPerPhoto,
                    onValueChange = { secondsPerPhoto = it.filter(Char::isDigit).take(2) },
                    label = { Text("مدت نمایش هر عکس (ثانیه)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { audioPicker.launch(arrayOf("audio/*")) }
                ) { Text(if (audioFile == null) "انتخاب موسیقی پس‌زمینه (اختیاری)" else "تغییر موسیقی پس‌زمینه") }
                audioFile?.let { Text("موسیقی انتخاب‌شده: ${it.name}") }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = photoFiles.isNotEmpty() && !busy,
                    onClick = { createSlideshow() }
                ) { Text("ساخت فیلم آفلاین از عکس‌ها") }
            }
        }

        Divider()
        Text("۲. تدوین ویدئوی موجود", style = MaterialTheme.typography.titleMedium)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { videoPicker.launch(arrayOf("video/*")) }
        ) { Text(if (inputFile == null) "انتخاب ویدئو از گوشی" else "انتخاب ویدئوی دیگر") }
        inputFile?.let { Text("فایل انتخاب‌شده: " + it.name) }
        OutlinedTextField(
            value = trimSeconds,
            onValueChange = { trimSeconds = it.filter(Char::isDigit).take(4) },
            label = { Text("مدت خروجی بر حسب ثانیه") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = inputFile != null && !busy,
            onClick = {
                runEdit("کوتاه‌کردن ویدئو", "mp4") { src, dst ->
                    val seconds = trimSeconds.toIntOrNull()?.coerceIn(1, 3600) ?: 15
                    "-y -i '${src.absolutePath}' -t $seconds -c:v mpeg4 -q:v 5 -c:a aac '${dst.absolutePath}'"
                }
            }
        ) { Text("ساخت کلیپ کوتاه") }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = inputFile != null && !busy,
            onClick = {
                runEdit("فشرده‌سازی ویدئو", "mp4") { src, dst ->
                    "-y -i '${src.absolutePath}' -vf scale='min(1280,iw)':-2 -c:v mpeg4 -q:v 7 -c:a aac -b:a 96k '${dst.absolutePath}'"
                }
            }
        ) { Text("کاهش حجم ویدئو") }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = inputFile != null && !busy,
            onClick = {
                runEdit("استخراج صدا", "m4a") { src, dst ->
                    "-y -i '${src.absolutePath}' -vn -c:a aac -b:a 128k '${dst.absolutePath}'"
                }
            }
        ) { Text("استخراج صدا از ویدئو") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodyMedium)
        lastOutput?.let { output ->
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { saveMediaOutput(context, output) }
            ) { Text("ذخیره خروجی در گالری / پوشه فیلم‌ها") }
        }
        Text(
            "این نسخه فیلم‌سازی آفلاین از عکس‌ها و موسیقی و تدوین فایل ویدئویی را انجام می‌دهد. تولید مولد ویدئو از یک جمله با هوش مصنوعی، قابلیت متفاوت و سنگین‌تری است و هنوز در این نسخه فعال نیست.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private fun saveMediaOutput(context: Context, source: File) {
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val isVideo = source.extension.lowercase() == "mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "AJAMI_AI_" + System.currentTimeMillis() + "." + source.extension)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) Environment.DIRECTORY_MOVIES + "/AJAMI-AI" else Environment.DIRECTORY_MUSIC + "/AJAMI-AI")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: error("ساخت فایل در حافظه ناموفق بود.")
            context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("نوشتن خروجی ناموفق بود.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val directory = File(context.getExternalFilesDir(if (source.extension == "mp4") Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC), "AJAMI-AI").apply { mkdirs() }
            source.copyTo(File(directory, source.name), overwrite = true)
        }
        Toast.makeText(context, "فایل در حافظه گوشی ذخیره شد.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "ذخیره ناموفق بود: " + (e.localizedMessage ?: "خطا"), Toast.LENGTH_LONG).show()
    }
}
