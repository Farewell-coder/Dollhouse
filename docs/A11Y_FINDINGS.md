# 无障碍（读屏）静态审计 · A11Y_FINDINGS

> 审计人：`ui-impl`
> 时间：2026-11-08
> 方式：**纯静态扫描源码** —— 不改一行代码、不碰前台（无 `monkey` / `am start` / `input tap` / `cmd uimode` / `settings put`）
> 范围：`app/src/main/java` 全部 `*.kt`（审计时 277 个文件；收工时 **278** 个 —— 新增 `ui/compose/AppDialog.kt`）
> 触发：`DEVICE_FINDINGS.md` §1 的搜索框 P2（placeholder 不进语义）暴露了一个面 —— 查了全仓，**同类问题不是 2 处，是 20+ 处**

---

## §00 方法论 · 写「未发现」，不写「没有」（**先读这条**）

静态扫描的结论只能到「**我看到的**」这一层，到不了「**不存在**」这一层。

原因很硬：grep 搜的是**文本形态**，而数据流会改名、会绕道、会由别的文件提供。
本次审计里我自己就撞了两次墙（完整经过见 §6 第 5、6 条）：

- 第一次：把 `io.github.ccch1mneyyy.dsh_app` 的 dump 当成 Dollhouse 的证据 —— **同名不同 App**；
- 第二次：断言 `CtxRingView` 「没有任何文本节点展示百分比」，而它环心就有一个 `Text`。
  漏掉的原因是我 grep 的是 `ChatState.ctxRatio`，那个 `Text` 用的却是**局部变量 `ratio`** ——
  **改名后的数据流 grep 不到**。

所以本报告的每条结论都按这个口径写：

| 不要写 | 要写 |
|---|---|
| 「这里没有名字」 | 「**未发现**名字」 |
| 「全仓只有 N 处」 | 「**本次扫描到** N 处（判据见 §0）」 |
| 「已经改完了」 | 「代码已改，**真机尚未听验**」 |

这条不是文字游戏：区别在于**下一个人是否会把它当成已排空的地板继续往上盖**。
写成「没有」，后续改动就会假设这块是干净的；写成「未发现」，才会有人去复核判据。

---

## §0 判定口径（先定死，避免下一个人重复争论）

**什么算"有名字"**

| 元素 | 判定 |
|---|---|
| 可点 / 承载信息，且**内部没有文字**（纯图标、纯图形、纯色块、图片） | **必须有 `contentDescription`**，否则读屏只念「按钮」 |
| 可点，但**内部有 Text**（行 + 标题 + 说明） | **已有名字**（读屏念文字）。本报告不列为问题 |
| 纯装饰（行首点缀、分割线、背景图、图标旁的说明文字已存在） | **显式 `null` 是正确做法**。强行给非空值 = 让读屏念废话 |

**两条已落地的硬知识**（见 `DEVICE_FINDINGS.md` §1，不重复论证）
1. `uiautomator dump` 读的是 **a11y bounds（`touchBoundsInRoot`，按 48dp 外扩）**，不是布局边界 —— 所以**不能**用 dump 反推"某个图标有没有名字之外"的几何结论。
2. Compose 1.10.2 **没有 hint 通道**（`setHintText` 不存在），`contentDescription` 是唯一能被 dump / 读屏读到的名字入口。

**本报告的能力边界（重要）**
- 只覆盖「**有没有名字**」这一层，**不覆盖朗读顺序、不覆盖是否念废话**。
- `docs/DEVICE_QA.md:225` 已有同样声明；朗读顺序**必须逐页开 TalkBack 听**，静态扫描替代不了。
- 我没有真机朗读数据，所以下文每条都写「建议文案」而不是「已修」。

**全仓基线数字**

| 项 | 数量 | 说明 |
|---|---|---|
| `.pressable(...)` 调用点 | ~60 | 其中**纯图标/无文字**的 19 处（本报告主体） |
| `DhKit.IconButton(` 调用点 | 51 | **22 处**没传 `label` / `tooltip` → 无名 |
| `contentDescription = <非空>` | **3 处** | `ChatListScreen.kt:217`（用户后加的"打开菜单"）、`Components.kt:809`（`DhIconButton` 的必需参数 `name`）、`DhKit.kt:1241` 的 KDoc 说明 |
| `contentDescription = null` | 27 处 | 绝大多数**正确**（见 §5），**不要一刀切改** |

