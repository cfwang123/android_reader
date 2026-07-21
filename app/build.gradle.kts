plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.whj.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whj.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
    }

    // 与 krdict-android 相同：项目根目录 keystore.properties + release.keystore
    // debug / release 共用同一签名 → 可互相覆盖安装且保留数据（书架、进度等）
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val hasReleaseKeystore = keystorePropertiesFile.exists()
    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                val keystoreProperties = Properties().apply {
                    load(keystorePropertiesFile.inputStream())
                }
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 无 keystore 时仍用系统 debug 默认签名
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        viewBinding = true
        buildConfig = true
    }

    // 模型从 assets 内存映射，勿再压缩
    androidResources {
        noCompress += "tflite"
    }

    // MP3（LAME）仅打包 arm64-v8a，其它 ABI 不带 native，运行时回退 M4A
    packaging {
        jniLibs {
            excludes += listOf(
                "lib/armeabi/libandroidlame.so",
                "lib/armeabi-v7a/libandroidlame.so",
                "lib/x86/libandroidlame.so",
                "lib/x86_64/libandroidlame.so",
                "lib/armeabi/libmp3lame.so",
                "lib/armeabi-v7a/libmp3lame.so",
                "lib/x86/libmp3lame.so",
                "lib/x86_64/libmp3lame.so",
            )
        }
    }
}

// release 输出：reader{versionName}.apk
android.applicationVariants.configureEach {
    val vName = versionName
    val isRelease = buildType.name == "release"
    outputs.configureEach {
        if (isRelease) {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "reader${vName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // PDF 文字提取（TTS）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // OCR：PP-OCRv4 mobile TFLite（GPU / CPU）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // MP3 编码（LAME 封装，仅 arm64 so 打入包，见 packaging.excludes）
    // 排除旧 support 库，避免与 AndroidX 冲突
    implementation("com.github.naman14:TAndroidLame:1.1") {
        exclude(group = "com.android.support")
    }
}
