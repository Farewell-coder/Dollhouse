# Dollhouse · UI 现状总览

> **这份文档的规则**：每个数字都附一条可直接复制运行的命令。
> 数字会随并行的改动漂移，**以命令输出为准，不要信本文的数值本身**。
>
> 所有命令都在**仓库根目录**执行（即 `Dollhouse/`，其下能看到 `app/`、`tools/`、`docs/`）。

**为什么需要这份文档**：进度散在大量 commit message 里，读不出来；而且此前多次出现过
口头数字与实际不符（例如「flat 0 处」实际 5 处、「SwitchRow 转发 2 次」实际 0 次）。
把每个数字绑到一条命令上，是唯一能防止这类错误复发的办法。

**快照时间**：2026-11-07。仓库正处于多个任务并行修改中，下文数值可能随时变化。

---

## 一、组件收口（ui/kit）

### 1.1 各 kit 组件的使用量

```bash
python3 tools/kit_usage_audit.py
```

当前输出（`K1` 段）：

| 组件 | 直接调用 | 经转发到达 | 实际服务 |
|---|---:|---:|---:|
| DhButton | 0 | 0 | **0** |
| DhField | 1 | 0 | 1 |
| DhRow | 5 | 169 | **174** |
| DhGroup | 2 | 52 | **54** |
| DhChip | 0 | 0 | **0** |
| DhTag | 1 | 0 | 1 |
| DhState | 6 | 0 | 6 |
| DhSkeletonRow | 1 | 0 | 1 |
| DhIconButton | 0 | 0 | **0** |
| DhDivider | 1 | 0 | 1 |
| **合计** | **17** | **221** | **238** |

**两个维度都要看**：只看「直接调用」会严重低估——`DhKit.ValueRow` 被调 44 次，
最终落到 `kit.DhRow`，但这 44 处不会出现在 DhRow 的直接列里。

三个 0 调用组件值得注意：`DhButton` / `DhChip` / `DhIconButton` —— 建好了但页面没在用。

### 1.2 转发链状态（DhKit → kit）

```bash
grep -n "com\.dollhouse\.app\.ui\.kit\.Dh" app/src/main/java/com/dollhouse/app/ui/compose/DhKit.kt | grep -v "^\s*[0-9]*:\s*//" | grep -v "\*"
```

当前输出（6 处转发）：

| DhKit 函数 | 转发到 | 证据 |
|---|---|---|
| `Card` | `DhGroup` | DhKit.kt:330, 344 |
| `ValueRow` | `DhRow` | DhKit.kt:677 |
| `ActionRow` | `DhRow` | DhKit.kt:1316 |
| `SwitchRow` | `DhRow` | DhKit.kt:843 |
| `PermRow` | `DhRow` | DhKit.kt:896 |

**五个函数现已全部转发**（`SwitchRow` / `PermRow` 是最近才接上的）。

脚本的 `FORWARD_CHAIN` 表**已同步为 5 条**（`KNOWN_NOT_FORWARDED` 已清空），
因此上面的「经转发到达 221」已含 `SwitchRow`(34) 与 `PermRow`(4)。
**若今后再给某函数加上转发，务必同步那张表**，否则统计会静默少算。

### 1.3 按钮体系：唯一未收口的大块

```bash
python3 tools/kit_usage_audit.py   # 看 K3c 段
```

| 工厂 | 形态 | 调用点 |
|---|---|---:|
| `DhForm.PrimaryChip` | 999 药丸 / accent 实心 | 18 |
| `DhForm.OutlineChip` | 999 药丸 / card 底 + line 描边 | 15 |
| `DhKit.OutlineChip` | 999 药丸 / cardUp 底 + line 描边 | 17 |
| `DhForm.SubmitButton` | 999 药丸 / **内边距撑高，无固定高度** | 2 |
| `DhForm.Chip` | 999 药丸 / 小号 | 2 |
| **合计** | | **54** |

设计稿要求「Buttons — one height, **44dp**」+ 12dp 圆角；`kit.DhButton` 正是这么实现的，
但它的库外调用是 **0**。实际 54 处按钮全是 999 药丸 + 内边距撑高。

**状态：只量化，未统一。** 统一它们会改变所有页面的点击区与视觉，属独立工程。

---

## 二、设计系统一致性

```bash
python3 tools/design_parity.py
```

当前：**一致 129 / 共 129（100.0%）；有意偏离 5；真偏差 0**（`exit 0`）

| 判据 | 内容 | 一致 / 共 |
|---|---|---|
| D1 | 字号 8 档 × (size/lineHeight/weight) | 24 / 24 |
| D2 | 间距 6 档 | 6 / 6 |
| D3 | 圆角 4 档 | 4 / 4 |
| D4 | 动效时长 4 档 | 4 / 4 |
| D5 | 缓动曲线 3 条 | 3 / 3 |
| D6 | 调色板 29 槽 × 3 档 | 88 / 88（例外 5） |

