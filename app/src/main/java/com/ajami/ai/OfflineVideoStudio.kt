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
    var status by remember { mutableStateOf("یک ویدئو از حافظه گوشی انتخاب کن. پردازش و تدوین روی خود گوشی انجام می‌شود.") }
    var busy by remember { mutableStateOf(false) }
    var trimSeconds by remember { mutableStateOf("15") }
    var lastOutput by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "در حال آماده‌سازی فایل ویدئو..."
            scope.launch {
                try {
                    val copied = withContext(Dispatchers.IO) {
                        val file = File(context.cacheDir, "ajami_input_" + System.currentTimeMillis() + ".video")
                        context.contentResolver.openInputStream(uri)?.use { source ->
                            file.outputStream().use { target -> source.copyTo(target) }
                        } ?: error("خواندن فایل انتخاب‌شده ممکن نیست.")
                        file
                    }
                    inputFile = copied
                    lastOutput = null
                    status = "ویدئو آماده است: " + copied.name
                } catch (e: Exception) {
                    status = "بازکردن ویدئو ناموفق بود: " + (e.localizedMessage ?: "خطا")
                } finally {
                    busy = false
                }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تدوین‌گر ویدئوی آفلاین", style = MaterialTheme.typography.titleLarge)
        Text("انتخاب فایل، فشرده‌سازی، کوتاه‌کردن ویدئو و استخراج صدا با FFmpeg؛ بدون آپلود یا API.")
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { picker.launch(arrayOf("video/*")) }
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
            "این بخش تدوین آفلاین واقعی انجام می‌دهد. تولید ویدئوی مولد از متن با مدل‌های سنگین، قابلیت جداگانه‌ای است و روی Galaxy A53 محدودیت سخت‌افزاری جدی دارد.",
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
