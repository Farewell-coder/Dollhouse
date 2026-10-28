#!/usr/bin/env python3
"""从 Icons.kt + res/drawable 生成图标映射表 Markdown。

【为什么用脚本生成】图标清单是代码的一部分，手写文档必然漂移。
这个脚本每次都能从真源码重新出表，保证「文档说的」就是「代码里的」。
"""
import os
import re
import subprocess

ROOT = "/storage/emulated/0/work area/xiaods/Dollhouse"
ICONS_KT = f"{ROOT}/app/src/main/java/com/dollhouse/app/ui/theme/Icons.kt"
DRAWABLE = f"{ROOT}/app/src/main/res/drawable"
OUT = f"{ROOT}/docs/ICONS.md"
APP = f"{ROOT}/app/src/main/java/com/dollhouse/app"

# ---- 1. 解析 Icons.kt：常量名 -> 资源名，附带前置注释 ----
with open(ICONS_KT, encoding="utf-8", errors="ignore") as f:
    kt = f.read()

entries = []  # (常量名, 资源名, 说明)
# 【为什么不带前置注释一起匹配】Icons.kt 里大量常量写作
#     @JvmField
#     val IC_X = R.drawable.ic_x
# 若把注释也塞进正则，会把上一条常量的注释吃掉、甚至把整段段落注释误当成本条说明
# （初版就踩过：IC_ARROW_RIGHT 的说明被提取成文件头部的「图标工厂。」）。
# 这里只认「@JvmField + val」这一对，说明另外按行号回溯查找。
for m in re.finditer(
    r"@JvmField\s*\n[ \t]*val (IC_[A-Z0-9_]+) = R\.drawable\.(ic_[a-z0-9_]*)",
    kt,
):
    const, res = m.group(1), m.group(2)
    # 回溯：从声明处往上找紧邻的注释（跳过空行与 @JvmField 注解行）。
    # 【两种写法都要覆盖】Icons.kt 里既有单行 `/** xxx */`，也有多行块
    #     /** 首句
    #         续行 */
    #   只认单行会导致一半常量的说明为空（初版就漏掉了多行块）。
    prev = kt[: m.start()].split("\n")
    desc = ""

    def clean(s: str) -> str:
        s = s.strip()
        s = s[:-2].strip() if s.endswith("*/") else s      # 去掉块注释结尾
        s = re.sub(r"^/\*+", "", s).strip()                # 去掉开头 /**
        return s

    i = len(prev) - 1
    while i >= 0 and prev[i].strip() in ("", "@JvmField"):
        i -= 1
    if i >= 0:
        ln = prev[i].strip()
        if ln.startswith("//"):
            desc = ln.lstrip("/").strip()
        elif ln.endswith("*/"):
            # 单行块注释
            desc = clean(ln)
        elif ln.startswith("*") or ln.startswith("/**"):
            # 多行块的结尾行：一路往上找到 /** 开头，取第一句
            block = []
            j = i
            while j >= 0:
                block.append(prev[j].strip())
                if prev[j].strip().startswith("/**"):
                    break
                j -= 1
            block.reverse()
            for b in block:
                s = clean(b)
                if s and not s.startswith("@"):
                    desc = s
                    break
    entries.append((const, res, desc))

# ---- 2. 统计每个图标被哪些文件引用 ----
usage = {}
for dirpath, _, files in os.walk(APP):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dirpath, fn)
        if p == ICONS_KT:
            continue
        with open(p, encoding="utf-8", errors="ignore") as f:
            src = f.read()
        for const, _, _ in entries:
            n = len(re.findall(r"\bIcons\." + const + r"\b", src))
            if n:
                rel = p.replace(APP + "/", "")
                usage.setdefault(const, []).append((rel, n))

# ---- 3. 校验：常量与资源一一对应 ----
res_files = {
    f[:-4]
    for f in os.listdir(DRAWABLE)
    if f.startswith("ic_") and f.endswith(".xml")
    # launcher_background 是启动器背景，不属于图标集，不参与一致性校验。
    and f != "ic_launcher_background.xml"
}
const_res = {e[1] for e in entries}
orphan_res = sorted(res_files - const_res)
missing_res = sorted(const_res - res_files)

