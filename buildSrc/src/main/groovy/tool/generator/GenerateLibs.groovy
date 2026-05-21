package tool.generator

import com.badlogic.gdx.jnigen.NativeCodeGenerator
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec

import java.util.regex.Pattern

@CompileStatic
class GenerateLibs extends DefaultTask {
    private static final String[] INCLUDES = [
        'include/imgui',
        'include/imnodes',
        'include/imgui-node-editor',
        'include/imguizmo',
        'include/implot',
        'include/ImGuiColorTextEdit',
//        'include/ImGuiFileDialog',
        'include/imgui_club/imgui_memory_editor',
        'include/imgui-knobs'
    ]

    @Internal
    String group = 'build'
    @Internal
    String description = 'Generate imgui-java native binaries.'

    private final String[] buildEnvs = System.getProperty('envs')?.split(',')
    private final boolean forWindows = buildEnvs?.contains('windows')
    private final boolean forLinux = buildEnvs?.contains('linux')
    private final boolean forMac = buildEnvs?.contains('macos')
    private final boolean forMacArm64 = buildEnvs?.contains('macosarm64')

    private final boolean isLocal = System.properties.containsKey('local')
    private final boolean withFreeType = Boolean.valueOf(System.properties.getProperty('freetype', 'true'))

    private final String sourceDir = project.file('src/generated/java')
    private final String classpath = project.file('build/classes/java/main')
    private final String rootDir = (isLocal ? project.buildDir.path : '/tmp/imgui')
    private final String jniDir = "$rootDir/jni"
    private final String tmpDir = "$rootDir/tmp"
    private final String libsDirName = 'libsNative'

