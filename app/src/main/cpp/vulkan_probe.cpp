#include <jni.h>
#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include <sstream>

static std::string result(VkResult r) {
    std::ostringstream s; s << "VkResult=" << static_cast<int>(r); return s.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ajami_ai_VulkanProbe_nativeRun(JNIEnv* env, jobject) {
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    std::string message;
    auto finish = [&]() {
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
        if (fence && device) vkDestroyFence(device, fence, nullptr);
        if (pool && device) vkDestroyCommandPool(device, pool, nullptr);
        if (buffer && device) vkDestroyBuffer(device, buffer, nullptr);
        if (memory && device) vkFreeMemory(device, memory, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    };

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "AJAMI-AI Vulkan Probe";
    app.applicationVersion = 1;
    app.pEngineName = "AJAMI-AI";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkResult vr = vkCreateInstance(&ici, nullptr, &instance);
    if (vr != VK_SUCCESS) { message = "Vulkan instance ساخته نشد: " + result(vr); finish(); return env->NewStringUTF(message.c_str()); }

    uint32_t count = 0;
    vr = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (vr != VK_SUCCESS || count == 0) { message = "هیچ دستگاه Vulkan پیدا نشد: " + result(vr); finish(); return env->NewStringUTF(message.c_str()); }
    std::vector<VkPhysicalDevice> gpus(count);
    vkEnumeratePhysicalDevices(instance, &count, gpus.data());
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    uint32_t family = UINT32_MAX;
    VkPhysicalDeviceProperties props{};
    for (auto gpu : gpus) {
        uint32_t qcount = 0; vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qcount, nullptr);
        std::vector<VkQueueFamilyProperties> qs(qcount);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qcount, qs.data());
        for (uint32_t i=0; i<qcount; ++i) {
            if (qs[i].queueCount && (qs[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                physical = gpu; family = i; vkGetPhysicalDeviceProperties(gpu, &props); break;
            }
        }
        if (physical) break;
    }
    if (!physical) { message = "دستگاه Vulkan پیدا شد، اما صف محاسباتی در دسترس نیست."; finish(); return env->NewStringUTF(message.c_str()); }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family; qci.queueCount = 1; qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    vr = vkCreateDevice(physical, &dci, nullptr, &device);
    if (vr != VK_SUCCESS) { message = "ساخت Logical Device ناموفق: " + result(vr); finish(); return env->NewStringUTF(message.c_str()); }

    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = 4096; bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    vr = vkCreateBuffer(device, &bci, nullptr, &buffer);
    if (vr != VK_SUCCESS) { message = "ساخت بافر GPU ناموفق: " + result(vr); finish(); return env->NewStringUTF(message.c_str()); }
    VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(device, buffer, &req);
    VkPhysicalDeviceMemoryProperties mp{}; vkGetPhysicalDeviceMemoryProperties(physical, &mp);
    uint32_t memoryType = UINT32_MAX;
    for (uint32_t i=0; i<mp.memoryTypeCount; ++i) {
        if ((req.memoryTypeBits & (1u<<i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { memoryType=i; break; }
    }
    if (memoryType == UINT32_MAX) {
        for (uint32_t i=0; i<mp.memoryTypeCount; ++i) if (req.memoryTypeBits & (1u<<i)) { memoryType=i; break; }
    }
    if (memoryType == UINT32_MAX) { message = "نوع حافظه مناسب برای بافر پیدا نشد."; finish(); return env->NewStringUTF(message.c_str()); }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize=req.size; mai.memoryTypeIndex=memoryType;
    vr=vkAllocateMemory(device,&mai,nullptr,&memory);
    if (vr!=VK_SUCCESS) { message="تخصیص حافظه Vulkan ناموفق: "+result(vr); finish(); return env->NewStringUTF(message.c_str()); }
    vr=vkBindBufferMemory(device,buffer,memory,0);
    if (vr!=VK_SUCCESS) { message="اتصال حافظه به بافر ناموفق: "+result(vr); finish(); return env->NewStringUTF(message.c_str()); }

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex=family; pci.flags=VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    vr=vkCreateCommandPool(device,&pci,nullptr,&pool);
    if(vr!=VK_SUCCESS){message="ساخت Command Pool ناموفق: "+result(vr);finish();return env->NewStringUTF(message.c_str());}
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool=pool; cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount=1;
    VkCommandBuffer cmd=VK_NULL_HANDLE;
    vr=vkAllocateCommandBuffers(device,&cai,&cmd);
    if(vr!=VK_SUCCESS){message="ساخت Command Buffer ناموفق: "+result(vr);finish();return env->NewStringUTF(message.c_str());}
    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vr=vkBeginCommandBuffer(cmd,&cbi);
    if(vr==VK_SUCCESS){vkCmdFillBuffer(cmd,buffer,0,VK_WHOLE_SIZE,0xA1A1A1A1u);vr=vkEndCommandBuffer(cmd);}
    if(vr!=VK_SUCCESS){message="ضبط فرمان GPU ناموفق: "+result(vr);finish();return env->NewStringUTF(message.c_str());}
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vr=vkCreateFence(device,&fci,nullptr,&fence);
    if(vr!=VK_SUCCESS){message="ساخت Fence ناموفق: "+result(vr);finish();return env->NewStringUTF(message.c_str());}
    VkQueue queue=VK_NULL_HANDLE; vkGetDeviceQueue(device,family,0,&queue);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1;si.pCommandBuffers=&cmd;
    vr=vkQueueSubmit(queue,1,&si,fence);
    if(vr==VK_SUCCESS) vr=vkWaitForFences(device,1,&fence,VK_TRUE,10'000'000'000ULL);
    if(vr!=VK_SUCCESS){message="ارسال یا تکمیل فرمان روی صف GPU ناموفق: "+result(vr);finish();return env->NewStringUTF(message.c_str());}

    std::ostringstream out;
    out << "آزمایش Vulkan موفق بود.\nGPU: " << props.deviceName
        << "\nVendor ID: 0x" << std::hex << props.vendorID
        << "\nDevice ID: 0x" << props.deviceID << std::dec
        << "\nVulkan API: " << VK_VERSION_MAJOR(props.apiVersion) << "." << VK_VERSION_MINOR(props.apiVersion)
        << "\nCompute queue: " << family
        << "\nBuffer: 4096 bytes; memory allocation + bind موفق"
        << "\nGPU queue submit + fence wait موفق"
        << "\nنتیجه: مسیر پایه Vulkan روی این گوشی کار می‌کند؛ این هنوز اثبات اجرای مدل ویدئوساز نیست.";
    message=out.str();
    finish();
    return env->NewStringUTF(message.c_str());
}
