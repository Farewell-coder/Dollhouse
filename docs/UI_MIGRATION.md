# Dollhouse UI 迁移清单

> 分支 `feat/compose-ui-unification` · 从 `master` @ `58f8a39`（v0.2.6）拉出
> 本文如实记录**已完成**、**保留**、**删除**与**未完成**四类，不粉饰进度。

---

## 一、已完成

### 1.1 设计系统（M1）

| 项 | 内容 |
|---|---|
| 唯一令牌源 | 新增 `ui/design/DesignSystem.kt`：`Type`/`Space`/`Size`/`Radius`/`Motion`/`Palette` |
| 收敛重复刻度 | `ui/theme/Design.kt`、`ui/compose/DhKit.kt`、`ui/theme/UiKit.kt` 三处同义常量改为委托 `DhDesign`（消除「卡片间距 12 vs 10」「图标间距 8 vs 10」「触控 48 vs 40」） |
| 配色换新 | `ThemeManager` 三档调色板换成方向 A（暖纸 `#F7F4EE` / 暖炭 `#171510` / 纯黑 `#000000`），29 格长度不变 |
| M3 投影 | `DollhouseTheme` 提供 `MaterialTheme`（ColorScheme/Typography/Shapes），全部由 `Palette` 派生；material3 组件不再落回默认紫与默认字号 |
| 单一强调色 | 开关开启色由固定薄荷绿改为跟随主色；卡片标题图标由主色改中性色 |
| 字体分层 | 品牌档（霞鹜文楷）仅用于 App 名/人偶名/头像首字；正文回落系统无衬线 |

### 1.2 组件库（M2）

新增 `ui/kit/Components.kt`：`DhButton`/`DhField`/`DhRow`/`DhGroup`/`DhDivider`/
`DhChip`/`DhTag`/`DhState`/`DhSkeletonRow`/`DhIconButton`。
固定指标具名化：按钮 44/sm34、输入框 40、chip 26、tag 20、状态图标座 56。
输入框补齐聚焦反馈（聚焦前无描边 → 聚焦时 card 底 + accent 描边 160ms）。
按压缩放全 App 统一 `PRESS_SCALE = 0.98`（Compose 与 View 两侧同名常量）。

### 1.3 布局与层级（M3/M4/M5）

| 改动 | 之前 | 之后 | 影响面 |
|---|---|---|---|
| 列表行 | 每行一个 card 底 + 描边 + 16 圆角 | 行直接铺在页面上，1dp 发丝线分隔 | 全部使用 `ValueRow`/`SwitchRow`/`PermRow` 的页面、聊天列表 |
| 卡片标题 | 15sp/Bold | section 档 16sp/600 | 全部 `DhKit.Card` 调用点 |
| 分组小标题 | 13sp 常规（与说明文字同款） | label 档 11sp/600 + 字距 | 全部 `SectionLabel` |
| 卡片描边 | 半透明 stroke | `line` 发丝线 | 全部卡片 |
| 底栏 | 62dp、选中实心色块、13sp/Bold 标签 | 64dp、弱胶囊底、11sp/600 | 主界面底栏 |
| 设置页实验卡 | 7 个混杂入口平铺 | 按语义分三组（对外连接/人偶行为/对话与模型） | 设置页 |
| 气泡几何 | 四角一律 16、内边距 8v/12h | 朝向发出者下角收 6、10v/14h | 聊天页 |

### 1.4 动效（M3）

- 覆盖页转场：进入 220ms 淡入 + 16dp 上浮（按密度换算像素）；退出 180ms 纯淡出。
  两条时间轴不同，消除「两层同时半透明」的重影。
- 修正 `anim_audit.sh` 五处失效判据（B10/B11/C11/C12/E06/E07）——它们分别检查了
  已被撤掉的动画句柄、已被推翻的「进出同时长」口径、写死的旧版本号。
  判据改为指向**问题本身**而非某个文件名。

### 1.5 真实缺陷修复

这几个不是「优化」，是**功能性不可读**：

| # | 缺陷 | 实测 | 修复 |
|---|---|---|---|
| 1 | 我方气泡白字压浅灰底 | 对比度 **1.23:1**（要求 4.5） | 两侧统一用 `title` → 12.22/12.17/15.44 |
| 2 | 未读徽标白字压浅红底 | 暗色/纯黑 **2.48/2.69:1** | 新增派生色 `onErr` 按对比度取黑/白 → 三档 4.94/6.06/5.59 |
| 3 | 浅色档发丝线 | **1.20:1**，分隔线几乎看不见 | 加深至 → 1.37/1.44 |
| 4 | 纯黑档描边 | **1.13:1** | → 1.19 |
| 5 | 暗色/纯黑档发丝线 | 1.28 / 1.24 | → 1.46 / 1.38 |

### 1.6 架构收敛（M8/M9）