> 上表是**审计当时**的基线（`IconButton` 22 处无名、`null` 27 处）。
> 收工后的现状见 **§9.0 收口校验**：`IconButton` **51/51** 有名字、P0 三条已闭合、六道门禁全绿。
> 表中 `null` 的行数在收工时复核**仍是 27 处**（另有 2 处在注释 / KDoc 里，不是代码）——
> 口径不变、也未新增。复核命令：全仓 grep `contentDescription = null` 后按行首是否为注释分类统计。

---

## §1 系统性根因（先看这条，否则会逐个页面瞎补）

### [P1·系统性] `ui/compose/DhKit.kt:1290` `IconButton` 缺「只要无障碍名」的通道

- **现状**：`IconButton` 只有两个能产生名字的参数：
  - `label` → **会渲染可见文字**（`hasLabel` 分支加 `Text` + 横向 padding），窄排布放不下；
  - `tooltip` → 会被 `HintTooltip` 包裹（**长按弹提示**，有交互副作用）。
- **结果**：布局紧凑处的图标按钮只能**留空**，于是 22 处无名。这不是"忘了写"，是**没有可用的写法**。
- **建议**（二选一，我不改代码，等你定口径）：
  1. `IconButton` 增 `a11yName: String? = null`，优先级 `a11yName > label > tooltip`，**只挂 `Modifier.semantics`、不渲染任何东西**；
  2. 或统一改用 `ui/kit/Components.kt:796` 的 **`DhIconButton(iconRes, name: String, ...)`** —— 它把 `name` 做成**必需参数**，漏写直接编译不过。这个设计比 ① 更能防止回归。
- **影响**：不修这个根因，就算这轮 22 处都补上，**下一轮新增的按钮还会漏**。

### [P1·系统性] `ui/kit/Components.kt:371` `DhRow` / `DhKit.ValueRow` 转发后的行内图标无名字

- **现状**：`DhRow` 的 `IconSeat` 是纯图形（32dp 圆底 + 16dp 图标），`DhKit.Icon` 默认 `contentDescription = null`。行有 `title` 文字 → 读屏念标题，**可用**。
- **判定**：**不是问题**（文字已承载语义）。列在这里是为了防止下一个人"看到图标就给 desc"——那会让每行念两遍。
- 唯一例外见 §3：`DhRow` 的**危险项/状态图标**若表达"已启用/已删除"之类**文字里没有**的信息，才需要 desc。当前扫描未发现这种用法。

---

## §2 P0（读屏用户**无法完成**或**无法区分**，建议本轮单独批）

### [P0] `ui/chat/ChatPanels.kt:352` · 记忆/上下文「档位分段条」

- **现状**：`names.forEachIndexed { i, _ -> Box(...).height(6.dp).clip(...).background(...).pressable { level = i } }`
  —— 5 个 **6dp 高、无文字**的色块，无 `contentDescription`、无选中态语义。
- **判定**：**缺名字 + 缺状态**（双重缺陷）。
- **建议文案**：`"档位 ${i + 1}，共 ${names.size} 档"`；并加选中态：
  - 首选 `Modifier.selectable(selected = on, role = Role.Tab) { level = i }` + 外层 `Modifier.selectableGroup()`；
  - 退一步也要 `Modifier.semantics { contentDescription = ...; selected = on }`。
- **影响**：触摸探索模式下读屏只报 5 个「按钮」；点完**也不知道现在第几档**。
  **命中区实测口径（2026-11-08 更正）**：原先这里写「即使有 48dp 外扩，5 段并排后命中区互相重叠」——**不成立**。
  链是 `.height(6.dp)` 在**外**、`pressable` 的 `minTouchTarget` 在**内**，
  内层的 48dp 下限会被外层约束压成 6dp（`sizeIn` 对入参约束做 coerce，maxHeight=6 时 minHeight 也变 6）。
  所以真实情况更糟也更简单：**每段命中区只有 6dp 高**，探索触摸基本点不中。
  —— **这个控件的功能对读屏用户等于不存在**（结论不变，论据已更正；同一结论见 §9 的三条「有意为之」第 1 条）。
- **备注**：同一面板里 `:364` 的「档位名」列表是**有文字的行**（读屏可用），可作为**备选修补方案**：若分段条改造风险高，可直接把分段条 `clearAndSetSemantics {}` 从语义树摘掉，让读屏只走文字列表（视觉保留分段条）。
- **已落地（2026-11-08）**：采用上面这条备选方案 —— 分段条 `clearAndSetSemantics { }` 摘出语义树，
  同时给有文字的档位行补 `Modifier.semantics { selected = i == level }`（否则会丢「当前第几档」）。
  视觉与触摸一字未改。

