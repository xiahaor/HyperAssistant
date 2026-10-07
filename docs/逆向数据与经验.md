# HyperAssistant · 逆向数据与经验参考

> 本文汇集本项目（HyperOS 负一屏过渡动画模块 · HyperAssistant）里**摸到的接口/结构数据**与**做出来才明白的经验**。
>
> **阅读方式（重要，别当圣经）**：
> - 所有签名/常量/数值都注明**来源**：`javap 反编译` / `真机实测` / `截图测量` / `源码:行号`。
> - **标 ⚠️ 的是"只在当前版本/当前设备上成立"**，换 ROM、换库版本、换设备都要重新验一次。
> - 标 ❓ 的是**证据不足或尚未验证**的，别当结论用。
> - 本文**不追求完备**，也不替代现场验证；它只是把"已经花过时间的地方"记下来，避免重复踩。
>
> 设备基准：REDMI K90 Pro Max（`myron`）· HyperOS `OS4.0.0.33.XPMCNXM`（Android 17 / SDK 37）· 1200×2608 @480dpi（3.0x）· SukiSU root · LSPosed 2.x

---

## 0. 一句话背景

给「负一屏」做打开/返回过渡动画：**不自己画动画**，而是 hook 系统自己的 **HyperViewScale（HVS / SurfaceFlinger 过渡）** 引擎，复用它的曲线与圆角；再由 App 提供可运行期调节的开关。

工程分两条线：
- **hook 侧**（跑在负一屏 / system_server / SystemUI 里）—— 逻辑与被逆向数据有关
- **App 侧**（Compose UI，负责开关、状态、日志）—— 与 MIUIx/Compose 数据有关

---

## 1. libxposed API 102（hook 框架）

### 1.1 入口与生命周期

`javap -classpath libxposed-api-102.0.0.jar` 实测：

```
XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface
XposedModuleInterface（全部是 default 空实现 ⇒ **不覆写就不会被调用**）
    void onModuleLoaded(ModuleLoadedParam)
    void onPackageLoaded(PackageLoadedParam)
    void onPackageReady(PackageReadyParam)
    void onSystemServerStarting(SystemServerStartingParam)
    boolean onHotReloading(HotReloadingParam)
    void onHotReloaded(HotReloadedParam)
```

参数能拿到什么（**容易踩的点**）：

| 参数类型 | 方法 | 备注 |
|---|---|---|
| `ModuleLoadedParam` | `isSystemServer()` · `getProcessName()` | 每个进程都会来一次，适合放"进程级上下文注入" |
| `PackageLoadedParam` | `getPackageName()` · `getApplicationInfo()` · `isFirstPackage()` · **`getDefaultClassLoader()`** | ⚠️ **没有 `getClassLoader()`**，写错直接编译报"找不到符号" |
| `PackageReadyParam extends PackageLoadedParam` | 额外 `getClassLoader()` · `getAppComponentFactory()` | 比 Loaded 晚；适合"再试一次"（Application 可能这时才建好） |
| `SystemServerStartingParam` | `getClassLoader()` | system_server 的入口在这里（**不是**靠 packageName 判断） |

> 经验：`onPackageLoaded` 与 `onPackageReady` **都挂一遍**是常见做法 —— 前者早、后者稳；用幂等闸门（按 ClassLoader 记已处理）避免重复安装。

### 1.2 挂钩 API

```
XposedInterface:
    HookBuilder hook(Executable)
    HookBuilder hookClassInitializer(Class)
    Invoker<...> getInvoker(Method) / CtorInvoker<T> getInvoker(Constructor<T>)
    void log(int, String, String[, Throwable])

HookBuilder: setPriority(int) · setExceptionMode(ExceptionMode) · setId(String) · intercept(Hooker)
Hooker:      Object intercept(Chain) throws Throwable
Chain:       getExecutable() · getThisObject() · getArgs():List · getArg(int)
             proceed() · proceed(Object[]) · proceedWith(Object) · proceedWith(Object, Object[])
```

⚠️ **`Chain` 没有 `setArgs(...)`** —— 想改参数只能把新数组喂给 `proceed(Object[])`；想跳过原方法就直接 `return`（或 `proceedWith`）。

### 1.3 模块元数据

