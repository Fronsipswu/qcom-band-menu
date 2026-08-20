plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.qcom.bandmenu"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.qcom.bandmenu"
        minSdk = 30
        targetSdk = 37
        versionCode = 53
        versionName = "4.3.6"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "Instantiatable"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/kotlin/**"
            excludes += "META-INF/versions/**"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Compose UI
    implementation(libs.androidx.compose.ui)

    // Miuix (local composite build)
    implementation("top.yukonga.miuix.kmp:miuix-ui")
    implementation("top.yukonga.miuix.kmp:miuix-preference")
    implementation("top.yukonga.miuix.kmp:miuix-icons")

    // Backdrop / Liquid glass (local composite build)
    implementation("io.github.kyant0:backdrop")

    // libsu — root shell (for launching daemon)
    val libsuVersion = "6.0.0"
    implementation("com.github.topjohnwu.libsu:core:$libsuVersion")

    // Tests
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
