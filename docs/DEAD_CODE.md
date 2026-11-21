# 死代码清单（M9 收口）

- 任务：`task-28` 死代码系统性清理
- 方法：`tools/symbol_refs.py` 初筛 → **自建精确分析**复核 → 人工逐项确认 → A/B/C 分类
- 性质：**A 类已删除**（每步跑门禁）；B/C 类只记录判断依据，不动代码

---

## 0. 结论速览

| 指标 | 改前 | 改后 |
|---|---|---|
| Kotlin 文件总数 | **326** | **324** |
| 删除整文件 | — | 2（`CrashLogExporter.kt` 92 行、`CtxRing.kt` 99 行） |
| 删除文件内声明 | — | 4（`FeedbackBox`、`AppMessageQueue`、`AppMessage`、`MessageKind`、`AppSnackbarHost`） |
| 清理死 import | — | 5 |
| A 类（确定死，已删） | — | **6 项** |
| B 类（仅本文件用，建议降级） | — | **16 项**（其中 2 项**禁止降级**，见 §3） |
| C 类（Manifest 引用，保留） | — | **4 项** |
| 排除（受限文件 / 有测试覆盖） | — | **5 项** |

**重要发现**：`tools/symbol_refs.py` 的 `UNUSED` 输出**不可直接用于本次任务**（1981 项，几乎全是假阳性）。原因见 §1。

---

## 1. 为什么不能直接用 `symbol_refs.py` 的 UNUSED 列表

跑 `python3 tools/symbol_refs.py`（非 quiet）会输出 1981 项 "UNUSED"，但抽样就是错的：

```
UNUSED class AexReceiveActivity   ← 实际被 AndroidManifest 注册
UNUSED fun pageExtra              ← 实际是 private，且同文件 68 行就在用
UNUSED fun consumeOpenProviders   ← 实际是 MainActivity 的 private 成员
```

**根因**（读脚本源码 `tools/symbol_refs.py:41-49` + `:264-272` 确认）：

1. `DECL_RE` 的缩进是 `\s{0,4}` —— 它把**类内成员函数**也当成了「顶层声明」收录；
2. 引用索引 `ref_pattern` 只匹配 **PascalCase**（`[A-Z][a-z]...`），小写函数名（`pageExtra`、`consumeOpenProviders`）**永远不会被当作引用**；
3. 于是「小写 + 成员函数」= 必然 `users` 为空 ⇒ 必然进 UNUSED 列表。

这条是脚本的**系统性假阳性**，不是个别误差。所以本次没有用它作为删除依据，改为自建分析（§2）。

> 建议：脚本的 `orphans` 段要么把 `DECL_RE` 收紧到顶格（`^` 无缩进），要么在报告里把 kind 限定为 `object|class|interface` 且名字为 PascalCase。当前形态对「找死代码」这个用途无效，只对它的主职（悬空引用检测）有效。

---

## 2. 自建分析方法（可复现）

```bash
# 只看真正顶格（column 0）的声明，且引用索引不限大小写
python3 - <<'PY'
# 完整脚本见本次任务产出 /tmp/anal.py 的思路：
#   1) 去注释/字符串
#   2) TOP 正则：必须 m.start() == 行首（排除成员函数）
#   3) 引用索引：\b[A-Za-z_]\w*\b 全大小写
#   4) 分类：无任何外部文件引用 + 本文件内 0 次使用 → A
#            无外部引用 + 本文件内有使用       → B
#            有外部引用                         → X（不动）
PY
```

结果：**386 → 379 个顶层声明**（删除后），A=8、B=33、X=338。

**⚠️ 该方法仍有一个盲区**：它只扫 `app/src/main/java`，**不扫 `app/src/test`**。
这导致 `MessageDeduper` 曾被判为 A 类 —— 实际它被 `FeedbackStateTest.kt` 使用。凡涉及类/函数删除，必须额外跑一遍：

```bash
grep -rn '<符号名>' app/src/test
```

---

## 3. A 类 · 确定死代码（已删除）

