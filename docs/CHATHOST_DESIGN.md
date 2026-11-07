# ChatHost 接口设计（ChatPanel 去 View 前置重构）

- 任务：`task-16`
- 性质：**已落地代码改动 + 设计说明**（不是纯文档任务）
- 涉及文件：`data/ChatHistoryStore.kt`、`ui/chat/ChatPanel.kt`、本文档
- 上游依据：`docs/CHATPANEL_CONTRACT.md`（其中「最高风险 #1」就是本项）

---

## 0. 结论先行

**可行性 = 是，改动面比预期小。** 已实际完成代码改动，门禁全绿。

关键事实：`ChatHistoryStore` 对宿主的依赖**只有 4 个成员**，不含任何 `ChatPanel` 专有方法。
实测改前统计（`git show HEAD:.../ChatHistoryStore.kt | grep -o 'host\.\w*'`）：

| 成员 | 出现次数 | 用途 |
|---|---|---|
| `host.history` | **30** | 读写历史数组本身 |
| `host.getContext()` | **7** | 落盘 / 取会话 id 需要 Context |
| `host.prevCount` | **2** | 「更早历史」展开态，决定保存时是否裁剪 |
| `host.boundDollId()` | **1** | 自动抽取记忆时定位人偶库 |
| 合计 | **36** | — |

**没有一个依赖是 `ChatPanel` 专有的** —— 这是本重构能成立的根本原因。
原任务描述里「若 Store 内部大量依赖 ChatPanel 的其它方法，改动面会失控」的情形**没有出现**。

改动后：`ChatHistoryStore.kt` 里 **代码级**（非注释）的 `ChatPanel` 引用 = **0**。
这意味着数据层对 UI 层的编译期依赖已被切断。

---

## 1. 接口定义

定义在 `ChatHistoryStore.kt` 内（`object ChatHistoryStore` 之前，同一文件）：

```kotlin
interface ChatHost {
    /** 整段对话上下文。读写都按字段语义，宿主自己决定是否落盘。 */
    var history: JSONArray

    /** 「更早历史」已补回的条数；0 = 当前不在展开态。 */
    var prevCount: Int

    /** 当前会话绑定的那只人偶 id（拿不到时由宿主退回默认）。 */
    fun boundDollId(): String

    /** 取 Context 用于落盘 / 读会话 id。 */
    val ctx: Context
}
```

### 三个设计决定及其理由

**① `history` 用 `var` 而不是 `fun history()` / `fun setHistory()`**

本类现有 30 处是字段式写法：`host.history = ...`、`host.history.optJSONObject(i)`、`host.history.put(...)`。
用 `var` 可以让这 30 处**一字不改**，把改动面压到「只改 9 个函数签名」。
若换成方法式，30 处全要改，风险与收益不成正比 —— 这与本轮「最小可行改动」的目标一致。

**② Context 用 `ctx` 而不是 `context`**

`ChatPanel` 继承 `android.view.View`，而 `View.getContext()` 是 **final**。
Kotlin 里无法 `override val context` 去实现一个同名接口属性 —— 会直接编译失败。
所以接口命名为 `ctx`，实现方桥接一行：

```kotlin
override val ctx: android.content.Context
    get() = context
```

**③ 接口成员一律 public（这是唯一一处「可见性必须放宽」的代价）**

Kotlin 接口成员都是 public，而覆写**不允许收窄可见性**。`ChatPanel` 原来三个成员是 `internal`：

| 成员 | 改前 | 改后 | 影响 |
|---|---|---|---|
| `history` | `internal var` | `public override var` | 无（见下） |
| `prevCount` | `internal var` | `public override var` | 无 |
| `boundDollId()` | `internal fun` | `public override fun` | 无 |

**为什么「无影响」可以断言**：`settings.gradle` 只有 `include ':app'` 一个模块。
在单模块工程里，`internal`（模块内可见）与 `public`（全工程可见）的**可访问范围完全等价**。
放宽不会引入任何新的跨包依赖，也不会改变任何调用点的可编译性。

原注释写着「跨包契约，勿改可见性」——那句的本意是「别缩到 private / 别让 `data` 包看不见」，
而不是「必须是 internal」。本文档在此显式记录这次放宽，避免后人误以为是被随意改动。

---

## 2. 9 个函数如何改

只改形参类型，**函数体一行未动**（除 7 处 `host.getContext()` → `host.ctx`）。