「有意偏离 5 项」全部是 `line`/`stroke` 三档的刻意加深（设计稿值对比度 1.17–1.28，
低于 1dp 发丝线可辨识下限 1.3）。逐条理由见 `docs/DESIGN_PARITY.md`。

```bash
python3 tools/design_parity.py --list-exceptions   # 看例外表全文
```

---

## 三、状态规范覆盖率

```bash
python3 tools/state_audit.py
```

当前：**已规范 6 处 / 自建 0 处（规范覆盖率 100%）；纯空白态 0 处**（`PASS 5 / FAIL 0`）

```bash
python3 tools/state_audit.py | grep -A8 "已规范"
```

6 处使用 `DhState` 的位置（行号会漂，以命令输出为准）：
`ChatListScreen`、`ContactScreen`、`ProviderDetailPage`、`ProviderListPage`、
`DeveloperOptionsPage`、`MemPage`。

「自建 0 处」意味着此前各自拼空态的 5 处已全部收编到 `DhState`。

---

## 四、交互态（输入框聚焦）

```bash
python3 tools/input_audit.py
```

当前：**PASS 22 / FAIL 0（共 22 项）**（`exit 0`）

判据是 3 个规范入口（`DhField` / `DhForm.Input(LegacyInput)` / `DhForm.InputMultiline`）
× 7 项检查，外加 1 项「全仓是否还有绕过入口的 `BasicTextField`」。

最关键的一项（I3）要求：`BasicTextField` 的 `interactionSource` 必须是
**本函数 `remember` 出来的、且正是被 `collectIsFocusedAsState` 订阅的那个**。
只「传了但传错对象」同样会判失败 —— 这是比「完全不传」更难发现的静默失效。

---

## 五、间距收敛进度（6dp / 10dp 剩余）

这两个值是旧体系遗留（`10dp` 与 `12dp` 曾并存、`6dp` 是手写残留），
统一到 4/8/12/16/24/32 阶梯后应归零。

```bash
awk '!/^[ \t]*(\/\/|\*|\/\*)/ {t=$0; n6+=gsub(/[^0-9.]6\.dp/,"",t); t2=$0; n10+=gsub(/[^0-9.]10\.dp/,"",t2)} END {print "6dp:",n6; print "10dp:",n10; print "合计:",n6+n10}' $(find app/src/main/java -name '*.kt')
```

当前输出：

```
6dp: 106
10dp: 76
合计: 182
```

（该口径**排除注释行**。含注释时数字会略高。）

涉及 40 个文件。占用最多的前几位：

```bash
grep -rcE "[^0-9.](6|10)\.dp" app/src/main/java --include=*.kt | grep -v ":0$" | sort -t: -k2 -rn | head -8
```

**状态：未收敛，182 处。** 这是纯清理工作，不影响功能，但每次改都要重新跑门禁。

---

## 六、View 实例化文件清单

```bash
grep -rlE "LinearLayout\(|FrameLayout\(|TextView\(|RelativeLayout\(|ScrollView\(|ImageView\(" app/src/main/java --include=*.kt | sort
```

当前 **10 个文件**：

| 文件 | 性质 | 说明 |
|---|---|---|
| `ui/chat/ChatPanel.kt` | **页面（待迁移）** | 仍是 `class ChatPanel : LinearLayout(context)`，见第七节 |
| `ui/home/HomeUi.kt` | **页面（待迁移）** | `object HomeUi`，13 处 View 实例化 |
| `ui/provider/ProviderDetailPage.kt` | 页面（壳层） | 3 处 |
| `pet/PetWindow.kt` | 保留（桌宠悬浮窗） | 自绘 View，Compose 不适合 |
| `pet/PetTalkInput.kt` | 保留（悬浮输入条） | 同上 |
| `BackgroundCropActivity.kt` | 保留（裁剪页） | 见第九节 |
| `CoverCropActivity.kt` | 保留（裁剪页） | 见第九节 |
| `ui/compose/LayerShield.kt` | 基础设施 | 层拦截 |
| `ui/theme/UiKit.kt` | **View 侧工厂库** | 15 处实例化，是"工厂"不是"页面" |
| `ui/theme/Icons.kt` | **图标工厂** | 2 处（`ImageView`/`LinearLayout`），同上是工厂 |

**关键区分**：`UiKit.kt` 与 `Icons.kt` 是**共享工厂**（被多个页面调用），
不是"某个页面还是 View"。真正待迁移的页面只有 `ChatPanel` / `HomeUi` / `ProviderDetailPage`。

