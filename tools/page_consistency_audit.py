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
# 【两步：先抓所有大写函数名，再用 Python 判断后缀】
#   为什么不把后缀并进正则：实测 `([A-Z]\w*(?:Page|Screen|Content))` 在
#   `@Composable private fun Content(` 上**匹配不到** —— `\w*` 已吃掉 "Content"，
#   交替组无字符可回溯，引擎直接判失败（ abstinence 而非懒惰问题）。
#   分成两步后逻辑一目了然，也更容易维护新增后缀（如 Sheet/Dialog 若将来要纳入）。
#
# 【前缀必须是贪婪的 [^\n]*】懒惰版会止步于行内第一个能配对的位置，
#   同样会漏掉带注解的函数。贪婪版把注解与修饰符一并吞进前缀。
PAGE_FUN = re.compile(
    r"^[^\n]*\bfun\s+([A-Z][A-Za-z0-9_]*)\s*\(", re.M)

# 视作"页面级"的后缀
PAGE_SUFFIXES = ("Page", "Screen", "Content")


def is_page_name(name):
    return name.endswith(PAGE_SUFFIXES)


# ===========================================================================
# 合理例外白名单
#
# 【为什么需要】本脚本按「页面级命名 + 布局特征」推断，会命中三类**不是页面**的东西：
#   · 脚手架/组件自身的函数（DhKit.Page 定义）
#   · 桌宠悬浮层（pet/ 下自绘 View，Compose 不适合，明确保留）
#   · 全屏画布 / 弹层（本来就不该有页面级 16dp 边距）
# 把它们硬改成"走脚手架 + 16dp"是**错的**——那会破坏这些层的既有语义。
# 因此登记为例外，只报告理由、不计入离群。
#
# 【写进这里的门槛】必须说明"为什么它不该遵循页面规范"，不接受"暂时不改"。
# ===========================================================================
EXEMPT = {
    ("ui/compose/DhKit.kt", "Page"):
        "这是页面脚手架**自身**的定义（DhKit.Page），不是页面。",
    ("pet/DollHomeOverlay.kt", "Content"):
        "桌宠悬浮层：自绘 View 覆盖在桌面上，没有页面栈、没有顶栏，不适用页面级边距。",
    ("pet/DollRangeOverlay.kt", "Content"):
        "同上（桌宠手势范围悬浮层）。",
    ("ui/chat/ChatUi.kt", "ChatContent"):
        "全屏聊天画布：消息区左右留 12dp、气泡自带内边距；"
        "页面边距由消息列表 contentPadding 与输入条各自控制，加统一 16dp 会把气泡挤窄。"
        "（且该文件正由 provider-shell 做去 View 改造，本任务不碰。）",
    ("ui/nav/NavHost.kt", "RouteContent"):
        "导航宿主：只做路由分发，本身不渲染页面内容（各页面自带 PADDING）。",
    ("ui/home/ComingSoonPage.kt", "Content"):
        "占位页：40dp 是刻意的大留白（居中一句话），不是页面级内容边距。",
    ("ui/chat/ChatListScreen.kt", "Screen"):
        "底部 Tab **根页**：用 DhKit.TabTopBar + 自己的 LazyColumn，"
        "列表左右边距由列表 contentPadding 控制（16dp），不套二级页脚手架。",
    ("ui/home/ContactScreen.kt", "Content"):
        "底部 Tab **根页**（人偶 Tab）：同 ChatListScreen，用 TabTopBar + LazyColumn，"
        "二级页脚手架会多画一层顶栏。",
    ("ui/doll/DollLookTab.kt", "Content"):
        "人偶编辑页内的**栏位（Tab）**：被 DollEditScreen / FriendAppearancePage 当作子内容调用，"
        "不承担页面级顶栏与边距，卡片自带内边距。",
    ("ui/chat/ChatUi.kt", "ChatScreen"):
        "全屏聊天画布：整幅界面被 Box + clip(16dp 圆角) 包裹（宿主 ChatPanel 负责裁切），"
        "消息区左右 12dp、气泡自带内边距；套页面脚手架会与既有 TopBarRow 重复出两层顶栏。"
        "该文件正由 provider-shell 做去 View 阶段 2/3，本任务不改动它。",
}


def is_exempt(rel, name):
    return (rel, name) in EXEMPT


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


