#!/usr/bin/env python3
"""
Dollhouse 对齐门禁：找出「容器有可见边界 + 里面只有一个文字/图标 + 没显式居中」的节点。

【为什么需要】
  Compose 三种容器的默认对齐**都不是居中**：
    Row    -> horizontalArrangement 默认 Start
    Column -> horizontalAlignment   默认 Start
    Box    -> contentAlignment      默认 TopStart
  于是「有背景/描边/固定尺寸的容器里塞一个文字或图标」这种最常见的小组件
  （药丸、圆底图标按钮、状态点、角标……）只要漏写对齐参数，内容就会贴左上。
  真机上表现为「元素不在中心」，改字号、改内边距都治不好 —— 根因是缺参数。

  真案（AboutPage.ChannelChip）：`.background(...).pressable(...).padding(...)` 里
  只有一个 Text，没写 contentAlignment；而 `pressable` 内置 `minTouchTarget()`
  会把盒子撑到 48dp 高 —— 背景跟着变成 48dp 的药丸，文字却仍贴顶部 8dp 处，
  下面空一大条，视觉上就是「文字不居中」。所以本脚本对含 `.pressable(` /
  `.minTouchTarget(` 的容器给 high 置信度：这类盒子**必然**比内容高。

【为什么不许用正则非贪婪】
  曾经写过 `Row\\s*\\((.*?)\\)\\s*\\{`。参数区里只要出现「`)` 紧跟 `{`」的片段
  （`.pointerInput(Unit) { ... }`、`.then(if (x) a else b)` 之类），
  非贪婪匹配就在那里收工，后面写好的 `horizontalArrangement` /
  `verticalAlignment` 全部看不见 —— 被判定「缺参数」；照着加了重复参数，
  `Argument already passed` 直接让 CI 红一轮（本项目真实事故）。
  所以本脚本**只做括号计数**：从 `(` 起按深度配对，参数区 = 完整到匹配的 `)`；
  且先对源码做「字符串 / 字符字面量 / 行注释 / 块注释（Kotlin 可嵌套）」掩码 ——
  否则注释和字符串里的括号同样会把深度算错。
  `--self-test` 里有一条对照用例，会现场演示非贪婪正则在哪儿截断。

判断标准（三条同时满足才计入 miss）
  1. 容器（Row / Column / Box）的 Modifier 链里有可见边界：
     `.background(` / `.border(` / `.size(` / `.height(`；
  2. 大括号体里**只有一个** UI 子节点，且它是叶子（Text / Icon / BasicText / Image…）；
     `if/else`、`for`、`when` 的分支会展开分别看 —— 「选中一个 Text / 未选一个 Text」
     这种药丸写法算「只有一个」。
  3. 缺对应容器的居中参数，且子节点也没用 `Modifier.align(...)` / `textAlign` 自己摆位。

  【为什么不查 Row/Column 的纵向】体里只有一个叶子时它们的高/宽由内容决定，
    补不补 verticalAlignment 视觉上完全一样 —— 只查水平轴，少制造噪音。

用法
  python3 tools/align_audit.py                       # 只列 miss（门禁视图）
  python3 tools/align_audit.py --all                 # 连「判为不用改」的候选一起列
  python3 tools/align_audit.py --quiet                # 只打汇总 + NEW 行（CI 用）
  python3 tools/align_audit.py --path DIR             # 扫指定目录（自测/回归用）
  python3 tools/align_audit.py --self-test            # 反向验证：注入必须报、清理必须过
  python3 tools/align_audit.py --baseline tools/align_baseline.json
  python3 tools/align_audit.py --update-baseline tools/align_baseline.json

  退出码：0 = 无新增问题；1 = 有新增问题；2 = 脚本自身出错 / 基线坏了。

基线（baseline）的用意：清单里的存量命中不打算一刀切改（有些是误报、有些视觉上
  无所谓），但门禁要能立刻挂进 CI 防**再新增**。基线里只放「人工确认过的存量」，
  新增一处就红。修完一批后 `--update-baseline` 重新固化。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path("app/src/main/java")
CONTAINERS = ("Box", "Row", "Column")

# 叶子节点：容器里只有它一个、且它就是「一个文字或图标」
LEAF_NAMES = {
    "Text", "BasicText", "Icon", "IconRes", "Image", "Img",
    "IconButton", "Spinner", "CircularProgressIndicator",
}

# 控制流关键字：不展开的话，「if (选中) Text(a) else Text(b)」会被当成两个子节点
CTRL = {"if", "else", "for", "while", "when", "try", "catch", "finally", "do"}

# 每个容器「居中」需要的参数 + 该参数该取什么值（打印建议用）
#
# 【为什么要分轴】居中参数只在「那个轴上有余量」时才有意义：
#   · 宽度由内容决定的 Row，horizontalArrangement 写了也不会有任何像素变化；
#   · 而 `.height(44.dp)` 的 Row 里只有一个 Text，纵向就有余量了 ——
#     默认 verticalAlignment = Top，文字会贴顶。
#   所以本脚本先算「哪个轴有余量」，只报那个轴上真正缺的参数。
#   一刀切地报「缺 horizontalArrangement」正是上一轮制造噪音、还被照着改出重复参数的原因。
NEED = {
    "Box": [("contentAlignment", "Alignment.Center")],
    "Row": [("horizontalArrangement", "Arrangement.Center"),
            ("verticalAlignment", "Alignment.CenterVertically")],
    "Column": [("horizontalAlignment", "Alignment.CenterHorizontally"),
               ("verticalAlignment", "Alignment.CenterVertically")],
}

# 哪个轴被「钉住」了 —— 于是内容填不满该轴，才会出现可见的偏
# 注意 Box 与 Row/Column 的语义不同：
#   Box 的子节点默认就会**铺满**宽松的那个轴，所以 fillMaxWidth/fillMaxHeight 不产生余量，
#   余量只来自「被固定住」或「被撑大」；
#   Row/Column 的子节点不会拉伸（除非 weight/fillMaxWidth），所以 fillMax* 反而意味着有余量。
SLACK_FIXED_H_RE = re.compile(r"\.(width|widthIn|size)\s*\(")
SLACK_FIXED_V_RE = re.compile(r"\.(height|heightIn|size)\s*\(")
SLACK_ROWCOL_H_RE = re.compile(r"\.(fillMaxWidth|width|widthIn|size|fillMaxSize)\s*\(")
SLACK_ROWCOL_V_RE = re.compile(r"\.(fillMaxHeight|height|heightIn|size|fillMaxSize)\s*\(")

# 「这个轴上已经显式居中了」的同义写法（wrapContent* 也能把内容摁到中间）
EXEMPT_WRAP = ("wrapContentSize", "wrapContentWidth", "wrapContentHeight")

BOUNDARY_RE = re.compile(r"\.(background|border|size|height)\s*\(")

# 会让盒子**比内容更高**的修饰符：pressable / minTouchTarget 内置 48dp 下限，
#   于是盒子被撑大、背景跟着变高，内容仍贴顶部 —— ChannelChip 的真凶。
# 【坑】pressable 有两种写法：`.pressable(onClick)` 和 `.pressable { onClick() }`，
#   后者没有参数括号 —— 只认 `\(` 就会漏掉 ChatUi 里那两个气泡药丸。
INFLATE_RE = re.compile(r"\.(pressable|pressScaleClick)\s*[({]|\.minTouchTarget\s*\(")


# --------------------------------------------------------------------------
# 1. 掩码：字符串 / 字符 / 注释 -> 等长空格（保留换行，行号偏移不变）
# --------------------------------------------------------------------------
def mask_source(src):
    out = []
    i = 0
    n = len(src)
    blank = lambda s: re.sub(r"[^\n]", " ", s)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            if j < 0:
                j = n
            out.append(blank(src[i:j]))
            i = j
        elif two == "/*":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if src[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif src[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            out.append(blank(src[i:j]))
            i = j
        elif src[i:i + 3] == '"""':
            j = i + 3
            while j < n and src[j:j + 3] != '"""':
                j += 1
            j = min(j + 3, n)
            out.append(blank(src[i:j]))
            i = j
        elif c in ('"', "'"):
            quote = c
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    j += 1
                    break
                if src[j] == "\n":      # 未闭合，别把整份文件吞掉
                    break
                j += 1
            out.append(blank(src[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


# --------------------------------------------------------------------------
# 2. 括号计数（核心：不用正则取参数区）
# --------------------------------------------------------------------------
def match_bracket(masked, start, open_ch="(", close_ch=")"):
    """start 必须指向 open_ch；返回配对 close_ch 的下标，找不到返回 -1。"""
    depth = 0
    for i in range(start, len(masked)):
        c = masked[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return -1


def read_ident(text, i):
    """从 i 读一个（可带点号的）标识符，返回 (名字, 结束下标)。"""
    n = len(text)
    j = i
    while j < n and (text[j].isalnum() or text[j] in "_."):
        j += 1
    return text[i:j], j


def skip_ws(text, i):
    n = len(text)
    while i < n and text[i] in " \t\r\n":
        i += 1
    return i


# --------------------------------------------------------------------------
# 3. 找大括号体里的直接子节点（控制流分支展开）
# --------------------------------------------------------------------------
def collect_children(text):
    """返回 (direct, branch_groups)。

    direct        : [(名字, 起始下标)] 大括号体第一层的直接调用
    branch_groups : [[(名字, 下标)…], …] if/else、for、when 每个分支块里的直接调用
                    药丸「选中一个 Text / 未选一个 Text」靠它才认得出是「只有一个」
    """
    direct, groups = [], []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in "([":
            j = match_bracket(text, i, c, ")" if c == "(" else "]")
            i = n if j < 0 else j + 1
            continue
        if c == "{":
            j = match_bracket(text, i, "{", "}")
            i = n if j < 0 else j + 1
            continue
        if not (c.isalpha() or c == "_"):
            i += 1
            continue

        name, j = read_ident(text, i)
        k = skip_ws(text, j)

        if name in CTRL:
            nxt = _consume_control(text, k, name, groups)
            i = j if nxt is None else nxt
            continue

        if k < n and text[k] == "(":
            close = match_bracket(text, k, "(", ")")
            if close < 0:
                break
            direct.append((name, i))
            after = close + 1
        elif k < n and text[k] == "{":
            direct.append((name, i))
            after = k
        else:
            i = j
            continue

        # 尾随 lambda 一起跳过（它的内部由它自己那层负责）
        k2 = skip_ws(text, after)
        if k2 < n and text[k2] == "{":
            b = match_bracket(text, k2, "{", "}")
            i = n if b < 0 else b + 1
        else:
            i = after
    return direct, groups


def _consume_control(text, k, name, groups):
    """吃掉 if/else/for/when 的若干分支块，每个分支的子调用塞进 groups；返回新下标。"""
    n = len(text)
    cur = name
    while True:
        k = skip_ws(text, k)
        if cur in ("if", "for", "while", "when", "catch", "do"):
            if k < n and text[k] == "(":
                close = match_bracket(text, k, "(", ")")
                if close < 0:
                    return None
                k = skip_ws(text, close + 1)
        if k < n and text[k] == "{":
            b = match_bracket(text, k, "{", "}")
            if b < 0:
                return None
            kids, _ = collect_children(text[k + 1:b])
            groups.append(kids)
            k = skip_ws(text, b + 1)
            if text[k:k + 4] == "else":
                k = skip_ws(text, k + 4)
                if text[k:k + 2] == "if":
                    cur = "if"
                    k = skip_ws(text, k + 2)
                else:
                    cur = "else"
                continue
            return k
        return None


# --------------------------------------------------------------------------
# 4. 单文件扫描
# --------------------------------------------------------------------------
def scan_text(src, display_path, cands):
    """把每个「有大括号体 + 有可见边界」的容器记成一条候选，带判定。

    只记「有可见边界」的：没有背景/描边/固定尺寸的容器，居中与否肉眼无关，
    记进来只会淹掉真问题。

    判定值：
      miss   缺居中参数         -> 门禁要拦
      ok     已经有居中参数
      exempt 靠别的写法生效     -> wrapContent* / 子节点 Modifier.align / textAlign
      skip   不是这个模式       -> 体内不止一个子节点
    """
    masked = mask_source(src)
    line_starts = [0]
    for idx, ch in enumerate(src):
        if ch == "\n":
            line_starts.append(idx + 1)

    def line_of(pos):
        lo, hi = 0, len(line_starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if line_starts[mid] <= pos:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1

    for m in re.finditer(r"(?<![\w.])(" + "|".join(CONTAINERS) + r")\s*\(", masked):
        name = m.group(1)
        open_paren = m.end() - 1
        close_paren = match_bracket(masked, open_paren, "(", ")")
        if close_paren < 0:
            continue
        args = masked[open_paren + 1:close_paren]

        # 必须带尾随 lambda：...) { —— 没有大括号体的容器不在此列
        k = skip_ws(masked, close_paren + 1)
        if k >= len(masked) or masked[k] != "{":
            continue
        close_body = match_bracket(masked, k, "{", "}")
        if close_body < 0:
            continue
        body = masked[k + 1:close_body]

        # 条件 1：有可见边界（否则连候选都不记）
        if not BOUNDARY_RE.search(args):
            continue

        item = {
            "file": display_path,
            "line": line_of(m.start()),
            "container": name,
            "missing": [],
            "child": "",
            "confidence": "",
            "suggest": "",
            "status": "",
            "reason": "",
            "keys": "%s:%d" % (display_path, line_of(m.start())),
        }
        cands.append(item)

        # 条件 2：体里只有一个叶子节点（控制流分支分别看）
        direct, groups = collect_children(body)
        only_name, only_pos, via_branch = None, -1, False
        if len(direct) == 1 and not groups and direct[0][0].split(".")[-1] in LEAF_NAMES:
            only_name, only_pos = direct[0]
        elif not direct and groups and all(len(g) == 1 for g in groups):
            names = {g[0][0].split(".")[-1] for g in groups}
            if len(names) == 1 and names.pop() in LEAF_NAMES:
                only_name, only_pos, via_branch = groups[0][0][0], -1, True

        if only_name is None:
            seen = [c_[0].split(".")[-1] for c_ in direct]
            if groups:
                seen += ["分支:%d组" % len(groups)]
            item["status"] = "skip"
            item["confidence"] = "low"
            item["reason"] = "体内 %d 个直接子节点（%s）——不是「只有一个文字/图标」" % (
                len(direct), "、".join(seen) or "空")
            continue

        short = only_name.split(".")[-1]
        item["child"] = short + ("（if/else 分支）" if via_branch else "")

        # 子节点自己的参数区（判断它是否已经自己摆过位置）
        child_args = ""
        if only_pos >= 0:
            cm = re.compile(r"(?<![\w.])" + re.escape(only_name) + r"\s*\(").search(body, only_pos)
            if cm:
                co = cm.end() - 1
                cc = match_bracket(body, co, "(", ")")
                if cc > 0:
                    child_args = body[co + 1:cc]

        # 条件 3：先算「哪个轴真的有余量」，再只看那个轴上缺的参数
        inflated = bool(INFLATE_RE.search(args))
        if name == "Box":
            slack_h = inflated or bool(SLACK_FIXED_H_RE.search(args))
            slack_v = inflated or bool(SLACK_FIXED_V_RE.search(args))
        else:
            slack_h = inflated or bool(SLACK_ROWCOL_H_RE.search(args))
            slack_v = inflated or bool(SLACK_ROWCOL_V_RE.search(args))

        if not (slack_h or slack_v):
            item["status"] = "skip"
            item["confidence"] = "low"
            item["reason"] = ("两个轴都由内容决定（没有固定尺寸/没有 pressable 撑高），"
                              "居中参数写了也没有像素变化")
            continue

        # Box 用一个 contentAlignment 管两个轴；Row/Column 分轴
        if name == "Box":
            want = ["contentAlignment"] if (slack_h or slack_v) else []
        else:
            want = []
            if slack_h:
                want.append("horizontalArrangement" if name == "Row" else "horizontalAlignment")
            if slack_v:
                want.append("verticalAlignment")

        missing = [p for p in want if not re.search(r"\b" + p + r"\s*=", args)]
        if not missing:
            item["status"] = "ok"
            item["confidence"] = "low"
            item["reason"] = "已有 " + "、".join(want)
            continue
        if any(re.search(r"\b" + w + r"\s*\(", args) for w in EXEMPT_WRAP):
            item["status"] = "exempt"
            item["confidence"] = "low"
            item["reason"] = "用 Modifier.wrapContent* 居中"
            continue
        if re.search(r"\.align\s*\(", child_args) or "textAlign" in child_args \
                or (via_branch and (re.search(r"\.align\s*\(", body) or "textAlign" in body)):
            item["status"] = "exempt"
            item["confidence"] = "low"
            item["reason"] = "子节点自己用 Modifier.align / textAlign 摆了位置"
            continue

        item["status"] = "miss"
        item["confidence"] = "high" if (inflated or slack_v or slack_h) else "low"
        item["missing"] = missing
        item["suggest"] = "、".join("%s = %s" % (p, dict(NEED[name])[p]) for p in missing)
        if inflated:
            axes_txt = "pressable/minTouchTarget 内置 48dp 尺寸下限，盒子被撑得比内容大"
        else:
            axes = []
            if slack_h:
                axes.append("宽度被钉住")
            if slack_v:
                axes.append("高度被钉住")
            axes_txt = "，".join(axes)
        item["reason"] = "%s，内容按默认 %s 排，偏%s" % (
            axes_txt,
            "TopStart" if name == "Box" else "Start/Top",
            "左上" if name == "Box" else "起始边")


def scan_path(root):
    """返回 (miss 列表, 全部候选, 文件数)。"""
    cands = []
    root = Path(root)
    files = sorted(root.rglob("*.kt"))
    for f in files:
        try:
            src = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        scan_text(src, str(f), cands)
    cands.sort(key=lambda d: (d["file"], d["line"]))
    return [c for c in cands if c["status"] == "miss"], cands, len(files)


# --------------------------------------------------------------------------
# 5. 反向验证：注入必须报、清理必须过
# --------------------------------------------------------------------------
FIXTURE_BAD = '''
package demo

@Composable
fun PillBad(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.acc)
            .then(if (true) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(999.dp)))
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = "稳定版")
    }
}
'''

FIXTURE_GOOD = FIXTURE_BAD.replace(
    "            .padding(horizontal = 12.dp, vertical = 8.dp)\n    ) {",
    "            .padding(horizontal = 12.dp, vertical = 8.dp),\n        contentAlignment = Alignment.Center\n    ) {",
)

# 陷阱一：参数区塞满嵌套括号（`.then(if..else..)` / `.background(..)` / `.padding(..)`），
#   真正的居中参数写在最后 —— 只有括号计数才看得见。
#   （`.fillMaxWidth()` + `.height(44.dp)` 把两个轴都钉住，水平/纵向都真的有余量）
FIXTURE_TRAP_OK = '''
@Composable
fun TrapOk() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (x) c.a else c.b)
            .then(Modifier.padding(horizontal = 12.dp))
            .height(44.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "trap")
    }
}
'''

# 陷阱二：同样的嵌套参数，但**真的缺** horizontalArrangement —— 必须报 1 处。
FIXTURE_TRAP_BAD = FIXTURE_TRAP_OK.replace(
    "        horizontalArrangement = Arrangement.Center,\n", ""
)

# 陷阱三：复刻当年 CI 红掉的真凶 —— 参数区里有 `.pointerInput(Unit) { ... }`，
#   那个 `)` 后面紧跟 `{`，非贪婪正则就在那里收工，后面写好的参数全都看不见。
FIXTURE_TRAP_NAIVE = '''
@Composable
fun TrapNaive() {
    Row(
        modifier = Modifier
            .background(c.a)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "naive")
    }
}
'''

# 陷阱四：药丸「选中 / 未选」两条分支各一个 Text —— 应当算「只有一个」并报出来。
FIXTURE_BRANCH = '''
@Composable
fun BranchPill(sel: Boolean) {
    Column(
        modifier = Modifier
            .background(c.card)
            .pressable { }
            .padding(8.dp)
    ) {
        if (sel) {
            Text(text = "选中")
        } else {
            Text(text = "未选")
        }
    }
}
'''


def self_test():
    import tempfile
    ok = True
    print("===== align_audit 反向验证 =====")

    def run_on(text, label):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "probe.kt").write_text(text, encoding="utf-8")
            misses, cands, _ = scan_path(d)
        print("  [%s] 命中 %d 处" % (label, len(misses)))
        for f in misses:
            print("       %s:%d | %s | 缺 %s | 置信 %s | 里面 %s"
                  % (Path(f["file"]).name, f["line"], f["container"],
                     ",".join(f["missing"]), f["confidence"], f["child"]))
        return misses

    def check(cond, ok_msg, bad_msg):
        nonlocal ok
        if cond:
            print("  PASS " + ok_msg)
        else:
            print("  FAIL " + bad_msg)
            ok = False

    # (1) 注入：缺 contentAlignment 的 Box -> 必须报
    fs = run_on(FIXTURE_BAD, "注入·缺 contentAlignment")
    check(len(fs) == 1 and fs[0]["container"] == "Box"
          and fs[0]["missing"] == ["contentAlignment"] and fs[0]["confidence"] == "high",
          "注入必须报：Box 缺 contentAlignment，且因 pressable 判 high",
          "注入必须报：期望 1 处 Box/contentAlignment/high，实得 %r" % ([f["missing"] for f in fs],))

    # (2) 清理：补上 contentAlignment -> 必须过
    fs = run_on(FIXTURE_GOOD, "清理·已居中")
    check(not fs, "清理必须过：补上 contentAlignment 后 0 命中",
          "清理必须过：仍有 %d 处命中" % len(fs))

    # (3) 嵌套括号：居中参数在 `.then(...)` 之后 -> 必须看见 -> 0 命中
    fs = run_on(FIXTURE_TRAP_OK, "括号计数·参数在嵌套括号之后")
    check(not fs, "括号计数：看得见 .then(if..else..) 之后的 horizontalArrangement",
          "括号计数：参数区被截断（误报 %r）" % ([f["missing"] for f in fs],))

    # (4) 同结构但真缺参数 -> 必须报 1 处（证明不是「一律不报」）
    fs = run_on(FIXTURE_TRAP_BAD, "括号计数·同结构真缺参数")
    check(len(fs) == 1 and fs[0]["missing"] == ["horizontalArrangement"]
          and fs[0]["confidence"] == "high",
          "括号计数：同结构真缺时报 1 处 horizontalArrangement（纵向已写，不重复报）",
          "括号计数：期望 1 处 horizontalArrangement/high，实得 %r" % ([f["missing"] for f in fs],))

    # (5) 对照：非贪婪正则的实际截断位置
    fs = run_on(FIXTURE_TRAP_NAIVE, "对照·非贪婪正则陷阱")
    check(not fs, "对照用例：解析器看得到 pointerInput 之后的参数（0 命中）",
          "对照用例：解析器也漏了")
    naive = re.search(r"Row\s*\((.*?)\)\s*\{", FIXTURE_TRAP_NAIVE, re.S)
    truncated = naive.group(1) if naive else ""
    if naive and "horizontalArrangement" not in truncated:
        print("       对照结论：同一段源码用 Row\\s*\\((.*?)\\)\\s*\\{ 取参数区 -> 只有 %d 字符，"
              "里面**没有** horizontalArrangement" % len(truncated))
        print("                 （当年就是这样误判，加了重复参数后 CI 红一轮）")
    else:
        print("  FAIL 对照用例构造有误：非贪婪正则居然也看到了 horizontalArrangement")
        ok = False

    # (6) 药丸 if/else 分支各一个 Text -> 必须当成「只有一个」并报
    fs = run_on(FIXTURE_BRANCH, "分支·if/else 各一个 Text")
    check(len(fs) == 1 and fs[0]["container"] == "Column"
          and "horizontalAlignment" in fs[0]["missing"],
          "分支展开：if/else 各一个 Text 被认成「只有一个」（否则这类药丸会漏）",
          "分支展开：漏报或误判，实得 %r" % ([f["missing"] for f in fs],))

    print("===== %s =====" % ("全部通过" if ok else "有失败项"))
    return 0 if ok else 1


# --------------------------------------------------------------------------
# 6. main
# --------------------------------------------------------------------------
def main(argv):
    if "--self-test" in argv:
        return self_test()

    quiet = "--quiet" in argv
    root = Path(argv[argv.index("--path") + 1]) if "--path" in argv else ROOT

    misses, candidates, nfiles = scan_path(root)

    baseline_path = None
    if "--baseline" in argv:
        baseline_path = Path(argv[argv.index("--baseline") + 1])
    elif "--update-baseline" in argv:
        baseline_path = Path(argv[argv.index("--update-baseline") + 1])
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(
            json.dumps(sorted(f["keys"] for f in misses), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print("已写入基线 %s（%d 条）" % (baseline_path, len(misses)))
        return 0

    baseline = set()
    if baseline_path and baseline_path.exists():
        try:
            baseline = set(json.loads(baseline_path.read_text(encoding="utf-8")))
        except Exception as e:          # 基线坏了要吵，不能静默当空集
            print("基线读取失败 %s: %s" % (baseline_path, e))
            return 2

    new = [f for f in misses if f["keys"] not in baseline]

    if not quiet:
        print("===== Compose 居中审计（缺参数）=====")
        for f in misses:
            mark = "  " if f["keys"] in baseline else "NEW"
            print("%s %s:%d | %s | 缺 %s | 里面: %s | 置信 %s"
                  % (mark, f["file"], f["line"], f["container"],
                     "、".join(f["missing"]), f["child"], f["confidence"]))
            print("      依据：%s" % f["reason"])
            print("      建议：加 %s" % f["suggest"])
        print()

    if "--all" in argv:
        print("===== 候选全览（含判定为不用改的）=====")
        for c in candidates:
            if c["status"] == "miss":
                continue
            print("  %-6s %s:%d | %s | 里面: %s | %s"
                  % (c["status"].upper(), c["file"], c["line"], c["container"],
                     c["child"] or "-", c["reason"]))
        print()

    print("居中审计：扫描 %d 个文件，缺参数 %d 处，其中新增 %d 处%s"
          % (nfiles, len(misses), len(new), "" if not baseline else "（基线 %d 条）" % len(baseline)))

    if quiet:
        for f in new:
            print("NEW %s:%d | %s | 缺 %s | 里面: %s"
                  % (f["file"], f["line"], f["container"],
                     "、".join(f["missing"]), f["child"]))
    return 0 if not new else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
