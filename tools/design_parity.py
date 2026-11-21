#!/usr/bin/env python3
"""
Dollhouse · 设计稿 ↔ 代码 一致性校验（可复跑）

【为什么需要这个脚本】
  设计稿（design-studio/dollhouse-refresh）与落地代码（ui/design/DesignSystem.kt、
  ui/theme/ThemeManager.kt）之间的一致性，此前是**人工核对**的：
  字号 8 档、间距 6 档、圆角 3 档、动效 4 档逐项看过一遍，结论写进了注释与文档。
  问题是「结论」不等于「校验」：
    · 下次有人把 title 从 20 改成 21，没有人会知道设计稿要求 20；
    · 有人把 r8 删掉，注释还在说「四档圆角」；
    · 颜色偏离设计稿是**有意的**（可辨识下限）还是**手滑**，肉眼分不出来。
  本脚本把这三件事都变成可复跑的判定，并把「有意偏离」单独登记，
  让真正的偏差不会被淹没在噪声里。

【三个数据源】
  设计稿  design-studio/dollhouse-refresh/css/token.css   → CSS 变量（--fs-* / --s1..6 / --r8|12|16 / --t-*）
  代码    app/src/main/java/com/dollhouse/app/ui/design/DesignSystem.kt → Type / Space / Radius / Motion
  颜色    ui/theme/ThemeManager.kt 的 LIGHT / DARK / BLACK 三个 29 格数组
          vs tools/design_parity_baseline.json（冻结基线，见下）

【颜色权威已变更 2026-11】
  token.css 的 data-dir="a"（暖米 #F7F4EE）与 "b"（冷紫 #F3F3F8）
  均为**旧视觉方向**，与当前代码的冷蓝 #E8EFF7 三不相等。
  故 D6 不再比对 token.css，改为比对冻结基线（见 design_parity_baseline.json）。
  提交 05ca215「视觉方向切换为清透轻拟物」是这次变更的来源。

  为什么用冻结基线而不是"把代码值抄回 token.css"：后者会让校验变成自己和自己比，
  永远绿，等于废掉这条门禁。冻结基线承认「设计稿的颜色权威已被方向切换取代」，
  同时保住「有人手滑改某个色槽」的检测能力。

  想回到"与设计稿比"的老行为：--against-design（此时例外表生效，用于说明方向差异）。
  确认要接受新的颜色值：--update-baseline（重写基线文件，请在 code review 里说明理由）。

【判据】
  D1 字号阶梯 8 档 × (size / lineHeight / weight)
  D2 间距 6 档
  D3 圆角 3 档（+ pill）
  D4 动效时长 4 档
  D5 缓动曲线 3 条（贝塞尔四元组逐个比较）
  D6 颜色 29 槽 × 3 档（设计稿 light/dark/black vs 代码 LIGHT/DARK/BLACK）

【已知例外表 vs 真偏差】
  例外表里的差异「只报告、不失败」；表外的差异一律判 FAIL 并让退出码非零。
  这是本脚本的核心设计：没有例外表，line 三档的刻意加深会天天报红，
  门禁被喊狼之后大家就都开始忽略它了。

【用法】
  python3 tools/design_parity.py            # 人类可读
  python3 tools/design_parity.py --quiet    # 只打结论
  python3 tools/design_parity.py --list-exceptions   # 打印例外表
退出码：0 = 无非例外偏差；1 = 有真偏差。
"""
import re
import sys
from pathlib import Path

# 设计稿与代码的位置解析
#
# 【为什么不能写死一个相对路径】设计稿不在仓库内：它位于仓库**同级**的
# `design-studio/`（Design Studio 的工作目录），而本脚本在仓库根的 `tools/` 下。
# 于是「从仓库根跑」时正确路径是 `../design-studio/...`；若哪天设计稿被拷进
# 仓库（例如 `assets/design/`），路径又变了。写死一种，换台机器就找不到文件，
# 而脚本又不能用「找不到就通过」敷衍 —— 那正是假绿。
# 这里按优先级依次探测，并把最终解析到的位置打印出来，让读者一眼看到用的是哪份。
#
# 也支持用环境变量覆盖：DOLLHOUSE_DESIGN_STUDIO=/path/to/design-studio
_CANDIDATES = [
    Path("design-studio/dollhouse-refresh/css/token.css"),      # 设计稿被拷进仓库
    Path("../design-studio/dollhouse-refresh/css/token.css"),   # 同级目录（当前实际情况）
    Path("../../design-studio/dollhouse-refresh/css/token.css"),
]


