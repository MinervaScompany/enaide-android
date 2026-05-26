// Root build file. Le configurazioni concrete stanno nei submoduli.
// I plugin sono dichiarati qui solo per applicazione condivisa (apply false).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
