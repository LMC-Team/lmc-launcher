pluginManagement {
    repositories {
        // 国内镜像优先(本机国际网络不稳)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.8.0")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "LMC-Zl2Shell" // zl2 壳(LMC 核心嵌入分支;lmc_core 由 ZalithLauncher 模块的 externalNativeBuild 编入)
include(":ZalithLauncher")
// LMC 核心性能层(C++ / liblmc_core.so),嵌入壳
include(":lmc-core")
include(":LWJGL")
include(":LWJGL:patches")
project(":LWJGL:patches").projectDir = file("LWJGL/patches")
include(":LWJGL:lwjgl-3.3.3")
project(":LWJGL:lwjgl-3.3.3").projectDir = file("LWJGL/3.3.3")
include(":LWJGL:lwjgl-3.4.1")
project(":LWJGL:lwjgl-3.4.1").projectDir = file("LWJGL/3.4.1")
include(":LayerController")
include(":ColorPicker")
include(":CardGrid")
include(":Terracotta")
include(":InputMap")
include(":Guide")