def _resolve_css():
    import os
    env = os.environ.get("DOLLHOUSE_DESIGN_STUDIO")
    if env:
        p = Path(env) / "dollhouse-refresh/css/token.css"
        if p.exists():
            return p
    for c in _CANDIDATES:
        if c.exists():
            return c
    return _CANDIDATES[0]   # 用于报错时显示最可能的那条


CSS = _resolve_css()
DESIGN_KT = Path("app/src/main/java/com/dollhouse/app/ui/design/DesignSystem.kt")
THEME_KT = Path("app/src/main/java/com/dollhouse/app/ui/theme/ThemeManager.kt")

QUIET = "--quiet" in sys.argv
LIST_EXC = "--list-exceptions" in sys.argv
AGAINST_DESIGN = "--against-design" in sys.argv     # 回到「与 token.css 比」的老行为
UPDATE_BASELINE = "--update-baseline" in sys.argv   # 用当前代码重写冻结基线

# 冻结基线（放在仓库内 → 克隆下来也能校验，不依赖仓库外的 design-studio）
BASELINE_FILE = Path(__file__).resolve().parent / "design_parity_baseline.json"

# CSS 里读哪一档 direction。
# 【为什么抽成常量】原先三处选择器各自写死 "a"；设计稿后来新增了 "b" 档，
#   于是「读哪一档」变成一个必须显式说明的选择，而不是藏三个字符串里。
#   【现状·重要】"a"（暖米）和 "b"（冷紫）**都是旧方向**，代码已是冷蓝（清透轻拟物）。
#   所以这一项只在 --against-design 模式下有意义；默认走冻结基线，不读它。
#   可用环境变量覆盖：DOLLHOUSE_DESIGN_DIR=b
DESIGN_DIR_ATTR = __import__("os").environ.get("DOLLHOUSE_DESIGN_DIR", "a")

THEME_SELECTOR = {
    "light": '.dh[data-dir="%s"][data-theme="light"]' % DESIGN_DIR_ATTR,
    "dark": '.dh[data-dir="%s"][data-theme="dark"]' % DESIGN_DIR_ATTR,
    "black": '.dh[data-dir="%s"][data-theme="black"]' % DESIGN_DIR_ATTR,
}

# ThemeManager 三个数组名 → 主题
CODE_ARRAYS = {"light": "LIGHT", "dark": "DARK", "black": "BLACK"}

# 29 格数组下标顺序（与 UiKit.snapshotPalette() 严格一致）
SLOT_ORDER = [
    "ACC", "ACC2", "CARD", "BG", "TITLE", "SUB", "LINE", "OPTION", "SOFT", "FIELD",
    "OK", "ERR", "ON_ACC", "CHAT_BUBBLE_USER", "CHAT_BORDER", "CHAT_CHIP_BG", "CHAT_CHIP_FG",
    "CHAT_CHIP_ON", "CHAT_CHIP_OFF", "CHAT_CHIP_MUTE", "CHAT_ACTION_BG", "HINT_FG", "HINT_BG",
    "SWITCH_OFF", "EMOTE_1", "EMOTE_2", "EMOTE_3", "STROKE", "SCRIM",
]

# 代码槽名 → token.css 变量名
SLOT_TO_CSS = {
    "ACC": "accent", "ACC2": "accent2", "CARD": "card", "BG": "bg", "TITLE": "title",
    "SUB": "sub", "LINE": "line", "OPTION": "option", "SOFT": "soft", "FIELD": "field",
    "OK": "ok", "ERR": "err", "ON_ACC": "onAcc",
    "CHAT_BUBBLE_USER": "chatBubbleUser", "CHAT_BORDER": "chatBorder",
    "CHAT_CHIP_BG": "chatChipBg", "CHAT_CHIP_FG": "chatChipFg", "CHAT_CHIP_ON": "chatChipOn",
    "CHAT_CHIP_OFF": "chatChipOff", "CHAT_CHIP_MUTE": "chatChipMute",
    "CHAT_ACTION_BG": "chatActionBg", "HINT_FG": "hintFg", "HINT_BG": "hintBg",
    "SWITCH_OFF": "switchOff", "EMOTE_1": "emote1", "EMOTE_2": "emote2", "EMOTE_3": "emote3",
    "STROKE": "stroke", "SCRIM": "scrim",
}

