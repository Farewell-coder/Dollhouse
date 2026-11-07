# 6dp / 10dp 收敛映射规则

> 来源：task-13「6dp/10dp 硬编码收敛：单文件试点」。
> 试点文件：`app/src/main/java/com/dollhouse/app/ui/settings/DeveloperOptionsPage.kt`
> 本文档是后续 40 个文件照做的判定依据，不是规范本身 —— 规范是 `ui/design/DesignSystem.kt` 的 `DhDesign.Space`。

---

## 1. 为什么不能机械替换

`6dp` 和 `10dp` 在代码里不是同一个东西。同一个数字在不同位置承担**不同语义**，而
`DhDesign.Space` 的刻度是 4 / 8 / 12 / 16 / 24 / 32：

| 原值 | 机械替换会到 | 问题 |
|---|---|---|
| `10.dp` | 12 或 8，任选其一 | 两种选择都很"合理"，无法自动判断 |
| `6.dp` | 8 或 4 | 同上，且 6 常与 5/9 这类微调混在一个卡里，一刀切会破坏已成立的视觉层级 |

**关键判定：6 与 10 都不在刻度上，所以每一处都必须先判语义，再决定落到哪一档。**
试点的结论是：10 处里 **10 处可映射、0 处必须保留原值**，但同一卡片内还有 2 处（`5.dp` / `9.dp`）属于
`6/10` 之外的近邻值，它们**必须保持原值**，否则卡片内部的视觉对位会被破坏。

---

## 2. 判定表（按语义选档）

按从上到下的顺序匹配，命中即停。

| # | 语义 | 识别特征 | 映射到 | 依据 |
|---|---|---|---|---|
| R1 | **图标 ↔ 文字** | `Spacer(Modifier.width/height(..))` 紧邻 Icon；或 `Row` 内 Icon 后接 Text 的间距 | `DhDesign.Space.iconGap`（=8） | 设计稿「图标与文字间距全 App 一口径」 |
| R2 | **行内元素之间**（chip 与 chip、按钮与按钮） | 间距两端都是可点控件 | `DhDesign.Space.sm`（=8） | 两侧都要保住命中区，不能压到 4 |
| R3 | **行内 padding**（卡内一行的上下内边距） | `padding(vertical=..)` / `padding(top=..)` 作用在**行容器**上 | `DhDesign.Space.sm`（=8） | 行内紧凑，取 8 |
| R4 | **卡内块与块之间**（徽标行 → 正文、正文 → 元信息） | `padding(top=..)` 作用在**内容块**上，上下两侧是不同的内容块 | `DhDesign.Space.md`（=12） | 设计稿 §间距「卡片内段落之间 = 12」 |
| R5 | **组间距**（一组元素与下一组之间） | 作用在包含多个子项的 `Column` 上；且内部已有 `spacedBy(8)` | `DhDesign.Space.md`（=12） | 「组间 > 组内」，12 > 8 才成立 |
| R6 | **区块间距**（页面级分组之间） | 紧邻 `SectionLabel` / 卡片边界 | `DhDesign.Space.xl`（=24）**减掉组件自带内边距后的差额** | 见 §3 的"补偿"陷阱 |
| R7 | **纯微调**（1–2dp 的视觉对位） | 与相邻值构成非整数关系（5/9/6 成组出现），或用于补偿字体基线 | **保持原值** | 见 §4 |
| R8 | **网格/列表的项间距**（`spacedBy`） | `Arrangement.spacedBy(..)` 作用于网格或列表容器，间距两端都是**完整卡片** | `DhDesign.Space.cardGap`（=12） | 与卡片间的组间距同一语义，不能比组内还紧 |
| R9 | **面板/浮层内边距**（自有内容区的整体留白） | `padding(N.dp)` 直接作用于**面板容器**（如搜索浮层、菜单卡） | `DhDesign.Space.md`（=12） | 与 `PAD_ROW`（=12）同档；面板不再是卡内行那么紧凑 |
| R10 | **主次文字对**（主标题 → 次级说明） | 两个 `Text` 之间，**没有**图标参与；后者是前者的补充说明 | `DhDesign.Space.xs`（=4） | 与图标间距区分开：这里不是"图标↔文字"，压到 4 才有从属感 |

