# Dollhouse · 整体视觉审计（找"不精致"的具体位置）

> **本文只审计、不改代码。** 目的是回答一个问题：
> 设计系统（`DhDesign` 令牌 + `ui/kit` 组件库）已经建好了，**哪些页面还没用上**。
>
> 参考方向：DSH Go 的原生 Android 风格 —— 模块化布局、圆角、留白、卡片、半透明、
> 统一的颜色/字体/图标/组件/主题、细腻的点击反馈、自然的面板动画、**避免多余动画**。
>
> 每条都附 **grep 命令**，可自行复现。数字快照时间 2026-11-07，会随并行改动漂移，
> **以命令为准**。

## 结论速览

| 项 | 现状 | 严重度 |
|---|---|---|
| 1 模块化分组 | **基本已收口**：`DhKit.Card` 覆盖 12 个页面，`SectionLabel` 覆盖 11 个文件。仅 2 个页面仍是纯 `Column + Spacer` | 低 |
| 2 间距令牌 | **184 处**裸间距（非 4/8/12/16/24/32），41 个文件。Top1 = `ChatUi.kt` 32 处 | **高** |
| 3 圆角令牌 | **7 处**非标圆角（10/14/99/2dp），集中在 3 个文件 | 低 |
| 4 点击反馈 | `pressable` 已内建缩放反馈（113 处）。仅 **1 处**真缺失（菜单项） | 低 |
| 5 半透明/遮罩 | Compose 弹窗宿主窗口背景 **TRANSPARENT 且无遮罩层**；抽屉/长按图层已有 scrim | 中 |
| 6 动画 spec | `animate*AsState` **17/17 全部带 spec**；`AnimatedVisibility` **4/4 全部带**（采样时点不同会有 ±1 浮动，缺 spec 恒为 0） | **已合规** |
| 7 重复实现 | 圆角图标座 **13 种组合 / 15 处**（其中 2 处是 7~8dp 小圆点，非图标座）；裸 fontSize **47 处** | **高** |

---

## 一、模块化分组：基本已收口

### 命令

```bash
# 用了 DhKit.Card 的页面
grep -rc "DhKit.Card(" app/src/main/java --include=*.kt | grep -v ":0" | sort -t: -k2 -rn

# 有页面函数但没用 Card 的文件
for f in $(grep -rlE "fun [A-Z]\w*(Content|Page|Screen)\(" app/src/main/java/com/dollhouse/app/ui --include=*.kt); do
  c=$(grep -c "DhKit.Card(" "$f"); [ "$c" -eq 0 ] && echo "${f#app/src/main/java/com/dollhouse/app/}"
done
```

### 现状：Card 覆盖良好

`DhKit.Card` 使用量 Top：`AppearancePage`(8)、`FriendProfilePage`(6)、`LamdaPage`(5)、
`SettingsScreen`(5)、`DollPersonaPage`(5)、`DollLookTab`(5) 等，共 12 个文件。

`DhKit.SectionLabel`（分组小标题）已覆盖 **11 个文件**：

```bash
grep -rc "DhKit.SectionLabel(" app/src/main/java --include=*.kt | grep -v ":0"
```

### 真正未分组的只有 2 处

| 位置 | 现状 | 应改成 | 影响页面 |
|---|---|---|---|
| `ui/home/ComingSoonPage.kt:49` | 3 个 `Spacer(Modifier.height(...))` 手拼上下节奏，无 Card/SectionLabel | 占位页内容极简，**可保留**；若要统一则套 `DhKit.Card` | 1（说说 Tab 占位） |
| `ui/chat/ChatUi.kt` | 4 个 `Spacer(Modifier.height(...))`，但这些是**聊天气泡间的排版位移**，不是分组 | **不应改**（气泡不是卡片） | — |

### 注：11 个"无 Card"的文件里，多数用其它构件分组

`AboutPage` / `ProviderEditPage` / `ModelEditPage` / `ProviderListPage` 等用的是
`SectionLabel` + `ValueRow` / `ActionRow` / `SwitchRow` 的组合 —— **这是对的**，
表单类页面并不需要每段一个卡片。**不列为问题。**