# ---------------------------------------------------------------------------
# 已知例外表
#
# 每条：(判据, 主题, 槽/档名) → 理由
# 命中的差异只报告、不判 FAIL、不进一致率的分母（单列「有意偏离」）。
# 【写这里的门槛】必须有可复核的数值或明确的工程理由，不接受「看着差不多」。
# ---------------------------------------------------------------------------
# 【2026-11·色值已随「清透轻拟物」方向切换更新】
#   本表里的「代码值」原先写的是旧暖色（#DAD2C2 / #2A251C …）。方向切换后实际值
#   全部变了，判据仍命中（例外键没变），但**理由里的数字已过期** —— 读者会去找一个
#   代码里不存在的颜色。现已按 ThemeManager 现值重写，对比度也重算过（均仍 ≥1.3）。
#   【这些例外只在 --against-design 模式下生效】默认走冻结基线，基线=代码现值，
#   故 D6 不会命中例外；保留本表是为了「与设计稿比」时能解释方向差异。
EXCEPTIONS = {
    # ---- line 三档：设计稿值低于 1dp 发丝线的可辨识下限 1.3 ----
    # 依据：ui/theme/ThemeManager.kt 的 LIGHT/DARK/BLACK 上方注释块的实测对照表，
    #   且 tools/palette_contrast.py 对「发丝线 / 页面底」与「发丝线 / 卡片」
    #   都设了 ≥1.3 的硬门禁 —— 也就是说这并不是一句口头理由，
    #   而是另一条门禁正在独立守护的约束。
    # 设计稿值 → 实测对比度：浅 #E6E0D4 1.20/1.26、暗 #2E2A22 1.28/1.20、纯黑 #201C15 1.24/1.17
    # 代码值 → 实测对比度：浅 #DAD2C2 1.37/1.44、暗 #38332A 1.46/1.37、纯黑 #2A251C 1.38/1.31
    # ---- D3 圆角 --r16：v3「清透轻拟物」整体上抬一档 ----
    # 依据：DesignSystem.Radius 的注释「旧档位（8/12/16）… 各档 +4~6，
    #   并新增 xl 给大面板」，以及同系列提交 05ca215（视觉方向切换）。
    #   设计稿写 16，代码 lg=22（+6）。r8→xs(8)、r12→md(12) 仍逐一相等，
    #   只有 r16 这一档是刻意放大的 —— 12dp 卡片在柔和阴影下会显得"没圆到位"。
    ("D3", "--r16"): (
        "设计稿 16，代码 lg=22（+6）。v3「清透轻拟物」把圆角整体上抬一档："
        "旧档 8/12/16 → 新档 xs8/sm10/md12/lg22/xl28。12dp 卡片在柔和阴影下"
        "像被削了角，故大圆角档必须更大才撑得住体积感。r8/r12 仍与设计稿逐值相等。"),

    ("D6", "light", "LINE"): (
        "设计稿 #E6E0D4 对 bg/card 只有 1.20/1.26，低于 1dp 发丝线可辨识下限 1.3；"
        "代码加深一档 #C7D1DD（1.33/1.50）。见 ThemeManager 注释块与 palette_contrast 的 line 门禁。"),
    ("D6", "dark", "LINE"): (
        "设计稿 #2E2A22 对 bg/card 只有 1.28/1.20，低于可辨识下限 1.3；"
        "代码加深一档 #303A47（1.52/1.32）。"),
    ("D6", "black", "LINE"): (
        "设计稿 #201C15 对 bg/card 只有 1.24/1.17，低于可辨识下限 1.3；"
        "代码加深一档 #27303A（1.57/1.39）。"),

    # ---- stroke：随 line 一起加深（仅暗色 / 纯黑命中）----
    # 浅色档不登记：token.css 的 stroke(#DAD2C2) 与代码的 LINE 恰好同值，
    #   比对时 RGB 相同 → 本就无差异。登记一条不命中的例外会让例外表虚高，
    #   也会让人误以为「浅色档的 stroke 也有问题」。例外表只收真正发生的差异。
    # token.css 把 stroke 写成不透明 #DAD2C2（与代码里加深后的 line 同值），
    # 但 design_prompts 文档 §2.6 写的是「line at 78% alpha」。
    # 代码取 0x8C（=140/255 ≈ 55%）。两边口径本就不一致（CSS 无 alpha vs 文档 78%），
    # 属「设计稿自身未统一」，不是代码单方面偏离，故登记为例外。
    ("D6", "dark", "STROKE"): (
        "设计稿 stroke #3A342A 未随 line 加深；代码取 #353F4C（暗色 line 是 #303A47，"
        "stroke 比它更实一档，对比度 1.64/1.43）以保持"
        "「描边与发丝线同色」的一致性。差异仅 #02 在 G 通道，肉眼不可辨，"
        "但语义上是对的：stroke 应当等同于同档 line。"),
    ("D6", "black", "STROKE"): (
        "设计稿 stroke #2A251D 未随 line 加深；代码取 #2C353F。纯黑档 line 已加深到 #27303A，"
        "stroke 需比 line 更实一档（1.69/1.49）才能在 #000 上承担「浮起层描边」。"),
}


