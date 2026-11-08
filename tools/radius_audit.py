#!/usr/bin/env python3
"""
Dollhouse 圆角比例门禁：抓「圆角 > 元素短边一半」→ 元素会被圆成药丸/变形。

【为什么需要（真实事故）】
  2026-11 那一轮把通用档 `Radius.md` 从 12 提到 16，本意是「让按钮圆润一点」。
  但这一档被大量**小元素**共用：30dp 提示条、26dp 色块、20dp 图标底。
  16dp 圆角已经大于它们半高（15 / 13 / 10）—— 圆角被裁到半高，视觉上整只元素
  变成药丸或「被啃了一角」。用户原话：**「很丑很丑」**。
  当时的修法是拆档（md 回 12，新增 btn=16 只给按钮），但**没有门禁** —— 下次还会犯。
  这个脚本就是那道门禁：**圆角必须和元素尺寸成比例**。

判据
  取元素链上的固定尺寸，算出短边；把链上的圆角解析成 dp：
      radius > min(w, h) / 2   ->  报「圆角超过半高」
  当前档位（DesignSystem.kt / Radius）：sm=10、md=12、btn=16、lg=22、xl=28、pill=999。

特例放行（这些「大圆角」是故意的）
  · 解析出 >= 900dp 的（`Radius.pill` / `999.dp`）—— 药丸就是想要全圆
  · `CircleShape`
  · `RoundedCornerShape(percent = 50)`
  · 尺寸没有任何固定值的（只有 `fillMaxWidth()` / 尺寸由内容决定）—— 短边不可知，
    宁可漏报也不误报
  · 圆角值解析不出来（`shape = btnShape` 这类变量）—— 同上
  · 圆角为 0 / 找不到圆角 —— 没有形状，不管

【为什么仍然只做括号计数】
  `.size(24.dp)` 与 `RoundedCornerShape(16.dp)` 都可能嵌在
  `.background(if (x) a else b, RoundedCornerShape(16.dp))`、`.then(...)` 里；
  正则非贪婪会在第一个 `)` 处截断，把「有圆角」判成「没圆角」，
  或者把隔壁元素的圆角算到它头上。上一轮有过同类事故（见 align_audit.py 头注释）。

用法
  python3 tools/radius_audit.py                 # 列命中
  python3 tools/radius_audit.py --all           # 连「判为放行/无法解析」的一起列（调判据用）
  python3 tools/radius_audit.py --quiet         # 只打汇总 + NEW 行
  python3 tools/radius_audit.py --path DIR      # 扫指定目录
  python3 tools/radius_audit.py --self-test     # 反向验证：注入必须报、改小必须过
  python3 tools/radius_audit.py --baseline tools/radius_baseline.json
  python3 tools/radius_audit.py --update-baseline tools/radius_baseline.json

  退出码：0 = 无新增；1 = 有新增；2 = 脚本自身出错 / 基线坏了。
"""
import ast
import json
import re
import sys
from pathlib import Path

ROOT = Path("app/src/main/java")

# 当前圆角档位（DesignSystem.kt 的 Radius）。用于给「建议档位」。
TIERS = [("sm", 10.0), ("md", 12.0), ("btn", 16.0), ("lg", 22.0), ("xl", 28.0)]

# >= 这个值就当作「故意的全圆（药丸）」
PILL_MIN = 900.0

SIZE_CALL_RE = re.compile(r"\.\s*(size|height|width|sizeIn)\s*\(")
SHAPE_RE = re.compile(r"RoundedCornerShape\s*\(|CircleShape")
IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*")


# --------------------------------------------------------------------------
# 1. 掩码（与 align_audit 同口径：字符串 / 字符 / 注释 -> 等长空格，留换行）
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
            q = c
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                if src[j] == "\n":
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
def match_bracket(text, start, open_ch="(", close_ch=")"):
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return -1


def match_open_backward(text, close_idx, open_ch="(", close_ch=")"):
    """close_idx 指向 close_ch；返回配对 open_ch 的下标。"""
    depth = 0
    for i in range(close_idx, -1, -1):
        c = text[i]
        if c == close_ch:
            depth += 1
        elif c == open_ch:
            depth -= 1
            if depth == 0:
                return i
    return -1


