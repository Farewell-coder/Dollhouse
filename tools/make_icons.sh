#!/usr/bin/env bash
# 把一张「方圆插画 + 纯黑底」的方形母图生成 Dollhouse 全套 Android 图标。
#
# 用法：
#   tools/make_icons.sh <母图路径>
#   tools/make_icons.sh            # 用默认母图 tools/icon_source.png
#
# 产出（全部覆盖）：
#   res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png      ← 传统图标（旧系统）
#   res/mipmap-{...}/ic_launcher_round.png                           ← 圆形图标（旧系统圆形启动器）
#   res/mipmap-{...}/ic_launcher_foreground.png                      ← 自适应图标前景（Android 8+）
#   res/mipmap-{...}/ic_launcher_monochrome.png                      ← 主题图标剪影（Android 13+）
#   res/drawable/ic_launcher_background.xml                          ← 自适应图标背景（兜底纯色）
#
# 为什么这么切（详细理由见 tools/make_icons.py 文件头）：
#   · **黑底用连通填充剥掉**——母图的黑只在四个角的楔形里，不是可裁的外框；
#     裁边留下的黑角在圆形启动器里会露出来。
#   · **黑角用「轮廓边缘色径向延续」补**——不是填一个固定色，否则人偶周围
#     会出现一圈异色环带（曾填成近白 #FAF7F7，比黑角更难看）。
#   · **人偶按外接直径缩进安全区**——前景 68dp（可见区 72dp）、传统图标 44dp（方框 48dp）。
#     自适应画布是 108dp，但系统只显示中心 72dp；铺满 108dp 等于主动裁掉 33% 构图，
#     表现为「比例不对、人偶顶到图标边」。
#
# 依赖：python3 + Pillow。不依赖 ImageMagick，避免额外装包。
#
# 【注意】本脚本刻意不用 `set -e`：它会让 shell 在任一步失败时改变退出行为，
#   在长会话终端里表现为「命令直接吞掉、没有输出」。错误由 python 侧的
#   非零退出码传递，调用方自行判断即可。
cd "$(dirname "$0")/.." || exit 1

SRC="${1:-tools/icon_source.png}"
if [ ! -f "$SRC" ]; then
    echo "母图不存在：$SRC" >&2
    exit 1
fi

python3 tools/make_icons.py "$SRC"
