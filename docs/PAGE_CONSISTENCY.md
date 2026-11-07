# 页面级视觉一致性审计

> 产出：task-31。脚本 `tools/page_consistency_audit.py`，可复跑。
> 数据快照：2026-11-07（`feat/compose-ui-unification`，未提交工作树）。

---

## 1. 为什么单独开这一项

本工程此前已有 9 套门禁，但**全部是组件级 / 令牌级**：

| 门禁 | 回答的问题 |
|---|---|
| `ui_audit.sh` | 令牌在不在、字体阶梯、对比度 |
| `anim_audit.sh` | 动效 spec 对不对 |
| `design_parity.py` | token.css ↔ DesignSystem.kt 逐项一致 |
| `input_audit.py` | 输入框有没有聚焦态 |
| `state_audit.py` | 空/加载/错误有没有统一骨架 |
| `kit_usage_audit.py` | 组件库被用了多少 |

它们能保证「**一个组件是对的**」，但没有一项能回答
「**两个页面像不像同一个团队做的**」。

用户反复抱怨的正是后一类：

- "相似页面使用不同边距"
- "页面内容左右不对齐"
- "弹窗比例不协调"

这类问题的特点是**单页看不出来、跨页并排才明显**，而且**不会编译失败、不会触发任何现有门禁**——
是纯粹的「模型漂移」。本脚本就是补这个盲区。

---

## 2. 检查项与期望值

期望值**从 `DhDesign` 读取**，不在脚本里另存一套（避免第二个真源）。

| 项 | 期望 | 来源 | 说明 |
|---|---|---|---|
| P1 | 页面根水平 padding = **16** | `DhDesign.Space.pageX`（= `lg`） | 全 App 一口径的页面左右留白 |
| P2 | 二级页走 **`DhKit.Page`** 脚手架 | 48 处调用点 | 脚手架自带 TopBar + 页级水平 padding |
| P3 | 顶栏走 `DhKit.TopBar` / `DhKit.TabTopBar` | — | 不是手搓 `Row` + 返回箭头 |
| P4 | 页面级项间距 = **12**（或 0） | `DhDesign.Space.cardGap`（= `md`） | 卡片之间 / 分组之间的间距 |

### Tab 页与二级页的期望**本就不同**（重要）

不能用一个标准套所有页：

- **二级页**：有返回键 → 走 `DhKit.Page`（内含 `DhKit.TopBar`）。
- **Tab 页**（聊天 / 人偶 / 说说）：**没有返回键**，标题是 26sp 大标题 →
  走 `DhKit.TabTopBar`，**不走** `DhKit.Page`。
  它们的页级水平留白不在根容器上，而是逐段套同一个常量
  （`ChatListScreen` 的 `PAD_H`）或列表的 `contentPadding.start`。
  脚本对这类页比较的是**那个常量**，不是根 `padding`。

把 Tab 页按二级页标准判会得到 5 个假阳性，这是第一版脚本犯过的错。

---

## 3. 当前现状（41 个页面）

```
合规 39 / 页面 41（合规率 95%），离群 2 处
另排除 11 个非页面宿主（悬浮窗 / Dialog / Nav3 宿主），避免误报
```

