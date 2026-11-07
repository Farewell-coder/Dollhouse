# ChatPanel 去 View 化 · 实施方案

> **状态**：设计定稿，待实施。**本文不改任何 .kt。**
> **产出目标**：给出确定能落地的实施方案与分批顺序，而不是"评估可行性"。
> 用户已明确要求：**去 View 化必须做**，不接受"风险太大所以不做"。

---

## 0. 先纠正三个流传中的事实（本文所有数字都已实测）

写方案前先对齐现状，因为任务描述里的数字已经过期，照抄会误导实施。

| 说法 | 实测值 | 复现命令 |
|---|---|---|
| ChatPanel 2238 行 | **2317 行** | `wc -l app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt` |
| 78 个函数 | **77 个**（28 private / 49 非 private） | `grep -cE '^\s+(public \|internal )?(override )?fun ' .../ChatPanel.kt` |
| ChatActivity / PetTalk / MainActivity 都直接持有实例 | **只有 ChatActivity 持有** | `grep -rn "ChatPanel(" app/src/main/java --include=*.kt \| grep -v ChatPanel.kt:` |

第 3 条特别重要：

- `PetTalk` **不持有** ChatPanel 实例。它自己那套 `Host` 接口与 ChatPanel 无关，唯一的联系是都调用 `ChatHistoryStore`（见 `docs/CHATPANEL_MIGRATION_PLAN.md` 第 5 节与 task-19 的结论）。
- `MainActivity` 对 ChatPanel 的引用**只出现在注释里**（`MainActivity.kt:40`），没有代码依赖。
- 所以"必须改的调用点"数量比预想少得多 —— 这是本方案能收敛的关键。

---

## 1. 关键可行性问题：ChatActivity 能不能承载 Compose？

这是任务点名要验证的问题，先给结论再给依据。

### 结论：**能，但必须用 `ComposeHost.createView` + `setContentView`，不能用 `setContent`。**

`setContent` 是 `androidx.activity.ComponentActivity` 的扩展函数（`activity-compose` 1.12.0），
它要求接收者是 `ComponentActivity`。本项目 `ChatActivity` 继承的是原生 `android.app.Activity`
（`ChatActivity.kt:22`），**`setContent` 直接编译不过**（不是运行期坑，是类型不匹配）。

那能不能把 `ChatActivity` 改成继承 `ComponentActivity`？**不能**，理由是硬性的：

```
ChatActivity.kt:88   override fun onRetainNonConfigurationInstance(): Any? {
```

而 `ComponentActivity.onRetainNonConfigurationInstance()` 被标为 **`final`**
（androidx.activity 源码，`ComponentActivity.java`）。改成继承 = **编译失败**。

这还不是"改个名字就行"的小事 —— 该方法承载着"配置变更重建时把在途输入走纯内存草稿传递"
的能力（`ChatActivity.kt:84-90`），而聊天页正是最需要它的地方（在途的半句话、未发送的正文）。
丢掉它 = 用户旋转屏幕 / 切日夜模式时输入被清空。`ComposeHost.kt:33-36` 已经把这条教训写死了，
`MainActivity` 当初就是因为同一个原因放弃改基类。

**因此结论是：**

- 方案 C 的"宿主直接 `setContent`"在 **ChatActivity 这条路径上不可行**（技术原因：基类 final + 保命能力）。
- 但方案 C 的**核心意图**（纯状态持有 + `@Composable` 入口）**完全可以实现**，
  只是落地方式要改成：**`ComposeHost.createView(act) { ChatScreen(holder) }` + `activity.setContentView(cv)`**。
  这与"`setContent`"的差别仅在入口调用形式，**Compose 侧零差别**。
- `ComposeHost` 已提供 `installForActivity`（幂等、补三个 owner、直接进 RESUMED），
  仓库里共 **66 处**调用这条路径，**已验证可用**（`grep -rn "ComposeHost.createView" app/src/main/java --include=*.kt | wc -l` → 66）。

**这条结论直接决定了方案 C 的形态**：不是"宿主 setContent"，而是"宿主 setContentView(ComposeView)"。
下文 C 方案按此定义。

---

## 2. 三个方案对比