def skip_ws(text, i):
    n = len(text)
    while i < n and text[i] in " \t\r\n":
        i += 1
    return i


def skip_ws_back(text, i):
    while i >= 0 and text[i] in " \t\r\n":
        i -= 1
    return i


# --------------------------------------------------------------------------
# 3. 常量表：把 Radius.md / DhKit.R_ROW / R_ROW 这类写到数字
# --------------------------------------------------------------------------
class Symbols:
    def __init__(self):
        self.by_full = {}    # "Radius.md" / "DhKit.R_ROW" -> 表达式文本
        self.by_short = {}   # "R_ROW" -> 表达式文本；同名不同值 -> AMBIG
        self.all_exprs = {}  # "shape" -> [表达式文本, ...]（同名多绑定，用于全称判定）
        self.AMBIG = object()

    def add(self, qual, short, expr):
        self.by_full[qual] = expr
        self.all_exprs.setdefault(short, []).append(expr)
        if short in self.by_short and self.by_short[short] != expr:
            self.by_short[short] = self.AMBIG
        else:
            self.by_short[short] = expr

    def resolve(self, expr, depth=0):
        """把表达式解析成 dp 数值；解析不出来返回 None。"""
        if depth > 6 or expr is None:
            return None
        e = expr.strip().rstrip(",;")
        if not e:
            return None
        # 去掉 .dp / .sp 后缀（逐个替换，保留表达式结构）
        e = re.sub(r"\.\s*dp\b", "", e)
        # 【坑】本工程常量常写成 `28.0f`（Float 字面量）—— 不剥掉 f/F/L 后缀就整条解析失败
        e = re.sub(r"(?<=\d)[fFL](?![\w.])", "", e)
        if re.fullmatch(r"-?\d+(?:\.\d+)?", e):
            return float(e)
        # 先把标识符路径替换成数字，剩下的必须是纯算术
        def sub(m):
            path = re.sub(r"\s+", "", m.group(0))
            val = self._lookup(path, depth)
            return repr(val) if val is not None else m.group(0)
        e2 = IDENT_RE.sub(sub, e)
        if not re.fullmatch(r"[\d\s.+\-*/()]+", e2):
            return None
        return _safe_eval(e2)

    def raw(self, name):
        """按短名/限定名取原始表达式文本（用于 shape 变量的一层间接解析）。"""
        parts = [x for x in name.split(".") if x]
        for k in range(len(parts), 1, -1):
            key = ".".join(parts[-k:])
            if key in self.by_full:
                return self.by_full[key]
        v = self.by_short.get(parts[-1]) if parts else None
        return v if isinstance(v, str) else None

    def _lookup(self, path, depth):
        parts = path.split(".")
        for k in range(len(parts), 1, -1):
            key = ".".join(parts[-k:])
            if key in self.by_full:
                return self.resolve(self.by_full[key], depth + 1)
        val = self.by_short.get(parts[-1])
        if isinstance(val, str):
            return self.resolve(val, depth + 1)
        return None


def _safe_eval(expr):
    """只允许数字与 + - * / 的算术求值（不 eval 任意代码）。"""
    try:
        node = ast.parse(expr, mode="eval")
    except SyntaxError:
        return None
    allowed = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Constant,
               ast.Add, ast.Sub, ast.Mult, ast.Div, ast.USub, ast.UAdd)
    for sub in ast.walk(node):
        if not isinstance(sub, allowed):
            return None
        if isinstance(sub, ast.Constant) and not isinstance(sub.value, (int, float)):
            return None
    try:
        return float(eval(compile(node, "<expr>", "eval"), {"__builtins__": {}}, {}))
    except Exception:
        return None


# 【坑】必须带 re.M：否则 `^` 只匹配整份文件的第一个字符 ——
#   常量表只收到第 1 条，后面全解析不出圆角，184 处被判「无法解析」。
VAL_RE = re.compile(
    r"^[ \t]*(?:@\w+\s+)?(?:public\s+|private\s+|internal\s+|protected\s+)?"
    r"(?:const\s+)?val\s+([A-Za-z_]\w*)\s*(?::\s*[\w.<>?]+)?\s*=\s*(.+)$",
    re.M)
OBJ_RE = re.compile(r"\bobject\s+([A-Za-z_]\w*)\s*(?::[^{]*)?\{", re.M)


