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
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun DeviceDiagnostics() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var vulkanBusy by remember { mutableStateOf(false) }
    var vulkanResult by remember { mutableStateOf("آزمایش عملی هنوز اجرا نشده است.") }
    var mnnBusy by remember { mutableStateOf(false) }
    var mnnResult by remember { mutableStateOf("آزمایش موتور MNN هنوز اجرا نشده است.") }
    val scope = rememberCoroutineScope()
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
        Text("این گزارش قابلیت‌های دستگاه را نشان می‌دهد؛ اجرای واقعی مدل و سازگاری درایور Mali باید جداگانه آزمایش شود.", color = Color.LightGray)
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
                    if (vulkanLevel > 0 && vulkanVersion > 0) "اندروید وجود قابلیت Vulkan را گزارش می‌کند؛ این به‌تنهایی تأیید اجرای مدل نیست."
                    else "قابلیت Vulkan در فهرست ویژگی‌های اندروید کامل گزارش نشده است.",
                    color = darkGold
                )
            }
        }
        Button(enabled = !vulkanBusy, onClick = {
            vulkanBusy = true
            vulkanResult = "در حال اجرای Shader محاسباتی Vulkan..."
            scope.launch {
                vulkanResult = withContext(Dispatchers.IO) { VulkanProbe.execute() }
                vulkanBusy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (vulkanBusy) "در حال آزمایش..." else "اجرای آزمایش واقعی Vulkan") }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))) {
            Text(vulkanResult, modifier = Modifier.fillMaxWidth().padding(12.dp), color = Color.White)
        }
        Divider()
        Text("آزمایش موتور MNN روی GPU", color = darkGold, style = MaterialTheme.typography.titleMedium)
        Text("این آزمایش جدا از موتور قبلی است و فقط تلاش می‌کند Runtime واقعی MNN/Vulkan را روی گوشی راه‌اندازی کند. در صورت شکست، نسخه اصلی برنامه تغییر نمی‌کند.", color = Color.LightGray)
        Button(enabled = !mnnBusy, onClick = {
            mnnBusy = true
            mnnResult = "در حال راه‌اندازی MNN Vulkan..."
            scope.launch {
                mnnResult = withContext(Dispatchers.IO) {
                    runCatching { MnnVulkanProbe.execute() }
                        .getOrElse { "خطای راه‌اندازی MNN: ${it.localizedMessage ?: it.javaClass.simpleName}" }
                }
                mnnBusy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (mnnBusy) "در حال آزمایش MNN..." else "آزمایش MNN Vulkan") }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10294A))) {
            Text(mnnResult, modifier = Modifier.fillMaxWidth().padding(12.dp), color = Color.White)
        }
        Text("موفقیت این مرحله فقط ایجاد Runtime را ثابت می‌کند؛ اجرای یک عملگر محاسباتی و سپس مدل تصویر، آزمون‌های بعدی هستند.", color = Color.LightGray)
    }
}
