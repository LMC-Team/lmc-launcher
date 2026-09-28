# LMC 核心后端 liblmc_core.so
# 依赖：NDK r27（提供 vulkan / android / aaudio / log），mimalloc 由 FetchContent 拉取
cmake_minimum_required(VERSION 3.22)
project(lmc_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Vulkan Android 扩展头文件（VK_KHR_android_surface）
add_compile_definitions(VK_USE_PLATFORM_ANDROID_KHR)

# 性能优先编译选项
option(LMC_OPT_FLAGS "启用激进的性能优化" ON)
if(LMC_OPT_FLAGS)
    add_compile_options(
        -O3
        -fno-math-errno      # 数学函数不置 errno，利于向量化
        -fthreadsafe-statics # 保留（多线程单例安全）
        -ffunction-sections -fdata-sections
    )
    add_link_options(-Wl,--gc-sections)
endif()

# Vulkan 结构体惯用省略 pNext/flags 字段（零初始化保证），豁免该告警
add_compile_options(-Wno-missing-field-initializers)

# ------------------------------------------------------------------
# mimalloc 2.1.7 —— 内存分配器替换（github.com/microsoft/mimalloc）
# 静态链入 lmc_core；核心后端自身的分配走 mi_* 快速路径。
# 注：Android 应用进程无法 LD_PRELOAD 全局替换（SELinux 限制），
# JVM 堆由 GC 自管 mmap，因此 mimalloc 的收益集中于 native 层（渲染/输入/存档缓冲）。
# ------------------------------------------------------------------
option(LMC_USE_MIMALLOC "使用 mimalloc 替换默认分配器" ON)
if(LMC_USE_MIMALLOC)
    include(FetchContent)
    FetchContent_Declare(mimalloc
        URL https://github.com/microsoft/mimalloc/archive/refs/tags/v2.1.7.tar.gz
        # 上线前建议补充 URL_HASH 固定校验和（sha256sum 该 tar.gz）
    )
    set(MI_BUILD_SHARED OFF CACHE BOOL "" FORCE)
    set(MI_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(MI_BUILD_STATIC ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(mimalloc)
endif()

# ------------------------------------------------------------------
# 源码：core/src 下的各功能模块
# ------------------------------------------------------------------
set(LMC_SOURCES
    src/core_runtime.cpp
    src/jni_bridge/jni_globals.cpp
    src/jni_bridge/native_bridge.cpp
    src/jvm/jvm_loader.cpp
    src/jvm/jvm_options.cpp
    src/graal/graal_runner.cpp
    src/render/vulkan_swapchain.cpp
    src/render/pipeline_cache.cpp
    src/render/render_bridge.cpp
    src/render/gl4es_loader.cpp
    src/sched/cpu_topology.cpp
    src/sched/thread_affinity.cpp
    src/sched/thermal_monitor.cpp
    src/input/input_hub.cpp
    src/input/input_queue.cpp
    src/input/native_activity_glue.cpp
    src/audio/audio_backend.cpp
    src/mem/oom_adj.cpp
    src/mem/mmap_saves.cpp
)

add_library(lmc_core SHARED ${LMC_SOURCES})

target_include_directories(lmc_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(aaudio-lib aaudio)

target_link_libraries(lmc_core
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
    ${aaudio-lib}
)

if(LMC_USE_MIMALLOC)
    target_link_libraries(lmc_core PRIVATE mimalloc-static)
    target_compile_definitions(lmc_core PRIVATE LMC_WITH_MIMALLOC=1)
    # 关键：mimalloc 的 static 目标默认加 -ftls-model=initial-exec（优化主程序场景），
    # 但 liblmc_core.so 是被 System.loadLibrary dlopen 的 —— Android bionic
    # 禁止 dlopen 的库使用 IE TLS 模型（运行时报
    # "dlopen failed: TLS symbol ... using IE access model"）。
    # 过滤该选项，回落默认 general-dynamic 模型。
    get_target_property(_mi_tls_opts mimalloc-static COMPILE_OPTIONS)
    if(_mi_tls_opts)
        list(FILTER _mi_tls_opts EXCLUDE REGEX "ftls-model")
        set_target_properties(mimalloc-static PROPERTIES COMPILE_OPTIONS "${_mi_tls_opts}")
    endif()
endif()

# liblmc_core.so 会由 NativeActivity 通过 dlopen 加载，保持符号可见
set_target_properties(lmc_core PROPERTIES
    LINK_FLAGS "-Wl,--no-undefined"
)
