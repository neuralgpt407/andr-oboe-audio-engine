pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localRepository = providers.gradleProperty("localRepository").orNull
    ?: error("Pass -PlocalRepository=<absolute local Maven repository>")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "qualifiedCandidate"
            url = uri(localRepository)
            content {
                includeGroup("com.github.neuralgpt407.andr-oboe-audio-engine")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "oboe-release-consumer-smoke"
include(":consumer")