def _root_container_head(body):
    """取「页面根容器的 modifier 链」这一段文本（不含其内部内容）。

    【为什么必须限定范围】页面函数体里既有"页面边距"，也有"内容/卡片/图片的内边距"。
    两者的正确值完全不同（页面 16dp、图片 48dp、行内 10dp 都合理）。
    只取根容器的 modifier 链，才能只量页面边距。

    做法：从函数体第 1 行起找第一个 `DhKit.Page(` / `Column(` / `Box(` / `LazyColumn(` /
    `LazyVerticalGrid(`，取到该调用之后第一个 `{` 为止（modifier 链就在这段里）。
    """
    lines = body.split("\n")
    start = None
    for i, ln in enumerate(lines):
        if re.search(r"\b(DhKit\.Page|Column|Box|LazyColumn|LazyVerticalGrid)\s*\(", ln):
            start = i
            break
    if start is None:
        return ""
    seg = []
    depth = 0
    for ln in lines[start:start + 40]:
        seg.append(ln)
        depth += ln.count("{") - ln.count("}")
        # 遇到根容器的 `{` 即停（modifier 链已经读完）
        if depth > 0:
            break
    return "\n".join(seg)


def scan_pages():
    """扫描所有页面级 Composable，抽取四项指标。

    【"独立页面"与"页内子视图"的区分——这一步很关键】
      命名约定 `XxxPage/Screen/Content` 会同时命中两类东西：
        · 真正的独立页面（走页面栈 / 有自己的顶栏与滚动）
        · 页面内的**状态分支子视图**（例如 DeveloperOptionsPage 里
          `Screen.LOGS -> LogsPage(...)` / `Screen.DETAIL -> DetailPage(...)`）
      后者不承担页面级 padding 与脚手架（它们活在宿主的容器里），
      拿页面标准去量它们是**误报**。第一版就是这样把 3 个子视图判成离群的。

      【如何判定】若某函数在同文件内**被另一个页面级函数调用**，
      说明它是子视图，标为 NESTED 并从页面审计中排除（只列出、不计离群）。
    """
    pages = []
    for p in sorted(SRC.rglob("*.kt")):
        raw = read(p)
        if raw is None:
            continue
        code = strip_comments(raw)
        rel = str(p).replace("app/src/main/java/com/dollhouse/app/", "")
        for m in PAGE_FUN.finditer(code):
            name = m.group(1)
            if not is_page_name(name):
                continue
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
            # 【只取"页面根容器"那一段，不是前 45%】
            #   早先取前 45% 行，会抓到深处的内容 padding：
            #   DollHomePage 的 `.padding(48.dp)` 其实是**人偶贴图**的内边距（被误报成页面离群）；
            #   DollAnimPage 的 10/12dp 也都在卡片内部。
            #   正确做法：从函数体开头往下找**第一个**根容器（Column/Box/LazyColumn/DhKit.Page），
            #   只在这个容器的 modifier 链范围内取 padding，遇到它的 `{` 就停。
            head = _root_container_head(body)
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

            # 脚手架内容区的水平 padding（只看前 12 行，避免深入卡片内部）
            inner_pad = []
            if uses_page:
                m_page = re.search(r"\bDhKit\.Page\s*\(", body)
                if m_page:
                    tail = body[m_page.start():]
                    br = tail.find("{")
                    inner_lines = tail[br:].split("\n")[1:13] if br >= 0 else []
                    for ln in inner_lines:
                        for mm in re.finditer(r"horizontal\s*=\s*(\d+(?:\.\d+)?)\.dp", ln):
                            inner_pad.append(mm.group(1) + ".dp")
                        for mm in re.finditer(r"\.padding\(\s*(\d+(?:\.\d+)?)\.dp\s*\)", ln):
                            inner_pad.append(mm.group(1) + ".dp")

            pages.append({
                "file": rel, "name": name, "line": line,
                "skip": False,
                "usesPage": uses_page,
                "innerPadH": sorted(set(inner_pad)),
                "hpad": sorted(set(hpad)),
                "hasCard": bool(re.search(r"\bDhKit\.Card\s*\(", body)),
                "cardGap": re.findall(r"spacingBottom|GAP_CARD", body)[:1],
            })

    # ------------------------------------------------------------------
    # 嵌套判定：用「调用图根节点」法，而不是逐条猜方向
    #
    # 【为什么不用"谁调用谁"直接判断】同文件内的页面级函数有两种相反形态：
    #   · ChatListScreen:      Content(2 行)   → Screen(长)     ← 壳在前，真页在后
    #   · DeveloperOptionsPage: Content(长分派) → LogsPage(中)   ← 真页在前，子视图在后
    #   只看调用方向会把其中一种判错（第一版就错了两次，方向各错一回）。
    #
    # 【根节点法】在同文件的「页面级函数调用图」里：
    #   1. 没有任何同文件页面级函数调用它的 → 是**根**；
    #   2. 根如果是薄壳（自身函数体很短、且只转调一个页面级函数）→ 真正的页面是它调的那个；
    #   3. 根如果不是薄壳 → 它就是真正的页面，其余被它调用/间接调用的都是**页内子视图**。
    #   这与 Compose 的实际组织方式一致：页面入口只有一个，其余是同页内的状态分支。
    # ------------------------------------------------------------------
    by_file = {}
    for pg in pages:
        by_file.setdefault(pg["file"], []).append(pg)

    for rel, group in by_file.items():
        raw = read(Path("app/src/main/java/com/dollhouse/app") / rel) or ""
        code = strip_comments(raw)
        info = {}
        for g in group:
            body = fun_body(code, g["name"])[1] or ""
            info[g["name"]] = {
                "lines": body.count("\n"),
                "calls": set(),
            }
        # 建边：a 的体内出现 b( → a calls b
        for g in group:
            body = fun_body(code, g["name"])[1] or ""
            for other in group:
                if other["name"] == g["name"]:
                    continue
                if re.search(r"\b" + re.escape(other["name"]) + r"\s*\(", body):
                    info[g["name"]]["calls"].add(other["name"])

        called = set()
        for v in info.values():
            called |= v["calls"]
        roots = [g["name"] for g in group if g["name"] not in called]

        # 选真正的页面入口：优先非薄壳的根；若所有根都是薄壳，取它调用的那个
        entry = None
        for r in roots:
            if info[r]["lines"] >= 8 or not info[r]["calls"]:
                entry = r
                break
        if entry is None and roots:
            r = roots[0]
            nxt = sorted(info[r]["calls"])
            entry = nxt[0] if nxt else r

        # 从 entry 出发做可达性：可达的都是"同一页面内的子视图"
        reach = set()
        stack = [entry] if entry else []
        while stack:
            cur = stack.pop()
            for nxt in info.get(cur, {}).get("calls", ()):
                if nxt not in reach and nxt != entry:
                    reach.add(nxt)
                    stack.append(nxt)

        for g in group:
            name = g["name"]
            if name == entry:
                continue                      # 真正的页面入口，保留
            if name in reach:
                g["skip"] = True
                g["reason"] = "页内子视图（同文件 %s() 的调用链内）" % entry
            else:
                # 既不是入口也不在调用链：薄壳无布局，跳过
                g["skip"] = True
                g["reason"] = "未参与 %s() 调用链（疑似壳/未使用）" % (entry or "?")

    return pages


