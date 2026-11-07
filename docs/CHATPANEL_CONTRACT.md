# ChatPanel 迁移契约（冻结清单）

- 目标文件：`app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt`（2283 行，`wc -l` 实测）
- 分支：`feat/compose-ui-unification`
- 任务：`task-2`「ChatPanel 迁移契约冻结（只读分析，产出文档）」
- 性质：**只读分析**。本文件是后续「去 View 化」迁移的验收基准；本轮未修改任何 `.kt`。
- 冻结含义：下表中的签名、可见性、语义在迁移期间**不得变更**，除非本文件同步更新并说明理由。

---

## 0. 结论速览

| 指标 | 数值 |
|---|---|
| 非 private 成员总数 | 57（50 个函数 + 3 个 `var` + 2 个 `val` + 1 个内部接口 + 1 个 `companion object`） |
| 其中 `interface Controller` 的抽象方法 | 3（`onClose` / `onDrag` / `onOpenTokenStat`），另计 |
| 真正 0 调用的成员 | 8 |
| 0 读取（只写不读）的成员 | 1（`highlightIndex`） |
| 仅经死路径可达的成员 | 1（`clearChat`） |
| 跨包数据契约（`ChatHistoryStore` 依赖） | 3 个成员 + 9 个以 `ChatPanel` 为形参的函数 |
| View 特有用法 | 9 类、共 18 处代码点 |
| 命令式回调里被调用的成员 | 20 |

最高风险前 5（详见 §7）：`history`、`askModel`/`handleReply`/`finishWithError` 链路、`onSend`、`boundDollId`、`prevCount`+`loadEarlier`+`onScrolled`+`reloadHistory`。

---

## 1. 复现方法（所有「0 调用」结论都可用下面的命令重跑）

工作目录：仓库根 `/sdcard/work area/xiaods/Dollhouse`。全部命令只用 `app/src`。

```bash
# 通用姿势：把 <M> 换成成员名
grep -rn --include='*.kt' --include='*.java' -w '<M>' app/src

# 判定要点
#   ① 声明行本身要排除（行号见本文件 §2 的「行」列）
#   ② 以 // 或 * 或 /* 开头的行是注释，不算调用
#   ③ 同名的其它符号要排除（例：doSearch 在 1888/1893 是局部 val；clearChat 在
#      FriendProfilePage.kt:319 是该文件自己的 private fun；reloadHistory / runTools /
#      openMain 在 PetTalk.kt / AexReceiveActivity.kt 里各自有同名私有函数）
```

逐条可复现命令（严格对应 §5 的 8 个 0 调用结论）：

```bash
grep -rn --include='*.kt' --include='*.java' -w 'addBubble'       app/src   # 仅 ChatPanel.kt:192 声明
grep -rn --include='*.kt' --include='*.java' -w 'showMemory'      app/src   # 仅 ChatPanel.kt:301 声明
grep -rn --include='*.kt' --include='*.java' -w 'setMemoBusy'     app/src   # 仅声明 + 注释提及
grep -rn --include='*.kt' --include='*.java' -w 'showChat'        app/src   # 仅 ChatPanel.kt:477 声明
grep -rn --include='*.kt' --include='*.java' -w 'confirmClearChat' app/src  # 仅 ChatPanel.kt:1302 声明
grep -rn --include='*.kt' --include='*.java' -w 'ctxUsedChars'    app/src   # 仅 ChatPanel.kt:1328 声明
grep -rn --include='*.kt' --include='*.java' -w 'doSearch'        app/src   # 命中 1888/1893 的局部 val + 1908 声明
grep -rn --include='*.kt' --include='*.java' -w 'highlightIndex'  app/src   # 723 写、745 声明、709 注释，**无读取**
```

对上表第 7 条：`1888: val doSearch = runTool(...)` 与 `1893: jSONObject3.put("content", doSearch)`
是 `runTools` 内的局部变量，与 `fun doSearch(String?)` 无关 —— 该成员的真实调用点为 0。

对上表第 8 条：`highlightIndex` 只在 `showSearchDialog` 里被**写**过一次（723），
`renderHistory`（2147-2231）和 `ChatItem`（`ChatState.kt:126-136`）都没有高亮字段，
`ChatUi` 全文也没有 `highlight` 字样 ⇒ 「搜索命中高亮」这条能力实际已经断了。

---

## 2. 全部非 private 成员总表

「回调语境 = ✓」表示调用点位于**命令式回调**（网络/线程/静态 listener/Activity 跳转/Dialog 按钮），
这类成员在 View 壳消失后必须改成「状态驱动」或「宿主回调」，不能继续靠 `host.xxx()` 直调。
「回调语境 = —」表示调用点是纯同步 Kotlin 或纯状态写入，可直接内联/删除。

### 2.1 类型、字段与构造

| 行 | 成员 | 签名 | 语义（一句话） | 调用点（`file:line`，代码行） | 回调语境 | 分类 |
|---|---|---|---|---|---|---|
| 55-59 | `ChatPanel` | `class ChatPanel(context: Context, internal val controller: Controller?, z: Boolean) : LinearLayout(context), PickFileActivity.Listener` | 聊天核心：持历史、跑请求、把可渲染状态写进 `ChatState` | `ChatActivity.kt:37`（全仓唯一构造点） | — | 必须保持 |
| 57 | `controller` | `internal val controller: Controller?` | 宿主动作（拖动/关闭/令牌页）回调出口 | `ChatUi.kt:145`、`ChatUi.kt:160`、`ChatUi.kt:271` | ✓ | 必须保持 |
| 122-127 | `Controller` | `interface Controller { onClose(); onDrag(f,f2); onOpenTokenStat() }` | 面板 → 宿主的三件事 | 实现方 `ChatActivity.kt:37`；调用 `ChatUi.kt:145/160/271` | ✓ | 必须保持 |
| 130 | `activity` | `internal val activity: android.app.Activity?` | `context as? Activity`，供 Compose 装 owner | `ChatUi.kt:87`、`ChatUi.kt:199`、`ChatUi.kt:253` | — | 必须保持 |
| 62 | `history` | `internal var history: JSONArray = JSONArray()` | 整段对话上下文的**唯一真源** | 外部 27 行全在 `ChatHistoryStore.kt`（代表：`:106` `:356` `:503` `:743` `:1059`）；内部 52 处 | ✓（多处在回调内写） | 必须保持 |
| 65 | `prevCount` | `internal var prevCount = 0` | 「更早历史」展开态凭据，0 = 收起 | `ChatHistoryStore.kt:112`、`ChatHistoryStore.kt:357`；内部 255/514/662/766/809/1054/1167/1318/2240/2252/2258 | — | 必须保持 |
| 745 | `highlightIndex` | `internal var highlightIndex = -1` | 搜索命中要高亮的下标 | 写：`ChatPanel.kt:723`；**读：0** | — | 可删除 |
| 2273 | `companion object` | `MAX_TOOL_ROUNDS=3`、`LEARN_MAX=12`（均 private） | 工具轮次 / 学习示例池上限 | `1547/1647/1772`（`MAX_TOOL_ROUNDS`）、`1265/1266`（`LEARN_MAX`） | — | 必须保持 |

