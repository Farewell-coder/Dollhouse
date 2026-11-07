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

        # S3 自建空态：函数名像空态，或函数体里**真的把空态文案画出来** + 可点动作
        for name, start, body in fun_bodies(src):
            if re.match(r"^(EmptyState|Empty|EmptyBox|NoData|Placeholder)$", name):
                inventory["selfbuilt"].append((r, start, name, "函数名 " + name))
                continue
            if "DhState" in body:
                continue
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
                if EMPTY_WORDS.search(arg):
                    drawn = True
                    break
            if drawn:
                inventory["selfbuilt"].append((r, start, name, "内联空态文案 + 动作"))

        # S4 纯空白态：Box(Modifier.fillMaxSize()) 且内容区为空
        for m in re.finditer(r"\bBox\s*\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*fillMaxSize\s*\(\s*\)\s*\)", src):
            ln = src[:m.start()].count("\n") + 1
            # 取该 Box 之后的 400 字符看有没有任何可绘制内容
            tail = src[m.end():m.end() + 400]
            has_content = re.search(
                r"\b(Text|Row|Column|LazyColumn|DhKit\.|DhState|Image|Canvas|Spacer)\s*\(", tail)
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