# QZX 规格提炼：按钮（GeoButton）与液态玻璃（Liquid Glass）

> 来源：`https://qzxui.geometryos.cn/components/basic/geo-button.html`（`QZXGeoButton`，约 **1435 行**）
> 　　　`https://qzxui.geometryos.cn/components/viewx3/liquid-glass.html`（`com.QZX.compose.blur` / `.liquid` / `.shader`）
> 抓取方式：正文原文 + 属性表，**未读源码**（QZX 是 Java + Views 库，文档本身就是完整规格）
> 本文只提炼规格，**不含实现**。所有数值均为文档给出的默认值 / clamp 区间。

---

## 0. 先说结论（给排期用）

| 效果 | 需要 AGSL | Compose 可实现 | 观感提升 | 建议 |
|---|---|---|---|---|
| 释放扩散 | 否 | ✅ 容易 | 高 | **先做** |
| 高光跟随 | 否 | ✅ 中等 | **最高** | **先做** |
| 外发光 | 否 | ⚠️ 降级可做 | 中低 | 缓做 |
| 按压色插值 | 否 | ✅ 容易 | 中 | 顺带做 |
| 边缘光 RimLight | 否 | ✅ 已在做（002） | 中 | 进行中 |
| 液态玻璃·折射 | **是** | ❌ 做不了 | 高 | 不排期 |
| 液态玻璃·模糊 | 否（需 31+） | ⚠️ 见 §3.3 | 中 | 不排期 |

---

# 一、按钮：`QZXGeoButton`

**定位**：QZX 库中动效最密集的控件。一次「按下 → 抬起」依次触发 **6 层**反馈，每层可独立关闭。

| 层 | 效果 | 关键属性 |
|---|---|---|
| ① 基色反馈 | 背景/边框色插值 + 轻微缩放（**180ms**） | `qzxButtonTouchFeedbackColor` / `qzxButtonTouchScale` |
| ② 高光跟随 | 径向渐变光斑跟随手指，**按速度预测落点** | `qzxButtonHighlight*` |
| ③ 外发光 | 按钮外圈光晕 | `qzxButtonGlow*` |
| ④ Loading | 中心转圈，隐藏文字并禁用点击 | `qzxButtonOnLoading` 等 |
| ⑤ 拖动形变 | 拖出按钮范围时按位移拉伸变形 | `qzxButtonDragDeform*` |
| ⑥ 释放扩散 | 松手时从触点扩散出的涟漪 | `qzxButtonReleaseDiffusion*` |

> 注意层数序号：原文表格标的是 ①②③④⑤⑥（第 2 行写「层」列时 ⑤=拖动形变、⑥=释放扩散）。

## 1.1 全部属性与默认值

### 基础

| 属性 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `qzxButtonBackgroundColor` | color\|reference | `@color/accent_color` | 背景色 |
| `qzxButtonTextColor` | color\|reference | `WHITE` | 文字色 |
| `qzxButtonCornerRadius` | dimension | **12dp** | 圆角 |
| `qzxButtonTouchFeedbackColor` | color\|reference | 背景 HSV **×0.92** | 按压背景色（V 通道压到 92%） |
| `qzxButtonTouchScale` | float | **0.96** | 按压缩放 |
| `qzxButtonBoldText` | boolean | `true` | 是否加粗 |
| `qzxButtonShadowEnabled` | boolean | `true` | 阴影开关 |
| `qzxButtonShadowColor` | color\|reference | `#FF4081` | 阴影色 |
| `qzxButtonShadowBlur` | dimension | **20dp** | 阴影模糊 |
| `qzxButtonShadowOpacity` | float | **0.8** | 阴影不透明度 |

### 边缘光（RimLight）

| 属性 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `qzxButtonRimLight` | boolean | `true` | 边缘光开关（**加法混合**的白边光） |
| `qzxButtonRimLightWidth` | dimension | **1dp** | 边缘光带宽（`0` = 不画） |
| `qzxButtonRimLightSkipTransparent` | boolean | `true` | 背景 `alpha == 0` 时跳过边缘光 |