### 硬规则

- **同一个文件内，同一语义只能落到同一档。** 若发现同一文件里"卡内块间距"既有 10 又有 12，那是历史漂移，
  一并收敛到 R4 的 12，不要保留两种。
- **不要用 `+2.dp` / `-2.dp` 的算术补偿去凑原来像素。** 间距收敛的目的就是承认"看起来差不多"，
  坚持像素级等价等于没做收敛。唯一例外见 §3。
- 一律写 `DhDesign.Space.xxx.dp`，不要在本文件里定义局部常量 —— 局部常量会成为下一轮的第二个真源。

---

## 3. 陷阱：组件自带内边距会叠加

`DhKit.SectionLabel` **自己**已经加 `padding(top = 12.dp, bottom = 4.dp)`。
调用点再传 `top=10, bottom=6` 时，真实间距是 **上 22 / 下 10**，不是 10 和 6。

所以 R6 的正确做法不是"把 10 换成 24"，而是**先减去组件自带值，只补差额**：

```
目标 上=24（区块间距 Space.xl）、下=8（标题与内容 Space.sm）
组件自带 上=12、下=4
⇒ 调用点只写 上=12(Space.md)、下=4(Space.xs)
```

**这是本次试点唯一"不能照字面替换"的一类，后续 40 个文件凡遇 `SectionLabel` 调用点都必须先查组件自带值。**

受此规则影响的组件（需在各自文件里确认，不要假设）：

- `DhKit.SectionLabel` → top=12 / bottom=4（无图标分支），top=12 / bottom=2（带图标分支）
- `DhForm.SelectChip` → 自带 `padding(horizontal=10, vertical=8)`
- `DhKit.ActionRow` / `DhKit.ValueRow` / `DhKit.Card` → 均自带内边距，调用点 padding 是**叠加**

---

## 4. 不要动清单（DO NOT TOUCH）

以下情况**一律保持原值**，并在改动处留一行注释说明"为什么不动"。这一条比映射表更重要 ——
误改它们不会编译失败，只会让页面慢慢变丑，而且没有任何自动化能发现。

| # | 情况 | 为什么不动 | 本次试点实例 |
|---|---|---|---|
| D1 | **同一文字块内的微对位**（5dp / 9dp 这类） | 与相邻元素构成非整数视觉关系，改成 4/8 会明显改变疏密 | `DeveloperOptionsPage.kt:225` `top=5.dp`、`:226` `top=9.dp` |
| D2 | **补偿字体基线 / 行高的对位** | 5/9 往往是"按 22sp 行高 + 字重算出来的"，换成刻度值反而错位 | 同上 |
| D3 | **命中区关键间距**（间距小于 8 会粘连两个可点目标） | 反过来：这类**必须**升到 8，不要因为"原来是 6"就保原值 | `FilterChip` 的 `end=6` 升 8 |
| D4 | **与 `1.dp`/`2.dp` 边框配套的内边距** | 边框是 1dp 时，内边距常按"边框 + 视觉补偿"取值，独立收敛会破坏对齐 | 本文件无实例；后续注意 `border(1.dp..)` 附近的 padding |
| D5 | **动画 / 位移相关的偏移** | 属于 `Motion` 语义，不是 `Space` 语义，收敛应走另一套令牌 | 本文件无实例 |
| D6 | **字号 / 行高 / 圆角 / 图标尺寸里的 6 或 10** | 它们归 `Type` / `Radius` / `Size`，不属于 `Space`，本轮不得顺手改 | 本文件无实例（`RoundedCornerShape(12)`、`fontSize=16.sp` 均未动） |
| D7 | **在任何"未验证页面"上做批量替换** | 见 §7 前提 | — |
| D8 | **圆角里的 6/10**（`RoundedCornerShape(10.dp)` 等） | 属 `Radius` 语义，不是 `Space`。改它等于改形状，且 `R_CARD/R_ROW` 已有统一入口 | `DollListScreen.kt:961/963` `RoundedCornerShape(10.dp)`（缩略图方块，视觉尺寸已定 72dp） |
| D9 | **带缩放系数的间距**（`N.dp * k`） | 系数 `k` 是"按列数等比缩放"的既有设计意图；只换基准值、保留 `* k`，**不要合并成一个常量** | `DollListScreen.kt:515` `Space.md.dp * k`（已换基准但保留系数） |
| D10 | **注释/文档里出现的 6dp/10dp 字样** | 那是历史说明（如"原实现是 `padding(6.dp)`"），改了会篡改事故记录 | `DollListScreen.kt:505/507` 两处注释 |

