# Dollhouse · 精致度评审

> **用户的判断是对的**：*「感觉就是改了排布，但是并没有变得更精致」*。
>
> 这句话点出了本轮工作的性质差异：
> **令牌化 / 组件转发 / 去 View 解决的是「一致」，不是「精致」。**
> 一个处处 8dp、处处 12 圆角、处处合规的界面，可以非常"一致性满分"，
> 同时看起来**像一张设计规范的示意图，而不像一个打磨过的应用**。
>
> 本文不问"令牌用对了吗"，只问：**细节够不够讲究**。
> 每条都给 `file:line` + 具体改成什么值。快照 2026-11-07。

---

## 核心诊断：三个"消失的维度"

| 维度 | 现状数量 | 正常打磨过的应用 | 差距 |
|---|---|---|---|
| **spring 动画** | **6 处**（且全在 `DhAnim.kt` 里定义，业务代码 0 处直接用） | 大多数交互 | 手感是"机械匀速" |
| **阴影** | **4 处**（3 处实际调用，全在两个弹层） | 每个浮起层 | 层次是"纯平色块" |
| **间距档位** | `spacedBy(8.dp)` **13 次**，几乎只用一档 | 3~4 档交替形成韵律 | 疏密是"处处 8dp" |

这三个数字合在一起，就是"不精致"的**结构性原因**：
- 没有 spring → 动效像 PowerPoint
- 没有阴影 → 界面像一张平面图
- 没有间距韵律 → 版面像表格

---

## 一、动效手感：tween 是机械插值，spring 才有呼吸

### 现状

```bash
grep -rn "spring(" app/src/main/java --include=*.kt | grep -vc "//"   # 6
grep -rn "tween("  app/src/main/java --include=*.kt | grep -vc "//"   # 19
```

**6 处 spring 全部在 `DhAnim.kt` 里"定义"**，没有一处在业务代码中直接使用：

```bash
grep -rn "spring(" app/src/main/java --include=*.kt | grep -v "//"
# ui/compose/DhAnim.kt:48  SW_SPRING     (定义)
# ui/compose/DhAnim.kt:52  COLOR_SPRING  (定义)
# ui/compose/DhAnim.kt:60  BOUNCY        (定义)
# ui/compose/DhAnim.kt:64  TAP_SCALE     (定义)
# ui/compose/DhAnim.kt:96  SWIPE_SETTLE  (定义)
# ui/compose/DhSwipe.kt:39               (注释)
```

### 为什么它让人觉得不精致

`tween` 是**时间驱动的匀速插值**：给定 160ms，它就用 160ms 均匀走完曲线。
`spring` 是**物理驱动**：质量、阻尼、刚度决定它"弹"过去，中途会因惯性轻微过冲或缓停。

实际体感差异：
- **tween**：像电梯 —— 匀速启动、匀速停，机械感
- **spring**：像抽屉滑轨 —— 推开时有一点惯性、到位置轻轻一顿

用户说的"丝滑"，在 Android 上 90% 来自 spring 的**微过冲**。而我们有 19 处 tween、0 处业务 spring。

### 哪些交互该用 spring 却用了 tween（逐处）

| # | 位置 | 现在 | 应该改成 | 理由 |
|---|---|---|---|---|
| **1** | `ui/compose/DhAnim.kt:56` `PRESS_DOWN` | `tween(90)` | **保持 tween**（按下要跟手，不需要惯性） | ✅ 正确用法 |
| **2** | `ui/compose/DhAnim.kt:77/82` `CARD_EXPAND/COLLAPSE` | `tween(220/180)` | `spring(dampingRatio=0.85f, stiffness=380f)` | **展开收起是"内容生长"**，tween 像"被拉伸"，spring 像"弹开"。这是最该换的一处 |
| **3** | `ui/compose/DhAnim.kt:87/92` `CARD_FADE_IN/OUT` | `tween(220/180)` | **保持 tween**（透明度用 spring 会闪） | ✅ 正确用法 |
| **4** | `ui/nav/NavHost.kt:222/231` 页面转场 | `tween(220/180)` | **保持 tween**（转场要可预测，spring 会让"到达时间"不确定） | ✅ 正确用法 |
| **5** | `ui/compose/DhForm.kt:230/235/381/386` 输入框聚焦 | `tween(160)` | **改成 `spring(dampingRatio=1f, stiffness=900f)`** | 底色/描边是**材质变化**，弹簧收尾更"润"。4 处 |
| **6** | `ui/kit/Components.kt:247/252` `DhField` 聚焦 | `tween(160)` | 同上 | 2 处 |
| **7** | `ui/chat/ChatUi.kt:1290/1297` 聊天输入框 | `tween(160)` | 同上 | 2 处 |
| **8** | `ui/settings/DeveloperOptionsPage.kt:270-272` 箭头旋转 | `tween(DhDesign.Motion.micro)` | `spring(dampingRatio=0.7f, stiffness=600f)` | **旋转最吃弹簧**，tween 转得像齿轮 |
| **9** | `ui/compose/DhAnim.kt:106` `FADE` | `tween(160)` | **保持 tween** | ✅ 正确用法 |

