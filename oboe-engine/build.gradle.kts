import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.neuralsound.audio"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildFeatures {
            prefab = true
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.oboe)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val nativeSymbols by tasks.registering(Zip::class) {
    dependsOn("assembleRelease")
    archiveClassifier.set("native-symbols")
    from(layout.buildDirectory.dir("intermediates/cxx/RelWithDebInfo")) {
        include("**/obj/arm64-v8a/*.so")
        include("**/obj/armeabi-v7a/*.so")
        includeEmptyDirs = false
        eachFile {
            val segments = relativePath.segments
            val objIndex = segments.indexOf("obj")
            if (objIndex >= 0 && segments.size > objIndex + 2) {
                relativePath = RelativePath(
                    true,
                    segments[objIndex + 1],
                    segments.last(),
                )
            }
        }
    }
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("META-INF/**")
    }
}

tasks.withType<Jar>().configureEach {
    if (name.contains("source", ignoreCase = true)) {
        from(layout.projectDirectory.dir("src/main/resources")) {
            include("META-INF/**")
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "oboe-engine"
                from(components["release"])
                artifact(nativeSymbols)
                pom {
                    name.set("Neural Sound Oboe Engine")
                    description.set("Low-latency Android multitrack playback and recording engine.")
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