> **`DhKit.Wrap` 不存在**（`grep -rn "fun Wrap(" app/src/main/java` → 空）。
> 任务描述里提到它，但代码库里没有这个构件。当前分组靠 Card / SectionLabel / Row 三件套。

---

## 二、间距令牌：184 处裸值（**最需要收口**）

### 命令

```bash
python3 - <<'EOF'
import re, os, collections
root='app/src/main/java'
rx=[re.compile(r'\.padding\(\s*[^)]*?(\d+(?:\.\d+)?)\.dp'),
    re.compile(r'Spacer\([^)]*?(\d+(?:\.\d+)?)\.dp'),
    re.compile(r'spacedBy\(\s*(\d+(?:\.\d+)?)\.dp')]
SCALE={4,8,12,16,24,32}
per=collections.Counter()
for d,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(d,f); rel=p.replace(root+'/','')
        for line in open(p,encoding='utf-8',errors='replace'):
            s=line.strip()
            if s.startswith(('//','*','/*')): continue
            for r in rx:
                for m in r.finditer(line):
                    if float(m.group(1)) not in SCALE: per[rel]+=1
print('总计', sum(per.values()), '文件', len(per))
for rel,n in per.most_common(8): print('%4d  %s' % (n, rel))
EOF
```

### 现状：184 处 / 41 个文件

| 序 | 文件 | 数量 | 典型位置与现状 |
|---|---|---:|---|
| 1 | `ui/chat/ChatUi.kt` | **32** | `:208` `.padding(start=6.dp,end=8.dp,top=6.dp,bottom=8.dp)`；`:369` `padding(horizontal=10.dp)` |
| 2 | `ui/compose/DhKit.kt` | **23** | `:432` `padding(top=14.dp,bottom=12.dp)`；`:528` `Spacer(Modifier.width(6.dp))` |
| 3 | `ui/compose/DhForm.kt` | **16** | `:173` `padding(start=2.dp,top=12.dp,bottom=4.dp)`（label 上边距） |
| 4 | `ui/settings/AppearancePage.kt` | 8 | `:111` `Spacer(Modifier.height(10.dp))`；`:179` `padding(horizontal=14.dp,vertical=8.dp)` |
| 5 | `ui/home/ContactScreen.kt` | 8 | 图标座/徽标类紧贴间距 |
| 6 | `ui/doll/DollActionPage.kt` | 7 | `Spacer(Modifier.height(6.dp))` 若干 |
| 7 | `ui/chat/ChatListScreen.kt` | 6 | 行内小间距 |
| 8 | `ui/settings/ModelEditPage.kt` | 6 | 表单字段间距 |

> ⚠ **这一节的数字两次采样不同（257 → 184）**：期间并行任务收敛了
> `AexImportSheet`(原15)、`ImportAssistantKit`(原13)、`DollAnimPage`(原12) 等文件。
> **请以重跑命令为准**，不要引用本文的绝对值。

**出现最多的离群值**：`6dp`、`10dp`、`2dp`、`14dp`。

### 应改成

| 现状值 | 应改成 | 理由 |
|---|---|---|
| `6.dp` | `DhDesign.Space.sm`(8) 或 `.xs`(4) | 6 不在阶梯上；紧邻用 4、一般间隙用 8 |
| `10.dp` | `Space.sm`(8) 或 `.md`(12) | 同上，10 是旧 `DhKit.GAP_ICON` 遗留 |
| `2.dp` | `Space.xs`(4) 或改用 `padding(top=…)` 语义合并 | — |
| `14.dp` | `Space.md`(12) 或 `.lg`(16) | — |

**影响面**：41 个文件 / 184 处。**这是本轮最大的一块收口工作。**
建议分批：先 `ChatUi.kt`(32) 与 `DhKit.kt`(23) 这两个头部，合计已占 21%。