def collect_symbols(texts):
    """扫所有 object 块里的 val 声明，建 qualified + short 两张表。"""
    syms = Symbols()
    for src in texts:
        masked = mask_source(src)
        stack = []          # [(对象名, 块的结束下标)]
        i = 0
        # 先把所有 object 块区间算出来（支持嵌套）
        blocks = []
        for m in OBJ_RE.finditer(masked):
            brace = masked.index("{", m.end() - 1)
            end = match_bracket(masked, brace, "{", "}")
            if end < 0:
                continue
            blocks.append((m.start(), m.end() - 1, brace, end, m.group(1)))

        def scope_of(pos):
            names = []
            for (s, e, b, en, name) in blocks:
                if b < pos < en:
                    names.append((b, name))
            names.sort()
            return [n for _, n in names]

        for m in VAL_RE.finditer(masked):
            name, raw = m.group(1), m.group(2).strip()
            # 表达式可能跨行：括号没配平就往后接
            if raw.count("(") != raw.count(")"):
                k = m.start(2)
                # 取到行末之后按括号配平继续
                j = m.end(2)
                depth = raw.count("(") - raw.count(")")
                while depth > 0 and j < len(masked):
                    nl = masked.find("\n", j)
                    if nl < 0:
                        break
                    seg = masked[j:nl]
                    depth += seg.count("(") - seg.count(")")
                    raw += seg
                    j = nl + 1
            raw = raw.strip().rstrip(",")
            if not raw or len(raw) > 120:
                continue
            scopes = scope_of(m.start())
            qual = ".".join(scopes + [name])
            syms.add(qual, name, raw)
    return syms


# --------------------------------------------------------------------------
# 4. 链：从 `.size(` / `.height(` 出发，向左右扩成一条 Modifier 链
# --------------------------------------------------------------------------
def expand_chain(text, anchor):
    """anchor 指向 `.size(` 等的前导点号；返回 (chain_start, chain_end)。"""
    n = len(text)
    # ---- 向右 ----
    end = anchor
    while True:
        k = end
        # 当前段：.name(...)  或  .name
        if k >= n or text[k] != ".":
            break
        j = k + 1
        while j < n and (text[j].isalnum() or text[j] == "_"):
            j += 1
        if j == k + 1:
            break
        k2 = skip_ws(text, j)
        if k2 < n and text[k2] == "(":
            close = match_bracket(text, k2, "(", ")")
            if close < 0:
                break
            k3 = skip_ws(text, close + 1)
            if k3 < n and text[k3] == "{":      # 尾随 lambda：.clickable { ... }
                b = match_bracket(text, k3, "{", "}")
                if b < 0:
                    break
                end = b + 1
            else:
                end = close + 1
        else:
            end = j
        # 下一个非空字符还是点号才继续
        k4 = skip_ws(text, end)
        if k4 < n and text[k4] == ".":
            end = k4
        else:
            break
    chain_end = end

    # ---- 向左 ----
    start = anchor
    while True:
        k = skip_ws_back(text, start - 1)
        if k < 0:
            start = 0
            break
        if text[k] == ")":
            op = match_open_backward(text, k, "(", ")")
            if op < 0:
                break
            # ( 之前必须是标识符
            e = skip_ws_back(text, op - 1)
            s = e
            while s >= 0 and (text[s].isalnum() or text[s] == "_"):
                s -= 1
            if s == e:
                start = op
                break
            start = s + 1
            k = skip_ws_back(text, s)
            if k >= 0 and text[k] == ".":
                start = k
                continue
            break
        if text[k].isalnum() or text[k] == "_":
            s = k
            while s >= 0 and (text[s].isalnum() or text[s] == "_"):
                s -= 1
            start = s + 1
            k = skip_ws_back(text, s)
            if k >= 0 and text[k] == ".":
                start = k
                continue
            break
        break
    return start, chain_end


