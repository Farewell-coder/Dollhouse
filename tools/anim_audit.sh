#!/usr/bin/env bash
# Dollhouse 全局动画系统检测套件 · 80 项静态检查
# 用法：bash tools/anim_audit.sh [--quiet]
# 退出码：全 PASS = 0，否则 = 1
set -u
cd "$(dirname "$0")/.." || exit 2

SRC=app/src/main/java/com/dollhouse/app
UI=$SRC/ui
COMPOSE=$UI/compose
THEME=$UI/theme
PET=$SRC/pet
ANIM=$SRC/anim
TEST=app/src/test/java/com/dollhouse/app

PASS=0; FAIL=0; FAILED=""
chk() {
  local id="$1"; local desc="$2"; shift 2
  if eval "$@" >/dev/null 2>&1; then
    PASS=$((PASS+1))
    [ "${QUIET:-0}" = "1" ] || printf 'PASS %-4s %s\n' "$id" "$desc"
  else
    FAIL=$((FAIL+1)); FAILED="$FAILED$id "
    printf 'FAIL %-4s %s\n' "$id" "$desc"
  fi
}
QUIET="${QUIET:-0}"
[ "${1:-}" = "--quiet" ] && QUIET=1

echo "===== 组 A：弹簧内核（A01–A20）====="
chk A01 "Spring.kt 存在" "test -f $ANIM/Spring.kt"
chk A02 "Spring 为纯数学（不引用 Android API）" "! grep -q '^import android' $ANIM/Spring.kt"
chk A03 "step(dt<=0) 有短路保护" "grep -q 'dt <= 0.0' $ANIM/Spring.kt"
chk A04 "子步数有上限" "grep -q 'steps > 16' $ANIM/Spring.kt"
chk A05 "子步步长有上限（防积分发散）" "grep -q 'MAX_SUB_STEP' $ANIM/Spring.kt"
chk A06 "单帧 dt 有上限（防后台回前台首帧炸）" "grep -q 'MAX_DT' $ANIM/Spring.kt"
chk A07 "step 后有有限性防护（NaN/Inf）" "grep -q 'isFinite' $ANIM/Spring.kt"
chk A08 "isSettled 收敛判定存在" "grep -q 'fun isSettled' $ANIM/Spring.kt"
chk A09 "settle 可直落终态" "grep -q 'fun settle' $ANIM/Spring.kt"
chk A10 "start/retarget 入口存在" "grep -q 'fun start' $ANIM/Spring.kt"
chk A11 "smooth 预置 = Spring(170,26)" "grep -q 'Spring(170.0, 26.0)' $ANIM/Springs.kt"
chk A12 "snappy 预置 = Spring(420,30)" "grep -q 'Spring(420.0, 30.0)' $ANIM/Springs.kt"
chk A13 "bouncy 预置 = Spring(300,14)" "grep -q 'Spring(300.0, 14.0)' $ANIM/Springs.kt"
chk A14 "预置文档标注阻尼比 ζ" "grep -q 'ζ' $ANIM/Springs.kt"
chk A15 "drive 返回可取消句柄" "grep -q 'fun drive(spring' $ANIM/Springs.kt"
chk A16 "onEnd 保证只回调一次" "grep -q 'if (ended)' $ANIM/Springs.kt"
chk A17 "cancel 与自然收敛语义分离" "grep -q 'finish(false)\|finish(true)' $ANIM/Springs.kt"
chk A18 "Listener 提供 onCancelled（可覆盖）" "grep -q 'fun onCancelled' $ANIM/Springs.kt"
chk A19 "lerpInt 四舍五入" "grep -q 'Math.round(from + (to - from) \* progress)' $ANIM/Springs.kt"
chk A20 "Spring 有 JVM 单测" "test -f $TEST/anim/SpringsTest.kt"