| 页面（文件:行） | Composable | 类型 | 根水平 pad | 项间距 | 顶栏 |
|---|---|---|---|---|---|
| `ai/TokenStat.kt:355` | TokenContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/chat/ChatListScreen.kt:115` | Screen | tab | 16 | - | TabTopBar |
| `ui/chat/ChatUi.kt:94` | ChatContent | shell | N/A | - | 无 |
| `ui/chat/ConversationHistoryPage.kt:46` | Content | page | 16 | 12 | **手搓** |
| `ui/chat/FriendAppearancePage.kt:19` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/chat/FriendModelConfigPage.kt:43` | Content | page | 16 | **10** | **手搓** |
| `ui/chat/FriendProfilePage.kt:103` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollActionPage.kt:129` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollAnimPage.kt:133` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollAwayPage.kt:44` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollEditScreen.kt:140` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollGesturePage.kt:67` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollGroupPage.kt:78` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollHomePage.kt:87` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollImportWizardPage.kt:105` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollListScreen.kt:94` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollPersonaPage.kt:82` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollRangePage.kt:75` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollReturnPage.kt:47` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/DollTalkPage.kt:61` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/ImportAssistantPage.kt:105` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/ModelManagePage.kt:86` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/doll/ReplaceModelPage.kt:72` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/home/ContactScreen.kt:85` | Content | tab | 16 | - | TabTopBar |
| `ui/home/HomeScreen.kt:45` | HomeContent | tab | N/A | - | TabTopBar |
| `ui/provider/ProviderEditPage.kt:150` | EditContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/provider/ProviderListPage.kt:68` | ListContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/provider/ProviderModelsPage.kt:77` | ModelsContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/AboutPage.kt:114` | AboutContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/AppearancePage.kt:97` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/ChatSettingsPage.kt:74` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/DeveloperOptionsPage.kt:56` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/LamdaPage.kt:123` | LamdaContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/MemPage.kt:183` | MemContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/MemSettingsPage.kt:78` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/ModelEditPage.kt:75` | EditContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/ModelListPage.kt:70` | ModelListContent | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/MyProfilePage.kt:78` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/PermissionPage.kt:84` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/SleepSettingsPage.kt:38` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/VisionModelPage.kt:81` | Content | page | 脚手架 | - | TopBar(脚手架) |
| `ui/settings/WanderSettingsPage.kt:33` | Content | page | 脚手架 | - | TopBar(脚手架) |

### 形态分布

| 形态 | 计数 | 结论 |
|---|---|---|
| 走 `DhKit.Page` 脚手架 | **36 / 38 二级页** | 已是绝对主流的正确形态 |
| 手拼根容器 | **2 页** | 见 §4，是真正的跨页不一致 |
| 顶栏走 `TopBar(脚手架)` | 36 | — |
| 顶栏走 `TabTopBar` | 3（三个 Tab） | 符合 Tab 页期望 |
| 顶栏**手搓** | **2** | 与同目录兄弟页不一致 |

**关键结论：脚手架覆盖率 36/38（95%），页面级纪律整体是好的。**
问题不在「普遍乱来」，而在**两个孤例**——而孤例恰恰是用户最容易一眼看出的
（「为什么这一页标题比别人低一截」）。

---

## 4. 离群清单（真不一致）

### 4.1 `FriendModelConfigPage.kt:43` —— 3 项偏差，最严重

| 项 | 实际 | 期望 |
|---|---|---|
| P2 脚手架 | 手拼 `Column(Modifier.fillMaxSize().verticalScroll(...).padding(16.dp))` | `DhKit.Page` |
| P3 顶栏 | 手搓 `Row { IconButton(IC_ARROW_LEFT); Text(20sp, LH 29sp) }` | `DhKit.TopBar` |
| P4 项间距 | `Arrangement.spacedBy(10.dp)` | 12（`Space.cardGap`） |

**为什么这是真问题（并排证据）**：它和 `FriendAppearancePage` 是**同一个模块、同一层级、
同一入口形态**（`personaId` + `PageStack.push`）的兄弟页，但一个走脚手架、一个手拼：

```
ui/chat/FriendAppearancePage.kt:19   → DhKit.Page(title="聊天外观", sub=…, onBack=…)
ui/chat/FriendModelConfigPage.kt:43  → Column(...padding(16.dp)) { Row { 返回 + Text } }
```

用户从「聊天外观」切到「模型与生成」，会直接看到：**标题下移、返回键与标题距离变化、
卡片间距变紧（10 vs 12）**。这就是「不像同一个团队做的」的典型来源。