### [P0] `ui/doll/DollAnimPage.kt:553 / 558 / 563 / 568` · 帧偏移「四方向微调」按钮

- **现状**：4 个 `IconButton`，图标分别是 `IC_CHEVRON_LEFT / UP / DOWN / RIGHT`，**都没传 label/tooltip**。
- **判定**：**缺**。4 个形状相同的无名按钮横向并排。
- **建议文案**：`"向左微调"` / `"向上微调"` / `"向下微调"` / `"向右微调"`（带单位更佳：`"向左微调 1 像素"`，取决于 `FINE_STEP` 的实际语义）。
- **影响**：读屏用户听到 4 个「按钮」，**无法区分**，只能瞎试；而这是该页调整人偶坐标的**唯一精细入口**（旁边只有「居中」）。
- **注意**：这排 4 键间距仅 4dp（`Spacer(Modifier.width(4.dp))`），**不能**用 `label`（会渲染可见文字直接挤爆行宽）—— 必须走 §1 的"只挂语义"通道。

### [P0] `ui/doll/DollListScreen.kt:569 / 581 / 590` · 人偶卡片底部三枚图标按钮

- **现状**：
  - `:569` `IC_SQUARE`/`IC_PLAY`（按 `doll.enabled` 切换）→ 启停人偶
  - `:581` `IC_EDIT` → 编辑
  - `:590` `IC_MORE` → 更多
  全部无名字。三处上方各有一条注释写着「**文字语义由卡片整体可点区域承载**」。
- **判定**：**缺**，且**注释的判断是错的** —— 卡片整体的可访问名来自人偶名文本（"默认配置"），念出来是"默认配置"，**不是**"停用/编辑/更多"。这条注释会误导后来者继续留空。
- **建议文案**：`"停用该人偶"`（`enabled=true` 时）/ `"启用该人偶"`（`enabled=false` 时）、`"编辑该人偶"`、`"更多人偶操作"`。
  - 强调：启停的文案**必须随状态变化**，否则读屏用户点完不知道当前是启用还是停用。
- **影响**：一张卡片里 3 个「按钮」+ 卡片本身 1 个可点区，共 4 个焦点、3 个无名；**「启用/停用」已 grep 确认是唯一入口**：`DollStore.toggleEnabled` 的 UI 侧调用点只有 `DollListScreen.kt:574` 这一处（`ImportAssistantPage.kt:750` 的 `setActive` 是导入时激活，不是启停开关）→ 读屏用户**无法完成启停人偶**这件事。
- **备注**：三枚按钮处在等分窄槽（列数 3 时每槽约 36dp），同样**不能**用 `label`。

---

## §3 P1（能做但只能靠猜、或信息丢失）

