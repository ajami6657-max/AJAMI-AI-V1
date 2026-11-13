package com.ajami.ai

internal object MnnVulkanProbe {
    init {
        System.loadLibrary("mnn_probe")
    }

    external fun execute(): String
}