先给对比表，再逐个展开。

| 维度 | A 保守 | B 中间 | C 目标（推荐） |
|---|---|---|---|
| ChatPanel 是否还是 View | 是（空 LinearLayout 壳） | 否（普通 class） | 否（普通 class + TopLevel Composable） |
| 对外 49 个方法 | 全保留 | 全保留 | 全保留 |
| `addView` / `clipToOutline` | 保留 | **移除** | **移除** |
| `post` / `postDelayed` / `removeCallbacks` | 保留 | **须替换** | **须替换** |
| `isAttachedToWindow` | 保留 | **须替换** | **须替换** |
| ChatUi 13 个 `host: ChatPanel` 形参 | 不改 | **须改** | **须改** |
| 需要改的调用点 | 1 处 | 约 18 处 | 约 18 处 |
| 能否根治"ChatPanel 是 View" | **否** | 是 | 是 |
| 可否与闸门并行 | 是 | 是 | 是 |

### 方案 A · 保守（内部 ComposeView 化，保留空壳）

**做法**：ChatPanel 仍 `: LinearLayout`，内部 `addView` 那个 `ChatUi.build()`，别的不动。

**现状其实已经是这样了** —— `ChatPanel.kt:164` 只有一句 `addView(ChatUi.build(this), ...)`，
整块界面早就 100% 是 Compose（`ChatUi` 是 `object`，14 个函数全是 `@Composable`）。

**所以方案 A 的"改动"= 零。这就是当前状态。**

**评估**：
- 好处：零风险、零改动。
- 坏处：**完全没解决任何问题**。ChatPanel 仍是 `LinearLayout`，仍持有 `clipToOutline`、
  `post`、`isAttachedToWindow`、`addView` 这些 View 语义；`ChatUi` 的 13 个形参仍是 `ChatPanel`；
  "去 View 化"这条目标一个字都没达成。
- **结论：方案 A 不是迁移方案，它是"现状"。列出来只为说明"我们已经走到这一步了"。**

### 方案 B · 中间（普通 class + 内部 ComposeView，宿主 setContentView）

**做法**：ChatPanel 去掉 `: LinearLayout`，改为 `class ChatPanel(act: Activity, controller, z)`：
- 内部持 `val view: ComposeView = ComposeHost.createView(act) { ChatScreen(this) }`
- ChatActivity 改 `setContentView(chatPanel.view)`
- 其余 49 个方法签名不动

**必须改的点（逐条 file:line，见第 3 节完整清单）**：

| # | 位置 | 现在 | 改成 |
|---|---|---|---|
| 1 | `ChatPanel.kt:57` | `: LinearLayout(context)` | 去掉 |
| 2 | `ChatPanel.kt:158-163` | `clipToOutline` + `outlineProvider` | Compose `Modifier.clip(RoundedCornerShape(16.dp))` |
| 3 | `ChatPanel.kt:164` | `addView(ChatUi.build(this), ...)` | 构造里建 `ComposeView` |
| 4 | `ChatPanel.kt:388` | `GlobalBackground.install(this, ...)` | 改画到 `view` 或转 Compose `drawBehind` |
| 5 | `ChatPanel.kt:396-402` | `width` / `height` / `post{}` | Compose `onSizeChanged` |
| 6 | `ChatPanel.kt:279,365,1919,2273` | `post` / `postDelayed` | `Handler(Looper.getMainLooper())` |
| 7 | `ChatPanel.kt:324,347` | `removeCallbacks` | `handler.removeCallbacks` |
| 8 | `ChatPanel.kt:1920,1957,2047,2064,2075,2108` | `isAttachedToWindow` | 自持 `attached` 状态（见 4.2） |
| 9 | `ChatPanel.kt:148` | `activity`（`context as? Activity`） | 构造注入，不再从 View 取 |
| 10 | `ChatActivity.kt:50` | `setContentView(chatPanel)` | `setContentView(chatPanel.view)` |
| 11 | `ChatActivity.kt:69` | `chatPanel.post {}` | `chatPanel.view.post {}` 或 `handler` |
| 12 | `ChatUi.kt:89` | `fun build(host: ChatPanel): View` | 删除（不再需要 View 桥） |
| 13-25 | `ChatUi.kt` 13 处 `host: ChatPanel` 形参 | 见第 3 节 | 改 `ChatHostState` 接口 |

