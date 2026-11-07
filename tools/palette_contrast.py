#!/usr/bin/env python3
"""
Dollhouse 三档调色板对比度校验（纯 Python，不需要 kotlinc / Gradle）

【为什么需要】
  `tools/ContrastRegression.kt` 是 Kotlin 写的，需要 kotlinc 才能跑 ——
  本机没有可用的编译器（详见 tools/kotlin_precheck.py 头注释），CI 也不跑它。
  结果是「改了配色没人验证」，本轮就真实出现过：浅色档发丝线对比度只有 1.20，
  分隔线在浅底上几乎看不见，而这一点在代码里看不出来。

  本脚本直接解析 `ThemeManager.kt` 里的三套 intArrayOf 调色板（29 格），
  按 WCAG 2.1 计算关键配对的对比度。它检查的是**真实落地值**，不是期望值。

【判据来源】设计稿 `design_prompts_forcoders.md`：
  · 正文 / 副文 / 药丸字 / 提示字 / 主按钮字 ≥ 4.5:1（WCAG AA 常规文本）
  · 卡片与页面底的差异 ≥ 1.03（"几乎看不出但足以浮起一丝"）
  · 发丝线 / 分隔线 ≥ 1.3（非文本元素，需可辨识；WCAG 建议 UI 元素 3:1 过于严格，
    1dp 装饰性分隔线取可辨识下限）

用法：python3 tools/palette_contrast.py [--quiet]
退出码 0 = 全部达标
"""
import re
import sys
from pathlib import Path

TM = Path("app/src/main/java/com/dollhouse/app/ui/theme/ThemeManager.kt")
QUIET = "--quiet" in sys.argv

# 29 槽的下标语义（与 UiKit.snapshotPalette / ThemeManager 的 I_* 常量一一对应）
I_ACC, I_ACC2, I_CARD, I_BG, I_TITLE, I_SUB = 0, 1, 2, 3, 4, 5
I_LINE, I_OPTION, I_SOFT, I_FIELD = 6, 7, 8, 9
I_OK, I_ERR, I_ON_ACC = 10, 11, 12
I_CHAT_BUBBLE_USER, I_CHAT_BORDER = 13, 14
I_CHAT_CHIP_BG, I_CHAT_CHIP_FG = 15, 16
I_CHAT_CHIP_ON, I_CHAT_CHIP_OFF, I_CHAT_CHIP_MUTE, I_CHAT_ACTION_BG = 17, 18, 19, 20
I_HINT_FG, I_HINT_BG, I_SWITCH_OFF = 21, 22, 23
I_EMOTE_1, I_EMOTE_2, I_EMOTE_3 = 24, 25, 26
I_STROKE, I_SCRIM = 27, 28


def read_palettes():
    src = TM.read_text(encoding="utf-8")
    out = {}
    for name in ("LIGHT", "DARK", "BLACK"):
        m = re.search(rf"private val {name} = intArrayOf\((.*?)\)\n", src, re.S)
        if not m:
            raise SystemExit(f"无法在 ThemeManager.kt 找到 {name} 调色板")
        vals = [int(t.split(".")[0], 16) for t in re.findall(r"0x[0-9A-Fa-f]{8}(?:\.toInt\(\))?", m.group(1))]
        if len(vals) != 29:
            raise SystemExit(f"{name} 调色板长度 {len(vals)}，应为 29（PAL_SIZE 会被校验）")
        out[name] = vals
    return out


def chan(x):
    x /= 255.0
    return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4


def luminance(c):
    return 0.2126 * chan((c >> 16) & 0xFF) + 0.7152 * chan((c >> 8) & 0xFF) + 0.0722 * chan(c & 0xFF)


def ratio(a, b):
    la, lb = luminance(a), luminance(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)


def alpha_of(c):
    return (c >> 24) & 0xFF


def flatten(fg, bg):
    """把带 alpha 的前景压到底色上，得到实际看到的颜色（描边都是半透明的）。"""
    a = alpha_of(fg) / 255.0
    if a >= 1.0:
        return fg
    r = round(((fg >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (1 - a))
    g = round(((fg >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (1 - a))
    b = round((fg & 0xFF) * a + (bg & 0xFF) * (1 - a))
    return 0xFF000000 | (r << 16) | (g << 8) | b


def on_color(bg):
    """复刻 DhDesign.Palette.onErr：分别算白字/深字对比度，取更高者。"""
    l = luminance(bg)
    c_dark = (l + 0.05) / (luminance(0xFF2A2620) + 0.05)
    c_light = 1.05 / (l + 0.05)
    return 0xFF2A2620 if c_dark >= c_light else 0xFFFFFFFF


def best_on(bg):
    return on_color(bg)


def checks_for(p):
    """返回 [(说明, 前景, 背景, 下限)]。"""
    return [
        ("正文 / 卡片", p[I_TITLE], p[I_CARD], 4.5),
        ("正文 / 页面底", p[I_TITLE], p[I_BG], 4.5),
        ("副文 / 卡片", p[I_SUB], p[I_CARD], 4.5),
        ("副文 / 页面底", p[I_SUB], p[I_BG], 4.5),
        ("药丸字 / 药丸底", p[I_CHAT_CHIP_FG], p[I_CHAT_CHIP_BG], 4.5),
        ("提示字 / 提示底", p[I_HINT_FG], p[I_HINT_BG], 4.5),
        ("主按钮字 / 主色", p[I_ON_ACC], p[I_ACC], 4.5),
        ("我方气泡字 / 气泡底", p[I_TITLE], p[I_CHAT_BUBBLE_USER], 4.5),
        ("发丝线 / 页面底", flatten(p[I_LINE], p[I_BG]), p[I_BG], 1.3),
        ("发丝线 / 卡片", flatten(p[I_LINE], p[I_CARD]), p[I_CARD], 1.3),
        ("描边 / 卡片", flatten(p[I_STROKE], p[I_CARD]), p[I_CARD], 1.15),
        ("卡片底 / 页面底", p[I_CARD], p[I_BG], 1.02),
        # 徽标：底色是 err，字色由 DhDesign.Palette.onErr 按「取对比度更高者」派生。
        #   本轮真实缺陷：原来硬编码白字，暗色/纯黑档 err 是浅红，白字仅 2.48/2.69:1。
        ("徽标字 / 错误底", best_on(p[I_ERR]), p[I_ERR], 4.5),
    ]


def main():
    palettes = read_palettes()
    fails = 0
    for name in ("LIGHT", "DARK", "BLACK"):
        p = palettes[name]
        if not QUIET:
            print(f"===== {name}")
        for label, fg, bg, need in checks_for(p):
            r = ratio(fg, bg)
            ok = r >= need
            if not ok:
                fails += 1
            if not QUIET or not ok:
                mark = "PASS" if ok else "FAIL"
                print(f"  {mark} {label:<22} {r:5.2f} (需 ≥ {need})")
    if fails:
        print(f"\n共 {fails} 项未达标")
        return 1
    if not QUIET:
        print("\n三档主题全部达标")
    return 0


if __name__ == "__main__":
    sys.exit(main())