# ChatPanel 表 B 剩余 8 项 · 配对矩阵

- 任务：`task-26`（原 `chat-batch2`，队伍精简后转交）
- **性质：纯分析。本轮没有修改任何 `.kt`。**
- 前置事实：`provider-shell` 的 `ChatHostUi` 接口**已落地**（`ui/chat/ChatHostUi.kt`），
  这直接改变了本批的结论 —— 见 §1。

---

## 0. 结论速览

| # | 成员 | 调用点集合 vs 状态变更点集合 | 判定 | 建议 |
|---|---|---|---|---|
| 1 | `refreshCtxRing` | **不相等**（调用 8 ⊂ 变更 14） | ❌ 不能删 | 保持 + 补 3 处陈旧点，或接受行为变化 |
| 2 | `onInputChange` | 相等（1↔1）但**已成接口契约** | ⚠️ 不能删 | 保持为接口成员 |
| 3 | `clearChat` | 内部 1 处（不可达入口） | ⚠️ 语义特殊 | 保持，见 §3 |
| 4 | `onScrolled` | 相等（1↔1）但**已成接口契约** | ⚠️ 不能删 | 保持为接口成员 |
| 5 | `setMemoBusy` | **已接回**（3 处调用） | ✅ 已完成 | 无需动作 |
| 6 | `openPicker` | 相等（1↔1）但**已成接口契约** | ⚠️ 不能删 | 保持为接口成员 |
| 7 | `showModels` | 相等（1↔1）但**已成接口契约** | ⚠️ 不能删 | 保持为接口成员 |
| 8 | `showThink` | 相等（1↔1）但**已成接口契约** | ⚠️ 不能删 | 保持为接口成员 |

**净结论：8 项里真正"能改/该改"的是 0 项。**
原因不是 8 项都改不动，而是**约束变了**：其中 5 项已成为 `ChatHostUi` 的接口成员，
另 3 项（`refreshCtxRing` / `clearChat` / `setMemoBusy`）各有各的实质阻碍，逐一说明。

> 这也解释了 `chat-batch2` 为什么停在那句
> 「All 8 items are resolving. Let me verify the one genuine equal-set pair
> (showModels/showThink) before deciding」—— 它验的那一对**确实集合相等**（都是 1↔1），
> 但"集合相等"在 `ChatHostUi` 落地之后**不再等于"可以删"**。这是本批的核心陷阱。

---

## 1. 关键前置：`ChatHostUi` 改变了判定规则

`ui/chat/ChatHostUi.kt` 是从 `ChatUi` 的实际用法倒推出来的宿主契约（31 项）。
下面 5 项**已经是它的成员**：

```kotlin
fun showModels()                              // ChatHostUi.kt:86
fun showThink()                               // ChatHostUi.kt:95
fun openPicker()                              // ChatHostUi.kt:122
fun onScrolled(firstVisibleIndex: Int, atBottom: Boolean)  // ChatHostUi.kt:136
fun onInputChange(text: String)               // ChatHostUi.kt:139
```

**为什么这否掉了"改状态驱动"**：

1. 这 5 项的实现体确实都只是「转调 / 写状态」，看起来正适合删掉；
2. 但 `ChatUi` 是通过 `host.xxx()` 调它们的（`ChatUi.kt:1146/1150/1156/394/1242`）；
3. 删掉 = 同时改 `ChatHostUi` + `ChatPanel` + `ChatUi` 三个文件，
   而 `ChatUi` 与 `ChatPanel` 正是本次任务**明确不许碰**的文件。

所以本批对它们只做判定记录，不动代码。

---

## 2. 逐项配对矩阵

### 2.1 `refreshCtxRing` —— 集合不相等（唯一"技术上可以改但会变行为"的一项）

**调用点（8 处，全在 ChatPanel 内）**
```
ChatPanel.kt:175  (init)
ChatPanel.kt:506  (reloadHistory)
ChatPanel.kt:654  (rollbackTo)
ChatPanel.kt:763  (editAndResend)
ChatPanel.kt:806  (regenerateAt)
ChatPanel.kt:1090 (insertSummaryAt → applySummary)
ChatPanel.kt:1203 (deleteAt)
ChatPanel.kt:1360 (clearChat)
```

