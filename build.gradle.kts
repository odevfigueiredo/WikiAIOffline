plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}