# --------------------------------------------------------------------------
# 5. 从链里读尺寸与圆角
# --------------------------------------------------------------------------
def dims_in_chain(chain):
    """返回 (宽度列表, 高度列表)；只收能解析成数字的固定尺寸。"""
    ws, hs = [], []
    for m in SIZE_CALL_RE.finditer(chain):
        op = chain.index("(", m.start())
        cp = match_bracket(chain, op, "(", ")")
        if cp < 0:
            continue
        args = chain[op + 1:cp]
        parts = [p.strip() for p in args.split(",") if p.strip()]
        kind = m.group(1)
        if kind == "size" and len(parts) >= 2:      # .size(w, h)
            w = SYMS_CACHE.resolve(parts[0])
            h = SYMS_CACHE.resolve(parts[1])
            if w:
                ws.append(w)
            if h:
                hs.append(h)
        elif len(parts) == 1:
            v = SYMS_CACHE.resolve(parts[0])
            if v is None:
                continue
            if kind in ("size", "sizeIn"):
                ws.append(v)
                hs.append(v)
            elif kind == "height":
                hs.append(v)
            elif kind == "width":
                ws.append(v)
    return ws, hs


SYMS_CACHE = Symbols()


VAR_SHAPE_RE = re.compile(
    r"\.\s*(?:clip|background|border)\s*\(|\bshape\s*=")


def shape_from_symbol(name):
    """`shape` 这类变量 -> 顺着常量表找一层，能算出数字就返回。"""
    if not re.fullmatch(r"[A-Za-z_][\w.]*", name or ""):
        return None
    expr = SYMS_CACHE.raw(name)
    if not expr:
        return None
    if "CircleShape" in expr:
        return float(PILL_MIN)
    m = re.search(r"RoundedCornerShape\s*\(([^()]*)\)", expr)
    if m:
        return SYMS_CACHE.resolve(m.group(1).split("=")[-1])
    return None


def all_shape_values(name):
    """同名变量的**所有**绑定 -> 可能的圆角值集合。

    为什么这么做：`val shape = RoundedCornerShape(Radius.btn.dp)` 这种局部变量在多个
    函数里重名，按名字查会「歧义」。但只要**所有**可能取值都合规，结论就是安全的
    （全称成立 => 这个元素一定没问题）。任一取值可能越界就转 UNKNOWN，不瞎报。
    """
    if not re.fullmatch(r"[A-Za-z_][\w.]*", name or ""):
        return None
    short = name.split(".")[-1]
    exprs = list(SYMS_CACHE.all_exprs.get(short, []))
    expr_full = SYMS_CACHE.raw(name)
    if expr_full and expr_full not in exprs:
        exprs.append(expr_full)
    if not exprs:
        return None
    vals = set()
    for e in exprs:
        if "CircleShape" in e:
            vals.add(PILL_MIN)
            continue
        m = re.search(r"RoundedCornerShape\s*\(([^()]*)\)", e)
        if not m:
            return None                       # 有绑定的形态认不出 -> 判不了
        v = SYMS_CACHE.resolve(m.group(1).split("=")[-1])
        if v is None:
            return None
        vals.add(v)
    return vals


def variable_shape_hint(chain):
    """链里有没有「圆角来自变量」的写法（clip(shape) / shape = xxx）。

    只为在 --all 里把盲区说清楚：这种静态判不了，不能算「合规」。
    """
    m = re.search(r"\.\s*clip\s*\(\s*([A-Za-z_][\w.]*)\s*\)", chain)
    if m and m.group(1) not in ("RoundedCornerShape", "CircleShape"):
        return m.group(1)
    m = re.search(r"\bshape\s*=\s*([A-Za-z_][\w.]*)\s*[,)]", chain)
    if m and m.group(1) not in ("RoundedCornerShape", "CircleShape"):
        return m.group(1)
    return None


def shapes_in_chain(chain):
    """返回 (圆角最大值, 是否放行, 放行理由, 原文片段)。"""
    best, src = None, ""
    for m in SHAPE_RE.finditer(chain):
        if m.group(0).startswith("CircleShape"):
            return None, True, "CircleShape", "CircleShape"
        op = chain.index("(", m.start())
        cp = match_bracket(chain, op, "(", ")")
        if cp < 0:
            continue
        args = chain[op + 1:cp]
        if "percent" in args:
            return None, True, "percent = 50（按比例）", args.strip()
        vals = []
        for part in [p.strip() for p in args.split(",") if p.strip()]:
            rhs = part.split("=")[-1].strip()
            v = SYMS_CACHE.resolve(rhs)
            if v is None:
                v = shape_from_symbol(rhs)
            if v is not None:
                vals.append(v)
        if not vals:
            continue
        v = max(vals)
        if best is None or v > best:
            best, src = v, ("RoundedCornerShape(%s)" % args.strip().replace("\n", " "))
    if best is None:
        if src:
            return None, False, "圆角无法解析（变量 / 未知常量）", src
        return None, False, "__noshape__", ""
    if best >= PILL_MIN:
        return best, True, ">= %ddp，按药丸/全圆处理" % int(PILL_MIN), src
    return best, False, "", src