### 高光（跟手）

| 属性 | 默认值 | clamp | 含义 |
|---|---|---|---|
| `qzxButtonHighlightEnabled` | `true` | — | 是否启用跟手高光 |
| `qzxButtonHighlightColor` | `#7CFFFFFF` | — | 高光颜色（`0x7C` ≈ 49% alpha 白） |
| `qzxButtonHighlightIntensity` | `1.0` | **0.1 ~ 0.5** | 强度 |
| `qzxButtonHighlightRadius` | **200dp** | — | 光斑半径 |
| `qzxButtonHighlightSoftness` | `0.9` | — | 软边（0~1，越大越柔） |
| `qzxButtonHighlightFollowSpeed` | `0.6` | **0.1 ~ 1.0** | 跟随速度 |
| `qzxButtonHighlightPressDepth` | `0.15` | — | 按压深度 |
| `qzxButtonHighlightDynamic` | `true` | — | 是否动态跟手（`false` = 固定在中心） |
| `qzxButtonHighlightScaleEnabled` | `true` | — | 高光缩放开关 |
| `qzxButtonHighlightMaxScale` | `1.3` | — | 高光最大缩放 |
| `qzxButtonHighlightDirectionSensitivity` | `0.5` | — | 方向敏感度 |

> ⚠️ **默认值 1.0 超出自己的 clamp 上限 0.5。** 文档写 intensity 默认 `1.0`、clamp `0.1~0.5` —— 意味着**实际生效值被夹到 0.5**。抄参数时必须按 0.5 落地，不要照抄 1.0。

### 发光（外发光）

| 属性 | 默认值 | clamp | 含义 |
|---|---|---|---|
| `qzxButtonGlowEnabled` | `true` | — | 外发光开关 |
| `qzxButtonGlowColor` | `argb(100,255,255,255)` | — | 发光颜色（100/255 ≈ **39% alpha** 白） |
| `qzxButtonGlowIntensity` | `0.1` | **0.05 ~ 0.3** | 发光强度 |
| `qzxButtonGlowRadius` | **80dp** | — | 发光半径 |
| `qzxButtonGlowSoftness` | `0.5` | — | 发光软边 |

### 触摸阈值 / 反馈强度

| 属性 | 默认值 | clamp | 含义 |
|---|---|---|---|
| `qzxButtonScrollThresholdRatio` | `0.2` | — | 纵向滚动阈值 = **高度 × 0.2** |
| `qzxButtonTransitionStartRatio` | `0.05` | — | 进入拖动判定的起始比例 |
| `qzxButtonHorizontalThresholdRatio` | `0.15` | — | 横向阈值 = **宽度 × 0.15** |
| `qzxButtonDebounceTime` | **50** ms | — | 防抖 |
| `qzxButtonTouchFeedbackIntensity` | `1.0` | **0.5 ~ 2.0** | 反馈强度 |
| `qzxButtonDragIntensityMultiplier` | `1.5` | — | 拖动强度倍率 |
| `qzxButtonSpeedSensitivity` | `1.0` | **0.5 ~ 2.0** | 速度灵敏度 |

### 释放扩散

| 属性 | 默认值 | clamp | 含义 |
|---|---|---|---|
| `qzxButtonReleaseDiffusionEnabled` | `true` | — | 释放扩散开关 |
| `qzxButtonReleaseDiffusionDuration` | **300** ms | **100 ~ 1000** | 时长 |
| `qzxButtonReleaseDiffusionRadius` | **100dp** | — | 扩散半径（**会被运行时改写，见坑 2**） |
| `qzxButtonReleaseDiffusionIntensity` | `0.8` | **0.1 ~ 1.0** | 强度 |
| `qzxButtonReleaseDiffusionColor` | `WHITE` | — | 扩散颜色 |

### 交互 / Loading / 形变 / 羽化

