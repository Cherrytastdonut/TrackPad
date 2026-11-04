plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI가 넣어 주는 값 (없으면 로컬 기본값)
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.example.wintrackpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wintrackpad"
        minSdk = 28          // BluetoothHidDevice API는 Android 9(API 28)부터 지원
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "1.1.$ciVersionCode"
    }

    signingConfigs {
        // 고정 배포 키: 키 파일은 저장소에 넣지 않고 CI 시크릿에서 복원한다
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 배포 키가 없으면(로컬 빌드 등) 디버그 키로 서명
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 앱 런타임 외부 라이브러리 없음 (Android 프레임워크 API만 사용)
dependencies {
    testImplementation("junit:junit:4.13.2")
}
