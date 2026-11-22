#!/usr/bin/env python3
"""把 Lucide 官方 SVG 转成工程格式的 VectorDrawable。

【为什么写这个脚本】工程里的 ic_*.xml 都是 24dp 视口 / 线宽 2 / 圆角端点 /
  白色占位描边（真色由 Icons.kt 运行期 tint）。手抄 SVG 路径既慢又容易抄错，
  而「抄错了也没人发现」正是之前图标风格漂移的原因。这里把转换固化成一步，
  任何人改了 ICON_MAP 重跑一次就能复现同样的产物。

【用法】
    python3 tools/gen_lucide.py            # 生成缺失的图标
    python3 tools/gen_lucide.py --check    # 只检查，不写盘（CI 可用）

【来源】https://github.com/lucide-icons/lucide （ISC）
   SVG 只取 <path> / <circle> / <rect> / <line> / <polyline> / <polygon>，
   Lucide 图标本身就是这套基础图形，不涉及渐变 / 裁切 / 遮罩。
"""
from __future__ import annotations

import argparse
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RAW = "https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/{name}.svg"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

# ic_ 文件名 → Lucide 官方名（连字符）
ICON_MAP = {
    "ic_file_input": "file-input",
    "ic_file_output": "file-output",
    "ic_link_2": "link-2",
    "ic_key_round": "key-round",
    # 【为什么没有 triangle-alert / history】这两个在官方 icons/ 目录里已改名 / 下架：
    #   triangle-alert → ic_warning.xml（工程里已有，几何一致）；history 用 clock-arrow-down。
    #   生成脚本只负责**新增**缺项，不覆盖已有资源，避免把现有图标改坏。
    "ic_refresh_cw": "refresh-cw",
    "ic_download": "download",
    "ic_upload": "upload",
    "ic_share_2": "share-2",
    "ic_server": "server",
    "ic_circle_check": "circle-check",
    "ic_wand_sparkles": "wand-sparkles",
    "ic_clock_arrow_down": "clock-arrow-down",
    "ic_list_checks": "list-checks",
    # 【M4·睡眠语义】睡觉模式此前借用 ic_sparkles（"闪烁"），与"休眠/省电"语义不符，
    #   也与随机走位共用一个图标 —— 两个不同功能显示同一个图标。
    #   Lucide 官方有 moon（弯月），最贴近"睡觉"。
    "ic_moon": "moon",
}

HEADER = '''<?xml version='1.0' encoding='utf-8'?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <group android:translateX="0" android:translateY="0.5">
'''
FOOTER = '''    </group>
</vector>
'''
PATH_TMPL = ('        <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" '
             'android:strokeWidth="2" android:strokeLineCap="round" '
             'android:strokeLineJoin="round" android:fillColor="#00000000" />\n')


def fetch(name: str) -> str:
    url = RAW.format(name=name)
    req = urllib.request.Request(url, headers={"User-Agent": "dollhouse-icon-gen"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def num(v: str | None, default: float = 0.0) -> float:
    if v is None:
        return default
    try:
        return float(v)
    except ValueError:
        return default


def fmt(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return s if s else "0"


def svg_to_paths(svg: str) -> list[str]:
    """SVG → 一组 pathData 字符串（只用 Android PathParser 支持的子集）。"""
    root = ET.fromstring(svg)
    out: list[str] = []
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag == "path":
            d = (el.get("d") or "").strip()
            if d:
                out.append(d)
        elif tag == "circle":
            cx, cy, r = num(el.get("cx")), num(el.get("cy")), num(el.get("r"))
            if r > 0:
                # 圆用两段半圆弧表达，和 Lucide 自家 circle 的视觉一致。
                out.append(
                    f"M {fmt(cx - r)} {fmt(cy)} a {fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0 "
                    f"a {fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0"
                )
        elif tag == "rect":
            x, y = num(el.get("x")), num(el.get("y"))
            w, h = num(el.get("width")), num(el.get("height"))
            if w > 0 and h > 0:
                out.append(f"M {fmt(x)} {fmt(y)} h {fmt(w)} v {fmt(h)} h {fmt(-w)} Z")
        elif tag == "line":
            out.append(
                f"M {fmt(num(el.get('x1')))} {fmt(num(el.get('y1')))} "
                f"L {fmt(num(el.get('x2')))} {fmt(num(el.get('y2')))}"
            )
        elif tag in ("polyline", "polygon"):
            pts = [float(v) for v in re.split(r"[,\s]+", (el.get("points") or "").strip()) if v]
            if len(pts) >= 4:
                d = f"M {fmt(pts[0])} {fmt(pts[1])}"
                for i in range(2, len(pts) - 1, 2):
                    d += f" L {fmt(pts[i])} {fmt(pts[i + 1])}"
                if tag == "polygon":
                    d += " Z"
                out.append(d)
    return out


def build(paths: list[str]) -> str:
    body = "".join(PATH_TMPL.format(d=d) for d in paths)
    return HEADER + body + FOOTER


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只检查缺失，不写盘")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    missing, written, failed = [], [], []
    for res_name, lucide_name in ICON_MAP.items():
        target = OUT / f"{res_name}.xml"
        if target.exists():
            continue
        missing.append(res_name)
        if args.check:
            continue
        try:
            paths = svg_to_paths(fetch(lucide_name))
            if not paths:
                failed.append((res_name, "no drawable shapes"))
                continue
            target.write_text(build(paths), encoding="utf-8")
            written.append(res_name)
        except Exception as e:  # noqa: BLE001 网络/格式问题都只记一笔，不让整批失败
            failed.append((res_name, str(e)))

    print(f"缺失 {len(missing)}：{', '.join(missing) or '无'}")
    if written:
        print(f"已生成 {len(written)}：{', '.join(written)}")
    for name, err in failed:
        print(f"失败 {name}: {err}", file=sys.stderr)
    if args.check and missing:
        return 1
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
