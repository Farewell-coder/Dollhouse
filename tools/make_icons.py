#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把一张「方圆插画 + 纯黑底」的方形母图生成 Dollhouse 全套 Android 图标。

由 tools/make_icons.sh 调用，也可以单独跑：
    python3 tools/make_icons.py tools/icon_source.png

## 产物

| 文件 | 谁用 | 内容 |
|---|---|---|
| `mipmap-*/ic_launcher.png` | Android 7 及更早 | 满幅方形：人偶 + 径向延续的边缘色 |
| `mipmap-*/ic_launcher_round.png` | 同上，圆形启动器 | 再套圆形遮罩 |
| `mipmap-*/ic_launcher_foreground.png` | Android 8+ 自适应前景 | 满幅 108dp：人偶完整落在安全区内 |
| `mipmap-*/ic_launcher_monochrome.png` | Android 13+ 主题图标 | 从人偶轮廓派生的单色剪影 |
| `drawable/ic_launcher_background.xml` | 自适应背景 | 与边缘色一致的纯色（兜底用） |

## 三个关键判断（都踩过坑）

### ① 黑底不能只裁边，也不能简单填一个纯色

母图是「方圆 / 圆形插画 + 圆外纯黑」：黑只存在于四个角的楔形里，
**不是一条可以裁掉的外边框**。早先的版本按「整行/整列全黑就裁掉」只裁掉了
最外圈细边，四个角的黑楔形还在，于是图标四角是纯黑 —— 在圆形启动器里
看起来像一张贴歪的图。

后来改过一次「把黑角填成母图背景主色」，结果填出来的是 #FAF7F7（近白），
与画面边缘的蓝色（#6C94CC）不是一回事，于是人偶周围多了一圈**白色环带**，
比原来更难看。

现在改成：**沿每个角度，把「轮廓最外那一圈的实际颜色」向外径向延续**。
这样人偶的边色无缝延伸成背景，既没有黑角，也没有色带接缝。

（实现上不是简单填色：按 0.25° 分 1440 个角度桶，每桶记录轮廓边缘色，
画到轮廓外时按像素所在角度取对应桶的颜色。）

### ② 人偶必须完整落在安全区内 —— 这就是「比例不对」的根因

自适应图标画布是 108dp，但系统给启动器遮罩的可见区只有**中心 72dp**
（四边各 18dp 恒被裁）。把内容直接铺满 108dp，等于**故意丢掉 33% 的构图**：
人偶的头顶、下颌、两侧被真实裁掉，看起来像被放大过头、贴着图标边。

现在按**人偶的外接半径**（母图轮廓对角方向最大半径 695px）缩入：
- 前景：外接直径 = 68dp ＜ 遮罩可见直径 72dp → 任何遮罩都不切主体；
- 传统图标：外接直径 = 44dp ＜ 48dp 方框 → 圆形启动器也不切。

【为什么以外接半径为准】母图轮廓是方圆（直边半径 575、对角半径 695）。
按宽高缩放时，四个对角会先撞到圆形遮罩；按外接半径缩放，任意角度都安全。

### ③ 单色剪影必须从人偶轮廓派生，不能用彩色前景

`<monochrome>` 由系统按 alpha 提取再重新着色。给它彩色插画，系统会得到
「哪里都不透明」的一整块，主题图标就是一团糊。
这里用「人偶轮廓 ∩ 暗部」做剪影，才是一张真正的单色图。

## 为什么不用现成工具