| # | 函数 | 改前 | 改后 | 行 |
|---|---|---|---|---|
| 1 | `loadHistory` | `(host: ChatPanel)` | `(host: ChatHost)` | 157 |
| 2 | `saveHistory` | `(host: ChatPanel)` | `(host: ChatHost)` | 401 |
| 3 | `prependPrev` | `(host: ChatPanel): Int` | `(host: ChatHost): Int` | 546 |
| 4 | `collapsePrev` | `(host: ChatPanel, count: Int): Boolean` | `(host: ChatHost, count: Int): Boolean` | 575 |
| 5 | `push` | `(host: ChatPanel, str, str2)` | `(host: ChatHost, str, str2)` | 754 |
| 6 | `push` | `(host: ChatPanel, str, str2, str3)` | `(host: ChatHost, str, str2, str3)` | 758 |
| 7 | `push` | `(host: ChatPanel, str, str2, str3, think, thinkMs)` | `(host: ChatHost, ...)` | 780 |
| 8 | `autoExtract` | `(host: ChatPanel?, role: String?)` | `(host: ChatHost?, role: String?)` | 841 |
| 9 | `buildRequest` | `(host: ChatPanel): JSONArray` | `(host: ChatHost): JSONArray` | 1112 |

`autoExtract` 的形参是**可空**的（`ChatHost?`），保留了原语义（null 直接 return）。

另外删除了 `import com.dollhouse.app.ui.chat.ChatPanel`（改完后已成死导入）。

### 反向兼容性

接口是**超集**：`ChatPanel` 实现了 `ChatHost`，所以所有现有调用点（传 `this` / 传 `chatPanel`）
都只**变得更宽松**，没有任何一处需要改。实测全部调用点：

| 调用点 | 传参 |
|---|---|
| `ChatPanel.kt:171, 508` | `loadHistory(this)` |
| `ChatPanel.kt:656/765/808/1092/1205/1360/1454` | `saveHistory(this)` |
| `ChatPanel.kt:263` | `prependPrev(this)` |
| `ChatPanel.kt:2271` | `collapsePrev(this, n)` |
| `ChatPanel.kt:1475/2043/2054` | `push(this, ...)` |
| `ChatHistoryStore.kt:801/818` | 内部转发 `saveHistory(host)` / `autoExtract(host, ...)` |

内部转发（`ChatHistoryStore.kt:801`）也自动成立：那里的 `host` 形参已经是 `ChatHost`。

---

## 3. 为什么这样切（分层收益）

改前：

```
ui.chat.ChatPanel  (LinearLayout 子类)
        ↑ 被 data 层直接引用（9 个函数形参 + import）
data.ChatHistoryStore
```

改后：

```
ui.chat.ChatPanel  ──implements──▶  data.ChatHost  ◀──依赖──  data.ChatHistoryStore
```

- **依赖方向正确了**：数据层不再 import UI 层。`data → ui` 的反向依赖被切断。
- **载体可换**：将来 ChatPanel 换成 Compose 宿主 / ViewModel，只要它们实现 `ChatHost`，
  Store 层**一行不用改**。这正是本任务「为去 View 铺路」的意义。
- **测试可写**：`ChatHost` 是个纯接口，可以写一个 Map/JSONArray 支撑的假实现，
  在没有 Android 环境的情况下对 `saveHistory` / `prependPrev` / `collapsePrev` 写 JVM 单测。
  改前这不可能 —— 因为形参是 `LinearLayout` 子类，必须有 Android 运行时。

---

## 4. 后续迁移步骤（建议顺序）

### 第 1 步（本任务已完成）
抽出 `ChatHost`，9 个签名改向，`ChatPanel` 实现它。**行为零变化。**

### 第 2 步 · 把 `ChatHost` 从 `ChatHistoryStore.kt` 里挪出去
现在接口定义在 `ChatHistoryStore.kt` 内部，只是为了本任务「只改 2 个文件」的约束。
建议下一步把它移到 `data/ChatHost.kt` 独立文件 —— 因为它描述的是**宿主契约**，
不是「历史存储」的一部分。将来 `ChatSessions` / `ChatDraftStore` 之类也可能加入该契约。
> 这一步纯搬运，无逻辑风险；但要改 2 处 import，建议与其他 data 层改动合并做。

### 第 3 步 · 让第二个实现者出现，验证抽象是否真的成立
目前 `ChatHost` 只有 `ChatPanel` 一个实现者 —— 单实现者的接口有「假抽象」风险
（可能把 `ChatPanel` 的偶然特征固化成了契约）。
建议下一步让 `PetTalk`（迷你聊天框，同样持有 history 数组）也实现 `ChatHost`。
若它能不费劲地实现，说明契约切得对；若要为它加特殊成员，说明接口设计有问题、应趁早改。
> **这是本设计最重要的一条自检**，建议优先于任何 UI 迁移。