```
app/src/main/resources/META-INF/xposed/module.prop     # minApiVersion/targetApiVersion/autoHotReload/staticScope/description
app/src/main/resources/META-INF/xposed/java_init.list  # 入口类**全名**，一行
app/src/main/resources/META-INF/xposed/scope.list      # 默认作用域（可被用户在 LSPosed 里改）
app/src/main/assets/xposed_init                        # 兼容用，同样写入口类全名
```

### 1.4 与 API 82 共存的一个做法（本项目采用）

需求是"把一整套 API 82 写的逻辑迁到 API 102，但别重写 92 处 `XposedHelpers` 调用"。
做法：**在自己的 APK 里实现一套 `de.robv.android.xposed.*`**（`XposedBridge` / `XposedHelpers` / `XC_MethodHook` / `IXposedHookLoadPackage` / `callbacks.XC_LoadPackage`），架在 API 102 之上：

- `XposedHelpers` 那几个方法**本来就是反射包装**（`findClass/callMethod/callStaticMethod/getObjectField/*Field`），自己实现很轻；
- `XC_MethodHook` 要额外开**公开入口**（`v4CallBefore/v4CallAfter` + `MethodHookParam` 的公开存取）—— 因为真的 API82 里 `before/afterHookedMethod` 是 `protected`、由**同包**的 `XposedBridge` 调用，而我们的适配器在别的包；
- 适配器把 `before/setResult/returnEarly` 语义映射到 `Chain`：`before` 里 `setResult` ⇒ 跳过原方法；`proceed` 前**必须复位** early/result，否则分不清是"before 想跳过"还是"after 想改返回值"。

> 结果：31 个业务文件**一行未改**（只改 import 都不用），只在入口做转发。

---

## 2. LSPosed 侧（部署相关）

### 2.1 数据库结构（改模块列表时用得到）

```
/data/adb/lspd/config/modules_config.db
    modules(module_pkg_name, apk_path)
    modules_state(module_pkg_name, user_id, enabled, scope_request_blocked)
    scope(module_pkg_name, app_pkg_name, user_id)
```

- **system_server 的作用域字符串是 `system`**（不是 `android`）。
- 改完这三张表**要重启**才生效；直接改 db 时记得清 `-wal/-shm` 并 `chown root:root` + `600`。

### 2.2 ⚠️ "开机早期模块读不到" —— **早期结论已被实测推翻**（2026-10-07 更正）

**早期判断（错的）**：以为必须把 APK 复制到 `/data/adb/…` 并把 `modules.apk_path` 指过去，
理由是"`/data/app` 在 CE 存储解锁前读不到，而 system_server 最早 fork ⇒ 错过模块"。

**实测反转（干净对照）**：
```
① 卸载 → 干净安装（普通 APK，路径 /data/app/~~xxxx==/…/base.apk）
② 把 db 的 apk_path 指向【当前真实路径】（不拷 /data/adb）
③ 删掉 /data/adb/dshmod/ 整个目录（排除副本干扰）→ 重启
④ 结果：(system)[…] hookAll OK = 8 条，Unable to open = 0 条
⑤ 端到端：点负一屏卡片 ⇒ 启动被接受(剥离级别 0/5) 1 次、被拒 0 次、setMaskSurface 命中 1 次
```
⇒ **直接安装可用**（普通安装 → LSPosed 启用 + 勾作用域 → 重启），不需要 `/data/adb` 那一步。

**那当初为什么会失败？** 报错是
`Unable to open /data/app/…/base.apk: **No such file** or directory` ——
是"**文件不存在**"，不是权限/加密错误。而**每次 `adb install -r` 都会换一个 `/data/app/~~xxxx==/` 目录**
⇒ 真正的原因是**数据库里的 `apk_path` 过期**（指向了上一次安装的旧目录），
被误诊成"开机读不到"。补充两条旁证：① `/data/app` 属于 **DE 存储**，未解锁即可读；
② 其它模块（如 HyperCeiler）同样是普通安装，且能正常注入 system_server。

**因此现在的正确做法/排错法**：
- 安装：普通安装 → LSPosed 启用 + 勾作用域 → 重启
- **重装 APK 之后**如果 system_server 侧没有 `hookAll OK`（动画不生效），
  先在 LSPosed 里**把模块关掉再打开一次**（让它重新记录当前路径），再重启。
- `/data/adb` 副本与 Magisk 模块形态仍然可用，但属于**可选**，不是必需。

### 2.3 纯 UI 改动要不要重启？

