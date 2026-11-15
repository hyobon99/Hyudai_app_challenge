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