| 位置 | 元素 | 现状 | 建议文案 | 影响 |
|---|---|---|---|---|
| `ui/compose/DhKit.kt:612` ★最高频 | 二级页顶栏返回键 | `IconButton(IC_ARROW_LEFT, ...)` 无名字 | `"返回"` | **全 App 每个二级页都有**。读屏念「按钮」，用户不知道是返回还是别的功能 |
| `ui/chat/ChatUi.kt:212` | 聊天页返回 | 同上 | `"返回会话列表"` | 同上；聊天页是最高频页面 |
| `ui/chat/ChatUi.kt:305` | 右上「更多」(进对话历史) | 无名字 | `"查看对话历史"` | 与返回键并排，两个无名按钮 |
| `ui/chat/ChatUi.kt:325`（现 `:369`） | 上下文占比圆环 `CtxRingView` | 30dp `Box` + `Canvas` 画弧；**环心其实有一个 `Text` 显示纯数字**（如「32」），但**操作无名字、数字无单位** → **已修** | 落地：`"上下文已用 32%，查看用量详情"`（`mergeDescendants` 把环心数字并进来，避免念两遍） | **操作丢失**：圆环可点（进用量页）但读屏念「按钮」；环心的「32」单独念出来既没单位也没含义。~~原写"完全没有文本节点"~~ → **是我错了**，见 §6 第 6 条 |
| `ui/chat/ChatUi.kt:1212` | 附件预览卡上的「清除」 | `IC_CLOSE` 无名字 | `"移除附件"` | 与卡片文字相邻，容易念成一片 |
| `ui/chat/ChatListScreen.kt:262` | 搜索框内「清空」 | `IC_CLOSE` 无名字 | `"清空搜索"` | 只在输入非空时出现，读屏用户不知道能一键清空 |
| `ui/doll/DollActionPage.kt:186 / 189 / 192` | 模板 / 导入 / 新建分组 | 三个无名字图标钮 | `"使用行为模板"` / `"导入动作"` / `"新建分组"` | 顶栏 actions 区，三连无名 |
| `ui/doll/DollAnimPage.kt:1303 / 1317` | 帧率 − / + | 无名字 | `"降低帧率"` / `"提高帧率"` | 中间夹一个数字文本，读屏念「按钮 12 帧/秒 按钮」，无法判断谁加谁减 |
| `ui/doll/DollHomePage.kt:182 / 184`（定义 `:208`） | 取消 / 确认（设置人偶屏幕位置） | `CircleButton` 无名字 → **已修**（`name` 必填参数） | 实际落地：`"取消，不保存位置"` / `"确认人偶位置"` | 一对互斥操作**无名且相邻**，误触代价大（写盘） |
| `ui/doll/DollRangePage.kt:166 / 170 / 178`（定义 `:236`） | 取消 / **重置** / 确认 | 同上 → **已修** | 实际落地：`"取消，不保存"` / `"重置为整屏"` / `"确认范围"` | 三个无名圆形按钮 |
| `ui/doll/DollListScreen.kt:964` | 72dp 封面选择瓦片 | `Box.size(72.dp).pressable` + `Image(desc=null)` / `IC_IMAGE` | `"选择封面图"` | 瓦片内既无文字也无 desc，整块"哑"掉 |
| `ui/compose/DhSwipe.kt:90` | 左滑露出的「删除」 | 红底 `Box` + `IC_TRASH`，无名字 | `"删除"` | 滑动动作读屏难触发；即使滑开也念「按钮」。**验证点**：是否有长按 / 菜单内的替代删除路径（聊天消息有长按菜单，人偶动作行有长按 → 有替代则维持 P1） |
| `ui/settings/MemPage.kt:306` 与 `:658` | 两处「关闭」(✕) | 无名字 | `"关闭"` / `"关闭详情"` | 都紧跟一段文字，容易连读成一坨 |
| `ui/settings/ModelListPage.kt:227` | 「添加模型」 | 36dp `Box` + `IC_PLUS` | `"添加模型"` | 与「已添加」的勾图标并排，一个有名（勾无文字）一个无名 |
| `ui/provider/ProviderListPage.kt:96 / 110 / 118` | 导出 / 导入 / 新建供应商 | 三个 40dp 无名字图标 | `"导出配置"` / `"导入配置文件"` / `"新建供应商"` | 顶栏 actions 三连无名；导入是**配置迁移唯一入口** |
| `ui/provider/ProviderListPage.kt:338` | 供应商卡「更多」 | 36dp `Box` + `IC_MORE` | `"更多操作"` | 卡片内还有其它可点区 |
| `ai/TokenStat.kt:450 / 465` | 上一周期 / 下一周期 | 无名字 | `"上一个周期"` / `"下一个周期"` | 中间夹周期文字；`:471` 的占位 `Box` 也画了右箭头但不可点（**别给它加 desc**） |

**P1 共 22 个元素，但只有 14 种修法**（同一种按钮在很多页重复：返回键 2 处、圆形确认钮 5 处）。

---

## §4 P2（不影响完成任务，但读屏体验差）

| 位置 | 元素 | 现状 | 建议 |
|---|---|---|---|
| `ai/TokenStat.kt:450/465` 之外的周期文字 | — | — | — |
| `ui/settings/MemPage.kt:1182 / 1194` | 「改」「删」单字可点文本 | 有文字但仅 1 字 | 保持文字，补 `contentDescription = "编辑这条记忆"` / `"删除这条记忆"`（单字在朗读里没有上下文） |
| `ui/chat/ChatUi.kt:889` | 用户发出的**图片消息** | `Image(contentDescription = null)` | 至少 `"图片消息"`。图片内容本身无法描述（可考虑留待"AI 自动描述"特性），但**无名等于这条消息对读屏用户不存在** |
| `ui/widget/AexImportSheet.kt:814` | 错误提示前的警告图标 | `desc = null`，同块有文字 | 可保持 null（文字已表达）；若要更贴近原生，给 `"警告"` |
| `ui/chat/ChatUi.kt:250` | 长按标题唤出搜索框 | 有文字（标题），长按动作**无任何语义提示** | 长按类交互读屏基本发现不了。建议在该页提供一个显式「搜索」入口（视觉可隐藏，语义保留） |
| `ui/settings/AboutPage.kt:340 / 413` | 版本药丸 / 「不再提醒此版本」 | 都有文字 | **无需改**。此处列出只因它是 `pressable` 调用点，避免被误判（AboutPage 由 Lead 维护，我不动） |