### 2.2 状态 → 界面（薄壳）

| 行 | 成员 | 签名 | 语义 | 调用点 | 回调语境 | 分类 |
|---|---|---|---|---|---|---|
| 192 | `addBubble` | `fun addBubble(str: String?, z: Boolean)` | 追加一条气泡（转 `appendBubble`） | **无（0）** | — | 可删除 |
| 376 | `refreshInputRow` | `fun refreshInputRow()` | 把 `browsingArchives` 与附件条刷进 `ChatState` | 内部 157/479/774/1146；外部 0 | — | 可改为状态驱动 |
| 386 | `applyBackground` | `fun applyBackground()` | 把背景图挂到本 View（`GlobalBackground.install(this,…)`） | `ChatActivity.kt:107`；内部 `160` | — | 必须保持（语义），实现需重写 |
| 369 | `scrollToSummary` | `internal fun scrollToSummary()` | 滚到摘要分割条下标 | 内部 `2261`；外部 0 | — | 可改为状态驱动 |
| 1407 | `refreshCtxRing` | `fun refreshCtxRing()` | 重算顶栏上下文环比例 → `ChatState.ctxRatio` | 内部 158/517/665/769/812/1057/1170/1324；外部 0 | — | 可改为状态驱动 |
| 2131 | `refreshHint` | `fun refreshHint()` | 按有无可用模型决定顶部提示条 → `ChatState.hint` | `ChatActivity.kt:56`、`ChatActivity.kt:103`；内部 `159`、`481` | ✓（Activity 生命周期内调） | 必须保持 |
| 312 | `setMemoBusy` | `internal fun setMemoBusy(busy: Boolean)` | 「记忆总结中」亮起/收起 | **无（0）** | — | 可删除 |
| 335 | `flashMemo` | `internal fun flashMemo(msg: String?)` | 工具条右侧 3 秒提示（失败/被拒/已复制） | `ChatUi.kt:714`、`ChatUi.kt:716`；内部 32 处 | ✓（长按菜单回调内） | 必须保持 |
| 444 | `clearAttachment` | `fun clearAttachment()` | 清空本轮附件（图/文本） | `ChatUi.kt:1076`；内部 `1500`、`1510` | ✓ | 必须保持 |
| 477 | `showChat` | `fun showChat()` | 退出归档态并重铺 | **无（0）** | — | 可删除 |

### 2.3 消息级操作（长按菜单 / 操作条）

| 行 | 成员 | 签名 | 语义 | 调用点 | 回调语境 | 分类 |
|---|---|---|---|---|---|---|
| 575 | `copyLastReply` | `fun copyLastReply()` | 复制最后一条助手回复 | `ChatUi.kt:992` | ✓ | 必须保持 |
| 603 | `copyAt` | `fun copyAt(index: Int)` | 复制指定下标消息 | `ChatUi.kt:676` | ✓ | 必须保持 |
| 617 | `copyMany` | `fun copyMany(indexes: List<Int>)` | 多选批量复制（`\n\n` 拼接） | `ChatUi.kt:1133` | ✓ | 必须保持 |
| 646 | `rollbackTo` | `fun rollbackTo(index: Int)` | 截断到该条（含），丢弃其后 | `ChatUi.kt:693` | ✓ | 必须保持 |
| 681 | `branchAt` | `fun branchAt(index: Int)` | 以「前 index 条」复制出新会话并切过去 | `ChatUi.kt:730` | ✓ | 必须保持 |
| 711 | `showSearchDialog` | `fun showSearchDialog()` | 原生 Dialog 搜当前会话关键词 | `ChatUi.kt:202` | ✓ | 必须保持（实现可换） |
| 753 | `editAndResend` | `fun editAndResend(index: Int)` | 回填正文 + 截断到该条之前 | `ChatUi.kt:691` | ✓ | 必须保持 |
| 784 | `regenerateAt` | `fun regenerateAt(index: Int)` | 删该条助手回复并立刻重新请求 | `ChatUi.kt:707` | ✓ | 必须保持 |
| 841 | `insertSummaryAt` | `fun insertSummaryAt(index: Int)` | 把该段压成 summary 分割条（原文转暂存位） | `ChatUi.kt:683` | ✓ | 必须保持 |
| 1098 | `showItemInfo` | `fun showItemInfo(index: Int)` | 弹出该条详情（角色/字数/时间/模型） | `ChatUi.kt:686` | ✓ | 必须保持 |
| 1137 | `replyTo` | `fun replyTo(index: Int)` | 把她的原话引用进输入框 | `ChatUi.kt:722` | ✓ | 必须保持 |
| 1151 | `deleteAt` | `fun deleteAt(index: Int)` | 删单条 | `ChatUi.kt:734` | ✓ | 必须保持 |
| 1187 | `saveToMemory` | `fun saveToMemory(index: Int)` | 存进当前人偶记忆库 | `ChatUi.kt:720` | ✓ | 必须保持 |
| 1226 | `learnFromAt` | `fun learnFromAt(index: Int): Boolean` | 把「问→答」存成学习示例，返回是否入库 | `ChatUi.kt:713`（`if (host.learnFromAt(idx))`） | ✓ | 必须保持（有返回值，不能改成纯发射） |
| 1316 | `clearChat` | `fun clearChat()` | 历史归零 + 清暂存位 | 唯一调用点 `ChatPanel.kt:1309`，位于 `confirmClearChat` 的 `OnClickListener` 内 ⇒ 该 listener 永不触发 | ✓（理论） | 可改为状态驱动 |

