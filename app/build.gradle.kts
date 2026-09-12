plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maawh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maawh.app"
        minSdk = 28          // Shizuku 与目标设备要求
        targetSdk = 34       // 34 以下不受强制 edge-to-edge 影响，骨架阶段最省心
        versionCode = 1
        versionName = "0.1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

// 打包前把 whmx 任务包同步进 assets（内置资源，首次启动释放到内部存储），
// 并生成 version.txt 版本标记：App 启动时比对，决定是否需要重新释放
val syncWhmxAssets = tasks.register<Copy>("syncWhmxAssets") {
    from(rootProject.file("whmx"))
    into(file("src/main/assets/whmx"))
    doLast {
        file("src/main/assets/whmx/version.txt")
            .writeText(System.currentTimeMillis().toString())
    }
}
tasks.named("preBuild") { dependsOn(syncWhmxAssets) }

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
}