不需要（hook 侧代码没变）。但 LSPosed 会因为"作用域含系统框架"弹一句"模块已更新，需要重新启动"——那是提示，不是要求。

---

## 3. MIUIx（`top.yukonga.miuix.kmp`）与 Compose

### 3.1 RuntimeShader / AGSL

```
top.yukonga.miuix.kmp.blur.RuntimeShader(String)      # 从 AGSL 源码建着色器
top.yukonga.miuix.kmp.blur.asBrush()                  # → Compose Brush（可直接 drawRect）
top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()  # javap 实测 = Build.VERSION.SDK_INT >= 33
```

> 反编译实证：`isRuntimeShaderSupported()` 就是 `SDK_INT >= 33` 两个分支，没有别的条件。

**流光溢彩着色器的 uniform 表**（来自 compose-miuix-ui / HyperNavBar，Apache-2.0）：

```
uniform vec2  uResolution;
uniform float uAnimTime;
uniform vec4  uBound;              // xy=起点(归一化) zw=尺寸
uniform float uTranslateY;
uniform vec3  uPoints[4];          // 每个点 xy + 半径 z
uniform vec2  uPointsAnim[4];      // 抖动后的位置
uniform vec4  uColors[4];          // 4 个颜色（含 alpha）
uniform float uAlphaMulti, uNoiseScale, uPointRadiusMulti, uSaturateOffset, uLightOffset;
```
着色器输出是**预乘 alpha**（`vec4(rgb*a, a)`），并且 `color.rgb /= color.a` —— 当 `a→0` 时是除零，靠后续 `clamp` 兜着；**所以别在 alpha 恒 0 的区域期待有意义颜色**。

### 3.2 ⚠️ 一个非常反直觉的坑：**把 RuntimeShader 画在 pager 页里会完全看不见**

- 现象：在 `HorizontalPager` 的某页里画着色器，**纯色矩形（`drawRect(color)`）看得见**，**着色器（`drawRect(brush)`）完全看不见**，且日志无任何报错、`draw()` 确实被调用、`RuntimeShader` 创建成功。
- 解释（当时的判断）：pager 页的绘制坐标系带横向平移，纯色不受影响、依赖坐标的着色器被画到了屏幕外。❓（未进一步证实到 Skia 层）
- **可用做法**：把全屏类效果**画在根布局**，用"离目标页的距离"做 alpha 渐入渐出：
  ```kotlin
  val frac = pagerState.currentPage + pagerState.currentPageOffsetFraction
  val alpha = (1f - abs(frac - targetPage)).coerceIn(0f, 1f)
  BgEffectBackground(dynamicBackground = alpha > 0.01f, alpha = { alpha }) { }
  ```
  顺带把"每帧重绘"限制在可见时（省电）。

### 3.3 阻尼 overscroll（MIUIx）

```
top.yukonga.miuix.kmp.utils.overScrollVertical(enabled = true, isAtTop = { ... })  // Modifier 扩展
top.yukonga.miuix.kmp.utils.overScrollHorizontal(...) / overScrollOutOfBound(...)
```
- 参考实现（HyperMusicCover `ui/util/BlurUtils.kt:130-136`）把它和 `overscrollEffect = null` **成对**使用，注释明确：`overScrollVertical` 会**取代** Compose 自带的拉伸，不成对会"两套在端点打架"。
- ❓ **本项目的实测反例**：把 `overScrollVertical()` 挂在"页面 LazyColumn"上（该 LazyColumn 位于 `HorizontalPager` 内），按住拖动**位移为 0**，没有回弹。改回 Compose 自带拉伸（列表不传 `overscrollEffect`）后，按住拖动位移 ≈ 216px，松手回弹。
- 结论（**当前阶段**）：**"某个 modifier 在某个层级是否生效"必须实测**；不要因为"上游这么写"就认为在本项目也成立。

### 3.4 其它零碎但省时间的点

- `BasicText`（Compose 原生）**没有 `textAlign` 参数**，对齐要写进 `TextStyle(textAlign = ...)`。
- `Modifier.weight(1f)` 在 `Row` 里正常，但**父容器宽度必须先确定**；把"内部强制 `fillMaxWidth()` 的自定义卡片组件"当按钮用，会把同行的标签挤成一列字。
- `LazyColumn` 的 **item 内部不要用 `fillMaxSize()`**：单 item 会被撑满视口，内容下方凭空多出空白（本项目"底部空缺"的一半原因）。