### 2.4 会话 / 上下文 / 人偶

| 行 | 成员 | 签名 | 语义 | 调用点 | 回调语境 | 分类 |
|---|---|---|---|---|---|---|
| 249 | `loadEarlier` | `internal fun loadEarlier()` | 补回一批更早历史并滚到该批顶部 | `ChatUi.kt:884` | ✓ | 必须保持 |
| 511 | `reloadHistory` | `fun reloadHistory()` | 重载历史 + 重铺 + 重算环 + 重灌草稿 | `ChatActivity.kt:118`；内部 `2260` | ✓（onResume） | 必须保持 |
| 2239 | `onScrolled` | `internal fun onScrolled(firstVisibleIndex: Int, atBottom: Boolean)` | 展开态滑出即自动收回 | `ChatUi.kt:378` | ✓（`snapshotFlow.collect`） | 必须保持 |
| 275 | `onInputChange` | `internal fun onInputChange(text: String)` | 单向数据流：写 `ChatState.input` + 存草稿 | `ChatUi.kt:1181` | ✓（`onValueChange`） | 可改为状态驱动 |
| 1350 | `boundDollId` | `internal fun boundDollId(): String` | 当前会话绑定的人偶 id（拿不到退默认） | 外部 `ChatHistoryStore.kt:795`；内部 11 处（155/279/544/908/1194/1331/1388/1472/1547/1837/2159） | — | 必须保持 |
| 1328 | `ctxUsedChars` | `fun ctxUsedChars(): Int` | 本地估算上下文占用字符数 | **无（0）** | — | 可删除 |
| 301 | `showMemory` | `internal fun showMemory()` | 兼容壳：直接转发 `openPicker()` | **无（0）** | — | 可删除 |
| 420 | `openPicker` | `fun openPicker()` | 打开选图/选文件 Activity | `ChatUi.kt:1112`；内部 `302`（即 `showMemory`） | ✓ | 必须保持 |
| 429 | `onPicked` | `override fun onPicked(str: String?, str2: String?)` | 选文件回调：记 pending + 刷附件条 | 由 `PickFileActivity.kt:422` 经接口分派（接口声明 `:32`） | ✓（静态 listener 回调） | 必须保持 |
| 440 | `onFailed` | `override fun onFailed(str: String?)` | 选文件失败（当前刻意静默） | 由 `PickFileActivity.kt:206`、`:420` 分派（接口声明 `:30`） | ✓（静态 listener 回调） | 必须保持 |
| 283 | `showModels` | `internal fun showModels()` | 模型面板（转 `ChatPanels.showModels`） | `ChatUi.kt:1102` | ✓ | 必须保持 |
| 293 | `showThink` | `internal fun showThink()` | 思考程度面板（转 `ChatPanels.showThink`） | `ChatUi.kt:1106` | ✓ | 必须保持 |
| 1302 | `confirmClearChat` | `fun confirmClearChat()` | 二次确认后清空当前对话 | **无（0）** | — | 可删除（或补回入口） |
| 232 | `openMain` | `internal fun openMain()` | 无可用模型时跳「供应商」页（独立页走 `EXTRA_OPEN_PROVIDERS`） | `ChatUi.kt:323` | ✓ | 必须保持 |

### 2.5 请求 / 工具 / 收尾（网络与线程核心）

| 行 | 成员 | 签名 | 语义 | 调用点 | 回调语境 | 分类 |
|---|---|---|---|---|---|---|
| 1436 | `onSend` | `fun onSend()` | 发送：闸门检查 → 落库 → 挂气泡 → 等 OCR → 组装请求 | `ChatUi.kt:1223` | ✓ | 必须保持 |
| 1412 | `regenerate` | `fun regenerate()` | 删最后一条助手回复并重发 | `ChatUi.kt:991`；内部 `1988`（`retryAfterError`） | ✓ | 必须保持 |
| 1584 | `askModel` | `fun askModel(jSONArray: JSONArray, i: Int, z: Boolean)` | 组装参数、选流式/非流式、发请求 | 内部 7 处：1579/1629/1638/1750/1755/1763/1901；外部 0 | ✓（回调/线程内重试） | 必须保持 |
| 1868 | `runTools` | `fun runTools(jSONArray: JSONArray, jSONArray2: JSONArray, jSONObject: JSONObject, i: Int)` | 后台线程跑工具并把结果回灌下一轮请求 | 内部 1648/1775；外部 0（`PetTalk.kt:514` 是另一个类的私有同名函数） | ✓（线程内 + `post`） | 必须保持 |
| 1908 | `doSearch` | `fun doSearch(str: String?): String` | 兼容壳：`web_search` 单次执行 | **无（0）** | — | 可删除 |
| 1928 | `finishWithError` | `fun finishWithError(str: String?)` | 收等待态 + 打错误气泡（可重试提示） | 内部 1641/1766/2040/2055；外部 0 | ✓ | 必须保持 |
| 1956 | `lastUserBeforeError` | `internal fun lastUserBeforeError(): JSONObject?` | 错误前是否还有可重答的 user 提问 | 内部 1941/1984 | — | 必须保持 |
| 1979 | `retryAfterError` | `fun retryAfterError()` | 错误气泡上的「重试这次」 | `ChatUi.kt:704` | ✓ | 必须保持 |
| 1992 | `handleReply` | `fun handleReply(str: String?)` | 旧单参重载（兼容入口） | **无（0）**，仅 `1993` 自身转发到三参版 | — | 可删除 |
| 2004 | `handleReply` | `fun handleReply(str: String?, reasoning: String?, costMs: Long)` | 落盘 + 分流思考/空内容 + 挂气泡 + 人偶气泡 | 内部 1659/1736/1784 | ✓ | 必须保持 |
| 2115 | `stopGenerating` | `fun stopGenerating()` | 作废票号、取消 task、收等待态 | `ChatUi.kt:1221` | ✓ | 必须保持 |