> **注意**：并非所有裸值都该改。`Spacer(Modifier.width(2.dp))` 这类"紧贴"在某些视觉里有
> 刻意用意（如图标与徽标间）。改造前需逐处判断，**不要盲扫替换**。

---

## 三、圆角令牌：7 处非标（**很干净**）

### 命令

```bash
python3 - <<'EOF'
import re, os, collections
root='app/src/main/java'
rx=re.compile(r'RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\s*(?:\.dp)?\s*\)')
CANON={8,12,16,999}
per=collections.Counter()
for d,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(d,f); rel=p.replace(root+'/','')
        for i,line in enumerate(open(p,encoding='utf-8',errors='replace'),1):
            if line.strip().startswith(('//','*')): continue
            for m in rx.finditer(line):
                if float(m.group(1)) not in CANON: per[rel]+=1
print('非标圆角', sum(per.values()))
for rel,n in per.most_common(): print('%2d  %s' % (n, rel))
EOF
```

### 现状：7 处 / 3 个文件

| 文件:行 | 现状 | 应改成 | 影响 |
|---|---|---|---|
| `ui/chat/BlockRenderer.kt:30` | `RoundedCornerShape(10.dp)` 代码块底 | `Radius.sm`(8) | 1（聊天里的代码块） |
| `ui/chat/BlockRenderer.kt:38` | `RoundedCornerShape(14.dp)` 引用图标座 | `Radius.md`(12) | 1 |
| `ui/chat/BlockRenderer.kt:44` | `RoundedCornerShape(99.dp)` 来源 chip | `Radius.pill`(999) | 1 |
| `ui/doll/DollListScreen.kt:961/963` | `RoundedCornerShape(10.dp)` 头像座 + 描边 | `Radius.md`(12) | 1（人偶列表） |
| `ui/doll/DollListScreen.kt:1098` | `RoundedCornerShape(2.dp)` 进度条 | 保留（进度条细圆角合理） | — |
| `ui/chat/ChatUi.kt:374` | `RoundedCornerShape(10.dp)` hint 条 | `Radius.sm`(8) 或 `.md`(12) | 1 |

全部圆角取值分布（供对照）：

```bash
grep -rhoE "RoundedCornerShape\(\s*[0-9]+" app/src/main/java --include=*.kt | sort | uniq -c | sort -rn
# 999×49、12×13、8×9、16×5、10×4、14×1、99×1、2×1
```

---

## 四、点击反馈：仅 1 处真缺失

### 命令

```bash
grep -rn "\.clickable(" app/src/main/java --include=*.kt | grep -v "//"
grep -rc "\.pressable"         app/src/main/java --include=*.kt | awk -F: '{s+=$2} END{print "pressable:", s}'
grep -rc "pressScaleClick"     app/src/main/java --include=*.kt | awk -F: '{s+=$2} END{print "pressScale:", s}'
```

### 现状

- `pressable`：**113 处**
- `pressScaleClick`：11 处
- 裸 `clickable`：**5 处**

**关键事实：`pressable` 已内建按压缩放**（`DhKit.kt` 里 `PRESS_SCALE` + `DhAnim.PRESS_DOWN`/`BOUNCY`），
所以 113 处**都有统一反馈**，不需要再换成 `pressScaleClick`。
`pressScaleClick` 是"不施加 48dp 触摸下限"的变体，用于本身已够大的按钮。

### 5 处裸 clickable 逐个判断

| 文件:行 | 现状 | 判断 | 应改成 |
|---|---|---|---|
| `ui/compose/DhKit.kt:1980` | `clickable(interactionSource, indication=null)` | 在 `pressable` 实现内部 | ✅ 合理 |
| `ui/compose/DhKit.kt:2072` | 同上（`pressScaleClick` 内部） | 同上 | ✅ 合理 |
| `ui/compose/DhKit.kt:2100` | 同上（带长按重载内部） | 同上 | ✅ 合理 |
| `ui/doll/DollListScreen.kt:1006` | 全屏透明层「点空白关闭」 | 透明层**本来就不该有视觉反馈** | ✅ 合理 |
| **`ui/doll/DollListScreen.kt:1061`** | **菜单项 `MenuItem` 用裸 clickable，无缩放** | **真问题**：手写菜单项的点击无按压反馈 | 换 `pressable` 或 `pressScaleClick` |