def read(p):
    if not p.exists():
        return None
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments_keep_lines(src):
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


# --------------------------------------------------------------------------- 设计稿解析

def parse_css_vars(css, selector):
    """取某个选择器块里的 --var: value 对。"""
    m = re.search(re.escape(selector) + r"\s*\{(.*?)\n\}", css, re.S)
    if not m:
        return {}
    d = {}
    # 【变量名必须允许连字符】--fs-brand / --t-press / --ease-std 都带 "-"。
    #   早先用 [A-Za-z0-9]+ 只匹配到 --s1..--s6 这类纯数字名，
    #   于是字号 / 动效 / 曲线三组全部解析为空，脚本把它们报成「一侧缺失」——
    #   看着像设计稿没写，实际是解析器漏读。
    for mm in re.finditer(r"--([A-Za-z0-9-]+)\s*:\s*([^;]+);", m.group(1)):
        d[mm.group(1)] = mm.group(2).strip()
    return d


def parse_root_tokens(css):
    """取 :root 块里的字号 / 间距 / 圆角 / 动效令牌。"""
    m = re.search(r":root\s*\{(.*?)\n\}", css, re.S)
    body = m.group(1) if m else css
    d = {}
    for mm in re.finditer(r"--([A-Za-z0-9-]+)\s*:\s*([^;]+);", body):
        # 同名声明后者胜（与 CSS 层叠一致）
        d[mm.group(1)] = mm.group(2).strip()
    return d


def px(v):
    m = re.match(r"\s*(-?\d+(?:\.\d+)?)\s*px\s*$", v or "")
    return float(m.group(1)) if m else None


def ms(v):
    m = re.match(r"\s*(\d+(?:\.\d+)?)\s*ms\s*$", v or "")
    return float(m.group(1)) if m else None


def num(v):
    """纯数字（字重 600 / 400 这类没有单位的值）。"""
    m = re.match(r"\s*(-?\d+(?:\.\d+)?)\s*$", v or "")
    return float(m.group(1)) if m else None


def hexcolor(v):
    m = re.match(r"\s*#([0-9A-Fa-f]{6})\s*$", v or "")
    return m.group(1).upper() if m else None


def rgba(v):
    m = re.match(r"\s*rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)\s*$", v or "")
    if not m:
        return None
    r, g, b = int(m.group(1)), int(m.group(2)), int(m.group(3))
    a = float(m.group(4)) if m.group(4) else 1.0
    return "%02X%02X%02X" % (r, g, b), a


# --------------------------------------------------------------------------- 代码解析

def parse_type_block(kt):
    """从 Type.of() 里取 8 档的 fontSize / lineHeight / fontWeight。

    做法：定位 `fun of(` 的函数体，逐个 `xxx = TextStyle(...)` 取出三属性。
    """
    src = strip_comments_keep_lines(kt)
    # 实际签名是 `fun of(brandFamily, bodyFamily): Type = Type(` ——
    #   花括号不紧跟签名，而是 Type(...) 的实参里。
    #   早先用 `src.find("{", m.end())` 会找到**下一个函数**的花括号，
    #   于是 22/28/20… 这些字号全部漏读，D1 整组报「代码侧缺失」。
    #   正确做法：从签名往后找 Type( ，再在它的实参范围内找 `xxx = TextStyle(`。
    m = re.search(r"fun\s+of\s*\(", src)
    if not m:
        return {}
    ti = src.find("Type(", m.end())
    if ti < 0:
        return {}
    # 配平 Type( ... ) 得到实参区
    k = ti + len("Type")
    depth, j = 0, k
    while j < len(src):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = src[k:j + 1]

    out = {}
    for mm in re.finditer(r"\b(brand|display|title|section|body|sub|caption|label)\s*=\s*TextStyle\s*\(", body):
        name = mm.group(1)
        # 配平这一个 TextStyle(...)
        k = mm.end() - 1
        d, q = 0, k
        while q < len(body):
            if body[q] == "(":
                d += 1
            elif body[q] == ")":
                d -= 1
                if d == 0:
                    break
            q += 1
        args = body[k:q + 1]
        fs = re.search(r"fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp", args)
        lh = re.search(r"lineHeight\s*=\s*(\d+(?:\.\d+)?)\.sp", args)
        # 【2026-11 修·判据过时】`Type.of` 的 fontWeight 早已改成深浅两档的条件式：
        #     fontWeight = if (dark) FontWeight.Medium else FontWeight.SemiBold
        #   旧正则 `fontWeight\s*=\s*FontWeight\.(\w+)` 完全匹配不到 → 取到 None
        #   → 8 档里 5 条报「一侧缺失，无法比对」（假红）。
        #   设计稿给的是**浅色档**字重（brand/display/title/section/label = 600），
        #   所以这里显式取 `else` 分支；退化成直写时再走简单正则。
        fw = re.search(
            r"fontWeight\s*=\s*if\s*\([^)]*\)\s*FontWeight\.\w+\s*else\s*FontWeight\.(\w+)",
            args)
        if not fw:
            fw = re.search(r"fontWeight\s*=\s*FontWeight\.(\w+)", args)
        out[name] = {
            "size": float(fs.group(1)) if fs else None,
            "lineHeight": float(lh.group(1)) if lh else None,
            "weight": fw.group(1) if fw else None,
        }
    return out