---

## 3. 命令式回调清单（迁 Compose 后必须改造的 20 项）

判定标准：调用点本身**不是** Composable 组合期同步代码，而是「某个 View/Compose 事件 lambda、静态 listener、
后台线程 `post`、Dialog 按钮、Activity 跳转」。这些位置没有 `@Composable` 作用域，也是「漏迁即静默失效」的地方。

| 成员 | 触发语境 | 具体位置 |
|---|---|---|
| `onSend` | Compose `clickable` 分支 | `ChatUi.kt:1223` |
| `stopGenerating` | 同一按钮的另一分支 | `ChatUi.kt:1221` |
| `regenerate` / `copyLastReply` | 操作条 chip lambda | `ChatUi.kt:991` / `:992` |
| `copyAt` `insertSummaryAt` `showItemInfo` `editAndResend` `rollbackTo` `retryAfterError` `regenerateAt` `learnFromAt` `saveToMemory` `replyTo` `branchAt` `deleteAt` | 长按菜单 `actions` 列表（`ArrayList<() -> Unit>`） | `ChatUi.kt:676/683/686/691/693/704/707/713/720/722/730/734` |
| `copyMany` | 多选工具条按钮 | `ChatUi.kt:1133` |
| `loadEarlier` | 顶部「加载更早」pressable | `ChatUi.kt:884` |
| `onScrolled` | `snapshotFlow{}.collect`（副作用协程） | `ChatUi.kt:378` |
| `onInputChange` | `BasicTextField.onValueChange` | `ChatUi.kt:1181` |
| `showModels` / `showThink` / `openPicker` | 工具条按钮 lambda | `ChatUi.kt:1102` / `:1106` / `:1112` |
| `clearAttachment` | 附件条关闭按钮 | `ChatUi.kt:1076` |
| `flashMemo` | 长按菜单内嵌 lambda | `ChatUi.kt:714` / `:716` |
| `openMain` | 顶部提示条 pressable | `ChatUi.kt:323` |
| `showSearchDialog` | 标题 `onLongClick` | `ChatUi.kt:202` |
| `controller.onDrag` / `.onClose` / `.onOpenTokenStat` | 拖拽手势 / 返回键 / 上下文环 | `ChatUi.kt:145/160/271` |
| `onPicked` / `onFailed` | `PickFileActivity` 静态 listener 分派 | `PickFileActivity.kt:422` / `:206`、`:420` |
| `handleReply` / `finishWithError` / `askModel` / `runTools` | `DeepSeekClient` Raw/Stream 回调 → 主线程 | `1659`、`1736`、`1784`、`1641`、`1766`、`1579`、`1901` |
| `reloadHistory` / `refreshHint` / `applyBackground` | `ChatActivity.onResume` / `onCreate` | `ChatActivity.kt:118` / `:103`、`:56` / `:107` |
| `loadEarlier` 之外的 `onScrolled → collapsePrev → reloadHistory` | 滚动回收链 | `2240-2266` |

---

## 4. View 特有用法（逐条给迁移建议）

全文件 grep（`grep -nE 'addView|ViewGroup|ViewParent|isAttachedToWindow|post|removeCallbacks|clipToOutline|outlineProvider|WindowInsets|width|height' ChatPanel.kt`）结果如下。

| # | 位置 | 用法 | 为什么是 View 依赖 | 迁移建议 |
|---|---|---|---|---|
| V1 | `55-59` | `: LinearLayout(context)` | 基类是 View；且注释 49-51 明确说明「必须是 LinearLayout 而不是 FrameLayout」，否则 `findLayer` 会命中自身（注：`findLayer` 已删除，见 496-505） | Compose 根 `Box`；外壳由 `ComposeHost.createView` 产出，不再继承任何 View。历史原因随 `findLayer` 一起消失 |
| V2 | `138` | `orientation = LinearLayout.VERTICAL` | 布局属性 | 由 `Column` 承担，删除 |
| V3 | `141-146` | `clipToOutline = true` + `outlineProvider = object : ViewOutlineProvider()` + `Outline.setRoundRect(0,0,view.width,view.height,dp(16))` | **手工圆角 + 手工读宽高** | 改用 `Modifier.clip(RoundedCornerShape(16.dp))`。注意风险：背景图由 `GlobalBackground` 画在**本 View** 上（`389`），改 Compose 后圆角裁剪与背景绘制必须落在同一层，否则四角会溢出（原注释 139-140 已记录这个坑） |
| V4 | `147` | `addView(ChatUi.build(this), LinearLayout.LayoutParams(-1,-1))` | 唯一的挂载动作 | `setContent { ChatContent(host) }`，无 addView |
| V5 | `389` | `GlobalBackground.install(this, UiKit.option(), false)` | 入参类型是 `View?`（`GlobalBackground.kt:238`），并按 `registry[v]` 记 View 引用 | 两条路：① 给 `GlobalBackground` 加 Compose 版入口（`Modifier` 或一层 `View` 包裹）；② 保留一个不可见的 `View` 只作背景载体（不推荐，等于把 View 留在树里）。**必须先改 `GlobalBackground`** |
| V6 | `397-406` | `if (width > 0 && height > 0)` + `post{}` 首帧回填 | View 实测宽高 | 用 `Modifier.onSizeChanged{}` 或 `BoxWithConstraints`，取到尺寸后写 `PetPrefs.setChatBgRatio` |
| V7 | `1900/1942/2037/2054/2065/2098` | `isAttachedToWindow`（6 处读） | 「在树里才更新界面」 | 这是**语义契约**，不是纯技术细节：`2082-2090` 记录了「离屏丢弃整条回复」的 P0 缺陷。迁移必须换成显式 `isOnScreen`/`isResumed` 状态（由宿主写入），**不能简单删掉判断** |
| V8 | `267/401/1562/1899/2263` + `361` + `320/343` | `post{}` ×5、`postDelayed(r,3000)`、`removeCallbacks(pending)` ×2 | View 自带 Handler | 换成 `Handler(Looper.getMainLooper())` 或 `CoroutineScope(Dispatchers.Main)`。**注意**：`flashMemo` 的 3 秒计时器依赖 `removeCallbacks` 做互斥（339-345），换成协程需 `Job.cancel()` 保持同样的「只允许一个计时器」语义 |
| V9 | `131/233/243` | `context as? android.app.Activity`、`context as? MainActivity`、`context.startActivity` | Context 即 View 的 Context | 构造参数显式收 `Activity`（或 `ComponentActivity`），不靠 cast；`openMain` 的 `EXTRA_OPEN_PROVIDERS` 协议保持不变（`MainActivity.kt:44/187`） |
| V10 | `424` | `PickFileActivity.setListener(this)` | 静态字段持有 View 实例（`PickFileActivity.kt:509/525`），发起方被回收就回调落空 | 改 Activity Result API 或由宿主注册一个带生命周期的回调对象；**必须**保留「用完置 null」的清理（原 `PickFileActivity.kt:335/374/473` 已有） |
| V11 | `712-729`、`1303-1312`、`1118-1126`、`1452` | `UiKit.showDialog(...)` + `android.widget.EditText` + `View.OnClickListener` | 原生 Dialog + 原生控件实例化 | 迁到 `DhForm.Alert` / Compose Dialog。**注意顺序**：`1298-1301` 明确说明「因为调用点在命令式回调里、不是 Composable 作用域，所以不能用 `DhForm.Alert`」——迁移时这些调用点本身要先进入 Composable 作用域 |
| V12 | `9`、`11` | `import android.view.ViewGroup`、`import android.view.ViewParent` | **两个 import 已无代码使用**（只出现在注释 `287`/`500`） | 顺手删除；也是「View 代码已基本撤空」的证据 |
| V13 | `496-505` | 注释记载 `findLayer()`（沿父链找第一个 `FrameLayout`）**已删除、0 调用** | 曾经的「手工找宿主层」契约 | 无需迁移；迁移后不要重新引入「找宿主层」模式，一律走 Dialog 窗口层级 |