def suggest_tier(half):
    """挑一个**严格小于**半短边的档位。

    为什么要严格小于：圆角恰好等于半短边时，渲染结果已经是一个胶囊/圆 ——
    换成这个档位外观一点不变，只是把数字改好看，属「假建议」。这种一律不报。
    """
    best = None
    for name, val in TIERS:
        if val < half - 1e-6:
            best = (name, val)
    return ("Radius.%s(%gdp)" % best) if best else None


# --------------------------------------------------------------------------
# 6. 单文件扫描
# --------------------------------------------------------------------------
def scan_text(src, display_path, rows):
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

    seen = set()          # 同一条链只判一次（链里可能有多个 .height(）
    for m in SIZE_CALL_RE.finditer(masked):
        i = m.start()
        if i in seen:
            continue
        chain_start, chain_end = expand_chain(masked, i)
        chain = masked[chain_start:chain_end]
        if not chain:
            continue
        for j in range(chain_start, chain_end):
            seen.add(j)

        ws, hs = dims_in_chain(chain)
        if not (ws or hs):
            continue                       # 没有固定尺寸 -> 短边不可知，放行
        short = min(ws + hs)
        if short <= 0:
            continue

        radius, exempt, why, src_txt = shapes_in_chain(chain)
        row = {
            "file": display_path,
            "line": line_of(chain_start + chain.index(".")),
            "short": short,
            "radius": radius,
            "shapesrc": src_txt,
            "status": "",
            "reason": "",
            "suggest": "",
            "keys": "",
        }
        if exempt:
            row["status"] = "exempt"
            row["reason"] = why
            rows.append(row)
            continue
        if radius is None:
            if why == "__noshape__":
                hint = variable_shape_hint(chain)
                # 一层间接：`shape = btnShape`，而 `btnShape = RoundedCornerShape(Radius.btn.dp)`
                v = shape_from_symbol(hint) if hint else None
                allv = all_shape_values(hint) if hint else None
                if allv and all(x <= short / 2.0 + 1e-6 for x in allv):
                    # 全称成立：变量无论绑定到哪一个，都不超过半短边
                    row["status"] = "ok"
                    row["radius"] = max(allv)
                    row["reason"] = ("变量 %s 的所有可能取值（%s）都 <= 半短边 %gdp"
                                     % (hint, "/".join("%g" % x for x in sorted(allv)), short / 2.0))
                    rows.append(row)
                    continue
                if v is not None:
                    if v >= PILL_MIN:
                        row["status"] = "exempt"
                        row["reason"] = "%s -> CircleShape" % hint
                        rows.append(row)
                        continue
                    radius, src_txt = v, "%s (=%gdp)" % (hint, v)
                    row["radius"], row["shapesrc"] = radius, src_txt
                elif hint:
                    row["status"] = "unknown"
                    row["reason"] = "圆角来自变量 %s（静态判不了，不计入合规）" % hint
                    rows.append(row)
                    continue
                continue                      # 链里压根没有形状，与圆角无关
            row["status"] = "unknown"
            row["reason"] = why
            rows.append(row)
            continue
        if radius <= 0:
            row["status"] = "skip"
            row["reason"] = "圆角为 0"
            rows.append(row)
            continue
        half = short / 2.0
        tier = suggest_tier(half) if radius > half + 1e-6 else None
        if radius > half + 1e-6 and tier:
            row["status"] = "miss"
            row["reason"] = "圆角 %gdp > 短边 %gdp 的一半（%gdp）—— 会被裁成药丸/变形" % (
                radius, short, half)
            row["suggest"] = "改用 %s（若确实要全圆，显式写 Radius.pill 把意图说明白）" % tier
        elif radius > half + 1e-6:
            # 短边太小：任何档位都会把它裁成胶囊/圆，换成更小的档也不改变外观。
            # 这种情况**不算问题**（否则粗细不一的进度条/骨架行会天天报警），
            # 只在 --all 里留一条 advisory，供人顺手判断要不要显式写 pill/CircleShape。
            row["status"] = "advisory"
            row["reason"] = ("短边只有 %gdp，圆角 %gdp 已超过半短边 —— 实际渲染成胶囊/圆；"
                             "没有更小档位可换，故不计入问题" % (short, radius))
            row["suggest"] = "若是有意为之，建议显式写 Radius.pill / CircleShape"
        else:
            row["status"] = "ok"
            row["reason"] = "圆角 %gdp <= 半短边 %gdp" % (radius, half)
        row["keys"] = "%s:%d" % (display_path, row["line"])
        rows.append(row)


