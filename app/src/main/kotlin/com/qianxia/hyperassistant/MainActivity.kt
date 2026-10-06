package com.qianxia.hyperassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v3 界面：**完全采用 Kyant0/AndroidLiquidGlass 的 Backdrop Catalog 演示**（Apache-2.0）。
 *
 * 来源：https://github.com/Kyant0/AndroidLiquidGlass  tag `2.0.1`（与已发布 `io.github.kyant0:backdrop:2.0.1` 匹配）
 * 移植方式：把 `app/src/{commonMain,androidMain}` 的 35 个源文件原样搬入本模块，
 *   展平 Kotlin Multiplatform 的 `expect/actual`（保留 Android 实现），
 *   并把 Compose Resources 的壁纸换成 `res/drawable/wallpaper.jpg`（自生成），
 *   移除依赖未提供 SDF 资源的 LockScreen 演示与依赖 material 内部 API 的 Ripple。
 *
 * 我们**额外保留**的部分：崩溃自证（最小崩溃屏），与负一屏动画模块本身无关。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ★ DSH 照 HMC 抄（MainActivity.kt:116-120）：关掉系统"导航栏对比度强制"。
        //   否则透明底栏后面会被系统**自动加一层对比底**（看着就是白/灰块），与 HMC 不一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val crash = remember { readCrash(this@MainActivity) }
            if (crash != null) {
                CrashScreen(crash) {
                    clearCrash(this@MainActivity)
                    recreate()
                }
                return@setContent
            }
            // ★ 修复（2026-10-03 用户报"手机开深色模式就看不见字"）：
            //   本 App 的配色体系是**固定浅色**（卡片 `Color.White`、页底浅灰、正文 `Color.Black`），
            //   而 MIUIx 的主题默认**跟随系统深色** ⇒ 深色模式下 MIUIx 组件（底栏标签、开关、弹窗）
            //   会按深色配色渲染，落在我们的浅色卡片上就是"浅字浅底" ⇒ 看不见。
            // ★ 撤回（2026-10-03）：这里曾被我"锁成浅色主题"来绕过深色模式看不见字的问题 ——
            //   那是**砍功能**，不是修 bug（用户明确否掉："深色模式不可用，直接把他砍了？"）。
            //   正确做法：**照 HMC 抄** —— `AppTheme`（`ThemeController(ColorSchemeMode.System)` +
            //   `MiuixTheme(controller=…)`），页面颜色统一从 `Theme.kt` 的角色取（浅/深两套）。
            AppTheme {
                ModuleSettings()
            }
        }
    }
}

private fun crashFile(a: android.content.Context) = java.io.File(a.filesDir, App.CRASH_FILE)

private fun readCrash(a: android.content.Context): String? = try {
    crashFile(a).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
} catch (t: Throwable) {
    null
}

private fun clearCrash(a: android.content.Context): String? {
    try {
        crashFile(a).delete()
    } catch (ignored: Throwable) {
    }
    return null
}

/** 最小崩溃屏：只用 foundation 最原始组件，不引用任何可疑依赖。 */
@Composable
private fun CrashScreen(crash: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        BasicText(
            "上次启动崩溃（已进入最小安全界面）\n下面是崩溃栈，请截图反馈：",
            style = TextStyle(fontSize = 14.sp)
        )
        Spacer(Modifier.height(10.dp))
        BasicText(crash.take(6000), style = TextStyle(fontSize = 10.sp))
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .clickable { onRetry() }
                .padding(12.dp)
        ) {
            BasicText("［清除崩溃记录并恢复正常界面］", style = TextStyle(fontSize = 14.sp))
        }
    }
}
