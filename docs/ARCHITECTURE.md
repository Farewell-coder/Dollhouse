# Dollhouse UI 架构说明

> 基线：分支 `feat/compose-ui-unification`，从 `master` @ `58f8a39`（v0.2.6）拉出。
> 本文描述**当前真实状态**，不是规划稿。所有数字均可用文中命令复现。

---

## 一、目标与边界

**目标**：常规 UI 页面统一由 Jetpack Compose 实现，全 App 一套设计规范，
View 只作为「浮层 + 自绘引擎 + 必要的互操作封装」存在。

**不做**：不迁移业务架构、不改数据层与协议、不动人偶隔离与记忆体系、
不升版本号、不发 Release。UI 改动必须可随时回退到 `master`。

---

## 二、分层结构

```
ComponentActivity（原生 Activity + ComposeHost 补 owner）
   └─ Nav3: rememberNavBackStack / NavStack + NavDisplay
        └─ entryProvider { NavKey -> @Composable }
             ├─ ui/design/DesignSystem.kt    ← 唯一设计令牌源
             ├─ ui/kit/Components.kt         ← 唯一组件库
             ├─ ui/compose/DhKit, DhForm     ← 既有构件（逐步收编进 kit）
             └─ 各功能页 @Composable
   颜色真源：ThemeManager.apply() → UiKit 29 字段 → DhDesign.Palette 快照
```

### 2.1 设计系统（单一大源）

`ui/design/DesignSystem.kt` 的 `DhDesign` 是**唯一**令牌定义：

| 组 | 内容 |
|---|---|
| `Type` | brand 22/600/30 · display 28/600/36 · title 20/600/28 · section 16/600/22 · body 15/400/24 · sub 13/400/20 · caption 12/400/17 · label 11/600/16 |
| `Space` | 4 / 8 / 12 / 16 / 24 / 32；pageX=16；cardPad=16；cardGap=12 |
| `Size` | 图标 14/16/20/24/28；minTouch 48；topBar 56；navBar 64；row 64/56；avatar 40/56/72 |
| `Radius` | 8 / 12 / 16 / pill |
| `Motion` | press 90 · micro 160 · enter 220 · exit 180 |
| `Palette` | 35 个语义槽，全部由 `UiKit` 29 个字段映射或派生 |

**为什么颜色不新增槽位**：`ThemeManager` 的调色板是定长数组（`PAL_SIZE = 29`），
`cachedMonetPalette()` 校验长度不符就整体丢弃莫奈取色。新增语义一律用派生计算
（`okBg/errBg/disabledFg/skeleton/focusRing/onErr`），长度不变、切主题自动跟随。

**Material3 是读者不是作者**：`DollhouseTheme` 把 `Palette` 投影成 `ColorScheme`/
`Typography`/`Shapes`，让 `material3.Text`/`Snackbar`/`BottomSheet` 不再落回默认紫与默认字号；
写入者永远是 `ThemeManager → UiKit`。

### 2.2 组件库

`ui/kit/Components.kt`：`DhButton`、`DhField`、`DhRow`、`DhGroup`、`DhDivider`、
`DhChip`、`DhTag`、`DhState`、`DhSkeletonRow`、`DhIconButton`。

固定指标（设计稿 §2.2，页面禁止覆盖）：按钮 44 / sm 34、输入框 40、chip 26、tag 20、
状态图标座 56、行 64/56、最小触摸 48。

`DhKit`（既有）与 `ui/kit` 的关系：新构件只进 `ui/kit`；`DhKit` 的构件在
M4–M7 逐页替换后逐步迁入，M9 删除重复处。

### 2.3 导航与转场

- 覆盖层：Nav3 `NavDisplay`，`PageStack` 是其门面（不自建第二套栈）。
- 转场：进入 220ms 淡入 + 16dp 上浮（按密度换算像素）；退出 180ms 纯淡出。
  两条时间轴不同，避免「两层同时半透明」造成重影。
- 系统关闭 Animator 时直接完成导航，不播放装饰动画。

---

## 三、View 的合法存在（白名单）

