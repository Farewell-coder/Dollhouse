# 药丸按钮 54 处语义分类审计

> 任务：task-25
> 设计稿依据：`design-studio/dollhouse-refresh/design_prompts_forcoders.md`
> - §3 **Buttons** — one height, 44dp (sm 34)。Primary = `accent` fill + `onAcc` text。
> - §2.1 `--r12: 12`（button, input, weak card, B rows）
> - §2.1 `--rPill: 999`（chip, switch, badge, avatar-round）
> - §3 **Chips vs Tags** — Chip (26dp, pill) filters/selects and is pressable。Tag (20dp, radius 8) labels state。

## 1. 结论先说

**在这 54 处之内：**

| 判定 | 处数 |
|---|---|
| **button**（应 44dp + 12 圆角） | 40 |
| **chip**（999 正确，保持不动） | 13 |
| **待产品确认**（模糊，按 chip 处理） | 1 |
| 合计 | **54** |

**加上审计漏掉的第 6 个工厂 `DhForm.DialogButton`（13 处，全部 button）：**

| 判定 | 处数 |
|---|---|
| **button** | **53**（40 + 13） |
| **chip** | **13** |
| **待产品确认** | **1** |
| 合计 | **67** |

**实际改动：只在工厂内部迁移 `DhForm.DialogButton`（13 处调用自动受益）**，
其余 38 处判为 button 的调用点**没有改**，
原因是它们全部与 chip 调用点共享同一个工厂函数体 —— 详见 §4「结构性阻碍」。
这是本任务最重要的结论：**不能按工厂一刀切，改动只能在工厂内部做**。

## 2. 六个工厂的实况（比任务书多一个）

任务书列了 5 个工厂。实测还有第 6 个：`DhForm.DialogButton`（13 处调用），
`kit_usage_audit.py` 的 `BUTTON_FACTORIES` 表没有收录它，所以 54 这个数字**本身是漏的**。

| 工厂 | 形态 | 调用点 | button | chip | 模糊 |
|---|---|---|---|---|---|
| `DhForm.PrimaryChip` | 999 / acc 实心 / pad 16×9 | 18 | 14 | 3 | 1 |
| `DhForm.OutlineChip` | 999 / card 底 + line 描边 | 15 | 12 | 3 | 0 |
| `DhKit.OutlineChip` | 999 / cardUp 底 + line 描边 | 17 | 12 | 5 | 0 |
| `DhForm.SubmitButton` | 999 → **已由 Lead 改成 44dp+12** | 2 | 2 | 0 | 0 |
| `DhForm.Chip` | **R_ROW=12（不是 999）** / 小号 | 2 | 0 | 2 | 0 |
| `DhForm.DialogButton` | 999 / 弹窗主次操作 | 13 | **13（全部）** | 0 | 0 |
| 合计 | | **67** | **53** | **13** | **1** |

> 注：`DhForm.Chip` 的审计描述「999 药丸 / 小号」是**过期的**。
> 实测 `DhForm.kt:530` 用的是 `RoundedCornerShape(DhKit.R_ROW.dp)` = 12，本来就是合规圆角。

## 3. 逐条判定表

### 3.1 `DhForm.DialogButton` —— 13 处，全部 button ✅ 已迁移

弹窗的主/次操作，无一例外是「确认 / 取消 / 删除 / 恢复 / 关闭」。

