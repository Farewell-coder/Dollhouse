#!/usr/bin/env bash
# 把一张方形母图生成 Dollhouse 全套 Android 图标。
#
# 用法：
#   bash tools/make_icons.sh <母图.png> [--with-stat]
#
# 默认产物：
#   res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        启动图标 5 档
#   res/mipmap-{...}/ic_launcher_round.png                             圆形图标 5 档
#   res/mipmap-{...}/ic_launcher_foreground.png                        自适应前景（内缩到安全区）
#   res/mipmap-anydpi-v26/ic_launcher.xml                              自适应图标声明（含圆形）
#   res/drawable/ic_launcher_background.xml                            自适应背景（母图平均色）
#   assets/icon.png                                                    README 顶部展示图
#
# 加 --with-stat 才额外生成：
#   res/drawable-nodpi/ic_stat_pet.png                                 通知栏剪影（默认不动它）
#
# 【为什么通知栏图标默认不重新生成】状态栏那一格只有约 24dp，能看清的只有简单轮廓。
# 拿一张精致的方形 App 图标去抠成剪影，出来的是一团糊住的色块，还不如原来专门画的剪影。
# 只有在你确认新图标的轮廓足够简单、想让通知栏也换成它时，才加 --with-stat。
#
# 依赖：ffmpeg、python3（无第三方包）
set -u
set -o pipefail

SRC=""
WITH_STAT=0
for a in "$@"; do
    case "$a" in
        --with-stat) WITH_STAT=1 ;;
        *) SRC="$a" ;;
    esac
done

if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
    echo "用法: bash tools/make_icons.sh <母图.png> [--with-stat]" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/app/src/main/res"

# 读母图边长
SIDE=$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$SRC")
case "$SIDE" in
    ''|*[!0-9]*) echo "读不到母图尺寸: $SRC" >&2; exit 3 ;;
esac
echo "母图: $SRC (${SIDE}x${SIDE})"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 先归一化：方图、边长 1024，后续所有缩放都从这一份派生，避免多次有损放大。
# 【为什么保留 rgba 而不是 rgb24】母图可能自带透明度（透明底的主体图很常见）。
# 早先这里写死 rgb24，alpha 会被直接丢掉，后面「沿用母图透明度」的分支就永远进不去，
# 通知栏小图标会退化成按四角比色抠图 —— 而透明底图的四角 RGB 通常是 0，
# 于是「拿黑当背景色」，主体和背景一起被判成背景，产出空图。
ffmpeg -v error -y -i "$SRC" \
    -vf "scale=1024:1024:flags=lanczos,format=rgba" \
    "$WORK/base.png" || exit 4

# 1) 五档启动图标（48 / 72 / 96 / 144 / 192）
declare -A DENS=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
for d in "${!DENS[@]}"; do
    px="${DENS[$d]}"
    mkdir -p "$RES/mipmap-$d"
    # 直角方形原图 + 圆角遮罩 → 部分启动器会自己裁切，这里保持满图不加边距
    ffmpeg -v error -y -i "$WORK/base.png" \
        -vf "scale=${px}:${px}:flags=lanczos" \
        "$RES/mipmap-$d/ic_launcher.png" || exit 5
    # 圆形版：圆形遮罩外透明
    ffmpeg -v error -y -i "$RES/mipmap-$d/ic_launcher.png" \
        -vf "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lte((X-W/2)*(X-W/2)+(Y-H/2)*(Y-H/2),pow(W/2\,2)),255,0)'" \
        "$RES/mipmap-$d/ic_launcher_round.png" || exit 6
    echo "  mipmap-$d  ${px}px  启动 + 圆形"
done

# 2) 自适应图标：前景是母图内缩到 66% 安全区（108dp 画布，可见区只有中间 72dp，
#    安全区 66dp，即母图内容占画布 66/108 ≈ 61%），四边留透明。
#    背景取母图平均色，系统各版本都能正确渲染，无需额外位图。
ANYDPI="$RES/mipmap-anydpi-v26"
mkdir -p "$ANYDPI" "$RES/drawable"
for d in "${!DENS[@]}"; do
    px="${DENS[$d]}"
    # 前景画布 = 108dp 对应像素，内容占 61%
    canvas=$(( px * 108 / 48 ))
    inner=$(( canvas * 61 / 100 ))
    pad=$(( (canvas - inner) / 2 ))
    ffmpeg -v error -y -i "$WORK/base.png" \
        -vf "scale=${inner}:${inner}:flags=lanczos,pad=${canvas}:${canvas}:${pad}:${pad}:color=black@0,format=rgba" \
        "$RES/mipmap-$d/ic_launcher_foreground.png" || exit 7
    echo "  mipmap-$d  ${canvas}px 自适应前景（内容 ${inner}px）"
