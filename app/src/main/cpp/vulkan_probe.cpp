#include <jni.h>
#include <vulkan/vulkan.h>
#include "generated/compute_shader_spv.h"
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

static std::string vkerr(const char* step, VkResult r) {
    std::ostringstream s; s << step << " (VkResult=" << static_cast<int>(r) << ")"; return s.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ajami_ai_VulkanProbe_nativeRun(JNIEnv* env, jobject) {
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    std::string message = "Vulkan compute test failed";
    auto cleanup = [&]() {
        if (device) vkDeviceWaitIdle(device);
        if (fence && device) vkDestroyFence(device, fence, nullptr);
        if (commandPool && device) vkDestroyCommandPool(device, commandPool, nullptr);
        if (pipeline && device) vkDestroyPipeline(device, pipeline, nullptr);
        if (shader && device) vkDestroyShaderModule(device, shader, nullptr);
        if (pipelineLayout && device) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        if (descriptorPool && device) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
        if (setLayout && device) vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
        if (buffer && device) vkDestroyBuffer(device, buffer, nullptr);
        if (memory && device) vkFreeMemory(device, memory, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    };
    auto fail = [&](const std::string& why) {
        message = why; cleanup(); return env->NewStringUTF(message.c_str());
    };

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "AJAMI-AI Compute Smoke Test";
    app.applicationVersion = 1; app.pEngineName = "AJAMI-AI";
    app.engineVersion = 1; app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkResult vr = vkCreateInstance(&ici, nullptr, &instance);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت Vulkan instance ناموفق", vr));

    uint32_t gpuCount = 0;
    vr = vkEnumeratePhysicalDevices(instance, &gpuCount, nullptr);
    if (vr != VK_SUCCESS || gpuCount == 0) return fail(vkerr("دستگاه Vulkan پیدا نشد", vr));
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vr = vkEnumeratePhysicalDevices(instance, &gpuCount, gpus.data());
    if (vr != VK_SUCCESS) return fail(vkerr("خواندن فهرست GPU ناموفق", vr));

    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    uint32_t queueFamily = UINT32_MAX;
    for (VkPhysicalDevice candidate : gpus) {
        uint32_t n = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, nullptr);
        std::vector<VkQueueFamilyProperties> queues(n);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, queues.data());
        for (uint32_t i = 0; i < n; ++i) {
            if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                physical = candidate; queueFamily = i;
                vkGetPhysicalDeviceProperties(candidate, &properties); break;
            }
        }
        if (physical) break;
    }
    if (!physical) return fail("صف محاسباتی Vulkan موجود نیست.");

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily; qci.queueCount = 1; qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    vr = vkCreateDevice(physical, &dci, nullptr, &device);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت logical device ناموفق", vr));

    constexpr uint32_t elementCount = 64;
    constexpr VkDeviceSize byteSize = elementCount * sizeof(uint32_t);
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = byteSize; bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    vr = vkCreateBuffer(device, &bci, nullptr, &buffer);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت storage buffer ناموفق", vr));

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device, buffer, &requirements);
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physical, &memoryProperties);
    uint32_t memoryType = UINT32_MAX;
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
        const auto flags = memoryProperties.memoryTypes[i].propertyFlags;
        if ((requirements.memoryTypeBits & (1u << i)) &&
            (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { memoryType = i; break; }
    }
    if (memoryType == UINT32_MAX) return fail("GPU درایور حافظهٔ قابل‌دسترسی و coherent برای تست ارائه نکرد.");
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = requirements.size; mai.memoryTypeIndex = memoryType;
    vr = vkAllocateMemory(device, &mai, nullptr, &memory);
    if (vr != VK_SUCCESS) return fail(vkerr("تخصیص حافظه ناموفق", vr));
    vr = vkBindBufferMemory(device, buffer, memory, 0);
    if (vr != VK_SUCCESS) return fail(vkerr("اتصال حافظه به بافر ناموفق", vr));

    void* mapped = nullptr;
    vr = vkMapMemory(device, memory, 0, byteSize, 0, &mapped);
    if (vr != VK_SUCCESS) return fail(vkerr("دسترسی CPU به بافر ناموفق", vr));
    auto* values = static_cast<uint32_t*>(mapped);
    for (uint32_t i = 0; i < elementCount; ++i) values[i] = i;
    vkUnmapMemory(device, memory);

    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0; binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    binding.descriptorCount = 1; binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo slci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    slci.bindingCount = 1; slci.pBindings = &binding;
    vr = vkCreateDescriptorSetLayout(device, &slci, nullptr, &setLayout);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت descriptor layout ناموفق", vr));

    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1; plci.pSetLayouts = &setLayout;
    vr = vkCreatePipelineLayout(device, &plci, nullptr, &pipelineLayout);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت pipeline layout ناموفق", vr));

    VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smci.codeSize = sizeof(ajami_compute_spv); smci.pCode = ajami_compute_spv;
    vr = vkCreateShaderModule(device, &smci, nullptr, &shader);
    if (vr != VK_SUCCESS) return fail(vkerr("بارگذاری compute shader ناموفق", vr));

    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; stage.module = shader; stage.pName = "main";
    VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage = stage; cpci.layout = pipelineLayout;
    vr = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت compute pipeline ناموفق", vr));

    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = 1; dpci.poolSizeCount = 1; dpci.pPoolSizes = &poolSize;
    vr = vkCreateDescriptorPool(device, &dpci, nullptr, &descriptorPool);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت descriptor pool ناموفق", vr));
    VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = descriptorPool; dsai.descriptorSetCount = 1; dsai.pSetLayouts = &setLayout;
    vr = vkAllocateDescriptorSets(device, &dsai, &descriptorSet);
    if (vr != VK_SUCCESS) return fail(vkerr("تخصیص descriptor set ناموفق", vr));
    VkDescriptorBufferInfo dbi{buffer, 0, byteSize};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = descriptorSet; write.dstBinding = 0; write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; write.pBufferInfo = &dbi;
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = queueFamily;
    vr = vkCreateCommandPool(device, &pci, nullptr, &commandPool);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت command pool ناموفق", vr));
    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = commandPool; cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cbai.commandBufferCount = 1;
    VkCommandBuffer command = VK_NULL_HANDLE;
    vr = vkAllocateCommandBuffers(device, &cbai, &command);
    if (vr != VK_SUCCESS) return fail(vkerr("تخصیص command buffer ناموفق", vr));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vr = vkBeginCommandBuffer(command, &begin);
    if (vr != VK_SUCCESS) return fail(vkerr("شروع command buffer ناموفق", vr));
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    vkCmdDispatch(command, 1, 1, 1);
    VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer = buffer; barrier.offset = 0; barrier.size = byteSize;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, nullptr, 1, &barrier, 0, nullptr);
    vr = vkEndCommandBuffer(command);
    if (vr != VK_SUCCESS) return fail(vkerr("پایان command buffer ناموفق", vr));

    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vr = vkCreateFence(device, &fci, nullptr, &fence);
    if (vr != VK_SUCCESS) return fail(vkerr("ساخت fence ناموفق", vr));
    VkQueue queue = VK_NULL_HANDLE; vkGetDeviceQueue(device, queueFamily, 0, &queue);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
    vr = vkQueueSubmit(queue, 1, &submit, fence);
    if (vr != VK_SUCCESS) return fail(vkerr("ارسال shader به GPU ناموفق", vr));
    vr = vkWaitForFences(device, 1, &fence, VK_TRUE, 15'000'000'000ULL);
    if (vr != VK_SUCCESS) return fail(vkerr("GPU در مهلت ۱۵ ثانیه پاسخ نداد", vr));

    vr = vkMapMemory(device, memory, 0, byteSize, 0, &mapped);
    if (vr != VK_SUCCESS) return fail(vkerr("خواندن نتیجهٔ GPU ناموفق", vr));
    values = static_cast<uint32_t*>(mapped);
    uint32_t errors = 0;
    for (uint32_t i = 0; i < elementCount; ++i) if (values[i] != i * 3u + 7u) ++errors;
    vkUnmapMemory(device, memory);

    std::ostringstream out;
    out << (errors == 0 ? "آزمایش محاسبات واقعی Vulkan موفق شد." : "Shader اجرا شد اما نتیجه نادرست است.")
        << "\nGPU: " << properties.deviceName
        << "\nVulkan API: " << VK_VERSION_MAJOR(properties.apiVersion) << "." << VK_VERSION_MINOR(properties.apiVersion)
        << "\nShader: 64 عنصر؛ هر عنصر = مقدار × 3 + 7"
        << "\nتعداد خطاهای خروجی: " << errors
        << "\nنتیجه: " << (errors == 0 ? "خروجی GPU با 64 مقدار مورد انتظار تطبیق دارد." : "نیازمند بررسی درایور/Shader است.")
        << "\nاین تست هنوز اجرای مدل انتشار یا موتور تصویر‌به‌ویدئو را اثبات نمی‌کند.";
    message = out.str();
    cleanup();
    return env->NewStringUTF(message.c_str());
}
