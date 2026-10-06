/*
 * HyperAssistant：流光溢彩背景所需的两个小工具。
 *
 * 背景着色器本体（BgEffect*.kt / OS2·OS3BgFrag.kt）来自 compose-miuix-ui / HyperNavBar，
 * 许可为 Apache License 2.0（源文件头部保留了原始版权声明），此处仅补上本项目自己的两个查询函数。
 */
package com.qianxia.hyperassistant.effect

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 是否深色（与系统一致；本 App 的页面底色是固定的浅色体系，但流光背景要跟随系统深浅取预设）。 */
@Composable
internal fun isInDarkTheme(): Boolean = isSystemInDarkTheme()

/** 是否分屏/大屏布局：手机上一律 false（流光背景按手机预设渲染）。 */
@Composable
internal fun shouldShowSplitPane(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp >= 600 &&
        cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
}
