plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dx9901mk5.pixelvoiceime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dx9901mk5.pixelvoiceime"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "3.4.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(rootProject.extra["MYAPP_RELEASE_STORE_FILE"] as String)
            storePassword = rootProject.extra["MYAPP_RELEASE_STORE_PASSWORD"] as String
            keyAlias = rootProject.extra["MYAPP_RELEASE_KEY_ALIAS"] as String
            keyPassword = rootProject.extra["MYAPP_RELEASE_KEY_PASSWORD"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.commons.compress)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.window:window:1.3.0")
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    debugImplementation(libs.androidx.ui.tooling)
}