`flutter_launcher_icons` / `AndroidAssetStudio` 要联网或额外装包；
这段逻辑留在仓库里可复现、可审计，换母图一条命令即可。
"""
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')

# 各密度的像素倍率（mdpi = 1x），与 Android 官方 mipmap 规格一致。
DENSITIES = [
    ('mdpi', 1.0),
    ('hdpi', 1.5),
    ('xhdpi', 2.0),
    ('xxhdpi', 3.0),
    ('xxxhdpi', 4.0),
]

BASE_DP = 48            # 传统图标基尺寸（48dp）
FOREGROUND_DP = 108     # 自适应图标画布（108dp）
VISIBLE_DP = 72         # 系统遮罩实际可见直径（108dp 画布内）
FG_FIGURE_DP = 68       # 前景里人偶的外接直径（< 72 才不被裁）
LEGACY_FIGURE_DP = 44   # 传统图标里人偶的外接直径（< 48 才不被圆形遮罩裁）

DARK = 40               # 「近黑」判据：三通道都 <= 该值即视为黑底
FILL = (255, 0, 255)    # flood fill 的临时标记色（母图里不存在，安全）
BUCKETS = 1440          # 角度桶数量（0.25°/桶）
MONO_LUMA = 150         # 单色剪影的亮度阈值


def load_square(path):
    """读母图并保证是正方形：非正方形时居中裁切，避免拉伸变形。"""
    im = Image.open(path).convert('RGB')
    w, h = im.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        im = im.crop((left, top, left + side, top + side))
    return im


def build_alpha(im):
    """算出「人偶轮廓」的 alpha 图：与画布边界连通的近黑区域 → 0，其余 → 255。

    【为什么是连通填充而不是阈值抠图】阈值抠图（`亮度 < N 就透明`）会把画内
    本就有的深色线稿、阴影一起挖成洞。而「黑底」的真正定义是
    **与画布边界连通** —— 画内深色被人偶包着，不与边界连通，天然被排除。

    【为什么用 ImageDraw.floodfill】它是 C 实现，1254² 的图一瞬间；
    纯 Python 的 BFS 在同尺寸下要几十秒。
    """
    w, h = im.size
    # 先按阈值把「近黑」压成纯黑，让 flood fill 能一次连通（母图黑底含 1~3 的噪点）
    dark_mask = im.convert('L').point(lambda v: 255 if v <= DARK else 0)

    marker = dark_mask.convert('RGB')
    seeds = ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1),
             (w // 2, 0), (w // 2, h - 1), (0, h // 2), (w - 1, h // 2))
    for seed in seeds:
        if dark_mask.getpixel(seed) == 255:
            ImageDraw.floodfill(marker, seed, FILL, thresh=8)

    alpha = Image.new('L', (w, h), 255)
    ap = alpha.load()
    mp = marker.load()
    for y in range(h):
        for x in range(w):
            if mp[x, y] == FILL:
                ap[x, y] = 0
    return alpha


def edge_colors(im, alpha):
    """按角度桶记录「人偶轮廓最外那一圈的边缘色」。

    【为什么要按角度记录】黑角被挖掉后，外面要填的不是一个固定颜色 ——
    人偶边缘是一圈从深蓝到浅蓝的渐变。填成单一色必然出现一条色带接缝。
    按角度各记各的边缘色，向外延续时接缝就消失了。

    【为什么向内取几像素再平均】轮廓边界有抗锯齿，最外面一两个像素是半透明的
    混色；往内取 6px 平均，得到的是「边界内侧的真实颜色」。
    """
    w, h = im.size
    px = im.load()
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    limit = int(min(w, h) * 0.75)

    colors = [(200, 200, 200)] * BUCKETS
    radii = [0.0] * BUCKETS
    for b in range(BUCKETS):
        a = 2.0 * math.pi * b / BUCKETS
        dx, dy = math.cos(a), math.sin(a)
        # 由外向内找第一个不透明像素 = 轮廓边界
        hit = 0
        for r in range(limit, 0, -1):
            x = int(round(cx + dx * r))
            y = int(round(cy + dy * r))
            if 0 <= x < w and 0 <= y < h and px[x, y][3] > 128:
                hit = r
                break
        radii[b] = hit
        if hit == 0:
            continue
        acc = [0, 0, 0]
        n = 0
        for r in range(hit, max(0, hit - 6), -1):
            x = int(round(cx + dx * r))
            y = int(round(cy + dy * r))
            if 0 <= x < w and 0 <= y < h and px[x, y][3] > 128:
                c = px[x, y]
                acc[0] += c[0]
                acc[1] += c[1]
                acc[2] += c[2]
                n += 1
        if n:
            colors[b] = (acc[0] // n, acc[1] // n, acc[2] // n)
    return colors, radii


def figure_radius(radii):
    """人偶的外接半径（像素）= 所有角度里最远的那一点。"""
    return max(radii)


def render(im, alpha, colors, fig_radius, canvas_px, figure_diameter_px):
    """把母图渲染成 canvas_px 正方形，人偶外接直径 = figure_diameter_px。

    【逐像素映射】目标像素 → 反算回母图坐标：
      · 落在人偶内 → 取母图原色（人偶本体，一比一，不重采样变形）；
      · 落在人偶外 → 取该角度桶的边缘色（径向延续，无接缝）。
    【为什么不用 resize + paste】那样轮廓外会留透明边或固定色块，
    而人偶轮廓是弯的，任何「方形垫底」都会在弯边处露出接缝。
    """
    scale = (figure_diameter_px / 2.0) / fig_radius
    w, h = im.size
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    src = im.load()
    alp = alpha.load()

    out = Image.new('RGB', (canvas_px, canvas_px))
    op = out.load()
    tcx, tcy = (canvas_px - 1) / 2.0, (canvas_px - 1) / 2.0

    for y in range(canvas_px):
        dy = (y - tcy) / scale
        for x in range(canvas_px):
            dx = (x - tcx) / scale
            sx = int(round(cx + dx))
            sy = int(round(cy + dy))
            if 0 <= sx < w and 0 <= sy < h and alp[sx, sy] > 128:
                op[x, y] = src[sx, sy]
            else:
                # 角度桶：把「相对中心的方向」换算成桶号
                ang = math.atan2(dy, dx)
                if ang < 0:
                    ang += 2.0 * math.pi
                b = int(ang / (2.0 * math.pi) * BUCKETS) % BUCKETS
                op[x, y] = colors[b]
    return out


def to_round(im):
    """套圆形遮罩（圆形启动器用）。"""
    size = im.size[0]
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = im.convert('RGBA')
    out.putalpha(mask)
    return out


def to_monochrome(im, alpha, fig_radius, canvas_px, figure_diameter_px):
    """生成 Android 13+ 主题图标的单色剪影。

    取「人偶轮廓 ∩ 暗部」：轮廓保证不会染到画面外，
    暗部保证只留线条与五官，而不是一整块实心图形。
    """
    scale = (figure_diameter_px / 2.0) / fig_radius
    w, h = im.size
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    src = im.load()
    alp = alpha.load()

    out = Image.new('RGBA', (canvas_px, canvas_px), (255, 255, 255, 0))
    op = out.load()
    tcx, tcy = (canvas_px - 1) / 2.0, (canvas_px - 1) / 2.0
    for y in range(canvas_px):
        dy = (y - tcy) / scale
        for x in range(canvas_px):
            dx = (x - tcx) / scale
            sx = int(round(cx + dx))
            sy = int(round(cy + dy))
            if not (0 <= sx < w and 0 <= sy < h) or alp[sx, sy] <= 128:
                continue
            c = src[sx, sy]
            luma = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
            if luma < MONO_LUMA:
                op[x, y] = (255, 255, 255, 255)
    return out


def save(path, im):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, 'PNG', optimize=True)


def main(src):
    raw = load_square(src)
    alpha = build_alpha(raw)
    base = raw.convert('RGBA')
    base.putalpha(alpha)
    colors, radii = edge_colors(base, alpha)
    fig = figure_radius(radii)

    print('母图 %s（%dx%d）' % (os.path.basename(src), raw.size[0], raw.size[1]))
    print('  人偶外接半径 %.0fpx（直边方向 %.0fpx）' % (fig, sorted(radii)[len(radii) // 8]))
    print('  边缘色样本：0°=%s  45°=%s  90°=%s'
          % (colors[0], colors[BUCKETS // 8], colors[BUCKETS // 4]))

    for name, scale in DENSITIES:
        legacy_px = int(round(BASE_DP * scale))
        fg_px = int(round(FOREGROUND_DP * scale))

        # ① 传统图标：满幅方形，人偶外接直径 44dp（< 48dp 方框，圆形遮罩也不裁）
        legacy = render(base, alpha, colors, fig, legacy_px, LEGACY_FIGURE_DP * scale)
        save(os.path.join(RES, 'mipmap-' + name, 'ic_launcher.png'), legacy)

        # ② 圆形图标：同图套圆形遮罩
        save(os.path.join(RES, 'mipmap-' + name, 'ic_launcher_round.png'), to_round(legacy))

        # ③ 自适应前景：满幅 108dp，人偶外接直径 68dp（< 可见区 72dp）
        fg = render(base, alpha, colors, fig, fg_px, FG_FIGURE_DP * scale)
        save(os.path.join(RES, 'mipmap-' + name, 'ic_launcher_foreground.png'), fg)

        # ④ 主题图标剪影：尺寸必须与前景一致
        save(os.path.join(RES, 'mipmap-' + name, 'ic_launcher_monochrome.png'),
             to_monochrome(base, alpha, fig, fg_px, FG_FIGURE_DP * scale))

        print('  %-8s legacy=%dpx  round=%dpx  fg=%dpx  mono=%dpx'
              % (name, legacy_px, legacy_px, fg_px, fg_px))

    # ⑤ 背景纯色：取正上方边缘色，仅作系统未绘制前景时的兜底
    bg = colors[3 * BUCKETS // 4]  # 270° = 正上方
    bg_xml = os.path.join(RES, 'drawable', 'ic_launcher_background.xml')
    with open(bg_xml, 'w', encoding='utf-8') as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 自适应图标背景：由 tools/make_icons.py 从母图轮廓边缘色生成（#%02X%02X%02X）。\n'
            '     【实际不显示】前景图是满幅的（人偶之外由轮廓边缘色径向延续），\n'
            '     这一层只在系统异常未绘制前景时兜底，取同一圈边缘色以免露出异色块。 -->\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <solid android:color="#%02X%02X%02X" />\n'
            '</shape>\n' % (bg[0], bg[1], bg[2], bg[0], bg[1], bg[2])
        )
    print('  背景兜底 #%02X%02X%02X → %s'
          % (bg[0], bg[1], bg[2], os.path.relpath(bg_xml, ROOT)))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('用法：python3 tools/make_icons.py <母图路径>', file=sys.stderr)
        sys.exit(2)
    main(sys.argv[1])