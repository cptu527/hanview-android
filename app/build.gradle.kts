plugins {
    id("com.android.application")
}

android {
    namespace = "com.hanview.translate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hanview.translate"
        minSdk = 23
        targetSdk = 37
        versionCode = 11
        versionName = "0.5.0"
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
}