---

## §5 明确「不要改」：显式 `null` 正确的 27 处

这 27 处**绝大多数是对的做法**。下一个人若按"所有 Image 都要有 desc"一刀切，会造成**每行念两遍**（名字一遍 + 图片一遍），是负优化。我逐个核对过的类型：

| 类型 | 代表位置 | 为什么 null 正确 |
|---|---|---|
| 头像 / 封面，且**同一可点区内有文字** | `ChatListScreen.kt:226`（父 Box 已有"打开菜单"）、`ProfileDrawer.kt:604`、`ContactScreen.kt:288`、`SettingsScreen.kt:179`、`MyProfilePage.kt:178` | 文字或父语义已命名，图片再报一次是冗余 |
| 人偶卡片封面（卡片本身有名字/标题） | `DollListScreen.kt:538`、`DollListScreen.kt:971`※、`DollHomePage.kt:124`、`DollLookTab.kt:118`、`DollGroupPage.kt:338`、`DollActionPage.kt:776/890` | 同上（※`:971` 是**例外**，已升 P1 列入 §3） |
| 纯装饰图标（行内点缀、图标旁已有说明文字） | `AexImportSheet.kt:771`、`DhForm.kt:747/756`、`ImportAssistantKit.kt:364`、`DollImportWizardPage.kt:309`、`FriendProfilePage.kt:358` | 描边/示意，非信息载体 |
| 自绘预览 / 桌宠精灵图（背景渲染） | `DollAnimPage.kt:443/1448`、`DollHomeOverlay.kt:179`、`pet/` 下精灵绘制 | 纯视觉预览，没有等价文字信息可给 |
| 消息气泡内图片 | `ChatUi.kt:271 / 630 / 1193` | 与 `:889` 同族 —— `:889` 那张**没有任何文字**所以升 P2；其余在带文字的卡片里 |

**判定口诀**：这个图标/图片的**信息**是否已被同级的文字或父级语义表达过？是 → `null`；否 → 必须给名字。

---

## §6 顺手纠错（不纠的话会按错信息继续改）

1. **`DollListScreen.kt:566 / 578 / 587` 三条注释**：「文字语义由卡片整体可点区域承载」—— **不成立**。卡片整体念的是人偶名，不是按钮功能。这三条注释是当前最危险的地方：它让"漏写"看起来像是"设计如此"。
2. **`DollActionPage.kt:184` 注释**：「语义由图标 + 下方说明承载」—— 同理不成立：`DhKit.Icon` 默认 `contentDescription = null`，**图标本身不产生任何语义**，"下方说明"是另一个节点、不在按钮的语义里。
3. **`DEVICE_FINDINGS.md:130`** 已过期：那里的「头像抽屉入口完全无名」在源码里**已修**（`ChatListScreen.kt:216-218` 已有 `contentDescription = "打开菜单"`），只是装机 APK（07:40 编）没带。
4. `DhKit.kt:1241` 的 KDoc 已经把 `Icon.contentDescription` 的口径写清楚了（"装饰传 null 是正确做法"）—— 全仓**应当沿用这份口径**，不要再造第二套。
5. **读 dump 之前先看 `package=`**（我自己踩了一次）：`/tmp/u3.xml`、`u5.xml` 里那些 `content-desc="上下文已用 32%，查看用量"`、`"停止生成"`、`"分享"` 属于 **`io.github.ccch1mneyyy.dsh_app`**（DSH App 自己的界面），**不是 Dollhouse**。我一开始把它当作聊天页的证据，差点给一个不存在的"已有文本节点"降级结论。同目录里 `w.xml`/`u4.xml` 的 `package="com.dollhouse.app"` 才是本工程的。
   → 与 §1 的 −6dp 同一个教训：**dump 的每个数字/节点都要先确认"它属于谁"**。