| # | 声明 | 文件 | 行 | 判定依据 | 处理 |
|---|---|---|---|---|---|
| 1 | `object CrashLogExporter` | `core/CrashLogExporter.kt` | 全文件 | 全仓 0 引用（含 .kt/.xml/test）；功能已被 `core/DiagnosticsExporter.kt` 取代（后者含 `crashGroups` 统计，且被 `DeveloperOptionsPage` 真实调用 4 处） | **删整个文件**（92 行） |
| 2 | `class CtxRing : View` | `ui/theme/CtxRing.kt` | 全文件 | 0 处实例化。`ChatUi.kt` 已用 Compose 的 `CtxRingView` 重绘替代；原文件注释「由 ChatPanel.build 创建」已过时 | **删整个文件**（99 行） |
| 3 | `fun FeedbackBox` | `ui/feedback/DhFeedback.kt` | 39 | 0 引用。它只是「Box + AppSnackbarHost 叠底」的组合壳 | **删函数** |
| 4 | `class AppMessageQueue` | `ui/feedback/AppMessage.kt` | 31 | 0 引用。Compose Snackbar 队列，全 App 实际用的是 `AppToast`（10 处） | **删类** |
| 5 | `data class AppMessage` + `enum class MessageKind` | 同上 | 12 / 15 | 仅被 #4 使用；#4 删除后连带成死代码 | **删（连锁）** |
| 6 | `fun AppSnackbarHost` | `ui/feedback/DhFeedback.kt` | 19 | 仅被 #3 使用；#3 删除后连带成死代码 | **删（连锁）** |

### 删除顺序与门禁（每次删完立刻跑）

| 步骤 | 删除内容 | 门禁结果 |
|---|---|---|
| 1 | `CrashLogExporter.kt` + `CtxRing.kt`（2 文件） | precheck ✅ comment ✅ refs ✅ |
| 2 | `FeedbackBox` | precheck ✅ comment ✅ refs ✅ |
| 3 | `FeedbackBox` 遗留的 5 个死 import | precheck ✅ comment ✅ refs ✅ |
| 4 | `AppMessageQueue` + `AppMessage` + `MessageKind` + 5 死 import | precheck ✅ comment ✅ refs ✅ |
| 5 | `AppSnackbarHost` + 3 死 import | precheck ✅ comment ✅ refs ✅ |
| 6 | 最后一个死 import（`Modifier`） | precheck ✅ comment ✅ refs ✅ anim 101/101 |

### 删除前的一处主动修正

`ChatUi.kt:264` 有一条 KDoc 链接指向 `[com.dollhouse.app.ui.theme.CtxRing]`。删掉该类后链接会断，
已改为纯文本描述并注明「该类已在 M9 死代码清理中删除」。**这是删除前做的**，不是事后补的。

---

## 4. B 类 · 仅本文件内使用（建议降级为 private）

共 33 项，其中 **21 项已经是 private**（无需动作），**16 项仍是 internal/public**。

### 4.1 ⚠️ 禁止降级（有单元测试依赖）

| 声明 | 文件 | 原因 |
|---|---|---|
| `class MessageDeduper` | `ui/feedback/AppMessage.kt:7` | **`FeedbackStateTest.kt:32,41` 直接 `MessageDeduper()`**。降为 private ⇒ 测试编译失败 |
| `class ConfirmGuard` | `ui/feedback/DhFeedback.kt` | **`FeedbackStateTest.kt:11,21` 直接 `ConfirmGuard()`**。同上 |

> 这两项在自建分析里会被判为 A/B（因为不扫 test），**是本次最容易误删的一类**。
> 保留 internal 是正确形态：它是「给同模块测试用的可见性」，不是死代码。

### 4.2 可降级候选（本任务未动，属 B 类记录）

