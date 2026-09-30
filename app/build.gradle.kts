plugins {
    id("com.android.application")
}

android {
    namespace = "com.hanview.translate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.viewnyang.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 65
        versionName = "0.8.4"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("VIEWNYANG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VIEWNYANG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VIEWNYANG_KEY_ALIAS")
                keyPassword = System.getenv("VIEWNYANG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