**禁止**：为了让计数归零而把 D1/D2 的值硬映射到刻度上。计数是手段，不是目标。

---

## 5. 试点结果（DeveloperOptionsPage.kt）

### 实测口径

任务描述写"13 处"，实测是 **10 处**（`grep -cE '[^0-9](6|10)\.dp'` 报 **8**，因为两行各含 2 处）。
按 `.dp` 字面量逐个数：`6.dp` ×3、`10.dp` ×7 = **10**。任务里的 13 未能复现，可能是统计时把
`16.dp`（含子串 `6.dp`）或相邻文件的计数算进来了。**本报告一律以 10 为准。**

### 逐处判定

| 行 | 原值 | 语义 | 判定 | 改为 |
|---|---|---|---|---|
| 127 | `top=10, bottom=6` | 区块标题（SectionLabel） | R6 差额补偿 | `Space.md` / `Space.xs` |
| 132 | `top=10, bottom=6` | 区块标题（SectionLabel） | R6 差额补偿 | `Space.md` / `Space.xs` |
| 181 | `vertical=10` | 行内 padding（筛选条带） | R3 | `Space.sm` |
| 187 | `bottom=10` | 行内 padding（筛选条带，同组） | R3 | `Space.sm` |
| 204 | `end=6` | 行内元素之间（chip 与 chip） | R2 | `Space.sm` |
| 212 | `top=10` | 卡内块与块之间 | R4 | `Space.md` |
| 224 | `top=10` | 卡内块与块之间 | R4 | `Space.md` |
| 230 | `top=10` | 组间距（面板组） | R5 | `Space.md` |

### 计数

| | 6dp | 10dp | 合计 | 门禁 `grep -cE` |
|---|---|---|---|---|
| 改前 | 3 | 7 | 10 | 8 |
| 改后 | 0 | 0 | **0** | **0** |

**10 处全部可收敛，0 处必须保留。** 文件内另有 2 处近邻微调按 §4 D1 保持原值（`5.dp`、`9.dp` 各 1 处）。

改动性质：只改了数值与常量引用 + 新增 `DhDesign` import + 每处一行解释性注释，**未改任何布局结构、无逻辑改动**。

---

## 6. 验收门禁

每个文件改完必须跑（本机无 aapt2，无法编译，全靠静态门禁）：

```bash
cd "/sdcard/work area/xiaods/Dollhouse"
python3 tools/kotlin_precheck.py      # 未发现问题
python3 tools/comment_balance.py      # 全部平衡
python3 tools/symbol_refs.py --quiet  # exit 0（新增 import 的悬空引用检查）
bash tools/ui_audit.sh                # 44/44 全通过
grep -cE '[^0-9](6|10)\.dp' <该文件>   # 记录前后数字
```

**注意 `comment_balance.py` 是必须的**：Kotlin 块注释可嵌套，注释里写 `some/path/` 后紧跟一个星号
（即通配写法）会静默吞掉后面整段代码。
写路径一律写 `some/path/`，不要带 `*`。

---

## 7. 推广前提（诚实结论）

规则**能推广，但不能整仓自动跑**：

1. **§3 的组件内边距叠加是人工判断项。** 必须逐个确认被调组件是否自带 padding，
   机器无法区分"调用点 padding 是叠加还是对齐"。这一条覆盖了 40 个文件里所有
   `SectionLabel` / `ActionRow` / `ValueRow` / `SelectChip` 调用点，占比很高。
2. **R4 / R5 的边界靠语义判断，不靠正则。** "卡内块间距"和"组间距"在 AST 上都是
   `padding(top=..)`，只有读上下文能分开。
3. **没有真机视觉验证。** 本轮的把握来自"10 处落在 3 个语义类别内、且每类都有设计稿依据"，
   不是来自"看见效果正确"。因此：
   - 建议按**每批 1–3 个文件**推进，每批留一次可回滚的提交粒度；
   - **不要**一次性改 40 个文件 —— 那正是任务描述里判定"风险大于收益"的做法。
