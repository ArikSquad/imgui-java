#include "jni_vulkan.h"

#include <cstdlib>
#include <string>

#include "imgui_impl_vulkan.h"

struct combo {
    PFN_vkGetInstanceProcAddr instanceProcAddr;
    PFN_vkGetDeviceProcAddr deviceProcAddr;
    VkInstance instance;
    VkDevice device;
};

static combo g_combo;
static VkSampler linear;
static std::string lastMissingFunction;

static PFN_vkVoidFunction lwjglLoad(const char* function_name, void* ptr) {
    combo* comb = (combo*)ptr;
    PFN_vkVoidFunction function = comb->instanceProcAddr(comb->instance, function_name);
    if (function == nullptr && comb->deviceProcAddr != nullptr && comb->device != VK_NULL_HANDLE) {
        function = comb->deviceProcAddr(comb->device, function_name);
    }
    if (function == nullptr) {
        lastMissingFunction = function_name;
    }

    return function;
}


void* get_long_field(JNIEnv* env, jobject obj, const char* field_name) {
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) return nullptr;

    jfieldID fid = env->GetFieldID(cls, field_name, "J");
    env->DeleteLocalRef(cls);

    if (fid == nullptr) {
        return nullptr;
    }

    jlong value = env->GetLongField(obj, fid);
    return (void*)(intptr_t)value;
}

int get_int_field(JNIEnv* env, jobject obj, const char* field_name) {
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) return 0;

    jfieldID fid = env->GetFieldID(cls, field_name, "I");
    env->DeleteLocalRef(cls);

    if (fid == nullptr) {
        return 0;
    }

    jint value = env->GetIntField(obj, fid);
    return value;
}
static VkFormat s_colorFormat;

JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_init(JNIEnv* env, jobject that, jint apiVersion, jlong instanceProcAddr, jlong instance, jlong physicalDevice, jlong device, jlong queue, jobject otherData) {
    g_combo.instanceProcAddr =
        (PFN_vkGetInstanceProcAddr)(intptr_t)instanceProcAddr;


    g_combo.instance =
        (VkInstance)(intptr_t)instance;
    g_combo.device =
        (VkDevice)(intptr_t)device;
    g_combo.deviceProcAddr =
        (PFN_vkGetDeviceProcAddr)g_combo.instanceProcAddr(g_combo.instance, "vkGetDeviceProcAddr");

    lastMissingFunction.clear();
    if (!ImGui_ImplVulkan_LoadFunctions(apiVersion, lwjglLoad, &g_combo)) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        std::string message = "failed";
        if (!lastMissingFunction.empty()) {
            message += ": ";
            message += lastMissingFunction;
        }
        env->ThrowNew(exceptionClass, message.c_str());
        return;
    }

    if (!linear)
    {
        linear = (VkSampler)(intptr_t)get_long_field(env, otherData, "sampler");

    }

    ImGui_ImplVulkan_InitInfo info = {};

    info.ApiVersion = apiVersion;
    info.Instance = (VkInstance)(intptr_t)instance;
    info.PhysicalDevice = (VkPhysicalDevice)(intptr_t)physicalDevice;
    info.Device = (VkDevice)(intptr_t)device;
    info.Queue = (VkQueue)(intptr_t)queue;
    info.UseDynamicRendering = true;
    info.QueueFamily = get_int_field(env, otherData, "queueFamily");
    info.MinImageCount = get_int_field(env, otherData, "minImageCount");
    info.ImageCount = get_int_field(env, otherData, "imageCount");
    info.DescriptorPool = (VkDescriptorPool)(intptr_t)get_long_field(env, otherData, "descriptorPool");
    info.PipelineCache = (VkPipelineCache)(intptr_t)get_long_field(env, otherData, "pipelineCache");
    s_colorFormat = static_cast<VkFormat>(get_int_field(env, otherData, "colorFormat"));
    auto depthFormat = static_cast<VkFormat>(get_int_field(env, otherData, "depthFormat"));

    VkPipelineRenderingCreateInfoKHR renderingInfo = {};
    renderingInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
    renderingInfo.pNext = nullptr;

    renderingInfo.colorAttachmentCount = 1;
    renderingInfo.pColorAttachmentFormats = &s_colorFormat;

    renderingInfo.depthAttachmentFormat = depthFormat;
    renderingInfo.stencilAttachmentFormat = VK_FORMAT_UNDEFINED;

    info.PipelineInfoMain = {};
    info.PipelineInfoMain.MSAASamples = VK_SAMPLE_COUNT_1_BIT;
    info.PipelineInfoMain.PipelineRenderingCreateInfo = renderingInfo;

    ImGui_ImplVulkan_Init(&info);
}

JNIEXPORT void JNICALL  Java_imgui_vk_ImGuiVulkan_newFrame(JNIEnv *, jobject) {
    ImGui_ImplVulkan_NewFrame();
}


JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_renderDraw(JNIEnv *, jobject, jlong drawData, jlong cmdBuf) {
    auto* data = (ImDrawData*)(intptr_t)drawData;
    auto cmdBufs = (VkCommandBuffer)(intptr_t)cmdBuf;

    ImGui_ImplVulkan_RenderDrawData(data, cmdBufs);
}

JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_shutdown(JNIEnv *, jobject) {
    ImGui_ImplVulkan_Shutdown();
}

JNIEXPORT jlong JNICALL Java_imgui_vk_ImGuiVulkan_addTexture(JNIEnv *, jobject, jlong imageView, jint imageLayout) {
    auto view = (VkImageView)(intptr_t)imageView;
    auto layout = (VkImageLayout)imageLayout;

    return reinterpret_cast<jlong>(ImGui_ImplVulkan_AddTexture(linear, view, layout));
}

JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_deleteTexture(JNIEnv *, jobject, jlong set) {
    ImGui_ImplVulkan_RemoveTexture((VkDescriptorSet)(intptr_t)set);
}