**状态变更点（history 真正变动的 14 处）**
```
ChatPanel.kt:171  ChatHistoryStore.loadHistory(this)        → 已配对（175）
ChatPanel.kt:263  ChatHistoryStore.prependPrev(this)        → ❌ 未配对（loadEarlier）
ChatPanel.kt:504  ChatHistoryStore.loadHistory(this)        → 已配对（506）
ChatPanel.kt:650  history = arr                             → 已配对（654）
ChatPanel.kt:686  ChatHistoryStore.saveHistory(ctx,arr,newId)→ ❌ 未配对（branchAt，且换会话）
ChatPanel.kt:759  history = arr                             → 已配对（763）
ChatPanel.kt:802  history = arr                             → 已配对（806）
ChatPanel.kt:1086 history = arr                             → 已配对（1090）
ChatPanel.kt:1199 history = arr                             → 已配对（1203）
ChatPanel.kt:1355 history = JSONArray()                     → 已配对（1360）
ChatPanel.kt:1446 history.remove(history.length()-1)        → ❌ 未配对（regenerate）
ChatPanel.kt:1495 ChatHistoryStore.push(this,"user",...)    → ❌ 未配对（onSend）
ChatPanel.kt:2063 ChatHistoryStore.push(this,"assistant",…) → ❌ 未配对（handleReply 空正文）
ChatPanel.kt:2074 ChatHistoryStore.push(this,"assistant",…) → ❌ 未配对（handleReply 纯思考）
ChatPanel.kt:2103 history = saved                           → ❌ 未配对（handleReply 正常）
```

**矩阵结论：调用点 ⊊ 状态变更点，差 6 处。**

如果把 `ChatState.ctxRatio = ...` 搬进 `renderHistory()`（唯一可行的"状态驱动"改法），
这 6 处**会开始刷新进度环**（当前它们让环停在旧值）：

| 未配对点 | 行为变化 |
|---|---|
| `loadEarlier`(263) | 补回一批老消息后，环会涨 —— 现在是**旧值** |
| `regenerate`(1446) | 删掉最后一条后环会缩 —— 现在是**旧值** |
| `onSend`(1495) | 发出用户消息后环会涨 —— 现在是**旧值** |
| `handleReply`(2063/2074/2103) | 收到回复后环会涨 —— 现在是**旧值** |
| `branchAt`(686) | 分支后环不变（换会话了，本来也不该沿用） |

**这些"旧值"是 bug 还是既定观感？** 无法从代码断定（环很小，用户未必注意）。
按任务规则「集合不等 → 不要动，写清哪几处行为会变」，**归为不动**。

> 如需推进，正确做法是**单独立项**「修进度环陈旧值」，明确接受这 5 处的可见变化，
> 而不是夹在"状态驱动重构"里顺手改掉。

### 2.2 `onInputChange` —— 集合相等，但已是接口契约

```
调用点：ChatUi.kt:1242  onValueChange = { host.onInputChange(it) }
状态变更点：ChatState.input = text; ChatDraftStore.save(...)   ← 1 处，就这一个入口
```
集合相等（1↔1）。但它是 `ChatHostUi:139` 的成员、`ChatUi` 通过 `host.` 调用。
按 §1 的规则，**不动**。

> 补充：文档表 B 第 5 项建议"草稿保存改成 `snapshotFlow{ input }.debounce.collect`"。
> 那个改法的价值在于**把落盘从每次按键降频**，属于性能优化而非去 View，
> 且会引入"debounce 期间崩溃丢失草稿"的新风险。建议不并入本批。

### 2.3 `clearChat` —— 语义特殊，不在"状态驱动"范畴

```
调用点：ChatPanel.kt:1346  onConfirm = { clearChat() }   ← confirmClearChat 的确认回调
```
`clearChat` 是**数据操作**（`history = JSONArray()` + 存盘 + 清暂存位），
不是"只写 ChatState 的纯壳"。它唯一的前置门面 `confirmClearChat` 现在是
`ChatHostUi` 的成员（`ChatHostUi.kt:170`），因此整条链路是「接口 → 门面 → 数据」。

**判定**：它本来就不该出现在"可改为状态驱动"表里 —— 那是文档表 B 第 6 项的分类偏差
（"抽成不依赖 View 的 `ChatStore.clear(convId)`"其实是**拆数据层**，属表 A/重构范畴）。
**不动**，建议把这条从表 B 移到「数据层抽取」的待办。

### 2.4 `onScrolled` —— 集合相等，但已是接口契约

```
调用点：ChatUi.kt:394  snapshotFlow{}.collect { host.onScrolled(...) }
状态变更点：滚动本身（Compose 侧 LazyListState）
```
它是「视图 → 宿主」的上报，天然属于接口契约（`ChatHostUi:136`）。
文档表 B 第 7 项建议"判据搬到 Compose 内 `LaunchedEffect`"，那需要改 `ChatUi.kt` ——
**本次不许碰**。不动。

### 2.5 `setMemoBusy` —— 已接回，无需动作

