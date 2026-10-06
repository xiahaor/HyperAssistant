/*
 * 照抄 HyperMusicCover（HMC）的深色模式做法：
 *   - `ui/theme/Theme.kt:23-49`   → AppTheme（ThemeController(ColorSchemeMode.System) + MiuixTheme(controller=…)）
 *   - `ui/util/BlurUtils.kt:78-83` → isInDarkTheme()（按主题 surface 的**相对亮度**判深浅，而不是只看系统开关）
 * 两处均为 Apache-2.0 文件，仅改包名/去掉本项目不用的部分。
 *
 * 为什么必须这样：本 App 的卡片/页底原来是**硬编码浅色**（Color.White / 0xFFF2F2F2），
 * 而正文色却跟随系统深浅 ⇒ 深色模式下"白字白底"看不见。正确做法是
 * **文字色与底色都从同一处（本文件）取**，深浅两套一起变。
 */
package com.qianxia.hyperassistant

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题（照抄 HMC `ui/theme/Theme.kt`）。
 * 可用模式：`System / Light / Dark / MonetSystem / MonetLight / MonetDark`，默认**跟随系统**。
 */
@Composable
fun AppTheme(
    themeMode: ColorSchemeMode = ColorSchemeMode.System,
    content: @Composable () -> Unit
) {
    val controller = remember(themeMode) { ThemeController(themeMode) }
    MiuixTheme(
        controller = controller,
        content = {
            // enableEdgeToEdge() 的 SystemBarStyle.auto 只跟随系统深浅，
            // 应用内改成 Light/Dark 时状态栏图标颜色不会同步 ⇒ 这里按**实际主题**动态设置。
            val isDark = isInDarkTheme()
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                }
            }
            content()
        }
    )
}

/** 是否深色主题（照抄 HMC `ui/util/BlurUtils.kt:78-83`：按 surface 相对亮度判）。 */
@Composable
fun isInDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    return luminance < 0.5f
}

// ---------------------------------------------------------------- 本 App 的两套配色
// 页面**只引用角色名**，不再散落硬编码颜色 ⇒ 深浅两套一起变，不会再出现"白字白底"。

/** 页面底色。 */
@Composable
fun pageBg(): Color = if (isInDarkTheme()) Color(0xFF121212) else Color(0xFFF2F2F2)

/** 卡片底色。 */
@Composable
fun cardBg(): Color = if (isInDarkTheme()) Color(0xFF1C1C1E) else Color.White

/** 状态卡"激活"底色（深色用暗绿，浅色用浅绿）。 */
@Composable
fun okBg(): Color = if (isInDarkTheme()) Color(0xFF14331F) else Color(0xFFDFFAE4)

/** 状态卡"未激活"底色（深色用暗红，浅色用浅红）。 */
@Composable
fun badBg(): Color = if (isInDarkTheme()) Color(0xFF3A1B1B) else Color(0xFFFDE8E8)

/** 正文/主文字色（与底色同源 ⇒ 深色白字、浅色黑字）。 */
@Composable
fun fgColor(): Color = if (isInDarkTheme()) Color.White else Color.Black
