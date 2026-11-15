plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase 콘솔에서 받은 google-services.json 을 app/ 에 넣으면 Firebase 서버를 쓰고,
// 없으면 빌드는 그대로 되고 앱은 시뮬레이션 서버로 동작한다.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.roadtalk.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.roadtalk.app"
        // Pleos Connect 보안 요건: minSdk 28 이상
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // 차량(arm64)과 에뮬레이터(x86_64)용 코드만 넣는다. 지도 엔진(MapLibre)이 기종마다 13MB 씩이라, 안 쓰는 기종은 뺀다
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // 제출용 APK: 따로 서명 키를 만들지 않고 Android 의 기본 디버그 키로 서명한다 (설치·시연용. 스토어 배포용 키가 아니다).
            // 저장소에 비밀번호나 키 파일을 넣지 않아도 누구나 같은 방법으로 빌드할 수 있다
            signingConfig = signingConfigs.getByName("debug")
            // 코드 줄이기(R8)는 켜지 않는다: Pleos SDK 가 이름으로 찾는 코드가 지워지면 실행 중에 오류가 날 수 있다
            isMinifyEnabled = false
        }
    }

    lint {
        // 릴리스 빌드의 검사가 'Fragment 를 1.3.0 이상으로 올리라' 며 멈춘다. 실제로 들어가는 Fragment 는 1.8.2 라서 해당되지 않는다
        // (지도 · Pleos 라이브러리가 옛 버전을 적어 둔 것을 보고 내는 경고)
        disable += "InvalidFragmentVersionForActivityResult"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.navi.helper)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.geofire.common)
    implementation(libs.maplibre)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