---

## 4. 图标 / 素材处理

### 4.1 自适应图标（Android 8+）三组数字

```
画布       108dp
安全区      66dp  （= 61.1%）  ← 内容**必须**放这里，否则可能被裁
掩码可见    最多 72dp
```
- 把"一张已经是完整图标"的图当**前景**用时，**只能按安全区缩放**（≈61%），否则四边（尤其底部文字）会被切掉。
- 前景与背景同色时，遮罩边缘看不出来 —— 所以"整图标当背景色 + 内容当前景"是可行的简化做法。
- 清单里没有 `android:icon` 时用的是系统默认图标（很容易被忽略）。

### 4.2 用户给的素材常见三个坑

1. **JPEG 没有 alpha** ⇒ 看到的"透明棋盘格"其实是**画进像素里的**；
2. **带生成水印**（如右下角"XX生成"），往往在主体之外 ⇒ **裁到主体包围盒**即可切掉；
3. 裁切基准要用**主体包围盒**，不要用"alpha 包围盒"——外部残留（水印/阴影）会把 bbox 撑大。

去背景（当背景是浅色棋盘格/纯白时）可用：**从四边对"很亮且低饱和"的像素做洪泛**，只吃与边界连通的部分 ⇒ 圆角外变透明，而**主体内部的白色内容不受影响**（它不与边界连通）。

---

## 5. 负一屏 / HyperViewScale（HVS）—— 本项目主体

> 这一节是"读反编译 + 真机日志"得来的结构数据；**类名/方法名是实证**，语义推断部分已标注。

- 负一屏不是独立 Activity：它是**桌面进程里的 overlay 窗口**（`topResumedActivity` 仍是 `com.miui.home`），内容由 `com.miui.personalassistant` 的 `AssistantOverlayWindow` 提供。
- HVS 相关（`android.app.hyperviewscale.*` 与 MIUI 的 remote 封装）：
  ```
  HyperViewScaleRemoteManager : getCurrentBundle · handleSystemStart · setScaleView · setMaskSurface · requestStart...
  HyperViewScaleRemoteTransition
  HyperViewScaleTransitionImpl : isHyperViewScaleRemote · requestViewScaleRemoteClose · requestCloseTransitionIfNeeded · requestStartTransition
  HyperViewScaleConfigManager  : preBindApplication · init · isHyperViewScaleAllowedUid
  HyperViewScaleRemoteStub     （android.app.hyperviewscale.*）
  ```
- **权限门槛**：非系统 uid 在 `startActivity` 时若带 `remoteTransition`，会被
  `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` 卡住（日志形如
  `Permission Denial: starting Intent { ... } from ProcessRecord{... personalassistant/u0a143 (pid=..., uid=10143) with remoteTransition`）。
  ⇒ 放行点要么是权限、要么是 `isHyperViewScaleAllowedUid` 之类的判定；**两种都要在 system_server 侧做**。
  ⚠️ 注意 uid 不一定是 1000/1000 系的常规值（本机 `com.android.systemui`=10227、`com.miui.personalassistant`=10143）——**别用 uid==1000 当"是不是系统"的判据**。
- WMShell（`/system_ext/framework/Miui-WindowManager-Shell.jar`）里：
  ```
  Transitions#dispatchTransition(IBinder, TransitionInfo, Transaction, Transaction, TransitionFinishCallback, TransitionHandler): TransitionHandler
  RecentsTransitionHandler#startAnimation(IBinder, TransitionInfo, Transaction, Transaction, Transitions$TransitionFinishCallback): boolean
  ```
  ⚠️ `startAnimation` 返回 **boolean** —— hook 里返回 `null` 会炸
  `Attempt to invoke virtual method 'boolean Boolean.booleanValue()' on a null`（本项目真实踩过）。
- mask 面的两条路（本项目历史结论，**属于观察不是定理**）：
  - 把铺色**收窄到 viewRect** ⇒ 卡片处**露出应用实时内容**；
  - 铺**整块** ⇒ 整屏变成组件的主色（"色块"）。
  - 另有一条独立观察：MIUI 的 mask **约 168ms 就把 alpha 1→0 淡完**，之后底下"还只有组件那么大"的应用窗口就会露出来。
  ⇒ 于是长期存在"**无色块 vs 不露内容**"的取舍；桌面之所以没这问题，是因为它**同时驱动两层**（应用 leash + 不淡出的 FloatingIcon 源快照层）。❓ 仍未在本项目内做成。