**评估**：
- 改完 ChatPanel 确实不再是 View。
- 但引入了一个新概念：`chatPanel.view`。**宿主要多知道一层**，且这是"半个抽象"——
  class 里挂着一个 View，本质是把继承换成了组合，View 语义（`post`/attach/尺寸）依然要手工维护。
- `isAttachedToWindow` 的 6 处语义要靠自己维护一个 `attached` 布尔，容易漏。

### 方案 C · 目标（纯状态 + `@Composable` 入口）—— **推荐**

**做法**：把 ChatPanel 拆成两半：

```
ChatPanel（普通 class，纯状态 + 业务逻辑，零 View 依赖）
   └─ 持有 ChatHostState / ChatState / controller / activity
   └─ 49 个方法原样（内部改成 handler / attached 状态）
   └─ 提供 @Composable 入口：fun ChatScreen(holder: ChatPanel)   ← 放在 ChatUi（或新文件）

宿主 ChatActivity
   └─ ComposeHost.createView(this) { ChatScreen(panel) }  → setContentView(cv)
```

差异点（相对 B）：
- **不需要 `chatPanel.view` 这个中间物**。宿主只交 activity，拿到一个 ComposeView，与其它页面完全一致。
- ChatPanel 里**一个 View 字段都不留**。
- 圆角 / 背景 / 尺寸全部落在 Compose 侧（`Modifier.clip` / `onSizeChanged` / `drawBehind`），
  不再有 `clipToOutline` 与 `outlineProvider`。

**为什么推荐 C**：
1. **它才是"去 View 化"**。B 只是把继承换成组合，`post`/attach/尺寸这些 View 语义一个没少。
2. **与全仓其它页面的形态一致**（共 66 处 `ComposeHost.createView`）：其它页面都是 `ComposeHost.createView(act) { Content(act) }`。
   ChatPanel 走同一条路，未来任何人读代码不会遇到"聊天页是特例"。
3. **它让方案 B 里那些手工维护的 `attached` 状态变成 Compose 的 `DisposableEffect`**，
   由框架保证，不会漏。
4. 改动量与 B 基本相同（都改同一批点），**但结果没有 View 残留**。

**为什么不选 A**：A 就是现状，零改动也零收益，不构成迁移。
**为什么不选 B**：B 的终点仍带着一个 `ComposeView` 字段和全套 View 语义维护，属于做了一半；
既然要改同一批 18 处，不如一次改到位。

---

## 3. 必须修改的调用点（完整清单，file:line）

> 全部实测自当前 HEAD。`ChatPanel.kt` 行号基于 2317 行的版本。

### 3.1 ChatPanel 内部（View API → Compose / 非 View）