```
调用点：ChatPanel.kt:917  setMemoBusy(true)     ← insertSummaryAt，发起 summarize 之前
        ChatPanel.kt:939  setMemoBusy(false)    ← onDone 失败分支
        ChatPanel.kt:950  setMemoBusy(false)    ← onDone 成功分支
```
**这一项已经做完了**（此前 task-9/task-11 的成果）。文档表 B 第 8 项写的"当前 0 调用"
**已过时**，应更新为「3 处调用，已接回」。

它的目标形态「`ChatState.memoBusy: Boolean`」也不必做了 ——
现有实现已经把状态收在 `ChatState.memoText` 上，且 `flashHold`/`memoBusyOn`
两个私有标志负责时序。**再拆一层 `memoBusy` 字段没有收益**。

### 2.6 / 2.7 / 2.8 `openPicker` / `showModels` / `showThink` —— 集合相等，但已是接口契约

```
openPicker : 调用 ChatUi.kt:1156  host.openPicker()   → ChatPanel.kt:419 实现（转 Activity 跳转）
showModels : 调用 ChatUi.kt:1146  host.showModels()   → ChatPanel.kt:295 实现（转 ChatPanels.showModels）
showThink  : 调用 ChatUi.kt:1150  host.showThink()    → ChatPanel.kt:305 实现（转 ChatPanels.showThink）
```
三项都是 1↔1，且实现体都只有一行转调 —— 正是 `chat-batch2` 想验的那一类。
**但它们是 `ChatHostUi:122/86/95` 的成员**，删掉要同时改三个文件。

**关于 `showModels`/`showThink` 这一对**（`chat-batch2` 最后卡住的点）：
- 集合确实**相等**（各 1 处调用、各 1 处实现）；
- 二者结构**同构**：`host.showX()` → `ChatPanels.showX(UiKit.findActivity(context))`；
- 但"同构"不等于"可合并/可删"：它们的目标形态都在 `ChatPanels`（Dialog 窗口），
  真正该做的是「把 Dialog 改成 Composable 内嵌面板」，那要改 `ChatPanels.kt` + `ChatUi.kt`。
- **判定：不动。** 若未来要做，`showModels` 与 `showThink` 应**一起**改（同一套机制），
  不要只改一个，否则同页出现"一个 Dialog 一个内嵌"两种形态。

---

## 3. 集合相等但不可删 —— 一个必须说明的判定原则

本批有 5 项「调用点集合 = 状态变更点集合」，按文档表 B 的字面标准都"可以改"。
但**集合相等只说明"改写不会改变行为"，不说明"改写是安全的"**。

真正决定能否动的是**这三条额外约束**：

| 约束 | 命中的项 |
|---|---|
| ① 是否为 `ChatHostUi` 接口成员 | `onInputChange` / `onScrolled` / `openPicker` / `showModels` / `showThink` |
| ② 改动是否必须碰受限文件（`ChatUi.kt` / `ChatPanels.kt`） | 上述 5 项全部命中 |
| ③ 是否其实属于别的类别（数据操作而非状态写入） | `clearChat` |

**结论**：表 B 剩余 8 项在当前约束下**没有一项适合现在动**。
这不是"分析失败"，而是**准确的可行性结论** —— 硬改任何一项都会：
- 要么破坏 `ChatHostUi` 契约（引发 `ChatUi` 编译失败）；
- 要么越过本次的文件边界；
- 要么引入可见行为变化（`refreshCtxRing`）。

---

## 4. 建议：表 B 的正确收口路径

表 B 的"状态驱动"目标本身没错，但它**要等 `ChatUi` 解耦之后**才有意义。
建议顺序：

1. **先把 `ChatHostUi` 的 5 个「一行转调」成员合并/内联**
   在 `ChatUi` 改成读 `ChatState` 直接驱动之后（需要改 `ChatUi.kt`），
   这 5 项自然消失，`ChatHostUi` 从 31 项缩到 26 项。**这是表 B 的真正落地方式**。
2. **`refreshCtxRing` 单独立项**为「修进度环陈旧值」，
   明确接受 `loadEarlier`/`regenerate`/`onSend`/`handleReply` 会开始刷新环。
3. **`clearChat` 移出表 B**，归入「抽 `ChatStore` 数据层」。
4. **`setMemoBusy` 从表 B 删除** —— 已完成。
5. 更新 `docs/CHATPANEL_CONTRACT.md` 表 B：
   - 第 8 项 `setMemoBusy` 的"当前 0 调用"**已过时**（实为 3 处）；
   - 第 6 项 `clearChat` **分类有误**（是数据操作不是状态写入）；
   - 表头写"11 项"但表体只有 11 行且其中 3 项已完成，建议标注进度。

---

## 5. 复现命令

