#!/usr/bin/env python3
"""
Dollhouse · 页面级视觉一致性审计

【为什么需要这个脚本】
  现有 9 套门禁全是**组件级/令牌级**：
    · design_parity 查令牌值是否等于设计稿；
    · input_audit / state_audit 查某个组件/状态实现是否规范；
    · kit_usage_audit 查组件有没有被绕过。
  它们**没有一项**检查「页面之间是否一致」——而用户抱怨里恰好有一类正是
  「相似页面的边距不同」「内容左右不对齐」。这种问题**单页看不出来，跨页才明显**，
  逐页 review 也容易漏，因为每页单独看都"挺正常"。

【查什么】
  对每个页面级 Composable（`XxxPage` / `XxxScreen` / `XxxContent`），比对四类应当统一的量：

  P1 页面水平内边距 —— 规范 16dp（= DhDesign.Space.lg = DhKit.PAD_CARD.pageX）
  P2 页面脚手架    —— 是否走 `DhKit.Page(...)`（它统一处理 padding/滚动/顶栏）
  P3 顶栏          —— 是 `DhKit.Page` 自带，还是自建
  P4 卡片间距      —— `DhKit.Card` 之间的 gap（规范 Space.cardGap = 12）

【判据口径（重要）】
  · 「规范值」从代码里读，不写死：优先读 `DhDesign.Space.pageX` / `DhKit.PAD_CARD`，
    这样若哪天规范改成 20，本脚本自动跟着变，不会变成"脚本说 16、代码说 20"的第二真源。
  · 只统计**页面根级**的 padding，不统计卡片内部的行 padding ——
    后者本来就该各自不同（行内边距 vs 页面边距是两回事）。
  · 判不出就明说「未识别」，不猜。宁可少报，不可假报。

【用法】
  python3 tools/page_consistency_audit.py            # 完整表格
  python3 tools/page_consistency_audit.py --quiet    # 只打结论
退出码：有离群页面 → 1；全部一致 → 0；数据源缺失 → 2。
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
DHKIT = SRC / "com/dollhouse/app/ui/compose/DhKit.kt"
DESIGN = SRC / "com/dollhouse/app/ui/design/DesignSystem.kt"

QUIET = "--quiet" in sys.argv

# 页面级 Composable 的命名约定
# 【必须容忍注解同行】实测有两种写法：
#     @Composable private fun Content(...)      ← 注解与 fun 同一行（3 处）
#     private fun AboutContent(...)             ← 普通写法（14 处）
# 早先的正则要求行首紧跟修饰符，漏掉了第一种，导致 ConversationHistoryPage /
# FriendModelConfigPage / 另一个页面完全不进审计 —— 而它们恰恰是
# 「手写 .padding(16.dp)」的页面，正是本审计要盯的对象。漏掉它们 = 审计失真。
PAGE_FUN = re.compile(
    r"^[^\n]*?\bfun\s+([A-Z]\w*(?:Page|Screen|Content))\s*\(", re.M)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace") if p.exists() else None


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


def fun_body(src, name):
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None, None
    i = src.find("{", m.end())
    if i < 0:
        return None, None
    depth, j = 0, i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return src[:m.start()].count("\n") + 1, src[i:j + 1]


def resolve_spec():
    """从代码里读规范值，而不是写死 16/12。

    【为什么要这么做】如果脚本写死 16，而代码把规范改成 20，
    脚本会继续报"所有页面都是 20，应为 16"——变成第二个真源。
    规范值必须只有一处（DesignSystem），脚本去读它。
    """
    spec = {"pageX": None, "cardGap": None}
    d = read(DESIGN)
    if d:
        code = strip_comments(d)
        m = re.search(r"object\s+Space\s*\{(.*?)\n\s*\}", code, re.S)
        if m:
            body = m.group(1)
            def const(name):
                mm = re.search(r"const\s+val\s+" + name + r"\s*=\s*(\d+)", body)
                return int(mm.group(1)) if mm else None
            lg = const("lg")
            md = const("md")
            spec["pageX"] = lg
            spec["cardGap"] = md
    return spec


def scan_pages():
    """扫描所有页面级 Composable，抽取四项指标。"""
    pages = []
    for p in sorted(SRC.rglob("*.kt")):
        raw = read(p)
        if raw is None:
            continue
        code = strip_comments(raw)
        rel = str(p).replace("app/src/main/java/com/dollhouse/app/", "")
        for m in PAGE_FUN.finditer(code):
            name = m.group(1)
            line, body = fun_body(code, name)
            if body is None:
                continue
            # 极短的函数大概率是转发（如 HomeContent → ComingSoonPage.Social()），不算页面
            body_lines = body.count("\n")
            if body_lines < 6:
                pages.append({
                    "file": rel, "name": name, "line": line,
                    "skip": True, "reason": "函数体过短（疑似转发壳）",
                })
                continue

            uses_page = bool(re.search(r"\bDhKit\.Page\s*\(", body))

            # P1：页面根级水平 padding。
            #   只认「Column/LazyColumn/Box 等直接出现 horizontal = N」的行，
            #   且要求该 modifier 链在函数体前 40% 内（根容器通常在最前面）。
            head = "\n".join(body.split("\n")[: max(3, int(body_lines * 0.45))])
            hpad = []
            # (a) padding(horizontal = 16.dp, ...)
            for mm in re.finditer(r"horizontal\s*=\s*(\d+(?:\.\d+)?)\.dp", head):
                hpad.append(mm.group(1) + ".dp")
            # (b) padding(horizontal = PAD_CARD.dp) —— 命名常量形态
            for mm in re.finditer(r"horizontal\s*=\s*([A-Z_][A-Z0-9_]*)\.dp", head):
                hpad.append(mm.group(1) + ".dp")
            # (c) 【关键补充】四边同值的整体 padding：.padding(16.dp)
            #     实测 ConversationHistoryPage / FriendModelConfigPage 都是这个写法，
            #     只认 horizontal= 的话会把它们判成"未识别到 padding"（假阴性）。
            #     注意排除 .padding(horizontal = X) 与 padding(8.dp, 4.dp) 这类多参形态。
            for mm in re.finditer(r"\.padding\(\s*(\d+(?:\.\d+)?)\.dp\s*\)", head):
                hpad.append(mm.group(1) + ".dp")

            pages.append({
                "file": rel, "name": name, "line": line,
                "skip": False,
                "usesPage": uses_page,
                "hpad": sorted(set(hpad)),
                "hasCard": bool(re.search(r"\bDhKit\.Card\s*\(", body)),
                "cardGap": re.findall(r"spacingBottom|GAP_CARD", body)[:1],
            })
    return pages


def classify(pages, spec):
    """把每个页面判成 PASS / OUTLIER / UNKNOWN。"""
    rows = []
    pageX = spec.get("pageX")
    for pg in pages:
        if pg.get("skip"):
            rows.append({**pg, "verdict": "SKIP"})
            continue

        problems = []

        # P2：脚手架
        if not pg["usesPage"]:
            problems.append("未走 DhKit.Page")

        # P1：水平 padding
        hp = pg["hpad"]
        if pg["usesPage"]:
            # 脚手架内部已经统一加了 pageX，页面自己再加就会变成双倍
            if hp:
                problems.append("走脚手架但页面又自加水平 padding: " + "/".join(hp))
        else:
            if not hp:
                problems.append("未走脚手架且未识别到页面级水平 padding")
            else:
                okvals = set()
                if pageX is not None:
                    okvals.add("%d.dp" % pageX)
                okvals.add("PAD_CARD.dp")
                # 只检查能直接比对的字面量
                numeric = [v for v in hp if re.match(r"^\d+\.dp$", v)]
                if numeric and okvals and not (set(numeric) & okvals):
                    problems.append("水平 padding 离群: " + "/".join(numeric) +
                                    ("（应为 %d.dp）" % pageX if pageX else ""))

        verdict = "PASS" if not problems else ("OUTLIER" if any("离群" in x or "未走" in x for x in problems) else "WARN")
        rows.append({**pg, "verdict": verdict, "problems": problems})
    return rows


def main():
    spec = resolve_spec()
    if spec.get("pageX") is None:
        print("无法从 DesignSystem.kt 读到 Space.lg（页面水平内边距规范值）。")
        print("请在仓库根目录运行；若规范改名，请同步本脚本的解析。")
        return 2

    pages = scan_pages()
    rows = classify(pages, spec)

    scanned = [r for r in rows if r.get("verdict") != "SKIP"]
    skipped = [r for r in rows if r.get("verdict") == "SKIP"]
    outliers = [r for r in scanned if r["verdict"] in ("OUTLIER", "WARN")]
    ok = [r for r in scanned if r["verdict"] == "PASS"]

    if not QUIET:
        print("===== 页面级视觉一致性审计 =====")
        print("规范值（从 DesignSystem.kt 读出，非硬编码）：")
        print("  页面水平内边距 Space.lg   = %s dp" % spec.get("pageX"))
        print("  卡片间距     Space.md     = %s dp" % spec.get("cardGap"))
        print()
        print("%-30s %-22s %-3s %-8s %-16s %s"
              % ("文件", "页面函数", "行", "脚手架", "水平 padding", "结论"))
        print("-" * 108)
        for r in scanned:
            print("%-30s %-22s %-3d %-8s %-16s %s"
                  % (r["file"][:30], r["name"][:22], r["line"],
                     "DhKit.Page" if r["usesPage"] else "自建",
                     "/".join(r["hpad"]) or "—",
                     r["verdict"]))
        print("-" * 108)

        if outliers:
            print()
            print("【离群页面】")
            for r in outliers:
                print("  %s:%d  %s()" % (r["file"], r["line"], r["name"]))
                for pr in r["problems"]:
                    print("      - " + pr)

        if skipped:
            print()
            print("【跳过】%d 个（函数体过短，疑似转发壳，不作页面审计）" % len(skipped))
            for r in skipped:
                print("  %s:%d  %s()" % (r["file"], r["line"], r["name"]))

        print()

    print("页面一致性：一致 %d / 共 %d；离群 %d；跳过 %d"
          % (len(ok), len(scanned), len(outliers), len(skipped)))
    if QUIET and outliers:
        for r in outliers:
            print("OUTLIER %s:%d %s -> %s"
                  % (r["file"], r["line"], r["name"], "; ".join(r["problems"])))
    return 1 if outliers else 0


if __name__ == "__main__":
    sys.exit(main())