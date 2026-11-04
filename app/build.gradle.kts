plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wintrackpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wintrackpad"
        minSdk = 28          // BluetoothHidDevice API는 Android 9(API 28)부터 지원
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인용 사이드로드 편의를 위해 디버그 키로 서명
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 외부 라이브러리 없음 (Android 프레임워크 API만 사용)
