#include "jni_glfw.h"
#include <cstdint>

#include "imgui_impl_glfw.h"
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_imgui_glfw_ImGuiGLFW_init(JNIEnv* env, jclass that, jlong windowHandle) {
    ImGui_ImplGlfw_InitForVulkan((GLFWwindow*)(intptr_t)windowHandle, false);
}

JNIEXPORT void JNICALL Java_imgui_glfw_ImGuiGLFW_installCallbacks(JNIEnv* env, jclass that, jlong windowHandle) {
    ImGui_ImplGlfw_InstallCallbacks((GLFWwindow*)(intptr_t)windowHandle);
}

JNIEXPORT void JNICALL Java_imgui_glfw_ImGuiGLFW_restoreCallbacks(JNIEnv* env, jclass that, jlong windowHandle) {
    ImGui_ImplGlfw_RestoreCallbacks((GLFWwindow*)(intptr_t)windowHandle);
}

JNIEXPORT void JNICALL Java_imgui_glfw_ImGuiGLFW_shutdown(JNIEnv* env, jclass that) {
    ImGui_ImplGlfw_Shutdown();
}

JNIEXPORT void JNICALL Java_imgui_glfw_ImGuiGLFW_newFrame(JNIEnv* env, jclass that) {
    ImGui_ImplGlfw_NewFrame();
}

#ifdef __cplusplus
}
#endif
