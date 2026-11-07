#!/usr/bin/env python3
"""
Dollhouse Kotlin 注释/字符串平衡检查

【为什么需要这个脚本】
  Kotlin 的块注释**可以嵌套**：`/* a /* b */` 只关掉内层，外层仍是打开状态。
  于是注释里随手写一句 `ui/widget/*` 就会静默开启一层嵌套注释，
  把后面整段代码（包括对象声明）全部吞进注释里，编译报 "Unclosed comment"
  却指在文件末尾 —— 排查成本很高（本轮 M2 就踩了一次）。

【为什么不用 grep 数 /* 和 */】
  字符串字面量里的 `"image/*"` 会被误算。本脚本按状态机跳过字符串、字符、
  行注释，只对真正的块注释计数，并报告**每个未闭合块的起始行**。

用法：python3 tools/comment_balance.py [路径...]
退出码：全部平衡 = 0，否则 = 1
"""
import sys
import os
from pathlib import Path

DEFAULT_ROOT = "app/src/main/java"


def scan(path: Path):
    """返回 (未闭合块起始行列表, 负深度行号列表)。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    i = 0
    n = len(text)
    line = 1
    # 状态栈：True 表示块注释层
    depth = 0
    opened = []
    negative = []
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            i += 1
            continue
        # 【顺序很重要】必须先判「是否已在块注释内」，再判行注释。
        #   否则块注释里的 `dollhouse://pet/on` 会被当成行注释起点，
        #   扫描器跳到行尾就看不见同一行末尾的 `*/`，于是误报"未闭合"。
        if depth > 0:
            # Kotlin 的块注释**可嵌套**：注释里出现 `/*` 会再开一层。
            #   这正是本轮 M2 的真实事故：一句 `ui/widget/*` 把后面整段代码吞进注释。
            #   所以这里必须同时处理嵌套开始与结束；行注释标记在块注释内只是普通文本。
            if c == "/" and nxt == "*":
                depth += 1
                opened.append(line)
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth -= 1
                if opened:
                    opened.pop()
                i += 2
                continue
            i += 1
            continue
        # 行注释：跳到行尾
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            if j < 0:
                break
            i = j
            continue
        # 块注释开始
        if c == "/" and nxt == "*":
            depth += 1
            opened.append(line)
            i += 2
            continue
        # 三引号原始字符串
        if c == '"' and text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0:
                break
            line += text.count("\n", i, j)
            i = j + 3
            continue
        # 普通字符串
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                if text[j] == "\n":
                    break
                j += 1
            i = j + 1
            continue
        # 字符字面量
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        if c == "*" and nxt == "/":
            negative.append(line)
            i += 2
            continue
        i += 1
    return opened, negative


def main(argv):
    roots = argv[1:] or [DEFAULT_ROOT]
    files = []
    for r in roots:
        p = Path(r)
        if p.is_file():
            files.append(p)
        elif p.is_dir():
            files.extend(sorted(p.rglob("*.kt")))
    bad = 0
    for f in files:
        opened, negative = scan(f)
        if opened or negative:
            bad += 1
            print(f"FAIL {f}")
            for ln in opened:
                print(f"       未闭合的块注释起始于第 {ln} 行")
            for ln in negative:
                print(f"       多余的 */ 出现在第 {ln} 行")
    total = len(files)
    if bad:
        print(f"\n检查 {total} 个文件：{bad} 个有问题")
        return 1
    print(f"检查 {total} 个文件：全部平衡")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))