| 属性 | 默认值 | 含义 |
|---|---|---|
| `qzxAllowTouch` | `false` | **`true` 时表示禁用**：不消费触摸 + 整体变淡到 45% |
| `qzxButtonOnLoading` | `false` | 加载态 |
| `qzxButtonLoadingColor` | `WHITE` | 转圈颜色 |
| `qzxButtonLoadingBackgroundColor` | `TRANSPARENT` | 加载态底色 |
| `qzxButtonLoadingStrokeWidth` | **4dp** | 转圈粗细 |
| `qzxButtonLoadingRadius` | **10dp** | 转圈半径 |
| `qzxButtonDragDeformEnabled` | **`true`** | 拖动形变（**注意：按钮默认开，与卡片相反**） |
| `qzxButtonDragDeformStrength` | `0.6` | 形变强度 |
| `qzxButtonDragDeformThreshold` | **6dp** | 形变阈值 |
| `qzxButtonDragDeformTranslateFactor` | `0.15` | 平移系数 |
| `qzxButtonDragDeformReleaseDuration` | **1880** ms | 归位时长 |
| `qzxButtonOverlayFeatherEnabled` | `true` | 叠层羽化 |
| `qzxButtonOverlayFeatherThreshold` | **2dp** | 接触阈值 |
| `qzxButtonOverlayFeatherReceiveEnabled` | `false` | 接收他人辉光 |
| `qzxButtonOverlayFeatherEmitMode` | `pressed` | 发射模式 |

### ❌ 声明了但**不生效**的属性（写了没用，别浪费时间）

`qzxButtonElevation`、`qzxButtonShadowSpread`、`qzxButtonShadowOffsetX`、`qzxButtonShadowOffsetY`、主题 `qzxGeoButtonStyle`
—— **构造函数未读取**。

## 1.2 三个核心效果的机制与观感

### ② 高光跟随 —— 「光斑会甩向手指前进方向」

**一句话**：一个半径 200dp 的柔和白色径向光斑跟着手指跑，且**带速度预测**。

**机制（文档原文）**：
> 用**最近 5 个触摸点**算平均速度，**预测落点**，再让 `highlightX/Y` 以**指数平滑**逼近目标。

**这正是它与 Material ripple 的本质区别**：ripple 是从触点半径扩散的圆圈；这里是**跟随的、有惯性的光斑**。
快速滑动时高光「甩」到手指前方，慢速时几乎贴合手指 —— 由 `FollowSpeed=0.6` 与 `DirectionSensitivity=0.5` 控制这个「甩」的力度。

**可感知参数链**：
- 光斑大小 `200dp` ≫ 按钮尺寸 → **光斑比按钮大**，所以按钮上看到的是光斑的**中段**，不是完整圆
- 软边 `0.9` → 几乎无硬边，是「漫射」而非「聚光」
- 有效强度 `0.5`（clamp 后） + 颜色 `#7CFFFFFF`（49% 白） → 叠加后约 **+25% 亮度**的柔光
- 按下时 `PressDepth=0.15` → 光斑额外「下沉」15%，制造被按进表面的错觉

### ③ 外发光 —— 「按钮外圈一圈淡淡的白雾」

**一句话**：按钮边界**外侧**一圈半径 80dp 的白色柔光，像按钮在发光。

**参数含义**：强度只有 `0.1`（clamp 上限 0.3）、颜色 39% 白 → **非常淡**，是「氛围」不是「霓虹」。
`Radius=80dp` 相对按钮（约 44dp 高）意味着光晕铺出按钮外 ~18dp（半径减半高度）。
`Softness=0.5` → 中等柔度。

> 实现提示：这是**在按钮 bounds 之外**绘制，Compose 里需要 `drawWithContent` 且不裁切，或外层包一个更大的 Box。

### ⑥ 释放扩散 —— 「松手时从触点荡开一圈涟漪」

**一句话**：松手瞬间从**最后触点**向外扩散一圈白色涟漪，**300ms** 内扩散到 100dp 并淡出。

**与 ripple 的区别**：ripple 是按下时开始、松手时收缩；QZX 是**松手时才开始扩散**，是「收尾动作」而非「按下反馈」。
强度 `0.8`（+clamp 至 1.0）比高光强得多 —— 它是**可见的明确动画**，不是氛围。