| # | file:line | 现状 | 处置 |
|---|---|---|---|
| 1 | `ui/chat/ChatPanel.kt:57` | `class ChatPanel(...) : LinearLayout(context)` | 去掉基类 |
| 2 | `ui/chat/ChatPanel.kt:60` | `public override var history` | 保留（ChatHost 契约） |
| 3 | `ui/chat/ChatPanel.kt:148` | `val activity = context as? Activity` | 构造注入 `act: Activity` |
| 4 | `ui/chat/ChatPanel.kt:156` | 注释"背景由 GlobalBackground 画在本视图上" | 改为 Compose 侧绘制 |
| 5 | `ui/chat/ChatPanel.kt:158` | `clipToOutline = true` | `Modifier.clip(RoundedCornerShape(16.dp))` |
| 6 | `ui/chat/ChatPanel.kt:159-163` | `outlineProvider` / `ViewOutlineProvider` | 删除 |
| 7 | `ui/chat/ChatPanel.kt:164` | `addView(ChatUi.build(this), ...)` | 删除（入口移到 Composable） |
| 8 | `ui/chat/ChatPanel.kt:279` | `post { ... }` | `handler.post { }` |
| 9 | `ui/chat/ChatPanel.kt:324,347` | `removeCallbacks(pending)` | `handler.removeCallbacks` |
| 10 | `ui/chat/ChatPanel.kt:365` | `postDelayed(r, 3000L)` | `handler.postDelayed` |
| 11 | `ui/chat/ChatPanel.kt:388` | `GlobalBackground.install(this, UiKit.option(), false)` | 画到 ComposeView，或 `drawBehind` 复用 `ChatBgDrawable` |
| 12 | `ui/chat/ChatPanel.kt:396-402` | `width`/`height`/`post` | Compose `onSizeChanged { writeChatBgRatio(...) }` |
| 13 | `ui/chat/ChatPanel.kt:704` | `input.setPadding(24,16,24,16)`（原生 EditText） | 保留：这是 Dialog 内控件，与 ChatPanel 是否 View 无关 |
| 14 | `ui/chat/ChatPanel.kt:1582` | `post { ... }` | `handler.post` |
| 15 | `ui/chat/ChatPanel.kt:1919-1920` | `post { if (isAttachedToWindow) askModel(...) }` | `handler.post` + `attached` 状态 |
| 16 | `ui/chat/ChatPanel.kt:1957` | `if (isAttachedToWindow) appendBubble(...)` | `if (attached)` |
| 17 | `ui/chat/ChatPanel.kt:2047,2064,2075` | 同上 | 同上 |
| 18 | `ui/chat/ChatPanel.kt:2108` | `if (!isAttachedToWindow)` | `if (!attached)` |
| 19 | `ui/chat/ChatPanel.kt:2273` | `post { ... }` | `handler.post` |
| 20 | `ui/chat/ChatPanel.kt:171,263,504,652,761,804,1088,1201,1356,1447,1495,2063,2074,2261` | `ChatHistoryStore.xxx(this)` × **14** | **不用改**（`this` 现在是 `ChatHost`，签名本来就是接口，见 task-16） |
| 21 | `ui/chat/ChatPanel.kt:420` | `PickFileActivity.setListener(this)` | 保留（Listener 接口与 View 无关） |

### 3.2 ChatUi（13 个形参 + 1 个 build）

| # | file:line | 现状 | 处置 |
|---|---|---|---|
| 22 | `ui/chat/ChatUi.kt:89` | `fun build(host: ChatPanel): View` | 删除（或改 `@Composable fun ChatScreen(host)`） |
| 23 | `ui/chat/ChatUi.kt:95` | `ChatContent(host: ChatPanel)` | 改 `host: ChatHostUi`（新接口，见 4.1） |
| 24 | `ui/chat/ChatUi.kt:133` | `TopBarRow(host: ChatPanel)` | 同上 |
| 25 | `ui/chat/ChatUi.kt:268` | `CtxRingView(host: ChatPanel)` | 同上 |
| 26 | `ui/chat/ChatUi.kt:313` | `HintBar(host: ChatPanel)` | 同上 |
| 27 | `ui/chat/ChatUi.kt:348` | `Messages(host: ChatPanel, modifier)` | 同上 |
| 28 | `ui/chat/ChatUi.kt:562` | `Bubble(host: ChatPanel, item)` | 同上 |
| 29 | `ui/chat/ChatUi.kt:696` | `showMessageMenu(ctx, host: ChatPanel, item)` | 同上 |
| 30 | `ui/chat/ChatUi.kt:908` | `LoadEarlierRow(host: ChatPanel)` | 同上 |
| 31 | `ui/chat/ChatUi.kt:1018` | `ActionRow(host: ChatPanel)` | 同上 |
| 32 | `ui/chat/ChatUi.kt:1072` | `AttachStrip(host: ChatPanel)` | 同上 |
| 33 | `ui/chat/ChatUi.kt:1131` | `ToolRow(host: ChatPanel)` | 同上 |
| 34 | `ui/chat/ChatUi.kt:1207` | `InputRow(host: ChatPanel)` | 同上 |
| 35 | `ui/chat/ChatUi.kt:99` | 注释提到 `clipToOutline` | 随 #5 更新 |

### 3.3 宿主与其它