```bash
F=app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt

# 各成员调用点（含 host.xxx 形式）
grep -rn 'refreshCtxRing(\|onInputChange(\|onScrolled(\|openPicker(\|showModels(\|showThink(\|setMemoBusy(\|clearChat(' \
  app/src/main/java --include='*.kt' | grep -v 'fun '

# 是否已是 ChatHostUi 成员
grep -n 'fun showModels\|fun showThink\|fun openPicker\|fun onScrolled\|fun onInputChange' \
  app/src/main/java/com/dollhouse/app/ui/chat/ChatHostUi.kt

# history 变更点（refreshCtxRing 的配对基准）
grep -n 'history = \|history\.remove\|ChatHistoryStore\.\(loadHistory\|prependPrev\|collapsePrev\|saveHistory\|push\)' $F

# setMemoBusy 已接回的证据
grep -n 'setMemoBusy(' $F
```

---

## 6. 待确认

| 项 | 需确认什么 |
|---|---|
| 进度环陈旧值是否算 bug | `loadEarlier`/`regenerate`/`onSend`/`handleReply` 后环不更新，是有意（省重组）还是遗漏？决定 `refreshCtxRing` 能否收编 |
| `ChatHostUi` 是否允许在本轮之后扩充/收缩 | 若允许，§4 第 1 步可以开始；若契约要冻结到去 View 之后，表 B 整体延后 |
| `showModels`/`showThink` 是否要改成内嵌面板 | 需要 `ChatPanels.kt` + `ChatUi.kt` 的改动窗口 |
---

## 7. 进度环陈旧值修复方案（Lead 已定口径：**算 bug，修**）

> 口径来源：Lead 2026-11-07 判定 —— 「这个环的唯一职责就是显示占用率。不更新 = 持续显示错误信息。
> 这不是『行为差异』，是缺陷。业务逻辑是『显示真实占用率』，现在它没做到。」
> 本节是**待执行方案**（ChatPanel.kt 当前归 `provider-shell` 独占，尚未动手）。

### 7.1 现状口径

```kotlin
fun refreshCtxRing() {
    val cap = Math.max(1, Mem.MAX_ENTRIES)          // Mem.MAX_ENTRIES = 200
    ChatState.ctxRatio = (history.length() / cap.toFloat()).coerceIn(0f, 1f)
}
```

口径是「**当前消息条数 ÷ 记忆库容量(200)**」，不是字符数。
`ChatState.ctxRatio` 只有一个消费者：`ChatUi.kt:270`（`CtxRingView` 画环）。

**为什么是缺陷**：环停在旧值期间，用户看到的百分比与 `history.length()` 不符 —— 显示错误数据。
`history.length()` 在下列 6 处发生变化，但只有 8 处调用点会刷新它（见 §2.1 矩阵）。

### 7.2 修复策略：把计算收进 `renderHistory()`，删掉独立方法

**主改法（推荐）**：`ChatState.ctxRatio` 的赋值移进 `renderHistory()` 的**最开头**（早于空历史 early-return），
然后删除 `refreshCtxRing()` 方法与它全部 8 处调用点。

**为什么放最开头而不是末尾**：`renderHistory()` 在 `history.length() == 0` 时**提前 return**
（`ChatPanel.kt:2174` 附近）。若放在末尾，清空对话后环不会归零 —— 而「清空后环应为 0%」
正是本次要修的缺陷之一。放在开头可保证**任何**重铺都刷新，包括空历史。

**为什么可以顺带删掉 8 处调用点**：§2.1 已核实这 8 处**全部**紧跟在 `renderHistory()` 之后或同函数内，
收进去之后它们成为重复计算。删除后 `refreshCtxRing` 变 0 调用（与表 B 目标一致）。

### 7.3 5 处"随 renderHistory 一起刷新"的处置

Lead 判定的 5 处（`loadEarlier` / `regenerate` / `onSend` / `handleReply`×3）**不需要单独写刷新代码** ——
它们各自都已经（或可以）经过 `renderHistory()`：

| # | 位置 | 现状 | 收进后行为 | 需要额外改动？ |
|---|---|---|---|---|
| 1 | `loadEarlier`:263 | `prependPrev` → `renderHistory()`（:270 附近，在 try 块内） | 环按新条数刷新 | **否**，自动生效 |
| 2 | `regenerate`:1446 | `history.remove(...)` → `saveHistory` → `renderHistory()`（:1448） | 环缩小 | **否**，自动生效 |
| 3 | `onSend`:1495 | `push(this,"user",...)` → `appendBubble`，**但不调 renderHistory** | 环**仍不涨** | **是**，见 7.4 |
| 4 | `handleReply`:2063 | `push(assistant,"")` → `appendBubble`，无 renderHistory | 环**仍不涨** | **是**，见 7.4 |
| 5 | `handleReply`:2074 | 同上 | 同上 | **是**，见 7.4 |
| 6 | `handleReply`:2103 | `history = saved` → `appendBubble`，无 renderHistory | 环**仍不涨** | **是**，见 7.4 |