WEIGHT_MAP = {
    "Normal": 400, "Medium": 500, "SemiBold": 600, "Bold": 700,
    "W600": 600, "W500": 500, "W400": 400,
}


def parse_space_block(kt):
    src = strip_comments_keep_lines(kt)
    m = re.search(r"object\s+Space\s*\{(.*?)\n\s*\}", src, re.S)
    if not m:
        return {}
    d = {}
    for mm in re.finditer(r"const\s+val\s+(\w+)\s*=\s*(\d+)", m.group(1)):
        d[mm.group(1)] = int(mm.group(2))
    return d


def parse_radius_block(kt):
    src = strip_comments_keep_lines(kt)
    m = re.search(r"object\s+Radius\s*\{(.*?)\n\s*\}", src, re.S)
    if not m:
        return {}
    d = {}
    for mm in re.finditer(r"const\s+val\s+(\w+)\s*=\s*(\d+)", m.group(1)):
        d[mm.group(1)] = int(mm.group(2))
    return d


def parse_motion_block(kt):
    src = strip_comments_keep_lines(kt)
    m = re.search(r"object\s+Motion\s*\{(.*?)\n\s*\}", src, re.S)
    if not m:
        return {}
    body = m.group(1)
    d = {}
    for mm in re.finditer(r"const\s+val\s+(\w+)\s*=\s*(\d+)", body):
        d[mm.group(1)] = int(mm.group(2))
    # 缓动：val easeX: Easing = CubicBezierEasing(a, b, c, d)
    for mm in re.finditer(
            r"val\s+(ease\w+)\s*:[^=]*=\s*CubicBezierEasing\s*\(\s*([\d.]+)f?\s*,\s*([\d.]+)f?\s*,\s*([\d.]+)f?\s*,\s*([\d.]+)f?\s*\)",
            body):
        d[mm.group(1)] = (float(mm.group(2)), float(mm.group(3)),
                          float(mm.group(4)), float(mm.group(5)))
    return d


def parse_palette_arrays(kt):
    """取 LIGHT / DARK / BLACK 三个 intArrayOf，返回 {theme: [0xAARRGGBB × 29]}。"""
    src = strip_comments_keep_lines(kt)
    out = {}
    for theme, name in CODE_ARRAYS.items():
        m = re.search(name + r"\s*=\s*intArrayOf\((.*?)\n\s*\)", src, re.S)
        if not m:
            out[theme] = None
            continue
        body = re.sub(r"//[^\n]*", "", m.group(1))
        vals = re.findall(r"0x([0-9A-Fa-f]{8})", body)
        out[theme] = [v.upper() for v in vals]
    return out


# --------------------------------------------------------------------------- 冻结基线

def load_baseline():
    """读冻结基线。返回 {"light": {slot: "AARRGGBB", ...}, ...}；文件不存在返回 None。

    【为什么按槽名而不是按数组下标存】下标顺序一旦调整（`UiKit.snapshotPalette()`
      的 29 格顺序是硬约束），按下标存会静默错位；按槽名存则对不上会直接报
      「基线缺少该槽」，不会张冠李戴。
    """
    if not BASELINE_FILE.exists():
        return None
    try:
        import json
        data = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
        return data.get("colors")
    except Exception:
        return None


