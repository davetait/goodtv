// Top-level build file. Plugins are declared here (not applied) so modules share one version.
plugins {
    // Not applied (AGP 9 compiles Kotlin itself); present only so KGP 2.4.x is on the classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
