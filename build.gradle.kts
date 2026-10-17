// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no
// org.jetbrains.kotlin.android plugin. Declaring the Compose compiler plugin
// here also pins the Kotlin version AGP uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