| 文件 | 行数 | 为什么必须保留 |
|---|---|---|
| `pet/PetWindow.kt` | 2390 | 桌宠自绘帧动画（`drawBitmapMesh` + WindowManager 悬浮窗），Compose 无对等能力 |
| `pet/PetTalkInput.kt` | 334 | 悬浮窗内 `EditText`：焦点与 IME 必须在系统窗口层，摘窗需先收起软键盘 |
| `BackgroundCropActivity.kt` | 682 | 裁剪手势与位图变换引擎；UI 可逐步 Compose 化，引擎保留 |
| `CoverCropActivity.kt` | 878 | 同上 |
| `ui/compose/LayerShield.kt` | 272 | 触摸闸门：在层根的 `dispatchTouchEvent` 上做屏蔽，需真 View 才能拦子树 |
| `ui/theme/UiKit.kt` | 2305 | 颜色字段 + 少量 View 工具（`press`/`dp`/`round`），是历史依赖的目标而非页面 |
| `ui/theme/Icons.kt` | 642 | 图标 tint 工具（View 侧），与 Compose 侧共用 |
| `ui/home/HomeUi.kt` | 1565 | 底座装配：三 Tab 用 FrameLayout + 3×ComposeView + 悬浮底栏（不改会动到 `LayerGate`） |
| `ui/chat/ChatPanel.kt` | 2281 | `LinearLayout` 壳 + 内嵌 `ChatUi`(Compose)；承载全部聊天业务契约 |
| `ui/chat/MessageMenu.kt` | 257 | 长按菜单 + 输入框（原生 Dialog），桌宠与聊天页共用 |
| `ui/widget/SheetPanel.kt` | 521 | 自建底部面板（模型选择 / 思考过程），依赖 View 层序 |
| `ui/provider/ProviderDetailPage.kt` | 293 | 供应商详情壳。**经分析确认不可安全迁移**：它用 `slot.removeAllViews + addView` 保留两个内容页的 **View 实例**——「切 tab 后表单内容不丢」完全依赖这一点（配置页全程复用同一实例）。若把 slot 换成 Compose，页面会被重组，用户填到一半的供应商表单在切「模型」再切回来时清空。要迁移必须先重构 `ProviderEditPage/ProviderModelsPage` 的返回类型与状态持有方式，属独立课题。 |

**已删除（本轮）**：`SwipeRow`(185)、`GlassCapsule`(303)、`LamdaUiKit`(154)、
`ApiPageKit`(253)、`UpdatePrompt` 的 View 实现（107 行改 Compose）。
判别标准：符号名全仓 grep 且排除自身与注释后为 0 处代码引用。

---

## 三点五、已知的取舍（不是遗漏）

下面几项是**显式决定不做**，每条都有依据；写在这里是为了避免被反复当成"漏做"重新提出。

| 项 | 现状 | 为什么不做 |
|---|---|---|
| `6dp` ×122 / `10dp` ×99 硬编码 | 分散在 **40 个文件** | 这两个值遍布聊天、记忆、导入、人偶等页面，机械替换会改变大量页面的实际观感；而本轮**没有实机视觉验收**，改坏了发现不了。收益（数字整齐）小于风险（观感回归无人察觉）。等有真机比对后再做。 |
| 设置页「实验」卡 7 行（设计稿要求每组 ≤5） | 保留单卡 | 该卡内部已是三个分组（1/2/3 行），每组都不臃肿。若拆成三张独立卡，首页卡片数从 5 涨到 7，与「减少卡片数、组内用发丝线」的方向相反。满足规则精神、不满足字面行数。 |
| `line` 三档色值比设计稿深一档 | 已偏离 | 设计稿值实测对比度 1.20–1.28，低于 1.3 的可辨识下限；而「层级由发丝线承担」是方向 A 的核心手法。详见 `ThemeManager.kt` 内的对照表。若改回设计稿值，`palette_contrast.py` 会报红。 |
| `UiKit` 里残留的 View 工具（`press`/`dp`/`round`…） | 保留 | 它同时是**颜色字段的唯一写入目标**（29 个可变字段）。颜色迁走难、风险高，而 View 侧浮层（桌宠、悬浮输入条）仍在读这些字段。 |
| `e1` elevation 无接入点 | 常量就位、未使用 | 设计稿里 e1 用于「被按下的卡片」，而本仓这两类已用底色/缩放表达（`pressScaleClick`），硬加会与既有按压反馈打架。 |

## 四、质量门禁

| 脚本 | 项数 | 状态 |
|---|---|---|
| `tools/ui_audit.sh` | 43 | 全通过 |
| `tools/anim_audit.sh` | 101 | 全通过 |
| `tools/palette_contrast.py` | 36 项实测值 | 全通过 |
| `tools/kotlin_precheck.py` | 273 文件 | 未发现问题 |
| `tools/comment_balance.py` | 273 文件 | 全部平衡 |

**为什么自建这些脚本**：本机是 arm64 手机上的 proot Ubuntu，官方 SDK 只发 x86-64 版
`aapt2`，arm64 版在 GitHub 上反复断流取不到，因此 `./gradlew compileDebugKotlin`
必然卡在 `processDebugResources`。CI 一轮 7–8 分钟，纯靠它试错代价过高。
三个脚本分别覆盖「漏 import / 括号 / 注解错位」「块注释嵌套」「配色对比度」——
都是本轮真实踩过或算出来的缺陷类型。

---

## 五、复现命令

```bash
bash tools/ui_audit.sh          # 设计系统一致性
bash tools/anim_audit.sh        # 动画与工程卫生
python3 tools/palette_contrast.py
python3 tools/kotlin_precheck.py
python3 tools/comment_balance.py

# 真实编译与出包只能走 CI（本机缺 aarch64 aapt2）：
gh workflow run build.yml --ref feat/compose-ui-unification
gh run download <run-id> -n dollhouse-apk
```