---

## 6. 参考实现取到的数据

### 6.1 HyperMusicCover（`zyl6932/HyperMusicCover`）

- **许可**：仓库 AGPL-3.0；但其中"关于页构图/背景效果"相关文件头部写着
  `Adapted from HyperNavBar ... Apache-2.0` 与 `SPDX-License-Identifier: Apache-2.0`
  ⇒ **背景着色器那几个文件本身是 Apache-2.0 的**；其余部分仍是 AGPL，**只参考版式、别直接搬代码**。
- **关于页构图**（`ui/screen/about/AboutPage.kt`）：顶部居中 **100dp 圆角图标** + **加粗应用名（35sp）** + **版本号（14sp，可点）**，
  下面几组 `ArrowPreference` 卡片（更新 / 链接 / 许可·依赖·致谢）；
  logo 区位置 ≈ `top + 52dp`（其 `logoPadding` 还带 `extraTop = 40dp`）。
  真机截图测量（1200×2608@3.0x）：**图标顶边约 192dp（含状态栏）**。
- **滚动渐隐**：背景效果 `alpha = { 1f - scrollProgress }`；图标/名称/版本各自按不同进度区间淡出。
- **下拉回弹配方**：见 §3.3。
- 其页面 `LazyColumn` 常带 `overscrollEffect = null`（与 §3.3 成对）。

### 6.2 HyperCeiler（`ReChronoRain/HyperCeiler`）

- **关于页结构**：XML 骨架（`about_settings.xml` 等）+ 代码往 `prefs_container` 里**动态塞 Preference**（`about/AboutSettingsFragment.java`）。
- **"作者"行**：`mAuthor.setTitle(name ?: "Sevtinge")` + `setIcon(BitmapDrawable)` —— 头像按 **user id + size** 拉、名字走 API，拿不到就用兜底值。
  ⇒ 可复用的形态：**头像 + 名字 + `›`，整行可点跳转**（拿不到头像时用一个稳定占位图，别留空圆）。
- **流光背景**：其 `about/controller/BgEffect*.java` 与 `res/layout/layout_effect_bg.xml`（后者只是个空容器，效果在代码里）。
- ⚠️ 该仓库同样 **AGPL-3.0** —— 参考做法可以，抄代码要按 AGPL 处理。

---

## 7. 工程与验证经验

### 7.1 构建 / 装机

- **构建自检**：命令里的工具链路径**用完整绝对路径**，且构建后核对
  **产物的 mtime 与 MD5 是否变化** —— 这是"构建到底跑没跑"的唯一可靠信号。
  ⚠️ 真实事故：把路径写少一层 + 用 `| grep` 过滤输出 + `&&` 串起装机 ⇒ 失败被吞、**装的是旧包**，
  现象是"改了半天没变化"。识别信号就是 **MD5 与上一版一字不差**。
- **装机三步**（决定"到底生不生效"）：
  1. `adb install -r`
  2. **把 APK 复制到 DE 存储并把 LSPosed 的 `apk_path` 指过去**（见 §2.2）
  3. 改过作用域/模块列表 ⇒ 重启
- **三条自证**（不看 UI 也能判断链路是否通）：
  ```
  ① system_server 侧出现 N 个 "hookAll OK ..."（证明 API102 的 onSystemServerStarting 通路成立）
  ② 桥：推送 PONG ⇒ 已发往 <模块包名>（证明"模块活着"这条链路通）
  ③ 改一项配置 ⇒ 日志出现 "⇒ 已生效并落盘"（证明运行期配置链路通）
  ```

### 7.2 运行期配置（把 `public static final` 变成"能改"）

- **坑的本质**：`public static final boolean X = true;` 是**编译期常量**，会被**内联进所有调用点**
  ⇒ App/广播在运行期改它**没有任何效果**（连反射改都不行，因为读到的是字面量）。
- 可用做法：
  1. 把要接管的开关改成 **非 final** 的 `public static`（默认值保持原样 ⇒ 不配置时行为不变）；
  2. 提供一个 `apply(key, value)` 把值**直接写进字段**；
  3. 传输沿用"**广播**"这条已验证通道（App → 广播 → 宿主进程接收器），**避开 `/sdcard`、`/data/local/tmp` 的权限问题**；
  4. 落盘一份（写在宿主本来就在写的目录），冷启动/新进程起来时恢复；
  5. 顺手打一行"当前配置快照"日志 ⇒ **只看日志就能判断生效没生效**。