**小结**：19 处 tween 里，**9 处该换 spring**（折叠 2 + 输入框聚焦 6 + 箭头旋转 1），
其余 10 处（按压/淡入淡出/转场）保持 tween 是对的。

### 最具体的 1 处改法

```kotlin
// ui/compose/DhAnim.kt:77  现在
val CARD_EXPAND: TweenSpec<IntSize> =
    tween(durationMillis = DhDesign.Motion.enter, easing = LinearOutSlowInEasing)

// 改成
val CARD_EXPAND: SpringSpec<IntSize> =
    spring(dampingRatio = 0.85f, stiffness = 380f)
```

> 展开收起用的是 `AnimatedVisibility` + `expandVertically`，它接受 `FiniteAnimationSpec`，
> spring 与 tween 都可传。

---

## 二、层次与材质：4 处阴影 = 界面是平的

### 现状

```bash
grep -rn "\.shadow(" app/src/main/java --include=*.kt | grep -vc "//"   # 4
# 其中 1 处是 DesignSystem.kt 的注释，实际调用 3 处：
#   ui/chat/ChatPanels.kt:411   PanelPaper
#   ui/kit/DhDialogHost.kt:188  ConfirmSheet
#   ui/kit/DhDialogHost.kt:326  MenuSheet
```

翻开全 App：**只有 3 个浮层有阴影**。卡片、底栏、顶栏、抽屉、输入框——**全部纯平**。

### 为什么它让人觉得不精致

人类判断"物体在哪个平面"靠**三层线索**：
1. **边缘**（发丝线）— 我们只有这一层
2. **阴影**（遮挡关系）— 几乎没有
3. **材质**（底色差异）— 弱

只有边缘 → 所有东西看起来**贴在同一个平面上**，像一张印刷好的版面。
用户说"只是改了排布"，正是因为**没有纵深**：区域是靠"划线"分出来的，不是靠"浮起来"分出来的。

Expressive / Material You 的做法是**极淡阴影**（不是重投影）：
`0 1px 2px 6%` 这种——你几乎看不出有阴影，但**大脑知道它浮着**。

### 哪些该有阴影却纯平（逐处）

| # | 位置 | 现在 | 应该改成 | 影响 |
|---|---|---|---|---|
| **1** | `ui/compose/DhKit.kt:414` `Card`（`flat` 的 else 分支） | 无阴影（1dp 描边 + 纯色底） | `.shadow(2.dp, shape)`（即 `DhDesign.Elevation.e1`） | **15 个文件、约 50 张卡片** |
| **2** | `ui/compose/DhKit.kt:1544` `NavBar`（底栏） | 半透明胶囊 + 描边，无阴影 | `.shadow(8.dp, pillShape)` | **所有 3 个 Tab 页** |
| **3** | `ui/home/ProfileDrawer.kt:429` 抽屉面板 | `.background(c.sheet)` 纯色 | `.shadow(12.dp, 右圆角shape)` | 抽屉开合的所有页面 |
| **4** | `ui/compose/DhKit.kt` `TopBar`/`TabTopBar` | **零背景、零阴影**（内容直接叠页面） | 滚动后加 `.shadow(1.dp)` 或顶部渐变 | **所有页面** |
| **5** | `ui/compose/DhForm.kt` `Input` 聚焦态 | 只换底色 + 描边 | 聚焦时加 `.shadow(1.dp)` | 53 处输入框 |

**注意**：设计稿有硬规则"列表行永不加阴影"。这条**不改**——
列表行确实不该有阴影（会显著增加绘制开销且视觉杂乱）。要加的是**卡片与浮层**。

### 最具体的 1 处改法