| 声明 | 文件 | 本文件内使用次数 |
|---|---|---|
| `ChatImageToolState` | `data/ChatDraftStore.kt:24` | 7 |
| `EffectiveChatConfig` | `data/PersonaChatConfig.kt:369` | 3 |
| `DhColors` | `ui/compose/DhTheme.kt:39` | 5 |
| `LocalDhColors` | `ui/compose/DhTheme.kt:157` | 2 |
| `LocalDhFontFamily` | `ui/compose/DhTheme.kt:162` | 2 |
| `LocalDhFontFamilyBold` | `ui/compose/DhTheme.kt:165` | 2 |
| `LocalDhPalette` | `ui/compose/DhTheme.kt:175` | 2 |
| `LocalDhType` | `ui/compose/DhTheme.kt:180` | 2 |
| `DollPinyin` | `ui/home/ContactScreen.kt:486` | 1 |
| `DhButtonStyle` | `ui/kit/Components.kt:93` | 9 |
| `DhButton` | `ui/kit/Components.kt:140` | 1 |

**为什么本任务不动它们**：
1. `DhTheme.kt` 的 5 个 `LocalXxx` 是 CompositionLocal —— 它们「本文件内有引用」是因为在 `CompositionLocalProvider` 里被 provide；将来任何 Composable 都可能通过 `LocalDhColors.current` 取值。降 private 会让「主题供给」只能在本文件内读，语义上不对。
2. `Components.kt` 与 `DhTheme.kt` 属于**本次任务排除文件**（队友在改）。
3. `DhButton` / `DhButtonStyle` / `DhColors` 是**组件库对外 API**，「本文件内使用」只是因为库外还没人用（与本轮 M9 的「库建好但没落地」是同一个问题）。降 private 会**主动堵死后续落地**，方向反了。

> 结论：B 类里真正值得降级的只有 `ChatImageToolState` / `EffectiveChatConfig` / `DollPinyin` / 以及各文件里已经 private 的常量。其余应保持现状或作为「待落地 API」跟踪，而不是降级。

---

## 5. C 类 · 被 Manifest / XML 引用（必须保留）

| 声明 | 文件 | 引用来源 |
|---|---|---|
| `class AexReceiveActivity` | `AexReceiveActivity.kt:33` | `AndroidManifest.xml`：`android.intent.action.SEND` / `SEND_MULTIPLE` / `VIEW` 三个 intent-filter |
| `class BootReceiver` | `BootReceiver.kt:22` | `AndroidManifest.xml`：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| `class PetLinkActivity` | `PetLinkActivity.kt:22` | `AndroidManifest.xml`：`VIEW` + `BROWSABLE` 深链 |
| `class DollhouseApp` | `keepalive/DollhouseApp.kt:23` | `AndroidManifest.xml`：`android:name=".keepalive.DollhouseApp"`（Application 类） |

这 4 项在自建分析里全部是「A 类（零引用）」—— **如果按 A 类删掉，App 会直接起不来**。
这正是任务里要求「排除 Manifest 引用」的原因。核对命令：

```bash
grep -oE 'android:name="[^"]+"' app/src/main/AndroidManifest.xml
```

> 同类还有 `MainActivity` / `ChatActivity` / `PickFileActivity` / `BackgroundCropActivity` /
> `CoverCropActivity` / `PetService` / `PetTileService` / `IconDefault` 等，
> 它们**因为有代码引用所以没进 A 类**，但同样属于 Manifest 绑定。

---

## 6. 排除项（不删，且说明原因）

| 声明 | 文件 | 排除原因 |
|---|---|---|
| `fun DhChip` | `ui/kit/Components.kt:609` | **任务排除文件**；且是组件库对外 API，「库外 0 调用」是待落地状态，不是死代码 |
| `fun DhIconButton` | `ui/kit/Components.kt:755` | 同上 |
| `private val LoadingSpinner` | `ui/kit/Components.kt:118` | 同上（且它本身是 private，只在库内被某处使用或已孤立，属该文件内部事务） |
| `MessageDeduper` / `ConfirmGuard` | 见 §4.1 | 有单元测试依赖 |

---

## 7. 「零调用」该删还是该留 —— 判断标准（2026-11 追加）