### ⑤ 拖动形变

拖出按钮范围超过 `6dp` 阈值后，按位移**拉伸变形**，`Strength=0.6`、平移系数 `0.15`；
松手后 **1880ms** 才完全归位（**接近 2 秒，非常慢**）。
⚠️ 按钮上**默认开启**（与卡片相反）。

### ① 基色反馈

背景色插值到 `背景 HSV×0.92`（仅压 V 通道，色相/饱和度不变）+ 缩放 `0.96`，**180ms**。

### ④ Loading

中心画弧（`StrokeWidth=4dp`、`Radius=10dp`、色 `WHITE`、底 `TRANSPARENT`），**隐藏文字并禁用点击**。

## 1.3 已知坑（原文抄录）

1. **禁用 / 加载态完全不消费触摸**
   ```java
   // onTouchEvent 首行
   if (qzxAllowTouch || qzxButtonOnLoading) return false;
   ```
   → 禁用后**父容器滚动可正常穿透**。禁用视觉 = 叠一层白色 LayerDrawable，**150ms** 内把按钮层降透明到 **0.45**。
   ⚠️ 属性名语义反直觉：`qzxAllowTouch = true` 表示**禁用**。

2. **释放扩散会永久放大自身半径字段**
   `startReleaseDiffusion` 会把扩散半径扩到 `max(配置半径, 最大边 × 1.5)` 并**回写字段** —— 即一次扩散后 `qzxButtonReleaseDiffusionRadius` 的实际值被改大。业务若依赖读该值须注意。
   （例：按钮宽 200dp → 扩散半径会被改成 `max(100, 300) = 300dp`，且**不再恢复**。）

3. **阴影走软件层**
   `applyNativeShadow` 会 `setLayerType(LAYER_TYPE_SOFTWARE, null)` → **开启阴影会降渲染性能**。列表内大量按钮建议 `qzxButtonShadowEnabled="false"`。

4. **拖动形变默认开启（按钮与卡片相反）**
   若按钮在可滚动列表里，「拖动形变会尝试抢占父容器事件」—— 确认这是想要的行为，否则显式关闭。

5. **Loading 隐藏文字的方式很脏**
   `onDraw` 里临时 `setText("")` 隐藏文字再画弧，结束时恢复原文并 `setClickable(false/true)`。

6. **边缘光与卡片同源，但少了「动画挂起」**
   与 `QZXGeoCardView` 共用同一份 `QZXRimLight`（同算法 / 同全局缓存），都在 `onDraw` 末尾以加法混合叠加。
   区别：按钮没有圆角动画，**没有卡片那套「圆角动画期间挂起边缘光」的逻辑** —— 按钮改圆角时边光立刻跟着新圆角走。
   按下态 / 禁用态**不做特殊处理**，边缘光照常画（禁用只是整体变淡）。
   想省掉这圈边光（如列表里上百个按钮），`qzxButtonRimLight="false"`。

---

# 二、液态玻璃与模糊后端

## 2.1 三档后端（按 `Build.VERSION.SDK_INT` 分派）

```kotlin
fun isRuntimeShaderSupported() = Build.VERSION.SDK_INT >= 33   // TIRAMISU
fun isRenderEffectSupported()  = Build.VERSION.SDK_INT >= 31   // S
```

| Android | 后端 | 能拿到什么 |
|---|---|---|
| **13+（≥33）** | AGSL `RuntimeShader` **完整管线** | 模糊 + 鲜艳度 + 镜面折射 + 边缘高光 + 内阴影 + 噪声 |
| **12（31–32）** | `RenderEffect` 模糊 + 平色蒙层 | 一层真实视图模糊；**没有折射、没有 AGSL 高光** |
| **9–11（<31）** | `QZXNewBlurView` 位图模糊（CPU） | 底下真位图模糊层 + 上面半透明蒙色胶囊 |

### 13+：一笔画完
`Modifier.drawBackdrop(...)` 在**一个** AGSL 着色器里把整条链路一次叠出：
采样 → 降采样 → 模糊 → 色彩控制 → 混合层 → 噪声抖动 → 合成。