**关键**：`onSend` 与 `handleReply` 走的是「增量追加气泡」（`appendBubble`），
**故意不重铺整屏**（重铺会丢流式占位、也会闪）。所以它们不经过 `renderHistory()`，
收进去也修不到 —— 必须单独补一句。

### 7.4 增量路径的补法（4 处）

这 4 处不要调 `renderHistory()`（会破坏增量语义），而是**单独抽一个私有纯函数**：

```kotlin
/** 【修·进度环陈旧值】增量追加消息后同步环比例；与 renderHistory 内的口径同源。 */
private fun syncCtxRatio() {
    val cap = Math.max(1, Mem.MAX_ENTRIES)
    ChatState.ctxRatio = (history.length() / cap.toFloat()).coerceIn(0f, 1f)
}
```

- `renderHistory()` 开头调它一次（**唯一**真源）；
- `onSend`:1495、`handleReply`:2063/2074/2103 在 `push`/`history = saved` **之后**各调一次。

这样「整屏重铺」与「增量追加」两条路径共用同一口径，不会出现两处算错的可能。

> 注：也可让这 4 处直接调 `renderHistory()`，但那会与「增量追加」的设计相冲突
> （见 `appendBubble` 注释：追加是为避免重铺、保住流式气泡与下标身份）。**不推荐。**

### 7.5 `branchAt` 的处置（Lead 判「特殊：应重置为 0 或新会话比例」）

**读代码结论 —— 这里有一个比"环陈旧"更严重的既有缺陷，必须先说明：**

```kotlin
// ChatPanel.kt branchAt
val newId = ChatSessions.createIn(context, pool, ...)
ChatHistoryStore.saveHistory(context, arr, newId)   // 写新会话
ChatSessions.switchTo(context, newId)               // 切"当前会话"指针
flashMemo("已分支到新会话")                          // ← 到此结束
```

`branchAt` **没有**调 `loadHistory`、**没有**调 `renderHistory`。也就是说：
本地指针已切到新会话，但**内存里的 `history` 仍是旧会话的**，界面也仍是旧会话的画面。

**这不是我的推断，有对照证据**：另外两条切会话的路径都是「切完立刻重开聊天页」——
- `ChatListScreen.openConv`（`:456`）：`switchTo` → `HomeUi.openChat(act)` → 走 `ChatActivity.onResume`
  → `reloadHistory()`（`ChatActivity.kt:121`）
- `ConversationHistoryPage`（`:72`）：`switchTo` → `ChatListScreen.openConv(...)`，同上

`branchAt` 是**唯一**原地切会话却不重载的路径。

**因此处置应当分两层**：

| 层 | 做什么 | 理由 |
|---|---|---|
| **A（本次该做）** | 在 `switchTo` 之后补 `loadHistory(this)` + `renderHistory()` | 与其它两条切会话路径对齐；环会自然变成新会话的比例（`arr` = 原会话前 `index+1` 条，所以通常是 `(index+1)/200`，**不是 0**） |
| **B（单独立项）** | 审查「分支后是否该留在当前页」 | 若产品期望"分支后跳到新会话"（像其它两条路径那样重开页），则 A 的补法只是在同一页内近似；真正的修法是复用 `openConv` 语义。这超出本任务范围 |

**"应该是 0 还是新会话比例"的答案：是新会话比例，不是 0。**
因为 `branchAt` 把「原会话前 `index+1` 条」复制进了新会话（`arr`），新会话**本来就有内容**。
只有 `clearChat` 之后才是 0。

### 7.6 执行清单（可直接照做 · 行号基于 2322 行的版本，**执行前必须重跑锚点 grep**）

> ⚠️ 行号会漂移（`provider-shell` 正在大改本文件）。**每一步开头先跑锚点命令确认行号**，
> 不要拿本文档的行号直接 `sed`。所有插入都用「匹配唯一文本」的方式，不靠绝对行号。

---

#### 步骤 0 · 开工前基线

```bash
cd /sdcard/work area/xiaods/Dollhouse
F=app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt
wc -l $F                                   # 记录基线行数
grep -c 'fun ' $F                         # 记录基线函数数
grep -n 'refreshCtxRing()' $F              # 应为 8 调用 + 1 声明
python3 tools/kotlin_precheck.py           # 必须绿
```

若 `refreshCtxRing()` 的调用点不是 8 处，说明文件已变，**停下来重新跑配对矩阵**再决定。

---

#### 步骤 1 · 新增私有函数 `syncCtxRatio()`

**位置**：紧跟在 `refreshCtxRing()` 方法**之后**（同一处缩进，4 空格）。