```kotlin
// ui/compose/DhKit.kt:414-419  Card 的 modifier 链（.then{ if(flat) Modifier else ... } 内），现在
.clip(RoundedCornerShape(R_CARD.dp))
.background(c.card)
.border(1.dp, c.line, RoundedCornerShape(R_CARD.dp))

// 改成（shadow 必须在 clip 之前）
.shadow(elevation = DhDesign.Elevation.e1, shape = RoundedCornerShape(R_CARD.dp), clip = false)
.clip(RoundedCornerShape(R_CARD.dp))
.background(c.card)
.border(1.dp, c.line, RoundedCornerShape(R_CARD.dp))
```

`DhDesign.Elevation.e1 = 2.dp` **已存在**（我上轮补的），只是从没被卡片用过。

---

## 三、比例精度：圆角与尺寸没成比例

### 现状

```bash
# 元素尺寸 vs 圆角
python3 - <<'EOF'
import re, os
root='app/src/main/java'
pat=re.compile(r'\.size\(\s*(\d+)\.dp\s*\)[\s\S]{0,100}?\.clip\(\s*RoundedCornerShape\(([^)]+)\)')
for d,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(d,f); rel=p.replace(root+'/','')
        src=open(p,encoding='utf-8',errors='replace').read()
        for m in pat.finditer(src):
            r=m.group(2).strip(); mm=re.search(r'(\d+)', r)
            rad = 999 if '999' in r else (int(mm.group(1)) if mm else None)
            s=int(m.group(1))
            print("%4ddp  圆角%-6s  %s:%d" % (s, rad, rel, src[:m.start()].count('\n')+1))
EOF
```

实测结果：

| 尺寸 | 圆角 | 比例 | 位置 | 判断 |
|---:|---|---|---|---|
| 72dp | 10dp | 0.14 | `DollListScreen.kt:960` | ❌ **偏小**：72dp 大块配 10dp 圆角，看着"方" |
| 40dp | 8dp | 0.20 | `ChatUi.kt:1167` | ⚠️ 与同尺寸的 40dp+12dp 不一致 |
| 32dp | 8dp | 0.25 | — | ✅ 合理 |
| 48dp | 8dp | 0.17 | `DollActionPage.kt:880` | ❌ 偏小 |
| 46dp | 圆 | — | `ContactScreen.kt:277` | ⚠️ |
| 38dp | 圆 | — | `ProviderListPage.kt:293` | ⚠️ |
| 34dp | 圆 | — | `DhKit.kt:997` / `DollGroupPage.kt:326` | ⚠️ |
| 52dp | 圆 | — | `DollHomePage.kt:205` / `DollRangePage.kt:232` | ⚠️ |

### 为什么它让人觉得不精致

圆角不是"有个值就行"，它有个**视觉比例律**：
- **小元素**（≤40dp）：圆角占比 1/4~1/3 看起来"圆润亲切"
- **中等**（40~56dp）：圆角 12~16dp
- **大块**（≥72dp）：圆角应 16~20dp，否则显得"硬"

我们的现状：**72dp 用 10dp（0.14）、48dp 用 8dp（0.17）** —— 都偏小，
大块显方、小圆角在大面积上更明显。同时**圆形按钮尺寸散成 34/38/46/52 四档**，
并排出现时大小不一。

### 具体改法

| 位置 | 现在 | 改成 |
|---|---|---|
| `ui/doll/DollListScreen.kt:963` | `.clip(RoundedCornerShape(10.dp))` | `.clip(RoundedCornerShape(DhDesign.Radius.lg.dp))`（16dp，比例 0.22） |
| `ui/doll/DollActionPage.kt:880` 附近 | `RoundedCornerShape(8.dp)` | `DhDesign.Radius.md.dp`（12dp） |
| `ui/chat/ChatUi.kt:1167` | `RoundedCornerShape(8.dp)` | 与其它 40dp 统一成 `Radius.md`（12dp） |
| `ui/provider/ProviderListPage.kt:293` 等圆形座 | 38/46/34/52 四档 | 统一到 **40dp**（`IconSeat.MD`） |

---

## 四、留白节奏：处处 8dp ＝ 没有节奏

### 现状

```bash
grep -rhoE "spacedBy\(\s*[0-9]+\.dp" app/src/main/java --include=*.kt | sort | uniq -c | sort -rn
#   13  spacedBy(8.dp
#    2  spacedBy(0.dp
#    1  spacedBy(3.dp
#    1  spacedBy(12.dp
```

**13 处全是 8dp**。卡片间距本该是 `Space.md`(12)，但大量地方用了 8。

### 为什么它让人觉得不精致