### 7.3 "像 X 一样"这类主观要求

把它**变成可核对的数**：量 X 的截图（像素 → dp），改完再量自己的截图对比。
本项目就这么把"图标要有距离"落成了"图标顶边 192dp vs 188dp"。
⚠️ 但**测量方法本身会骗人**：整屏差异会被"动态背景/玻璃背板"污染、互相关会被大面积渐变带偏；
优先选**稳健特征**（某条边的 y 坐标、某块区域的平均饱和度），并保留"人眼复核一眼"这一步。

### 7.4 改 UI 的通用纪律（这几条都是踩出来的）

- **淡出 ≠ 让位**：给某块内容做 alpha 淡出时，它的**高度仍然占着** ⇒ 会出现空白。
  要"淡出且不留白"，就得把那块改成**浮层 + 列表里的 spacer**（内容从它下面滚过）。
- **特效的作用域画到最小**：把 `graphicsLayer{alpha=...}` 套在整页容器上，会让**整页连卡片一起透明**。
- **别叠两份留白**：底栏让位既有 `contentPadding.bottom` 又有尾部 `Spacer` ⇒ 空隙翻倍。
- **`fillMaxSize` 的传染性**：放进 `LazyColumn` 的单 item 里会撑满视口。
- **自定义组件当按钮用之前先看它内部**：内部 `fillMaxWidth()` 的卡片组件放进 `Row` 会挤垮同行布局。

---

## 8. 踩坑速查表（现象 → 根因 → 处置）

| 现象 | 根因 | 处置 |
|---|---|---|
| system_server 里模块"随机不生效" | 开机早期读不到 `/data/app` 的 APK（CE 未解锁），而 system_server 最早 fork | APK 放 DE 存储 + `apk_path` 指过去 |
| hook 抛 `... booleanValue() on a null` | hook 的方法返回 `boolean`，回调返回了 `null` | 返回类型必须对齐（用反编译确认签名） |
| 页内画着色器"完全看不见" | pager 页坐标系带平移，纯色能画、着色器被画到屏外 | 全屏效果画在根布局 + 按页距离渐隐 |
| 滚动后整页空白 | 淡出的 `graphicsLayer` 套在了整页容器上 | 只包该淡出的那块 |
| "下拉没回弹" | 只抄了 `overscrollEffect = null`，没抄配套的阻尼节点（或该节点在本层级不生效） | 先实测；不行就交回 Compose 自带拉伸 |
| 底部空一大截 | `contentPadding.bottom` 与尾部 `Spacer` 叠加 | 只留一份 |
| 自适应图标被裁（如底部文字没了） | 前景按 86% 缩放，超出 66dp 安全区 | 前景内容收到 61.1%（66/108） |
| 改完没变化、MD5 与上版相同 | 构建没跑（路径/管道吞错） | 构建后**必须**核对 mtime+MD5 |
| App 上改开关没反应 | 开关是 `public static final`（编译期内联） | 改成非 final + 广播 + 落盘（§7.2） |
| 版本/名字各处不一致 | 清单 label、`strings.xml`、UI 里的版本常量、`module.prop` 是**四处独立**的 | 改名/改版时四处一起改 |

---

## 9. 已知问题清单（**唯一权威**，别处只引用本表）

> **记录纪律**：出现过的症状都必须在这张表里有一条。
> 曾经只写了"最出名的那个（闪黑）"，结果**漏掉了同样是用户可见的 U2** —— 记录不完整就是一种失误。

