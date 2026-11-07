#!/usr/bin/env python3
"""
Dollhouse 输入框交互态审计（静态检查）

【为什么需要这个脚本】
  上一轮的真实教训：「聚焦态只存在于没人调用的 DhField 里」——
  代码里写了 `collectIsFocusedAsState`，但没有任何页面用它，用户看到的仍然是
  「点了输入框什么都不变」。`kotlin_precheck.py` 查的是「会不会编译失败」，
  `ui_audit.sh` 查的是「架构与色彩口径」，两者都看不见这类**静默失效**。

  本脚本只查一件事：**输入框的聚焦态是否真的接上了线**。

【最关键的判据：interactionSource 必须传给 BasicTextField】
  Compose 的 BasicTextField 在**不传** interactionSource 时，会自己 new 一个内部的，
  于是外层 `remember { MutableInteractionSource() }` 那个对象永远收不到焦点事件，
  `collectIsFocusedAsState()` 恒为 false —— 代码能编译、能运行、聚焦色永远不变。
  这是本轮最难靠肉眼发现的一种失效，所以单列一条硬判据。

【判据一览】
  I1 三个规范入口（DhField / DhForm.Input / DhForm.InputMultiline）都存在
  I2 每个入口都有 collectIsFocusedAsState
  I3 每个入口都把 interactionSource = <同一个本地变量> 传给 BasicTextField
  I4 每个入口都用 animateColorAsState + tween(DhDesign.Motion.micro)
  I5 圆角走 DhDesign.Radius、高度走 DhDesign.Size，不写裸值
  I6 全仓没有绕过入口自建 BasicTextField 的页面

【用法】
  python3 tools/input_audit.py            # 人类可读
  python3 tools/input_audit.py --quiet    # 只打印结论（给 CI / 门禁用）
退出码 0 = 全部通过；1 = 有未通过项。
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
QUIET = "--quiet" in sys.argv

# 规范入口：文件 → [(展示名, 函数名)]
ENTRIES = {
    "app/src/main/java/com/dollhouse/app/ui/kit/Components.kt": [("DhField", "DhField")],
    "app/src/main/java/com/dollhouse/app/ui/compose/DhForm.kt": [
        ("DhForm.Input(LegacyInput)", "LegacyInput"),
        ("DhForm.InputMultiline", "InputMultiline"),
    ],
}

results = []  # (ok, id, desc, detail)


def add(ok, cid, desc, detail=""):
    results.append((ok, cid, desc, detail))


def read(rel):
    p = Path(rel)
    if not p.exists():
        return None
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(src, keep_lines=True):
    """去掉行注释与块注释，避免「注释里写了就算有」的假绿。

    Kotlin 块注释可嵌套，这里用深度计数而不用非贪婪正则。

    [keep_lines] 为 True 时，注释被替换成等量的换行符 ——
    这样后续按 ``src[:pos].count("\\n")`` 算出的行号与原始文件一致。
    上一版直接用空前缀累计，导致报告的行号比真实行号小（ChatUi.kt 报 1140，实际 1217），
    行号错了就等于把开发者带到错误的位置，所以这里必须保位。
    """
    out = []
    i, n = 0, len(src)
    depth = 0
    while i < n:
        two = src[i:i + 2]
        if depth == 0 and two == "//":
            j = src.find("\n", i)
            if j < 0:
                break
            out.append("\n" if keep_lines else "")
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
            # 块注释内部：保留换行以维持行号
            if src[i] == "\n" and keep_lines:
                out.append("\n")
        else:
            out.append(src[i])
        i += 1
    return "".join(out)


def extract_fun(src, name):
    """取一个函数体：从 `fun <name>(` 起，按花括号配平截到函数末尾。"""
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    start = m.start()
    # 从函数签名开头找到第一个 { ，再配平
    i = src.find("{", m.end())
    if i < 0:
        return None
    depth = 0
    j = i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
        j += 1
    return src[start:]


def local_interaction_sources(body):
    """函数体里声明的 MutableInteractionSource 变量名集合。"""
    return set(re.findall(
        r"\b(?:val|var)\s+(\w+)\s*(?::\s*MutableInteractionSource)?\s*=\s*remember\s*\{\s*MutableInteractionSource\(",  # noqa: E501
        body))


def focused_source_names(body):
    """**真正被 collectIsFocusedAsState 订阅**的那些 source 变量名。

    【为什么必须要这一步】只检查"传了 interactionSource = X"是不够的：
      · Compose 允许函数里存在多个 MutableInteractionSource；
      · 若 BasicTextField 传的是 A，而 collectIsFocusedAsState 订阅的是 B，
        聚焦时 B 永远收不到事件 → 聚焦色仍然不变。
      这是比"完全不传"更难发现的静默失效（完全不传至少还能被 I3 抓到）。
    实测反例：往 DhField 里插一个 decoy source 并把它传给 BasicTextField，
      上一版的 I3 判 PASS —— 正是本轮要消灭的假绿。

    支持三种常见写法：
      val focused by <src>.collectIsFocusedAsState()
      val focused = <src>.collectIsFocusedAsState()
      val focused by remember { <src>.collectIsFocusedAsState() }
    """
    names = set()
    for m in re.finditer(
            r"(?:val|var)\s+\w+\s*(?::\s*Boolean)?\s*(?:by|=\s*)(?:\s*remember\s*\{)?\s*"
            r"(\w+)\s*\.\s*collectIsFocusedAsState\s*\(", body):
        names.add(m.group(1))
    # 兜底：任何 "X.collectIsFocusedAsState(" 里的 X
    for m in re.finditer(r"(\w+)\s*\.\s*collectIsFocusedAsState\s*\(", body):
        names.add(m.group(1))
    return names


def basic_text_field_calls(body):
    """取该函数体内所有 BasicTextField(...) 的完整参数串（配平圆括号）。"""
    calls = []
    for m in re.finditer(r"\bBasicTextField\s*\(", body):
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(body):
            if body[j] == "(":
                depth += 1
            elif body[j] == ")":
                depth -= 1
                if depth == 0:
                    calls.append(body[i:j + 1])
                    break
            j += 1
    return calls


# ---------------------------------------------------------------- I1 / I2 / I3 / I4 / I5

def audit_entry(rel, label, fname):
    raw = read(rel)
    if raw is None:
        add(False, "I1", "入口文件存在：" + label, "缺文件 " + rel)
        return
    src = strip_comments(raw)
    body = extract_fun(src, fname)
    if body is None:
        add(False, "I1", "入口函数存在：" + label, "未找到 fun " + fname)
        return
    add(True, "I1", "入口函数存在：" + label, "")

    # I2 聚焦态
    has_focus = "collectIsFocusedAsState" in body
    add(has_focus, "I2", "有 collectIsFocusedAsState：" + label,
        "" if has_focus else "聚焦态不成立——用户点了框也不会变")

    # I3 interactionSource 真的传给了 BasicTextField（最关键）
    btfs = basic_text_field_calls(body)
    if not btfs:
        add(False, "I3", "BasicTextField 传了 interactionSource：" + label,
            "该函数体内没有 BasicTextField —— 可能已改委托，判据需同步")
    else:
        srcs = local_interaction_sources(body)
        watched = focused_source_names(body)
        ok = True
        detail = ""
        for call in btfs:
            m = re.search(r"interactionSource\s*=\s*(\w+)", call)
            if not m:
                ok = False
                detail = "有 BasicTextField 未传 interactionSource → 聚焦态恒为 false"
                break
            var = m.group(1)
            # (a) 必须是函数内 remember 出来的 source
            if srcs and var not in srcs:
                ok = False
                detail = ("interactionSource = %s 不是本函数内 remember 的那个" % var +
                          "（本地变量：%s）" % (",".join(sorted(srcs)) or "无"))
                break
            # (b) 且必须是被 collectIsFocusedAsState 订阅的那个
            if watched and var not in watched:
                ok = False
                detail = ("interactionSource = %s **不是** collectIsFocusedAsState 订阅的对象" % var +
                          "（被订阅的是：%s）→ 聚焦色永远不会变"
                          % (",".join(sorted(watched)) or "无"))
                break
        add(ok, "I3", "BasicTextField 传了 interactionSource 且即被订阅的那个：" + label, detail)

    # I4 颜色过渡
    has_anim = "animateColorAsState" in body
    has_tween = bool(re.search(r"tween\(\s*DhDesign\.Motion\.micro", body)) or \
        bool(re.search(r"tween\(\s*durationMillis\s*=\s*DhDesign\.Motion\.micro", body))
    add(has_anim, "I4a", "有 animateColorAsState：" + label,
        "" if has_anim else "聚焦切换会瞬间跳变，没有 160ms 过渡")
    add(has_tween, "I4b", "过渡用 DhDesign.Motion.micro：" + label,
        "" if has_tween else "时长没走令牌（设计稿 160ms）")

    # I5 圆角 / 高度走令牌
    bare_shape = re.findall(r"RoundedCornerShape\(\s*\d+(?:\.\d+)?\s*(?:\.dp)?\s*\)", body)
    add(not bare_shape, "I5a", "圆角走 DhDesign.Radius：" + label,
        ("裸圆角 %d 处：%s" % (len(bare_shape), bare_shape[0])) if bare_shape else "")
    bare_h = re.findall(r"\.height\(\s*\d+(?:\.\d+)?\.dp\s*\)", body)
    add(not bare_h, "I5b", "高度走 DhDesign.Size：" + label,
        ("裸高度 %d 处：%s" % (len(bare_h), bare_h[0])) if bare_h else "")


# ---------------------------------------------------------------- I6 绕过入口

ENTRY_CALL = re.compile(r"\b(DhField|DhForm\.Input)\s*\(")


def audit_bypass():
    """全仓 BasicTextField：除了规范入口自身的实现文件，其余都算绕过。"""
    allowed = {
        "app/src/main/java/com/dollhouse/app/ui/kit/Components.kt",   # DhField 本体
        "app/src/main/java/com/dollhouse/app/ui/compose/DhForm.kt",   # LegacyInput / InputMultiline 本体
    }
    bypass = []
    for p in sorted(SRC.rglob("*.kt")):
        rel = str(p)
        if rel in allowed:
            continue
        raw = p.read_text(encoding="utf-8", errors="replace")
        src = strip_comments(raw)
        hits = basic_text_field_calls(src)
        if not hits:
            continue
        # 位置：行号在**保留换行的去注释文本**上算，与真实文件一致
        lines = []
        for m in re.finditer(r"\bBasicTextField\s*\(", src):
            lines.append(src[:m.start()].count("\n") + 1)
        bypass.append((rel.replace("app/src/main/java/com/dollhouse/app/", ""), lines, hits))

    if not bypass:
        add(True, "I6", "无页面绕过入口自建 BasicTextField", "")
        return

    # 逐处判断：绕过 ≠ 一定错，但没有聚焦态就是错的
    total = 0
    bad = []
    for rel, lines, hits in bypass:
        for idx, call in enumerate(hits):
            total += 1
            ln = lines[idx] if idx < len(lines) else 0
            has_src = bool(re.search(r"interactionSource\s*=", call))
            if not has_src:
                bad.append((rel, ln))
    add(not bad, "I6",
        "无页面绕过入口自建 BasicTextField 且缺聚焦态",
        ("%d 处绕过入口；其中 %d 处未传 interactionSource：%s"
         % (total, len(bad), ", ".join("%s:%d" % (r, l) for r, l in bad))) if bad
        else ("%d 处绕过入口但都传了 interactionSource（可接受）" % total))


def main():
    for rel, items in ENTRIES.items():
        for label, fname in items:
            audit_entry(rel, label, fname)
    audit_bypass()

    passed = sum(1 for r in results if r[0])
    total = len(results)
    if not QUIET:
        print("===== 输入框交互态审计 =====")
        for ok, cid, desc, detail in results:
            if ok:
                print("PASS %-5s %s" % (cid, desc))
            else:
                print("FAIL %-5s %s" % (cid, desc))
                if detail:
                    print("            -> " + detail)
        print()
    print("输入框审计：PASS %d / FAIL %d（共 %d 项）" % (passed, total - passed, total))
    if passed != total:
        if QUIET:
            for ok, cid, desc, detail in results:
                if not ok:
                    print("FAIL %-5s %s | %s" % (cid, desc, detail))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())