| 删除 | 行数 | 判定依据 |
|---|---|---|
| `ui/widget/SwipeRow.kt` | 185 | 全仓符号 grep 排除自身与注释后 **0 处引用**；功能由 `compose/DhSwipe` 承接 |
| `ui/widget/GlassCapsule.kt` | 303 | 同上；底栏统一走 `DhKit.NavBar` |
| `ui/theme/LamdaUiKit.kt` | 154 | 同上；`LamdaPage` 已自带 Compose 等价物 |
| `ui/widget/ApiPageKit.kt` | 253 | 迁移兜底页后仅剩 `findActivity`，与 `UiKit.findActivity` 逐字等价 |
| `UpdatePrompt` 的 View 实现 | 107→190 | 改为 Compose `AlertDialog`（自身重构，非删除） |

迁移：供应商模块两处兜底页（已删除/尚未保存）由手拼 `FrameLayout` 改 `DhKit.Page + DhState`，
顺带修掉「根背景 `setColor(UiKit.BG)` 盖死全局背景图」的缺陷。

**效果**：`ui/` 内 View 实例化文件 **18 → 8**（全仓含 pet/裁剪页 13 → 12，因为裁剪页与桌宠仍在白名单内）。

---

## 二、保留的特殊 View（附理由）

| 文件 | 行数 | 理由 |
|---|---|---|
| `pet/PetWindow.kt` | 2390 | 桌宠自绘帧动画（`drawBitmapMesh` + WindowManager 悬浮窗），Compose 无对等能力 |
| `pet/PetTalkInput.kt` | 334 | 悬浮窗 `EditText`：焦点与 IME 必须在系统窗口层，摘窗须先收软键盘 |
| `BackgroundCropActivity.kt` | 682 | 裁剪手势与位图变换引擎；引擎不迁 |
| `CoverCropActivity.kt` | 878 | 同上 |
| `ui/compose/LayerShield.kt` | 272 | 触摸闸门须在层根 `dispatchTouchEvent` 拦子树，Compose 无等价物 |
| `ui/theme/UiKit.kt` | 2305 | 颜色字段唯一写入目标 + `press`/`dp`/`round` 等 View 工具 |
| `ui/theme/Icons.kt` | 642 | View 侧图标 tint，与 Compose 共用 |
| `ui/home/HomeUi.kt` | 1565 | 底座装配（FrameLayout + 3×ComposeView + 悬浮底栏），改动会触碰 `LayerGate` |
| `ui/chat/ChatPanel.kt` | 2281 | `LinearLayout` 壳 + 内嵌 `ChatUi`(Compose)，承载全部聊天业务契约 |
| `ui/chat/MessageMenu.kt` | 257 | 长按菜单 + 输入框，桌宠与聊天页共用 |
| `ui/widget/SheetPanel.kt` | 521 | 自建底部面板，依赖 View 层序 |
| `ui/provider/ProviderDetailPage.kt` | 293 | 供应商详情壳（FrameLayout 叠悬浮底栏），内容页已是 Compose |

---

## 三、尚未完成（如实记录）

| 项 | 现状 | 说明 |
|---|---|---|
| `SheetPanel` → Compose | 未做 | 521 行自建底部面板（模型选择/思考过程），依赖 `layer` 这个 ViewGroup 层序；迁移须同时改聊天页层级，风险高 |
| `MessageMenu` → Compose | 未做 | 原生 `Dialog` + `EditText`，桌宠与聊天页共用；改 Compose 要给桌宠侧另找宿主 |
| `ChatPanel` 壳去 View | 未做 | 2281 行、承载全部聊天业务方法契约（`addBubble`/`loadEarlier`/`scrollToSummary`…），需先冻结契约清单 |
| `HomeUi` 底座去 View | 未做 | 三 Tab 显隐式管理改状态驱动会触碰 `LayerGate` 与页面栈，须整批一起做 |
| 裁剪页 UI | 未做 | 引擎不迁，只可迁 UI 层手势与控件 |
| `MainActivity` → `ComponentActivity` | 未做 | 原计划 M0；因它重写了 `onRetainNonConfigurationInstance`（`ComponentActivity` 中为 `final`），需先用 ViewModel 等价替换草稿传递，属高风险改动，本轮未动 |
| 真机视觉验收 | 未做 | 需要安装 CI 产出的 APK 并回传截图；本轮所有对比度是**算出来的**，不是看过实机 |

### 为什么这些没做完

本机是 arm64 手机上的 proot Ubuntu，官方 SDK 只发 x86-64 版 `aapt2`，
arm64 版在 GitHub 上反复断流取不到 → `./gradlew` 必然卡在 `processDebugResources`。
唯一编译通道是 CI（一轮 7–8 分钟）。
在上述四项高风险改动上盲改、且无法本地验证，违反「不得为进度牺牲稳定性」，
故选择先交付稳定可验证的部分，并把剩余项明确记录。