**影响页面**：1（人偶列表的长按菜单）。

---

## 五、半透明 / 遮罩

### 命令

```bash
# scrim 令牌在 Compose 侧的使用点
grep -rn "c\.scrim\|palette\.scrim" app/src/main/java/com/dollhouse/app/ui --include=*.kt | grep -v DhTheme

# Compose 弹窗宿主的窗口背景
grep -n "setBackgroundDrawable\|ColorDrawable" app/src/main/java/com/dollhouse/app/ui/kit/DhDialogHost.kt
```

### 现状

| 位置 | 现状 | 判断 |
|---|---|---|
| `ProfileDrawer.kt:387` | `.background(c.scrim.copy(alpha = c.scrim.alpha * p))`，跟手透明度 | ✅ **已正确用 scrim** |
| `DollListScreen.kt:732/795` | `scrimColor = c.scrim`（长按图层） | ✅ 正确 |
| `ProfileDrawer.kt:429` | 抽屉面板 `.background(c.sheet)` **实色** | ✅ **刻意**：注释写明「必须垫实底，否则背后列表文字透上来叠成一片」 |
| `DhForm.kt:878/1033` | 弹窗 `.background(c.sheet)` 实色 | ✅ 同上（弹窗职责是盖住） |
| **`DhDialogHost.kt:252-253`** | 窗口背景设 **`Color.TRANSPARENT`**，且 Compose 侧**没有遮罩层** | ⚠️ **待确认**：见下 |

### 需要确认的一处：Compose 弹窗是否有遮罩

```bash
# 宿主里是否有 scrim 层
grep -n "scrim\|Scrim\|Color(0x" app/src/main/java/com/dollhouse/app/ui/kit/DhDialogHost.kt
```

结果：**没有**。`DhDialogHost.show()` 只 `setContentView(ComposeView)` + 窗口背景透明，
**没有画半透明遮罩**。

**这意味着**：`DhDialogHost` 弹出的菜单/确认框背后，页面是**原亮度**的，
没有"压暗一层"的观感。对比 `ProfileDrawer`（有 scrim）与 `DollListScreen` 长按图层（有 scrim），
**弹窗这一路是唯一缺遮罩的**。

| 影响面 | 说明 |
|---|---|
| 约 **62 处** `DhForm.Alert` 调用 + `DhDialogHost.showMenu` 的所有菜单 | 都是 Compose 弹窗，都没遮罩 |

**应改成**：在 `DhDialogHost.show()` 的 `ComposeView` 内容最外层加一层
`Box { Box(Modifier.fillMaxSize().background(palette.scrim)) { content() } }`，
或在 Dialog 的 window 上设 `dimAmount`。

> **注意**：当前"无遮罩"可能是刻意的（避免弹窗太重）。这属**观感取向**，
> 需产品确认后再改，不要盲改。

### 卡片半透明

机制**完整可用**：`UiKit.cardAlpha` → `UiKit.card()` → `DhDesign.Palette.card` → Compose。
用户调「卡片透明度」会影响 `card` / `cardUp` / `level`（`UiKit.kt:408-412`）。
**无需改动。**

---

## 六、动画 spec：**全部合规（0 问题）**

### 命令

```bash
python3 - <<'EOF'
import re, os
root='app/src/main/java'
bad=[]; total=0
for d,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(d,f); rel=p.replace(root+'/','')
        src=open(p,encoding='utf-8',errors='replace').read()
        for m in re.finditer(r'animate\w*AsState\s*\(', src):
            i=m.end()-1; depth=0; j=i
            while j<len(src):
                if src[j]=='(': depth+=1
                elif src[j]==')':
                    depth-=1
                    if depth==0: break
                j+=1
            total+=1
            if 'animationSpec' not in src[i:j+1]:
                bad.append((rel, src[:m.start()].count('\n')+1))
print('animate*AsState 总数', total, '缺 spec', len(bad))
for r,l in bad: print('  ', r, l)
EOF
```