精致的排版有**节奏**：一段紧、一段松，读者能感到"这里分组了"。
全部 8dp 的结果是**所有东西等距**，视觉上没有"段落"，
像一份没有段落间距的文档——字都对，但读起来累、看不出结构。

用户说的"只是改了排布"，有一部分就是**这个**：
排布确实改了（对齐了、落档了），但**没有疏密变化**，所以"看起来还是平"。

### 具体改法（建立三档节奏）

| 场景 | 现在 | 改成 | 理由 |
|---|---|---|---|
| **组内**相邻行 | 8dp | **4dp**（`Space.xs`） | 同一组内的行应该"贴"在一起 |
| **组间**（卡片之间） | 8dp | **16~24dp**（`Space.lg`/`xl`） | 组与组之间要"呼吸" |
| **段落间**（标题与内容） | 8dp | **12dp**（`Space.md`） | 中间层 |
| 卡片内部上下 padding | `PAD_CARD`(16) | 保持 16 | ✅ |

具体落点：
- `ui/compose/DhKit.kt:1154` `Divider` 周边、`:1010` `SectionLabel` 下方 —— 组标题与内容间用 **12**
- 各页面 `spacedBy(8.dp)` → 按"组内 4 / 组间 16"重排

**原则**：不是"把 8 改成 12"，而是**让间距有 3 个层次**（4 / 12 / 24）。
同一种间距到处用，就是没有节奏。

---

## 五、色彩点缀：90 次 accent 有点泛滥

### 现状

```bash
grep -rho "c\.acc\b" app/src/main/java --include=*.kt | wc -l    # 90
grep -rho "c\.err\b" app/src/main/java --include=*.kt | wc -l    # 33
grep -rho "c\.ok\b"  app/src/main/java --include=*.kt | wc -l    # 9
```

单文件排行：`ChatUi.kt` 10、`ImportAssistantKit.kt` 9、`DhForm.kt` 8、`DollRangePage.kt` 6。

### 为什么它让人觉得不精致

**强调色的价值来自稀缺性**。一个屏幕里出现 5 处紫色，就等于没有强调——
用户的眼睛不知道该看哪里。精致界面的规律是：**每屏 1~2 个强调点**。

我们现在 90 处 accent，平均每个页面约 3 处。`c.err` 33 处也偏多（错误色应该是"意外"，不是常态）。

### 具体改法（不是删，是"降级"）

| 场景 | 现在 | 改成 |
|---|---|---|
| 图标着色 `c.acc` | 用强调色画图标 | 多数改 `c.sub`，**只有主操作图标保留 acc** |
| 文字 `c.acc` | 用强调色写文字 | 只有**可点文字**用 acc，普通说明用 `c.title`/`c.sub` |
| `c.err` 33 处 | 频繁用错误色 | 只有**真的出错**用 err，禁用态用 `c.sub.copy(alpha=0.5f)` |

具体落点：`ui/chat/ChatUi.kt`（10 处）先看——
其中工具条图标、模型名、状态文字里，估计只有 2~3 处该保留紫色。

### 状态色克制度：✅ 这块其实做得不错

`c.ok` 只有 9 处、`semanticBg` 7 处，没有硬编码色（仅 1 处，是 `AppearancePage` 的 hex 解析）。
**状态色是克制的**，问题只在 accent 偏多。

---

## 六、前一轮已确认的短板（保持结论）

| 项 | 现状 | 影响 |
|---|---|---|
| **半透明** | 全 App 仅 1 处（底栏胶囊 25% alpha，`UiKit.kt:262`）；顶栏**零背景**、弹窗**零遮罩** | 浮起感弱 |
| **加载态** | `DhSkeletonRow` **仅 1 处**（`ChatListScreen:286`） | 4 个主力页 3 个空白 |
| 非标圆角 | 7 处 | 轻 |
| 6dp/10dp | 38 + 29 = **67 处** | 中 |

---

## 核心问题：只改 3 处，最能让它显精致

> 判据：**感知增益 ÷ 改动力度**。优先选"一行代码、全 App 受益、肉眼立刻可见"。

### 第 1 处：给 `Card` 加 2dp 阴影 ⭐ 投入产出最高

**为什么排第一**
- **改 1 行代码**（`DhKit.kt:414` 的 modifier 链，`.then{}` 的 else 分支内）+ 其余 4 处浮层，就能让 **15 个文件、约 50 张卡片**全部"浮起来"
- `DhDesign.Elevation.e1 = 2.dp` **已经存在**，只是从没被用过
- 2dp 是极淡阴影：**几乎看不出，但大脑能感到纵深**

