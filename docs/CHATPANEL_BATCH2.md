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