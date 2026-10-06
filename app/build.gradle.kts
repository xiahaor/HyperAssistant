plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.qianxia.hyperassistant"
    compileSdk = 37
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "com.qianxia.HyperAssistant"; minSdk = 33; targetSdk = 37; versionCode = 400007; versionName = "4.0.0-alpha7" }
    /*
     * ★★ 为什么 source=1.8 而 target=17（本机环境补丁，不改任何功能）★★
     *
     * 背景：v3 这次加了 Java 源文件（hook 侧）之后，`compileReleaseJavaWithJavac` 第一次
     * 真正有源码要编。AGP 在 `sourceCompatibility >= 9` 时会注入 `androidJdkImage` 变换，
     * 它需要 `$SDK/platforms/android-37/core-for-system-modules.jar`
     * —— 本机这份 android-37 平台目录里**没有这个文件**（android-35/36 都有），于是报
     *   JdkImageTransform: NoSuchFileException: .../android-37/core-for-system-modules.jar
     *
     * 处置（硬约束：只许改 v3，不许去补 SDK 目录）：把 **source** 降到 1.8，
     * 让 AGP 走"不用 JDK image"的分支；**target 保持 17**，这样
     *   · Kotlin 的 jvmTarget 仍由 targetCompatibility 推出 17 ⇒ Compose/UI 的字节码与改动前完全一致；
     *   · 只有我们新加的 Java（hook 侧）以 `-source 8 -target 17` 编译，
     *     而我们的 Java 代码本来就是 Java 8 语法（全部用匿名内部类，没有 lambda/var/record）。
     *
     * 影响面：Javac 的 java.* 平台类改由本机 JDK 17 提供（Android 的 java.* 本来也是这样取的），
     * android.jar 依旧在 classpath 上，Android API 解析不变；不影响 dex / 打包 / 签名。
     */
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module") }

    // ★ P1 新增：release 必须有签名才装得上机（原来是 app-release-unsigned.apk）。
    //   用标准 debug keystore 签名，但 buildType 仍是 release ⇒ android:debuggable=false，
    //   即「release（非 debuggable）」这条硬约束不受影响。
    // ★ 开源版：签名**不写死**。密钥路径从环境变量或 gradle.properties 读：
    //     HA_KEYSTORE / HA_KEYSTORE_PASSWORD / HA_KEY_ALIAS / HA_KEY_PASSWORD
    //   读不到 ⇒ 不配签名 ⇒ 产出 `app-release-unsigned.apk`，
    //   **别人 clone 下来无需任何私钥即可编译**（自己装的话用 apksigner 再签一次即可）。
    signingConfigs {
        create("release") {
            val ks = (System.getenv("HA_KEYSTORE") ?: providers.gradleProperty("HA_KEYSTORE").orNull)
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = (System.getenv("HA_KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("HA_KEYSTORE_PASSWORD").orNull) ?: "android"
                keyAlias = (System.getenv("HA_KEY_ALIAS")
                    ?: providers.gradleProperty("HA_KEY_ALIAS").orNull) ?: "androiddebugkey"
                keyPassword = (System.getenv("HA_KEY_PASSWORD")
                    ?: providers.gradleProperty("HA_KEY_PASSWORD").orNull) ?: storePassword
            }
        }
    }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    // ★ MIUIx（GitHub: compose-miuix-ui/miuix，Apache-2.0）
    implementation("org.jetbrains.compose.animation:animation:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    // ★ 液态玻璃（MIUIx blur）+ 图标（底栏用）
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // ★ Kyant0/AndroidLiquidGlass = "Backdrop"（Apache-2.0）—— 用户指定的液态玻璃
    implementation("io.github.kyant0:backdrop-android:2.0.1")
    // ★ 连续曲率圆角（squircle）：Kyant0/AndroidLiquidGlass 的形状库（Apache-2.0）
    implementation("io.github.kyant0:shapes-android:1.2.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    // ★ P1 新增（唯一的新依赖，任务书明确允许）：libxposed API 102。
    //   - 来源：libxposed 官方发布（与 v2 真机跑通的是同一份）
    //   - 已复制进 app/libs/（md5 cc70ba385bf8e188310cdd19a812f19f），APK 自包含、不依赖外部路径
    //   - compileOnly：框架运行期自带这些类，我们的 dex 里**不该**再打一份
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
}
android {
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val ks = (System.getenv("HA_KEYSTORE") ?: providers.gradleProperty("HA_KEYSTORE").orNull)
            if (ks != null && file(ks).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
