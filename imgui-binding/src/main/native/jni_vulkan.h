#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_init(JNIEnv*, jobject, jint, jlong, jlong, jlong, jlong, jlong, jobject);
JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_newFrame(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_renderDraw(JNIEnv*, jobject, jlong, jlong);
JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_shutdown(JNIEnv*, jobject);
JNIEXPORT jlong JNICALL Java_imgui_vk_ImGuiVulkan_addTexture(JNIEnv*, jobject, jlong, jint);
JNIEXPORT void JNICALL Java_imgui_vk_ImGuiVulkan_deleteTexture(JNIEnv*, jobject, jlong);

#ifdef __cplusplus
}
#endif