    @TaskAction
    void generate() {
        println 'Generating Native Libraries...'
        println "Build targets: $buildEnvs"
        println "Local: $isLocal"
        println "FreeType: $withFreeType"
        println '====================================='

        if (!buildEnvs) {
            throw new IllegalStateException('No build targets')
        }

        new File(jniDir).deleteDir()
        new File(tmpDir).deleteDir()
        new File("$rootDir/$libsDirName").deleteDir()

        // Generate h/cpp files for JNI
        new NativeCodeGenerator().generate(sourceDir, classpath, jniDir)
        normalizeGeneratedJniForMsvc()

        // Copy ImGui h/cpp files
        project.copy { CopySpec spec ->
            INCLUDES.each {
                spec.from(project.rootProject.file(it)) { CopySpec s -> s.include('*.h', '*.cpp', '*.inl') }
            }
            spec.from(project.rootProject.file('imgui-binding/src/main/native'))
            spec.into(jniDir)
            spec.duplicatesStrategy = DuplicatesStrategy.INCLUDE // Allows for duplicate imconfig.h, we ensure the correct one is copied below
        }

        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('include/imgui/backends')) { CopySpec s ->
                s.include('imgui_impl_glfw.cpp', 'imgui_impl_glfw.h', 'imgui_impl_vulkan.cpp', 'imgui_impl_vulkan.h')
            }
            spec.into(jniDir)
        }

        // Ensure we overwrite imconfig.h with our own
        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('imgui-binding/src/main/native/imconfig.h'))
            spec.into(jniDir)
        }

        // Ensure the active ImGuiColorTextEdit snapshot wins even if a stale TextEditor.* exists in the JNI dir.
        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('include/ImGuiColorTextEdit')) { CopySpec s -> s.include('TextEditor.h', 'TextEditor.cpp') }
            spec.into(jniDir)
        }

        if (withFreeType) {
            project.copy { CopySpec spec ->
                spec.from(project.rootProject.file('include/imgui/misc/freetype')) { CopySpec it -> it.include('*.h', '*.cpp') }
                spec.into("$jniDir/misc/freetype")
            }

            // Since we give a possibility to build library without enabled freetype - define should be set like that.
            replaceSourceFileContent("imconfig.h", "//#define IMGUI_ENABLE_FREETYPE", "#define IMGUI_ENABLE_FREETYPE")

            // Binding specific behavior to handle FreeType.
            // By defining IMGUI_ENABLE_FREETYPE, Dear ImGui will default to using the FreeType font renderer.
            // However, we modify the source code to ensure that, even with this, the STB_TrueType renderer is used instead.
            // To use the FreeType font renderer, it must be explicitly forced on the atlas manually.
            replaceSourceFileContent("imgui_draw.cpp", "ImGuiFreeType::GetFontLoader()", "ImFontAtlasGetFontLoaderForStbTruetype()")
        }

        // Copy dirent for ImGuiFileDialog
        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('include/ImGuiFileDialog/dirent')) { CopySpec s -> s.include('*.h', '*.cpp', '*.inl') }
            spec.into(jniDir + '/dirent')
        }

        writeCMakeProject()

        if (forWindows) {
            runCMakeBuild('windows64', 'imgui-java64.dll', [], [:])
            checkLibExist("windows64/imgui-java64.dll")
        }
        if (forLinux) {
            runCMakeBuild('linux64', 'libimgui-java64.so', [], [:])
            checkLibExist("linux64/libimgui-java64.so")
        }
        if (forMac) {
            runCMakeBuild('macosx64', 'libimgui-java64.dylib', [], [
                'CMAKE_OSX_ARCHITECTURES'    : 'x86_64',
                'CMAKE_OSX_DEPLOYMENT_TARGET': '10.15'
            ])
            checkLibExist("macosx64/libimgui-java64.dylib")
        }
        if (forMacArm64) {
            runCMakeBuild('macosxarm64', 'libimgui-java64.dylib', [], [
                'CMAKE_OSX_ARCHITECTURES'    : 'arm64',
                'CMAKE_OSX_DEPLOYMENT_TARGET': '10.15'
            ])
            checkLibExist("macosxarm64/libimgui-java64.dylib")
        }
    }

    void checkLibExist(String libName) {
        def path = new File("$rootDir/$libsDirName/$libName")
        if (!path.exists()) {
            logger.error("Failed to build $libName!")
            throw new IllegalStateException("$path does not exist")
        }
    }

    void replaceSourceFileContent(String fileName, String replaceWhat, String replaceWith) {
        def sourceFile = new File("$jniDir/$fileName")
        def sourceTxt = sourceFile.text
        def sourceTxtModified = sourceTxt.replace(replaceWhat, replaceWith)
        if (sourceTxt == sourceTxtModified) {
            throw new IllegalStateException("Unable to replace [$fileName] with content [$replaceWith]!")
        }
        sourceFile.text = sourceTxtModified
    }

    void normalizeGeneratedJniForMsvc() {
        new File(jniDir, 'imj_msvc_jnigen.h').text = '''#pragma once
#include <vector>

template <typename T>
class JniTempArray {
public:
    explicit JniTempArray(int size) : values(size > 0 ? static_cast<size_t>(size) : 0) {}

    operator T*() { return values.data(); }
    operator const T*() const { return values.data(); }

    T& operator[](int index) { return values[static_cast<size_t>(index)]; }
    const T& operator[](int index) const { return values[static_cast<size_t>(index)]; }

private:
    std::vector<T> values;
};
'''

        Pattern dynamicArrayPattern = Pattern.compile('(?m)^(\\s*)((?:const\\s+)?[A-Za-z_][A-Za-z0-9_:<>]*(?:\\s*\\*)?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\[([A-Za-z_][A-Za-z0-9_]*)\\];\\s*$')
        new File(jniDir).eachFileMatch(~/.*\.cpp/) { File sourceFile ->
            String sourceTxt = sourceFile.text
            String sourceTxtModified = dynamicArrayPattern.matcher(sourceTxt).replaceAll('$1JniTempArray<$2> $3($4);')
            if (sourceTxt == sourceTxtModified) {
                return
            }

            if (!sourceTxtModified.contains('#include "imj_msvc_jnigen.h"')) {
                sourceTxtModified = sourceTxtModified.replaceFirst('(?m)^(#include\\s+<[^\\r\\n]+>[\\r\\n]+)', '$1#include "imj_msvc_jnigen.h"\n')
            }
            sourceFile.text = sourceTxtModified
        }
    }

    void writeCMakeProject() {
        new File(jniDir, 'CMakeLists.txt').text = '''cmake_minimum_required(VERSION 3.20)
project(imgui_java_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(POLICY CMP0091)
    cmake_policy(SET CMP0091 NEW)
endif()
if(POLICY CMP0069)
    cmake_policy(SET CMP0069 NEW)
endif()

find_package(JNI REQUIRED)
include(CheckIPOSupported)
check_ipo_supported(RESULT IMJ_IPO_SUPPORTED OUTPUT IMJ_IPO_ERROR)

if(WIN32 AND NOT MSVC)
    message(FATAL_ERROR "Windows imgui-java natives must be built with the MSVC ABI, not MinGW.")
endif()

file(GLOB JNI_CPP_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_LIST_DIR}/*.cpp"
)
file(GLOB JNI_C_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_LIST_DIR}/*.c"
)

if(IMJ_WITH_FREETYPE)
    file(GLOB JNI_FREETYPE_CPP_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_LIST_DIR}/misc/freetype/*.cpp"
    )
    file(GLOB JNI_FREETYPE_C_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_LIST_DIR}/misc/freetype/*.c"
    )
    list(APPEND JNI_CPP_SOURCES ${JNI_FREETYPE_CPP_SOURCES})
    list(APPEND JNI_C_SOURCES ${JNI_FREETYPE_C_SOURCES})
endif()

add_library(imgui-java SHARED ${JNI_CPP_SOURCES} ${JNI_C_SOURCES})

target_include_directories(imgui-java PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/dirent"
    "${IMJ_GLFW_DIR}/include"
    ${JNI_INCLUDE_DIRS}
)

if(IMJ_IPO_SUPPORTED)
    set_property(TARGET imgui-java PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
else()
    message(STATUS "IPO/LTO disabled: ${IMJ_IPO_ERROR}")
endif()

if(MSVC)
    target_compile_options(imgui-java PRIVATE /Gy /Gw /Zc:inline)
elseif(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(imgui-java PRIVATE
        -ffunction-sections
        -fdata-sections
        $<$<COMPILE_LANGUAGE:CXX>:-fvisibility=hidden>
        $<$<COMPILE_LANGUAGE:C>:-fvisibility=hidden>
    )
    target_link_options(imgui-java PRIVATE
        $<$<PLATFORM_ID:Darwin>:-Wl,-dead_strip>
        $<$<PLATFORM_ID:Darwin>:-Wl,-x>
        $<$<NOT:$<PLATFORM_ID:Darwin>>:-Wl,--gc-sections>
        $<$<NOT:$<PLATFORM_ID:Darwin>>:-s>
    )
endif()

if(EXISTS "${IMJ_GLFW_DIR}/CMakeLists.txt")
    set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
    set(GLFW_INSTALL OFF CACHE BOOL "" FORCE)
    set(GLFW_LIBRARY_TYPE SHARED CACHE STRING "" FORCE)
    add_subdirectory("${IMJ_GLFW_DIR}" "${CMAKE_BINARY_DIR}/glfw" EXCLUDE_FROM_ALL)
    if(TARGET glfw)
        set_target_properties(glfw PROPERTIES OUTPUT_NAME "glfw")
        if(IMJ_IPO_SUPPORTED)
            set_property(TARGET glfw PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
        endif()
        target_link_libraries(imgui-java PRIVATE glfw)
        if(MSVC)
            set_property(TARGET glfw PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        endif()
    else()
        message(FATAL_ERROR "GLFW subproject did not define a glfw target.")
    endif()
else()
    find_package(glfw3 CONFIG QUIET)
    if(TARGET glfw)
        target_link_libraries(imgui-java PRIVATE glfw)
    elseif(TARGET glfw3)
        target_link_libraries(imgui-java PRIVATE glfw3)
    else()
        message(FATAL_ERROR "GLFW is required to compile imgui_impl_glfw.cpp. Provide glfw3Config.cmake or vendor/glfw.")
    endif()
endif()

if(MSVC)
    set_property(TARGET imgui-java PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_link_options(imgui-java PRIVATE /OPT:REF /OPT:ICF /INCREMENTAL:NO /DELAYLOAD:glfw.dll)
    target_link_libraries(imgui-java PRIVATE delayimp)
endif()

if(IMJ_WITH_FREETYPE)
    if(NOT WIN32)
        find_package(Freetype QUIET)
    endif()
    if(TARGET Freetype::Freetype)
        target_link_libraries(imgui-java PRIVATE Freetype::Freetype)
    elseif(Freetype_FOUND)
        target_include_directories(imgui-java PRIVATE ${FREETYPE_INCLUDE_DIRS})
        target_link_libraries(imgui-java PRIVATE ${FREETYPE_LIBRARIES})
    else()
        set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
        set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
        set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
        set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
        set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
        set(FT_ENABLE_ERROR_STRINGS OFF CACHE BOOL "" FORCE)
        set(IMJ_PREV_BUILD_SHARED_LIBS ${BUILD_SHARED_LIBS})
        set(BUILD_SHARED_LIBS OFF)
        if(EXISTS "${IMJ_FREETYPE_DIR}/CMakeLists.txt")
            add_subdirectory("${IMJ_FREETYPE_DIR}" "${CMAKE_BINARY_DIR}/freetype" EXCLUDE_FROM_ALL)
        else()
            include(FetchContent)
            message(STATUS "FreeType requested; fetching FreeType 2.14.3 because no package or local source tree was found.")
            FetchContent_Declare(
                freetype
                URL "https://downloads.sourceforge.net/freetype/freetype-2.14.3.tar.xz"
                URL_HASH SHA256=36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f
            )
            FetchContent_MakeAvailable(freetype)
        endif()
        set(BUILD_SHARED_LIBS ${IMJ_PREV_BUILD_SHARED_LIBS})
        target_link_libraries(imgui-java PRIVATE freetype)
        if(IMJ_IPO_SUPPORTED)
            set_property(TARGET freetype PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
        endif()
        if(MSVC)
            set_property(TARGET freetype PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        endif()
    endif()
endif()

if(DEFINED ENV{VULKAN_SDK})
    target_include_directories(imgui-java PRIVATE "$ENV{VULKAN_SDK}/Include")
    if(WIN32)
        target_link_directories(imgui-java PRIVATE "$ENV{VULKAN_SDK}/Lib")
        target_link_libraries(imgui-java PRIVATE vulkan-1)
    else()
        find_library(IMJ_VULKAN_LIBRARY
            NAMES vulkan
            PATHS "$ENV{VULKAN_SDK}/lib" "$ENV{VULKAN_SDK}/Lib"
            NO_DEFAULT_PATH
        )
        if(IMJ_VULKAN_LIBRARY)
            target_link_libraries(imgui-java PRIVATE "${IMJ_VULKAN_LIBRARY}")
        endif()
    endif()
endif()

set_target_properties(imgui-java PROPERTIES
    OUTPUT_NAME "imgui-java64"
    ARCHIVE_OUTPUT_DIRECTORY "${IMJ_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${IMJ_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY "${IMJ_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY_RELEASE "${IMJ_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${IMJ_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${IMJ_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY_DEBUG "${IMJ_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_DEBUG "${IMJ_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${IMJ_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY_MINSIZEREL "${IMJ_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_MINSIZEREL "${IMJ_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${IMJ_OUTPUT_DIR}"
)
'''
    }

    void runCMakeBuild(String platformDir, String libName, List<String> configureOptions, Map<String, String> options) {
        def buildDir = new File(tmpDir, "cmake-$platformDir")
        def outputDir = new File("$rootDir/$libsDirName/$platformDir")
        buildDir.mkdirs()
        outputDir.mkdirs()

        List<String> configureCommand = [
            'cmake',
            '-S', jniDir,
            '-B', buildDir.path,
            '-DCMAKE_BUILD_TYPE=MinSizeRel',
            "-DIMJ_OUTPUT_DIR=${outputDir.path}".toString(),
            "-DIMJ_WITH_FREETYPE=${withFreeType ? 'ON' : 'OFF'}".toString(),
            "-DIMJ_FREETYPE_DIR=${project.rootProject.file('build/vendor/freetype').path}".toString(),
            "-DIMJ_GLFW_DIR=${project.rootProject.file('vendor/glfw').path}".toString()
        ]
        configureCommand.addAll(configureOptions)
        options.each { String key, String value ->
            configureCommand.add("-D$key=$value".toString())
        }

        exec(configureCommand)
        println((['cmake', '--build', buildDir.path, '--config', 'MinSizeRel'] as List<String>).toListString())
        exec(['cmake', '--build', buildDir.path, '--config', 'MinSizeRel'] as List<String>)

        def expectedLib = new File(outputDir, libName)
        if (!expectedLib.exists()) {
            def builtLib = findFile(buildDir, libName)
            if (builtLib == null) {
                throw new IllegalStateException("CMake build completed, but $libName was not found under $buildDir")
            }
            project.copy { CopySpec spec ->
                spec.from(builtLib)
                spec.into(outputDir)
            }
        }
    }

    void exec(List<String> command) {
        println "\$ ${command.join(' ')}"
        project.providers.exec(new Action<ExecSpec>() {
            @Override
            void execute(ExecSpec spec) {
                spec.commandLine(command)
            }
        }).result.get().assertNormalExitValue()
    }

    File findFile(File dir, String fileName) {
        File[] files = dir.listFiles()
        if (files == null) {
            return null
        }

        for (File file : files) {
            if (file.name == fileName) {
                return file
            }
            if (file.isDirectory()) {
                File found = findFile(file, fileName)
                if (found != null) {
                    return found
                }
            }
        }

        return null
    }
}