> **为什么单开一节**：本文档原来只给了**本次**每一项的结论（删 / 降级 / 保留），
> 没有给**可复用的判据**。于是下一个人看到零调用声明时，仍要重新纠结一遍；
> 更危险的是**可能按「咱们倾向保留」这种印象去猜** —— 而保留与删除都对过、也错过。
> 这一节把「凭什么这么判」写下来，让结论可复核、可反驳。

### 7.1 一句话判据

> **「零调用」只回答「有没有人用」，不回答「该不该留」。**
> 判「删」看它**有没有被需要**；判「留」看它**有没有被承诺**。
> 两者都否 → 删。两者都成立 → 留，且记下它在等什么。

### 7.2 三类「零调用」，处理完全不同

| 类别 | 特征 | 处理 | 本项目实例 |
|---|---|---|---|
| **A · 真废弃** | 曾经有调用者，后来功能被移除；**没有任何文档承诺它会被用** | **删**（连注释里的引用一起清） | `FeedbackBox` / `AppMessageQueue` / `AppSnackbarHost`（§3 已删）；`ImportAssistantKit.BusyHint()`（见 7.4） |
| **B · 待落地** | 从建起就没人用，**但有明确文档/任务把「用起来」列为目标** | **留**，并在文档里写明「等什么」 | `DhChip` / `DhButton`（§6、`KIT_USAGE.md` §八-1） |
| **C · 有外部契约** | 零调用，但被测试 / Manifest / 反射 / XML 引用 | **留**（且不可降级） | `MessageDeduper` / `ConfirmGuard`（有测试）、`CtxRing` 之外的 Activity（Manifest） |

### 7.3 四条可操作判据（按顺序问，前一条能定就不问下一条）

1. **有没有外部契约钉住它？**
   测试、`AndroidManifest`、XML 布局、`@JvmField`/反射入口、公开 API 文档。
   → 有则**留**，且注明契约在哪。（判据最强，因为它会**编译失败或运行时崩**，不是审美问题）
2. **它曾经被谁调用？为什么消失？**
   用 `git log -S "<符号名>" --all` 查。
   → 若曾有调用、后来随功能一起被删 → **真废弃，删**。
   → 若**从未有过调用**（只在建库那次 commit 里出现）→ 进第 3 条。
3. **有没有文档把「用起来」写成了目标？**
   搜 `docs/` 里的组件名：`KIT_USAGE.md` 的收编建议、设计稿、任务清单。
   → 有则**待落地，留**，并把出处写进排除表（否则下一个人还会再查一遍）。
4. **以上都没有 → 删。**
   注意：这条是**默认可删**，不是默认保留。「暂时留着说不定以后有用」不是理由 ——
   真要用时 `git log` 还能找回来，而留在代码里会**持续误导**（见 7.5）。

### 7.4 本项目的两个具体判定（含判断过程）

**`DhChip` / `DhIconButton` → 留（B 类·待落地）**
- 判据 2：`git log -S "DhChip" --all` 只有建库那一次 commit（`10fc00f`）→ 从未被调用
- 判据 3：`KIT_USAGE.md` §八-1 把 `DhChip` 所在按钮体系列为「最大缺口」；
  `docs/DEAD_CODE.md` §6 明确「库外 0 调用是待落地状态，不是死代码」
- ⇒ **留**。且**不要加 `@Deprecated`**：`@Deprecated` 的语义是「将来会删」，
  与「该用起来」正好相反 —— 它会在 IDE 里给所有引用划删除线，**反而阻止落地**

**`ImportAssistantKit.BusyHint()` → 应删（A 类·真废弃），但本次未动**
- 判据 1：无测试、无 Manifest、无反射引用
- 判据 2：曾有调用（`ImportAssistantPage` / `ReplaceModelPage`），
  随 B4 接入统一加载态（`DhLoadingState`）后停用 —— 两处只剩**注释**在提它：
  ```
  ImportAssistantPage.kt:382   // 【B4·接入加载态】改造前这里是 BusyHint(busyText) ——
  ReplaceModelPage.kt:197      // 【B4·接入加载态·识别阶段】改造前是 BusyHint(busyText)
  ```
