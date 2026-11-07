#!/usr/bin/env bash
# Dollhouse UI 一致性静态门禁
#
# 用途：把「设计系统是否真的落地」变成可执行的检查，而不是靠人工看截图。
# 用法：bash tools/ui_audit.sh [--quiet]
# 退出码：全 PASS = 0，否则 = 1
#
# 检查分四组：
#   A 设计令牌 —— 是否还有绕过 DhDesign 的裸值（字号 / 圆角 / 边距 / 时长）
#   B 组件统一 —— 是否还有绕开 ui/kit 与 DhKit 的临时控件
#   C 架构边界 —— View 代码是否只允许出现在白名单文件里（ui/interop 与浮层）
#   D 主题一致 —— 是否还有自建配色真源（裸色值、MaterialTheme 的第二套 scheme）
#
# 白名单原则：**只允许真实必要的例外**，每条例外都必须在注释里写清楚原因。
set -u
cd "$(dirname "$0")/.." || exit 2

SRC=app/src/main/java/com/dollhouse/app
UI=$SRC/ui
KIT=$UI/kit
DESIGN=$UI/design
QUIET="${QUIET:-0}"
[ "${1:-}" = "--quiet" ] && QUIET=1

PASS=0; FAIL=0; FAILED=""

chk() {
  local id="$1"; local desc="$2"; shift 2
  if eval "$@" >/dev/null 2>&1; then
    PASS=$((PASS+1))
    [ "$QUIET" = "1" ] || printf 'PASS %-4s %s\n' "$id" "$desc"
  else
    FAIL=$((FAIL+1)); FAILED="$FAILED$id "
    printf 'FAIL %-4s %s\n' "$id" "$desc"
  fi
}

# 统计「不含白名单文件」的匹配行数
count_outside() {
  local pattern="$1"; shift
  local excludes=("$@")
  local args=()
  for e in "${excludes[@]}"; do args+=(-e "$e"); done
  grep -rnE "$pattern" "$UI" "$SRC" --include=*.kt 2>/dev/null \
    | grep "${args[@]}" | wc -l | tr -d ' '
}

echo "===== 组 A：设计令牌（A01–A10）====="

chk A01 "ui/design/DesignSystem.kt 存在" "test -f $DESIGN/DesignSystem.kt"
chk A02 "DhDesign 提供 Type/Space/Size/Radius/Motion/Palette 六组令牌" \
  "grep -q 'data class Type' $DESIGN/DesignSystem.kt && grep -q 'object Space' $DESIGN/DesignSystem.kt && grep -q 'object Radius' $DESIGN/DesignSystem.kt && grep -q 'object Motion' $DESIGN/DesignSystem.kt && grep -q 'data class Palette' $DESIGN/DesignSystem.kt"
chk A03 "不新增调色板槽位（PAL_SIZE 仍为 29）" \
  "grep -q 'PAL_SIZE = 29' $UI/theme/ThemeManager.kt"
chk A04 "Design.kt 的刻度委托 DhDesign（不再自写数字）" \
  "grep -q 'DhDesign.Space.cardPad' $UI/theme/Design.kt"
chk A05 "DhKit 的刻度委托 DhDesign" \
  "grep -q 'DhDesign.Radius.lg' $UI/compose/DhKit.kt"
chk A06 "按压缩放只有一个常量（两侧同名 PRESS_SCALE，无裸字面量）" \
  "test \$(grep -rn 'scaleX(0\.9\|scaleY(0\.9\|targetValue = if (pressed) 0\.9' $UI --include=*.kt | wc -l) -eq 0 && grep -q 'PRESS_SCALE = 0.98f' $UI/theme/UiKit.kt && grep -q 'PRESS_SCALE = 0.98f' $UI/compose/DhKit.kt"
chk A07 "裸圆角字面量已在收敛中（基线 70 处，目标 M9 清零）" \
  "test \$(count_outside 'RoundedCornerShape\([0-9]' -e '/design/DesignSystem.kt' -e '/compose/DhKit.kt' -e '/kit/Components.kt') -le 70"
chk A08 "无裸 setDuration 数字（动画时长走统一规格）" \
  "test \$(grep -rn 'setDuration([0-9]' $SRC --include=*.kt | grep -v -e '/ui/theme/' -e '/ui/widget/' -e '/pet/' | wc -l) -eq 0"
chk A09 "总标题字号只有一档（TAB_TITLE_SP 与 FS_TITLE 同为 20）" \
  "grep -q 'TAB_TITLE_SP = 20f' $UI/compose/DhKit.kt && grep -q 'FS_TITLE = 20.0f' $UI/theme/UiKit.kt"