先用唯一文本定位：
```bash
grep -n -A4 'fun refreshCtxRing()' $F
```
当前应为：
```kotlin
    fun refreshCtxRing() {
        val cap = Math.max(1, Mem.MAX_ENTRIES)
        ChatState.ctxRatio = (history.length() / cap.toFloat()).coerceIn(0f, 1f)
    }
```
在它后面**插入**：
```kotlin

    /**
     * 【修·进度环陈旧值】把「当前条数 ÷ 记忆库容量」同步到 [ChatState.ctxRatio]。
     *
     * 【为什么单独抽出来】进度环有两条更新路径：
     *   ① 整屏重铺 —— `renderHistory()` 开头调用本函数；
     *   ② 增量追加 —— `onSend` / `handleReply` 走 `appendBubble`，**故意不重铺**
     *      （重铺会丢流式占位气泡、也会让列表整体闪一下），所以那几处必须单独调本函数。
     *   两条路径共用本函数，口径只有一处，不会算错两遍。
     *
     * 【口径】`Mem.MAX_ENTRIES` = 200（不是字符数）。
     */
    private fun syncCtxRatio() {
        val cap = Math.max(1, Mem.MAX_ENTRIES)
        ChatState.ctxRatio = (history.length() / cap.toFloat()).coerceIn(0f, 1f)
    }
```

> 此步**不删** `refreshCtxRing()`，它暂时仍被 8 处调用，删了编译不过。删除在步骤 5。

---

#### 步骤 2 · `renderHistory()` 开头接上

**位置**：`private fun renderHistory() {` 的**第一行**（即 `summaryIndex = -1` 之前的那一行位置）。

先定位：
```bash
grep -n -A3 'private fun renderHistory()' $F
```
把
```kotlin
    private fun renderHistory() {
        summaryIndex = -1
```
改成
```kotlin
    private fun renderHistory() {
        // 【修·进度环陈旧值】必须放在本函数**最开头**，早于下方「空历史 early-return」：
        //   否则清空对话后 renderHistory 提前 return，环不会归 0（那正是本次要修的缺陷之一）。
        syncCtxRatio()
        summaryIndex = -1
```

---

#### 步骤 3 · `onSend` 增量路径补一次

**位置**：`ChatHistoryStore.push(this, "user", text, str2)` **之后**、`appendBubble(...)` 之前或之后均可，
建议紧跟 `appendBubble` 之后（语义是"这一轮消息已经进历史了"）。

先定位：
```bash
grep -n -B2 -A3 'ChatHistoryStore.push(this, "user", text, str2)' $F
```
把
```kotlin
        ChatHistoryStore.push(this, "user", text, str2)
        appendBubble(text, true, str2, history.length() - 1)
```
改成
```kotlin
        ChatHistoryStore.push(this, "user", text, str2)
        appendBubble(text, true, str2, history.length() - 1)
        // 【修·进度环陈旧值】本路径走 appendBubble 增量追加、不重铺，环需单独同步。
        syncCtxRatio()
```

---

#### 步骤 4 · `handleReply` 的 3 处补一次（**注意插入位置陷阱**）

本函数里 `history` 有三种变动方式，都要补。**但第 ③ 处必须插在两处 early-return 之后**，
否则会在「数据写回了别的会话」时误刷新当前会话的环。

先定位：
```bash
grep -n 'ChatHistoryStore.push(this, "assistant"\|history = saved' $F
```

> **⚠️ 名称漂移**：本文件正在被 `provider-shell` 改为去 View 版本。
> 旧的 `isAttachedToWindow` 已替换为字段 `attached`（由 `ChatScreen` 写入，`:186` 声明、`:190` 赋值）。
> 下面的代码块按**当前最新形态**（`attached`）书写；若你接手时代码又变，**以实际代码为准**，
> 本清单的意图是「在三个位置各插一次 `syncCtxRatio()`」。

**① 空正文 + 有思考（约 :2068）**
```kotlin
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                if (attached) appendBubble("", false, null, history.length() - 1)
```
改成
```kotlin
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                syncCtxRatio()
                if (attached) appendBubble("", false, null, history.length() - 1)
```

**② 空正文纯思考（约 :2079）**
```kotlin
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                if (attached) {
```
改成
```kotlin
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                syncCtxRatio()
                if (attached) {
```

**③ 正常回复（约 :2108 起）—— 插入点很关键，见下**