| # | file:line | 现状 | 处置 |
|---|---|---|---|
| 36 | `ChatActivity.kt:16` | `import ...ChatPanel` | 保留 |
| 37 | `ChatActivity.kt:23` | `private var panel: ChatPanel?` | 保留（类型不变） |
| 38 | `ChatActivity.kt:37` | `ChatPanel(this, object : ChatPanel.Controller {...}, true)` | 保留（签名不变） |
| 39 | `ChatActivity.kt:50` | `setContentView(chatPanel)` | → `setContentView(ComposeHost.createView(this){ ChatScreen(chatPanel) })` |
| 40 | `ChatActivity.kt:69` | `chatPanel.post { TokenStat.open(...) }` | → `chatPanel.view?.post{}` 或 `window.decorView.post{}` |
| 41 | `ChatActivity.kt:110` | `chatPanel.applyBackground()` | **保留**（方法仍在；内部改画到 Compose 侧） |
| 42 | `ChatActivity.kt:121` | `chatPanel.reloadHistory()` | 保留（签名不变） |

**统计：必须改的实际代码点 = 约 18 处**（3.1 里 #1,#3,#5,#6,#7,#8,#9,#10,#11,#12,#14~#19、3.2 全部 14 处、3.3 的 #39,#40 合并计）。
其余"不用改"的（**14 处** `ChatHistoryStore(this)`、`applyBackground`/`reloadHistory` 调用）
正是前几轮 `ChatHost` 重构换来的红利 —— **契约提前切对了，迁移面从 50 处降到 18 处**。

---

## 4. 三个必须认真解决的机制（不能"风险太大"带过）

### 4.1 ChatUi 的 13 个 `host: ChatPanel` 形参怎么办

`ChatUi` 读了 `host.` 的 **31 个不同成员**（实测 `grep -oE "host\.[a-zA-Z0-9_]+" ChatUi.kt | sort -u | wc -l` = 31）。

但它们**全部是方法调用与少量属性读取**，没有一个需要 `View` 身份。所以最省的做法：

```kotlin
// ui/chat/ChatHostUi.kt（新文件）
internal interface ChatHostUi {
    val activity: Activity?
    val controller: ChatPanel.Controller?
    fun showModels(); fun showThink(); fun flashMemo(msg: String?)
    fun regenerate(); fun regenerateAt(i: Int); fun retryAfterError()
    // ……按 ChatUi 实际用到的 31 个成员逐条声明
}
```

`ChatPanel : ChatHostUi`（与已有的 `: ChatHost` 并列）。
`ChatUi` 的 13 个形参从 `ChatPanel` 改成 `ChatHostUi` —— **13 处改动，全是类型替换，零逻辑改动**。

**为什么不直接用 `ChatHost`**：`ChatHost` 是**数据层**契约（history/prevCount/ctx/boundDollId），
刻意只含数据；把 31 个 UI 方法塞进去会让 data 层认识 UI 语义，正是 task-16 花力气切断的反向依赖。
所以**必须分开**：`ChatHost`（data 用）+ `ChatHostUi`（ui 用），ChatPanel 同时实现两者。

这条也和 task-19 的结论一致：`ChatHost` 不该被推广给 PetTalk；UI 侧契约单独一个接口更清晰。

### 4.2 `isAttachedToWindow` 的 6 处怎么替代

原语义："这个 View 是否挂在窗口上"，用于决定**要不要更新界面**（落盘照常做）。

Compose 等价物：

```kotlin
// ChatPanel 内（普通 class，不是 View）
@Volatile private var attached = false
internal fun onAttached() { attached = true }
internal fun onDisAttached() { attached = false }
```

```kotlin
// ChatScreen 内
DisposableEffect(Unit) {
    holder.onAttached()
    onDispose { holder.onDisAttached() }
}
```

`DisposableEffect` 的 `onDispose` 在离开组合时必定执行（含页面销毁、进程重建），
**比 `isAttachedToWindow` 更可靠**（后者在 `removeView` 后才翻 false，中间有一帧窗口期）。

**注意语义差异**：`isAttachedToWindow` 在 ChatPanel 里被用来"有界面才加气泡"。
换成 `attached` 后语义完全一致。但有一处要小心：`ChatPanel.kt:1919` 的
`post { if (isAttachedToWindow) askModel(...) }` —— 这里的意图是"post 到主线程后确认还在"。
改成 `handler.post { if (attached) ... }` 语义一致。