# ---- 4. 出表 ----
def sort_key(e):
    return e[0]

lines = []
lines.append("# 图标映射表（Lucide）")
lines.append("")
lines.append("> 本文件由 `tools/gen_icons_md.py` 从 `Icons.kt` 与 `res/drawable/` 生成，**不要手工编辑**。")
lines.append("> 改了图标只需重跑脚本，文档与代码永远同步。")
lines.append("")
lines.append("## 规范")
lines.append("")
lines.append("| 项 | 约定 |")
lines.append("|---|---|")
lines.append("| 来源 | [Lucide](https://lucide.dev)（ISC 协议），路径数据取自官方 SVG |")
lines.append("| 视口 | `24dp × 24dp`，`viewportWidth/Height = 24` |")
lines.append("| 线宽 | `strokeWidth = 2`，端点 / 拐角均为 `round` |")
lines.append("| 占位色 | `strokeColor = #FFFFFFFF`，**真色由 `Icons.get()/tint()` 在运行时按主题覆盖** |")
lines.append("| 命名 | 常量 `IC_XXX` 与资源 `ic_xxx` 一一对应，转换规则：小写 + 下划线 |")
lines.append("| 新增 | ① 放 `res/drawable/ic_xxx.xml`；② `Icons.kt` 加 `@JvmField val IC_XXX`；③ 重跑本脚本 |")
lines.append("")
lines.append(f"当前共 **{len(entries)}** 个图标常量，**{len(res_files)}** 个 `ic_*.xml` 资源。")
lines.append("")

if orphan_res or missing_res:
    lines.append("## ⚠ 一致性问题")
    lines.append("")
    for r in orphan_res:
        lines.append(f"- 资源 `{r}.xml` 存在但没有对应常量")
    for r in missing_res:
        lines.append(f"- 常量指向 `{r}` 但资源文件不存在")
    lines.append("")

lines.append("## 按用途分组")
lines.append("")
lines.append("同一个图标在不同页面承担相同语义。下面按**业务语义**归类，")
lines.append("新增界面时先在这里找有没有现成的，避免同一个意思用两个不同图标。")
lines.append("")