原文（注意后面有**两个** early-return）：
```kotlin
        if (ChatSessions.currentId(context) == ownerConversation && saved != null) history = saved
        if (ChatSessions.currentId(context) != ownerConversation || saved == null) {
            // 数据已写回原会话；不触碰正在显示其他会话的全局 ChatState。
            return
        }
        if (!attached) {
            // 离屏：没有气泡可加，收掉等待态即可（回来由 reloadHistory 重铺）。
            setWaiting(false)
            removeThinking()
            return
        }
        setWaiting(false)
        removeThinking()
        appendBubble(trim, false, null, history.length() - 1)
```
改成（**只在过了两个 return 之后**加）：
```kotlin
        if (ChatSessions.currentId(context) != ownerConversation || saved == null) {
            // 数据已写回原会话；不触碰正在显示其他会话的全局 ChatState。
            return
        }
        if (!attached) {
            // 离屏：没有气泡可加，收掉等待态即可（回来由 reloadHistory 重铺）。
            setWaiting(false)
            removeThinking()
            // 【修·进度环陈旧值】离屏也要同步：环是纯状态、与是否在屏无关。
            //   依据：本分支紧邻的 setWaiting/removeThinking 本身就在写 ChatState，
            //   说明「离屏不更新界面」只约束**气泡**，不约束 ChatState 状态位。
            syncCtxRatio()
            return
        }
        setWaiting(false)
        removeThinking()
        // 【修·进度环陈旧值】在屏路径同样同步（与离屏分支对称，两条路径都覆盖）。
        syncCtxRatio()
        appendBubble(trim, false, null, history.length() - 1)
```

> **为什么离屏分支也要同步**：`history` 已经因 `saved` 被更新（`:2108` 那个赋值），
> 环的比例就该跟着变。环是状态、不是 View，「离屏不更新界面」的原则指的是**气泡**，
> 不适用于 `ChatState.ctxRatio`。若只在在屏分支同步，用户离屏收到回复、回前台时环仍是旧值 ——
> 而回前台会走 `reloadHistory()` → `renderHistory()` 把环刷对，所以**表面看不出问题，
> 但逻辑上应在此处一并覆盖**，避免将来 `renderHistory` 的调用链变化后重新引入陈旧值。

---

#### 步骤 5 · 删除 `refreshCtxRing()` 与它的 8 处调用点

**先证实"删掉 8 处不会漏刷新"** —— 这 8 处全部位于**同时调用了 `renderHistory()` 的函数**内，
因此步骤 2 把 `syncCtxRatio()` 放进 `renderHistory()` 后，它们全部被覆盖：

| 调用点 | 所在函数 | 该函数是否调 `renderHistory()` |
|---|---|---|
| `:228` | `init` | ✅（:224 附近） |
| `:559` | `reloadHistory` | ✅ |
| `:707` | `rollbackTo` | ✅ |
| `:816` | `editAndResend` | ✅ |
| `:859` | `regenerateAt` | ✅ |
| `:1143` | `applySummary` | ✅ |
| `:1256` | `deleteAt` | ✅ |
| `:1413` | `clearChat` | ✅ |

**这张表是删除的前提**：若将来有人新增一个「只改 history、不调 renderHistory」的路径又顺手加了
`refreshCtxRing()`，那张表就会失效 —— 届时正确做法是给它补 `syncCtxRatio()`，而不是恢复方法。

```bash
# 先确认 8 处调用（不含声明）
grep -n 'refreshCtxRing()' $F | grep -v 'fun refreshCtxRing'
```

1. 删除 8 处 `        refreshCtxRing()` 调用行（每处都是独立一行，直接删该行）。
2. 删除方法本体（步骤 1 里那 3 行 + 上面的 KDoc 注释块）。
3. **不要**删除步骤 1 新增的 `syncCtxRatio()`。

**⚠️ 必须一起改的一处**：`reloadHistory()` 内部（约 `:559`）有一行 `refreshCtxRing()`，
它也在上面那 8 处里。删方法时这一行要改成 `syncCtxRatio()`
（**不能直接删** —— `reloadHistory` 的语义是「换会话后全部状态刷新」，
只是它内部调用 `renderHistory()` 时已经会同步环，所以此处**改成 `syncCtxRatio()` 是冗余但安全**；
若想精简，也可以直接删掉这一行，因为 `reloadHistory` 里 `renderHistory()` 在它之前已经调过）。
**建议直接删该行**，并在注释里写明「环由 renderHistory 内的 syncCtxRatio 负责」。

删除后自检：
```bash
grep -c 'refreshCtxRing' $F     # 应为 0
grep -c 'syncCtxRatio' $F       # 1（定义） + 1（renderHistory） + 1（onSend）
                                # + 2（handleReply 空正文两处） + 2（handleReply 正常路径在屏/离屏）= 7
```

---

#### 步骤 6 · `branchAt` 数据一致性修复（**独立于进度环，优先级更高**）

**这是 Lead 判定的必修 bug**：分支后界面仍显示旧会话，之后新消息却写进新会话 ——
界面与数据脱节，退出重进才正常。

