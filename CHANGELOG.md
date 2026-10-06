# 更新日志

本项目的前身是同一作者从 v1 起逐步演进的一套负一屏动画实验（v1 → v2 → v3），
当前主线是下面的 **HyperAssistant（v4）**：把主线逻辑与 libxposed API 102、Compose UI 合并为一。

## 4.0.0-alpha7

- 关于页底部留白修正（`contentPadding` 与尾部 `Spacer` 叠加导致空隙过大）
- 递增版本号并同步 UI / hook 侧版本串

## 4.0.0-alpha6

- 作者行改用**本地头像**（不再联网，移除 `INTERNET` 权限）
- 修复「下拉没回弹」：交回 Compose 自带拉伸回弹
- 关于页流光背景**下滑渐隐为白**

## 4.0.0-alpha5

- 自适应图标按**安全区 61.1%** 重做（修"图标内容被裁"）
- 关于页去掉大标题、图标间距对齐参考实现（188dp vs 192dp）

## 4.0.0-alpha4

- 更名 **HyperAssistant**、包名 `com.qianxia.HyperAssistant`（全量迁移 Java/Kotlin 包名与广播动作名）
- 新图标（关于页用无字版、应用图标用带字版）
- 关于页改为分组卡片 + 作者行（照 HyperCeiler 的形态）

## 4.0.0-alpha3

- 应用名 / 版本串四处同步（清单 label、strings.xml、UI 常量、module.prop）

## 4.0.0-alpha2 · alpha1

- 首次合并：主线（alpha 归零）逻辑 **31 个 java 零改动** + API 82 兼容垫片 + **libxposed API 102** 入口 + Compose/MIUIx UI
- 运行期配置打通（20 个开关去 `final` + 广播 + 落盘）
- 关于页「流光溢彩」背景（AGSL 运行期着色器，画在根布局）
- 模块激活状态链路接通（PING/PONG 心跳）