GROUPS = [
    ("导航与方向", [
        "IC_CHEVRON_LEFT", "IC_CHEVRON_RIGHT", "IC_CHEVRON_DOWN", "IC_CHEVRON_UP",
        "IC_ARROW_LEFT", "IC_ARROW_RIGHT", "IC_STEP_BACK", "IC_STEP_FORWARD",
        "IC_HOUSE", "IC_EXTERNAL",
    ]),
    ("增删改与文件", [
        "IC_PLUS", "IC_MINUS", "IC_CHECK", "IC_CLOSE", "IC_EDIT", "IC_TRASH",
        "IC_SAVE", "IC_COPY", "IC_REFRESH", "IC_DOWNLOAD", "IC_ERASER",
        "IC_PACKAGE", "IC_PACKAGE_OPEN", "IC_PACKAGE_PLUS",
    ]),
    ("人偶与人格", [
        "IC_WHALE", "IC_TILE_PET", "IC_SPARKLES", "IC_WAND_SPARKLES", "IC_HEART_HANDSHAKE",
        "IC_BOOK_HEART", "IC_QUOTE", "IC_SIGNATURE", "IC_USERS", "IC_USER_ROUND",
        "IC_USER_PLUS", "IC_CIRCLE_USER",
    ]),
    ("动作与动画", [
        "IC_PLAY", "IC_PAUSE", "IC_SQUARE", "IC_BLOCKS", "IC_LAYERS", "IC_SHUFFLE",
        "IC_ROTATE_CCW", "IC_ROTATE_CW", "IC_MOVE", "IC_GRAB", "IC_HAND", "IC_CROSSHAIR",
    ]),
    ("外观与主题", [
        "IC_SUN", "IC_PALETTE", "IC_DROPLET", "IC_CONTRAST", "IC_APERTURE",
        "IC_IMAGE", "IC_IMAGE_PLUS", "IC_LAYOUT_GRID", "IC_LAYOUT_TEMPLATE",
        "IC_PANEL_RIGHT", "IC_MAXIMIZE", "IC_MINIMIZE", "IC_ZOOM_IN", "IC_ZOOM_OUT",
    ]),
    ("聊天与社交", [
        "IC_CHAT", "IC_MESSAGE_SQUARE", "IC_MESSAGE_SQUARE_HEART", "IC_MESSAGES_SQUARE",
        "IC_SEND", "IC_BELL", "IC_MEGAPHONE", "IC_NEWSPAPER", "IC_MESSAGE_SQUARE_HEART",
    ]),
    ("AI 与模型", [
        "IC_BRAIN", "IC_BOT", "IC_SERVER", "IC_PLUG", "IC_NETWORK", "IC_CABLE",
        "IC_SPINNER", "IC_GAUGE", "IC_INFINITY",
    ]),
    ("设备与系统", [
        "IC_TERMINAL", "IC_SQUARE_TERMINAL", "IC_APP_WINDOW", "IC_MOUSE_POINTER_2",
        "IC_KEYBOARD", "IC_CLIPBOARD", "IC_SCAN", "IC_SHIELD_CHECK", "IC_KEY_ROUND",
        "IC_BATTERY_CHARGING", "IC_ACTIVITY", "IC_WAYPOINTS",
    ]),
    ("状态与诊断", [
        "IC_INFO", "IC_WARNING", "IC_CIRCLE_ALERT", "IC_CHECK_CIRCLE", "IC_X_CIRCLE",
        "IC_EYE", "IC_EYE_OFF", "IC_CLOCK_3", "IC_TAGS", "IC_PIN", "IC_LINK_2",
    ]),
    ("文件夹与分类", [
        "IC_FOLDER_OPEN", "IC_FOLDER_PLUS", "IC_FOLDER_TREE", "IC_LIBRARY", "IC_BOOK_OPEN",
    ]),
]
by_const = {e[0]: e for e in entries}
for gname, members in GROUPS:
    hit = [m for m in members if m in by_const]
    if not hit:
        continue
    lines.append(f"### {gname}")
    lines.append("")
    lines.append("| 常量 | 资源 |")
    lines.append("|---|---|")
    for m in hit:
        lines.append(f"| `{m}` | `{by_const[m][1]}` |")
    lines.append("")

lines.append("## 清单")
lines.append("")
lines.append("| 常量 | 资源 | 用途说明 | 引用处 |")
lines.append("|---|---|---|---|")
for const, res, desc in sorted(entries, key=sort_key):
    u = usage.get(const, [])
    total = sum(n for _, n in u)
    files = sorted({f for f, _ in u})
    if total == 0:
        use = "—"
    else:
        shown = ", ".join(f"`{os.path.basename(f)}`" for f in files[:3])
        more = f" 等 {len(files)} 个文件" if len(files) > 3 else ""
        use = f"{total} 处（{shown}{more}）"
    lines.append(f"| `{const}` | `{res}` | {desc if desc else '—'} | {use} |")

lines.append("")

# 未被任何页面引用的（只登记备用）
unused = [e for e in entries if e[0] not in usage]
if unused:
    lines.append("## 已登记但当前未被引用")
    lines.append("")
    lines.append("这些常量在 `Icons.kt` 里登记但业务代码还没用到。保留是有意的：")
    lines.append("它们是同一套 Lucide 图标集的组成部分，新增功能时直接取用，不必再找资源。")
    lines.append("")
    for const, res, desc in sorted(unused, key=sort_key):
        lines.append(f"- `{const}`（{res}）")
    lines.append("")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print(f"已生成 {OUT}")
print(f"图标 {len(entries)} 个，资源 {len(res_files)} 个")
print(f"孤儿资源 {len(orphan_res)}，缺失资源 {len(missing_res)}，未引用常量 {len(unused)}")