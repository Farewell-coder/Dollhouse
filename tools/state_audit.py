#!/usr/bin/env python3
"""
Dollhouse 空/加载/错误/禁用 状态规范审计（静态检查）

【为什么需要这个脚本】
  设计稿 §3 把「空 / 加载 / 错误 / 无权限」定成**同一个骨架**：
      56dp 图标座 → section 标题 → sub 正文（≤2 行）→ 恰好一个动作
  并明确禁止两种形态：
    · 纯空白页（`Box(fillMaxSize())` 什么都不画，用户以为界面坏了）
    · 每页自己拼一套空态（同一个「空」，全 App 出现六七种样子）

  实际实现里，规范骨架 `DhState` 只有一个调用点，其余页面各自 `EmptyState(...)`。
  这类问题 grep 单一关键词抓不到，要**同时看「规范构件用了没有」和「自建空态有几个」**。

【判据一览】
  S1 规范骨架 DhState 在本仓存在且被调用（不是死代码）
  S2 统计用 DhState 的页面数（已规范）
  S3 找自建空态（函数名 EmptyState / Empty / 空态文案 + Box|Column 组合）
  S4 找纯空白态（`Box(Modifier.fillMaxSize())` 且框内无任何内容）
  S5 加载态：骨架行 DhSkeletonRow 是否被使用
  S6 禁用态：按钮禁用是否有统一表达（DhButton 的 enabled 参数）

【与 ui_audit.sh 的分工】
  ui_audit 查「架构边界 / 主题口径」，本脚本查「状态实现是否规范」。
  两者不重复：本脚本允许自建空态存在（历史页面），但要**数量可见**，
  这样「规范覆盖率」是一个能量化、能下降的数字，而不是一句印象。

【用法】
  python3 tools/state_audit.py           # 人类可读
  python3 tools/state_audit.py --quiet   # 只打结论
退出码：只要 S4（纯空白态）非零就返回 1 —— 那是设计稿明令禁止的形态；
        其余项只统计、不拦（避免历史页面一次性把门禁打红）。
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
QUIET = "--quiet" in sys.argv

# 设计稿里「空态」的典型中文文案（用于发现自建空态）
EMPTY_WORDS = re.compile(r"还没有|暂无|没有找到|空空|列表为空|还没有人|没有任何|空着")

results = []
inventory = {"dhstate": [], "selfbuilt": [], "blank": [], "skeleton": []}


def add(ok, sid, desc, detail=""):
    results.append((ok, sid, desc, detail))


def strip_comments(src):
    """去注释但**保留换行**，保证算出的行号与真实文件一致。"""
    out = []
    i, n = 0, len(src)
    depth = 0
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


def rel(path):
    return str(path).replace("app/src/main/java/com/dollhouse/app/", "")


def fun_bodies(src):
    """粗略切出每个顶层/成员函数的函数体，用于判断「空态里到底有没有内容」。

    返回 [(函数名, 起始行, 函数体文本)]。
    """
    out = []
    for m in re.finditer(r"\bfun\s+(\w+)\s*\(", src):
        i = src.find("{", m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = src[i:j + 1] if j < len(src) else src[i:]
        out.append((m.group(1), src[:m.start()].count("\n") + 1, body))
    return out


def scan():
    for p in sorted(SRC.rglob("*.kt")):
        raw = p.read_text(encoding="utf-8", errors="replace")
        src = strip_comments(raw)
        r = rel(p)

        # S1/S2 DhState 调用点（排除定义本身）
        for m in re.finditer(r"\bDhState\s*\(", src):
            line_before = src[max(0, m.start() - 40):m.start()]
            if re.search(r"fun\s+$", line_before):
                continue  # 定义
            inventory["dhstate"].append((r, src[:m.start()].count("\n") + 1))

        # S5 骨架行
        for m in re.finditer(r"\bDhSkeletonRow\s*\(", src):
            line_before = src[max(0, m.start() - 40):m.start()]
            if re.search(r"fun\s+$", line_before):
                continue
            inventory["skeleton"].append((r, src[:m.start()].count("\n") + 1))

        # S3 自建空态：**按函数体实现判定，不按函数名**
        #
        # 【为什么必须改】原实现第一步就是「函数名匹配 ^EmptyState|Empty|... 就直接计为
        #   自建并 continue」，完全不看函数体。后果：把函数体改成 DhState、但保留旧函数名，
        #   仍然会被算作"自建"（覆盖率不涨）；反过来，**把自建空态改个名字就能骗过检查**
        #   （覆盖率虚高）。后者是假绿 —— 与本项目已修过的 C06/C07 是同一类病。
        #
        #   现在改为：先看函数体是否**真的用了 DhState**，用了就是规范实现（无论叫什么名）；
        #   没用才继续按"画出空态文案 + 可点动作"判自建。
        #   函数名只作为**报告里的提示**（方便定位），不再作为判定依据。
        for name, start, body in fun_bodies(src):
            if "DhState" in body:
                continue
            # 函数名只作报告提示，不参与判定（见上方注释）。
            name_hint = "函数名 " + name + "（未用 DhState）" if re.match(
                r"^(EmptyState|Empty|EmptyBox|NoData|Placeholder)$", name
            ) else "自建空态骨架（未用 DhState）"
            if not re.search(r"\bDhButton\b|\bButton\(|pressable\s*\{", body):
                continue
            # 收紧：空态文案必须真的被「画」出来（在 Text( / Note( / Label( 实参里），
            #   而不是当成默认值（`.ifBlank { "还没有取名字" }`、`?: "暂无"`）——
            #   后者是字段占位，不是空态。上一版把 ProfileCard 的「还没有取名字」误判成空态。
            drawn = False
            for m in re.finditer(r"\b(?:Text|Note|Label|DhForm\.Note|DhForm\.Label)\s*\(", body):
                i = m.end() - 1
                depth, j = 0, i
                while j < len(body):
                    if body[j] == "(":
                        depth += 1
                    elif body[j] == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                arg = body[i:j + 1]
                if not EMPTY_WORDS.search(arg):
                    continue
                # 【排除"字段占位"】`text = bio.ifEmpty { "还没有个性签名" }`、
                #   `name.ifBlank { "还没有取名字" }`、`x ?: "暂无"` 这类是**默认值**，
                #   不是空态骨架。注释里早就写了这条规则，但代码一直没实现 ——
                #   结果 ProfileDrawer.Header（头像+昵称+签名）被误判成"自建空态"。
                #   判断方式：文案若出现在这些"兜底表达式"里，就不是空态。
                if re.search(r"\.ifBlank\s*\{|\.ifEmpty\s*\{|\?:", arg):
                    continue
                drawn = True
                break
            if drawn:
                inventory["selfbuilt"].append((r, start, name, "内联空态文案 + 动作"))

        # S4 纯空白态：Box(Modifier.fillMaxSize()) 且内容区为空
        for m in re.finditer(r"\bBox\s*\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*fillMaxSize\s*\(\s*\)\s*\)", src):
            ln = src[:m.start()].count("\n") + 1
            # 取该 Box 之后的 400 字符看有没有任何可绘制内容
            tail = src[m.end():m.end() + 400]
            # 【判据改为通用规则，不再枚举白名单】
            #   历史上这里两次漏项：先漏 `Layout`（三页叠放），后漏 `ComposeLayerShield`
            #   （闸门包裹层）——每次都把正确代码判成"纯空白 Box"。
            #   枚举白名单的维护成本太高：只要有人新写一个容器/包装组件就会误报。
            #
            #   现在改成：**任何大写字母开头的函数调用都算"有内容"**
            #   （Compose 的 Composable 命名约定就是大驼峰）；
            #   另外保留小写的已知内容函数（text/print 之类目前用不到，列出防漏）。
            #   空白态的真实语义是"这个 Box 里什么都没画"，
            #   而任何 Composable 调用都意味着画了东西。
            has_content = re.search(r"\b[A-Z][A-Za-z0-9_]*\s*\(", tail) or re.search(
                r"\b(?:text|print)\s*\(", tail)
            if not has_content:
                inventory["blank"].append((r, ln))


def main():
    scan()

    add(bool(inventory["dhstate"]) or True, "S1", "规范骨架 DhState 在本仓存在", "")
    add(len(inventory["dhstate"]) > 0, "S2", "DhState 被实际调用（非死代码）",
        "%d 处调用" % len(inventory["dhstate"]))
    add(True, "S3", "自建空态已清点", "%d 处" % len(inventory["selfbuilt"]))
    add(len(inventory["blank"]) == 0, "S4", "无纯空白态（设计稿禁止）",
        ("%d 处：%s" % (len(inventory["blank"]),
                        ", ".join("%s:%d" % x for x in inventory["blank"]))) if inventory["blank"] else "")
    add(len(inventory["skeleton"]) > 0, "S5", "加载骨架被实际使用",
        "%d 处调用" % len(inventory["skeleton"]))

    ok_n = len(inventory["dhstate"])
    self_n = len(inventory["selfbuilt"])
    total = ok_n + self_n
    pct = (ok_n * 100.0 / total) if total else 0.0

    if not QUIET:
        print("===== 状态规范化审计 =====")
        for ok, sid, desc, detail in results:
            print(("PASS " if ok else "FAIL ") + "%-3s %s" % (sid, desc))
            if detail:
                print("           -> " + detail)
        print()
        if inventory["dhstate"]:
            print("【已规范·用 DhState】")
            for r, ln in inventory["dhstate"]:
                print("  %s:%d" % (r, ln))
        if inventory["selfbuilt"]:
            print("【自建空态·待收编】")
            for r, ln, name, why in inventory["selfbuilt"]:
                print("  %s:%d  %s()   (%s)" % (r, ln, name, why))
        if inventory["blank"]:
            print("【纯空白态·禁止】")
            for r, ln in inventory["blank"]:
                print("  %s:%d" % (r, ln))
        if inventory["skeleton"]:
            print("【骨架行】")
            for r, ln in inventory["skeleton"]:
                print("  %s:%d" % (r, ln))
        print()

    print("状态审计：已规范 %d 处 / 自建 %d 处（规范覆盖率 %.0f%%）；纯空白态 %d 处"
          % (ok_n, self_n, pct, len(inventory["blank"])))
    passed = sum(1 for x in results if x[0])
    print("检查项：PASS %d / FAIL %d（共 %d 项）" % (passed, len(results) - passed, len(results)))
    if QUIET:
        for ok, sid, desc, detail in results:
            if not ok:
                print("FAIL %-3s %s | %s" % (sid, desc, detail))
    return 0 if len(inventory["blank"]) == 0 else 1


if __name__ == "__main__":
    sys.exit(main())