### 结果

```
animate*AsState 总数 17  缺 spec 0
AnimatedVisibility 总数 4  缺 enter 0
```

**全部 `animate*AsState` 都显式给了 `animationSpec`；4 处 `AnimatedVisibility` 全部给了 `enter`/`exit`。**
（总数会随并行改动浮动，**关键指标是"缺 spec = 0"**。）

`DhKit.kt:475` 的折叠卡动画是正面例子：

```kotlin
AnimatedVisibility(
    visible = open,
    enter = expandVertically(animationSpec = DhAnim.CARD_EXPAND, expandFrom = Alignment.Top)
          + fadeIn(animationSpec = DhAnim.CARD_FADE_IN),
    ...
)
```

> **这一项不必派活。** 但要注意：`anim_audit.sh` 的 C14/C15/C16 已在守这条，
> 属已收口项。

---

## 七、重复实现（**需要新建统一构件**）

### 7.1 圆角图标座：13 种组合 / 15 处，无统一构件

**命令**

```bash
python3 - <<'EOF'
import re, os, collections
root='app/src/main/java'
pat=re.compile(r'\.size\(\s*(\d+)\.dp\s*\)[\s\S]{0,120}?\.clip\(\s*RoundedCornerShape\(([^)]+)\)\s*\)[\s\S]{0,160}?\.background\(')
hits=collections.defaultdict(list)
for d,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(d,f); rel=p.replace(root+'/','')
        src=open(p,encoding='utf-8',errors='replace').read()
        for m in pat.finditer(src):
            hits[(m.group(1), m.group(2).strip())].append((rel, src[:m.start()].count('\n')+1))
for k,v in sorted(hits.items(), key=lambda kv:-len(kv[1])):
    print('size=%s radius=%s x%d' % (k[0],k[1],len(v)))
    for r,l in v[:4]: print('    %s:%d' % (r,l))
EOF
```

**现状（13 种组合、15 处；其中 `size=7/8` 是指示点不是图标座，实为 2 处）**

| size | radius | 位置 |
|---:|---|---|
| 72 | 10.dp | `ui/doll/DollListScreen.kt:960` |
| 52 | 999 | `ui/doll/DollHomePage.kt:205`、`DollRangePage.kt:232` |
| 48 | 8.dp | `ui/doll/DollActionPage.kt:880` |
| 46 | 999 | `ui/home/ContactScreen.kt:275` |
| 46 | `Radius.md` | `ui/chat/ChatPanels.kt:318` |
| 40 | 12.dp | `ui/settings/AboutPage.kt:409` |
| 38 | 999 | `ui/provider/ProviderListPage.kt:292` |
| 34 | 999 | `ui/doll/DollGroupPage.kt:326`、`DhKit.kt:996` |
| 32 | 999 | `ui/chat/ChatUi.kt:261` |
| 26 | `R_ROW` | `ui/settings/AppearancePage.kt:577` |
| 20 | `R_ROW` | `ui/doll/DollLookTab.kt:287` |

**问题**：同一个「圆角方块 + 图标」的视觉，13 处各自实现，尺寸无规律（20/26/32/34/38/40/46/48/52/72）。
**应改成**：新建 `DhKit.IconSeat(size: Dp, shape: Shape, tint)`，统一 3 档尺寸
（`sm` 28 / `md` 40 / `lg` 56）+ 2 种形状（圆角 8-12 / 药丸）。

**影响**：11 个文件、15 处（有效图标座 13 处）。

### 7.2 裸 fontSize：47 处

**命令**

```bash
grep -rn "fontSize = [0-9]" app/src/main/java --include=*.kt | grep -vE "UiKit\.FS|DhDesign|DhTokens\.type" | wc -l
```

**现状**：47 处。典型：