> 副作用：所有 AGSL 专属效果（`lens()` / `colorControls()` / `blendColors()` / `progressiveBlur()`…）在低版本**静默跳过** —— 不报错、不崩，只是不做。

### 12：模糊半径被**写死**
降档路径**不看调用方传的半径，强制用 `40f`**。
所以底栏在 AGSL 分支传的是 `blur(4dp, 4dp)`，到 Android 12 按 **40dp** 走 —— **12 上比 13 更「糊」**。
这是**故意的**：`RenderEffect` 的 sigma 与 AGSL 的半径不是一套量纲，不硬拉大反而像「没糊」。

### 9–11：位图模糊兜底
截取根视图像素 → **降采样 8×** → stack blur（半径 **40dp**）。CPU 计算。

| | AGSL / RenderEffect（12+） | QZXNewBlurView（<12） |
|---|---|---|
| 谁在算 | GPU | CPU |
| 糊什么 | 视图本身，**逐帧** | 根视图截下来的**位图** |
| 特点 | 玻璃**实时跟着内容走** | 玻璃「垫在下面」，不参与 View 合成；**无折射、无 AGSL 高光** |

> 底栏低版本叠法：最底 `AndroidView` 放位图模糊层（圆角用 View 自己的 outline 裁），上面铺半透明蒙色胶囊。
> 用**普通版**而不用 `QZXNewBlurViewCross`：Cross 是给「模糊视图与内容不在同一棵窗口树」的弹窗写的（需跨根续帧）；底栏同窗口，普通版语义更正、少一层续帧开销。

## 2.2 玻璃由 7 个可拼效果 + 2 个 Modifier 挂件组成

| 效果 | 作用 | 需要 AGSL |
|---|---|---|
| `blur` | 主模糊（**默认半径 20**） | 否（12+ 有 RenderEffect 降档） |
| `vibrancy` | 提鲜艳度（**饱和度 ×1.5**），让玻璃「活」 | **是** |
| `lens` | 圆角矩形边缘的**镜面折射**，可选色散 | **是** |
| `colorControls` | 亮度 / 对比 / 饱和 | **是** |
| `blendColors` | **至多 8 层** 着色 / 染色 / 暗化 | **是** |
| `noiseDither` | 抗大块模糊的色带 | **是** |
| `progressiveBlur` | 上下渐隐的模糊 | **是** |

**直接挂在 `Modifier` 上的两个**：
- **边缘高光**：贴边的镜面光带（AGSL 产物）
- **内阴影**：指示器按下时向内收的暗影

## 2.3 底栏的实际组合（照抄，作为参数基准）

```kotlin
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { pillShape },
    effects = {
        padding = maxOf(padding, 40.dp.toPx())     // 撑开采样区，给折射留余地
        vibrancy()
        blur(4.dp.toPx(), 4.dp.toPx())
        lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
    },
    onDrawSurface = { drawRect(containerColor) },
)
```

**关键参数**：
- 采样 padding ≥ **40dp**（给折射留出余量，否则边缘折射采样到画布外）
- `blur` **4dp**
- `lens` `refractionHeight = 24dp`、`refractionAmount = 24dp`（**折射高度与位移量相等**）

> 玻璃「看见」的背后内容来自 `Backdrop`。底栏那个**不是 Compose 内部记录图层**，而是直接绘制外部 Java View 树（因宿主内容本就不是 Compose），靠一条自建通道刷新：内容重绘 → 通知玻璃重采样。

## 2.4 已知坑（原文抄录）

1. **软件画布上 AGSL 当场抛异常** 🛑
   `RuntimeShader` 只认硬件加速画布。画进 `Canvas(Bitmap)` 这类软件画布，直接
   `IllegalArgumentException: Software rendering doesn't support RuntimeShader`。
   **正解不是到处 try/catch，而是能力门控**：玻璃外壳在软件画布下整体跳过绘制。