---

## 四、验证结果

| 门禁 | 结果 |
|---|---|
| `tools/ui_audit.sh` | **43/43 通过** |
| `tools/anim_audit.sh` | **101/101 通过** |
| `tools/palette_contrast.py` | 三档 × 13 项真实值全部达标 |
| `tools/kotlin_precheck.py` | 273 文件，未发现问题 |
| `tools/comment_balance.py` | 273 文件，全部平衡 |
| CI `build.yml` | 17 个提交全部 success（含 `./gradlew test` 40+ 单测 + `assembleRelease` + `apksigner verify`） |

**业务保护**：`PersonaIsolationTest`、`ChatDraftStoreTest`、`ImportSourceTest` 等
既有单测全部通过；未改数据层、协议与会话/记忆/人格隔离逻辑；未删任何功能入口。

### 4.1 交互态与状态一致性门禁（两个新增脚本）

「代码里有」不等于「页面上有」。上一轮的真实例子：聚焦态写在 `DhField` 里，
但没有任何页面调用它，53 处输入框仍然聚焦无反馈；而当时 `kotlin_precheck.py`
（查编译）与 `ui_audit.sh`（查架构 / 色彩口径）**都看不见这类静默失效**。
为此新增两个专门的静态脚本。

#### `tools/input_audit.py` — 输入框交互态

```
python3 tools/input_audit.py            # 人类可读（逐项 PASS/FAIL + 定位）
python3 tools/input_audit.py --quiet    # 只打结论（CI 用）
```

| 判据 | 检查什么 | 为什么单列 |
|---|---|---|
| I1 | 三个规范入口都存在（`DhField` / `DhForm.Input` 的 `LegacyInput` / `DhForm.InputMultiline`） | 入口被删或改名时立刻发现，而不是等页面各自退化 |
| I2 | 入口内有 `collectIsFocusedAsState` | 没有它就没有聚焦态 |
| **I3** | **`BasicTextField` 的 `interactionSource` 必须是「本函数 remember 出来的」且「正是被 `collectIsFocusedAsState` 订阅的那个」** | **最关键的一环**，见下 |
| I4 | `animateColorAsState` + `tween(DhDesign.Motion.micro)` | 聚焦切换要有 160ms 过渡，不能瞬跳 |
| I5 | 圆角走 `DhDesign.Radius`、高度走 `DhDesign.Size` | 防止入口自身写裸值，成为新的分叉点 |
| I6 | 全仓除入口实现文件外，是否还有页面自建 `BasicTextField` **且未传 `interactionSource`** | 绕过入口是「规范形同虚设」的入口 |

**I3 为什么是三条而不是一条**（这一项经过两次自测才立住）：

1. 不传 `interactionSource` → Compose 内部自建一个，外层 source 永远收不到事件，
   `collectIsFocusedAsState()` 恒为 false。**能编译、能运行、聚焦色永不变。**
2. 传了，但传的不是本函数 remember 的那个 → 同上，仍然恒 false。
3. 传了、也是本函数的，但**不是被订阅的那个** → 函数里若存在两个 source，
   传 A 订阅 B，聚焦色同样永不变。这是最难发现的一种。
   实测：向 `DhField` 插入一个 `decoy` source 并把它传给 `BasicTextField`，
   早期版本的 I3 判 **PASS**（假绿）；现在的 I3 会判 FAIL 并指出被订阅的是 `source`。

**判据实现要点**：脚本先按花括号配平切出函数体，再做判定；去注释时**保留换行**，
保证报告的行号与真实文件一致（早期版本因丢弃换行把 `ChatUi.kt` 的真实 1217 行
报成 1140，行号错了等于把人带到错误位置）。

**当前基线（如实记录，未修）**：

```
输入框审计：PASS 22 / FAIL 0（共 22 项）
```

三个入口（`DhField` / `DhForm.Input` 的 `LegacyInput` / `DhForm.InputMultiline`）全部满足
I1–I5，全仓也**没有**绕过入口且缺聚焦态的 `BasicTextField`。

**这项门禁已经抓到过一次真缺陷**：脚本首次运行时 I6 报出
`ui/chat/ChatUi.kt` 的聊天页输入框自建 `BasicTextField` 且未传 `interactionSource`
（聚焦时界面毫无变化，53 处规范输入框之外唯一漏网的一处）。
该问题随后被并行修复（现该文件已有 `inputFocus` + `collectIsFocusedAsState` + 传参三件套），
**脚本在修复后自动转为全绿** —— 这是门禁真正有效、而不是恒绿的直接证据。

> 快照：2026-11-07。`ChatUi.kt` 仍在被并行修改，行号以脚本实时输出为准。

