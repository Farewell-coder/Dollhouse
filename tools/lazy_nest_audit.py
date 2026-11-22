#!/usr/bin/env python3
"""
Dollhouse · Lazy 组件嵌套滚动容器门禁

【为什么需要这个脚本 —— 一次真实的 CI 事故】
2026-11-22，`DesignScreenshotTest.dollGrid_realPageShape` 在 CI 上红：

    DhKit.Page(title = "人偶", onBack = {}) {     // ← scrollable 默认 true
        LazyVerticalGrid(...)                    // ← 本身纵向可滚动
    }

`DhKit.Page` 的 `scrollable` 参数默认 **true**，会把内容包进 `Column.verticalScroll`。
而 `LazyVerticalGrid` / `LazyColumn` 本身就是纵向可滚动组件，两者嵌套会抛：

    IllegalStateException: Vertically scrollable component was measured
    with an infinity maximum height

**这个异常在组合期抛出，编译期完全查不出来** —— 它只会让 `setContent` 静默中断
（表现是截图测试里 `capturedP` 为 null，真实场景是页面白屏或半截）。
真实页面 `DollListScreen.kt:167` 写的就是 `scrollable = false`，是测试漏抄了参数。

【判据】
扫 `DhKit.Page(` 调用的**花括号体**，若体内出现 Lazy 四组件之一，
而该 `Page(` 的实参里没有显式 `scrollable = false` → 报错。

【为什么还要看 Lazy 的 modifier 有没有限高】
`IllegalStateException` 的触发条件是「测得 infinity 最大高度」—— 只有**无限高**的
Lazy 才会抛。若 Lazy 自己带了 `heightIn(max = N)` / `height(N.dp)`，它的高度有界，
嵌在滚动容器里只是「内层滚完外层接着滚」，不会崩（真实例子：
`settings/ModelListPage.kt:186` 的 `LazyColumn(... heightIn(max = 420.dp))`，
一直这么跑也没事）。
所以判据是**两条同时满足**才报：
  ① Lazy 在 DhKit.Page 体内，且 Page 没写 scrollable = false；
  ② 该 Lazy 的 modifier **没有**限高（height / heightIn / aspectRatio / weight）。
第 ② 条是为了不误报 —— 误报会毁掉门禁的可信度（本脚本第一版就误报了上面那处，
故补上此条）。

【实现要点】
· 括号配对要**跨行**、要**跳过字符串与注释**，否则会把 `"}"` 当成闭合。
· 只认 `DhKit.Page` 的**具名实参**里的 scrollable，函数体内的局部变量不算
  （保守：宁可漏报，不可误报 —— 误报会毁掉门禁的可信度）。

【用法】
  python3 tools/lazy_nest_audit.py          # 人类可读
  python3 tools/lazy_nest_audit.py --quiet  # 只打结论
退出码：发现问题 = 1，否则 = 0。
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
QUIET = "--quiet" in sys.argv

LAZY_NAMES = ("LazyColumn", "LazyVerticalGrid", "LazyRow", "LazyHorizontalGrid")


def strip_noise(src: str) -> str:
    """把字符串与注释换成等长空格，保住所有下标与行号。

    【为什么必须保长】后面要用下标反推行号；若直接删除字符，所有下标都会漂。
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if two == "/*":
            depth = 1
            out[i] = out[i + 1] = " "
            i += 2
            while i < n and depth > 0:
                if src[i:i + 2] == "/*":
                    depth += 1
                elif src[i:i + 2] == "*/":
                    depth -= 1
                out[i] = " " if src[i] != "\n" else "\n"
                i += 1
            continue
        if src[i] == '"':
            out[i] = " "
            i += 1
            while i < n:
                if src[i] == "\\":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                if src[i] == '"':
                    out[i] = " "
                    i += 1
                    break
                out[i] = " " if src[i] != "\n" else "\n"
                i += 1
            continue
        i += 1
    return "".join(out)


def match_brace(src: str, open_idx: int):
    """从 src[open_idx] == '{' 起配平，返回配对 '}' 的下标；不匹配返回 None。"""
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def find_page_calls(src: str):
    """找出所有 `DhKit.Page(` 调用，返回 (调用起点, 实参区起点, 实参区终点, 函数体起, 函数体止)。"""
    calls = []
    for m in re.finditer(r"DhKit\.Page\s*\(", src):
        args_open = m.end() - 1          # '(' 的下标
        depth = 0
        i = args_open
        n = len(src)
        while i < n:
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        args = src[args_open + 1:i]
        # 实参后第一个 '{' 就是函数体
        j = src.find("{", i)
        if j < 0:
            continue
        end = match_brace(src, j)
        if end is None:
            continue
        calls.append((m.start(), args, j, end))
    return calls


def check_file(path: Path):
    src = path.read_text(encoding="utf-8", errors="ignore")
    clean = strip_noise(src)
    problems = []
    for start, args, body_open, body_close in find_page_calls(clean):
        body = clean[body_open:body_close]
        hit = next((n for n in LAZY_NAMES if re.search(r"\b" + n + r"\b", body)), None)
        if not hit:
            continue
        # 只认具名实参里的 scrollable = false
        if re.search(r"scrollable\s*=\s*false", args):
            continue
        # 该 Lazy 有没有给自己的 modifier 限高？有限高则不会抛 infinity 异常。
        bounded = False
        for m in re.finditer(r"\b(?:" + "|".join(LAZY_NAMES) + r")\s*\(", body):
            seg = body[m.end():m.end() + 400]
            # modifier = ... 那一段（到顶层逗号或左括号为止）
            mm = re.search(r"modifier\s*=\s*", seg)
            window = seg[mm.end():] if mm else seg[:200]
            if re.search(r"\.\s*(?:height|heightIn|aspectRatio|fillMaxHeight|weight)\s*\(", window):
                bounded = True
                break
        if bounded:
            continue
        line = clean.count("\n", 0, start) + 1
        col = start - (clean.rfind("\n", 0, start) + 1) + 1
        body_line = clean.count("\n", 0, body_open) + 1
        problems.append(
            f"{path.as_posix()}:{line}:{col} DhKit.Page 内含无限高的 {hit}"
            f"（函数体起于第 {body_line} 行），且实参未显式 scrollable = false"
        )
    return problems


def main():
    files = sorted(SRC.rglob("*.kt"))
    all_problems = []
    for f in files:
        all_problems.extend(check_file(f))

    if not QUIET:
        print("===== Lazy 嵌套滚动容器审计 =====")
        print(f"扫描 {len(files)} 个文件（{SRC}）")
        print(f"判据：DhKit.Page(...) 函数体内出现 {', '.join(LAZY_NAMES)} 之一，")
        print("      而实参未显式 scrollable = false → 报错")
        print()

    if all_problems:
        print(f"FAIL：{len(all_problems)} 处 Lazy 组件嵌在可滚动的 DhKit.Page 里")
        for p in all_problems:
            print(f"  {p}")
        print()
        print("修法：给该 DhKit.Page 加 scrollable = false（由 Lazy 自己滚），")
        print("      或把 Lazy 移出 Page 的滚动容器。")
        return 1

    if not QUIET:
        print("PASS：没有 Lazy 组件被嵌进可滚动的 DhKit.Page")
    return 0


if __name__ == "__main__":
    sys.exit(main())