| 位置 | 文案 | 语义 | 处置 |
|---|---|---|---|
| `ui/doll/DollCardIO.kt:120` | 取消 | 弹窗取消 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:115` | 删除 | 弹窗破坏性操作 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:126` | 保存 | 弹窗主提交 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:212` | 删除 | 弹窗破坏性操作 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:223` | 保存 | 弹窗主提交 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:309` | 删除 | 弹窗破坏性操作 | ✅ 随工厂迁移 |
| `ui/doll/DollPersonaDialogs.kt:320` | 保存 | 弹窗主提交 | ✅ 随工厂迁移 |
| `ui/doll/DollNumDialog.kt:135` | 取消 | 弹窗取消 | ✅ 随工厂迁移 |
| `ui/doll/DollNumDialog.kt:137` | 恢复 | 弹窗重置 | ✅ 随工厂迁移 |
| `ui/doll/DollNumDialog.kt:143` | 保存 | 弹窗主提交 | ✅ 随工厂迁移 |
| `ui/doll/DollPromptPreview.kt:83` | 关闭 | 弹窗关闭 | ✅ 随工厂迁移 |
| `ui/compose/DhForm.kt:793` | negText（Alert 内置） | 弹窗负向 | ✅ 随工厂迁移 |
| `ui/compose/DhForm.kt:805` | posText（Alert 内置） | 弹窗正向 | ✅ 随工厂迁移 |

### 3.2 `DhForm.PrimaryChip` —— 18 处：14 button / 3 chip / 1 模糊

| 位置 | 文案 | 语义 | 判定 | 处置 |
|---|---|---|---|---|
| `ui/settings/ModelEditPage.kt:231` | 保存 | 表单主提交 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:295` | 知道了 | 结果确认 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:446` | 解密并预览 | 主操作 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:541` | 解密并预览 | 主操作 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:941` | 去查看 | 跳转主操作 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:1210` | text（ModeChip 选中） | **导入模式选择** | **chip** | 保持 999 ✅ |
| `ui/widget/AkcpImportSheet.kt:160` | 知道了 | 结果确认 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:232` | 解密并预览 | 主操作 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:373` | 导入 | 主提交 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:393` | 好 | 结果确认 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:426` | text（ModeChip 选中） | **导入模式选择** | **chip** | 保持 999 ✅ |
| `ui/widget/ExportSheet.kt:126` | 导出中…/导出 | 主提交 | button | ⏸ 未改（§4） |
| `ui/widget/ExportSheet.kt:152` | 好 | 结果确认 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:322` | 用它 | 就地设默认模型 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:410` | 保存中…/保存 | 表单主提交 | button | ⏸ 未改（§4） |
| `ui/doll/DollAnimPage.kt:1183` | 导出这只人偶为模型包 | 弹窗主操作 | button | ⏸ 未改（§4） |
| `ui/doll/DollListScreen.kt:687` | 恢复自动适配 | **既有设定重置动作** | **待产品确认** | 按 chip 不动 ⚠️ |
| `app/ai/TokenStat.kt:427` | names[i]（每日/每周/累计） | **分段选择器** | **chip** | 保持 999 ✅ |

### 3.3 `DhForm.OutlineChip` —— 15 处：12 button / 3 chip

| 位置 | 文案 | 语义 | 判定 | 处置 |
|---|---|---|---|---|
| `ui/settings/ModelEditPage.kt:229` | 取消 | 表单取消 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:444` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:539` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:895` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:948` | 留在此页 | 次操作 | button | ⏸ 未改（§4） |
| `ui/widget/AexImportSheet.kt:1212` | text（ModeChip 未选） | **导入模式选择** | **chip** | 保持 999 ✅ |
| `ui/widget/AkcpImportSheet.kt:230` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:371` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/widget/AkcpImportSheet.kt:428` | text（ModeChip 未选） | **导入模式选择** | **chip** | 保持 999 ✅ |
| `ui/widget/ExportSheet.kt:124` | 取消 | 弹窗取消 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:345` | 看全部 | 次操作跳转 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:361` | 自己去看 | 次操作跳转 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:378` | 返回列表/取消 | 次级返回 | button | ⏸ 未改（§4） |
| `ui/provider/ProviderEditPage.kt:380` | 测试连接 | 次操作 | button | ⏸ 未改（§4） |
| `app/ai/TokenStat.kt:429` | names[i]（每日/每周/累计） | **分段选择器** | **chip** | 保持 999 ✅ |

### 3.4 `DhKit.OutlineChip` —— 17 处：12 button / 5 chip

| 位置 | 文案 | 语义 | 判定 | 处置 |
|---|---|---|---|---|
| `ui/chat/ChatListScreen.kt:265` | 全部 | **筛选器** | **chip** | 保持 999 ✅ |
| `ui/chat/ChatListScreen.kt:266` | 未读 | **筛选器** | **chip** | 保持 999 ✅ |
| `ui/chat/ChatListScreen.kt:267` | 置顶 | **筛选器** | **chip** | 保持 999 ✅ |
| `ui/chat/ChatListScreen.kt:269` | 归档/返回 | **视图切换** | **chip** | 保持 999 ✅ |
| `ui/chat/ConversationHistoryPage.kt:64` | 新建会话 | 主操作 | button | ⏸ 未改（§4） |
| `ui/chat/ConversationHistoryPage.kt:76` | 重命名 | 行内动作 | button | ⏸ 未改（§4） |
| `ui/chat/ConversationHistoryPage.kt:78` | 删除 | 行内破坏性动作 | button | ⏸ 未改（§4） |
| `ui/settings/MemPage.kt:745` | 删除这条关系 | 破坏性动作 | button | ⏸ 未改（§4） |
| `ui/settings/MemPage.kt:1041` | 选择 | 打开选择器 | button | ⏸ 未改（§4） |
| `ui/settings/AboutPage.kt:457` | value（**无 onClick**） | **纯状态标签** | **chip/tag** | 保持 999 ✅ |
| `ui/settings/AppearancePage.kt:586` | 恢复跟随主题 | 重置动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollAnimPage.kt:526` | 露出更多 | 微调动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollAnimPage.kt:531` | 露出更少 | 微调动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollAnimPage.kt:536` | 恢复默认 | 重置动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollAnimPage.kt:572` | 居中 | 重置动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollGroupPage.kt:143` | 重命名 | 行内动作 | button | ⏸ 未改（§4） |
| `ui/doll/DollGroupPage.kt:148` | 删除分组 | 破坏性动作 | button | ⏸ 未改（§4） |

