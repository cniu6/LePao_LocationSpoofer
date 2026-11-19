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

// core-geo 用 jvmToolchain(17)：foojay-resolver 让 Gradle 在任意机器/CI 自动下载所需 JDK，
// 不再依赖入库的机器专属 org.gradle.java.installations.paths。settings plugins 块须在 pluginManagement 之后。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "R7VqzN"
include(":app")
include(":app-ui")
include(":service")
include(":xposed")
include(":core-data")
include(":core-geo")