---

## 七、ChatPanel 规模与剩余风险

```bash
wc -l app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt
grep -cE "^\s*(private |internal |public |protected )?fun " app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt
```

当前：**2317 行**，**70 个函数**（其中 32 个 public / `@JvmStatic`）。

契约文档：`docs/CHATPANEL_CONTRACT.md`（38 KB）。三张表：

```bash
grep -nE "^### 表 " docs/CHATPANEL_CONTRACT.md
```

| 表 | 内容 | 项数 |
|---|---|---:|
| 表 A | 必须保持语义不变 | 30 |
| 表 B | 可改为状态驱动 | 11 |
| 表 C | 可删除（0 调用） | 8 |

剩余迁移批次（逐批可独立验收）：

```bash
grep -nE "^### 批次" docs/CHATPANEL_CONTRACT.md
```

| 批次 | 内容 | 风险 | 预估改动 |
|---|---|---|---|
| 1 | 清死代码 | 0 风险 | ~90 行 |
| 2 | `setMemoBusy` + `flashMemo` 合并 | 低 | ~60 行 |
| 3 | 纯状态壳内联 | 低 | ~40 行 |
| 4 | `history` 所有权从 View 拿出 | **中** | ~300 行 |
| 5 | 网络/工具/收尾链路去 View 化 | **高** | ~400 行 |
| 6 | 去 View 壳（最后一刀，含外部文件） | 未标注 | ~120 行 |

**剩余高/中风险项：2 项**（契约文档明确标注：批次 4 中风险、批次 5 高风险），
合计约 **700 行**改动，共 **6 个批次**。

> 批次 6 在契约文档里**没有标注风险等级**（只写了「最后一刀」），
> 本文不替它补标签。查法：
> ```bash
> grep -E "^### 批次" docs/CHATPANEL_CONTRACT.md | grep -cE "中风险|高风险"   # → 2
> ```

当前仍残留的 View 相关引用：

```bash
grep -cE "addView|removeView|ViewGroup" app/src/main/java/com/dollhouse/app/ui/chat/ChatPanel.kt
```

当前：**3 处**。

---

## 八、9 套门禁

全部在仓库根目录运行。`exit 0` = 通过。

| # | 门禁 | 查什么 | 命令 | 当前 |
|---|---|---|---|---|
| 1 | `ui_audit.sh` | 架构边界 / 令牌 / 主题口径 | `bash tools/ui_audit.sh` | **44/44** |
| 2 | `anim_audit.sh` | 动效内核 / View 与 Compose 动画口径 | `bash tools/anim_audit.sh` | **101/101** |
| 3 | `kotlin_precheck.py` | 未闭合注释 / 缺 import / 括号失衡（替代编译） | `python3 tools/kotlin_precheck.py` | **273 文件，未发现问题** |
| 4 | `comment_balance.py` | 注释定界符配平 | `python3 tools/comment_balance.py` | **273 文件，全部平衡** |
| 5 | `symbol_refs.py` | 悬空引用（删文件后残留定义） | `python3 tools/symbol_refs.py` | **273 文件，无悬空引用** |
| 6 | `palette_contrast.py` | 三档调色板 WCAG 对比度 | `python3 tools/palette_contrast.py` | **三档全部达标** |
| 7 | `input_audit.py` | 输入框聚焦态接线 | `python3 tools/input_audit.py` | **22/22** |
| 8 | `state_audit.py` | 空/加载/错误态规范 | `python3 tools/state_audit.py` | **5/5，覆盖率 100%** |
| 9 | `design_parity.py` | 设计稿 ↔ 代码令牌一致 | `python3 tools/design_parity.py` | **129/129，真偏差 0** |

另有 `kit_usage_audit.py`（组件库使用量与绕过），是第 10 套，
因引入较晚单列：`python3 tools/kit_usage_audit.py` → **通过**（转发链 3/3 按旧表计）。

一次性全跑：

```bash
bash tools/ui_audit.sh --quiet && bash tools/anim_audit.sh --quiet \
 && python3 tools/kotlin_precheck.py --quiet && python3 tools/comment_balance.py --quiet \
 && python3 tools/symbol_refs.py --quiet && python3 tools/palette_contrast.py --quiet \
 && python3 tools/input_audit.py --quiet && python3 tools/state_audit.py --quiet \
 && python3 tools/design_parity.py --quiet && python3 tools/kit_usage_audit.py --quiet
```

### 8.1 `.py` 门禁的版本控制状态（**部分已修，仍有 1 个漏网**）

`.gitignore` 第 28 行是 `*.py`（"本地开发临时脚本"），需要逐个开白名单例外。
此前 8 个门禁脚本**全部 UNTRACKED**；现已加入 7 个（`.gitignore:36-42`）：