2. **没有 AGSL 就没有那圈高光 → 用边缘光补**
   贴边镜面光带是 AGSL 产物。12 和 9–11 改用非 shader 的 `QZXRimLight` 补一圈加法白光 —— 与卡片/按钮的边光**同一份实现、同一个缓存**，观感统一。

3. **指示器的「蓝色滤镜」在低版本要拆两层**
   AGSL 指示器采样「蓝色强调层」做出「透过玻璃的蓝」。回退分支里蓝色选中态是「画在胶囊里的一整行 accent 内容」，靠与底下黑图标**精确重合**才读成滤镜。而果冻物理会让胶囊按下放大到约 **1.39×** —— 整块一起缩会让蓝色放大、错位、露出黑图标，滤镜就破了。
   → 回退分支**拆两层**：物理只作用在胶囊底上，蓝色内容纹丝不动。

4. **重力高光的方向被量化**
   倾斜手机转动玻璃高光时，方向量化到 **3° 一档**、近平放时钉死。为避免传感器噪声让光每帧抖、也让 Compose 少重算。

---

# 三、我们能实现什么（Compose，不引 AGSL）

## 3.1 本工程事实（判断依据）

| 项 | 值 | 对本任务的影响 |
|---|---|---|
| `minSdk` | **24** | AGSL 要 **API 33+**、`RenderEffect` 要 **31+** → 本工程最低支持的 24~32 这一段**完全拿不到 AGSL**（具体占比未实测，不做估算） |
| `targetSdk` | 34 | — |
| `compileSdk` | 36 | — |
| Compose BOM | `2026.01.01` | 支持 `graphicsLayer` / `drawWithContent` / `PathEffect` / `BlendMode` |
| 现有模糊 | `ui/theme/BgBlur.kt`（CPU stack blur，用于 `GlobalBackground`） | 与 QZX 的 9–11 档同思路，可复用 |
| 现有按压 | `PRESS_SCALE = 0.96`（**View 与 Compose 已收口**） | ① 基色反馈**已完成缩放部分** |
| 现有涟漪 | **`indication = null`（刻意无 ripple）** | ✅ 高光/扩散不是"替换 ripple"，是**填补我们主动留空的反馈位** |
| `Radius.btn` | **16dp** | QZX 按钮是 **12dp** → 见 §3.5 |

## 3.2 逐条可行性

### ② 高光跟随 —— ✅ **完全可做**（最高价值）

| 子项 | Compose 手段 |
|---|---|
| 光斑 | `drawWithContent` 里 `drawCircle(brush = Brush.radialGradient(...))` |
| 加法混合 | `BlendMode.Plus`（组件侧映射到 `PorterDuff.PLUS`，API 24 可用） |
| 软边 0.9 | radial gradient 的 stop 分布：中心→0.1 保持 alpha，0.1→1.0 平滑降到 0 |
| 跟随 | `pointerInput { detectDragGestures }` 或 `awaitPointerEventScope` 取位置 |
| 指数平滑 | `Animatable` / `animateFloatAsState` + `tween`；或直接每帧 `lerp(current, target, 0.6)` |
| 速度预测 | **自己维护最近 5 个点的环形数组**求平均速度，`target = 手指 + velocity × sensitivity` |
| 光斑超出按钮 | 用 `Modifier.clip(shape)` **会裁掉** → 需在 clip **之前**画，或用 `drawWithContent` + 不裁切的外层 |

**唯一难点**：QZX 的「5 点平均速度 + 预测落点」需要**自己实现**（Compose 没有现成 API）。
但逻辑只有几十行，且不需要任何平台能力。

### ⑥ 释放扩散 —— ✅ **容易**（第二价值）

| 子项 | 手段 |
|---|---|
| 涟漪 | `Animatable<Float>` 从 0 → `max(100dp, maxEdge×1.5)` |
| 时长 300ms | `DhDesign.Motion.enter`(220) 不够 → 需新增 300ms 档，或直接用 `tween(300)` |
| 淡出 | 同一条 `Animatable` 驱动 alpha `0.8 → 0` |
| 起点 | 松手时的**最后触点**（`onDragEnd` 的 position） |