先定位：
```bash
grep -n -A12 'public override fun branchAt' $F
```
把
```kotlin
            ChatHistoryStore.saveHistory(context, arr, newId)
            ChatSessions.switchTo(context, newId)
            flashMemo("已分支到新会话")
```
改成
```kotlin
            ChatHistoryStore.saveHistory(context, arr, newId)
            ChatSessions.switchTo(context, newId)
            // 【修·分支后数据脱节】原先只切了「当前会话」指针，**没有重载 history、
            //   也没有重铺界面** —— 于是提示说"已分支"，界面还停在旧会话，
            //   之后发的新消息却写进新会话，界面与数据错位，退出重进才恢复。
            //   对照：另外两条切会话的路径都靠「重开聊天页 → onResume → reloadHistory」
            //   达到一致（ChatListScreen.openConv / ConversationHistoryPage），
            //   而本方法是唯一原地切换、不重载的路径。
            //
            //   【为什么用 reloadHistory() 而不是自己拼 loadHistory + renderHistory】
            //   reloadHistory() 已经做全了换会话该做的事（见其实现）：
            //     prevCount = 0 → ChatHistoryStore.loadHistory(this) → renderHistory()
            //     → refreshCtxRing() → reloadDraft()（重灌草稿与附件）
            //   最后那步「重灌草稿」是必需的：否则新会话里会显示上一个会话的草稿/附件，
            //   正是附录D-5 修过的 bug。自己拼会漏掉它。
            //
            //   【注意】此处必须写全 `ChatHistoryStore.loadHistory` —— ChatPanel 内
            //   没有同名成员函数，裸 `loadHistory(this)` 编译不过（已核对）。
            reloadHistory()
            flashMemo("已分支到新会话")
```

> **`prevCount = 0` 无需单独写** —— `reloadHistory()` 内部第一行就是它。
> **环的值**：`reloadHistory()` 末尾的 `refreshCtxRing()` 会按新 `history` 重算，
> 而新会话内容 = 原会话前 `index+1` 条，所以环显示 `(index+1)/200`，**不是 0** —— 符合 Lead 判定。
> **若步骤 5 已删除 `refreshCtxRing()`**（执行顺序：真机上应先做步骤 6 再做步骤 5，
> 或同一次提交内完成），则 `reloadHistory()` 里的那一行也要一并改为 `syncCtxRatio()`。
> 这一点务必对齐，否则删完方法后 `reloadHistory()` 里会留下一个悬空调用。
>
> **建议执行顺序调整：步骤 6（branchAt）→ 步骤 5（删 refreshCtxRing）**
> 这样 `reloadHistory()` 里只需把 `refreshCtxRing()` 改成 `syncCtxRatio()` 一次，不会遗漏。

---

#### 步骤 7 · 门禁与验收

```bash
python3 tools/kotlin_precheck.py        # 未发现问题
python3 tools/comment_balance.py        # 全部平衡
python3 tools/symbol_refs.py --quiet    # exit 0
bash tools/anim_audit.sh                # 101/101
grep -c 'fun ' $F                       # 记录：删 1（refreshCtxRing）加 1（syncCtxRatio）→ 净 0
```

**手测清单（本机不能编译，必须真机/CI 跑一遍）**：
| 场景 | 期望 |
|---|---|
| 发一条消息 | 环立即上涨 |
| 收到回复 | 环上涨 |
| 点「加载更早」 | 环按补回后总条数上涨 |
| 点「重新生成」 | 环缩 1 条 |
| 清空对话 | 环归 **0**（验证步骤 2 放在开头确实有效） |
| 离屏时收到回复，再回前台 | 环正确 |
| 点「从这里分支」 | **界面立刻变成新会话内容**；环 = `(index+1)/200`；随后发消息写进新会话且界面同步 |

---

### 7.7 执行风险与回滚

| 风险 | 说明 | 缓解 |
|---|---|---|
| 行号漂移 | `provider-shell` 正在大改本文件 | 每步先跑锚点 grep；用唯一文本匹配而非绝对行号 |
| `handleReply` 插入点错位 | 第 ③ 处后面有两个 early-return，插错会误刷别的会话 | 本文档已给出插入前后完整代码块，逐行对照 |
| `branchAt` 加 `renderHistory()` 引发重铺抖动 | 分支是低频操作，重铺可接受 | 若观感不佳，可改为「重开聊天页」路径（复用 `ChatListScreen.openConv` 语义），但那会超出本文件范围 |
| `Mem.MAX_ENTRIES` 口径 | 环按**条数**不是字符数（200 条） | 与现有实现一致，未改口径 |
| 无法本地编译 | 本机无 kotlinc | 靠 precheck + CI + 上表手测 |