### 4.1 关于「手工 WindowInsets 数学」

`ChatPanel.kt` 内**没有**任何 `WindowInsets` / `setOnApplyWindowInsetsListener` / `statusBarPad` 代码（已按上表 grep 核实，0 命中）。
手工 insets 数学分布在别处，迁移时要连带确认：

| 位置 | 内容 | 与本契约的关系 |
|---|---|---|
| `ChatActivity.kt:55` | `UiKit.applyEdgeToEdge(this)` | 面板铺满 + 状态栏透明，由宿主做，`ChatPanel` 不参与 |
| `ChatUi.kt:155` | `Modifier.windowInsetsPadding(WindowInsets.displayCutout)` | 已在 Compose 侧，顶栏让出刘海 |
| `UiKit.kt:616` `statusBarPad`、`:654-711` `viewCutoutInsets` / `cutoutSafeInsets` | 读 `rootWindowInsets.displayCutout`，并「同步首帧 + attach 后 post + 布局变化」校正 | **这是真正的手工 insets 数学**，靠 `View.rootWindowInsets` 与 attach 时机；迁 Compose 后建议整体换成 `WindowInsets.displayCutout` 的组合式 API，而不是继续调用 `UiKit` 那套 View 版 |

未在 `ChatPanel` 出现的项（避免漏检）：无 `onAttachedToWindow` / `onDetachedFromWindow` 覆写，无 `removeAllViews`（仅注释 `2150` 提及），无 `findViewById`，无 `requestLayout`。

---

## 5. 三张表

### 表 A · 必须保持语义不变（30 项）

迁移只允许换实现，**不允许**改签名/可见性/返回值/副作用顺序。

| # | 成员 | 保护的语义要点 | 漏迁后果 |
|---|---|---|---|
| 1 | `history` | 唯一真源；`ChatHistoryStore` 直接读写 27 行 | 整条数据链断裂 |
| 2 | `prevCount` | 展开态凭据；`ChatHistoryStore.kt:357` 用它决定「不裁剪」 | 展开期间消息被裁掉 = 丢数据 |
| 3 | `controller` / `Controller` | 三件宿主动作 | 返回键/拖动/令牌页失效 |
| 4 | `activity` | Compose owner 安装点 | `ChatUi` 组合不起来 |
| 5 | `onSend` | 闸门 → 清草稿 → **先落库再挂气泡** → OCR → 组装 | 气泡拿不到 `history` 下标 ⇒ 长按菜单无反应（`169-173` 记的原 bug） |
| 6 | `regenerate` | 删尾条 → 存盘 → 重铺 → 请求 | 重复回复或空回复 |
| 7 | `stopGenerating` | `requestTicket++` + `cancel` + 收等待态 | 「点了停止几秒后还是回了」（`2120-2122`） |
| 8 | `askModel` | 票号/会话双校验、`thinkDegraded` 只降级一次 | 串会话、无限重试 |
| 9 | `runTools` | 后台线程 + `post` 回主线程、`i+1` 轮次 | 工具轮死循环或卡主线程 |
| 10 | `handleReply(3 参)` | 先快照 `gen` 再清 `activeGeneration`（`2028-2031`） | **整条回复丢失**（`2005-2024` 记录的 P0） |
| 11 | `finishWithError` | 先收等待态再挂错误气泡 | 输入行永久「停止」 |
| 12 | `retryAfterError` / `lastUserBeforeError` | 口径与 `ChatRetryTest` 一致 | 点了却提示「前面没有可重答的问题」 |
| 13 | `insertSummaryAt` | 覆盖范围口径（内存 + 暂存位）、`summaryCover` | 摘要重复压旧内容 / 丢段 |
| 14 | `loadEarlier` | **不能走 `reloadHistory`**（会清 `prevCount`） | 展开后立刻收回 |
| 15 | `reloadHistory` | `prevCount=0` → 载入 → 重铺 → 重算环 → 重灌草稿 | 换会话串草稿/串附件（`519-529` 记的 bug） |
| 16 | `onScrolled` | 两条判据 + 失败也清展开态（`2255-2258`） | 每帧重试构建 JSON |
| 17 | `boundDollId` | 每次现算、不缓存 | 串人设/串记忆库 |
| 18 | `onPicked` / `onFailed` | 图与文本互斥（`431-436`） | 同时挂图和文本 |
| 19 | `clearAttachment` | 发送瞬间不清、OCR 完再清（`1509-1510`） | 附件条提前消失 |
| 20 | `showSearchDialog` | 命中后 `renderHistory` + `flashMemo` | 搜索无反馈（注：高亮已死，见 §2.1） |
| 21 | `editAndResend` | 截断到 `index` **之前** | 模型看到同一句话两遍 |
| 22 | `rollbackTo` / `branchAt` | 回滚删、分支复制 | 不可逆数据误删 |
| 23 | `deleteAt` / `saveToMemory` / `replyTo` / `copyAt` / `copyMany` / `copyLastReply` | 逐条语义与提示文案 | 功能静默失效 |
| 24 | `learnFromAt` | 返回 `Boolean` 且去重、上限 12 | 菜单文案与实际不符 |
| 25 | `flashMemo` | 单计时器互斥 + 3 秒收起 | 旧计时器抹掉新提示（`339-345`） |
| 26 | `refreshHint` | `HomeUi.hasChatTarget` 判据 | 提示条不消失 |
| 27 | `applyBackground` | 与首页共用同一张图 + 可读性遮罩 | 背景不生效/变形 |
| 28 | `showModels` / `showThink` | 走 `ChatPanels`（Dialog 层） | 面板被裁在面板内 |
| 29 | `openPicker` / `openMain` | 归档态禁选图；`EXTRA_OPEN_PROVIDERS` 协议 | 跳转落空（`213-231` 记的 bug） |
| 30 | `companion object` 两个常量 | 3 轮工具、12 条示例 | 预算/成本失控 |