**为什么它能解决"不精致"**
用户说"只是改了排布"——因为现在**所有东西在一个平面上**，靠划线分区。
加上 2dp 阴影后，卡片从"一个有线框的矩形"变成"一张浮在页面上的纸"。
这是 Material Design 与"印刷版面"最本质的区别。

**具体改法**
```kotlin
// ui/compose/DhKit.kt:414 之前插一行（在 .then{ ... } 的 else 分支内），Card
.shadow(DhDesign.Elevation.e1, RoundedCornerShape(R_CARD.dp), clip = false)
.clip(RoundedCornerShape(R_CARD.dp))
.background(c.card)
.border(1.dp, c.line, RoundedCornerShape(R_CARD.dp))
```
同时 `NavBar`(底栏) 用 e2(12dp)、抽屉面板用 e2。

---

### 第 2 处：折叠卡与输入框聚焦改用 spring（9 处 tween → spring）

**为什么排第二**
- 用户感受"丝滑"的**唯一来源**就是 spring 的微过冲
- 改 **9 处**（`DhAnim.kt:77/82` 折叠 2 处 + `DhForm.kt` 4 处 + `Components.kt` 2 处 + `DeveloperOptionsPage.kt:270` 1 处）
- 现成 API（Compose 的 `spring()`），零额外依赖

**为什么它能解决"不精致"**
现在展开卡片像"被匀速拉开"，聚焦输入框像"色块瞬间变色"。
换成 spring 后：卡片"弹开"、描边"润过来"——这就是"打磨过"与"能用"的区别。

**具体改法**
```kotlin
// ui/compose/DhAnim.kt:77
val CARD_EXPAND: SpringSpec<IntSize> = spring(dampingRatio = 0.85f, stiffness = 380f)
// ui/compose/DhForm.kt:230
animationSpec = spring(dampingRatio = 1f, stiffness = 900f)   // 颜色用 ζ=1.0 不过冲
```

> **注意**：颜色动画必须用 `dampingRatio = 1f`（临界阻尼），否则颜色 lerp 越界会闪
> —— `DhAnim.kt` 里 `COLOR_SPRING` 已经是这么写的，直接复用。

---

### 第 3 处：建立三档间距节奏（组内 4 / 组间 16 / 段落 12）

**为什么排第三**
- 改动需逐页判断（`spacedBy(8.dp)` 13 处 + 各处 8dp padding），比前两件重
- 但它是**"看起来有结构"**的根本：现在处处 8dp = 没有段落感

**为什么它能解决"不精致"**
用户的抱怨"**只是改了排布**"里，"排布"这个词本身就是线索：
我们确实只做了"对齐与落档"，没有做"**疏密**"。同一份内容，
组内贴紧 4dp、组间拉开 24dp，立刻从"表格"变成"卡片组"。

**具体改法**
- `ui/compose/DhKit.kt:1010` `SectionLabel` 的 `bottom` → 12dp（标题与内容之间）
- 各页面 `spacedBy(8.dp)` → 组内改 4、组间保留 12~16
- 优先改 **3 个高频页**：`ChatListScreen`、`MemPage`、`SettingsScreen`

---

## 附：为什么"令牌化"没能带来精致感

这一节是给后续工作的判断依据：

| 我们做的 | 解决了 | 没解决 |
|---|---|---|
| 字号/间距/圆角令牌化 | **一致**（各处不再各写各的） | **节奏**（全部落档 = 全部等距） |
| 组件转发到 `ui/kit` | **统一**（同一构件一份实现） | **层次**（统一成"平的"也一样平） |
| 去 View 化 | **架构**（Compose 接管 UI） | **质感**（架构不影响观感） |
| 动画 spec 全覆盖 | **不乱**（无默认曲线） | **手感**（tween 也能"合规但机械"） |

**结论**：规范是**下限**，不是"精致"的来源。
精致来自三件规范之外的事：**阴影带来的纵深、弹簧带来的呼吸、疏密带来的韵律**。
这三件恰好就是本文推荐的前三项。

---

## 边界

1. **全部静态分析**，不能证明观感。阴影浓度、spring 手感**必须真机确认**（`docs/DEVICE_QA.md`）。
2. **`spring` 的 dampingRatio/stiffness 给的是起点值**，需真机微调（0.85/380 是常见甜点区）。
3. 阴影会**增加绘制开销**，列表滚动场景需实测帧率；本文只建议给"卡片/浮层"，不给列表行。
4. 数字会漂移，以命令输出为准。