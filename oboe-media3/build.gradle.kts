import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.neuralsound.audio.media3"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":oboe-engine"))
    api(libs.androidx.media3.exoplayer)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "oboe-media3"
                from(components["release"])
                pom {
                    name.set("Neural Sound Oboe Media3 Adapter")
                    description.set("Optional Media3 video synchronization for the Neural Sound Oboe engine.")
                }
            }
        }
        repositories {
            val actor = System.getenv("GITHUB_ACTOR")
            val token = System.getenv("GITHUB_TOKEN")
            if (!actor.isNullOrBlank() && !token.isNullOrBlank()) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/neuralgpt407/andr-oboe-audio-engine")
                    credentials {
                        username = actor
                        password = token
                    }
                }
            }
        }
    }
}