| 文件:行 | 现状 | 应改成 |
|---|---|---|
| `ui/chat/ChatListScreen.kt:495` | `fontSize = 15.sp, lineHeight = 24.sp` | `DhDesign.Type.body`（15/24 ✓ 值对，应走令牌） |
| `ui/chat/ChatListScreen.kt:510` | `fontSize = 13.sp, lineHeight = 19.sp` | `Type.sub`（13/**20**）—— **行高差 1sp** |
| `ui/chat/ChatListScreen.kt:521/582` | `11.sp / 16.sp` | `Type.label` ✓ 值对 |
| `ui/chat/ChatUi.kt:285` | `16.sp / 24.sp` | `Type.section`(16/**22**) —— **行高差 2sp** |
| `ui/chat/FriendProfilePage.kt:369` | `20.sp / 29.sp` | `Type.title`(20/**28**) —— **行高差 1sp** |
| `ui/settings/DeveloperOptionsPage.kt:155` | `28.sp / 36.sp` | `Type.display` ✓ 值对 |
| `ui/chat/BlockRenderer.kt:30` | `12.sp / 18.sp` + `Monospace` | `Type.caption`(12/**17**) |

**重点不是"数值对不对"，而是"没走令牌"**：
一旦 `DesignSystem` 调整某档行高，这 47 处**不会跟着变**，页面间会渐渐分叉。
其中 `13sp/19sp`、`16sp/24sp`、`20sp/29sp` 三处行高已经与令牌**不一致**了。

**影响**：约 15 个文件、47 处。

### 7.3 其它重复

| 视觉 | 重复处数 | 说明 |
|---|---|---|
| 「分组小标题」 | `DhKit.SectionLabel` 已统一（11 文件） | ✅ 已收口 |
| `spacedBy(8.dp)` | 13 处 | 8dp 是合法值，但未走 `Space.sm`；统一后可一处调 |
| 卡片间距 | `DhKit.GAP_CARD` 已被 `Card(spacingBottom)` 内建 | ✅ 已收口 |

---

## 八、汇总：建议派活的优先级

| 优先级 | 事项 | 量 | 影响 | 风险 |
|---|---|---|---|---|
| **P0** | 裸 `fontSize` 47 处 → `DhDesign.Type` | 15 文件 | 字阶统一，后续改令牌能全局生效 | 低（纯替换，值需逐处核对） |
| **P1** | 新建 `IconSeat` + 替换 13 处图标座（共 15 个匹配点，2 个是圆点不适用） | 11 文件 | 圆角方块视觉统一 | 低 |
| **P2** | 裸间距 184 处 → `Space` | 41 文件 | 留白节奏统一（本轮最大工作量） | **中**（需逐处判断，6/10dp 有些是刻意紧贴） |
| **P3** | 圆角 7 处 → `Radius` | 3 文件 | 视觉细节 | 低 |
| **P4** | `DollListScreen:1061` 菜单项补按压反馈 | 1 处 | 点击手感一致 | 低 |
| **待定** | Compose 弹窗是否加遮罩（62 处 Alert 受影响） | 1 个宿主 | 浮层纵深感 | **需产品确认**（可能是刻意无遮罩） |
| — | 动画 spec | — | **已全部合规，无需派活** | — |

---

## 九、审计本身的边界

1. **只做静态文本分析**，不能证明视觉效果。每处"应改成"都是基于令牌规范，
   实际改完仍需真机确认（见 `docs/DEVICE_QA.md`）。
2. **不判断设计意图**。例如 `Spacer(2.dp)` 可能是刻意紧贴，`10dp` 可能是刻意比 8 稍松。
   改造前应逐处核对，**不要用脚本盲扫替换**。
3. **数字会漂移**。仓库正在多任务并行修改，重跑命令以当下输出为准。
4. 本审计**未覆盖**：颜色对比度（已有 `palette_contrast.py`）、
   组件是否被绕过（已有 `kit_usage_audit.py`）、页面间边距一致性（已有 `page_consistency_audit.py`）。
   这三项已有专门脚本，不重复。