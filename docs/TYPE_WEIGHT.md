# 字重层级规则（Type Weight Scale）

- 触发：用户反馈「改了排布但不精致」
- 诊断：`FontWeight.Bold` **57 处**、`fontsBold` **76 处** —— 其中 **54 处是两者叠加**。
  「什么都在喊」= 没有主次 = 不精致。
- 本文档定义规则；`docs/VISUAL_AUDIT.md` 记录偏差清单。

---

## 0. 规则一句话

**Bold 是稀缺资源：整屏只留给 1~2 处最需要压重的东西。**
其余层级靠「字号 + 颜色」拉开，不靠字重。

---

## 1. 五级字重职责表

| 层级 | 字重 | 用在 | 不允许用在 |
|---|---|---|---|
| **品牌/关键数字** | `Bold` | App 名、人偶名（顶栏）、统计大数字 | 正文、说明、列表项标题 |
| **页面/卡片标题** | `SemiBold` | 页面顶栏标题、卡片标题、分组标题（`Type.section` / `Type.title`） | 正文 |
| **强调值** | `Medium` 或 `SemiBold` | 列表右侧的值、状态文案、选中态 | 大段说明 |
| **正文/标签** | `Normal` | 消息正文、说明、时间戳、计数、人名、次级信息 | 标题 |
| **禁用/弱化** | `Normal` + 降透明度 | `enabled = false` | — |

### 关键判据

判断某处该不该 `Bold`，只问一句：
> **这一行如果去掉粗体，用户还能一眼找到它吗？**
> 能 → 说明它靠位置/字号就够了，**不该 Bold**。
> 不能 → 才配 Bold。

---

## 2. 三个必须避免的形态

### ① 双重加粗（最严重）
```kotlin
fontFamily = DhTokens.fontsBold,     // 粗体字族
fontWeight = FontWeight.Bold          // 再叠一层人工加粗
```
`fontsBold` 本身已是 Bold 字重，再叠 `FontWeight.Bold` 在很多字体上是**伪粗体**
（合成加粗，笔画粘连、发糊）—— 这是「不精致」最直接的来源。

**规则：二者**只能取一**。**
- 用 `fontsBold` + **不写** `fontWeight`（或写 `Normal` 让它继承字族本身）
- 或 用 `fonts` + `fontWeight = SemiBold`

> 实测本仓 **54 处** 是叠加态。

### ② 正文/次级信息用 Bold
人名、记忆标题、帧计数、时间标签这类**功能性标签**，Bold 会让整屏全是重音。

### ③ 用 Bold 表达"选中"
```kotlin
fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
```
选中态应靠**底色/颜色/指示条**表达，字重变化会让行宽抖动（已有 tnum 修过宽度抖动，
字重抖动是同一类问题）。选中态改用 `SemiBold`（差异更克制）或纯颜色。

---

## 3. 本仓落地计划（分批）

| 批次 | 范围 | 预期 Bold 数 |
|---|---|---|
| 基线 | — | 57 |
| 批 1 | 正文/标签类（人名、计数、时间、记忆标题、说明）→ `Normal`，去掉叠加 | ~35 |
| 批 2 | 选中态 `if (selected) Bold` → `SemiBold` | ~25 |
| 批 3 | 卡片/页面标题 Bold → `SemiBold` | ~15 |
| 批 4 | 复核：仅保留品牌名/关键数字 | 目标 **≤8** |

**风险控制**：每批跑 `precheck` / `comment_balance` / `ui_audit` / `anim_audit`；
观感类改动不 commit、留给 Lead 统一验。

---

## 4. 已识别的「必须保留 Bold」清单

| 位置 | 理由 |
|---|---|
| `ChatUi.kt:288` 人偶名 | 聊天页顶栏的产品/角色身份，是唯一需要压重的标题 |
| `TokenStat.kt:513/515` 大数字（30sp） | 关键数字，Bold 是唯一手段（字号已大，去掉粗体会散掉） |
| `ProfileDrawer.kt:619` 昵称 | 抽屉身份区，与头像同层级 |
| `DhKit.kt` 内部标题构件 | 构件自身的标题语义（`CardTitle` 等），是"标题"层 |