### 4.2 `ConversationHistoryPage.kt:46` —— 2 项偏差

| 项 | 实际 | 期望 |
|---|---|---|
| P2 脚手架 | 手拼 `Column(...padding(16.dp))` | `DhKit.Page` |
| P3 顶栏 | 手搓 `Row { IconButton; Text(20sp, LH 29sp) }` | `DhKit.TopBar` |

P1（16）与 P4（12）都对，所以只剩「包壳」与「顶栏」两项。

### 为什么这两页不是"故意如此"

手搓顶栏缺的不只是包壳，**还缺两处肉眼可见的间距**：

| 项 | `DhKit.TopBar` | 这两页手搓版 |
|---|---|---|
| 顶部呼吸 | `TOP_MIN_GAP = 24`（且与状态栏取较大值） | 仅来自外层 `padding(16.dp)` → **16** |
| 返回键 ↔ 标题 | `Spacer(Modifier.width(10.dp))` | **无**（`IconButton` 紧贴 `Text`） |
| 副标题 | 支持 `sub` | 不支持 |
| 行高 | 走 `UiKit.FS_TITLE` 令牌 | 裸写 `20.sp / lineHeight = 29.sp` |

所以在 edge-to-edge + 状态栏常隐的机型上，这两页的标题会**比其它 36 页明显更贴屏幕顶边**。

---

## 5. 建议修法（按影响面排序）

| # | 页面 | 改法 | 影响面 | 风险 |
|---|---|---|---|---|
| 1 | `ui/chat/FriendModelConfigPage.kt:43` | 外层 `Column` → `DhKit.Page(title="模型与生成", onBack={PageStack.pop(act)})`；删掉手搓 `Row`；`spacedBy(10.dp)` → `Space.cardGap` | 1 页，用户从好友资料进得来 | 低：结构不变，只换壳 |
| 2 | `ui/chat/ConversationHistoryPage.kt:46` | 同上（`title="历史会话"`） | 1 页 | 低：同上 |
| 3 | 两页共同 | 删掉 `lineHeight = 29.sp` 的裸值，改走 `DhKit.TopBar` 令牌（`LH_TITLE` 已是 28，两处 29 属历史漂移） | — | 极低 |

这两页改完后，脚手架覆盖率会从 36/38 变成 **38/38（100%）**，
页面级门禁则从「存量 2 处」变成「存量 0 处」。

**为什么本任务没有顺手改**：task-31 的硬约束是「不改任何 `.kt`、只新建脚本 + 文档」。
上面两项已登记为存量基线，下一批可以照单执行。

**优先修 1 还是 2**：`FriendModelConfigPage` 优先 —— 它有 3 项偏差、
且与兄弟页并排时差异最大（顶栏 + 间距同时不同）。

---

## 6. 脚本的判定口径（可复现）

### 页面怎么识别（不按文件名猜）

1. 找所有 `ComposeHost.createView(owner) { Fn(...) }` 里的 `Fn` —— 这是全工程产出页根的唯一方式。
2. 排除三类非页面宿主：**悬浮窗**（本文件含 `WindowManager`）、**弹窗**（含 `Dialog(` / `AlertDialog(`）、
   **Nav3 宿主**（`NavHost.kt`）。共排除 11 个，避免把悬浮窗当页面误报。
3. 中间层装配也算：`ProviderNav.render` / `ProviderDetailPage` / `ExperimentPage`
   内部调 `ProviderListPage.build(...)` 这类，能正确纳入
   —— **第一版漏了这 6 个二级页**（Provider 三页 + Model 两页 + Lamda），现已修正。
4. `ChatUi.kt` 标 `shell`：聊天主界面宿主是 `ChatPanel`（View 壳），
   按 `docs/CHATPANEL_CONTRACT.md` 单独治理，不参与页面级判定。

### 为什么用「基线」而不是「零容忍」