4. **优先级排序**（按收益/风险比）：
   - 先做**独立二级页**（settings / provider / doll 的独立页面）：改坏了影响面局部
   - 后做**聊天核心**（`ChatUi.kt` 34 处、`MemPage.kt` 22 处）：改坏了用户当场能感觉到
   - `DhKit.kt`（17 处）、`DhForm.kt`（4 处）是**组件本身**，改动会放大到所有调用点，
     必须单独一批、单独验证

---

## 8. 剩余文件清单（按 6dp+10dp 数量降序）

> 快照时间：**第一批 + 第二批完成后**。全仓剩余 **187 处 / 41 个文件**。
> 任务描述写"221 处 / 40 文件"，与实测有小差异，以本节可复现的数字为准：
> `grep -rnE '(?<![0-9])(6|10)\.dp' app/src/main/java --include=*.kt`（Python 正则）
>
> ⚠️ 带 🔒 的文件当时正由其他队员编辑，**不得与本任务并行处理**。

### 第一批（独立页面，低风险）

| 文件 | 6dp | 10dp | 合计 |
|---|---|---|---|
| `ui/widget/AexImportSheet.kt` | 7 | 6 | 13 |
| `ui/doll/DollListScreen.kt` | 6 | 7 | 13 |
| `ui/doll/DollAnimPage.kt` | 7 | 3 | 10 |
| `ui/widget/AkcpImportSheet.kt` | 3 | 4 | 7 |
| `ui/home/ContactScreen.kt` | 6 | 0 | 6 |
| `ui/doll/DollActionPage.kt` | 6 | 0 | 6 |
| `ui/settings/AppearancePage.kt` | 3 | 2 | 5 |
| `ui/doll/DollGroupPage.kt` | 2 | 2 | 4 |
| `ui/settings/ModelEditPage.kt` | 1 | 3 | 4 |
| `ui/settings/LamdaPage.kt` | 0 | 4 | 4 |
| `ui/doll/ModelManagePage.kt` | 3 | 0 | 3 |
| `ui/doll/DollImportWizardPage.kt` | 3 | 0 | 3 |
| `ui/provider/ProviderListPage.kt` | 2 | 1 | 3 |
| `ui/doll/ImportAssistantPage.kt` | 2 | 1 | 3 |
| `ui/settings/ModelListPage.kt` | 2 | 0 | 2 |
| `ui/provider/ProviderModelsPage.kt` | 2 | 0 | 2 |
| `ui/home/BottomNav.kt` | 2 | 0 | 2 |
| `ui/doll/DollPersonaDialogs.kt` | 2 | 0 | 2 |
| `ui/doll/DollLookTab.kt` | 2 | 0 | 2 |
| `ui/widget/ExportSheet.kt` | 1 | 1 | 2 |
| `ui/provider/ProviderEditPage.kt` | 1 | 1 | 2 |
| `ui/home/ProfileDrawer.kt` | 1 | 1 | 2 |
| `ui/settings/AboutPage.kt` | 0 | 2 | 2 |
| `ui/doll/DollNumDialog.kt` | 0 | 2 | 2 |
| `ui/settings/MyProfilePage.kt` | 1 | 0 | 1 |
| `ui/home/ComingSoonPage.kt` | 1 | 0 | 1 |
| `ui/doll/DollTalkPage.kt` | 1 | 0 | 1 |
| `ui/doll/DollHomePage.kt` | 1 | 0 | 1 |
| `pet/DollHomeOverlay.kt` | 1 | 0 | 1 |
| `ui/widget/MiniChart.kt` | 0 | 1 | 1 |
| `ui/chat/FriendModelConfigPage.kt` | 0 | 1 | 1 |

### 第二批（导入助手，与第一性相近但更大）

| 文件 | 6dp | 10dp | 合计 |
|---|---|---|---|
| `ui/doll/ImportAssistantKit.kt` | 8 | 5 | 13 |

### 第三批（聊天核心 / 组件本身，高风险，必须单独验证）