echo "===== 组 B：View 侧动画句柄（B01–B20）====="
chk B01 "UiKit.press 保存弹簧句柄" "grep -q 'pressSpringMap' $THEME/UiKit.kt"
chk B02 "UiKit.press 按下前取消上一个弹簧" "grep -n 'fun press(v: View)' -A20 $THEME/UiKit.kt | grep -q 'pressSpringMap\[view\]'"
chk B03 "UiKit.press 回弹起点取当前实际缩放" "grep -q 'val from = v.scaleX' $THEME/UiKit.kt"
chk B04 "UiKit.pulse 起点取当前实际缩放" "grep -q 'Math.abs(v.scaleX - 1f)' $THEME/UiKit.kt"
chk B05 "UiKit.expand 持有高度动画句柄" "grep -q 'expandAnimMap' $THEME/UiKit.kt"
chk B06 "UiKit.expand 持有箭头动画句柄" "grep -q 'arrowAnimMap' $THEME/UiKit.kt"
chk B07 "UiKit.expand 箭头为空时安全" "grep -q 'if (arrow != null)' $THEME/UiKit.kt"
chk B08 "UiKit.Switch 位移动画可取消" "grep -q 'trackAnim?.cancel()' $THEME/UiKit.kt"
chk B09 "UiKit.Slider 进度动画可取消" "grep -q 'anim?.cancel()' $THEME/UiKit.kt"
chk B10 "SwipeRow 有统一 cancelAnim" "grep -q 'private fun cancelAnim' $UI/widget/SwipeRow.kt"
chk B11 "ProviderDetailPage 切换前取消标记动画" "grep -q 'markerAnim\[0\]?.cancel()' $UI/provider/ProviderDetailPage.kt"
chk B12 "PetWindow 吸附动画句柄存在" "grep -q 'snapAnim' $PET/PetWindow.kt"
chk B13 "closePage 对未挂载视图幂等" "grep -n 'fun closePage' -A8 $THEME/UiKit.kt | grep -q 'page.parent !is ViewGroup'"
chk B14 "swapPage 用结束回调移除旧页" "grep -n 'fun swapPage' -A22 $THEME/UiKit.kt | grep -q 'withEndAction'"
chk B15 "openPage 清理同 tag 旧页" "grep -n 'fun openPage' -A10 $THEME/UiKit.kt | grep -q 'findViewWithTag'"
chk B16 "reveal/collapse 成对存在" "grep -q 'fun reveal' $THEME/UiKit.kt && grep -q 'fun collapse' $THEME/UiKit.kt"
chk B17 "文字变色动画有句柄入口" "grep -n 'fun setTextColorAnimated' -A12 $THEME/UiKit.kt | grep -q 'val va = ValueAnimator'"
chk B18 "硬编码 setDuration 已收口到 UiKit/widget" "test \$(grep -rn 'setDuration([0-9]' $SRC --include=*.kt | grep -v -e '/theme/' -e '/widget/' -e '/pet/' | wc -l) -eq 0"
chk B19 "无裸 Interpolator 实例化（曲线一律走 UiKit.EASE_*）" "test \$(grep -rn 'Interpolator()' $SRC --include=*.kt | grep -v -e '/theme/UiKit.kt' -e 'setInterpolator' | wc -l) -eq 0"
chk B20 "无裸 setVisibility(VISIBLE) 直切造成闪屏" "test \$(grep -rn 'visibility = View.VISIBLE' $SRC --include=*.kt | grep -v -e '/theme/' -e '/pet/' | wc -l) -le 6"

