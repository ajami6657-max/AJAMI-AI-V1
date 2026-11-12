package com.ajami.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DeviceDiagnostics() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    val features = pm.systemAvailableFeatures.orEmpty()
    val vulkanLevel = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version ?: 0
    val vulkanVersion = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version ?: 0
    val gles = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = am.memoryClass
    val largeMemory = am.largeMemoryClass
    val abis = Build.SUPPORTED_ABIS.joinToString()
    val darkGold = Color(0xFFFFC857)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("گزارش سازگاری دستگاه", color = darkGold, style = MaterialTheme.typography.titleLarge)
        Text("این گزارش فقط قابلیت‌های اعلام‌شده توسط اندروید را می‌خواند؛ اجرای واقعی مدل و سازگاری درایور Mali هنوز باید جداگانه آزمایش شود.", color = Color.LightGray)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("دستگاه: ${Build.MANUFACTURER} ${Build.MODEL}", color = Color.White)
                Text("اندروید: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", color = Color.White)
                Text("پردازنده‌های پشتیبانی‌شده: $abis", color = Color.White)
                Text("حافظهٔ کلاس برنامه: $memory MB", color = Color.White)
                Text("حداکثر کلاس حافظهٔ بزرگ: $largeMemory MB", color = Color.White)
                Text("OpenGL ES feature version: $gles", color = Color.White)
                Text("Vulkan hardware level: ${if (vulkanLevel > 0) vulkanLevel else "اعلام نشده"}", color = Color.White)
                Text("Vulkan hardware version: ${if (vulkanVersion > 0) "0x" + vulkanVersion.toString(16) else "اعلام نشده"}", color = Color.White)
                Text(
                    if (vulkanLevel > 0 && vulkanVersion > 0) "اندروید وجود قابلیت Vulkan را گزارش می‌کند؛ این به‌تنهایی تأیید اجرای موتور ویدئو نیست."
                    else "قابلیت Vulkan در فهرست ویژگی‌های اندروید کامل گزارش نشده است.",
                    color = darkGold
                )
            }
        }
        Text("مرحلهٔ بعد: تست native Vulkan روی GPU واقعی و اجرای یک کرنل آزمایشی؛ این صفحه هنوز آن تست را انجام نمی‌دهد.", color = Color.LightGray)
    }
}
