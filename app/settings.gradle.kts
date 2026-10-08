pluginManagement {
    // Composite-build checkouts live in the repo root by default (the CI layout).
    // Local builds can point elsewhere with QBM_DEPS_DIR (absolute, or relative
    // to this folder): the directory that contains miuix/ and AndroidLiquidGlass/.
    includeBuild((System.getenv("QBM_DEPS_DIR") ?: "..") + "/miuix/build-plugins")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val depsDir = System.getenv("QBM_DEPS_DIR") ?: ".."
includeBuild("$depsDir/miuix")
includeBuild("$depsDir/AndroidLiquidGlass")

rootProject.name = "QcomBandMenu"
include(":app")