6. **我自己第二次错在同一件事上**：`ChatUi.kt` 的上下文圆环（`CtxRingView`）我在 §3 写「**没有任何文本节点展示该百分比**」——**错的**。它环心就有一个 `Text`：`text = "${(ratio * 100f).roundToInt()}"`。
   我之所以漏掉：grep 的是 `ChatState.ctxRatio`，而那个 `Text` 用的是**局部变量 `ratio`**（`val ratio = ChatState.ctxRatio.coerceIn(...)`）——**改名后的数据流 grep 不到**。
   这正是"静态扫描的边界"：**只能证明"我看到的"，不能证明"不存在"**。结论要写"未发现"，不该写"没有"。
   修正后的真实缺陷是两件（都已修）：① 可点区域无名；② 环心「32」无单位无含义。

---

## §7 未覆盖 / 待确认（不用猜的，明确列出）

1. **朗读顺序**：全部未验证。需真机逐页开 TalkBack 听（`DEVICE_QA.md:225` 已有同样限制）。
2. **`CtxRingView`（`ChatUi.kt:325`）**：~~是否已有等价文本节点~~ → **已确认没有**（`ChatState.ctxRatio` 全仓唯一消费点就是它）。无需再验。
3. **「启用/停用」入口唯一性**：~~未查~~ → **已确认唯一**（`DollStore.toggleEnabled` UI 侧仅 `DollListScreen.kt:574`）。故 `DollListScreen:569` 维持 **P0**。
4. **`DhSwipe` 是否有替代删除路径**：聊天消息有长按菜单，人偶动作行有长按删除（`DollActionPage.kt:874` `pressable(onOpen, onDelete)`）→ 按"有替代"给 P1。若要更稳，需真机确认长按菜单里确实有删除项。
5. **未扫描**：`res/` 下的 XML 布局（若还有 View 侧页面，`android:contentDescription` 需另查）—— 本次只扫 `*.kt`。
6. **动态内容**：列表里由数据决定的可读文本（人偶名、会话预览）不在本审计范围。

---

## §8 建议的修复口径（等你批，我不动手）

1. **先修根因**（§1）：给 `IconButton` 加 `a11yName`（或全仓迁 `DhIconButton`）。**不修根因，补完这 22 处还会漏。**
2. **P0 三条单独批**，因为其中两条涉及布局（窄槽 / 4 键并排），**不能**用 `label`，必须先有 §1 的通道。
3. **P1 按"元素类型"批量修**，不按页面修：同一种"返回键"只有一处改动点（`DhKit.kt:612`），同一种"圆形确认钮"只有两处定义（`DollHomePage:201` / `DollRangePage:228`）—— 一次改定义，5 个调用点全好。
4. **P2 最后做**，并且 §5 的清单**保持不动**。
5. 每条改完必须**真机听一遍**再关；`contentDescription` 写错（比如给装饰图加 desc）比没有更糟。

---

## §9 施工进度（按「元素类型」归并 —— 这是实际修法）

> 原则：**改定义点，不改调用点**。同一种按钮有 1~2 处定义时，一次覆盖多个页面。
> **状态：全部闭合。** 收口校验见下方「§9.0 收口校验（可复现）」。
> 注：行号会随他人改动漂移，本表的行号取自 2026-11-08 收工时的 `HEAD`。

### §9.0 收口校验（可复现）

**1) `IconButton` 51/51 有名字**

判定方法：对每个 `IconButton(` 调用点，用**括号计数**取出完整参数区
（不能用正则非贪婪 —— `.then(if (hasLabel) ... Modifier.size(...))` 这种嵌套括号会把参数区截断），
再检查参数区里是否有 `label =` / `tooltip =` / `a11yName =` 三者之一。

| 项 | 数量 |
|---|---|
| `DhKit.IconButton` 调用点 | **51** |
| 其中有名字 | **51** |
| 无名 | **0** |
| 审计当时的无名数（基线） | 22 |

> **踩坑记录（下次复扫别再踩）**：粗暴 grep `IconButton\s*\(` 会多出 **1** 个 —— 那是
> `DhKit.kt` 里**函数定义** `fun IconButton(` 本身，不是调用点。按「剔除 `fun ` 前缀」后才是 51；
> 机器扫出来 52/51 时先看这一条，别急着去找那根不存在的"漏网按钮"。

**2) P0 三条的落地形态**

| 位置 | 落地 |
|---|---|
| `ChatPanels` 档位分段条 | `clearAndSetSemantics { }` 摘出语义树（**不命名**），档位行补 `selected` 状态 |
| `DollAnimPage` 四方向微调 | `a11yName`：左移 / 上移 / 下移 / 右移 |
| `DollListScreen` 卡片底部三枚 | `a11yName`：`停用 <名字>` / `启用 <名字>`（**随状态变**）、`编辑 <名字>`、`更多操作 <名字>` |