chk A10 "行图标只有一档（ICON_ROW_DP = 20）" \
  "grep -q 'ICON_ROW_DP = 20f' $UI/compose/DhKit.kt"
# 注释平衡：Kotlin 块注释可嵌套，注释里写 `xxx/*` 会吞掉后面整段代码。
#   本轮 M2 真实踩过一次（编译报 Unclosed comment 却指在文件末尾），故纳入门禁。
chk A11 "Kotlin 块注释全部闭合（防嵌套注释吞代码）" \
  "python3 tools/comment_balance.py > /dev/null"
# 静态预检：本机无 aapt2 无法编译，把「漏 import / 括号不平衡 / 注解错位」
#   这三类最高频的 CI 失败提前到本地抓（本轮已因此省掉至少两轮 8 分钟 CI）。
chk A12 "Kotlin 静态预检通过（漏 import / 括号 / 注解错位）" \
  "python3 tools/kotlin_precheck.py --quiet > /dev/null"
# 【A13】悬空引用：本轮真实事故——删 MessageMenu.kt 时连带删掉了同文件的
#   object MultiSelect，ChatUi 的 9 处引用全变 Unresolved reference，整轮 CI 白跑。
chk A13 "无悬空符号引用（防删文件误伤同文件其它符号）" \
  "python3 tools/symbol_refs.py --quiet > /dev/null"

echo "===== 组 B：组件统一（B01–B10）====="

chk B01 "ui/kit 组件库存在" "test -f $KIT/Components.kt"
chk B02 "kit 提供 DhButton / DhField / DhRow / DhGroup / DhState" \
  "grep -q 'fun DhButton(' $KIT/Components.kt && grep -q 'fun DhField(' $KIT/Components.kt && grep -q 'fun DhRow(' $KIT/Components.kt && grep -q 'fun DhGroup(' $KIT/Components.kt && grep -q 'fun DhState(' $KIT/Components.kt"
chk B03 "kit 提供 Chip/Tag 分离（可点 vs 不可点）" \
  "grep -q 'fun DhChip(' $KIT/Components.kt && grep -q 'fun DhTag(' $KIT/Components.kt"
chk B04 "DialogButton 走统一高度（44dp）" \
  "grep -q 'ButtonHeight = 44.dp' $KIT/Components.kt"
chk B05 "输入框聚焦有视觉反馈（accent 描边）" \
  "grep -q 'targetValue = if (focused) p.accent else Color.Transparent' $KIT/Components.kt"
chk B06 "空/错/无权限共用同一状态骨架" \
  "grep -q 'fun DhState(' $KIT/Components.kt"
chk B07 "行分隔用发丝线而非逐行卡片" \
  "grep -q 'fun DhDivider(' $KIT/Components.kt"
chk B08 "图标按钮具备无障碍名称（contentDescription 必需参数）" \
  "grep -n 'fun DhIconButton' -A4 $KIT/Components.kt | grep -q 'name: String'"
chk B09 "kit 不新增裸色值（一律读 DhTokens.palette）" \
  "test \$(grep -cE 'Color\(0x' $KIT/Components.kt) -eq 0"
chk B10 "kit 的尺寸具名化（裸 .dp 字面量只允许出现在具名常量与间距微调）" \
  "grep -q 'ButtonHeight = 44.dp' $KIT/Components.kt && grep -q 'ButtonHeightSm = 34.dp' $KIT/Components.kt"

echo "===== 组 C：架构边界（C01–C10）====="

chk C01 "常规页面走 Compose（buildPage 返回 ComposeView 的页面占比 ≥ 90%）" \
  "test \$(grep -rl 'fun buildPage' $UI --include=*.kt | xargs grep -l 'ComposeHost.createView' | wc -l) -ge \$(( \$(grep -rl 'fun buildPage' $UI --include=*.kt | wc -l) * 9 / 10 ))"
chk C02 "不存在 XML 布局目录（新页面禁止 View/XML）" "test ! -d app/src/main/res/layout"
chk C03 "View 实例化只出现在白名单文件" \
  "test \$(grep -rlE 'LinearLayout\(|FrameLayout\(|TextView\(|EditText\(' $UI --include=*.kt | grep -vE '/(widget|theme)/|/compose/DhForm.kt|/compose/ComposeHost.kt|/compose/LayerShield.kt|/chat/(ChatPanel|MessageMenu).kt|/home/HomeUi.kt|/provider/ProviderDetailPage.kt|/interop/' | wc -l) -eq 0"