- 判据 3：无任何文档要求保留
- ⇒ **应删**（三条都指向删）。**本次未删的原因**：它属 `ui/doll` 包，
  与本轮改动无交集，且「删函数 + 清两处注释」应单独一次改动以便回滚。
  **列入下次清理清单。**

> 这个例子正好说明为什么需要标准：`BusyHint` 与 `DhChip` **同样是零调用**，
> 但结论完全相反 —— 一个是「被功能替换后的残留」，一个是「还没轮到它上场」。

### 7.5 三条容易判错的陷阱

1. **「只在注释里出现」不是引用。**
   `grep` 命中不等于有人用。判断前必须**去注释与字符串**再统计
   （本文档 §2 的自建脚本已这么做；手工核对时用
   `grep -rn "<名字>" | grep -v '\*'` 之类仍会漏，最好写脚本）。
2. **「本文件内还有使用」不等于活着。**
   B 类（仅本文件内使用）里有一批是 CompositionLocal 的 provide ——
   它们在**本文件内**被引用，但真正的消费方是将来任何 Composable。
   降 private 会破坏「主题供给」的语义（§4.2-1）。**这类不是死代码，是分层的正常形态。**
3. **「删了就编译不过」是最强的保留理由，但也最容易被漏查。**
   §4.1 的 `MessageDeduper` / `ConfirmGuard` 就是**只被测试引用** ——
   自建分析不扫 `app/src/test`，差点判成 A 类。**任何删除前，必须把 test 纳入扫描。**

### 7.6 给下一个人

- 要删某个零调用声明：**先跑 §2 的方法、再用 7.3 的四条判据**，别凭印象。
- 要保留：**必须写下「它在等什么」+ 出处**（哪个文档哪一节）。
  只写「保留」等于把问题留给下一个人。
- 判据变了（例如产品决定不做 Snackbar 消息系统）→ **回来改这一节**，
  并重新评估受影响的条目（如 `MessageDeduper` 会从「有契约」变成 A 类）。

---

## 8. 复现命令汇总

```bash
# 1) 文件数（改前 326 → 改后 324）
find app/src -name '*.kt' | wc -l

# 2) 原脚本的 UNUSED 列表（噪音大，仅作初筛）
python3 tools/symbol_refs.py

# 3) 确认某声明是否真的零引用（含 test！）
grep -rn -w 'CtxRing' app/src --include='*.kt'
grep -rn -w 'CrashLogExporter' app/src --include='*.kt' --include='*.xml'

# 4) Manifest 引用核对
grep -oE 'android:name="[^"]+"' app/src/main/AndroidManifest.xml

# 5) 门禁
python3 tools/kotlin_precheck.py
python3 tools/comment_balance.py
python3 tools/symbol_refs.py --quiet
bash tools/anim_audit.sh
bash tools/ui_audit.sh
```

---

## 9. 残留风险与建议

| 项 | 说明 |
|---|---|
| `symbol_refs.py` 的 UNUSED 段 | 如上，对「找死代码」无效（成员函数 + 小写名系统性假阳性）。建议修脚本，或明确它只管悬空引用 |
| 自建分析不扫 `app/src/test` | 本次靠人工补上（发现 `MessageDeduper`/`ConfirmGuard` 有测试）。**后续若做同类清理，必须把 test 纳入扫描** —— 否则会删掉有测试保护的代码 |
| 未做的连锁分析 | 我删除后重跑了一次分析（A 从 11 → 8），确认没有新的死代码。但**更深的连锁**（例如某组件库 API 彻底没人用后其依赖链）未系统排查 |
| `DhChip` / `DhIconButton` | 属「组件库建好但没落地」，与 M9 的另一条主线（让组件库真正落地）相关。**不该删，该用起来** |
| `ui/feedback` 包现状 | 删完后只剩 `HintTooltip`（活，2 处）+ `MessageDeduper`/`ConfirmGuard`（测试用）+ `AppToast`（活，10 处）。包里已无死代码，但 `MessageDeduper` 目前**只有测试在用** —— 它是「为将来 Snackbar 队列准备的」，若产品确认不再做 Snackbar 消息系统，它与对应测试应一起删 |