### 第 4 步 · 写 JVM 单测
用假 `ChatHost` 覆盖 `saveHistory` 的裁剪逻辑（`trimForSave`）、`prependPrev`/`collapsePrev`
的展开-收回闭环。这两块是历史数据最容易出 P0（丢数据）的地方，且现有测试
（`ChatRetryTest`）只覆盖了口径判定、没覆盖真实读写。

### 第 5 步 · 才轮到 ChatPanel 去 View
届时 `ChatPanel` 换成 Compose 宿主，只需新宿主实现 `ChatHost` 的 4 个成员。
`ChatHistoryStore` 不动。这一步才真正解开 `docs/CHATPANEL_CONTRACT.md` 的「最高风险 #1」。

---

## 5. 本次没做的事（明确边界）

| 项 | 为什么不做 |
|---|---|
| 没有把接口挪到独立文件 | 本任务限定「只改 ChatHistoryStore.kt / ChatPanel.kt」，见第 4 节第 2 步 |
| 没有让 `PetTalk` 实现 `ChatHost` | 超出任务范围；但它正是验证抽象的关键，见第 4 节第 3 步 |
| 没有改任何业务逻辑 / 序列化格式 | 任务硬约束；实测 diff 里函数体只有 `host.getContext()` → `host.ctx` 这一种替换 |
| 没有改 `history` / `prevCount` 的读写语义 | 同上。字段名、类型、默认值、初值全部不变 |
| 没有验证编译 | 本机无 Kotlin 编译器（`which kotlinc` = 无），只能靠 `kotlin_precheck.py` 等静态门禁 |

---

## 6. 风险与未验证项（如实记录）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **未真正编译** | 本机没有 kotlinc / aapt2，`./gradlew compileDebugKotlin` 无法运行。所有结论来自静态门禁 | 需 CI 或真机编译确认。最需要盯的是 `public override var history` 这一行是否被接受 |
| `internal` → `public` 的放宽 | 虽然单模块下等价，但这是**可见性契约的变更**，写进了本文件 | 若团队不接受，替代方案见下 |
| `override val ctx get() = context` | `View.getContext()` 是 final，但**属性 `ctx`** 是新的，不冲突（已 grep 确认 ChatPanel 内无 `ctx` 成员） | 已核实无同名冲突 |
| 单实现者 | `ChatHost` 目前只有 `ChatPanel` 一个实现，存在「假抽象」风险 | 第 4 节第 3 步用 `PetTalk` 验证 |

### 若团队不接受 `internal` → `public` 的替代方案
在 `ChatPanel` 里保留 `internal var historyInternal` 等私有字段，接口属性做单向桥接：

```kotlin
internal var historyInternal: JSONArray = JSONArray()
override var history: JSONArray
    get() = historyInternal
    set(value) { historyInternal = value }
```

代价是**多一层间接**，且 `ChatPanel` 内部 50+ 处 `history` 引用要么改名、要么继续走桥接属性
（后者其实就等价于直接用 public 属性了）。**权衡后认为不值得**：单模块下放宽 public 零收益损失，
而桥接会引入「两个名字指同一份数据」的长期困惑。故选择了直接放宽 + 注释记录。

---

## 7. 复现命令（给 reviewer）

```bash
# 1) 改前：Store 到底依赖宿主的哪些成员
git show HEAD:app/src/main/java/com/dollhouse/app/data/ChatHistoryStore.kt \
  | grep -o 'host\.[A-Za-z_][A-Za-z0-9_]*' | sort | uniq -c | sort -rn
#   → 30 host.history / 7 host.getContext / 2 host.prevCount / 1 host.boundDollId

# 2) 改后：9 个签名已改向
grep -n 'ChatHost' app/src/main/java/com/dollhouse/app/data/ChatHistoryStore.kt | grep 'fun '

# 3) 改后：代码级 ChatPanel 引用应为 0（只剩注释）
python3 - <<'PY'
p='app/src/main/java/com/dollhouse/app/data/ChatHistoryStore.kt'
bad=[l for l in open(p,encoding='utf-8')
     if 'ChatPanel' in l and not l.strip().startswith(('//','*','/*'))]
print('non-comment ChatPanel refs:', len(bad))
PY

# 4) 门禁
python3 tools/kotlin_precheck.py
python3 tools/comment_balance.py
python3 tools/symbol_refs.py --quiet
bash tools/anim_audit.sh
```