### 表 B · 可改为状态驱动（11 项 · 其中 4 项已完成或改判）

迁移后**不再暴露为方法**，改为「写一个 `ChatState` 字段 / 触发一次状态事件」，由 Compose 读。

> **进度（r27 更新）**
> - ✅ 已完成：`refreshInputRow`（删，纯别名）、`scrollToSummary`（内联）、`refreshHint`（抽顶层 `chatHintText`）、
>   `setMemoBusy`（已接回，3 处调用）
> - ↪️ 改判移出：`clearChat`（是数据操作，非状态写入）
> - ⏳ 待做：`refreshCtxRing`（**口径已定：陈旧值算 bug**，方案见 `docs/CHATPANEL_BATCH2.md` §7）
> - ⛔ 当前不可动：`onInputChange` / `onScrolled` / `openPicker` / `showModels` / `showThink`
>   —— 这 5 项已成为 `ChatHostUi` 接口成员，删改必须同时动 `ChatUi.kt`，且「调用点集合相等」
>   在接口契约存在时**不再推出「可以删」**（详见 BATCH2 §1）

| # | 成员 | 现状 | 目标形态 |
|---|---|---|---|
| 1 | `refreshInputRow` | **✅ 已完成（r27）**：它已退化为私有 `refreshAttachStrip()` 的单行别名 | 已删除方法，3 处调用点改调私有函数（行为逐字节相同） |
| 2 | `refreshCtxRing` | 只写 `ChatState.ctxRatio`（`ChatPanel.kt:1430`） | **方案已定（r27）**：计算收进 `renderHistory()` 开头（早于空历史 early-return，保证清空后归 0），增量路径（`onSend` / `handleReply`×3）用私有 `syncCtxRatio()` 单独补；`branchAt` 另补 `loadHistory`+`renderHistory`。随后删除方法与 8 处调用点。**不用 `derivedStateOf`** —— 它会在每次重组读盘算比例，且无法覆盖增量路径。详见 `docs/CHATPANEL_BATCH2.md` §7 |
| 3 | `refreshHint` | **✅ 已完成（r27）**：已删除方法，判据抽成顶层纯函数 `chatHintText(ctx)` | `ChatActivity.kt:56/103` 与 init 处改为直接 `ChatState.hint = chatHintText(...)`（未用 derivedStateOf，因它要读盘） |
| 4 | `scrollToSummary` | **✅ 已完成（r27）**：已内联到唯一调用点（`onScrolled` 收回分支） | 现为 `if (summaryIndex >= 0) ChatState.requestScrollTo(summaryIndex)`；`summaryIndex` 仍私有，待随后续批次进 ChatState |
| 5 | `onInputChange` | 写 `input` + 存草稿（276-279） | `ChatState.input` 已是 `mutableStateOf`；草稿保存改成 `snapshotFlow{ input }.debounce.collect` |
| 6 | `clearChat` | **分类修正（r27）**：这不是「只写 ChatState 的纯壳」，而是**数据操作**（`history = JSONArray()` + 存盘 + 清暂存位）。它本不该在本表 | **已移出表 B**，归入「抽 `ChatStore` 数据层」待办（属表 A / 重构范畴，不是状态驱动） |
| 7 | `onScrolled` | 只做判据 + 调 `collapsePrev` | 判据搬到 Compose（`LazyListState`）内的 `LaunchedEffect`，`collapsePrev` 留数据层 |
| 8 | `setMemoBusy` | **已完成（r27 更新）**：已接回，现有 **3 处调用**（`ChatPanel.kt:917` 发起 summarize 之前置 true；`:939` 失败分支复位；`:950` 成功分支复位） | **无需再改**——表 B 原写的「当前 0 调用」已过时。原拟的 `ChatState.memoBusy: Boolean` 也不必加：现有 `ChatState.memoText` + 私有 `flashHold`/`memoBusyOn` 已覆盖全部状态与时序，再拆一层无收益 |
| 9 | `openPicker` | 只做 Activity 跳转 | 改 `rememberLauncherForActivityResult`，或保留为宿主回调（二选一，**建议保留宿主回调**，因为 `PickFileActivity` 是多用途选择器） |
| 10 | `showModels` / `showThink` | 只调 `ChatPanels.xxx(activity)` | 改成 `ChatState.showModelsPanel = true` 之类的开关，面板在 Composable 里渲染（这才是 Compose 的做法；**当前 `showModels` 能工作只是因为 `ChatPanels` 走 Dialog 窗口**，`ChatPanels.kt:48` 的注释已承认这一点） |
| 11 | `showChat` | **✅ 已完成（表 C 批次）**：0 调用，已随表 C 清理删除 | 无需动作 |