### 3.5 `DhForm.Chip` —— 2 处，全部 chip

| 位置 | 文案 | 语义 | 判定 | 处置 |
|---|---|---|---|---|
| `app/ai/TokenStat.kt:535` | 请求 N 次 | 统计标签 | chip | 保持 ✅（圆角已是 12） |
| `app/ai/TokenStat.kt:537` | ￥N / 百万 | 可点价格标签 | chip | 保持 ✅（圆角已是 12） |

### 3.6 `DhForm.SubmitButton` —— 2 处，全部 button，已由 Lead 迁移

| 位置 | 文案 | 语义 | 判定 | 处置 |
|---|---|---|---|---|
| `ui/settings/ModelListPage.kt:119` | 全部加入 (N) | 批量主提交 | button | ✅ 已是 44dp+12 |
| `ui/widget/AexImportSheet.kt:900` | 导入 N 条 | 主提交 | button | ✅ 已是 44dp+12 |

## 4. 结构性阻碍：为什么 40 处 button 没有改

这是本次审计**最关键**的发现，也是「不能一刀切」的实证理由。

### 4.1 问题

5 个旧工厂里有 3 个**内部同时装着 chip 与 button 两种调用点**，共用同一个函数体：

```
DhForm.PrimaryChip(18 处)
  ├── button 14 处（保存 / 导入 / 导出 / 知道了 / 用它 …）
  └── chip    3 处（ModeChip 选中 ×2、TokenStat 分段 ×1）   ← 同一函数体

DhForm.OutlineChip(15 处)
  ├── button 12 处
  └── chip    3 处（ModeChip 未选 ×2、TokenStat 分段 ×1）   ← 同一函数体

DhKit.OutlineChip(17 处)
  ├── button 12 处
  └── chip    5 处（ChatListScreen 筛选 4、AboutPage 标签 1）← 同一函数体
```

### 4.2 为什么不能改工厂（硬约束）

这些工厂的签名是 `(text, modifier, iconRes, onClick)` —— **没有语义参数**。
工厂内部无法区分「这次调用是筛选器还是提交按钮」。因此：

- 若把工厂改成 44dp+12 → 11 处真 chip 被误伤成按钮，**违反设计稿**（chip 必须 999 且 26dp）。
- 若给工厂加 `asButton: Boolean` 参数 → **跳过**：任务硬约束明确「不改签名、不改调用点」。

### 4.3 为什么也不能改调用点

任务硬约束：「不改调用点、不改签名」。
要让这 40 处 button 真正变成 44dp+12，唯一合规路径是**给每个调用点改用独立按钮工厂**
（例如 `DhForm.DialogButton` 的通用化版本，或 `kit.DhButton`）。这需要：
1. 新增工厂（改签名面）；
2. 逐处改 40 个调用点（改调用点面）。

两者都被本任务的硬约束明确禁止。**所以本任务只能做到「分类 + 迁移那个唯一可整体迁移的工厂」。**

### 4.4 `DhKit.OutlineChip` 特别说明

任务允许我改 `DhKit.kt` 的 OutlineChip 部分。但我**没有改**，理由同上：
它 17 处里有 5 处是真 chip（含 4 个页面级筛选器、1 个无 onClick 的纯标签）。
改它会直接违反设计稿「Chip (26dp, pill) filters/selects」这一条。

> 另一个队友正在改同文件的 SwitchRow/PermRow，我也刻意避开了那部分 —— 见 `git diff` 只有他们的改动。

## 5. 本次实际改动

只改 `ui/compose/DhForm.kt` 的 `DialogButton`（1 个函数体，13 处调用自动受益）。

| 项 | 改造前 | 改造后 |
|---|---|---|
| 圆角 | `RoundedCornerShape(999.dp)` 药丸 | `DhDesign.Radius.md` = **12** |
| 高度 | 内边距撑高（约 36dp，随字号浮动） | `.height(44.dp)` 定高 |
| 字号 | `UiKit.FS_CHIP` = 12sp | `DhDesign.Type.fallback().body` = **15sp** |
| 字重（主） | `Bold` | **`SemiBold`**（对齐设计稿 600） |
| 字重（次） | `Normal` | `Normal`（不变） |
| 左右内边距 | 16dp | 20dp（与 `kit.DhButton` 同档） |
| 点击反馈 | `pressable`（内置 48dp 下限，会把 44 撑到 48） | `pressScaleClick`（与 `kit.DhButton` 一致） |
| 颜色 / 描边 | acc / sheetUp + line | **完全不变** |
| 签名 / 调用点 | — | **零改动** |