这一处是**真实缺陷**（聊天输入框聚焦时无任何变化），修它需要改 `ChatUi.kt`，
不属于本脚本任务的范围，留待后续。

#### `tools/state_audit.py` — 空 / 加载 / 错误 / 禁用

```
python3 tools/state_audit.py            # 人类可读（含清单）
python3 tools/state_audit.py --quiet    # 只打结论
```

| 判据 | 检查什么 | 退出码影响 |
|---|---|---|
| S1 | 规范骨架 `DhState` 在本仓存在 | 不拦 |
| S2 | `DhState` 被**实际调用**（不是死代码） | 不拦 |
| S3 | 清点自建空态（函数名 `EmptyState`/`Empty`，或函数体内**真的画出**空态文案 + 有可点动作） | 不拦（只统计） |
| **S4** | **纯空白态 `Box(Modifier.fillMaxSize())` 且框内无任何内容** | **非零即 FAIL**（设计稿明令禁止） |
| S5 | 加载骨架 `DhSkeletonRow` 被实际使用 | 不拦 |

**S4 是唯一会拦门禁的一项**：其余项只统计。「自建空态」是历史存量，
一次性打红会让门禁失去参考价值；把它做成**可见的覆盖率数字**才可管理。

**S3 的判定坑（已修）**：不能只看「函数里出现空态文案」——
`MyProfile.name(act).ifBlank { "还没有取名字" }` 是**字段默认值**，不是空态。
早期版本因此把 `SettingsScreen.ProfileCard` 误判成自建空态。
现在要求文案必须出现在 `Text(...)` / `Note(...)` / `Label(...)` 的**实参里**才算。

**当前基线（如实记录）**：

```
状态审计：已规范 1 处 / 自建 5 处（规范覆盖率 17%）；纯空白态 0 处
检查项：PASS 5 / FAIL 0（共 5 项）
```

- 已规范 1 处：`ui/provider/ProviderDetailPage.kt` 的 `DhState`
- 自建待收编 5 处：`ChatListScreen` 的 `EmptyState`、`ContactScreen` 的 `EmptyState`、
  `ProviderListPage` 的 `ListContent`、`DeveloperOptionsPage` 的 `Empty`、
  `MemPage` 的 `MemFolderDialog`
- 纯空白态：**0 处**（`ChatListScreen` 曾经的空白 Box 已在 M4 换成骨架行）

> **行号以脚本输出为准，本文不抄行号。** 这些文件仍在被并行修改，
> 写死行号会在一小时内失效。需要定位时跑 `python3 tools/state_audit.py`，
> 它会打印 `文件:行号`。同理，本文其它处的行号若与脚本不一致，
> **一律以脚本为准**。

**解释**：规范覆盖率 17% 说明骨架 `DhState` 已经「建好但没落地」——
与上一轮 `ui/kit` 组件库的处境相同。后续应把 5 处自建空态逐个换到 `DhState`，
每换一处覆盖率上升；这两个数字就是迁移进度。

#### 自测记录（防「检查脚本自己假绿」）

两个脚本都做过**注入式自测**：先制造缺陷 → 确认脚本报 FAIL → 删除缺陷 → 确认回到基线。
自测文件在 `_auditprobe` 包下临时创建，验证后已删除。

| # | 制造的缺陷 | 期望 | 实测 |
|---|---|---|---|
| 1 | 新建 `ProbeField.kt`：自建 `BasicTextField` 且不传 `interactionSource` | I6 FAIL 并给出 `ProbeField.kt:15` | FAIL，行号准确 |
| 2 | 在 `DhField` 内插入 `decoy` source 并传给 `BasicTextField` | I3 FAIL | 早期版本 **PASS（假绿）** → 已修，现 FAIL 并指出被订阅的是 `source` |
| 3 | 新建 `ProbeBlank.kt`：`Box(Modifier.fillMaxSize())` 空着 | S4 FAIL，退出码 1 | FAIL `ProbeBlank.kt:12`，exit 1 |
| 4 | 把 `DhField` 的 `interactionSource = source` 整行删掉 | I3 FAIL | FAIL：「未传 interactionSource → 聚焦态恒为 false」 |

第 2 项是本节最有价值的一条：它证明「写了检查」本身也可能假绿，
必须用**注入缺陷**的方式验证检查器，而不是看它输出 PASS 就放心。

---

## 五、回退方式

每个阶段是独立提交，可单独 `git revert`：

```
M1 8cdb33c  设计系统
M2 10fc00f  组件库 + ui_audit
M3 433164f  转场
M4 2757dec  聊天列表 / 8995f8a 设置分组 / 6cadab2 行统一
M5 7a40ffb  底栏
修复 fd4ef03 对比度 / 181181a 气泡 / <badge+字号>
M8 76545ce  供应商兜底页
M9 f59b3c4  死代码删除
```

`master` 未改动，随时可整体回退。