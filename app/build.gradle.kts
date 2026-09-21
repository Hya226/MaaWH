import java.util.Properties
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名口令取自仓库根的 keystore.properties（被 .gitignore 忽略，不入库），绝不写明文兜底。
// 该文件只影响 release/beta：缺失时 debug 变体照常构建，只有真要构 release/beta 才明确报错。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else {
    null
}

// 本次命令行是否真要产出 release/beta 包（:app:assembleRelease、assembleBeta、bundleRelease…）。
// 报错若不加这层判断，配置阶段一触发就会把 debug 构建也一起挡掉。
val signingRequested = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':').lowercase()
    (name.contains("release") || name.contains("beta")) &&
        listOf("assemble", "bundle", "install", "package").any { name.startsWith(it) }
}

val keystoreHint = "请在仓库根目录创建 $keystorePropsFile（该文件不入库）：\n" +
    "  storeFile=maawh-release.keystore\n" +
    "  storePassword=<口令>\n" +
    "  keyAlias=<别名>\n" +
    "  keyPassword=<口令>"

fun keystoreProp(key: String): String =
    keystoreProps?.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("$keystorePropsFile 缺少 $key（release/beta 签名必需）。\n$keystoreHint")

android {
    namespace = "com.maawh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maawh.app"
        minSdk = 28          // Shizuku 与目标设备要求
        targetSdk = 34       // 34 以下不受强制 edge-to-edge 影响，骨架阶段最省心
        versionCode = 5
        versionName = "0.2.4"

        // 预编译 MaaFramework 仅提供 arm64；限定 ABI 避免 JNA 打包其他平台
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // 传统打包：.so 解压到 nativeLibraryDir，
            // 保证 MaaFramework 在运行时能按名字 dlopen 各 control unit
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProp("storeFile"))
                storePassword = keystoreProp("storePassword")
                keyAlias = keystoreProp("keyAlias")
                keyPassword = keystoreProp("keyPassword")
            }
        } else if (signingRequested) {
            throw GradleException(
                "缺少 $keystorePropsFile，无法构建 release/beta：签名口令不再写在构建脚本里。\n$keystoreHint"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 缺 keystore.properties 时不挂签名（产物即 -unsigned）；真要构 release 已在上面报错
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
        // 与 release 内容/签名一致但可调试：run-as、抓帧、引擎日志等诊断手段可用。
        // 自己手机装这个排障；分发给别人的用 release。
        create("beta") {
            initWith(getByName("release"))
            isDebuggable = true
            matchingFallbacks += listOf("release")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
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
        aidl = true
    }

    // APK 产物名：MaaWH-<变体>-v<版本>.apk（MaaWH-release-v0.1.0 / MaaWH-beta-v0.1.0 / MaaWH-debug-v0.1.0）
    applicationVariants.all {
        val vName = versionName
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "MaaWH-$name-v$vName.apk"
        }
    }
}

// 打包前把 whmx 任务包同步进 assets（内置资源，首次启动释放到内部存储），
// 并生成 version.txt 版本标记：App 启动时比对，决定是否需要重新释放
// ★ 用 Sync 而不是 Copy：Copy 只覆盖同名文件、**不删**目标里已不存在的文件，于是
//   仓库里退役的 pipeline（如迁移后挪走的 zhengji.json）会一直留在 assets 里，
//   装包后 App 整包重放又把它搬回手机（同名节点在同一个 bundle 里谁生效不确定）。
//   这个目录是构建产物（.gitignore 掉），清干净才是对的。
val syncWhmxAssets = tasks.register<Sync>("syncWhmxAssets") {
    from(rootProject.file("whmx"))
    into(file("src/main/assets/whmx"))
    doLast {
        file("src/main/assets/whmx/version.txt")
            .writeText(System.currentTimeMillis().toString())
    }
}

// 抽卡器者名单正本（gacha/names.json）同步进 assets：随包预置，首装释放到
// files/gacha/names.json（已存在则不动，用户手编不被覆盖）；后台更新名单 =
// 改仓库正本重编译，或 adb 直推手机 files/gacha/names.json
val syncGachaNames = tasks.register<Sync>("syncGachaNames") {
    from(rootProject.file("gacha/names.json"))
    into(file("src/main/assets/gacha"))
}

// 抽卡 OCR 模型同步进 assets：任何新设备/全新安装都能首装自举
// （OnnxPpocrOcr 的 ensureAssetFile 会释放到 files/gacha/ocr_models/）。
// 模型二进制不进 git（.gitignore 掉 assets/gacha/ocr_models/），正本在 gacha/ocr_models/
val syncGachaOcrModels = tasks.register<Sync>("syncGachaOcrModels") {
    from(rootProject.file("gacha/ocr_models"))
    into(file("src/main/assets/gacha/ocr_models"))
}
tasks.named("preBuild") { dependsOn(syncWhmxAssets, syncGachaNames, syncGachaOcrModels) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Shizuku：API 用于绑定与命令执行，provider 用于非 root 场景下的授权通信
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // JNA：加载 libMaaFramework.so 并调用 C API
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // RecyclerView：任务队列列表（长按拖动排序）
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 隐藏 API 绕过（Shizuku 服务内使用 DisplayManager/SurfaceControl 等隐藏接口）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // 抽卡记录本地 OCR：PP-OCR v4 rec ONNX 推理（模型文件不进 APK，放 files/gacha/ocr_models/）
    // ⚠ 版本必须钉在 1.19.2：jniLibs 里 MaaFw 预编译的 libonnxruntime.so 是 1.19.2
    //   （libfastdeploy_ppocr 依赖它，C++ ABI 不兼容其它版本），jniLibs 合并优先级高于
    //   aar——aar 的 JNI 必须与这份运行时同版本，否则 dlopen 报 cannot locate symbol
    //   "OrtGetApiBase"。引擎（启动任务）也会因此挂掉。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
