# 无障碍（读屏）静态审计 · A11Y_FINDINGS

> 审计人：`ui-impl`
> 时间：2026-11-08
> 方式：**纯静态扫描源码** —— 不改一行代码、不碰前台（无 `monkey` / `am start` / `input tap` / `cmd uimode` / `settings put`）
> 范围：`app/src/main/java` 全部 `*.kt`（277 个文件）
> 触发：`DEVICE_FINDINGS.md` §1 的搜索框 P2（placeholder 不进语义）暴露了一个面 —— 查了全仓，**同类问题不是 2 处，是 20+ 处**

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
- **影响**：触摸探索模式下读屏只报 5 个「按钮」；点完**也不知道现在第几档**。6dp 高的段落即使有 48dp 外扩，5 段并排后命中区互相重叠，读屏用户基本无法定位目标 —— **这个控件的功能对读屏用户等于不存在**。
- **备注**：同一面板里 `:364` 的「档位名」列表是**有文字的行**（读屏可用），可作为**备选修补方案**：若分段条改造风险高，可直接把分段条 `clearAndSetSemantics {}` 从语义树摘掉，让读屏只走文字列表（视觉保留分段条）。

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
| `ui/chat/ChatUi.kt:325` | 上下文占比圆环 `CtxRingView` | 30dp `Box` + `Canvas` 画弧，**无名字、无数值语义** | `"上下文已用 ${pct}%，查看用量详情"` | **信息 + 操作双丢**，且**已 grep 确认无兜底**：`ChatState.ctxRatio` 全仓只有 `ChatUi.kt:321` 这一处消费，**没有任何文本节点展示该百分比** → 弧长表达的信息对读屏用户**完全不存在** |
| `ui/chat/ChatUi.kt:1212` | 附件预览卡上的「清除」 | `IC_CLOSE` 无名字 | `"移除附件"` | 与卡片文字相邻，容易念成一片 |
| `ui/chat/ChatListScreen.kt:262` | 搜索框内「清空」 | `IC_CLOSE` 无名字 | `"清空搜索"` | 只在输入非空时出现，读屏用户不知道能一键清空 |
| `ui/doll/DollActionPage.kt:186 / 189 / 192` | 模板 / 导入 / 新建分组 | 三个无名字图标钮 | `"使用行为模板"` / `"导入动作"` / `"新建分组"` | 顶栏 actions 区，三连无名 |
| `ui/doll/DollAnimPage.kt:1303 / 1317` | 帧率 − / + | 无名字 | `"降低帧率"` / `"提高帧率"` | 中间夹一个数字文本，读屏念「按钮 12 帧/秒 按钮」，无法判断谁加谁减 |
| `ui/doll/DollHomePage.kt:180 / 182`（定义 `:201`） | 取消 / 确认（设置人偶屏幕位置） | `CircleButton` 无名字 | `"取消，不保存"` / `"确认位置"` | 一对互斥操作**无名且相邻**，误触代价大（写盘） |
| `ui/doll/DollRangePage.kt:164 / 168 / 176`（定义 `:228`） | 取消 / 旋转 / 确认 | 同上 | `"取消"` / `"旋转 90 度"` / `"确认范围"` | 三个无名圆形按钮；「旋转」与「取消」都是圆形，读屏无法区分 |
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