def save_baseline(arrays):
    """把当前代码的三档数组冻结成基线 JSON。"""
    import json
    colors = {}
    for theme, arr in arrays.items():
        if not arr:
            continue
        colors[theme] = {slot: arr[i] for i, slot in enumerate(SLOT_ORDER) if i < len(arr)}
    payload = {
        "_comment": [
            "design_parity.py 的 D6（调色板）冻结基线。",
            "来源：2026-11「清透轻拟物」方向切换（提交 05ca215）后的代码现状。",
            "为什么不再比对设计稿 token.css：它的 a/b 两档都是旧方向（暖米/冷紫），",
            "  与当前代码的冷蓝三不相等，继续比会产生 78 条常红，让整条门禁失效。",
            "改这里的正确姿势：确认是有意的配色调整 → python3 tools/design_parity.py --update-baseline",
            "  → 在 code review 里说明为什么改，而不是直接提交这个 JSON 的 diff。",
            "值格式：AARRGGBB（与 ThemeManager 的 LIGHT/DARK/BLACK 数组一致）。",
        ],
        "colors": colors,
    }
    BASELINE_FILE.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------- 判据

results = []   # (status, cid, item, expected, actual, note)
# status: "PASS" | "FAIL" | "EXC"


def cmp(cid, item, expected, actual, exc_key=None, exc_reason=""):
    if expected is None or actual is None:
        results.append(("FAIL", cid, item, expected, actual, "一侧缺失，无法比对"))
        return
    same = (expected == actual)
    if same:
        results.append(("PASS", cid, item, expected, actual, ""))
        return
    if exc_key and exc_key in EXCEPTIONS:
        results.append(("EXC", cid, item, expected, actual, EXCEPTIONS[exc_key]))
        return
    results.append(("FAIL", cid, item, expected, actual, "偏离设计稿，且不在例外表"))


TYPE_ROLES = ["brand", "display", "title", "section", "body", "sub", "caption", "label"]
TYPE_CSS_KEY = {r: r for r in TYPE_ROLES}

SPACE_MAP = [("s1", "xs"), ("s2", "sm"), ("s3", "md"), ("s4", "lg"), ("s5", "xl"), ("s6", "xxl")]
# 【2026-11 修·映射错位】v3 给 Radius 新增了 `xs = 8` 档（理由见 DesignSystem 里
#   「P1-b 第 0 批替换时发现 9 处 RoundedCornerShape(8.dp)…此前最小档是 sm=10」），
#   于是「设计稿 --r8 ↔ 代码某档」的对应关系整体后移了一格：
#       旧映射 r8 → sm(10)  ← 错位后拿 10 去比设计稿的 8，恒报偏差
#       正解  r8 → xs(8)
#   现档位：xs=8 / sm=10 / md=12 / lg=22 / xl=28 / pill=999
RADIUS_MAP = [("r8", "xs"), ("r12", "md"), ("r16", "lg"), ("rPill", "pill")]
MOTION_MAP = [("t-press", "press"), ("t-micro", "micro"), ("t-enter", "enter"), ("t-exit", "exit")]
EASE_MAP = [("ease-std", "easeStd"), ("ease-io", "easeIo"), ("ease-exit", "easeExit")]


