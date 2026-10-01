// LMC Launcher 主模块：Compose UI + C++ 核心后端（liblmc_core.so）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// C++ 编译开关：-Plmc.nativeBuild=false 可跳过 NDK/CMake（仅验证 Java/Kotlin 层，
// 供无 NDK 环境快速迭代；默认开启）
val nativeBuild = providers.gradleProperty("lmc.nativeBuild").orElse("true").get().toBoolean()

android {
    namespace = "com.lemwoodmc.launcher"
    compileSdk = 35
    // 本机 34.0.0 是 Linux 版（无 aapt.exe），钉 35.0.0（Windows 版）
    buildToolsVersion = "35.0.0"
    // NDK 版本钉死（CI 与本地一致；本机工具链为 r27b）
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.lemwoodmc.launcher"
        minSdk = 29 // Android 10，按项目约束不支持更旧系统
        // targetSdk 必须保持 28：Android 10+ 上 targetSdk>=29 的应用被 SELinux
        // neverallow 禁止 dlopen 应用私有目录（app_data_file）下的 so，
        // 而我们的 JVM（libjvm.so）就部署在 files/runtime/ 下——
        // PojavLauncher / FCL 等所有同类启动器均采用此策略。
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // 本项目仅面向 ARM64，不做多 ABI
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 声明游戏相关特性，便于应用商店与系统识别
    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (nativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("../core/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    defaultConfig {
        if (nativeBuild) {
            externalNativeBuild {
                cmake {
                    // 渲染后端三路径全部编译进 lmc_core；mimalloc 默认开启
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DLMC_USE_MIMALLOC=ON",
                        "-DCMAKE_BUILD_TYPE=Release",
                        // 打开优化：性能唯一优先级
                        "-DLMC_OPT_FLAGS=ON"
                    )
                    cppFlags += "-std=c++20"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // targetSdk 28 是硬约束（SELinux dlopen app_data_file），lint 的
    // ExpiredTargetSdkVersion/GooglePlay 检查不适用，直接排除
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 内联优化，游戏内控制层要求零开销
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    packaging {
        // 游戏 runtime 以目录形式放入 files，不做资源冲突合并
        jniLibs.useLegacyPackaging = true // 需要运行时 dlopen 压缩 so（如 libjvm 由我们另行放置，不在此列）
        // 本机 NDK 为 linux-x86_64 版（llvm-strip 不可运行），跳过 strip；
        // 发布构建在 Linux 机器上做，届时可移除
        jniLibs.keepDebugSymbols += "**/*.so"
        // zl2 构建产物的桥件优先于 maven aar 自带版本（同源配对原则）
        jniLibs.pickFirsts += listOf("**/libbytehook.so", "**/libc++_shared.so")
    }

    androidResources {
        noCompress += listOf("so", "jar", "jsa", "jks")
    }
}

dependencies {
    // ---- AndroidX / Compose（版本均可在 google() 仓库检索） ----
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03") // https://androidx.dev / maven.google.com
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3") // Material 3
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity / Lifecycle / ViewModel / Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Kotlin 协程与 Flow（状态桥）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 偏好持久化（设置项）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 核心 UI 依赖
    implementation("androidx.core:core-ktx:1.13.1")

    // ---- Pojav/FCL 生态桥类（打进 dex，供 libpojavexec 的 JNI_OnLoad 在 ART 侧
    // RegisterNatives 使用——这些类不能只存在于 HotSpot 的 jar classpath，
    // 否则 ART FindClass 失败导致 abort。CallbackBridge.java 已直接放入
    // app 源码树（org.lwjgl.glfw 包，源自 PojavLauncher gladiolus 源码，
    // 声明了 libpojavexec JNI_OnLoad 注册的全部 native 方法）----
    // libbytehook 的 JNI_OnLoad 也需要 dex 上的 ByteHook API 类：
    implementation("com.bytedance:bytehook:1.1.1")

    // ---- 渲染后端运行时依赖（native 侧 dlopen，不参与 Gradle 依赖管理） ----
    //  OpenJDK 21 aarch64     : PojavLauncherTeam/android-openjdk-build-multiarch（Termux 补丁版，JRE 21）
    //  Mesa 24.x (Zink+Turnip): https://gitlab.freedesktop.org/mesa/mesa（scripts/build_mesa.sh 自编译）
    //  gl4es（裁剪保底）       : https://github.com/PojavLauncherTeam/gl4es（scripts/build_gl4es.sh）
    //  OpenAL-Soft 1.24.x     : https://github.com/kcat/openal-soft（scripts/build_openal.sh，AAudio 后端）
    //  mimalloc 2.1.7         : https://github.com/microsoft/mimalloc（CMake FetchContent，见 core/CMakeLists.txt）
}
