// The public-API check (binary-compatibility-validator, `apiCheck`) is a development and
// release-qualification gate. JitPack only publishes, and its builds cannot resolve this
// plugin (it is served from Maven Central only), so JitPack (JITPACK=true) builds leave it out.
val apiCheckEnabled = System.getenv("JITPACK") != "true"

buildscript {
    if (System.getenv("JITPACK") != "true") {
        repositories {
            mavenCentral()
        }
        dependencies {
            classpath(libs.binary.compatibility.validator)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

extra["apiCheckEnabled"] = apiCheckEnabled

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}