def compare_all():
    global BASELINE_COLORS
    BASELINE_COLORS = load_baseline() or {}
    css = read(CSS)
    dkt = read(DESIGN_KT)
    tkt = read(THEME_KT)
    missing = []
    if css is None:
        missing.append(str(CSS))
    if dkt is None:
        missing.append(str(DESIGN_KT))
    if tkt is None:
        missing.append(str(THEME_KT))
    if missing:
        print("找不到数据源：")
        for x in missing:
            print("  " + x)
        if str(CSS) in missing:
            print("已尝试的设计稿路径：")
            for c in _CANDIDATES:
                print("  %-58s %s" % (c, "存在" if c.exists() else "不存在"))
            print("可用环境变量指定：DOLLHOUSE_DESIGN_STUDIO=/path/to/design-studio")
        print("代码侧请从仓库根目录运行本脚本。")
        return None

    root = parse_root_tokens(css)
    types = parse_type_block(dkt)
    spaces = parse_space_block(dkt)
    radii = parse_radius_block(dkt)
    motion = parse_motion_block(dkt)
    arrays = parse_palette_arrays(tkt)

    # ---- D1 字号阶梯
    for role in TYPE_ROLES:
        w = num(root.get("fw-" + TYPE_CSS_KEY[role]))
        exp = {
            "size": px(root.get("fs-" + TYPE_CSS_KEY[role])),
            "lineHeight": px(root.get("lh-" + TYPE_CSS_KEY[role])),
            # 字重没有单位（CSS 写 --fw-brand: 600），早先误用 px() 解析 → 恒为 None，
            # 于是 8 档字重全报「设计稿缺失」。这是解析器 bug，不是设计稿缺项。
            "weight": (int(w) if w is not None else None),
        }
        act = types.get(role, {})
        actw = WEIGHT_MAP.get(act.get("weight")) if act.get("weight") else None
        cmp("D1", role + ".size", exp["size"], act.get("size"))
        cmp("D1", role + ".lineHeight", exp["lineHeight"], act.get("lineHeight"))
        cmp("D1", role + ".weight", exp["weight"], actw)

    # ---- D2 间距
    for ck, dk in SPACE_MAP:
        cmp("D2", "--" + ck, px(root.get(ck)), spaces.get(dk))

    # ---- D3 圆角
    for ck, dk in RADIUS_MAP:
        cmp("D3", "--" + ck, px(root.get(ck)), radii.get(dk),
            exc_key=("D3", "--" + ck),
            exc_reason=EXCEPTIONS.get(("D3", "--" + ck), ""))

    # ---- D4 动效时长
    for ck, dk in MOTION_MAP:
        cmp("D4", "--" + ck, ms(root.get(ck)), motion.get(dk))

    # ---- D5 缓动曲线
    for ck, dk in EASE_MAP:
        raw = root.get(ck, "")
        m = re.search(r"cubic-bezier\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*\)", raw)
        exp = tuple(float(m.group(i)) for i in range(1, 5)) if m else None
        cmp("D5", "--" + ck, exp, motion.get(dk))

    # ---- D6 颜色 29 槽 × 3 档
    # 【基线缺失要快速失败】否则 87 个槽各报一条「基线缺少该槽」，把真正的信息淹掉。
    if not AGAINST_DESIGN and not BASELINE_COLORS:
        results.append(("FAIL", "D6", "palette.baseline", "基线文件存在", "缺失",
                        "找不到 %s —— 首次使用请跑 "
                        "`python3 tools/design_parity.py --update-baseline` 冻结基线"
                        % BASELINE_FILE.name))
        return True
    for theme, sel in THEME_SELECTOR.items():
        cssvars = parse_css_vars(css, sel)
        arr = arrays.get(theme)
        if arr is None or len(arr) != 29:
            results.append(("FAIL", "D6", theme + ".palette",
                            "29 格", (len(arr) if arr else "缺失"),
                            "调色板数组缺失或长度不是 29（PAL_SIZE 硬校验）"))
            continue
        for idx, slot in enumerate(SLOT_ORDER):
            code_val = arr[idx]                      # AARRGGBB
            code_hex = code_val[2:]                  # RRGGBB
            code_alpha = code_val[:2]

            # ---- 基线模式（默认）：与冻结基线比 ----
            if not AGAINST_DESIGN:
                base_val = (BASELINE_COLORS.get(theme) or {}).get(slot)
                if base_val is None:
                    results.append(("FAIL", "D6", theme + "." + slot,
                                    "基线应包含该槽", None,
                                    "冻结基线缺少该槽 —— 可能是新增了色槽，"
                                    "请 --update-baseline 重新冻结"))
                    continue
                base_hex, base_alpha = base_val[2:], base_val[:2]
                cmp("D6", theme + "." + slot, "#" + base_hex, "#" + code_hex,
                    exc_key=("D6", theme, slot),
                    exc_reason=EXCEPTIONS.get(("D6", theme, slot), ""))
                if base_alpha != code_alpha:
                    cmp("D6", theme + "." + slot + ".alpha",
                        "alpha=%d%%" % round(int(base_alpha, 16) / 255 * 100),
                        "alpha=%d%%" % round(int(code_alpha, 16) / 255 * 100))
                continue

            # ---- 设计稿模式（--against-design）：与 token.css 比（老行为）----
            cv = cssvars.get(SLOT_TO_CSS.get(slot, ""), None)

            if cv is None:
                # token.css 未定义该槽（如 emote*/stroke 只在不透明情况给出）
                continue

            h = hexcolor(cv)
            if h is not None:
                # 设计稿给的是不透明色 → 只比 RGB
                exc = ("D6", theme, slot) if ("D6", theme, slot) in EXCEPTIONS else None
                cmp("D6", theme + "." + slot, "#" + h, "#" + code_hex,
                    exc_key=("D6", theme, slot),
                    exc_reason=EXCEPTIONS.get(("D6", theme, slot), ""))
                if code_alpha != "FF":
                    # 代码额外带了透明度 —— 单独提示（不计入失败，除非上述已失败）
                    results.append(("PASS", "D6", theme + "." + slot + ".alpha",
                                    "不透明", "alpha=%d%%" % round(int(code_alpha, 16) / 255 * 100),
                                    ""))
                continue

            r = rgba(cv)
            if r is not None:
                rgb, alpha = r
                # 设计稿是 rgba → 比 RGB + alpha（容差 0.02）
                cmp("D6", theme + "." + slot, "#" + rgb, "#" + code_hex,
                    exc_key=("D6", theme, slot),
                    exc_reason=EXCEPTIONS.get(("D6", theme, slot), ""))
                cmp("D6", theme + "." + slot + ".alpha",
                    round(alpha, 2), round(int(code_alpha, 16) / 255, 2),
                    exc_key=("D6", theme, slot),
                    exc_reason=EXCEPTIONS.get(("D6", theme, slot), ""))

    # 【必须显式返回 True】原先这里没有 return，函数隐式返回 None，
    #   而 main() 用 `if compare_all() is None` 判断"找不到数据源" ——
    #   于是正常跑完 134 项比对也会被判成"缺文件"，直接 exit 2 且**什么都不打印**。
    #   这正是本轮要防的静默失败：脚本看起来"跑过了"，其实结论从未输出。
    return True