| 文件 | 6dp | 10dp | 合计 |
|---|---|---|---|
| `ui/chat/ChatUi.kt` 🔒 | 21 | 13 | 34 |
| `ui/settings/MemPage.kt` | 10 | 12 | 22 |
| `ui/compose/DhKit.kt` 🔒 | 7 | 10 | 17 |
| `ai/TokenStat.kt` | 2 | 3 | 5 |
| `ui/kit/Components.kt` | 2 | 2 | 4 |
| `ui/compose/DhForm.kt` 🔒 | 1 | 3 | 4 |
| `ui/chat/ChatListScreen.kt` | 3 | 3 | 6 |
| `ui/chat/BlockRenderer.kt` | 0 | 2 | 2 |
| `ui/chat/ChatPanels.kt` | 1 | 0 | 1 |

### 第二批（已完成）

| 文件 | 6dp | 10dp | 合计 | 改后 | 说明 |
|---|---|---|---|---|---|
| `ui/settings/MemPage.kt` | 10 | 12 | 22 | **0** | 全部可收敛；含 3 处图标间距（R1）、7 处列表/空态缩进（R4）、4 处 chip 前置间距（R3） |
| `ui/doll/DollListScreen.kt` | 6 | 7 | 13 | **4** | 收敛 9 处（上一批 7 处 + 收尾 2 处）；保留 4 处：2 处圆角（D8）、2 处注释（D10） |

#### MemPage 逐类统计（22 处）

| 规则 | 处数 | 典型位置 |
|---|---|---|
| R1 图标↔文字 → `iconGap` | 3 | 空态图标→标题、关联行图标→文字 |
| R3 行内 padding / 前置间距 → `sm` | 5 | 输入框→快捷 chip、工具条按钮之间 |
| R4 块间距 / 列表缩进 → `md` | 10 | 列表头、图谱头、文档卡、详情弹窗 |
| R5 组间距 → `md` | 2 | 重要度区、关联区 |
| R9 面板内边距 → `md` | 1 | `SearchPanel` 的 `.padding(10.dp)` |
| R10 主次文字对 → `xs` | 1 | 空态标题→说明 |

#### DollListScreen 保留的 4 处（逐条理由）

| 行 | 原值 | 类别 | 为什么不动 |
|---|---|---|---|
| 961 | `RoundedCornerShape(10.dp)` | D8 | 圆角属 `Radius`，不是 `Space`；且该方块视觉尺寸已由 `.size(72.dp)` 定死，单改圆角会改变形状比例 |
| 963 | 同上（border 的 shape） | D8 | 必须与 961 保持一致，否则描边与裁剪圆角不重合 |
| 505/507 | 注释中的 `padding(6.dp)` | D10 | 历史事故说明（"原实现是 Fit + 6dp → 改 Crop + 0dp"），改了等于篡改记录 |

> `:515` 的 `.padding(top = 10.dp * k)` 已改为 `Space.md.dp * k` —— **换了基准值、保留了缩放系数**（D9）。

#### 收尾批次（task-22）：原 6 处逐条判定

上一批遗留：`6dp` ×3、`10dp` ×3，落在 5 行上（其中 `:1087` 一行含两个值）。

| 行 | 原值 | 判定 | 结论 |
|---|---|---|---|
| 505 | 注释 `padding(6.dp)` | D10 | **保持原值** —— 事故记录 |
| 507 | 注释 `padding(6.dp)` | D10 | **保持原值** —— 同上 |
| 961 | `RoundedCornerShape(10.dp)` | D8 | **保持原值** —— Radius 语义 |
| 963 | `RoundedCornerShape(10.dp)` | D8 | **保持原值** —— 与 961 配套 |
| 1087 | `padding(top=10, bottom=6)` | **R3**（面板内行内 padding） | **收敛** → `Space.sm` / `Space.sm` |

**关于 `:1087` 的分类更正**：上一批把它记作「D8 邻近/D4」，两条都不成立 ——
D8 是圆角语义（此处是 padding），D4 是「与 1dp/2dp 边框配套的内边距」（此处无边框）。
它是**正牌的 R3 行内 padding**，属于该收敛的间距。之前保留的依据（「M3 handle 位置按固定值算」）
也不成立：`SheetDragHandle` 是**完全自绘**的 `dragHandle` 槽内容，M3 不参与其布局，
上下留白就是它自己的 `padding`，没有需要对齐的系统值。