**3) 门禁现状（2026-11-08 收工后实测）**

| 门禁 | 结果 |
|---|---|
| `kotlin_precheck.py` | 未发现问题（278 文件） |
| `comment_balance.py` | 全部平衡（330 文件） |
| `symbol_refs.py` | 0 悬空引用（含新增的 `kotlin.*` 默认导入白名单：`ReplaceWith` 等不再误报） |
| `align_audit.py --quiet` | 0 处 |
| `radius_audit.py --quiet` | 0 处 |
| `ui_audit.sh` | 44/44 PASS |

**4) 两道 UI 门禁的反向验证（证明它们不是"永远绿"）**

- `align_audit --self-test`：注入缺 `contentAlignment` 的 `Box` → 报；补上 → 过；
  另有一条对照用例现场演示「非贪婪正则在 `.pointerInput(Unit) { ... }` 处截断」的旧事故。
- `radius_audit --self-test`：注入 `size(24.dp)` + 圆角 16 → 报；改 8 → 过；
  **新增 drawBehind 用例**：`size(24.dp)` + `drawBehind { drawRoundRect(cornerRadius = CornerRadius(16.dp)) }` → 报，改 8 → 过。
- `symbol_refs`：注入 `replaceWith = ReplaceWith("...")` → **不报**（kotlin 默认导入）；
  注入一个全仓不存在的 `FrobnicatorXyz` → **报**（证明白名单没有把真问题一起吞掉）；清理 → 过。

### 已修清单

| 元素类型 | 定义点 / 位置 | 覆盖 | 落地文案 | 谁做的 |
|---|---|---|---|---|
| **二级页返回键** ★ | `DhKit.kt:705`（`TopBar` 内） | **全 App 每个二级页** | `"返回"` | ui-impl |
| 圆形确认钮·初始位置页 | `DollHomePage.kt:208` 定义（`name` **必填**） | 2 调用 | `"取消，不保存位置"` / `"确认人偶位置"` | ui-impl |
| 圆形确认钮·范围页 | `DollRangePage.kt:236` 定义（`name` **必填**） | 3 调用 | `"取消，不保存"` / `"重置为整屏"` / `"确认范围"` | ui-impl |
| 挑帧缩略图 | `DollListScreen.kt` `CoverThumb`（`name` **必填**） | 最多 120 瓦片 | `"第 N 帧，设为封面"` | ui-impl |
| 顶栏图标三连 | `ProviderListPage.kt` | 3 | `"导出配置"` / `"导入配置文件"` / `"新建供应商"` | ui-impl |
| 卡片「更多」 | `ProviderListPage.kt` | 1 | `"更多供应商操作"` | ui-impl |
| 列表行「添加」 | `ModelListPage.kt` | 1 | `"添加该模型"` | ui-impl |
| 左滑删除区 | `DhSwipe.kt` 删除层（`SwipeToDelete`） | **1 个调用点**（`ProviderModelsPage` 模型行）※ | `"删除此项"` | ui-impl |
| 聊天页返回 | `ChatUi.kt:234` | 1 | `"返回会话列表"` | ui-impl |
| 聊天页「更多」 | `ChatUi.kt:343` | 1 | `"查看对话历史"` | ui-impl |
| 移除附件 | `ChatUi.kt:1264` | 1 | `"移除附件"` | ui-impl |
| 上下文占比圆环 | `ChatUi.kt:369` | 1 | `"上下文已用 N%，查看用量详情"` | ui-impl |
| 清空搜索 | `ChatListScreen.kt` | 1 | `"清空搜索"` | ui-impl |
| 关闭（记忆库搜索） | `MemPage.kt:376` | 1 | `"关闭记忆库搜索"` | ui-impl |
| 关闭（记忆详情） | `MemPage.kt:750` | 1 | `"关闭记忆详情"` | ui-impl |
| 帧率 − / + | `DollAnimPage.kt` | 2 | `"降低帧率"` / `"提高帧率"` | ui-impl |
| 周期切换 | `TokenStat.kt` | 2 | `"上一个周期"` / `"下一个周期"` | ui-impl |
| 方向微调（原 P0） | `DollAnimPage.kt:557/562/567/572` | 4 | 左移 / 上移 / 下移 / 右移 | align-audit |
| 卡片底部三连（原 P0） | `DollListScreen.kt:582/596/608` | 3 | `"停用 <名字>"` / `"启用 <名字>"`（随状态）、`"编辑 <名字>"`、`"更多操作 <名字>"` | align-audit |
| 页头三连 | `DollActionPage.kt:189/192/195` | 3 | 行为模板 / 导入资源 / 新建动作 | align-audit |
| 搜索框可访问名 | `ChatListScreen.kt:269` | 1 | `"搜索会话或人偶名"` | align-audit |
| 档位分段条（原 P0） | `ChatPanels.kt` | 1 | —（不命名）`clearAndSetSemantics` **摘出语义树**，改由有文字的档位行承担 + 补 `selected` | 他人 |