def report():
    passed = [r for r in results if r[0] == "PASS"]
    failed = [r for r in results if r[0] == "FAIL"]
    exc = [r for r in results if r[0] == "EXC"]

    # 一致率：分母排除「有意偏离」——它们是已知的、有理由的
    denom = len(passed) + len(failed)
    rate = (len(passed) * 100.0 / denom) if denom else 0.0

    if not QUIET:
        print("===== 设计稿 ↔ 代码 一致性校验 =====")
        print("数据源：")
        print("  设计稿  %s（direction=%s）" % (CSS, DESIGN_DIR_ATTR))
        print("  字号/间距/圆角/动效  %s" % DESIGN_KT)
        print("  三档调色板  %s" % THEME_KT)
        if AGAINST_DESIGN:
            print("  颜色基准  token.css（--against-design；例外表生效）")
        else:
            print("  颜色基准  %s（冻结基线；设计稿 a/b 两档均为旧方向）" % BASELINE_FILE.name)
        print()
        by_cid = {}
        for st, cid, item, e, a, note in results:
            by_cid.setdefault(cid, []).append((st, item, e, a, note))
        titles = {
            "D1": "字号阶梯", "D2": "间距阶梯", "D3": "圆角阶梯",
            "D4": "动效时长", "D5": "缓动曲线", "D6": "调色板 29 槽",
        }
        for cid in ["D1", "D2", "D3", "D4", "D5", "D6"]:
            rows = by_cid.get(cid, [])
            if not rows:
                continue
            p = sum(1 for r in rows if r[0] == "PASS")
            f = sum(1 for r in rows if r[0] == "FAIL")
            x = sum(1 for r in rows if r[0] == "EXC")
            print("--- %s %s：一致 %d / 共 %d%s" %
                  (cid, titles.get(cid, ""), p, p + f, ("，有意偏离 %d" % x) if x else ""))

        if failed:
            print()
            print("【真偏差】（不在例外表，必须处理）")
            for st, cid, item, e, a, note in failed:
                print("  FAIL %-3s %-28s 设计稿 %-12s 代码 %-12s  %s"
                      % (cid, item, str(e), str(a), note))

        if exc:
            print()
            print("【有意偏离】（已知、有理由，不算失败）")
            for st, cid, item, e, a, note in exc:
                print("  EXC  %-3s %-28s 设计稿 %-12s 代码 %-12s"
                      % (cid, item, str(e), str(a)))
                print("       -> " + note)

        print()

    print("设计稿一致性：一致 %d / 共 %d（%.1f%%）；有意偏离 %d；真偏差 %d"
          % (len(passed), denom, rate, len(exc), len(failed)))
    if failed and QUIET:
        for st, cid, item, e, a, note in failed:
            print("FAIL %-3s %-28s 设计稿 %s / 代码 %s" % (cid, item, e, a))
    return len(failed)


def main():
    if UPDATE_BASELINE:
        arrays = parse_palette_arrays(read(THEME_KT))
        if not arrays or not any(arrays.values()):
            print("❌ 读不到 ThemeManager 的三档数组，无法冻结基线")
            return 2
        save_baseline(arrays)
        n = sum(len(v) for v in (load_baseline() or {}).values())
        print("✅ 已冻结调色板基线 → %s（%d 个槽）" % (BASELINE_FILE, n))
        print("   请把这次改动的**理由**写进 code review，不要只提交 JSON 的 diff。")
        return 0

    if LIST_EXC:
        print("===== 已知例外表（%d 条）=====" % len(EXCEPTIONS))
        for (cid, theme, slot), reason in sorted(EXCEPTIONS.items()):
            print("\n[%s] %s.%s" % (cid, theme, slot))
            print("  " + reason)
        return 0

    if compare_all() is None:
        return 2
    failed = report()
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())