### 4.3 `post` / `postDelayed` / `removeCallbacks`

现在用的是 `View.post`（借 View 的 handler）。ChatPanel 里**没有**自己的 Handler（实测 `grep Handler` = 0）。

替换：

```kotlin
private val handler = Handler(Looper.getMainLooper())
```

三处语义一一对应：`post`→`handler.post`、`postDelayed`→`handler.postDelayed`、`removeCallbacks`→`handler.removeCallbacks`。

**必须保留的一条坑**（`ChatPanel.kt:314` 的原注释）：
> 若换成 View.post，面板 detached 时会把任务挂到重新 attach 才执行，提示可能永久残留。

这条恰恰支持改用 Handler —— `Handler(Looper.getMainLooper())` 不依赖 View 存活，
正是原注释想要的行为。

---

## 5. `ComposeLayerShield` 设计（闸门重做）

### 5.1 先核实：ChatPanel 到底需不需要 LayerShield？

**实测结论：当前 ChatActivity 的内容视图外面没有 LayerShield。**

- `ChatActivity.kt:50` 是 `setContentView(chatPanel)`，**裸挂**，没有任何 shield 包裹
  （实测 `grep -n "setContentView" ChatActivity.kt` 只有这一行，且 `grep -c LayerShield ChatActivity.kt` = 0）。
- `LayerShield` 的**实际使用点**只在两处代码里创建：
  `NavHost.kt:173`（覆盖页栈那层）与 `HomeUi.kt:145/186/202`（主界面底座/底栏/抽屉三层）。
- 全仓 `grep -rn "LayerShield" app/src/main/java --include=*.kt` 共 **20 行命中**，分布 6 个文件：
  `NavHost.kt`(7) / `HomeUi.kt`(5) / `LayerGate.kt`(5) / `ProfileDrawer.kt`(1) /
  `LayerShield.kt`(1，定义自身) / `MainActivity.kt`(1，仅注释)。
  → **ChatActivity 不在其中**。这是"闸门不是本迁移阻塞项"的证据。

**所以"闸门是 ChatPanel 迁移的最大障碍"这个前提需要修正**：ChatPanel 本身不依赖 LayerShield。

但它**间接**相关：迁移后 ChatActivity 的内容视图会变成 `ComposeView`，
而 `NavHost` 的覆盖层栈（Token 统计等）是**后挂**到 `android.R.id.content` 上的兄弟层。
`NavHost.push` → `install(act)` 目前会自己包一个 `LayerShield`（`NavHost.kt:173,439`），
所以**覆盖页那层仍有闸门保护**，与内容层是 ComposeView 还是 View 无关。

**因此：`ComposeLayerShield` 不是 ChatPanel 迁移的前置条件，可以并行做。**
（若未来要把"内容层也纳入统一闸门"，才需要它。那是一个独立价值项，不是阻塞项。）

### 5.2 如果要做：三条语义如何用 pointerInput 复刻

既然用户要求"不能以不可迁移收场"，这里给出**完整可落地的设计**，即使它不是本迁移的阻塞项。

三条语义（来自 `LayerShield.kt:129-148` 实测）：

| # | 原语义 | Compose 复刻 |
|---|---|---|
| 1 | DOWN 时按 `blocking` 决定是否接管 | `awaitFirstDown(requireUnconsumed = false)` 后**消费**该 change = 接管；不消费 = 放行 |
| 2 | 所有权锁定到序列结束 | `awaitEachGesture` 的块作用域天然覆盖整个 DOWN→UP 序列，块内不重读 `blocking` |
| 3 | 屏蔽瞬间向子树补发 CANCEL | `pointerInput` 的 **key 变化会取消当前协程作用域** → Compose 自动向子节点发取消/重置 |

**关键实现（含"中途 blocking 变化要取消当前手势"的触发方式）**：