取值理由：`10 → sm(8)`、`6 → sm(8)`。上下合计仍为 16dp，把手整体占位高度不变，
仅 4dp 条子在抓取带内上移 2dp；且与本文件既有的 6dp → `sm` 落档一致（§2 硬规则「同一语义只能落到同一档」）。

---

**✅ 已完成汇总**：
- `ui/settings/DeveloperOptionsPage.kt`（10 → 0）
- `ui/settings/MemPage.kt`（22 → 0）
- `ui/doll/DollListScreen.kt`（13 → 4，剩余全为 D8/D10 类别，非间距语义）
---

## 9. 全仓收敛批次（第三批 / 本轮）：182 → 69

> 快照口径：`python3` 正则 `(?<![0-9])(6|10)\.dp` 扫 `app/src/main/java/**/*.kt`。
> 本轮**排除**了他人正在编辑的文件：`ChatUi.kt`、`DhKit.kt`、`DhForm.kt`、
> `ProfileDrawer.kt`（34 + 14 + 4 + 2 = **54 处**，均未动）。

### 总数

| | 6dp | 10dp | 合计 | 文件数 |
|---|---|---|---|---|
| 改前 | 108 | 74 | **182** | 39 |
| 改后 | 40 | 29 | **69** | 13 |
| 本轮收敛 | −68 | −45 | **−113** | −26 |

其中留在原地的 5 处是**新加的 D 类例外**（见下），其余 64 处属于被排除的他人文件。

### 逐文件结果（本轮处理 24 个文件，113 处）

| 文件 | 改前 | 改后 | 收敛 |
|---|---|---|---|
| `ui/widget/AexImportSheet.kt` | 13 | 0 | 13 |
| `ui/widget/AkcpImportSheet.kt` | 7 | 0 | 7 |
| `ui/widget/ExportSheet.kt` | 2 | 0 | 2 |
| `ui/doll/ImportAssistantKit.kt` | 13 | 0 | 13 |
| `ui/doll/ImportAssistantPage.kt` | 3 | 0 | 3 |
| `ui/doll/DollAnimPage.kt` | 10 | 0 | 10 |
| `ui/doll/DollActionPage.kt` | 6 | 0 | 6 |
| `ui/doll/DollGroupPage.kt` | 4 | 0 | 4 |
| `ui/doll/DollImportWizardPage.kt` | 3 | 0 | 3 |
| `ui/doll/ModelManagePage.kt` | 3 | 0 | 3 |
| `ui/doll/DollLookTab.kt` | 2 | 0 | 2 |
| `ui/doll/DollNumDialog.kt` | 2 | 0 | 2 |
| `ui/doll/DollPersonaDialogs.kt` | 2 | 0 | 2 |
| `ui/doll/DollTalkPage.kt` | 1 | 0 | 1 |
| `ui/settings/AppearancePage.kt` | 5 | 0 | 5 |
| `ui/settings/LamdaPage.kt` | 4 | 0 | 4 |
| `ui/settings/ModelEditPage.kt` | 4 | 0 | 4 |
| `ui/settings/ModelListPage.kt` | 2 | 0 | 2 |
| `ui/settings/AboutPage.kt` | 2 | 0 | 2 |
| `ui/settings/MyProfilePage.kt` | 1 | 0 | 1 |
| `ui/provider/ProviderListPage.kt` | 3 | 0 | 3 |
| `ui/provider/ProviderEditPage.kt` | 2 | 0 | 2 |
| `ui/provider/ProviderModelsPage.kt` | 2 | 0 | 2 |
| `ui/home/ContactScreen.kt` | 5 | 0 | 5 |
| `ui/home/ComingSoonPage.kt` | 1 | 0 | 1 |
| `ai/TokenStat.kt` | 5 | 0 | 5 |
| `ui/chat/BlockRenderer.kt` | 2 | 1 | 1（剩 D8 圆角） |
| `ui/chat/ChatListScreen.kt` | 5 | 1 | 4（剩 D4 徽标内边距） |

### 本轮新增的 D 类例外（5 处，一律保持原值 + 就地注释）

