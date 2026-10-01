// 根构建脚本：统一插件版本
// 版本来源：
//   AGP    https://dl.google.com/dl/android/maven2/ （google() 仓库）
//   Kotlin https://repo1.maven.org/maven2/          （mavenCentral() 仓库）
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose 编译器插件（Kotlin 2.x 起 Compose 编译器随 Kotlin 一起发布，来源 mavenCentral）
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
