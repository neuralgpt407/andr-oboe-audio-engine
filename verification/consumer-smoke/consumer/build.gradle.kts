plugins {
    id("com.android.library")
}

val engineVersion = providers.gradleProperty("engineVersion").orNull
    ?: error("Pass -PengineVersion=<exact candidate version>")

android {
    namespace = "com.neuralsound.audio.consumer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(
        "com.github.neuralgpt407.andr-oboe-audio-engine:oboe-media3:$engineVersion"
    )

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