| 文件:行 | 原值 | 类别 | 为什么不动 |
|---|---|---|---|
| `ui/doll/DollHomePage.kt:135` | `.blur(6.dp)` | D5/D6 | 虚化**半径**是滤镜强度，不是间距；改 4/8 直接改变画面 |
| `pet/DollHomeOverlay.kt:183` | `.blur(6.dp)` | D5/D6 | 同上（同一处视觉的浮层版本） |
| `ui/widget/MiniChart.kt:105` | `padT = 10.dp.toPx()` | D5 | Canvas 内绘图留白（px），与轴标签排版配套，非行间距 |
| `ui/chat/ChatPanels.kt:344` | `.height(6.dp)` | D6 | 分段条**自身尺寸**（Size 语义），不是元素之间的间距 |
| `ui/chat/ChatListScreen.kt:572` | `padding(horizontal = 6.dp, vertical = 1.dp)` | D4 | 未读徽标内边距，与 999 圆角配套并决定徽标尺寸 |
| `ui/home/BottomNav.kt:92/137` | `6/5`、`18/6` | D3/D4 | 底栏胶囊与单项内边距，决定底栏 64dp 口径与命中区宽度 |

### 本轮遵守的两条易错规则

1. **§3 组件内边距叠加**：`DollImportWizardPage` / `ModelEditPage` 等处紧邻
   `DhKit.SectionLabel`（自带 `top=12`），所以「标题→内容」只补差额，
   没有把 10 直接换成 24（那样真实间距会变成 36）。
2. **R4 / R5 / R10 的语义区分**：本轮所有 `<6dp → xs(4)>` 都只用在
   **主标题→次级说明**（无图标参与）的场合；凡两端是不同的内容块一律给 `md(12)`。

### 仍未收敛（13 个文件 / 69 处）及原因

| 文件 | 剩余 | 原因 |
|---|---|---|
| `ui/chat/ChatUi.kt` | 34 | 🔒 队友在改（去 View 阶段） |
| `ui/compose/DhKit.kt` | 14 | 🔒 队友在改（button-audit） |
| `ui/compose/DhForm.kt` | 4 | 🔒 队友在改 |
| `ui/home/ProfileDrawer.kt` | 2 | 🔒 队友在改 |
| `ui/kit/Components.kt` | 4 | **组件库自身的几何常量**（`ChipPadH` / `TagPadH` / `RowPadV`），改一处放大到 168 处转发调用点 —— 按 §7.4 必须单独一批单独验证 |
| `ui/doll/DollListScreen.kt` | 4 | 全部 D8（圆角）/ D10（注释） |
| `ui/chat/BlockRenderer.kt` | 1 | D8 圆角 |
| `ui/chat/ChatListScreen.kt` | 1 | D4 徽标 |
| `ui/chat/ChatPanels.kt` | 1 | D6 分段条尺寸 |
| `pet/DollHomeOverlay.kt` | 1 | D5/D6 虚化半径 |
| `ui/widget/MiniChart.kt` | 1 | D5 Canvas 内留白 |
| `ui/home/BottomNav.kt` | 1 | D3/D4 底栏口径 |
| `ui/doll/DollHomePage.kt` | 1 | D5/D6 虚化半径 |

### 门禁

每批改完均跑：`kotlin_precheck`（275 文件未发现问题）、`comment_balance`（全部平衡）、
`symbol_refs --quiet`（exit 0）。**无真机视觉验证** —— 见 §7.3 的既有结论。

---

## 10. 剩余裸间距收敛（第四批）：69 → 54

> 口径同 §9：Python 正则 `(?<![0-9])(6|10)\.dp` 扫 `app/src/main/java/**/*.kt`（按**出现次数**，含注释行）。
> 本轮任务书给的是「68 处」；**实测为 69**（6=40 / 10=29）。差 1 处的原因未能复现，
> 可能是统计时点或口径差异（按 grep 行数算是 63，排除注释行是 61），以本节数字为准。

### 总数

| | 6dp | 10dp | 合计 | 文件 |
|---|---|---|---|---|
| 改前 | 40 | 29 | **69** | 13 |
| 改后 | 35 | 19 | **54** | 11 |
| 本轮收敛 | −5 | −10 | **−15** | −2 |

### 本轮解除「在改中」后可处理的两个文件

