#!/usr/bin/env python3
"""
Dollhouse · 组件库使用量与绕过审计（防 ui/kit 回退成"没人用的库"）

【为什么需要这个脚本】
  本项目最大的一次返工是：`ui/kit` 组件库建好了，但 8 个组件的**库外调用数全是 0**——
  规范立在那儿，页面一个都没用。后来靠 `DhKit` 的「转发」机制才把 260 处调用接上：
  页面的 `DhKit.ValueRow` 内部调 `ui/kit.DhRow`，样式只有一份。

  这个机制有一个致命弱点：**转发是"软"的**。
  如果哪天有人嫌麻烦，把 `DhKit.ValueRow` 里的 `kit.DhRow(...)` 换回自己画的 Row，
  代码照样能编译、界面看起来差不多、测试全绿 —— 但规范就悄悄失效了。
  现有门禁没有一项能发现这件事：
    · `ui_audit.sh` 的 B 组只检查**定义存在**（`grep -q 'fun DhButton('`），
      不检查"有没有人调"、"转发是不是还在"；
    · `input_audit.py` / `state_audit.py` 查的是各自领域的实现细节。

  本脚本补的正是这个缺口，并且**把当前数字钉成基线**：
  调用数下降或转发链断裂 → 非零退出。这样"回退"会在下一轮立刻暴露。

【四项判据】
  K1 使用量：10 个 kit 组件的库外调用数（与基线比对，下降即失败）
  K2 转发链：候选转发函数内部必须仍有对 kit 组件的调用（断裂即失败）
  K3 绕过：自建 kit 已有能力的代码（按钮 / 开关 / 输入框 —— 后两者引用既有脚本结论）
  K4 复用声明：确认没有重复实现 input_audit / state_audit 已覆盖的检测

【与既有脚本的分工：引用而非重写】
  · 输入框绕过 → 直接调用 `tools/input_audit.py --quiet`，读它的 I6 结论；
  · 空态骨架 → 直接调用 `tools/state_audit.py --quiet`，读它的 S3/S4 结论。
  两者都用 subprocess 调用（不 import），原因：
    · 这两个脚本原本是「模块级就执行」的写法（顶层 sys.argv 求值 + 全局 results），
      直接 import 会污染本进程的 argv 与全局状态，且它们的 main() 会跑一遍；
    · subprocess 还能同时验证"那两个脚本自己是否健康"，一举两得。
  代价是多起两个解释器（毫秒级），值得。

【用法】
  python3 tools/kit_usage_audit.py              # 完整表格 + 清单
  python3 tools/kit_usage_audit.py --quiet      # 只打结论
  python3 tools/kit_usage_audit.py --update-baseline   # 基线下降后，确认要接受时更新
退出码：0 = 无回退；1 = 有回退 / 绕过；2 = 数据源缺失。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
KIT_DIR = SRC / "com/dollhouse/app/ui/kit"
DHKIT = SRC / "com/dollhouse/app/ui/compose/DhKit.kt"
BASELINE_FILE = Path("tools/kit_usage_baseline.json")

QUIET = "--quiet" in sys.argv
UPDATE = "--update-baseline" in sys.argv

# K1：被追踪的 kit 组件（顺序即表格顺序）
KIT_COMPONENTS = [
    "DhButton", "DhField", "DhRow", "DhGroup", "DhChip",
    "DhTag", "DhState", "DhSkeletonRow", "DhIconButton", "DhDivider",
]

# K2：应当转发到 kit 的 DhKit 函数 → 必须出现的全限定 kit 调用
#
# 【为什么用全限定名而非 import 短名】
#   实测 DhKit.kt **没有** import ui/kit，转发点写的是全限定名：
#     com.dollhouse.app.ui.kit.DhRow(
#     com.dollhouse.app.ui.kit.DhGroup(
#   若按任务描述写 `grep 'kit.DhXxx'`，会因为源码里实际是
#   `ui.kit.DhRow`（中间有点）而漏判 —— 这正是"判据与代码脱节"的典型。
#   这里同时接受两种写法（全限定 / 短名），并在报告中说明实际命中的是哪一种。
#
# 【候选函数与实测结果】
#   Card       → DhGroup ✓
#   ValueRow   → DhRow   ✓
#   ActionRow  → DhRow   ✓
#   SwitchRow  → DhRow   ✓（此前登记为"未转发"，已补齐：Switch 进 trailing 槽）
#   PermRow    → DhRow   ✓（此前登记为"未转发"，已补齐：状态文字+图标进 trailing 槽）
#   StepperRow → DhRow   ✓（此前未登记，已补齐：减号/数值/加号三件进 trailing 槽）
#   六个候选现在**全部**在 FORWARD_CHAIN 里 —— 转发链 6/6。
#
# 【StepperRow 为什么也必须登记，而不是"转发完就算了"】
#   本脚本存在的全部意义就是**防回退**：不登记的转发等于没被守护。
#   若哪天有人把 StepperRow 的 `kit.DhRow(...)` 改回自己画 Row + soft 底，
#   代码照样能编译、界面看起来"差不多"、其它门禁全绿，规范就悄悄失效了 ——
#   而 K2 是唯一能发现这件事的检查。登记它，才会在那一刻报"转发链断裂"。
#
# 【StepperRow 当初为何曾被判断"语义对不上"】
#   一度以为它的右尾是"减号 / 数值 / 加号"三件套 + 两个方向的回调，
#   与 DhRow「单个 onClick + 单个 trailing」对不上，硬塞等于假装成一件东西。
#   复核后发现这个前提是错的：`DhRow.trailing` 的形参类型本来就是
#   `@Composable RowScope.() -> Unit` —— 接收器是 RowScope，天然可放任意多件，
#   且 `Modifier.width` 在 RowScope 内正常工作。PermRow 早就把两件放进同一槽作先例。
#   所以**不需要给 DhRow 加任何参数**，直接复用现有槽即可。
#
# 【为什么 SwitchRow / PermRow 曾长期登记在 KNOWN_NOT_FORWARDED】
#   它们是**从未转发过**的历史实现（各自画 Row + Text + Switch/Icon），
#   与"从转发版改回复制"（回退）必须区分：前者是技术债，后者才是要让门禁红的错误。
#   现在都与 StepperRow 一起转发完毕，登记表里不再有"待收编"项 ——
#   一旦有人把转发改回自绘，K2 会直接判"转发链断裂"而失败，这正是本表想要的防回退效果。
FORWARD_CHAIN = {
    "Card": ["DhGroup"],
    "ValueRow": ["DhRow"],
    "ActionRow": ["DhRow"],
    "SwitchRow": ["DhRow"],
    "PermRow": ["DhRow"],
    "StepperRow": ["DhRow"],
}

# 已知「从未转发」的函数：只报告，不计失败（技术债，不是回退）
# 【当前为空】SwitchRow / PermRow / StepperRow 均已真正转发到 kit 组件，登记一并移除。
KNOWN_NOT_FORWARDED = {}


_RAW_CACHE = {}
_STRIPPED_CACHE = {}


def read(p):
    """读文件（带缓存）。

    【为什么要缓存】脚本要跑好几轮全仓扫描（使用量 / 转发到达 / 自建按钮 / 按钮工厂）。
    每轮都重新 read + strip_comments 275 个文件（约 3.3M 字符），
    实测总耗时超过 60 秒 —— 门禁脚本慢到这种程度就没人愿意跑了，
    而"没人跑的门禁"和"没有门禁"是一回事。
    文件名 → 内容的缓存在同一进程内有效，且源码文件在一次运行中不会变。
    """
    key = str(p)
    if key not in _RAW_CACHE:
        _RAW_CACHE[key] = p.read_text(encoding="utf-8", errors="replace") if p.exists() else None
    return _RAW_CACHE[key]


def read_code(p):
    """读文件并去注释（带缓存）—— strip_comments 是纯字符串变换，可安全缓存。"""
    key = str(p)
    if key not in _STRIPPED_CACHE:
        raw = read(p)
        _STRIPPED_CACHE[key] = "" if raw is None else strip_comments(raw)
    return _STRIPPED_CACHE[key]


def strip_comments(src):
    """去注释但保留换行，保证行号与真实文件一致。"""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        two = src[i:i + 2]
        if depth == 0 and two == "//":
            j = src.find("\n", i)
            if j < 0:
                break
            out.append("\n")
            i = j + 1
            continue
        if two == "/*":
            depth += 1
            i += 2
            continue
        if two == "*/" and depth > 0:
            depth -= 1
            i += 2
            continue
        if depth > 0:
            if src[i] == "\n":
                out.append("\n")
        else:
            out.append(src[i])
        i += 1
    return "".join(out)


def fun_span(src, name):
    """返回 (起行, 函数体)。按花括号配平。"""
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    i = src.find("{", m.end())
    if i < 0:
        return None
    depth, j = 0, i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return (src[:m.start()].count("\n") + 1, src[i:j + 1])


# ---------------------------------------------------------------- K1 使用量

_KIT_CALL_RE = None
_DHKIT_CALL_RES = None


def _kit_call_re():
    """把 10 个组件的调用正则**合并成一条**，单次遍历完成统计。

    【为什么要合并】原实现对每个组件各扫描一遍全仓源码（10 遍 × 275 文件），
    实测 count_kit_calls 单项就要 6.3 秒。合并成一条交替正则后降到 ~0.7 秒。
    门禁脚本一旦超过几十秒，就会有人开始跳过它 —— 那等于没有门禁。
    """
    global _KIT_CALL_RE
    if _KIT_CALL_RE is None:
        alts = "|".join(KIT_COMPONENTS)
        _KIT_CALL_RE = re.compile(
            r"(?:(?:com\.dollhouse\.app\.ui\.)?kit\.)?(" + alts + r")\s*\(")
    return _KIT_CALL_RE


def count_kit_calls():
    """统计每个 kit 组件在 kit 目录之外的调用数。

    【判据细节】
      · 排除定义行（`fun DhXxx(`）；
      · 排除 kit 目录自身（`ui/kit/*.kt`）—— 那是实现，不是使用；
      · 兼容三种调用写法：
          DhRow(                    ← 有 import 时
          kit.DhRow(                ← 部分限定
          com.dollhouse.app.ui.kit.DhRow(   ← 全限定
      · 用去注释后的文本计数，避免注释里提到组件名就算一次。
    """
    kitfiles = {str(p) for p in KIT_DIR.glob("*.kt")}
    counts = {c: 0 for c in KIT_COMPONENTS}
    sites = {c: [] for c in KIT_COMPONENTS}
    for p in sorted(SRC.rglob("*.kt")):
        if str(p) in kitfiles:
            continue
        src = read_code(p)
        rel = str(p).replace("app/src/main/java/com/dollhouse/app/", "")
        for m in _kit_call_re().finditer(src):
            comp = m.group(1)
            # 排除定义行（fun DhXxx(）
            head = src[max(0, m.start() - 24):m.start()]
            if re.search(r"fun\s+$", head):
                continue
            counts[comp] += 1
            sites[comp].append("%s:%d" % (rel, src[:m.start()].count("\n") + 1))
    return counts, sites


def count_reach_by_forwarding():
    """经 DhKit 转发函数「到达」kit 的调用数。

    【为什么需要第二个指标】
      只看"页面直接调 kit"会严重低估组件库的使用面：本项目的机制是
      页面调 `DhKit.ValueRow`（38 处），ValueRow 内部再调 `kit.DhRow`。
      若只数后者，DhRow 只有 3 处直接调用，看起来库还是没人用；
      但真实使用面是「3 直连 + 38 经 ValueRow + 88 经 ActionRow」。

      所以本脚本输出两个维度：
        · 直接调用  —— 页面直接写 kit.DhRow(...)
        · 经转发到达 —— 页面调用了内部会转发到该 kit 组件的 DhKit 函数
      两者相加才是"这个组件到底服务了多少处 UI"。

    【口径】转发映射取自 FORWARD_CHAIN（已经源码核实），
      DhKit 函数的调用数用全仓计数（含 DhKit 内部互调时排除定义行）。
    """
    counts, _ = count_kit_calls()   # 直接调用
    # 同样合并成单条正则：一次遍历统计所有 DhKit.Xxx( 调用
    names = list(FORWARD_CHAIN.keys()) + list(KNOWN_NOT_FORWARDED.keys())
    dhkit_calls = {fn: 0 for fn in names}
    pat = re.compile(r"(?:com\.dollhouse\.app\.ui\.compose\.)?\bDhKit\.(" +
                     "|".join(names) + r")\s*\(")
    kitfiles = {str(p) for p in KIT_DIR.glob("*.kt")}
    for p in sorted(SRC.rglob("*.kt")):
        if str(p) in kitfiles:
            continue
        src = read_code(p)
        for m in pat.finditer(src):
            dhkit_calls[m.group(1)] += 1

    reached = {c: 0 for c in KIT_COMPONENTS}
    for fn, targets in FORWARD_CHAIN.items():
        for t in targets:
            reached[t] += dhkit_calls.get(fn, 0)
    return counts, reached, dhkit_calls


def load_baseline():
    if not BASELINE_FILE.exists():
        return None
    try:
        return json.loads(BASELINE_FILE.read_text(encoding="utf-8")).get("counts")
    except Exception:
        return None


def save_baseline(counts):
    BASELINE_FILE.write_text(
        json.dumps({"counts": counts}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")


# ---------------------------------------------------------------- K2 转发链

def check_forward_chain():
    """检查每个候选转发函数内部是否仍有对 kit 组件的调用。"""
    src = read(DHKIT)
    if src is None:
        return None
    code = strip_comments(src)
    rows = []
    for fn, targets in FORWARD_CHAIN.items():
        span = fun_span(code, fn)
        if span is None:
            rows.append((fn, targets, False, "函数不存在"))
            continue
        start, body = span
        found = []
        for t in targets:
            # 同时接受：全限定（含 ui.kit.）与短名（import 后）
            if re.search(r"(?:com\.dollhouse\.app\.ui\.)?kit\." + t + r"\s*\(", body) or \
               re.search(r"(?<![\w.])" + t + r"\s*\(", body):
                found.append(t)
        ok = bool(found)
        detail = ("命中 " + ", ".join(found)) if ok else "未发现对 kit 的调用 → 转发链断裂"
        rows.append((fn, targets, ok, detail))
    return rows


def check_never_forwarded():
    """确认 KNOWN_NOT_FORWARDED 里的函数仍然没有转发（若自己加上了转发，提示可移除登记）。"""
    src = strip_comments(read(DHKIT) or "")
    out = []
    for fn, why in KNOWN_NOT_FORWARDED.items():
        span = fun_span(src, fn)
        if span is None:
            out.append((fn, why, False, "函数不存在"))
            continue
        _, body = span
        has_kit = bool(re.search(r"(?:com\.dollhouse\.app\.ui\.)?kit\.Dh\w+\s*\(", body))
        out.append((fn, why, not has_kit,
                    "仍未转发（技术债，按登记处理）" if not has_kit
                    else "已开始转发 —— 可从事先登记表中移除本项"))
    return out


# ---------------------------------------------------------------- K3 绕过（引用既有脚本）

def run_script(rel):
    """跑既有审计脚本，返回 (exit_code, stdout)。检查器缺失时返回 (None, "")。"""
    p = Path(rel)
    if not p.exists():
        return None, ""
    try:
        r = subprocess.run([sys.executable, rel, "--quiet"],
                           capture_output=True, text=True, timeout=180)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return None, str(e)


def _modifier_chain(src, pos):
    """取从 pos 处 `Modifier` 开始的那条链，截到同一表达式结束。

    【为什么不能用固定字符窗口】早先用 `src[pos:pos+600]` 取样：
      · 600 字符会**跨过当前函数**，把下一个函数的 `.clip/.background` 算进来
        （实测 `DhForm.kt:525` 的 `fun Chip(` 定义行因此被误报成"自建按钮"）；
      · 也会把底栏胶囊的容器 Modifier 误判成按钮（`BottomNav.kt:127`）。
    正确做法：从 Modifier 起按括号/逗号深度走，遇到「深度归零且换行」即停。
    """
    i, n = pos, len(src)
    depth = 0
    while i < n:
        ch = src[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                # 到达所在表达式的外层结束
                return src[pos:i]
            depth -= 1
        elif ch == "\n" and depth == 0:
            # 下一行若仍以 . 开头，说明链还在延续
            j = i + 1
            while j < n and src[j] in " \t":
                j += 1
            if j < n and src[j] == ".":
                i = j
                continue
            return src[pos:i]
        i += 1
    return src[pos:]


def check_bypass_buttons():
    """自建"按钮"：一条 Modifier 链里同时出现 clip(圆角) + background + pressable。

    【判据收紧的三个要点】上一版报 17 处，逐一核对发现大部分是误报：
      1. **必须在同一函数内**：用 [`_modifier_chain`] 精确取链，不再跨函数取样；
      2. **必须有可点语义**：只 `.background + .clip` 但不可点的（如底栏胶囊容器）不算按钮；
      3. **定义行排除**：`fun Chip(` 那类定义行不是调用点。
    另外把"药丸按钮工厂"单独统计（K3c），这里只抓**散落的**自建按钮。
    """
    hits = []
    skip = {str(p) for p in KIT_DIR.glob("*.kt")}
    for p in sorted(SRC.rglob("*.kt")):
        if str(p) in skip:
            continue
        rel = str(p).replace("app/src/main/java/com/dollhouse/app/", "")
        # 规范源文件与已知按钮工厂：它们的药丸按钮已由 K3c 单独统计，
        # 这里再报一遍只会重复计数。
        if rel in ("ui/design/DesignSystem.kt", "ui/compose/DhKit.kt"):
            continue
        src = read_code(p)
        seen_lines = set()
        for m in re.finditer(r"Modifier\b", src):
            chain = _modifier_chain(src, m.start())
            if not chain:
                continue
            if ".pressable" not in chain and ".clickable" not in chain:
                continue
            if ".background(" not in chain:
                continue
            if "RoundedCornerShape(" not in chain and ".clip(" not in chain:
                continue
            ln = src[:m.start()].count("\n") + 1
            if ln in seen_lines:
                continue
            seen_lines.add(ln)
            hits.append("%s:%d" % (rel, ln))
    return hits


# ---------------------------------------------------------------- K3c 按钮体系（本轮重点）
#
# 【为什么单列】
#   设计稿写的是「Buttons — one height, 44dp」：按钮统一 44dp 高 + 12 圆角。
#   kit 的 `DhButton` 正是这么实现的，但它的库外调用数是 0 ——
#   真正的按钮来自四个工厂，全部用 `RoundedCornerShape(999.dp)` 药丸 + 内边距撑高：
#
#     工厂                        形态                        调用点
#     DhForm.PrimaryChip         999 药丸 / acc 实心 / pad 16x9    18
#     DhForm.OutlineChip         999 药丸 / card 底 + line 描边    15
#     DhKit.OutlineChip          999 药丸 / cardUp 底 + line 描边  17
#     DhForm.SubmitButton        999 药丸 / 内边距撑高              2
#     DhForm.Chip                999 药丸 / 小号                   2
#
#   「行 / 卡片 / 输入框」都已经转发到 kit，只有按钮体系没统一 ——
#   这是 ui/kit 收口里唯一剩下的大块。本轮只量化，不改（改 52 处是另一个工程）。
BUTTON_FACTORIES = [
    ("DhForm.PrimaryChip", "999 药丸 / accent 实心 / pad 16x9"),
    ("DhForm.OutlineChip", "999 药丸 / card 底 + line 描边"),
    ("DhKit.OutlineChip", "999 药丸 / cardUp 底 + line 描边"),
    ("DhForm.SubmitButton", "999 药丸 / 内边距撑高（无固定高）"),
    ("DhForm.Chip", "999 药丸 / 小号"),
]


def check_button_system():
    """按钮体系现状：各工厂调用点 + 999 药丸总量 + 是否符合 44dp/12 规范。"""
    rows = []
    for name, form in BUTTON_FACTORIES:
        n = 0
        for p in sorted(SRC.rglob("*.kt")):
            if p.parent == KIT_DIR:
                continue
            src = read_code(p)
            n += len(re.findall(re.escape(name) + r"\s*\(", src))
        rows.append((name, form, n))

    # 999 药丸圆角总量（全仓，含 View/Compose）
    total_999 = 0
    for p in sorted(SRC.rglob("*.kt")):
        src = read_code(p)
        total_999 += len(re.findall(r"RoundedCornerShape\(\s*999(?:\.0)?\s*(?:\.dp)?\s*\)", src))

    pill_buttons = sum(n for _, _, n in rows)
    return rows, pill_buttons, total_999


# ---------------------------------------------------------------- K3d 形态断言（不是调用点数）
#
# 【为什么必须另开一项】
#   K3c 数的是「工厂被调用了多少次」——它**不会**因为工厂内部的形态被改好而减少。
#   实测教训：`DhForm.DialogButton` 13 处调用全部是弹窗主次操作（纯 button），
#   已从「999 药丸 + 内边距撑高 + 12sp」改成「44dp 定高 + 12 圆角 + 15sp」，
#   但 K3c 的合计 54 一点没动 —— 因为 DialogButton 压根不在 BUTTON_FACTORIES 表里，
#   改内部实现也不影响任何计数。于是「做对了却看不见」，门禁等于没覆盖这一项。
#
# 【判据】对每个具名工厂，提取它的**函数体**（从 `fun Name(` 到与之配平的结束花括号），
#   检查体内是否仍出现 `RoundedCornerShape(999`。这直接回答「这个工厂还是不是药丸形态」。
#
# 【为什么要配平花括号而不是切到下一个 fun】
#   工厂体内有 when / if / Row{} 等嵌套块，按行切会把相邻函数一起吃进来，产生假结果。
#   按花括号配平才能准确圈定函数体边界。
#
# 【退出码】药丸形态的工厂**不算失败**（chip 工厂本来就该是 999，见 SHAPE_EXPECTED_PILL）。
#   只有「登记为已收编、实际却还是 999」才算失败 —— 防止有人把改动悄悄退回去。
SHAPE_EXPECTED_PILL = {
    # chip 工厂：设计稿规定 chip = 999 pill，它们**应该**保持 999，永远不算失败。
    "DhForm.PrimaryChip": "chip/button 混用，含真 chip 调用点（ModeChip / 分段选择）",
    "DhForm.OutlineChip": "chip/button 混用，含真 chip 调用点（ModeChip / 分段选择）",
    "DhKit.OutlineChip": "chip/button 混用，含真 chip 调用点（页面筛选器 / 纯标签）",
}

# 已知旧偏差：不是本轮引入，也不在本轮修（改它会动调用点）。
# 记录在案是为了让门禁**如实**反映现状，而不是假装它「应该」是 999。
SHAPE_KNOWN_DEVIATION = {
    "DhForm.Chip": "chip 语义但圆角是 R_ROW=12（非 999）；K3c 表的描述是过期的，待产品确认",
}

# 登记为「已收编成 44dp+12」的工厂：它们体内**不得**再出现 999。
SHAPE_MUST_BE_RECT = {
    "DhForm.DialogButton": "已收编：44dp + 12 圆角 + 15sp SemiBold",
    "DhForm.SubmitButton": "已收编：44dp + 12 圆角 + 15sp SemiBold",
    "DhForm.Button": "语义化按钮工厂：44dp + 12 圆角 + 15sp SemiBold",
}

# 所有要检查形态的工厂（chip 工厂 + 已收编工厂）
SHAPE_FACTORIES = (list(SHAPE_EXPECTED_PILL.keys())
                   + list(SHAPE_KNOWN_DEVIATION.keys())
                   + list(SHAPE_MUST_BE_RECT.keys()))


def _extract_fun_body(src, fun_name):
    """取出 `fun <fun_name>(` 的函数体文本（按花括号配平）。

    找不到返回 None。配平过程跳过字符串与注释，避免被 `"{"` 或注释里的括号误导。
    """
    m = re.search(r"\bfun\s+" + re.escape(fun_name) + r"\s*\(", src)
    if not m:
        return None
    # 从参数列表开始配平括号，找到函数体的第一个 '{'
    i = src.find("(", m.end() - 1)
    depth = 0
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    # 跳过 `: Unit {` / `{` 这类到函数体开头
    brace = src.find("{", i)
    if brace < 0:
        return None
    # 配平函数体的花括号
    depth = 0
    j = brace
    in_str = False
    in_line_comment = False
    in_block_comment = 0
    while j < n:
        ch = src[j]
        nxt = src[j + 1] if j + 1 < n else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
        elif in_block_comment:
            if ch == "/" and nxt == "*":
                in_block_comment += 1
                j += 1
            elif ch == "*" and nxt == "/":
                in_block_comment -= 1
                j += 1
        elif in_str:
            if ch == "\\":
                j += 1
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "/" and nxt == "/":
                in_line_comment = True
                j += 1
            elif ch == "/" and nxt == "*":
                in_block_comment += 1
                j += 1
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return src[brace:j + 1]
        j += 1
    return src[brace:]


def check_factory_shapes():
    """K3d：逐个工厂检查**函数体内**是否仍是 999 药丸形态。

    返回 (rows, violations)。rows: [(factory, shape, is_pill, expected, note)]；
    violations: 只装「登记为已收编、实际仍是 999」——这些才是真失败。
    """
    src_path = SRC / "com/dollhouse/app/ui/compose/DhForm.kt"
    dhkit_path = SRC / "com/dollhouse/app/ui/compose/DhKit.kt"
    rows = []
    violations = []
    for name in SHAPE_FACTORIES:
        short = name.split(".")[-1]
        path = dhkit_path if name.startswith("DhKit.") else src_path
        if not path.exists():
            continue
        src = read_code(path)
        body = _extract_fun_body(src, short)
        if body is None:
            rows.append((name, "未找到", False, "?", "函数未定位"))
            continue
        is_pill = bool(re.search(r"RoundedCornerShape\(\s*999", body))
        has_44 = bool(re.search(r"\.height\(\s*44\.dp\s*\)", body))
        has_12 = bool(
            re.search(r"Radius\.md", body) or re.search(r"R_ROW", body)
            or re.search(r"RoundedCornerShape\(\s*12\.dp\s*\)", body)
        )
        if is_pill:
            shape = "999 药丸"
        elif has_44 and has_12:
            shape = "44dp + 12 圆角"
        else:
            shape = "非药丸（%s）" % ("44dp" if has_44 else "无定高")
        if name in SHAPE_MUST_BE_RECT:
            expected = "应为 44dp+12"
            if is_pill:
                violations.append("%s 登记为已收编，但函数体内仍是 999 药丸圆角" % name)
        elif name in SHAPE_KNOWN_DEVIATION:
            # 已知旧偏差：不判失败，但要在表里如实显示，不许标成「应保持 999」。
            expected = "已登记偏差"
        else:
            expected = "应保持 999"
        rows.append((name, shape, is_pill, expected,
                     SHAPE_MUST_BE_RECT.get(name)
                     or SHAPE_KNOWN_DEVIATION.get(name)
                     or SHAPE_EXPECTED_PILL.get(name, "")))
    return rows, violations


def check_bypass_switch():
    """自建 Switch：直接调用 DhKit 内部自绘的 Switch，而不是走 SwitchRow。

    【注意】kit 与 DhKit 都**没有**提供公开的"标准开关行"以外的入口，
    所以这里只统计"直接用 Switch(" 的数量，作为收编线索，不算失败——
    因为有些地方确实需要裸开关（例如卡片右上角）。
    """
    hits = []
    for p in sorted(SRC.rglob("*.kt")):
        # p 是 Path，比较父目录要用 p.parent（早先误写 str(p).parent，直接 AttributeError）
        if p.parent == KIT_DIR:
            continue
        rel = str(p).replace("app/src/main/java/com/dollhouse/app/", "")
        if rel == "ui/compose/DhKit.kt":
            continue
        src = read_code(p)
        # 只认「未限定的 Switch(」= 调 DhKit 自绘那个；
        # 排除 DhKit.Switch( / 别的 xxx.Switch( —— 那是显式调用，不同语义。
        for m in re.finditer(r"(?<![\w.])Switch\s*\(", src):
            hits.append("%s:%d" % (rel, src[:m.start()].count("\n") + 1))
    return hits


def main():
    issues = []
    lines = []

    def out(s=""):
        lines.append(s)
        if not QUIET:
            print(s)

    # ---------- 数据源
    missing = [str(p) for p in (KIT_DIR / "Components.kt", DHKIT) if not p.exists()]
    if missing:
        print("找不到数据源：")
        for x in missing:
            print("  " + x)
        print("请在仓库根目录运行。")
        return 2

    out("===== 组件库使用量与绕过审计 =====")
    out()

    # ---------- K1 使用量
    counts, sites = count_kit_calls()
    baseline = load_baseline()

    direct, reached, dhkit_calls = count_reach_by_forwarding()

    out("--- K1 使用量（两个维度：直接调用 / 经 DhKit 转发到达）")
    out("%-16s %8s %10s %10s  %s" % ("组件", "直接", "经转发", "实际服务", "基线(直接)"))
    out("-" * 72)
    regressions = []
    for c in KIT_COMPONENTS:
        cur = counts[c]
        base = (baseline or {}).get(c)
        if base is None:
            delta = "—"
        elif cur < base:
            delta = "%d（↓%d）" % (base, base - cur)
            regressions.append((c, base, cur))
        elif cur > base:
            delta = "%d（↑+%d）" % (base, cur - base)
        else:
            delta = "%d（=）" % base
        served = cur + reached.get(c, 0)
        out("%-16s %8d %10d %10d  %s" % (c, cur, reached.get(c, 0), served, delta))

    total = sum(counts.values())
    total_served = sum(counts[c] + reached.get(c, 0) for c in KIT_COMPONENTS)
    out("-" * 72)
    out("%-16s %8d %10d %10d" % ("合计", total, total_served - total, total_served))
    out()
    out("  经转发的 DhKit 调用数：")
    for fn, n in sorted(dhkit_calls.items(), key=lambda kv: -kv[1]):
        tgt = "/".join(FORWARD_CHAIN.get(fn, [])) or "（不转发）"
        out("    DhKit.%-11s %4d  → %s" % (fn, n, tgt))
    out()
    out("  【读法】「实际服务」= 直接调用 + 经转发到达。只看直接调用会低估组件库：")
    out("          页面的 DhKit.ValueRow(38) 最终落到 kit.DhRow，但不会出现在「直接」列。")
    if total == 0:
        out("")
        out("⚠ 全部为 0：组件库没有任何库外调用 —— 规范没有被使用。")
        issues.append("kit 组件库外调用总数为 0")

    if regressions:
        out("")
        out("【回退】以下组件调用数低于基线：")
        for c, b, cur in regressions:
            out("  %s: %d → %d" % (c, b, cur))
        issues.append("kit 调用数回退 %d 项" % len(regressions))
    elif baseline is None:
        out("")
        out("（基线尚未建立：本次数字将不作比对。用 --update-baseline 建立基线。）")

    # ---------- K2 转发链
    out()
    out("--- K2 转发链（DhKit 函数是否内部调用 kit 组件）")
    chain = check_forward_chain()
    if chain is None:
        issues.append("DhKit 不可读")
    else:
        for fn, targets, ok, detail in chain:
            out("  %-11s → %-9s %s   %s"
                % (fn, "/".join(targets), "OK  " if ok else "断裂", detail))
            if not ok:
                issues.append("转发链断裂：%s" % fn)

    out()
    out("--- K2b 从未转发的函数（技术债，不计失败）")
    nf = check_never_forwarded()
    for fn, why, still, detail in nf or []:
        out("  %-11s %-12s %s" % (fn, "未转发" if still else "已转发", detail))
        out("              %s" % why)

    # ---------- K3 绕过
    out()
    out("--- K3 绕过检测")

    btn = check_bypass_buttons()
    out("  自建按钮候选（可点 + 圆角 + 底色）：%d 处" % len(btn))
    for h in btn[:12]:
        out("    " + h)
    if len(btn) > 12:
        out("    … 另有 %d 处" % (len(btn) - 12))
    out()
    out("  【本项只作线索，不计失败】判据只能识别「可点 + 圆角 + 底色」的形态，")
    out("    其中确实有真按钮（如 DhForm 的 DialogButton、DollGroupPage 的条目），")
    out("    但也混着头像按钮、提示条、可点卡片等非按钮用途，无法静态区分。")
    out("    把无法区分的形态设为硬失败，会让门禁长期报红——那等于没有门禁。")
    out("    真正的按钮收口问题在 K3c（药丸按钮工厂），请看那一节。")

    sw = check_bypass_switch()
    out("  裸 Switch(（未走 SwitchRow，仅作收编线索）：%d 处" % len(sw))
    for h in sw[:12]:
        out("    " + h)
    if len(sw) > 12:
        out("    … 另有 %d 处" % (len(sw) - 12))

    out()
    out("--- K3c 按钮体系（唯一未收口的大块）")
    brows, pill_buttons, total_999 = check_button_system()
    out("  %-24s %-38s %6s" % ("工厂", "形态", "调用点"))
    out("  " + "-" * 72)
    for name, form, n in brows:
        out("  %-24s %-38s %6d" % (name, form, n))
    out("  " + "-" * 72)
    out("  %-24s %-38s %6d" % ("合计（药丸按钮）", "", pill_buttons))
    out()
    out("  全仓 RoundedCornerShape(999) 总量：%d 处（含底栏胶囊 / 头像 / 徽标等非按钮用途）" % total_999)
    out("  设计稿要求：按钮统一 44dp 高 + 12 圆角（「Buttons — one height, 44dp」）")
    out("  现状：上述 %d 处按钮全部是 999 药丸 + 内边距撑高，无固定高度。" % pill_buttons)
    if counts.get("DhButton", 0) == 0:
        out("  注意：kit.DhButton 库外调用为 0 —— 规范按钮没有任何页面在用。")
    out("  结论：按钮体系是 ui/kit 收口里唯一未统一的块（行/卡片/输入框都已转发）。")
    out("        本轮只量化，不做统一（统一 %d 处属独立工程）。" % pill_buttons)

    # ---- K3d：形态断言（K3c 数调用点，K3d 看工厂体内到底是不是药丸）----
    out()
    out("--- K3d 工厂形态（函数体内是否仍为 999 药丸，K3c 的度量缺口）")
    out("  %-22s %-16s %-12s %s" % ("工厂", "实际形态", "期望", "备注"))
    out("  " + "-" * 78)
    srows, sviol = check_factory_shapes()
    for name, shape, is_pill, expected, note in srows:
        flag = ""
        if name in SHAPE_MUST_BE_RECT and is_pill:
            flag = "  <<< 回退！"
        out("  %-22s %-16s %-12s %s%s" % (name, shape, expected, note, flag))
    for v in sviol:
        issues.append("K3d 形态回退：" + v)
    out("  " + "-" * 78)
    if sviol:
        out("  K3d FAIL %d 项：登记为已收编的工厂又退回 999 药丸。" % len(sviol))
    else:
        out("  K3d PASS：已收编工厂均为 44dp+12；chip 工厂保持 999（设计稿要求）。")

    # 引用 input_audit / state_audit 的结论（不重复实现）
    out()
    out("--- K3b 引用既有脚本（不重复实现）")
    ia_code, ia_out = run_script("tools/input_audit.py")
    sa_code, sa_out = run_script("tools/state_audit.py")

    if ia_code is None:
        out("  input_audit.py  缺失（跳过）")
    else:
        m = re.search(r"PASS\s+(\d+)\s*/\s*FAIL\s+(\d+)", ia_out)
        if m:
            out("  input_audit.py  PASS %s / FAIL %s" % (m.group(1), m.group(2)))
        else:
            out("  input_audit.py  exit %d（未解析到统计行）" % ia_code)
        for ln in ia_out.splitlines():
            if ln.startswith("FAIL"):
                out("      " + ln)
                issues.append("input_audit 报 FAIL：" + ln.strip())
        if ia_code not in (0,) and not m:
            issues.append("input_audit.py 执行异常 exit %s" % ia_code)

    if sa_code is None:
        out("  state_audit.py  缺失（跳过）")
    else:
        m = re.search(r"已规范\s+(\d+)\s*处\s*/\s*自建\s+(\d+)\s*处", sa_out)
        blank = re.search(r"纯空白态\s+(\d+)\s*处", sa_out)
        if m:
            out("  state_audit.py  已规范 %s / 自建 %s / 纯空白 %s"
                % (m.group(1), m.group(2), blank.group(1) if blank else "?"))
        else:
            out("  state_audit.py  exit %d" % sa_code)
        for ln in sa_out.splitlines():
            if ln.startswith("FAIL"):
                out("      " + ln)
                issues.append("state_audit 报 FAIL：" + ln.strip())

    # ---------- K4 复用声明
    out()
    out("--- K4 复用（本脚本没有重复实现已有检测）")
    out("  输入框绕过  → 引用 tools/input_audit.py 的 I6 结论")
    out("  空态骨架    → 引用 tools/state_audit.py 的 S3/S4 结论")

    # ---------- 结论
    out()
    if UPDATE:
        save_baseline(counts)
        print("基线已更新：%s（%d 个组件，合计 %d）"
              % (BASELINE_FILE, len(counts), total))
        return 0

    if issues:
        print()
        print("组件库审计：发现 %d 类问题" % len(issues))
        for i in issues:
            print("  - " + i)
        return 1

    print()
    print("组件库审计：通过（直接 %d + 经转发 %d = 实际服务 %d 处；转发链 %d/%d 完整；"
          "药丸按钮 %d 处待收编）"
          % (total, total_served - total, total_served,
             sum(1 for r in (chain or []) if r[2]), len(chain or []), pill_buttons))
    return 0


if __name__ == "__main__":
    sys.exit(main())