存量已有 2 处，若直接要 0，脚本从第一天就是红的，很快会被无视。
所以通过标准是「**不新增**」：存量照常打印（不掩盖），新增或变化则退出码非零。

```bash
python3 tools/page_consistency_audit.py               # 审计（详细）
python3 tools/page_consistency_audit.py --quiet       # 只打印汇总与离群
python3 tools/page_consistency_audit.py --verbose     # 每页判定细节
python3 tools/page_consistency_audit.py --write-baseline   # 修完一批后重新生成基线
```

退出码：`0` = 无新增偏离 · `1` = 有新增偏离 · `2` = 脚本自身错误

### 技术细节

- **注释与字符串先抹平**再解析（保留索引与行号）。Kotlin 块注释**可嵌套**，按深度计数。
- **透明转发跟随**：像 `HomeContent` 这种「一行把活交给 `ComingSoonPage.Social()`」的入口，
  量它自己毫无意义 → 顺着目标最多跟 3 跳再量。
  同名（如全仓多个 `Content`）优先取本文件内的实现。
- `val PAD_H = 16.dp` 这类**本文件常量会被还原成数字**，不会因为用了别名就漏判。

---

## 7. 自测证据

### 自测 A：合规页改坏必须被拦（存量页面）

```bash
# ConversationHistoryPage 根 padding 16 → 24
sed -i 's/padding(16.dp)/padding(24.dp)/' .../ConversationHistoryPage.kt
python3 tools/page_consistency_audit.py
```

```
偏差项变化 1 处（门禁拦截）：
  ~ ui/chat/ConversationHistoryPage.kt:46:Content
       基线 ('P2 手拼根容器（未走 DhKit.Page）', 'P3 手搓顶栏（未走 DhKit.TopBar / TabTopBar）')
       现在 ('P2 …', 'P1 根水平 padding=24（期望 16）', 'P3 …')
结果：FAIL —— 页面一致性出现新增偏离
EXIT=1
```

### 自测 B：**完全合规**的页改坏（新增离群路径）

```bash
# ContactScreen（Tab 页）contentPadding start 16 → 24
sed -i 's/start = 16.dp,/start = 24.dp,/' .../ContactScreen.kt
```

```
新增离群 1 处（门禁拦截）：
  + ui/home/ContactScreen.kt:85:Content
       - P1 页级水平留白=24（期望 16）
结果：FAIL —— 页面一致性出现新增偏离
EXIT=1
```

### 自测 C：恢复后必须通过

```
结果：PASS —— 无新增页面不一致（存量 2 处，见 docs/PAGE_CONSISTENCY.md）
EXIT=0
```

两条路径（**存量页偏差变化** 与 **合规页新增离群**）都已验证，不是只验了其中一条。

---

## 8. 诚实的边界

1. **这是静态审计，不是渲染比对。** 它证明「代码里声明的值一致」，不证明「真机上看起来一致」。
   `DhKit.Page` 内部叠加、`windowInsets`、弹窗比例这类**运行时**问题它看不到。
   弹窗比例（"弹窗比例不协调"）目前**没有**被覆盖 —— 需要 `DhFeedback` / `DhForm.Alert` 这一层
   单独的盒子模型检查，建议另开一项。
2. **只覆盖水平方向与项间距。** 垂直大留白（`pageBottom`）、行高、卡片内边距
   （已被 `ui_audit` 部分覆盖）不在本脚本范围。
3. **`ChatUi` 是盲区（已被承认并标 `shell`）**。它是聊天主界面、用户看得最多的一页，
   但因为宿主是 `ChatPanel`（View 壳），本脚本**不判**它。
   这也是 `ChatPanel` 去 View 化（`docs/CHATPANEL_MIGRATION_PLAN.md`）的另一个理由。
4. **基线机制可被「改基线」绕过。** 它能防手滑，防不了故意 —— 和保护 `ui_audit.sh` 的约束同一性质。