done

# 背景色：取**四角**平均色，而不是整图平均色。
# 【为什么不取整图平均色】主体通常占画面 70~80%，混进来会把背景算偏色。
# 实测本图标整图平均得到 #CED8F0（偏蓝），而真实底色是 #F5F7FB ——
# 前景内缩后边缘会露出一圈明显色差，像贴了张歪画。四角采样才能拿到真正的底色。
CORNER=$( {
    ffmpeg -v error -i "$WORK/base.png" -vf 'crop=120:120:0:0,scale=1:1:flags=area'             -f rawvideo -pix_fmt rgb24 -
    ffmpeg -v error -i "$WORK/base.png" -vf 'crop=120:120:934:0,scale=1:1:flags=area'           -f rawvideo -pix_fmt rgb24 -
    ffmpeg -v error -i "$WORK/base.png" -vf 'crop=120:120:0:934,scale=1:1:flags=area'           -f rawvideo -pix_fmt rgb24 -
    ffmpeg -v error -i "$WORK/base.png" -vf 'crop=120:120:934:934,scale=1:1:flags=area'         -f rawvideo -pix_fmt rgb24 -
} 2>/dev/null | od -An -tu1 -v)
R=$(echo "$CORNER" | awk '{s+=$1; n++} END{printf "%d", s/n}')
G=$(echo "$CORNER" | awk '{s+=$2; n++} END{printf "%d", s/n}')
B=$(echo "$CORNER" | awk '{s+=$3; n++} END{printf "%d", s/n}')
R=${R:-0}; G=${G:-0}; B=${B:-0}
if [ -z "${R:-}" ] || [ -z "${G:-}" ] || [ -z "${B:-}" ]; then
    R=107; G=78; B=230   # 兜底：项目主色 #6b4ee6
fi
HEX=$(printf '#%02X%02X%02X' "$R" "$G" "$B")
echo "  自适应背景色 ${HEX}"

cat > "$RES/drawable/ic_launcher_background.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标背景：由 tools/make_icons.sh 从母图平均色生成（${HEX}）。
     用纯色而非位图，各密度下都不会糊，也不必再补 5 张背景图。 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="${HEX}" />
</shape>
EOF

for name in ic_launcher ic_launcher_round; do
    cat > "$ANYDPI/$name.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Android 8.0+ 自适应图标。旧系统回落到 mipmap-*dpi/${name}.png。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
EOF
done
echo "  mipmap-anydpi-v26  自适应图标声明 + drawable/ic_launcher_background.xml"
if [ "$WITH_STAT" -eq 1 ]; then
# 3) 通知栏小图标：必须是**按形状透明**的白剪影（系统只取 alpha 通道着色）。
#    【为什么覆盖 ic_stat_pet.png 而不是新建文件】代码里已有三处
#    setSmallIcon(R.drawable.ic_stat_pet)，就地替换可以做到零代码改动，
#    也避免新增资源后忘记接线导致「图标换了、通知栏还是旧的」。
#
#    【为什么不直接把像素涂白】系统只用 alpha 通道着色，RGB 是白是灰都不影响显示，
#    真正决定「看到什么形状」的是 alpha。所以必须把主体抠出来：alpha=255、背景 alpha=0。
#    这里用「四角平均色 = 背景色」做自动抠图，浅底深图与深底浅图都能出正确剪影。
#    母图若本身带 alpha，则直接沿用它的透明度，不做抠图。
mkdir -p "$RES/drawable-nodpi"

# 【先声明再进分支】脚本启用了 set -u，任何未赋值的变量被引用都会直接退出。
# 透明底分支不会设置 BR/BG/BB，后面打印时就会踩到 unbound variable —— 表现为
# 「日志停在『透明底母图』然后什么都没产出」，很难联想到是变量未初始化。
BR=0; BG=0; BB=0
BG_DESC="（透明底，未参与比色）"

# 【判据用「透明像素占比」而不是像素格式名】format=rgba 之后所有图都叫 rgba，
# 用格式名判断会让每个母图都误入「自带透明度」分支。真正的问题是「这张图有没有透明底」，
# 所以直接统计 alpha<128 的像素比例，超过 8% 才认为它是透明底图形。
ffmpeg -v error -y -i "$WORK/base.png" -vf "scale=384:384:flags=lanczos" "$WORK/stat_src.png" || exit 8
TRANS=$(ffmpeg -v error -i "$WORK/stat_src.png" -f rawvideo -pix_fmt rgba - 2>/dev/null \
        | python3 -c 'import sys;d=sys.stdin.buffer.read();a=d[3::4];print(int(100*sum(1 for x in a if x<128)/max(1,len(a))))')