### 为什么要换 `pressable` → `pressScaleClick`

`pressable`（`DhKit.kt:2037`）内部调了 `minTouchTarget()`，那是 `sizeIn(minWidth=48, minHeight=48)`。
它会把 `.height(44.dp)` 的**布局高度撑到 48**，于是「统一 44dp」名存实亡。
这正是 `DhKit.OutlineChip` 注释里记过的同一个坑（v0.2.5 修过）。
`kit.DhButton`（`Components.kt:185`）用的就是 `pressScaleClick`，这里与它完全一致。
弹窗按钮自带可见轮廓且高 44dp，命中区足够，不需要额外 48dp 透明补丁。

## 6. 刻意未改的清单（明确声明）

以下**一行都没动**，且是有意为之：

| 类别 | 处数 | 理由 |
|---|---|---|
| 真 chip（筛选 / 选择 / 标签，999 形态） | 11 | 999 是设计稿对 chip 的规定，改了才是错 |
| `DhForm.Chip` 的 2 处 chip | 2 | 圆角本来就是 12（审计描述过期），无需改 |
| 待产品确认 | 1 | `DollListScreen:687`「恢复自动适配」介于「重置动作」与「设定切换」之间 |
| button 但与 chip 共用工具体 | 38 | §4 结构性阻碍；需先加工厂 + 改调用点，均被硬约束禁止 |
| `DhKit.kt` OutlineChip | — | 17 处含 5 处真 chip，改它违反设计稿（且同文件有队友在改 SwitchRow） |
| `DhForm.Chip` | 2 | 圆角本来就是 12（审计描述过期），无需改 |

> 「恢复自动适配」为什么算模糊：它点了会把尺寸/位置重置回默认值 —— 像动作；
> 但它同时是「自动/手动适配」这个二态设定的切换 —— 像 chip。
> 按任务规则「模糊一律按 chip 处理，不要动」，保持 999 并标注待确认。

## 7. 验证

```
python3 tools/kotlin_precheck.py    → 检查 275 个文件：未发现问题
python3 tools/comment_balance.py    → 检查 275 个文件：全部平衡
bash tools/ui_audit.sh              → 总计 44 项：PASS 44 / FAIL 0
bash tools/anim_audit.sh            → 总计 101 项：PASS 101 / FAIL 0
```

`kit_usage_audit.py` 的 K3c「药丸按钮数」前后对比：

| 指标 | 前 | 后 |
|---|---|---|
| `DhForm.kt` 内 `RoundedCornerShape(999)` | 6 | **4** |
| 全仓 `RoundedCornerShape(999)` | 51 | **49** |
| K3c「合计（药丸按钮）」 | 54 | **54（数字未变，见下）** |

**为什么 K3c 的 54 没降**：该脚本统计的是**工厂的调用点数**（`BUTTON_FACTORIES` 表），
不是「这些工厂内部是不是 999」。`DialogButton` 既不在它的表里，改内部实现也不影响计数。
这是脚本的度量口径问题，**不是改动没生效** —— 全仓 999 字面量从 51 降到 49 是实证。
建议后续把 `DhForm.DialogButton` 补进 `BUTTON_FACTORIES`，并给 K3c 增加一条
「工厂函数体内是否仍为 999」的断言（当前只数调用点，无法反映形态是否已收编）。

## 8. 建议的后续（超出本任务硬约束，需产品/Lead 决定）

1. **新增语义化工厂**：`DhForm.Button(text, onClick)`（44dp+12+15sp，通用主按钮）
   与 `DhForm.SecondaryButton(...)`，然后逐处迁移 §3.2/3.3/3.4 里那 38 处 button 调用点。
   这是唯一合规路径（先加工厂，再改调用点，两步都必须被授权）。
2. **补 K3c 断言**：见 §7，让门禁能真实反映「药丸是否已收编」。
3. **`DhForm.Chip` 描述修正**：`kit_usage_audit.py:428` 的「999 药丸 / 小号」与实际不符（实为 12）。
4. **待确认 1 处**：`DollListScreen:687`「恢复自动适配」的 chip / button 归属。

---

**改动文件**：`app/src/main/java/com/dollhouse/app/ui/compose/DhForm.kt`（仅 `DialogButton` 一个函数体）
**未提交**：按要求不 commit / 不 push / 不触发 CI。