def scan_path(root):
    root = Path(root)
    files = sorted(root.rglob("*.kt"))
    texts = []
    for f in files:
        try:
            texts.append(f.read_text(encoding="utf-8"))
        except UnicodeDecodeError:
            texts.append("")
    global SYMS_CACHE
    SYMS_CACHE = collect_symbols(texts)

    rows = []
    for f, src in zip(files, texts):
        if not src:
            continue
        scan_text(src, str(f), rows)
    rows.sort(key=lambda d: (d["file"], d["line"]))
    misses = [r for r in rows if r["status"] == "miss"]
    return misses, rows, len(files)


# --------------------------------------------------------------------------
# 7. 反向验证
# --------------------------------------------------------------------------
FIXTURE_BAD = '''
@Composable
fun Bad() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.card)
    ) { Icon(iconRes = 1) }
}
'''

FIXTURE_GOOD = FIXTURE_BAD.replace("RoundedCornerShape(16.dp)", "RoundedCornerShape(8.dp)")

# 嵌套括号：圆角藏在 if/else 与 .then(...) 之后 —— 只有括号计数看得见
FIXTURE_NESTED = '''
@Composable
fun Nested(sel: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(if (sel) c.a else c.b, RoundedCornerShape(16.dp))
            .then(if (sel) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)))
    ) { Icon(iconRes = 1) }
}
'''

# 30dp 提示条：fillMaxWidth + height(30) —— 「上一轮把 md 提到 16」时正是它变药丸
FIXTURE_BAR_BAD = '''
@Composable
fun Bar(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.soft)
            .padding(horizontal = 10.dp)
    ) { Text(text = text) }
}
'''

FIXTURE_BAR_GOOD = FIXTURE_BAR_BAD.replace("RoundedCornerShape(16.dp)", "RoundedCornerShape(12.dp)")

FIXTURE_EXEMPT = '''
@Composable
fun Exempt() {
    Box(Modifier.size(20.dp).clip(CircleShape).background(c.a)) { Icon(iconRes = 1) }
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(percent = 50)).background(c.a)) { Icon(iconRes = 1) }
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).background(c.a)) { Icon(iconRes = 1) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.a)) { Text("x") }
}
'''