echo "  母图透明像素占比 ${TRANS}%"

if [ "$TRANS" -ge 8 ]; then
    # 透明底母图：直接沿用它的 alpha，不再抠图。
    # 【为什么单独分一支】透明底图的四角 RGB 通常是 0（黑），
    # 拿它当背景色去比色会把主体也判成背景，结果是一张空图。
    echo "  通知图标：透明底母图，沿用其透明度"
    ffmpeg -v error -y -i "$WORK/stat_src.png" \
        -vf "format=rgba,geq=r='255':g='255':b='255':a='alpha(X,Y)',scale=96:96:flags=lanczos" \
        "$WORK/stat_cut.png" || exit 9
else
    # 不透明母图：用「四角平均色 = 背景色」做自动抠图，浅底深图与深底浅图都能出正确剪影。
    CORNER_RAW=$( {
        ffmpeg -v error -i "$WORK/stat_src.png" -vf 'crop=32:32:0:0,scale=1:1:flags=area'     -f rawvideo -pix_fmt rgb24 -
        ffmpeg -v error -i "$WORK/stat_src.png" -vf 'crop=32:32:352:0,scale=1:1:flags=area'   -f rawvideo -pix_fmt rgb24 -
        ffmpeg -v error -i "$WORK/stat_src.png" -vf 'crop=32:32:0:352,scale=1:1:flags=area'   -f rawvideo -pix_fmt rgb24 -
        ffmpeg -v error -i "$WORK/stat_src.png" -vf 'crop=32:32:352:352,scale=1:1:flags=area' -f rawvideo -pix_fmt rgb24 -
    } 2>/dev/null | od -An -tu1 -v )
    BR=$(echo "$CORNER_RAW" | awk '{s+=$1; n++} END{printf "%d", s/n}')
    BG=$(echo "$CORNER_RAW" | awk '{s+=$2; n++} END{printf "%d", s/n}')
    BB=$(echo "$CORNER_RAW" | awk '{s+=$3; n++} END{printf "%d", s/n}')
    BR=${BR:-0}; BG=${BG:-0}; BB=${BB:-0}
    BG_DESC="rgb(${BR},${BG},${BB})"

    # 色差阈值：|ΔR|+|ΔG|+|ΔB| 超过 48 判为前景
    THR=48
    ffmpeg -v error -y -i "$WORK/stat_src.png" \
        -vf "format=rgba,geq=r='255':g='255':b='255':a='if(gt(abs(r(X,Y)-${BR})+abs(g(X,Y)-${BG})+abs(b(X,Y)-${BB}),${THR}),255,0)',scale=96:96:flags=lanczos" \
        "$WORK/stat_cut.png" || exit 9
    echo "  通知图标：不透明母图，背景色 ${BG_DESC}"
fi

# 自检：前景覆盖率必须落在合理区间，否则说明抠图失败（全白或全透明都会让状态栏显示异常）
COVER=$(ffmpeg -v error -i "$WORK/stat_cut.png" -f rawvideo -pix_fmt rgba - 2>/dev/null \
        | python3 -c 'import sys;d=sys.stdin.buffer.read();a=d[3::4];print(int(100*sum(1 for x in a if x>128)/max(1,len(a))))')
echo "  通知图标：背景 ${BG_DESC}  前景覆盖率 ${COVER}%"

if [ "$COVER" -lt 3 ] || [ "$COVER" -gt 92 ]; then
    echo "    警告：抠图结果异常（前景 ${COVER}%），改用「亮度作 alpha」兜底。" >&2
    echo "    若仍不满意，请给一张主体居中、背景干净的单色图。" >&2
    ffmpeg -v error -y -i "$WORK/stat_src.png" \
        -vf "format=rgba,geq=r='255':g='255':b='255':a='lum(X,Y)',scale=96:96:flags=lanczos" \
        "$WORK/stat_cut.png" || exit 10
fi

cp "$WORK/stat_cut.png" "$RES/drawable-nodpi/ic_stat_pet.png" || exit 11
echo "  drawable-nodpi     ic_stat_pet.png (96px 白剪影，覆盖原通知图标)"
else
    echo "  drawable-nodpi     ic_stat_pet.png 保持原样（如需替换请加 --with-stat）"
fi


# 4) README 展示图
mkdir -p "$ROOT/assets"
ffmpeg -v error -y -i "$WORK/base.png" -vf "scale=512:512:flags=lanczos" "$ROOT/assets/icon.png" || exit 9
echo "  assets/icon.png    512px（README 顶部）"

echo
echo "完成。下一步："
echo "  cd $ROOT && ./gradlew :app:compileDebugKotlin --no-daemon"
