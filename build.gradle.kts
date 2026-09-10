buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin (2.2.10). The AndroidX and Compose
        // artifacts this app uses carry 2.4 metadata, which a 2.2 compiler
        // cannot read, so the toolchain is lifted here rather than pinning
        // every library back to an older release.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
}
