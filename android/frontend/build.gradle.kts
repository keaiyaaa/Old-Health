// 根构建脚本：只声明插件，不 apply。
// 版本唯一来源是 gradle/libs.versions.toml。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
