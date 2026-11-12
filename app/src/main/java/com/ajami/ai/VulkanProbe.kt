package com.ajami.ai

class VulkanProbe {
    private external fun nativeRun(): String

    companion object {
        fun execute(): String = try {
            System.loadLibrary("vulkan_probe")
            VulkanProbe().nativeRun()
        } catch (e: Throwable) {
            "اجرای آزمایش Vulkan ممکن نشد: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }
}
