// LMC Launcher —— 高性能 Android Minecraft Java 版后端
// 根构建脚本：声明仓库与模块
//
// 模块划分：
//   app   —— Kotlin + Jetpack Compose 启动器 UI 层（Material 3）
//   core  —— C++ 核心后端（NDK + CMake），以 :app 的 externalNativeBuild 方式编译为 liblmc_core.so
//   （Mesa/Zink/Turnip 与 OpenJDK 由 scripts/ 下的独立脚本构建，产物放入应用 files 目录，不参与 Gradle）

pluginManagement {
    repositories {
        // 阿里云镜像在前（网络不稳时优先命中），原始仓库兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "LMCLauncher"
include(":app")
