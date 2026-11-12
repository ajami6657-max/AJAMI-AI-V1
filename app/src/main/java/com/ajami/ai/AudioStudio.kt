package com.ajami.ai

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import java.util.Locale

@Composable
fun OfflineAudioStudio() {
    val context = LocalContext.current
    var sourceText by remember { mutableStateOf("سلام! به استودیوی صدای آفلاین اجامی خوش آمدید.") }
    var initState by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var status by remember { mutableStateOf("در حال آماده‌سازی موتور گفتار گوشی...") }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { result ->
            initState = result
            status = if (result == TextToSpeech.SUCCESS) {
                "موتور گفتار آماده است؛ در حال بررسی صدای فارسی آفلاین..."
            } else {
                "موتور تبدیل متن به گفتار روی گوشی راه‌اندازی نشد."
            }
        }
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    busy = false
                    status = "فایل صوتی ساخته شد و در حافظه برنامه قرار گرفت."
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    busy = false
                    status = "ساخت فایل صوتی ناموفق بود؛ موتور گفتار یا صدای انتخابی را بررسی کن."
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    busy = false
                    status = "خطای موتور گفتار (کد $errorCode)."
                }
            }
        })
        onDispose {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    LaunchedEffect(initState) {
        if (initState == TextToSpeech.SUCCESS) {
            val faVoice = tts.voices?.firstOrNull {
                it.locale.language.equals("fa", ignoreCase = true) &&
                    !it.isNetworkConnectionRequired
            }
            if (faVoice != null) {
                tts.voice = faVoice
                status = "صدای فارسی آفلاین آماده است: ${faVoice.name}"
            } else {
                status = "صدای فارسی آفلاین روی گوشی پیدا نشد. از تنظیمات موتور گفتار اندروید، داده صوتی فارسی را نصب کن؛ این بخش به اینترنت هنگام تولید وابسته نیست."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("استودیوی صدای آفلاین", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("تبدیل متن به گفتار با موتور نصب‌شده روی گوشی؛ فقط صدایی استفاده می‌شود که نیاز به اتصال شبکه نداشته باشد.")
        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it },
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            label = { Text("متن فارسی برای تبدیل به صدا") },
            minLines = 4,
            maxLines = 10
        )
        Text(status, style = MaterialTheme.typography.bodySmall)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = initState == TextToSpeech.SUCCESS && sourceText.isNotBlank() && !busy &&
                tts.voices?.any { it.locale.language.equals("fa", true) && !it.isNetworkConnectionRequired } == true,
            onClick = {
                val voice = tts.voices?.firstOrNull {
                    it.locale.language.equals("fa", true) && !it.isNetworkConnectionRequired
                }
                if (voice == null) {
                    status = "صدای فارسی آفلاین موجود نیست؛ ابتدا بسته صدای فارسی را در تنظیمات گوشی نصب کن."
                } else {
                    tts.voice = voice
                    val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AJAMI-AI")
                    folder.mkdirs()
                    val file = File(folder, "ajami_voice_${System.currentTimeMillis()}.wav")
                    outputFile = file
                    busy = true
                    status = "در حال تولید گفتار فارسی..."
                    val result = tts.synthesizeToFile(sourceText.trim(), Bundle(), file, "ajami_voice")
                    if (result != TextToSpeech.SUCCESS) {
                        busy = false
                        status = "موتور گفتار نتوانست تولید صدا را آغاز کند (کد $result)."
                    }
                }
            }
        ) { Text(if (busy) "در حال تولید..." else "تولید صدای فارسی") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = outputFile?.exists() == true && !busy,
                onClick = {
                    outputFile?.let { file ->
                        runCatching {
                            MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { it.release() }
                                setOnErrorListener { player, _, _ -> player.release(); true }
                                prepare()
                                start()
                            }
                            status = "در حال پخش آخرین فایل صوتی..."
                        }.onFailure {
                            status = "پخش فایل ممکن نشد: ${it.localizedMessage ?: "خطای ناشناخته"}"
                        }
                    }
                }
            ) { Text("پخش صدا") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = outputFile?.exists() == true && !busy,
                onClick = {
                    outputFile?.let { file ->
                        val saved = saveAudioToMusic(context, file)
                        status = if (saved != null) "صدا در پوشه Music ذخیره شد." else "ذخیره در پوشه Music انجام نشد؛ فایل اصلی در حافظه برنامه باقی است."
                    }
                }
            ) { Text("ذخیره در Music") }
        }
        Text("نکته: این مرحله گفتار را می‌سازد، نه موسیقی یا آواز. تولید موسیقی آفلاین مرحله جداگانه‌ای است.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun saveAudioToMusic(context: Context, source: File): Uri? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AJAMI-AI")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: error("Cannot open output stream")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else null
    }.getOrNull()
}