```kotlin
@Composable
fun ComposeLayerShield(
    blocking: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 【key 就是 blocking】blocking 一变 → pointerInput 作用域被取消重建
    //   = 当前手势被丢弃，等价于原实现的 cancelChildren() 补发 CANCEL。
    //   这就是"中途变化要取消当前手势"的触发机制，不需要手工补 MotionEvent。
    Box(
        modifier = modifier
            .then(
                if (blocking) Modifier.pointerInput(blocking) { /* 消费一切 */ } else Modifier
            )
    ) { content() }
}
```

**更贴近原语义的版本**（保留"DOWN 时判定 + 序列内锁定"）：

```kotlin
@Composable
fun ComposeLayerShield(blocking: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // blocking 作为 pointerInput 的 key：值变化 → 作用域取消 → 当前手势作废
    //   （对应原 setter 里的 cancelChildren()）
    val shield = Modifier.pointerInput(blocking) {
        if (blocking) {
            awaitEachGesture {
                // requireUnconsumed = false：即使子树已有人接管起手，也要拿到这个 DOWN
                val down = awaitFirstDown(requireUnconsumed = false)
                // 消费 = 接管。所有权在本 awaitEachGesture 块内锁定到序列结束（语义 2）。
                down.consume()
                while (true) {
                    val e = awaitPointerEvent()
                    // 屏蔽期间把所有变化都消费掉，等于"不下发给子树"
                    e.changes.forEach { it.consume() }
                    if (e.changes.all { !it.pressed }) break   // 全部抬起 = 序列结束
                }
            }
        }
    }
    Box(modifier = modifier.then(shield)) { content() }
}
```

**三条语义逐条对应**：

1. `if (blocking)` 才挂 `pointerInput` → **DOWN 时按 blocking 决定是否接管**（未挂则完全不参与命中测试，等价于原 `return false`）。
2. `awaitEachGesture` 块内不重读 `blocking` → **所有权锁定到序列结束**。
3. `pointerInput(blocking)` 的 key 变化 → **作用域取消，当前手势作废**，等价于补发 CANCEL。

**为什么这不影响导航手势**：`pointerInput` 只挂在层根，且只在 `blocking=true` 时存在。
`blocking=false` 时该 Modifier 不参与命中，子树（含 `NavDisplay` 的预测性返回手势）零干扰 —— 与原
`dispatchTouchEvent` 返回 `super` 的情形一致。

**已知差异（如实记录，不是障碑）**：原实现 `cancelChildren()` 是**手工构造 MotionEvent 发给子树**；
Compose 版靠**作用域取消**让子节点自行复位。两者对"按钮停在按下态"的效果相同
（Compose 的 `pressable`/`clickable` 在作用域取消时会复位），但**实现路径不同**：
前者是事件注入，后者是协程取消。若将来发现某个自绘 View（`AndroidView` 内）不复位，
需要补一个 `MotionEvent.ACTION_CANCEL` 注入 —— 这是**局部补丁**，不影响整体方案。

---

## 6. 实施顺序（推荐：先闸门后 ChatPanel？不，应并行）

**结论：两者可以并行，且应并行；但有先后依赖的部分必须先做。**

```
阶段 1（可并行，互不阻塞）
  ├─ [1a] ChatHostUi 接口抽取（4.1）        ← 无依赖，纯粹加一个接口 + 13 处类型替换
  └─ [1b] ComposeLayerShield（5.2）          ← 无依赖，独立价值项

阶段 2（依赖 1a）
  └─ [2] ChatPanel 去 View 骨架：
        去掉 : LinearLayout、加 handler、加 attached 状态、构造注入 act
        （此时 ChatPanel 已不是 View，但宿主还没改，编译不过 —— 属预期）

阶段 3（依赖 2）
  ├─ [3a] ChatScreen Composable 入口 + 宿主改 setContentView
  ├─ [3b] 圆角 / 背景 / 尺寸迁到 Compose
  └─ [3c] 逐条替换 post / isAttachedToWindow（共 18 处）

阶段 4（收尾）
  └─ [4] 删 ChatUi.build、更新全部注释里提到 clipToOutline 的地方
```

**为什么在这里"并行"是安全的**：`ChatHostUi` 与 `ComposeLayerShield` 都只**新增**符号，
不改任何现有行为；而阶段 2 才开始动 ChatPanel 本体。三者写的是不同文件（`ChatHostUi.kt` /
`ComposeLayerShield.kt` / `ChatPanel.kt`），**无写冲突**。