### 表 C · 可删除（0 调用，8 项）

每项的复现命令见 §1。

| # | 成员 | 行 | 唯一命中 | 删除前必须确认 |
|---|---|---|---|---|
| 1 | `addBubble(str, z)` | 192 | `ChatPanel.kt:192`（声明） | 它转调私有 `appendBubble`，**`appendBubble` 是活路径**（被 `1476/1943/2054/2066/2108` 调），只删 `addBubble` |
| 2 | `showMemory()` | 301 | `ChatPanel.kt:301`（声明） | 体内 `openPicker()`（302）已由工具条直连（`ChatUi.kt:1112`），删除不影响发图 |
| 3 | `setMemoBusy(busy)` | 312 | 声明 + 5 处注释 | **有耦合**：它写 `memoBusyOn`/`flashHold`，而 `flashHold` 被 `flashMemo` 的收尾读到（329/354/356）。删它要连带简化 `flashHold`/`memoBusyOn` 两个私有字段；`ChatState.memoText` 的「记忆总结中」分支也随之失效 |
| 4 | `showChat()` | 477 | `ChatPanel.kt:477`（声明） | 归档页已下线，`browsingArchives` 全仓只有 `false`（67/136/478）⇒ `ChatState.browsingArchives` 恒 false，`ChatUi.kt:1087/1166` 两个 `if` 是死分支，可一并清 |
| 5 | `confirmClearChat()` | 1302 | `ChatPanel.kt:1302`（声明） | **这不是「该删」而是「入口丢了」**：`ChatUi.kt:169-170` 声称「清空当前对话保留在 ActionRow」，实测 `ActionRow` 只有「重新生成/复制」（`ChatUi.kt:991-992`）。建议**补回入口**而不是直接删 |
| 6 | `ctxUsedChars()` | 1328 | `ChatPanel.kt:1328`（声明） | `CtxRing.kt:13/17` 注释说「换口径只改 ChatPanel 的估算函数」，实测环读的是 `ChatState.ctxRatio`（`ChatUi.kt:267`）。注释已过期 |
| 7 | `doSearch(str)` | 1908 | `1888/1893` 是同名**局部变量**，`1908` 是声明 | 联网工具已收口到 `ChatToolRegistry`（1916-1925 的 `runTool` 才是活路径），删除安全 |
| 8 | `handleReply(str)` 单参重载 | 1992 | 声明 + 自身转发 | 三参版是唯一活路径；单参版无任何调用 |

补充两条「不算成员但应一起清」：

| 项 | 行 | 说明 |
|---|---|---|
| `highlightIndex` | 745 | 写 1（723）读 0；`renderHistory` 与 `ChatItem` 无高亮字段 |
| `import android.view.ViewGroup` / `ViewParent` | 9 / 11 | 只出现在注释里，无代码引用 |

---

## 6. 建议迁移顺序（先易后难，每批可独立验收）

原则：**先删死代码与纯状态壳（零行为风险），再抽数据所有权（中风险），最后动网络/线程/View 壳（高风险）**。
每批结束后跑：`python3 tools/kotlin_precheck.py`、`python3 tools/comment_balance.py`、`bash tools/ui_audit.sh`。

### 批次 1 · 清死代码（0 风险，改动约 90 行）

删除：`addBubble`、`showMemory`、`showChat`、`ctxUsedChars`、`doSearch`、`handleReply(1 参)`、`highlightIndex`（含 723 的写入）、
两个无用 import、`ChatState.browsingArchives` 死分支（`ChatUi.kt:1087/1166`）。
**验收**：`grep -c -w '<M>' app/src` 对上述每个名字只剩 0 命中；编译通过；无行为变化。

### 批次 2 · `setMemoBusy` + `flashMemo` 合并（低风险，改动约 60 行）

先确认产品口径：`setMemoBusy` 无调用者 ⇒「记忆总结中」从来不会显示。
两条路选一：① 删除 `setMemoBusy`，同时把 `memoBusyOn`/`flashHold` 合并进 `flashMemo` 的单一状态；
② 由 `insertSummaryAt` 调 `setMemoBusy(true/false)` 把功能接回来（**推荐**，`MemExtractor.summarize` 是异步的，本来就需要这个状态）。
**验收**：`ChatState.memoText` 的三种取值（总结中/失败提示/空）都有可达路径。

### 批次 3 · 纯状态壳内联（低风险，改动约 40 行）

`refreshInputRow`（4 处）、`refreshCtxRing`（8 处）、`refreshHint`（2 内部 + `ChatActivity` 2 处）、`scrollToSummary`（1 处）。
改为 `derivedStateOf` / 直接写 `ChatState`。
**验收**：`ChatState.ctxRatio` / `.hint` / `.input` / 滚动请求在原有场景下数值不变（可对照旧版截图或加临时日志）。

### 批次 4 · 把 `history` 所有权从 View 里拿出来（中风险，改动约 300 行）

这是**结构性**的一步，也是后面一切的前提：

- `ChatHistoryStore` 有 **9 个**以 `ChatPanel` 为形参的函数：`loadHistory:102`、`saveHistory:346`、`prependPrev:491`、`collapsePrev:520`、
  `push:699`（3 参）、`push:703`（4 参）、`push:725`（6 参）、`autoExtract:786`、`buildRequest:1057`。
- 它们只用到 `host.getContext()`（7 处）、`host.history`（27 行）、`host.prevCount`（2 行）、`host.boundDollId()`（1 行）。

做法：引入一个不依赖 View 的宿主接口（例：`ChatHistoryHost { context; history; prevCount; boundDollId() }`），
让 Kotlin 的 `ChatPanel`（若仍存在）实现它，或直接把 `history` 移进 `ChatStore`。
**不要**改这 9 个函数的业务逻辑（`trimForSave` / `byPoolSafe` / `collapsePrevOnLoad` 的语义必须原样）。
**验收**：`grep -rn 'host\.history'` 归零或全部指向新接口；`ChatRetryTest` 与全部数据层单测通过。

