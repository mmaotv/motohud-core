import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 高德 API Key：在 local.properties 中配置 amap.apiKey=你的Key（该文件不入库）
// 未配置时应用内会引导用户自行申请填写，地图/搜索功能降级，蓝牙投屏导航不受影响。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapApiKey = (localProps.getProperty("amap.apiKey") ?: "").trim()

android {
    namespace = "com.motohud.ble"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.motohud.ble"
        minSdk = 24
        targetSdk = 36
        // 开发期版本号：0.0.1 起，每次只递增最后一位（0.0.2 → 0.0.3 …）。
        // 未正式上线，不体现主版本语义。
        versionCode = 16
        versionName = "0.0.4"

        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        buildConfigField("String", "AMAP_API_KEY", "\"$amapApiKey\"")

        // 高德 SDK 仅提供 armeabi-v7a / arm64-v8a
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 便于直接产出可安装的 Release 包；发布到应用市场请替换为自己的签名
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // 高德 SDK：3D 地图 + 搜索（POI 提示 / 路线规划）+ 定位（地图蓝点依赖）
    // 官方只发布了类 jar（so 与 assets 在 jar 内部），此处使用合并去重后的本地 jar，
    // so 已解出到 src/main/jniLibs，assets 已解出到 src/main/assets。
    implementation(files("libs/amap-sdk-merged.jar"))
}