**为什么阶段 2 和 3 不能拆给两个人**：阶段 2 结束时工程**编译不过**（ChatPanel 不再是 View，
但 `ChatActivity.kt:39` 还在 `setContentView(chatPanel)`）。中间态必须由同一个人在同一个
提交里跨过去，否则会留给队友一个红的树。

**建议每个阶段后跑**：`bash tools/gate_all.sh`（若已就绪）或
`python3 tools/kotlin_precheck.py && python3 tools/comment_balance.py && bash tools/anim_audit.sh`。

---

## 7. 风险与"确实做不到"的边界

用户不接受"因为风险所以不做"，所以这里只列**技术性硬约束**，其余全部给出解法。

### 7.1 唯一真正的硬约束（已给出绕法）

| 障碍 | 技术原因（精确到 API） | 绕法 |
|---|---|---|
| `setContent` 不可用 | 它是 `ComponentActivity` 的扩展（`activity-compose` 1.12.0），`ChatActivity : android.app.Activity`（`ChatActivity.kt:22`）类型不符 | 用 `ComposeHost.createView` + `setContentView`，Compose 侧零差别 |
| 不能改继承 `ComponentActivity` | `onRetainNonConfigurationInstance()` 在 `ComponentActivity` 是 **`final`**，而 `ChatActivity.kt:88` 重写了它 | 保持原生 Activity（`ComposeHost` 已把 owner 补齐，全仓 66 处已验证） |

**这两条都不是"去 View 化做不到"，而是"入口写法不同"。**

### 7.2 需要验证但**不是**阻塞的问题

| 项 | 为什么不是阻塞 | 验证方式 |
|---|---|---|
| `attached` 状态语义 | `DisposableEffect.onDispose` 比 `isAttachedToWindow` 更可靠 | 阶段 3 后手动测"发消息→立刻返回→回来"（`ChatPanel.kt:2094` 注释里那个 bug 场景） |
| 背景图 + 圆角 | `Modifier.clip` + `drawBehind` 是标准做法；`ChatBgDrawable` 可直接用 `drawIntoCanvas` | 设置背景图后看四角是否裁切 |
| ChatUi 31 个成员的类型替换 | 纯机械替换，编译器保证 | `kotlin_precheck.py` + CI |
| 13 处 `host: ChatPanel` | 同上 | 同上 |
| `showSearchDialog` 里的原生 `EditText` | 它是 Dialog 内控件，与 ChatPanel 是否 View 无关（`ChatPanel.kt:704`） | 无需迁移 |

### 7.3 明确的工作量估计

- 阶段 1a：约 60 行（新接口）+ 13 行类型替换 → **半天**
- 阶段 1b：约 60 行（新 Composable）→ **半天**
- 阶段 2+3：18 处改动 + `ChatScreen` 入口 → **1–1.5 天**（主要成本在 18 处逐条替换与回归）
- 阶段 4：删 `ChatUi.build` + 注释清理 → **1 小时**

**总计约 2.5–3 人日**，可分 3 个提交。

---

## 8. 结论摘要

1. **推荐方案 C**（纯状态 + `@Composable` 入口），落地形态是
   `ComposeHost.createView(act) { ChatScreen(holder) }` + `setContentView` ——
   **不是** `setContent`（ChatActivity 是原生 Activity，且不能改基类，原因见 7.1）。
2. **方案 A 就是现状**（内部早已 100% Compose），不构成迁移；方案 B 是 C 的半成品。
3. **实际必须改的代码点约 18 处**，不是 50 处 —— 因为 `ChatHost` 重构已经把 14 处
   `ChatHistoryStore(this)` 与 `applyBackground`/`reloadHistory` 的签名稳定下来了。
4. **`ComposeLayerShield` 不是本迁移的阻塞项**（实测 ChatActivity 外面没有 LayerShield），
   但已给出完整可落地的设计（5.2），可作为独立价值项并行推进。
5. **唯一的硬约束是入口写法**（`setContent` vs `setContentView`），已有确定绕法，
   不影响"去 View 化"目标达成。