| 文件 | 改前 | 改后 | 说明 |
|---|---|---|---|
| `ui/compose/DhKit.kt` | 14 | **5** | 收敛 9 处（R1×3、R2×2、R3×2、R4×1、R9×1）；剩 5 处为底栏/胶囊几何 |
| `ui/kit/Components.kt` | 4 | **3** | 收敛 1 处（骨架条 R10）；剩 3 处为控件自身几何常量 |
| `ui/home/ProfileDrawer.kt` | 1 | **0** | R1 图标↔文字 → `Space.iconGap` |

### 本轮保留原值的位置及理由（6 处）

| 位置 | 值 | 类别 | 为什么不动 |
|---|---|---|---|
| `DhKit.kt:1486` | `vertical = 6.dp` | D4 | NavItem 纵向内边距，与 `NAV_ITEM_H(54)` 配套；两者相加是**定值**，改了底栏会随页抖 |
| `DhKit.kt:1584` | `top = 6.dp, bottom = 10.dp` | D4 | NavBar 胶囊上下留白，同上「项高 + 内边距 = 定值」 |
| `DhKit.kt:1599` | `horizontal = 6.dp, vertical = 4.dp` | D4 | 胶囊内边距，决定胶囊总高（设计稿 64dp 口径） |
| `DhKit.kt:1755` | `horizontal = 6.dp, vertical = 4.dp` | D4 | `BarButton` 自身几何，不是间距 |
| `Components.kt:121/122/123` | `ChipPadH=10` / `TagPadH=6` / `RowPadV=10` | D6 | **控件自身几何**：前两者决定 chip/tag 宽度，后者是 DhRow 两行形态行高；改动放大到 **169 处**转发调用点，按 §7.4 须单独立项验证 |

> 依据本次指令「拿不准时保留原值并注释比硬落档安全」，以上均在代码内就地注明。

### DhKit.kt 本轮收敛明细（9 处）

| 行 | 语义 | 规则 | 落档 |
|---|---|---|---|
| 529 | SectionLabel 图标分支：图标↔文字 | R1 | `iconGap(8)` |
| 1097 | SectionLabel 无图标→带图标分支：图标↔文字 | R1 | `iconGap(8)` |
| 1143 | `InfoRow`：图标↔文字 | R1 | `iconGap(8)` |
| 574 | Page 顶栏：返回按钮↔标题（两侧都可点） | R2 | `md(12)` |
| 631 | TabBar：左槽↔标题 | R2 | `md(12)` |
| 707 | `LegacyValueRow` 两行形态行内纵向内边距 | R3 | `sm(8)` |
| 1385 | `LegacyActionRow` 有 desc 时行内纵向内边距 | R3 | `sm(8)` |
| 1139 | `InfoRow` 与上方内容块 | R4 | `md(12)` |
| 1893 | `Segmented` 分段项面板内边距 | R9 | `md(12)` / `sm(8)` |

### 仍未收敛（11 文件 / 54 处）

| 文件 | 剩余 | 原因 |
|---|---|---|
| `ui/chat/ChatUi.kt` | 33 | 🔒 排除清单（chat-contract 在改圆角） |
| `ui/compose/DhForm.kt` | 4 | 🔒 排除清单 |
| `ui/doll/DollListScreen.kt` | 2 | 🔒 排除清单（且为 D8 圆角） |
| `ui/compose/DhKit.kt` | 5 | 底栏/胶囊几何（D4），见上表 |
| `ui/kit/Components.kt` | 3 | 控件自身几何常量（D6），见上表 |
| `ui/home/BottomNav.kt` | 2 | D3/D4 底栏口径 |
| `pet/DollHomeOverlay.kt` · `ui/doll/DollHomePage.kt` | 各 1 | D5/D6 虚化半径 |
| `ui/chat/ChatListScreen.kt` | 1 | D4 未读徽标 |
| `ui/chat/ChatPanels.kt` | 1 | D6 分段条尺寸 |
| `ui/widget/MiniChart.kt` | 1 | D5 Canvas 内留白 |

### 门禁

`kotlin_precheck` 275 文件未发现问题 · `comment_balance` 全部平衡 · `symbol_refs` exit 0 ·
`ui_audit` 44/44 · `anim_audit` 101/101。**无真机视觉验证**（见 §7.3）。