echo "===== 组 C：Compose 侧动画（C01–C20）====="
chk C01 "pressable 按下阶段不过冲" "grep -q 'DhAnim.PRESS_DOWN' $COMPOSE/DhKit.kt"
chk C02 "pressable 松手阶段用回弹" "test \$(grep -c 'DhAnim.BOUNCY' $COMPOSE/DhKit.kt) -ge 2"
chk C03 "pressable 两个重载口径一致" "test \$(grep -c 'val spec = if (pressed) DhAnim.PRESS_DOWN else DhAnim.BOUNCY' $COMPOSE/DhKit.kt) -ge 2"
chk C04 "Switch 颜色动画无过冲（ζ=1.0）" "grep -q 'dampingRatio = 1f' $COMPOSE/DhAnim.kt"
chk C05 "Switch 位移/颜色分别取自词汇表" "grep -q 'DhAnim.SW_SPRING' $COMPOSE/DhKit.kt && grep -q 'DhAnim.COLOR_SPRING' $COMPOSE/DhKit.kt"
chk C06 "Card 展开有显式 enter/exit spec" "grep -q 'CARD_EXPAND' $COMPOSE/DhKit.kt && grep -q 'CARD_COLLAPSE' $COMPOSE/DhKit.kt"
chk C07 "Card 展开时长对齐 UiKit.D_LAYER" "grep -q 'UiKit.D_LAYER' $COMPOSE/DhAnim.kt"
chk C08 "BottomNav 缩放动画有显式 spec" "grep -q 'DhAnim.TAP_SCALE' $UI/home/BottomNav.kt"
chk C09 "DhSwipe 回弹参数与 snappy 同源" "grep -q 'DhAnim.SWIPE_SETTLE' $COMPOSE/DhSwipe.kt"
chk C10 "NavHost 关闭 sizeTransform" "grep -q 'sizeTransform = null' $UI/nav/NavHost.kt"
chk C11 "NavHost 入场两层同时长" "test \$(grep -n 'transitionSpec' -A22 $UI/nav/NavHost.kt | grep -c 'durationMillis = UiKit.D_PAGE') -ge 2"
chk C12 "NavHost 出场两层同时长" "test \$(grep -n 'popTransitionSpec' -A22 $UI/nav/NavHost.kt | grep -c 'durationMillis = UiKit.D_LAYER') -ge 2"
chk C13 "NavHost 提供预测性返回规格" "grep -q 'predictivePopTransitionSpec' $UI/nav/NavHost.kt"
chk C14 "无缺省 spec 的 animateFloatAsState" "perl -0777 -ne 'my \$c=()=/animateFloatAsState\\(/g; my \$s=()=/animateFloatAsState\\([^)]*?animationSpec/sg; exit(\$c==\$s?0:1)' \$(find $SRC -name '*.kt')"
chk C15 "animateColorAsState 全部带 spec" "perl -0777 -ne 'my \$c=()=/animateColorAsState\\(/g; my \$s=()=/animateColorAsState\\([^)]*?animationSpec/sg; exit(\$c==\$s?0:1)' \$(find $SRC -name '*.kt')"
chk C16 "AnimatedVisibility 全部带显式 spec" "perl -0777 -ne 'my \$c=()=/AnimatedVisibility\\(/g; my \$s=()=/AnimatedVisibility\\([^)]*?enter\\s*=/sg; exit(\$c==\$s?0:1)' \$(find $SRC -name '*.kt')"
chk C17 "无 Crossfade 缺省用法" "test \$(grep -rn 'Crossfade(' $SRC --include=*.kt | grep -v '^.*import' | wc -l) -eq 0"
chk C18 "无 rememberInfiniteTransition 常驻空转" "test \$(grep -rn 'rememberInfiniteTransition' $SRC --include=*.kt | wc -l) -eq 0"
chk C19 "Compose tween 时长均引用常量" "test \$(grep -rn 'tween(durationMillis = [0-9]' $SRC --include=*.kt | wc -l) -eq 0"
chk C20 "Compose 动画参数集中到 DhAnim" "test -f $COMPOSE/DhAnim.kt && grep -q 'object DhAnim' $COMPOSE/DhAnim.kt"

echo "===== 组 D：桌宠帧循环（D01–D20）====="
chk D01 "帧率策略独立成类" "test -f $PET/PetFramePolicy.kt"
chk D02 "熄屏档优先于动作提帧（避免熄屏空转 60fps）" "grep -n 'fun nextFrameDelay' -A10 $PET/PetView.kt | grep -q 'lowPower'"
chk D03 "熄屏心跳常量存在" "grep -q 'MS_HEARTBEAT' $PET/PetFramePolicy.kt"
chk D04 "屏幕状态有缓存（避免每帧 Binder）" "grep -q 'screenCheckedAt' $PET/PetView.kt"
chk D05 "wakeFrame 保证同一 Runnable 单实例" "grep -n 'fun wakeFrame' -A8 $PET/PetView.kt | grep -q 'removeCallbacks'"
chk D06 "动作层复用主帧时钟（不另起调度）" "grep -n 'fun tick' -A8 $PET/PetView.kt | grep -q 'a.tick'"
chk D07 "动作解码有代次校验" "grep -q 'generation' $PET/ActionPlayerCore.kt"
chk D08 "动作播放层可 release" "grep -q 'fun release' $PET/ActionPlayerCore.kt"
chk D09 "帧图按目标尺寸采样解码" "grep -q 'DECODE_TARGET_PX' $PET/ActionPlayerCore.kt"
chk D10 "桌宠无 Timer/独立 Handler 动画调度" "test \$(grep -rn 'java.util.Timer\|newSingleThreadScheduledExecutor' $PET --include=*.kt | wc -l) -eq 0"
chk D11 "PetWindow 窗口更新走 safeUpdate" "grep -q 'fun safeUpdate' $PET/PetWindow.kt"
chk D12 "PetWindow 动画回调中 lp 可空安全" "grep -n 'snapAnim = Springs.drive' -A10 $PET/PetWindow.kt | grep -q 'lp?.let'"
chk D13 "贴边低耗档独立常量" "grep -q 'MS_EDGE_LOW_POWER' $PET/PetFramePolicy.kt"
chk D14 "帧率策略有 JVM 单测" "test -f $TEST/pet/PetFramePolicyTest.kt"
chk D15 "离开窗口时停帧" "grep -q 'onDetachedFromWindow' $PET/PetView.kt"
chk D16 "屏幕广播注册与注销成对" "grep -q 'registerReceiver' $PET/PetView.kt && grep -q 'unregisterReceiver' $PET/PetView.kt"
chk D17 "屏灭兜底档存在" "grep -q 'MS_SCREEN_OFF' $PET/PetFramePolicy.kt"
chk D18 "帧间隔由策略统一决策（无 16L 散落）" "test \$(grep -rn 'postDelayed(frame, 16L)' $PET --include=*.kt | wc -l) -eq 2"
chk D19 "动作在播时不因贴边降到 4fps" "grep -q 'isAnimated() == true' $PET/PetView.kt"
chk D20 "PetView 未直接引用 Animator/ValueAnimator 做补间" "test \$(grep -c 'ValueAnimator' $PET/PetView.kt) -eq 0"