```bash
# 列出哪些门禁脚本还没被 git 跟踪
for f in kotlin_precheck comment_balance palette_contrast symbol_refs \
         input_audit state_audit design_parity kit_usage_audit; do
  git ls-files --error-unmatch tools/$f.py >/dev/null 2>&1 \
    && echo "TRACKED   $f" || echo "UNTRACKED $f"
done
```

当前结果：

| 脚本 | 状态 |
|---|---|
| `kotlin_precheck.py` | TRACKED |
| `comment_balance.py` | TRACKED |
| `palette_contrast.py` | TRACKED |
| `symbol_refs.py` | TRACKED |
| `input_audit.py` | TRACKED |
| `state_audit.py` | TRACKED |
| `design_parity.py` | TRACKED |
| **`kit_usage_audit.py`** | **UNTRACKED ← 仍漏** |

**待办**：`.gitignore` 需补一行 `!tools/kit_usage_audit.py`。
否则 clone 下来的人跑不了组件库审计，而它正是防「组件库回退成没人用」的唯一门禁。

```bash
git check-ignore -v tools/kit_usage_audit.py   # 当前会输出 .gitignore:28:*.py
```

（`tools/kit_usage_baseline.json` 是 JSON，不受 `*.py` 影响，但若脚本不随代码走，基线也无意义。）

---

## 九、明确尚未做的

以下均为**未完成**，不要当作已完成：

| 项 | 状态 | 说明 |
|---|---|---|
| **ChatPanel 去 View** | 未做 | 仍是 `class ChatPanel : LinearLayout(context)`，2317 行 / 70 函数。见第七节 6 个批次，剩余约 700 行高风险改动 |
| **HomeUi 去 View** | 未做 | `object HomeUi`，13 处 View 实例化，1565 行 / 63 函数 |
| **ProviderDetailPage 壳层** | 未做 | 3 处 View 实例化 |
| **裁剪页（BackgroundCrop / CoverCrop）** | 未做，明确保留 | 需要 `AndroidView` 兼容或原生活动，Compose 迁移成本高于收益 |
| **按钮体系统一（54 处）** | 未做，已量化 | 999 药丸 → 44dp/12 圆角。会改变所有页面视觉，需先和设计稿确认取舍 |
| **间距收敛（182 处 6dp/10dp）** | 未做 | 纯清理，不影响功能 |
| **真机验收** | 未做 | 本机无编译环境（proot arm64 无 aapt2），所有验证均为**静态检查**，未经真实编译与装机 |
| **`kit_usage_audit.py` 纳入版本控制** | 未做（7/8 已纳入） | 其余 7 个 `.py` 门禁已进 `.gitignore` 白名单，仅它仍 UNTRACKED。见 §8.1 |

### 9.1 关于「未经真机验收」的准确含义

本项目全程**没有跑过 `./gradlew`**，原因见 `tools/kotlin_precheck.py` 头部注释：
本机是 Android 手机上的 proot Ubuntu（arm64），官方 SDK 只发 x86-64 的 aapt2，
`processDebugResources` 必然失败。

因此：
- 所有「通过」都表示**静态规则通过**，不表示能编译、能运行、视觉正确；
- 配色对比度、令牌一致性等**可静态计算**的项，可信度较高；
- 布局、动效手感、点击区等**必须真机**的项，完全未验证。

---

## 十、一页速查（全部经实测）

| 指标 | 值 | 命令 |
|---|---|---|
| .kt 文件数 | 273 | `find app/src/main/java -name "*.kt" \| wc -l` |
| kit 组件直接调用 | 17 | `python3 tools/kit_usage_audit.py` |
| kit 经转发到达 | 221 | 同上 |
| kit 实际服务 | 238 | 同上 |
| 转发链 | 5/5 完整 | 见 §1.2 命令 |
| 设计稿一致率 | 129/129 (100%) | `python3 tools/design_parity.py` |
| 状态规范覆盖率 | 6/6 (100%) | `python3 tools/state_audit.py` |
| 输入框审计 | 22/22 | `python3 tools/input_audit.py` |
| 药丸按钮 | 54 | `python3 tools/kit_usage_audit.py` |
| 6dp + 10dp 剩余 | 182 | 见 §5 awk 命令 |
| View 实例化文件 | 10（其中 2 个是工厂） | 见 §6 命令 |
| ChatPanel | 2317 行 / 70 函数 | 见 §7 命令 |
| 高风险迁移批次 | 2 | `grep -nE "^### 批次" docs/CHATPANEL_CONTRACT.md` |
| 门禁 | 9 套（+1）全绿 | 见 §8 |