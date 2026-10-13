// lmc-core:LMC 核心性能层(C++ / liblmc_core.so)
// 嵌入 zl2 壳:独立 CMake library 模块,ZalithLauncher 依赖之。
// 边界:只提供性能层(渲染环境/音频/调度/内存/温度监控),
//       JVM 启动链由壳(zl2 VMLauncher)负责,不启用 nativeCreateJvm。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 起 Kotlin 支持内置,无需再声明 kotlin.android
    id("com.android.library")
}

android {
    namespace = "com.lemwoodmc.core"
    compileSdk = 35
    // lmc 核心在 NDK r27 上验证过(壳的 ndk-build 项目用 r25,本模块独立指定)
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 26
        // JNI 契约类不可混淆(标准 JNI 命名解析依赖原始类名/方法名),
        // 以 consumer 规则下发给宿主(壳的 release 会开 R8)
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // 只编真机架构(arm64),缩短构建时间
                abiFilters += "arm64-v8a"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
