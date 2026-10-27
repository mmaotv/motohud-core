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
        versionCode = 19
        versionName = "0.0.7"

        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        buildConfigField("String", "AMAP_API_KEY", "\"$amapApiKey\"")

        // ABI 过滤交给下面的 splits（两者不能同时设置）。
        // 高德 SDK 只提供 armeabi-v7a / arm64-v8a；sherpa-onnx 的 so 里还有
        // x86 / x86_64（合计 69MB），靠 splits 的 include 把用不到的 ABI 全部剔掉。
    }

    /**
     * 按 ABI 分包。
     *
     * 引入离线 TTS 后，单包里塞两份 so（高德 20MB + sherpa 30MB）会让 APK 逼近 80MB。
     * 用户设备只可能是其中一种 ABI，另一份纯属陪跑，所以拆成两个包：
     * arm64 给现代手机、armeabi-v7a 给老设备。每个包都只有自己那一份 so。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // 不再额外产出一个 universal 包（那正是我们想避免的大包）
            isUniversalApk = false
        }
    }

    /**
     * 让 so 参与 zip 压缩。
     *
     * AGP 默认把 `.so` 以 STORED（不压缩）方式放进 APK，安装时直接 mmap。
     * 好处是启动快、省安装空间；坏处是 APK 体积巨大 —— 本项目光 so 就有
     * 49.9MB（高德 20MB + onnxruntime 21MB + sherpa 4.6MB），几乎占满一个 APK。
     *
     * 开启后 so 正常参与压缩，APK 能小 20MB 左右；代价是安装时解压到
     * `lib/` 目录（多占一份存储、首次加载略慢）。对"从 GitHub 下载一个包"
     * 这个场景来说，下载体积比安装体积更值得优化。
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

    // 离线 TTS 运行时（sherpa-onnx，Apache-2.0）。
    //
    // AAR 已按 ABI 解出：so 放进 src/main/jniLibs（只留 arm64-v8a / armeabi-v7a，
    // AAR 里 x86/x86_64 那 69MB 直接丢掉），Kotlin/Java API 打成 libs/sherpa-classes.jar。
    // 这样绕开"AGP 不支持直接依赖本地 .aar"的限制，也精确控制了进包的 ABI。
    //
    // 注意：这只是**推理引擎**，中文模型约 27MB 不打进 APK，
    // 由用户在应用内按需下载（见 TtsModelStore）。
    implementation(files("libs/sherpa-classes.jar"))
}