**注意**：**不要**照抄坑 2（回写半径字段）。我们不暴露那个字段，每次从配置算即可，避免"越用越大"。

### ③ 外发光 —— ⚠️ **降级可做**

| 方案 | 评价 |
|---|---|
| 径向渐变画在 bounds 外 | ✅ 推荐。`drawWithContent` 里在内容**之后**画一个大 radial gradient，`BlendMode.Plus`。**不裁切**是关键 |
| `BlurMaskFilter` | ❌ 硬件加速画布上行为不可靠（多数情况不生效） |
| `Modifier.blur`（API 31+） | ⚠️ 会让**整个节点**模糊（含文字），不是只要外圈 |

强度只有 0.1，**观感收益小**，且与 RimLight 视觉上会叠在一起 → 建议**缓做或不做**。

### ① 基色反馈 —— ✅ **已完成 70%**

缩放 `0.96` 已done（我们刚收口过 View/Compose 两侧）。
剩下：背景色插值到 `HSV×0.92`，180ms。
我们当前没有实现 V 通道压暗 —— 这个可以顺手加，成本极低。

### ④ Loading —— ✅ **已有等价物**

`DhButton` 已有 `CircularProgressIndicator` + 自动禁用。参数字面差异：我们 stroke 默认、QZX `4dp`/`Radius=10dp`。**无需改动**。

### ⑤ 拖动形变 —— ✅ **已有** `DhBounce.cardDeform`

但注意 QZX 按钮的 `DragDeformReleaseDuration = 1880ms`（近 2 秒）比我们 `cardDeform` 的归位快得多/慢得多需对齐 —— 我们那套是弹簧（`DhAnim.BOUNCY`），不是固定时长。

### 液态玻璃·折射 —— ❌ **做不了**

`lens()`（`refractionHeight` / `refractionAmount` / 色散）**必须逐像素采样背后内容并重映射坐标**。
Compose 无 AGSL 时唯一等价物是 `RenderEffect.createRuntimeShaderEffect`（也要 33+），
**没有纯 CPU/纯 Compose 的折射实现**（自己写等于在 CPU 上逐像素做屏幕空间重采样，一帧几百万次采样，手机不可接受）。

### 液态玻璃·模糊 —— ⚠️ **技术可行但我们不该做**

| 档 | 可行性 |
|---|---|
| API 31+ `Modifier.graphicsLayer { renderEffect = BlurEffect(...) }` | ✅ 能糊，但**只能糊自己**（模糊的是该节点的内容，不是它背后的兄弟/背景）→ 做不出"透出背后内容" |
| CPU 位图模糊（复用 `BgBlur`） | ✅ 能糊，但**每帧截根视图**，代价极高；我们只在 `GlobalBackground` 里对**静态背景图**做一次 |
| 真正 backdrop blur | ❌ Compose 无公开 API（AGSL 是唯一正路） |

**结论：真正的"液态玻璃"在我们 minSdk 24 + 不引 AGSL 的前提下做不出来。**
我们现在用的 `readableSheet(fill = SOFT, cornerDp)` 是**半透明蒙色**（无模糊）——
这正是 QZX 的 **12 档「平色蒙层」**思路，属于合理降级，继续用即可。

## 3.3 我的明确判断（按 lead 要求「做不了就说」）

> **液态玻璃整体不做。** 折射需要 AGSL（33+），我们 minSdk 24；没有 AGSL 的降级路径（平色蒙层）我们**已经等价实现了**（`readableSheet`）。
> 花成本去追一个「低于 33 的设备完全看不到、33+ 也要改渲染管线」的效果，不如把按钮的 ②⑥ 做扎实 —— 那是**全设备可见**的。

## 3.4 落地参数建议（抄 QZX，映射到我们令牌）