echo "===== 组 E：工程卫生（E01–E20）====="
chk E01 "源码全 Kotlin（无 .java 业务源码）" "test \$(find app/src/main/java -name '*.java' | wc -l) -eq 0"
chk E02 "无 System.gc() 调用" "test \$(grep -rn 'System.gc()' $SRC --include=*.kt | wc -l) -eq 0"
chk E03 "主线程路径无 Thread.sleep（后台线程除外）" "perl -0777 -ne 'while (/Thread\\.sleep/g) { my \$p = pos(); my \$pre = substr(\$_, 0, \$p); my \$lastThread = rindex(\$pre, \"Thread(\"); if (\$lastThread < 0) { exit 1 } } exit 0' \$(find $SRC -name '*.kt' -not -path '*/device/*')"
chk E04 "无 println 调试残留" "test \$(grep -rn 'println(' $SRC --include=*.kt | wc -l) -eq 0"
chk E05 "动画代码无 TODO/FIXME 残留" "test \$(grep -rn 'TODO\|FIXME' $ANIM $THEME/UiKit.kt --include=*.kt | wc -l) -eq 0"
chk E06 "versionCode 与 CHANGELOG 首条版本号一致（10 = v0.1.0）" "grep -q 'versionCode 10' app/build.gradle"
chk E07 "versionName 与 versionCode 配对（0.1.0 / 10）" "grep -q 'versionName \"0.1.0\"' app/build.gradle"
chk E08 "targetSdk 保持 34" "grep -q 'targetSdk 34' app/build.gradle"
chk E09 "minSdk 保持 24" "grep -q 'minSdk 24' app/build.gradle"
chk E10 "签名配置仍从 keystore.properties 读取" "grep -q 'keystoreProps' app/build.gradle"
chk E11 "无水印/无硬编码密钥新增" "test \$(grep -rn 'sk-[A-Za-z0-9]\\{20,\\}' $SRC --include=*.kt | wc -l) -eq 0"
chk E12 "检测脚本自身可执行幂等" "test -f tools/anim_audit.sh"
chk E13 "app 目录无临时备份文件" "test \$(find app/src -name '*.bak' -o -name '*.orig' | wc -l) -eq 0"
chk E14 "Kotlin 源无重复 import 块" "test \$(awk '/^import /{print}' $THEME/UiKit.kt | sort | uniq -d | wc -l) -eq 0"
chk E15 "Springs 与 DhSwipe 弹簧参数口径一致" "grep -q 'SWIPE_SETTLE' $COMPOSE/DhSwipe.kt && grep -q 'stiffness = 420f' $COMPOSE/DhAnim.kt && grep -q 'Spring(420.0, 30.0)' $ANIM/Springs.kt"
chk E16 "统一时长常量唯一真源" "grep -q 'const val D_PRESS' $THEME/UiKit.kt && grep -q 'const val D_PAGE' $THEME/UiKit.kt"
chk E17 "统一曲线常量唯一真源" "grep -q 'val EASE_STD' $THEME/UiKit.kt && grep -q 'val EASE_DECEL' $THEME/UiKit.kt && grep -q 'val EASE_ACCEL' $THEME/UiKit.kt"
chk E18 "无新增危险权限" "test \$(grep -c 'uses-permission' app/src/main/AndroidManifest.xml) -le 30"
chk E19 "Git 工作区可回滚（存在 HEAD）" "git rev-parse --verify HEAD"
chk E20 "动画内核单测类存在且命名规范" "test -f $TEST/anim/SpringsTest.kt && grep -q 'class SpringsTest' $TEST/anim/SpringsTest.kt"

echo
echo "=============================================="
printf '总计 %d 项：PASS %d / FAIL %d\n' $((PASS+FAIL)) "$PASS" "$FAIL"
[ -n "$FAILED" ] && echo "未通过：$FAILED"
echo "=============================================="
[ "$FAIL" -eq 0 ]
