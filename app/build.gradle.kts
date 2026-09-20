plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gorillajumping"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.gorillajumping"
        minSdk = 29
        // docs/porting.txt: с targetSdk 30+ брокер виден только через <queries>, они в манифесте есть.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" } // PhoneXR запускает только 64-битные сборки
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }

    // Две версии из одного кода. Сервер и комнаты общие: игроки обеих версий играют вместе.
    flavorDimensions += "edition"
    productFlavors {
        // Обычная: без рекламы.
        create("clean") {
            dimension = "edition"
            buildConfigField("boolean", "ADS", "false")
        }
        // Праздничная: плакаты MUSOR DROP в лесу и своё имя приложения (src/prazdnik).
        create("prazdnik") {
            dimension = "edition"
            applicationId = "gorila.jump.in1fps.po.prazdnikam"
            buildConfigField("boolean", "ADS", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    buildFeatures { prefab = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/gameAssets"))
    androidResources { noCompress += listOf("glb", "mp3", "task") }
    packaging { jniLibs { useLegacyPackaging = false } } // несжатые .so, выровненные под страницы 16 КБ
}

// 3D-модели лежат в корне проекта; копируем их в ассеты при сборке, не трогая оригиналы.
val copyModels by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory) {
        include("the_real_gorilla_tag_map._not_low_polly.glb")
        include("gorilla_tag_gorillas.glb")
        include("all_gorilla_tag_season_cosmetics.glb")
        include("boombox.glb")
    }
    into(layout.buildDirectory.dir("generated/gameAssets/models"))
    rename("the_real_gorilla_tag_map._not_low_polly.glb", "map.glb")
    rename("gorilla_tag_gorillas.glb", "gorillas.glb")
    rename("all_gorilla_tag_season_cosmetics.glb", "cosmetics.glb")
}
tasks.named("preBuild") { dependsOn(copyModels) }

dependencies {
    implementation(project(":sdk"))
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.49")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    // Камера трекинга рук — как в PhoneXR (CameraX).
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    // Мультиплеер: Supabase Realtime по WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
