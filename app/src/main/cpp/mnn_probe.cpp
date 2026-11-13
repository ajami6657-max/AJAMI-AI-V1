#include <jni.h>
#include <string>
#include <vector>
#include <MNN/Interpreter.hpp>

extern "C" JNIEXPORT jstring JNICALL
Java_com_ajami_ai_MnnVulkanProbe_execute(JNIEnv* env, jobject /* thiz */) {
    std::vector<MNN::ScheduleConfig> configs(1);
    configs[0].type = MNN_FORWARD_VULKAN;
    configs[0].backupType = MNN_FORWARD_CPU;
    configs[0].numThread = 2;

    auto runtime = MNN::Interpreter::createRuntime(configs);
    const auto gpu = runtime.first.find(MNN_FORWARD_VULKAN);
    if (gpu == runtime.first.end() || !gpu->second) {
        const std::string message =
            std::string("MNN Vulkan runtime was not created. MNN ") + MNN::getVersion() +
            ". CPU fallback remains available; no GPU inference was attempted.";
        return env->NewStringUTF(message.c_str());
    }

    const std::string message =
        std::string("SUCCESS: MNN ") + MNN::getVersion() +
        " created its Vulkan runtime on this device. CPU fallback configured. "
        "This confirms backend initialization only; model/operator inference is the next test.";
    return env->NewStringUTF(message.c_str());
}