def self_test():
    import tempfile
    ok = True
    print("===== radius_audit 反向验证 =====")

    def run_on(text, label):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "probe.kt").write_text(text, encoding="utf-8")
            misses, rows, _ = scan_path(d)
        print("  [%s] 命中 %d 处" % (label, len(misses)))
        for r in misses:
            print("       probe.kt:%d | 短边 %gdp | 圆角 %gdp | %s"
                  % (r["line"], r["short"], r["radius"], r["suggest"]))
        return misses, rows

    def check(cond, ok_msg, bad_msg):
        nonlocal ok
        if cond:
            print("  PASS " + ok_msg)
        else:
            print("  FAIL " + bad_msg)
            ok = False

    # (1) 注入：size(24) + 圆角 16 -> 必须报
    fs, _ = run_on(FIXTURE_BAD, "注入·24dp 配 16dp 圆角")
    check(len(fs) == 1 and fs[0]["short"] == 24 and fs[0]["radius"] == 16,
          "注入必须报：24dp 元素配 16dp 圆角（>12）被拦下",
          "注入必须报：期望 1 处 24/16，实得 %r" % [(f["short"], f["radius"]) for f in fs])

    # (2) 改小：圆角 8 -> 必须过
    fs, _ = run_on(FIXTURE_GOOD, "改小·同上改 8dp")
    check(not fs, "改小必须过：圆角 8dp <= 12 后 0 命中",
          "改小必须过：仍有 %d 处命中" % len(fs))

    # (3) 嵌套括号：圆角藏在 if/else 与 .then() 里 -> 仍要报
    fs, _ = run_on(FIXTURE_NESTED, "嵌套·圆角在 if/else 与 .then() 里")
    check(len(fs) == 1 and fs[0]["short"] == 26 and fs[0]["radius"] == 16,
          "括号计数：.background(if..else.., shape) / .then(...) 里的圆角被正确读到",
          "括号计数：嵌套结构里漏读了圆角（%r）" % [(f["short"], f["radius"]) for f in fs])

    # (4) 30dp 提示条：fillMaxWidth + height(30) + 16dp 圆角 -> 必须报（上一轮真凶）
    fs, _ = run_on(FIXTURE_BAR_BAD, "事故复现·30dp 提示条配 16dp")
    check(len(fs) == 1 and fs[0]["short"] == 30,
          "事故复现：30dp 提示条配 16dp 圆角被拦下（短边取 height=30）",
          "事故复现：没拦住（%r）" % [(f["short"], f["radius"]) for f in fs])
    fs, _ = run_on(FIXTURE_BAR_GOOD, "事故复现·改回 12dp")
    check(not fs, "事故复现：改回 12dp（=当前 md 档）后 0 命中 —— 说明拆档后的现状是干净的",
          "事故复现：12dp 仍被拦（判据过严）")

    # (5) 放行项：CircleShape / percent=50 / 999 / 无固定尺寸
    fs, _ = run_on(FIXTURE_EXEMPT, "放行项")
    check(not fs, "放行项：CircleShape、percent=50、999dp、无固定尺寸 都不报",
          "放行项：误报了 %r" % [(f["short"], f["radius"]) for f in fs])

    print("===== %s =====" % ("全部通过" if ok else "有失败项"))
    return 0 if ok else 1


# --------------------------------------------------------------------------
# 8. main
# --------------------------------------------------------------------------
def main(argv):
    if "--self-test" in argv:
        return self_test()

    quiet = "--quiet" in argv
    root = Path(argv[argv.index("--path") + 1]) if "--path" in argv else ROOT

    misses, rows, nfiles = scan_path(root)

    baseline_path = None
    if "--baseline" in argv:
        baseline_path = Path(argv[argv.index("--baseline") + 1])
    elif "--update-baseline" in argv:
        baseline_path = Path(argv[argv.index("--update-baseline") + 1])
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(
            json.dumps(sorted(m["keys"] for m in misses), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print("已写入基线 %s（%d 条）" % (baseline_path, len(misses)))
        return 0

    baseline = set()
    if baseline_path and baseline_path.exists():
        try:
            baseline = set(json.loads(baseline_path.read_text(encoding="utf-8")))
        except Exception as e:
            print("基线读取失败 %s: %s" % (baseline_path, e))
            return 2

    new = [m for m in misses if m["keys"] not in baseline]

    if not quiet:
        print("===== 圆角 / 尺寸 比例审计 =====")
        for m in misses:
            mark = "  " if m["keys"] in baseline else "NEW"
            print("%s %s:%d | 短边 %gdp | 圆角 %gdp | %s"
                  % (mark, m["file"], m["line"], m["short"], m["radius"], m["shapesrc"]))
            print("      依据：%s" % m["reason"])
            print("      建议：%s" % m["suggest"])
        print()

    if "--all" in argv:
        print("===== 全量判定（含放行 / 解析不出 / 合规）=====")
        for r in rows:
            print("  %-7s %s:%d | 短边 %gdp | 圆角 %s | %s"
                  % (r["status"].upper(), r["file"], r["line"], r["short"],
                     ("%g" % r["radius"]) if r["radius"] is not None else "?",
                     r["reason"]))
        print()

    print("圆角审计：扫描 %d 个文件，命中 %d 处，其中新增 %d 处%s"
          % (nfiles, len(misses), len(new),
             "" if not baseline else "（基线 %d 条）" % len(baseline)))
    if quiet:
        for m in new:
            print("NEW %s:%d | 短边 %gdp | 圆角 %gdp" % (m["file"], m["line"], m["short"], m["radius"]))
    return 0 if not new else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