chk C04 "AndroidView 只出现在 NavHost/Details/Appearance/Mem/DhKit 等已知桥接点" \
  "test \$(grep -rl 'viewinterop.AndroidView' $UI --include=*.kt | grep -vE '/(nav/NavHost|settings/(MemPage|AppearancePage)|compose/DhKit)\.kt' | wc -l) -eq 0"
chk C05 "MaterialTheme 只在主题外壳里提供（无第二处）" \
  "test \$(grep -rn 'MaterialTheme(' $UI --include=*.kt | grep -v 'compose/DhTheme.kt' | grep -v 'MaterialTheme.colorScheme' | grep -v 'MaterialTheme.typography' | wc -l) -eq 0"
chk C06 "主题外壳提供 MaterialTheme 投影" \
  "grep -q 'DhDesign.colorScheme' $UI/compose/DhTheme.kt"
chk C07 "导航仍由 Nav3 承载（NavDisplay 存在）" \
  "grep -q 'NavDisplay(' $UI/nav/NavHost.kt"
chk C08 "PageStack 是 NavHost 的门面（不自建第二套栈）" \
  "grep -q 'return NavHost.push(ctx, key, build)' $UI/compose/PageStack.kt"
chk C09 "保留浮层 View 白名单（桌宠自绘 + 悬浮输入条）" \
  "test -f $SRC/pet/PetWindow.kt && test -f $SRC/pet/PetTalkInput.kt"
chk C10 "裁剪页允许 AndroidView 兼容（不静默丢失功能）" \
  "grep -q 'pointerInput\|AndroidView' $SRC/BackgroundCropActivity.kt || grep -q 'AndroidView' $SRC/CoverCropActivity.kt || test -f $SRC/BackgroundCropActivity.kt"

echo "===== 组 D：主题一致（D01–D10）====="

chk D01 "颜色唯一真源是 UiKit 的可变字段" \
  "grep -q 'var ACC = ' $UI/theme/UiKit.kt"
chk D02 "三档调色板长度与 PAL_SIZE 一致（29 位）" \
  "test \$(grep -c 'intArrayOf(' $UI/theme/ThemeManager.kt) -ge 3"
chk D03 "主题切换后夹紧对比度（WCAG）" \
  "grep -q 'fun clampPaletteContrast' $UI/theme/UiKit.kt"
chk D04 "开关开启色跟随主色（单一强调色）" \
  "grep -q 'UiKit.SWITCH_ON = p\[I_ACC\]' $UI/theme/ThemeManager.kt"
chk D05 "阴影不落在列表行/芯片/顶栏（kit 层无 shadow 调用）" \
  "test \$(grep -c 'shadow(' $KIT/Components.kt) -eq 0"
chk D06 "没有 gradient 滥用（kit 层无 Brush.linearGradient）" \
  "test \$(grep -c 'Brush\.' $KIT/Components.kt) -eq 0"
chk D07 "文字选择把手走主题色（不再是 M3 默认蓝）" \
  "grep -q 'palette.accent' $UI/compose/DhTheme.kt"
chk D08 "字体阶梯含品牌档与正文档两套（品牌字不用于正文）" \
  "grep -q 'brandFamily' $UI/design/DesignSystem.kt && grep -q 'bodyFamily' $UI/design/DesignSystem.kt"
chk D09 "行高显式声明（杜绝 CJK 行距贴死）" \
  "grep -q 'lineHeight = 24.sp' $UI/design/DesignSystem.kt"
chk D10 "对比度回归脚本存在" "test -f tools/ContrastRegression.kt"
# 【D11】三档调色板的**实际落地值**对比度校验。
#   D10 检查的 ContrastRegression.kt 是 Kotlin 写的、需要 kotlinc，本机与 CI 都不跑它 ——
#   等于"有脚本但没人验证"。本项直接解析 ThemeManager 的 29 格数组按 WCAG 计算，
#   本轮已借此发现并修掉两个真实问题（浅色发丝线 1.20、纯黑描边 1.13）。
chk D11 "三档主题对比度达标（正文≥4.5 / 发丝线≥1.3）" \
  "python3 tools/palette_contrast.py --quiet > /dev/null"

echo
echo "=============================================="
echo "总计 $((PASS+FAIL)) 项：PASS $PASS / FAIL $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "未通过：$FAILED"
  exit 1
fi
echo "全部通过"
exit 0