| # | 症状 | 根因 | 状态 |
|---|---|---|---|
| **U1** | **闪黑** | ❓ **未定案**（候选解释：mask 铺色 / 位图缺失 / MIUI `drawViewOnCanvas` 的 no-bitmap 分支 —— **逐条都被真机证据否掉**） | **未解** |
| **U2** | **动画后，图标/组件那个位置显示应用实时内容** | ✅ **已定案**：mask 是 1:1 整屏，`maskAlpha 1.0→0.0` **仅约 168ms 就淡完**；一淡完，底下"**此刻还只有组件那么大**"的应用窗口就直接露出来 | **未解**（根因清楚，但唯一出路被判废，见下） |
| **U3** | **色块**：整屏变成组件的主色 | ✅ 已定案：我们在 `view_scale_mask` 面用 `drawColor(fill)` 铺色，且该面在过渡结束后**仍在合成里** | **未解**（与 U2 是同一枚硬币的两面） |
| — | **U2 与 U3 的取舍** | **"无色块"与"不露应用内容"不可兼得**：铺满 ⇒ 色块；收窄 ⇒ 露内容。桌面靠**同时驱动两层**绕开（应用 leash `type=2` + **不淡出的 FloatingIcon 源快照层** `type=1`），本项目只驱动了一层 | 待新方案 |
| **U4** | 下拉没有阻尼回弹（手感偏"硬"） | MIUIx 的 `overScrollVertical()` 在"pager 内的列表"这一层级**实测位移为 0** | 已取舍：改用 Compose 自带拉伸回弹（§3.3） |
| **U5** | 关于页头部**不能既淡出又不留白** | 淡出只改 alpha，**高度仍然占位** | 已取舍：不做淡出；要兼得需改成 `LazyColumn(spacer) + 头部浮层`（§7.4） |
| **U6** | **重装 APK 后动画不生效** | ✅ 已定案：LSPosed db 里的 `apk_path` **过期**（每次重装 `/data/app/~~xxxx==/` 目录都会变） | **有解**：LSPosed 里把模块**关掉再打开** → 重启（§2.2） |

### 9.1 已被尝试并**判废**的"解 U2/U3"路线（不许重开，除非有新证据）

| # | 路线 | 失败现象 |
|---|---|---|
| 1 | 自建快照层顶替内容 | 空 / 重影 |
| 2 | 接系统过渡引擎 | 引擎**零参与** |
| 3 | 改真实 View（含 MAML） | **MAML 组件卡不渲染** |
| 4 | 打窗口 SC 矩阵 | 调用成功但被 **WMS 覆盖**，画面无变化 |
| 5 | 改真实 View（排除 MAML） | 项留半透明（我的代码 bug：未复位旧项） |

> 详细判废依据见 **`结论总表.md` §D** 与附录的收口档（§CM2 一带）。
> 另注：`MASK_ALPHA_FROM_ZERO=true`（当前主线版的默认值）会让 **U2 结构性必然出现** ——
> 因为 mask 全程不可见 ⇒ 底下那层全程露着（这也解释了为什么该版本的观感是"闪黑 + 图标显示内容"）。

### 9.2 其它"证据不足 / 未验证"（不是用户可见 bug，但别当结论）

- ❓ **`Chain` 层面对参数数组的所有权**：`proceed(Object[])` 传进去的数组是否会被框架复用/缓存 —— 未验证，改参数时最好传新数组。
- ❓ **流光着色器里 `color.rgb /= color.a` 的除零**：当前靠 `clamp` 兜住、观感没问题；换设备/换 AGSL 编译器后是否仍安全，未验证。
- ❓ **U2/U3 是否存在"第三条路"**：桌面用"双源层"绕过；本项目内尚未做成。

## 10. 一页速查（Cheat sheet）

```
# 构建（路径按本机实际）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :app:assembleRelease
# 构建后必查：产物 mtime + md5 与上一版不同

# 装机三步
adb install -r app-release.apk
adb shell su -c 'cp $(pm path <pkg> | sed s/package://) /data/adb/dshmod/<name>.apk; chmod 644 ...'
# 改过模块列表/作用域 ⇒ 重启

# 三条自证
grep -a "hookAll OK" (system 进程的 LSPosed 日志)      # ① system_server 通路
logcat | grep "推送 PONG ⇒ 已发往 <pkg>"               # ② 激活桥
logcat | grep "收到配置设置 ... 已生效并落盘"           # ③ 运行期配置

# 自适应图标三数：画布 108dp / 安全区 66dp(61.1%) / 掩码可见 ≤72dp

# API102 入口：覆写 onModuleLoaded / onSystemServerStarting / onPackageLoaded(+Ready)
#   PackageLoadedParam 只有 getDefaultClassLoader()；getClassLoader() 在 PackageReadyParam 上
```

---

*本文是活的：任何一条在**新设备/新 ROM/新库版本**上被推翻，就地更新（并保留原判断与出处），不要删。*