### 三处有意为之、不要"补全"的地方

1. **档位分段条**：摘出语义树比"给 6dp 色块起名"更对 —— `.height(6.dp)` 是外层强制约束，会把 `pressable` 内建的 48dp `minTouchTarget` **压死在 6dp**，起了名也点不中。
2. **`TokenStat` 右侧不可退时的占位 `Box`**：它画了同一个箭头但**不可点**，必须保持无名字（否则读屏会念一个点不动的「按钮」）。
3. **`CoverThumb` 里的 `Image`** 与 **`DhRow` 行首 `IconSeat`**：继续 `null`（名字已在可点父层 / 文字已承载语义），见 §5。

### 遗留（本批未做，非代码问题）

- **朗读顺序、是否念废话**：静态改动无法证明，**必须真机开 TalkBack 逐页听**（`DEVICE_QA.md:225` 同口径）。
- ~~**滑动手势的可达性**~~ → **已闭合（仅有的 1 个调用点）**，见 §10。

---

## §10 手势可达性（补完·`SwipeToDelete` 的等效入口）

> 背景：名字补了但**动作做不到**等于没修。本节记普查口径与修法。

### 普查：全仓"删除靠手势"的地方只有 4 处，其中 3 处本来就可达

| 位置 | 删的是什么 | 手势 | 长按等价 |
|---|---|---|---|
| `ProviderModelsPage` 模型行（`SwipeToDelete`，**唯一调用点**） | 供应商下的某个模型 | 左滑露出垃圾桶 → 点它（**直接删**） | ✗ → **本次已补** |
| `ChatUi` 消息气泡 | 单条消息 | **长按** → 菜单「删除」 | ✓ 本身就是长按 |
| `DollActionPage.kt:877` 动作行 | 单条动作 | **长按** → 删除确认（`pressable(onOpen, onDelete)`） | ✓ 本身就是长按 |
| `ChatListScreen` 会话行 | —（行本身不删除） | 仅点击进聊天 | 不涉及（删人偶/会话在 `FriendProfilePage`） |

**口径纠正**：我上一版写「全 App 所有 `DhSwipeRow`」是**错的** —— ① 组件实名 `SwipeToDelete`；② 全仓**只有 1 个调用点**。另外 `ProviderModelsPage` 与 `DhSwipe.kt` 里「View 版 `SwipeRow`」的注释也已过期：该 View 构件已不存在，只在注释里被提到。**"全 App 所有"这种话以后必须先用 `grep -c` 数一遍再说。**

### 修法（两层，都不重复实现删除）

1. **组件层** `SwipeToDelete` 增 `onLongPress: (() -> Unit)? = null`：
   - 只有非空时才挂 `pressable(onClick, onLongClick)` 双参重载，**其余调用点逐字节不变**；
   - 组件**自己不实现删除**，只把长按事件交出去（避免第二份删除逻辑）。
   - 回报：`combinedClickable` 会把它暴露成无障碍"长按"动作，读屏用户可从动作菜单触发。
2. **页面层** `ProviderModelsPage`：
   - 把「调 `ModelStore.deleteModel` + 触发重读」收成**唯一一份** `deleteModel` lambda，左滑与长按都调它；
   - 左滑维持 r13 定案（两步、**不弹**确认）；长按是**一步触达**，所以先 `DhForm.Alert` 确认再调同一个 lambda。
   - 顺带修正两处撒谎的注释（"二次确认"实际不弹、`View 版 SwipeRow` 已不存在）。

### 仍存在的边界
- 长按删除**只补在模型行**。若日后 `SwipeToDelete` 新增调用点，**必须同时传 `onLongPress`**（目前靠 KDoc 约定，没有编译期强制）。
- 语音/开关控制类辅助技术的"点击"能力也依赖语义动作，本次未单独验证。
