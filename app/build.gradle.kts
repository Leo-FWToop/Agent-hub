plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tailnet.agenthub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tailnet.agenthub"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.17"
    }

    signingConfigs {
        create("release") {
            // 如果 release.keystore 文件存在，使用自定义签名；否则使用 Debug 签名
            val keystoreFile = rootProject.file("release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "agenthub-v1"
                keyAlias = System.getenv("KEY_ALIAS") ?: "agenthub"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "agenthub-v1"
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩 + 资源收缩：APK 从 ~10MB 减到 ~4MB，
            // 大幅降低慢速下载源下的截断风险
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 如果 release.keystore 存在，使用 release 签名；否则使用 Debug 签名
            signingConfig = if (rootProject.file("release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