def classify(pages, spec):
    """把每个页面判成 PASS / OUTLIER / UNKNOWN。"""
    rows = []
    pageX = spec.get("pageX")
    for pg in pages:
        if pg.get("skip"):
            rows.append({**pg, "verdict": "SKIP"})
            continue
        if is_exempt(pg["file"], pg["name"]):
            rows.append({**pg, "verdict": "EXEMPT", "reason": EXEMPT[(pg["file"], pg["name"])]})
            continue

        problems = []

        # P2：脚手架
        if not pg["usesPage"]:
            problems.append("未走 DhKit.Page")
        else:
            # 【走脚手架后仍自加水平 padding】= 双倍边距，这是跨页不一致的常见来源。
            #   早先 _root_container_head 在 `DhKit.Page(` 的 `{` 处就停了，
            #   只能看到脚手架那一层，**看不见脚手架内部又包一层 Column(padding)**。
            #   自测正好命中这个盲区：注入 `Column(Modifier.padding(horizontal = 12.dp))`
            #   后脚本仍报 0 离群。现在单独扫描脚手架内容区的前若干行。
            inner = pg.get("innerPadH") or []
            if inner:
                problems.append("走脚手架但内容区又自加水平 padding: " + "/".join(inner))

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

    skipped = [r for r in rows if r.get("verdict") == "SKIP"]
    exempt = [r for r in rows if r.get("verdict") == "EXEMPT"]
    scanned = [r for r in rows if r.get("verdict") not in ("SKIP", "EXEMPT")]
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

        if exempt:
            print()
            print("【合理例外】%d 个（不适用页面规范，不计离群）" % len(exempt))
            for r in exempt:
                print("  %s:%d  %s()" % (r["file"], r["line"], r["name"]))
                print("      → " + r["reason"])

        if skipped:
            print()
            print("【跳过】%d 个（转发壳 / 页内子视图，不作页面级审计）" % len(skipped))
            for r in skipped:
                print("  %s:%d  %s()  —— %s" % (r["file"], r["line"], r["name"], r["reason"]))

        print()

    print("页面一致性：一致 %d / 共 %d；离群 %d；合理例外 %d；跳过 %d"
          % (len(ok), len(scanned), len(outliers), len(exempt), len(skipped)))
    if QUIET and outliers:
        for r in outliers:
            print("OUTLIER %s:%d %s -> %s"
                  % (r["file"], r["line"], r["name"], "; ".join(r["problems"])))
    return 1 if outliers else 0


if __name__ == "__main__":
    sys.exit(main())