| QZX 参数 | 值 | 我们的落地 |
|---|---|---|
| HighlightRadius | 200dp | 原值（远超按钮尺寸是有意的） |
| HighlightColor | `#7CFFFFFF` | 原值 |
| **HighlightIntensity** | 默认 1.0 → **clamp 到 0.5** | **用 0.5**（见 §1.1 那条警告） |
| HighlightSoftness | 0.9 | 原值 |
| HighlightFollowSpeed | 0.6 | 原值（指数平滑系数） |
| HighlightPressDepth | 0.15 | 原值 |
| HighlightMaxScale | 1.3 | 原值 |
| 预测点数 | 5 | 原值（环形数组长度） |
| ReleaseDiffusionDuration | 300ms | **需新增 `DhDesign.Motion` 档位**（现有 enter=220 / exit=180 都不等于 300） |
| ReleaseDiffusionRadius | 100dp | 原值，但**每次从配置算，不回写** |
| ReleaseDiffusionIntensity | 0.8 | 原值 |
| ReleaseDiffusionColor | WHITE | 原值 |
| RimLightWidth | 1dp | 与 002 的 RimLight 共用 |
| TouchScale | 0.96 | ✅ 已一致 |
| TouchFeedbackColor | 背景 HSV×0.92 | 待加（低成本） |

## 3.5 一个需要 lead 拍板的冲突

QZX 按钮圆角 **12dp**，我们 `Radius.btn` 是 **16dp**（上一轮刚把卡片/按钮/输入框统一到 16）。
**建议保持 16，不跟随 12** —— 我们上一轮刚花整轮把圆角收敛成一套，为一个外部值拆回去不划算。规格里保留 12dp 供参考。

---

# 四、优先级排序（观感提升 / 实现成本）

| 序 | 效果 | 观感 | 成本 | 需要 AGSL | 建议 |
|---|---|---|---|---|---|
| **1** | **② 高光跟随** | ★★★★★ | 中（~150 行：光斑 + 5 点速度预测 + 指数平滑） | 否 | **先做** |
| **2** | **⑥ 释放扩散** | ★★★★ | 低（~60 行：一个 Animatable 驱动半径+alpha） | 否 | **先做** |
| 3 | ① 基色 V×0.92 + 180ms | ★★ | 极低（~10 行） | 否 | 顺带 |
| 4 | 边缘光 RimLight | ★★★ | 中 | 否 | 002 在做 |
| 5 | ③ 外发光 | ★★ | 中（需处理不裁切 + 与 RimLight 叠加） | 否 | 缓做 |
| 6 | ⑤ 拖动形变（1880ms 归位） | ★★ | 低（已有 cardDeform，改归位时长） | 否 | 可选 |
| — | 液态玻璃·折射 | ★★★★ | **极高 / 做不了** | **是** | **不排期** |
| — | 液态玻璃·模糊 | ★★★ | 高（无 backdrop API） | 事实上是 | **不排期**（已有等价降级） |

## 为什么是这两个

**② 高光跟随**：它是 QZX 按钮**唯一的"签名效果"** —— ripple 谁都有，跟手惯性的光斑没有。
而且我们 `pressable` 是 `indication = null`（**刻意无涟漪**），这块反馈位是空的。补上它不是"再加一层动效"，是**把留空的位置填对**。
全设备可见（不需要 API 33+），成本可控（无平台依赖，纯 Compose 绘制）。

**⑥ 释放扩散**：60 行换一个「按完有回响」的收尾，性价比最高。
且它与高光**不冲突**（一个按下持续、一个松手触发），可以一起做。

**两者合计约 200 行**，全部是 Compose 自绘，**不引入任何版本门槛**，且都能挂进现有的 `DhButton` / `pressable` 体系。

---

## 附：本文未覆盖的部分（避免误以为读全了）

- `QZXGeoCardView` 的 1448 行规格（lead 已单独掌握，本文只引用了它对 `QZXRimLight` 的共用关系）
- 边缘光（RimLight）专篇的算法细节（002 在实现，本文只记「1dp 加法白、同卡片共用缓存、按钮无动画挂起」三点）
- 悬浮玻璃底栏（Compose）的 `Backdrop` 通道细节（我们不是 Compose 宿主，这条路用不上）
- 模糊视图体系（View 世界三条路线）的对比（我们已有 `BgBlur`，且只用于静态背景图）