### 批次 5 · 网络/工具/收尾链路去 View 化（高风险，改动约 400 行）

`onSend` / `regenerate` / `regenerateAt` / `stopGenerating` / `askModel` / `askModelStream`(private) / `runTools` /
`handleReply` / `finishWithError` / `retryAfterError` / `insertSummaryAt` / `applySummary`(private) / `flushStream`(private)。

三个必须原样保留的机制：
1. `requestTicket` 票号三校验（`1545`、`1564-1572`、`2122`）；
2. `isAttachedToWindow` 的 6 处语义（尤其 `handleReply` 的「先落盘、后判在屏」`2082-2103`）；
3. `post`/`postDelayed`/`removeCallbacks` 换成显式 Handler 或 Compose `CoroutineScope`（**作用域必须与页面同生命周期**，否则离屏丢消息）。

**验收**：逐场景手测 6 条 —— 发送、停止、切会话、后台返回、断网、工具轮；并对照 `CHANGELOG.md` 里记录的
「流完即消失」「返回再进来中断发话」「点了停止还是回了」三个历史缺陷是否复现。

### 批次 6 · 去 View 壳（最后一刀，改动约 120 行，含外部文件）

按 §4 的 V1-V13 逐条落地：去掉 `: LinearLayout`、`addView`、`clipToOutline`/`outlineProvider`、
`GlobalBackground.install(this)`（**需要先改 `GlobalBackground`**）、`PickFileActivity.setListener(this)`、
`context as? Activity` 三处 cast。
**这一批的前置条件**：批次 1-5 全部完成，且 `ChatUi.build(host: ChatPanel)` 的 13 个函数签名已经不再需要 `ChatPanel`。
**验收**：`grep -nE 'LinearLayout|addView|clipToOutline|outlineProvider|isAttachedToWindow|postDelayed' ChatPanel.kt` 归零（或仅剩注释）；
`bash tools/ui_audit.sh` 通过；聊天页在 6 条场景下与迁移前一致。

---

## 7. 最高风险前 5（按「漏迁后果 × 隐蔽程度」排序）

| 排名 | 成员 | 风险 | 表现 | 为什么隐蔽 |
|---|---|---|---|---|
| 1 | `history` + `ChatHistoryStore` 9 个 host 形参 | 整条数据链 | 消息不落盘 / 落错会话 / 展开态被裁 | 不会编译报错；`JSONArray` 可变，误以为是「引用传递就没问题」 |
| 2 | `handleReply`(3 参) + `finishWithError` + `askModel` | P0 级丢回复 | 「字流完 → 全没了」 | `2028-2031` 的快照顺序是**顺序敏感**的，重排即复现 v0.1.6~v0.1.9 的缺陷 |
| 3 | `onSend` | 发送链 | 气泡长按无反应 / 草稿串会话 / 附件提前消失 | 依赖「先落库再挂气泡」与 OCR 闸门三段顺序 |
| 4 | `boundDollId` | 数据隔离 | 串人设、记忆写错库 | 返回值永远非空，错了也「看起来正常」 |
| 5 | `prevCount` + `loadEarlier` + `onScrolled` + `reloadHistory` | 展开/收回状态机 | 补回的历史被裁掉、或每帧重试 | 四个成员互相写同一个字段，且 `reloadHistory` 会清零它（`256` 明确禁止走这条） |

---

## 8. 无法确定 / 待确认

| 项 | 状态 | 原因 |
|---|---|---|
| 构造参数 `z: Boolean` 的用途 | **待确认** | `grep -n '\bz\b' ChatPanel.kt` 全文件只在 `58` 声明处命中；`init`（134-161）与所有属性初始化都没读它。`ChatActivity.kt:48` 传的是 `true`。它现在**很可能是死参数**，但删它会改构造签名（跨包契约），故不擅自判断 |
| 「清空当前对话」应该删还是补回入口 | **待确认（产品口径）** | 代码事实：`confirmClearChat` 0 调用，`ChatUi.ActionRow` 无该入口（`991-992` 只有重新生成/复制）。但 `ChatUi.kt:169-170` 的注释声称「保留在 ActionRow」。是「入口漏迁」还是「需求下线」，需要产品确认 |
| 「搜索命中高亮」是否要恢复 | **待确认（产品口径）** | 代码事实：`highlightIndex` 写而不读，`ChatItem` 无高亮字段。恢复需要同时改 `ChatItem`、`renderHistory`、`ChatUi` 三处 |
| `setMemoBusy` 的调用方是否曾经存在（`MemSummarizer`？） | **待确认** | 注释多处写「回调 finally 必调 `setMemoBusy(false)`」，但全仓无此调用；注释提到的 `MemSummarizer` / `SheetPanel` / `ChatDrawer` 在仓库里也已不存在（`47` 行注释仍列着它们）。无法判断是重构中途丢了还是从未接上 |
| `ChatPanels` 是否最终要改成 Composable 内嵌面板 | **待确认** | 现在走 `DhDialogHost`/Dialog 窗口，能用但不是 Compose 惯用法；`ChatPanels.kt:48` 的注释把原因归为「调用点在命令式代码里」。批次 5 完成后这个约束才解除 |
| `activity` 在非 Activity 宿主下的行为 | **待确认** | 只有 `ChatActivity`（`ChatActivity.kt:37`）一个构造点，所以「非 Activity 宿主时为 null」这条分支当前**从未被走过**。若将来挂进桌宠悬浮窗，`ChatUi.kt:87` 的 `installForActivity(null)` 会返回 null 并崩 —— 这一点需要在迁移前决定 |

---

## 9. 语法/风格注意事项（给后续实现者）

- 本仓库有「嵌套注释」隐患（`tools/comment_balance.py` 在查）。**不要在注释里写 `some/path/*` 这类含 `*/` 的路径通配**。
- 本文件所有路径都用反引号包裹，不给 grep 制造歧义。
- 迁移期间如果必须临时保留某个成员（例如 `loadEarlier` 的兼容壳），在本文件 §5 表 A 里加一行「临时保留 + 移除条件」，不要悄悄改表。