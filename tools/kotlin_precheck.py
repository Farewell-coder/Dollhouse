#!/usr/bin/env python3
"""
Dollhouse Kotlin 静态预检（替代本机不可用的 Gradle 编译）

【为什么需要】
  本机是 Android 14 手机上的 proot Ubuntu（arm64）：
    · 没有可用的 aapt2（官方 SDK 只发 x86-64 版，arm64 版在 GitHub 上反复断流取不到）；
    · 于是 `./gradlew compileDebugKotlin` 必然在 `processDebugResources` 阶段失败，
  本地无法真正编译，只能靠 push + CI 反馈。而 CI 一轮 7–8 分钟，纯靠它试错代价过高。

  本脚本用正则 + 括号/引号状态机，把「CI 最常见的那几类编译错误」提前抓出来：
    E1 未闭合的块注释（Kotlin 块注释可嵌套，注释里写 `xxx/*` 会吞掉后面整段代码）
    E2 使用了某个 Compose 扩展 / 函数，但文件里没有对应 import（本轮真实踩过：
       `defaultMinSize` 用了没导入 → Unresolved reference，整轮 CI 白跑）
    E3 花括号 / 圆括号不平衡
    E4 `object` / `class` 声明后紧跟 `@Composable` 之类注解错位
    E5 同一文件里重复声明同名顶层常量 / 函数（本轮也踩过：常量插到了注解与函数之间）

  它只做**廉价但高频**的检查，不替代真正的编译；目的是把 CI 迭代次数从 N 降到 1–2。

用法：python3 tools/kotlin_precheck.py [--quiet]
退出码 0 = 未发现问题
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
QUIET = "--quiet" in sys.argv

# 【E16 分阶段启用·警告模式】
#   E16（多行文本缺行高）是一次**存量清零**型的规则：004 实测 181 处没有 lineHeight，
#   它正在逐批补。若现在就让 E16 决定退出码，CI 会立刻红 95 处，把别人的批次一起堵死 ——
#   而「CI 常年红」比「没有门禁」更糟：绿灯失去信号意义，大家会开始忽略它。
#   所以默认**只警告不阻塞**（不影响 exit code），等存量清完再一键转阻塞：
#       python3 tools/kotlin_precheck.py --e16-block
#   转阻塞的时机：**E16 警告数降到 ≤5 处时**，把 CI（.github/workflows/build.yml）
#   里的调用加上 `--e16-block`。这样 004 收尾时不用改脚本，只改 CI 一行。
#   为什么是 5 而不是 0：剩余的极少数通常是「静态判不准」的案例（短字面量、固定高度容器），
#   为了它们卡死 CI 不划算；留 5 的余量后，新代码再漏就会被立刻拦下。
E16_BLOCK = "--e16-block" in sys.argv

# Compose / Kotlin 常见函数 → 需要的 import（只收录「用了没导入就会编译失败」的那批）
# 依据：本工程实际用到的 API。宁可少列，也不要有误报噪音。
IMPORT_MAP = {
    "defaultMinSize": "androidx.compose.foundation.layout.defaultMinSize",
    "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
    "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth",
    "fillMaxHeight": "androidx.compose.foundation.layout.fillMaxHeight",
    "wrapContentSize": "androidx.compose.foundation.layout.wrapContentSize",
    "wrapContentWidth": "androidx.compose.foundation.layout.wrapContentWidth",
    "wrapContentHeight": "androidx.compose.foundation.layout.wrapContentHeight",
    "aspectRatio": "androidx.compose.foundation.layout.aspectRatio",
    "windowInsetsPadding": "androidx.compose.foundation.layout.windowInsetsPadding",
    "navigationBarsPadding": "androidx.compose.foundation.layout.navigationBarsPadding",
    "statusBarsPadding": "androidx.compose.foundation.layout.statusBarsPadding",
    "imePadding": "androidx.compose.foundation.layout.imePadding",
    "safeDrawingPadding": "androidx.compose.foundation.layout.safeDrawingPadding",
    "systemBarsPadding": "androidx.compose.foundation.layout.systemBarsPadding",
    "displayCutoutPadding": "androidx.compose.foundation.layout.displayCutoutPadding",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
    "LazyRow": "androidx.compose.foundation.lazy.LazyRow",
    "rememberLazyListState": "androidx.compose.foundation.lazy.rememberLazyListState",
    "items": "androidx.compose.foundation.lazy.items",
    "animateItem": "androidx.compose.foundation.lazy.",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "horizontalScroll": "androidx.compose.foundation.horizontalScroll",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "clickable": "androidx.compose.foundation.clickable",
    "background": "androidx.compose.foundation.background",
    "border": "androidx.compose.foundation.border",
    "clip": "androidx.compose.ui.draw.clip",
    "scale": "androidx.compose.ui.draw.scale",
    "alpha": "androidx.compose.ui.draw.alpha",
    "rotate": "androidx.compose.ui.draw.rotate",
    "graphicsLayer": "androidx.compose.ui.graphics.graphicsLayer",
    "RoundedCornerShape": "androidx.compose.foundation.shape.RoundedCornerShape",
    "BasicTextField": "androidx.compose.foundation.text.BasicTextField",
    "KeyboardOptions": "androidx.compose.foundation.text.KeyboardOptions",
    "KeyboardActions": "androidx.compose.foundation.text.KeyboardActions",
    "SolidColor": "androidx.compose.ui.graphics.SolidColor",
    "ColorFilter": "androidx.compose.ui.graphics.ColorFilter",
    "asImageBitmap": "androidx.compose.ui.graphics.asImageBitmap",
    "ContentScale": "androidx.compose.ui.layout.ContentScale",
    "TextOverflow": "androidx.compose.ui.text.style.TextOverflow",
    "TextAlign": "androidx.compose.ui.text.style.TextAlign",
    "FontWeight": "androidx.compose.ui.text.font.FontWeight",
    "PasswordVisualTransformation": "androidx.compose.ui.text.input.PasswordVisualTransformation",
    "VisualTransformation": "androidx.compose.ui.text.input.VisualTransformation",
    "ImeAction": "androidx.compose.ui.text.input.ImeAction",
    "KeyboardType": "androidx.compose.ui.text.input.KeyboardType",
    "animateFloatAsState": "androidx.compose.animation.core.animateFloatAsState",
    "animateColorAsState": "androidx.compose.animation.animateColorAsState",
    "animateDpAsState": "androidx.compose.animation.core.animateDpAsState",
    "tween": "androidx.compose.animation.core.tween",
    "spring": "androidx.compose.animation.core.spring",
    "AnimatedVisibility": "androidx.compose.animation.AnimatedVisibility",
    "AnimatedContent": "androidx.compose.animation.AnimatedContent",
    "fadeIn": "androidx.compose.animation.fadeIn",
    "fadeOut": "androidx.compose.animation.fadeOut",
    "expandVertically": "androidx.compose.animation.expandVertically",
    "shrinkVertically": "androidx.compose.animation.shrinkVertically",
    "slideInVertically": "androidx.compose.animation.slideInVertically",
    "slideOutVertically": "androidx.compose.animation.slideOutVertically",
    "ExperimentalFoundationApi": "androidx.compose.foundation.ExperimentalFoundationApi",
    "ExperimentalMaterial3Api": "androidx.compose.material3.ExperimentalMaterial3Api",
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "remember": "androidx.compose.runtime.remember",
    "rememberSaveable": "androidx.compose.runtime.saveable.rememberSaveable",
    "mutableStateListOf": "androidx.compose.runtime.mutableStateListOf",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "SideEffect": "androidx.compose.runtime.SideEffect",
    "derivedStateOf": "androidx.compose.runtime.derivedStateOf",
    "snapshotFlow": "androidx.compose.runtime.snapshotFlow",
    "CompositionLocalProvider": "androidx.compose.runtime.CompositionLocalProvider",
    "compositionLocalOf": "androidx.compose.runtime.compositionLocalOf",
    "getValue": "androidx.compose.runtime.getValue",
    "setValue": "androidx.compose.runtime.setValue",
    "Alignment": "androidx.compose.ui.Alignment",
    "Modifier": "androidx.compose.ui.Modifier",
    "dp": "androidx.compose.ui.unit.dp",
    "sp": "androidx.compose.ui.unit.sp",
    "semantics": "androidx.compose.ui.semantics.semantics",
    "contentDescription": "androidx.compose.ui.semantics.contentDescription",
    "painterResource": "androidx.compose.ui.res.painterResource",
    "stringResource": "androidx.compose.ui.res.stringResource",
    "LocalContext": "androidx.compose.ui.platform.LocalContext",
    "LocalDensity": "androidx.compose.ui.platform.LocalDensity",
    "AndroidView": "androidx.compose.ui.viewinterop.AndroidView",
    "FontFamily": "androidx.compose.ui.text.font.FontFamily",
    "TextStyle": "androidx.compose.ui.text.TextStyle",
    "withContext": "kotlinx.coroutines.withContext",
    "Dispatchers": "kotlinx.coroutines.Dispatchers",
    "launch": "kotlinx.coroutines.launch",
}

# 这些文件/目录允许例外（本身是基础设施，或刻意全限定引用）
EXEMPT = ("/ui/design/DesignSystem.kt",)


def strip_code(text, state=None):
    """把字符串与注释替换成空格，返回等长文本，用于括号平衡与引用检测。

    【参数 state（可选）】传入一个 dict，函数会把「块注释的最终嵌套深度」与
      「尚未闭合的 `/*` 位置栈」写进去，供 E1 使用：
          state["depth"] = 到文件末尾仍未闭合的层数（正常应为 0）
          state["opens"] = 未闭合的 `/*` 起始下标列表（由外到内）
      **不传时行为与从前完全一致**（现有 12 处调用零影响）——这样 E1 不必复制
      一套字符串/注释状态机（两套必然分叉，E12/E16 的教训）。
    """
    out = list(text)
    i = 0
    n = len(text)
    depth = 0
    _opens = [] if state is not None else None
    while i < n:
        c = text[i]
        # 块注释（可嵌套）
        if depth > 0:
            if text.startswith("/*", i):
                depth += 1
                if _opens is not None:
                    _opens.append(i)
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if text.startswith("*/", i):
                depth -= 1
                if _opens is not None and _opens:
                    _opens.pop()
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c != "\n":
                out[i] = " "
            i += 1
            continue
        if text.startswith("/*", i):
            depth = 1
            if _opens is not None:
                _opens.append(i)
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            if j < 0:
                j = n
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if text[k] != "\n":
                    out[k] = " "
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                if text[k] != "\n":
                    out[k] = " "
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                out[k] = " "
            i = j + 1
            continue
        i += 1
    if state is not None:
        state["depth"] = depth
        state["opens"] = _opens
    return "".join(out)


# ============================================================================
# E12 / E13 共用的两条基础设施
#
# 【为什么抽出来共用】两条规则都要回答同一件事：「这个可点元素**在它自己的
#   修饰符链上**有没有 48dp 的保障」。用「按行回走修饰符链」而不是「字符窗口」，
#   是因为 004 实测出 E12 旧实现用 240 字符窗口会串味：
#   祖先节点写的 `.fillMaxWidth()` 会把兄弟/子节点的 `.clickable` 误判成遮罩。
# ============================================================================

# 固定尺寸调用：`.size(32.dp)` / `.height(40.dp)` / `.requiredSize(30.dp)` …
_SIZE_CALL_RE = re.compile(
    r"\.(size|width|height|requiredSize|requiredWidth|requiredHeight)"
    r"\s*\(\s*([0-9]+(?:\.[0-9]+)?)f?\s*\.dp"
)

# 可点入口。`combinedClickable` 必须一并算进来 —— 它同样**不**带 48dp 下限，
# 而旧 E12 的正则只认小写 `.clickable`，于是 004 用例 #1 完全绕过。
_CLICKABLE_RE = re.compile(r"\.(?:combined)?[Cc]lickable\s*[({]")

# 「自带 48dp 下限」的入口：pressable 内含 minTouchTarget，minTouchTarget 本身就是它。
# 注意 pressScaleClick **刻意不含** 48dp 下限（专给自带轮廓的按钮），故不在其中。
_PRESS_TRIGGER_RE = re.compile(r"\.(?:pressable|minTouchTarget)\s*[({]")

# 铺满父容器的两种写法，都算「点外部关闭」的遮罩。
_FULL_BLEED_TOKENS = (".fillMaxSize", ".matchParentSize")

# 符号型的 48dp 常量：静态期解析不了数值，但名字明确表达「这是命中区」。
_ADEQUATE_SYMBOLS = ("MIN_TOUCH", "minTouch", "HIT_DP")

# 这些在实现体内部出现 clickable 属于基础设施本身，不算违规。
# 【为什么从「整文件豁免 DhKit/DhForm」收窄到这里】旧实现整文件跳过，
#   于是这两个文件里几十个按钮工厂（DhButton/DhChip/OutlineChip/SubmitButton…）
#   将来新增裸 clickable 永远不会被抓。
_PRESS_IMPL_FUNCS = ("pressable", "pressScaleClick", "minTouchTarget", "pressScaleClickWith")


def modifier_chain_lines(code_lines, idx):
    """从第 idx 行（0 基）往上收集**同一条** modifier 链的行号（含 idx 本身）。

    边界判据：只吃「以 `.` / `modifier` / `Modifier` 开头的行」以及空行
    （strip_code 后注释行会变成空行，天然被跳过），遇到别的语句就停。
    """
    got = [idx]
    j = idx - 1
    while j >= 0:
        s = code_lines[j].strip()
        if s == "":
            j -= 1
            continue
        if s.startswith(".") or s.startswith("modifier") or s.startswith("Modifier"):
            got.append(j)
            j -= 1
            continue
        break
    return got


def modifier_chain_lines_forward(code_lines, idx):
    """从第 idx 行（0 基）往下收集同一条 modifier 链的行号（不含 idx 本身）。

    为什么两个方向都要：`.clickable` 只是链上的一环，48dp 的证据可能写在它**后面**
    （`.clickable { }.size(48.dp)` —— 对裸 clickable 来说内层尺寸就是命中区本身）。
    只向回看会把这种正确写法误报。
    """
    got = []
    j = idx + 1
    while j < len(code_lines):
        s = code_lines[j].strip()
        if s == "":
            j += 1
            continue
        if s.startswith(".") or s.startswith(")"):
            got.append(j)
            j += 1
            continue
        break
    return got


def _fun_body_ranges(code, only_names=None):
    """`fun` 的名字与函数体字符区间：[(name, body_start, body_end)]。

    取名字的**末段**（`fun Modifier.pressable(` → `pressable`）—— 004 之前踩过
    第一版按全名匹配、于是 `Modifier.pressable` 对不上 `pressable` 的坑。

    【only_names】只解析关心的函数名。必须过滤：全仓 3576 个函数声明，每个都做一次
    花括号配对会把门禁从 ~4 秒拖到 35 秒；而这里只需要 press 实现体（每文件至多 4 个）。
    """
    only = None if only_names is None else set(only_names)
    out = []
    for m in re.finditer(r"\bfun\s+([A-Za-z_][\w.]*)", code):
        name = m.group(1).split(".")[-1]
        if only is not None and name not in only:
            continue
        # 找参数表之后的第一个 `{`：跳过括号内的 `->` / 默认值 lambda
        i = m.end()
        depth = 0
        brace = -1
        while i < len(code):
            ch = code[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == "{" and depth == 0:
                brace = i
                break
            elif ch == "=" and depth == 0:
                break          # 表达式体函数：没有块体，不参与豁免
            elif ch == "\n" and depth == 0 and brace < 0:
                # 签名跨行是常态；只有连续两个换行才认为签名结束（容错）
                pass
            i += 1
        if brace < 0:
            continue
        d = 0
        j = brace
        while j < len(code):
            if code[j] == "{":
                d += 1
            elif code[j] == "}":
                d -= 1
                if d == 0:
                    break
            j += 1
        out.append((name, brace, j))
    return out


def allow_marker_above(raw_lines, code_lines, idx, prefixes):
    """第 idx 行（0 基）上方是否挂着允许标记。

    【为什么不再用「紧邻上一行」】旧 E12 用 `_i + 2` 严格对齐下一行，中间插一行
    空行或一条说明注释就失效 —— 004 实测过这个脆弱点。这里改成：从上一行起往上
    走，**跳过空行与纯注释行**（strip_code 后它们的 code 形态是空串），
    只要途中遇到带指定前缀的注释就算允许；一旦遇到真正的代码行立即停止，
    避免「标记挂得老远也算数」。
    """
    j = idx - 1
    while j >= 0:
        if code_lines[j].strip() != "":
            return False                 # 撞到代码行，标记不覆盖到这么远
        s = raw_lines[j].strip()
        if any(s.startswith(p) for p in prefixes):
            return True
        j -= 1
    return False



def call_args(code, open_paren_idx):
    """从 `(` 的下标起做括号配对，返回实参串（不含外层括号）；配不上返回 None。"""
    d = 0
    j = open_paren_idx
    while j < len(code):
        ch = code[j]
        if ch == "(":
            d += 1
        elif ch == ")":
            d -= 1
            if d == 0:
                return code[open_paren_idx + 1:j]
        j += 1
    return None


def top_level_named_value(args, key):
    """取**顶层**（深度 0）`key = <value>` 的 value 文本。

    返回 (found, value)：
      · (False, None)        —— 没写这个命名实参
      · (True, "null")       —— 显式传了 null（调用方明确表示"不要图标"）
      · (True, "Icons.IC_X") —— 正常传值

    【为什么必须只看顶层】实参里可能嵌套别的调用，例如
        DhKit.ActionRow(label, trailing = other(iconRes = Icons.IC_X))
    若用全文搜索会把嵌套那层的 iconRes 误当成这一层的 —— 那会让真正缺图标的行逃过检查。

    【为什么用 `=(?!=)`】避开 `iconRes == xxx` 这种比较表达式。
    """
    d = 0
    i = 0
    n = len(args)
    while i < n:
        ch = args[i]
        if ch in "([{":
            d += 1
        elif ch in ")]}":
            d -= 1
        elif d == 0:
            m = re.match(re.escape(key) + r"\s*=(?!=)", args[i:])
            if m:
                start = i + m.end()
                # 取到同层的下一个逗号为止
                d2 = 0
                j = start
                while j < n:
                    cj = args[j]
                    if cj in "([{":
                        d2 += 1
                    elif cj in ")]}":
                        d2 -= 1
                    elif cj == "," and d2 == 0:
                        break
                    j += 1
                return True, args[start:j].strip()
        i += 1
    return False, None


def top_level_arg_count(args):
    """顶层实参个数（粗略：同层逗号数 + 1）。用于判断是否用了位置参数。"""
    d = 0
    commas = 0
    has = False
    for ch in args:
        if ch in "([{":
            d += 1
        elif ch in ")]}":
            d -= 1
        elif ch == "," and d == 0:
            commas += 1
        if d == 0 and not ch.isspace():
            has = True
    return 0 if not has else commas + 1



def block_span(code, open_brace_idx):
    """从 `{` 下标起做花括号配对，返回块内文本（不含外层括号）；配不上返回 None。"""
    d = 0
    j = open_brace_idx
    while j < len(code):
        if code[j] == "{":
            d += 1
        elif code[j] == "}":
            d -= 1
            if d == 0:
                return code[open_brace_idx + 1:j]
        j += 1
    return None


def check_type_sp_consistency(path):
    """E15：`DhDesign.TypeSp` 的 8 个字号常量必须与 `Type.of` 里各档 `fontSize` 逐一相等。

    【为什么这条必须绕过 check_file 的 EXEMPT】`DesignSystem.kt` 在 EXEMPT 里（它是
      设计系统本体，E11/E12 等规则对它没意义），所以 `main()` 根本不会把文件交给
      `check_file`。E15 是**针对这一个文件**的专项校验，只能在 main() 里单独调。

    【为什么用「括号配对解析」而不是正则扫行】作者改字号时很可能顺手重排格式
      （一行拆成多行、加注释、调缩进）。按「`name = TextStyle( … )` 的括号区间」
      取 fontSize，对排版变化免疫；纯正则按行扫一旦格式变了就会静默失效 ——
      而静默失效的防漂移门禁比没有更糟（让人误以为有保护）。

    【解析对象】
      · TypeSp：块内 `const val NAME = 22f`
      · Type.of：`= Type(...)` 的实参里，深度 0 的 `name = TextStyle(...)` 中的 `fontSize = 22.sp`
    两边按**名字**配对（BRAND ↔ brand），不依赖书写顺序。

    【已知边界（宁可漏报不误报）】
      · 若 `fontSize =` 后面不是数字字面量（例如改成引用常量），该档直接跳过，
        不报错 —— 静态期解析不了，硬报会误伤。
      · 不检查 `rowTitle` / `numeric`：它们是 `body.copy(...)` 派生档，字号随 body 走。
    """
    raw = path.read_text(encoding="utf-8", errors="replace")
    code = strip_code(raw)
    probs = []

    m = re.search(r"\bobject\s+TypeSp\s*\{", code)
    if not m:
        return ["E15 找不到 `object TypeSp` —— DesignSystem.kt 结构变了，本规则需同步更新"]
    ts_body = block_span(code, code.find("{", m.end() - 1))
    if ts_body is None:
        return ["E15 TypeSp 的花括号配不上（结构性损坏）"]
    ts = {}
    for mm in re.finditer(r"const\s+val\s+([A-Z][A-Z0-9_]*)\s*=\s*([0-9]+(?:\.[0-9]+)?)f\b", ts_body):
        ts[mm.group(1)] = float(mm.group(2))
    if not ts:
        return ["E15 TypeSp 里一个 `const val X = Nf` 都没解析到 —— 格式变了，请同步规则"]

    mo = re.search(r"fun\s+of\s*\(", code)
    if not mo:
        return ["E15 找不到 `fun of(` —— Type 的构造入口变了，本规则需同步更新"]
    mt = re.search(r"=\s*Type\s*\(", code[mo.start():])
    if not mt:
        return ["E15 `fun of` 里找不到 `= Type(` —— 结构变了，本规则需同步更新"]
    _type_open = mo.start() + mt.end() - 1
    type_args = call_args(code, _type_open)
    if type_args is None:
        return ["E15 `Type(...)` 的括号配不上（结构性损坏）"]

    type_fs = {}
    _d = 0
    _i = 0
    while _i < len(type_args):
        _ch = type_args[_i]
        if _ch in "([{":
            _d += 1
        elif _ch in ")]}":
            _d -= 1
        elif _d == 0:
            _mm = re.match(r"([a-zA-Z_]\w*)\s*=\s*TextStyle\s*\(", type_args[_i:])
            if _mm:
                _name = _mm.group(1)
                _inner = call_args(type_args, _i + _mm.end() - 1)
                _fm = re.search(r"fontSize\s*=\s*([0-9]+(?:\.[0-9]+)?)\.sp", _inner or "")
                if _fm:
                    type_fs[_name] = float(_fm.group(1))
                # 【关键】停到 `(` 上，让下一轮按正常深度逻辑吃掉整个 TextStyle(...)。
                #   早先写成 `_i += _mm.end()`（越过 `(`）会让 depth 少加 1，
                #   于是 TEXTSTYLE 的 `)` 把 depth 减成 -1，后面 7 档全部掉在 depth<0 里被跳过
                #   —— 实测只解析出 brand 一档。
                _i += _mm.end() - 1
                continue
        _i += 1

    for _k in sorted(ts):
        _v = ts[_k]
        _low = _k.lower()
        if _low not in type_fs:
            probs.append(
                f"E15 TypeSp.{_k} 在 Type.of 里没有同名档 `{_low}`："
                f"常量是孤立的（要么删常量，要么 Type 里补这一档）"
            )
        elif abs(type_fs[_low] - _v) > 1e-6:
            probs.append(
                f"E15 字号漂移：TypeSp.{_k} = {_v:g}f，但 Type.of 的 {_low} 是 "
                f"{type_fs[_low]:g}.sp —— 两边必须同步（改字号就要改 TypeSp）"
            )
    for _name in sorted(type_fs):
        if _name.upper() not in ts:
            probs.append(
                f"E15 Type.of 有档位 `{_name}`（{type_fs[_name]:g}sp），但 TypeSp 缺常量："
                f"请补 `const val {_name.upper()} = {type_fs[_name]:g}f`"
            )
    return probs


def paren_map(code):
    """一次 O(n) 预处理：把每个 `(` 的下标映射到配对的 `)` 下标。

    【为什么需要】E17 要枚举**全仓 2.4 万个调用点**。若每个调用都用 `call_args`
      从头扫到配对的 `)`，复杂度是「调用数 × 平均实参长度」，实测慢一倍多（11.1s → 6.2s）。
      一次建表后每个调用 O(1) 取区间。
    【配对不上】`(` 多于 `)` 时该键不存在，调用方必须显式处理（不能静默跳过）——
      否则就会重演「门禁自己悄悄不跑」。
    """
    m = {}
    st = []
    for i, ch in enumerate(code):
        if ch == "(":
            st.append(i)
        elif ch == ")" and st:
            m[st.pop()] = i
    return m


def check_file(path: Path):
    raw = path.read_text(encoding="utf-8", errors="replace")
    _strip_state = {}
    code = strip_code(raw, _strip_state)
    problems = []

    # E1 块注释未闭合（Kotlin 块注释**可嵌套**，注释里写 `/*` 会吞掉后面整段代码）
    #
    # 【为什么这条此前是空的】文件头 docstring 从第一版就写着 E1，但 `strip_code`
    #   内部虽然一直在维护 `depth`，却**从不报告**它 —— 于是长期"文档说有 17 条、
    #   实际只有 16 条在跑"。这是"验证本身没生效"的第七个案例。
    # 【为什么不在 strip_code 里直接报】它要返回字符串、且被 12 处调用，
    #   在里面塞"报错"会污染底座。改成**可选出参**把状态带出来，E1 自己判断。
    # 【为什么必须复用 strip_code 的状态机】字符串里出现 `/*`（`val s = "a /* b"`）
    #   不能被当成注释起点；自己再写一份扫描器必然与它分叉。
    if _strip_state.get("depth", 0) > 0:
        _opens = _strip_state.get("opens") or []
        # 报**最外层**那个未闭合的 `/*`：它才是根因（后面的嵌套都因为它没关）
        _pos = _opens[0] if _opens else 0
        _line = raw.count("\n", 0, _pos) + 1
        problems.append(
            f"E1 第 {_line} 行块注释未闭合（到文件末尾仍差 "
            f"{_strip_state['depth']} 层 `*/`）：Kotlin 块注释可嵌套，"
            f"里面写 `/*` 会把后面整段代码吞进注释 —— 编译期报的往往是别处的"
            f"Unresolved reference，很难反查到这里"
        )

    # E3 括号平衡（只查净值与中途转负 —— **有已知盲区，见下**）
    #
    # 【盲区实录·2026-11】MemPage 抽函数时，把 `} else {` 整行换成调用，
    #   `{` 去掉了、配对的 `}` 留在函数体里。于是「少一个 {、多一个 }」**正好抵消**：
    #   净值仍是 0、中途也没有转负 → 本条全绿。
    #   但结构错了：`object MemPage` 在那里提前关闭，后面的 `readAll` 等掉到了顶层。
    #
    # 【为什么不能用廉价检查补上】上面那个形态**语法是合法的**（顶层函数本来就允许）。
    #   要判断「readAll 本该在 object 内」必须知道它的预期归属 —— 那是语义分析，
    #   不是正则能做的。硬加「深度归零后不许再有代码」会误报所有多顶层声明的文件。
    #
    # 【该怎么办】改动大段代码结构时（抽函数、搬块、调层级），**自己逐行追踪一次深度**，
    #   或者用 IDE 的 Reformat Code（它会暴露缩进与结构不一致）。
    #   这条门禁只负责「漏括号」这种最常见的手误，不负责「括号数对了但嵌套错了」。
    for open_ch, close_ch, name in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
        d = 0
        bad = False
        for ch in code:
            if ch == open_ch:
                d += 1
            elif ch == close_ch:
                d -= 1
                if d < 0:
                    bad = True
                    break
        if bad or d != 0:
            problems.append(f"E3 {name}不平衡（净 {d}）")

    # E18 扩展函数接收者没 import
    #
    # 【起因·2026-11】MemPage 抽出 `fun ColumnScope.MemListPane(...)` 时漏了
    #   `import androidx.compose.foundation.layout.ColumnScope`。CI 除了报
    #   `Unresolved reference 'ColumnScope'`，还连带报 `'weight'` —— 接收者解析不了，
    #   它的成员自然全部找不到，**第二条报错是噪音，会把真正原因埋掉**。
    #
    # 【为什么能廉价抓到】`fun Xxx.Name(` 里的接收者必须是已 import 的类型或同包类型。
    #   这批作用域接收者都是 Compose 的固定几个，逐个查 import 即可，零误报。
    for recv in ("ColumnScope", "RowScope", "BoxScope", "LazyListScope",
                 "LazyGridScope", "LazyStaggeredGridScope", "DrawScope", "AnnotatedString"):
        if re.search(r"fun\s+" + recv + r"\.", code) and ("import " + recv) not in code:
            # 允许同包 / 通配符导入
            if not re.search(r"import\s+\S*\." + recv + r"\s*$", code, re.M) \
                    and not re.search(r"import\s+\S*\*", code):
                problems.append(f"E18 用了 `fun {recv}.` 但没有 import {recv}")

    # E2 未导入的常见 API
    #
    # 【为什么只查这一批 + 强过滤】纯正则做名字解析必然有误报：
    #   `alpha` / `scale` / `items` / `dp` 在业务代码里也常作字段名或局部变量
    #   （实测第一版 24 个文件里过半是这类假红）。命中后必须同时满足：
    #     ① 文件没有该 import，也没有 `包.*` 星号导入；
    #     ② 文件里没有同名的本地声明（fun/val/var/参数）；
    #     ③ 该名字是「Compose 专属」的——即业务数据里不会当变量用的那批。
    #   仍然只是启发式：目的是把 CI 迭代次数压下来，不是替代编译。
    STRONG = {
        "defaultMinSize", "windowInsetsPadding", "navigationBarsPadding",
        "statusBarsPadding", "imePadding", "safeDrawingPadding", "systemBarsPadding",
        "displayCutoutPadding", "aspectRatio", "wrapContentSize", "fillMaxSize",
        "fillMaxWidth", "LazyColumn", "LazyRow", "rememberLazyListState",
        "rememberScrollState", "verticalScroll", "horizontalScroll",
        "BasicTextField", "KeyboardOptions", "KeyboardActions",
        "SolidColor", "ContentScale", "TextOverflow", "TextAlign",
        "PasswordVisualTransformation", "ImeAction", "KeyboardType",
        "animateFloatAsState", "animateColorAsState", "animateDpAsState",
        "AnimatedVisibility", "AnimatedContent", "expandVertically",
        "shrinkVertically", "slideInVertically", "slideOutVertically",
        "rememberSaveable", "mutableStateListOf", "LaunchedEffect",
        "DisposableEffect", "derivedStateOf", "snapshotFlow",
        "CompositionLocalProvider", "compositionLocalOf", "painterResource",
        "AndroidView", "stringResource",
    }
    for fname, imp in IMPORT_MAP.items():
        if fname not in STRONG:
            continue
        if re.search(r"\b(?:val|var|fun)\s+" + re.escape(fname) + r"\b", code):
            continue
        # 只检查「作为调用/属性出现」而不是「我们自己定义的函数名」
        # 【用法形态】Compose 的 Modifier 扩展都是 `.foo(` 形式（前面有点）；
        #   第一版用负向断言排除前导点，正好把这类全部漏掉（自测时抓不到
        #   缺失的 defaultMinSize 导入）。这里改成「前面允许空格/点/括号」。
        use_re = re.compile(r"(?:^|[\s.(]|\b(?:Modifier|modifier)\s*\.)" + re.escape(fname) + r"\s*[({\[]")
        decl = re.compile(r"fun\s+(?:Modifier\.)?" + re.escape(fname) + r"\s*\(")
        if decl.search(code):
            continue
        if not use_re.search(code):
            continue
        if imp.startswith("androidx.compose.foundation.lazy.") and "lazy" in imp:
            if "import androidx.compose.foundation.lazy." in code:
                continue
        if f"import {imp}" in raw:
            continue
        # 【星号导入】`import androidx.compose.runtime.*` 会把这些名字全部引入，
        #   漏判会产出大量假红（本轮 24 个文件里过半是这一类），故按包前缀放行。
        pkg = imp.rsplit(".", 1)[0]
        if f"import {pkg}.*" in raw:
            continue
        # 全限定使用（写了 androidx.compose...xxx）则不算缺失
        if re.search(r"androidx\.[\w.]*\." + re.escape(fname) + r"\b", code):
            continue
        # 与本地同名变量/参数的误判规避：如果出现 `val xxx` / `xxx =` 就不再判
        problems.append(f"E2 用到 {fname} 但没有 import {imp}")

    # E5 同名顶层常量重复
    # E5：判「同一作用域内重复声明」。
    #   第一版按全文计数、第二版按缩进计数都会误报（`val t` / `val p` 在每个函数里
    #   都是合法的局部变量）。真正会编译失败的只有两处：
    #     ① 同一文件里出现两次**同名顶层** `const val`（缩进为 0 或 4 且不在函数体内）；
    #     ② 同一个 object/class 体内重复声明同名 val —— 靠括号深度判断归属。
    #   实现成本远大于收益，只保留 ①：那是复制粘贴事故的典型形态，也是本轮真实踩过的。
    top_consts = re.findall(
        r"^(?:private\s+|internal\s+)?const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)", raw, re.M
    )
    dup = sorted({c for c in top_consts if top_consts.count(c) > 1})
    for c in dup:
        problems.append(f"E5 顶层 const val 重复声明 {c}")

    # E6 类型不匹配：`findActivity(...)` 返回 Activity，却传给要求 MainActivity 的私有函数。
    #
    #   本轮真实事故：PermissionPage 的 `Content(act: MainActivity)` 被
    #   `Content(findActivity(ctx))` 调用 —— findActivity 声明返回 `Activity?`，
    #   而 Kotlin 不会把父类型隐式转成子类型，直接编译失败。
    #   本机无编译器，靠 CI 才发现（8 分钟一轮）。
    #
    #   判据：文件里存在 `fun Xxx(... : MainActivity` 的私有函数，
    #   且同一文件用 `Xxx(` 调用它时，实参来自 `findActivity` 或声明为 `Activity` 的变量。
    #   只做保守检测：宁可漏报，不要误报。
    # 【允许全限定名】`act: com.dollhouse.app.MainActivity` 与 `act: MainActivity` 都要匹配。
    #   第一次自测用全限定名还原事故，正则只认短名，于是漏报 —— 这类漏报比误报危险。
    main_fns = re.findall(
        r"fun\s+([A-Z][A-Za-z0-9_]*)\s*\([^)]*:\s*(?:[a-z][\w.]*\.)?MainActivity",
        code
    )
    if main_fns:
        # 该文件是否有 Activity 类型的局部变量
        act_vars = set(re.findall(r"val\s+([a-zA-Z][a-zA-Z0-9_]*)\s*=\s*findActivity", code))
        act_vars |= set(re.findall(r"val\s+([a-zA-Z][a-zA-Z0-9_]*)\s*=\s*[a-zA-Z.]*getActivity", code))
        for fn in main_fns:
            for v in act_vars:
                if re.search(r"\b" + re.escape(fn) + r"\s*\(\s*" + re.escape(v) + r"\s*[,)]", code):
                    problems.append(
                        f"E6 {fn}(...) 要求 MainActivity，但传入的 {v} 来自 findActivity（返回 Activity）"
                    )

    # E4 注解错位：`@Composable` 后面紧跟 `private const val` / 注释块开头
    if re.search(r"@Composable\s*\n\s*(?:private\s+|internal\s+)?const\s+val", raw):
        problems.append("E4 @Composable 注解落在了常量上（注解错位）")

    # E8 「data class 的实例属性当 object 用」
    #
    # 【为什么会发生】`DhDesign.Type` 是 **data class**，`body/sub/label/caption` 是
    #   **实例属性**。要拿到实例只有两条路：`Type.of(brand, body)` 或 Compose 侧的
    #   `DhTokens.type`。写成 `DhDesign.Type.body` 是 `object.属性` 形态,
    #   值本身没错、符号名也没错，但**取不到实例** ⇒ CI 报 Unresolved reference。
    #
    # 本项目为此连续浪费了两轮 CI（ae75957、e66ee15）。静态检查原本抓不到,
    #   因为它在文本层面完全合法 —— 但「形态」是能静态识别的：
    #   `DhDesign.Type` 后面只允许跟 `of(` / `fallback(` ，不允许跟 `.属性`。
    if re.search(r"DhDesign\s*\.\s*Type\s*\.\s*(?!of\b|fallback\b)[a-z]", raw):
        problems.append(
            "E8 `DhDesign.Type` 是 data class，不能当 object 取实例属性；"
            "请用 DhTokens.type.X（Composable 内）或 DhDesign.Type.of(...)"
        )

    # E10 注解被 KDoc 顶开：`@Composable` 后面紧跟 `/**`
    #
    # 【这是本项目真实踩过两次的形态】
    #   正常写法是「KDoc → 注解 → 函数」：
    #       /** 这个函数的文档 */
    #       @Composable
    #       fun foo()
    #   但用脚本往中间插函数时，锚点常选到注解行，于是变成：
    #       @Composable            ← 变成下一个函数的注解
    #       /** 新函数的文档 */
    #       private fun helper()   ← 普通函数被当成 Composable
    #   两次都是「插入 lighten / softRaised 辅助函数」时踩的，症状一模一样：
    #   辅助函数报「invoke @Composable 必须标注」，而真正的组件丢了注解。
    #
    #   特征极明确：`@Composable` 的**下一个非空行**就是 `/**`。
    #   合法的「注解 + 其他注解 + 函数」不会命中（下一个是 `@`）。
    _l3 = raw.split("\n")
    for _i, _line in enumerate(_l3):
        if _line.strip() != "@Composable":
            continue
        _j = _i + 1
        while _j < len(_l3) and _l3[_j].strip() == "":
            _j += 1
        if _j < len(_l3) and _l3[_j].strip().startswith("/**"):
            problems.append(
                f"E10 @Composable 被 KDoc 顶开：第 {_i + 1} 行的注解下面紧跟文档注释，"
                f"可能插函数时插错了位置（注解会落到下一个函数身上）"
            )

    # E9 悬空注解：`@Composable` 之后（跳过注释）不是声明，也不是另一个注解
    #
    # 【为什么需要】E7 只查「相邻两个 @Composable」。但真实踩过的形态是
    #   「KDoc 被插到了注解与函数之间」：
    #       @Composable          ← 悬空
    #       /** 另一个函数的文档 */
    #       @Composable
    #       private fun foo()
    #   这种时候 E7 能报（因为出现了两次），但如果只留一个 @Composable 悬空、
    #   下面接的是普通函数，E7 就完全看不见。直接校验「注解下面必须是声明」更本质。
    #
    # 【允许的后续】另一个注解（@JvmStatic 等）、常见修饰符 + fun/val/var/class/object/interface。
    _l2 = raw.split("\n")
    for _i, _line in enumerate(_l2):
        if _line.strip() != "@Composable":
            continue
        _j = _i + 1
        while _j < len(_l2):
            _t = _l2[_j].strip()
            if (_t.startswith("*") or _t.startswith("/*") or _t.startswith("//")
                    or _t == "" or _t.startswith("@file:") or _t.startswith("@suppress")):
                _j += 1
                continue
            break
        if _j >= len(_l2):
            continue
        _nx = _l2[_j].strip()
        if _nx.startswith("@"):
            continue
        if re.match(r"^(public |private |internal |protected |open |override |suspend |inline |"
                    r"operator |infix |tailrec |external |actual |expect |abstract |final |"
                    r"fun |val |var |class |object |interface |typealias |constructor)", _nx):
            continue
        problems.append(
            f"E9 @Composable 悬空：第 {_i + 1} 行的注解下面不是声明"
            f"（第 {_j + 1} 行是 `{_nx[:40]}`）"
        )

    # E7 重复注解：同一个声明上方出现两次 `@Composable`（含被 KDoc 隔开的情况）
    #
    # 【为什么会发生】往「已有 `@Composable` 的 private fun」前面插入新函数时，
    #   如果插入点选在注解与函数签名之间，就会变成：
    #       @Composable          ← 原属于 Content
    #       /** 新函数的注释 */
    #       @Composable          ← 新加的
    #       fun ContentForHost(...)
    #       private fun Content(...)   ← 丢了自己的 @Composable
    #   编译期才报 `Composable is not a repeatable annotation type`（kapt 阶段），
    #   而括号平衡、import 齐全等静态检查全都看不出来。本项目本轮真踩过。
    _lines = raw.split("\n")
    for _i in range(len(_lines) - 1):
        if _lines[_i].strip() != "@Composable":
            continue
        # 往下跳过 KDoc / 空白行，看下一个有内容的行是否又是 @Composable
        _j = _i + 1
        while _j < len(_lines) and (_lines[_j].strip().startswith("*")
                                    or _lines[_j].strip().startswith("/*")
                                    or _lines[_j].strip().startswith("//")
                                    or _lines[_j].strip() == ""):
            _j += 1
        if _j < len(_lines) and _lines[_j].strip() == "@Composable":
            problems.append(f"E7 @Composable 重复注解（第 {_i + 1} 行与第 {_j + 1} 行）")

    # E11 文字用了「几何色」：`Text(color = ...line / ...stroke / ...chatBorder)`
    #
    # 【为什么必须有这一条 —— 这是两道现有门禁的盲区】
    #   本工程的三类颜色语义分得很清：
    #     · 文字槽：TITLE / SUB / HINT_FG / CHAT_CHIP_* / ON_ACC
    #     · 几何槽：LINE / STROKE / CHAT_BORDER（画线、描边、分隔线）
    #   几何槽的颜色是按「1.3:1 刚好看得见一条线」调的（见 tools/palette_contrast.py
    #   的「发丝线 / 页面底 ≥1.3」）。一旦把它当文字色用，13sp 中文在 1.3~1.5:1 的
    #   对比度下基本看不见 —— 2026-11 实测在 ui/settings 与 ui/home/ui/doll 一共
    #   **11 处**犯过这个错（MemPage 空态副文案 1.36、图例 1.36、MyProfile 字数计数
    #   1.30、ComingSoonPage 引导句 1.33 …），其中 5 处被用户直接投诉"文字看不见"。
    #
    #   而且**两道现有门禁都抓不到它**：
    #     ① `UiKit.clampPaletteContrast()` 的名单只含文字槽，LINE/STROKE 不在里面
    #        —— 它永远不会被自动夹到 4.5；
    #     ② `tools/palette_contrast.py` 查的是「调色板内两个颜色之间的比值」，
    #        它知道 LINE/CARD=1.50 并认为这是**合格**的（线本来就该这么淡），
    #        它无法知道「有人把这个值拿去当字色了」。
    #   所以只能在源码形态上静态拦：文字色出现几何槽 → 直接报错。
    #
    # 【为什么必须排除非文字用法（误报会毁掉这个门禁的可信度）】
    #   同一个 `c.line` 在下面这些位置是**正确**的，绝不能报：
    #     · `Icon(..., color = c.line)`      —— 装饰图标（空态大图标、EmptyHint）
    #     · `Modifier.border(1.dp, c.line)`  —— 描边
    #     · `.background(c.line)`            —— 填充
    #     · `HorizontalDivider(color = ...)` / `Divider(...)` —— 分隔线
    #     · `setPalette(edge = c.line)`      —— 自绘画布的线色
    #     · `drawLine(color = ...)` / `drawRect(...)` —— Canvas
    #   做法是**只认 `Text(` 的实参**：先按括号配对取出每一个 `Text(` 调用的实参区间，
    #   再只在该区间的**第一层**（深度 1）找 `color =`。
    #   这样 `Icon(...)` / `border(...)` 天然不在考察范围，而 `Text(...)` 里的
    #   嵌套 lambda（`buildAnnotatedString { withStyle(color = ...) }` 之类）因为
    #   深度 > 1 也不会被误判。
    #
    # 【已知边界】只查 `Text(`。工程里 `BasicTextField` 的 `textStyle = TextStyle(color=...)`
    #   不在本条覆盖范围（那是另一种形态，暂时全仓没有犯这个错）。
    GEO_COLORS = ("line", "stroke", "chatBorder")
    _code_lines = code.split("\n")
    for _m in re.finditer(r"(?<![A-Za-z0-9_.])Text\s*\(", code):
        _start = _m.end() - 1          # 指向 "("
        _depth = 0
        _i = _start
        _end = None
        while _i < len(code):
            if code[_i] == "(":
                _depth += 1
            elif code[_i] == ")":
                _depth -= 1
                if _depth == 0:
                    _end = _i
                    break
            _i += 1
        if _end is None:
            continue                   # 括号不平衡由 E3 负责报
        # 扫第一层实参里的 `color = xxx`
        _d = 0
        _i = _start + 1
        while _i < _end:
            _ch = code[_i]
            if _ch in "([{":
                _d += 1
            elif _ch in ")]}":
                _d -= 1
            elif _d == 0 and code.startswith("color", _i):
                _after = code[_i + 5:_i + 9]
                _before_ok = _i == 0 or not (code[_i - 1].isalnum() or code[_i - 1] in "_.")
                _is_assign = re.match(r"\s*=\s*", _after)
                if _before_ok and _is_assign:
                    _val_start = _i + 5 + _is_assign.end()
                    _val_end = _val_start
                    while _val_end < _end and code[_val_end] not in ",)\n":
                        _val_end += 1
                    _val = code[_val_start:_val_end].strip()
                    _hit = next(
                        (g for g in GEO_COLORS
                         if re.search(r"\.\s*" + re.escape(g) + r"\s*$", _val)),
                        None,
                    )
                    if _hit:
                        _line_no = code.count("\n", 0, _i) + 1
                        problems.append(
                            f"E11 第 {_line_no} 行 Text(color = ...{_hit})："
                            f"`{_hit}` 是几何色（画线/描边用，对比度约 1.3~1.5:1），"
                            f"当文字色会几乎看不见；文字请用 sub / title / "
                            f"hintFg / chatChipFg 这些文字槽"
                        )
            _i += 1

    # E12 裸 `.clickable` / `.combinedClickable` 没有 48dp 命中区保障；
    #     真按钮必须改用 `pressable`（内置 minTouchTarget(48) + 按压反馈）。
    #
    # 【本版按 004 的实测重写，修掉旧实现的 4 个漏洞】
    #   ① `combinedClickable` 完全绕过 —— 旧正则只认 `.clickable`。它同样不带下限，
    #      004 用例 #1 就靠它绕过。现由 _CLICKABLE_RE 一并覆盖。
    #   ② 遮罩判别用的是「前 240 字符窗口」—— 祖先/兄弟节点写的 fillMaxWidth
    #      会污染判定，把真按钮误当遮罩放行（漏报）。现改为**只看同一条修饰符链**。
    #   ③ 遮罩只认 `fillMaxSize`，不认 `matchParentSize` —— 后者是弹层遮罩的常见写法，
    #      被误报。现已纳入 _FULL_BLEED_TOKENS。
    #   ④ 窗口左右都不可靠（链比 240 长就漏、比 240 短就串味）。chain 走法没有长度假设。
    #
    # 【判据（正向取证，而不是反向猜遮罩）】
    #   命中 `.clickable` / `.combinedClickable` 后，在**同一条链**上找「有 48dp 保障」的证据：
    #     · `.minTouchTarget(`               —— 就是那个 48dp 下限本身
    #     · `.size/.height/.width(≥48)` 或符号常量 MIN_TOUCH / minTouch / HIT_DP
    #     · `.fillMaxSize` / `.matchParentSize`，或同时 fillMaxWidth + fillMaxHeight
    #   有证据 → 放行；没有 → 报。
    #   顺带解决旧「整文件豁免 DhKit/DhForm」的问题：那两个文件里的按钮工厂
    #   （DhButton / DhChip / OutlineChip / SubmitButton …）现在也受检，
    #   只有 pressable / pressScaleClick / minTouchTarget / pressScaleClickWith
    #   的**函数体内部**才豁免（实现本身必然含 clickable）。
    _e12_raw_lines = raw.split("\n")
    _e12_code_lines = code.split("\n")
    _e12_funcs = _fun_body_ranges(code, _PRESS_IMPL_FUNCS)
    for _m in _CLICKABLE_RE.finditer(code):
        _pos = _m.start()
        _ln = code.count("\n", 0, _pos)
        # ① 实现体内部（pressable 等）不检
        if any(_a <= _pos <= _b and _n in _PRESS_IMPL_FUNCS for _n, _a, _b in _e12_funcs):
            continue
        # ② 允许标记（上一行起、跨空行与注释块都认）
        if allow_marker_above(_e12_raw_lines, _e12_code_lines, _ln,
                              ("// E12-ALLOW:", "// E13-ALLOW:")):
            continue
        # ③ 同链取证
        _back = [k for k in modifier_chain_lines(_e12_code_lines, _ln) if k != _ln]
        _fwd = modifier_chain_lines_forward(_e12_code_lines, _ln)
        # 【为什么整条链都收】48dp 的证据可能写在 clickable 之前（`.size(48).clickable`）
        #   也可能写在之后（`.clickable.size(48)` —— 裸 clickable 没有下限，
        #   内层尺寸就是命中区本身）。只向回看会把后者误报。
        _seg = "\n".join(_e12_code_lines[_k] for _k in _back + [_ln] + _fwd)
        _ok = (
            ".minTouchTarget(" in _seg
            or any(_t in _seg for _t in _ADEQUATE_SYMBOLS)
            or any(_t in _seg for _t in _FULL_BLEED_TOKENS)
            or (".fillMaxWidth" in _seg and ".fillMaxHeight" in _seg)
            or any(float(_s.group(2)) >= 48 for _s in _SIZE_CALL_RE.finditer(_seg))
        )
        if _ok:
            continue
        _what = "combinedClickable" if "combined" in _m.group(0).lower() else "clickable"
        problems.append(
            f"E12 第 {_ln + 1} 行裸 .{_what}：真按钮请改用 pressable "
            f"（内置 48dp 命中区 + 按压反馈）；"
            f"整屏点外部关闭的遮罩请用 fillMaxSize / matchParentSize 铺满再挂点击"
        )

    # E13 `.size(<48)` / `.width(<48)` / `.height(<48)` 写在 48dp 下限的**外层**，
    #     会把 `pressable` 内置的 `minTouchTarget(48)` 生生压回去。
    #
    # 【为什么必须有这一条 —— E12 的盲区】
    #   E12 只拦「裸 `.clickable`」。于是下面这种写法**门禁全绿、命中区却只有 34dp**：
    #       Modifier.size(34.dp).pressable { }      // ← pressable 里确实有 minTouchTarget(48)
    #   但 `size` 是**强制**尺寸（见 DhKit.kt `IconButton` 上方 v0.1.5·K-03 的注释：
    #   「必须先 minTouchTarget 再 size；先 size(40) 再 sizeIn(48) 点击区仍是 40」）。
    #   外层 size 把约束上限压到 34，内层 minTouchTarget 要 48 也只能被夹到 34。
    #   实测全仓 6 处犯这个错（ChatPanels 的分段色块甚至只有 6dp 高）。
    #
    # 【判据】只看 `.pressable(`（以及同一失效模式的 `.minTouchTarget(`）**之前**的固定尺寸：
    #   修饰符链里越靠前 = 越外层 = 约束越先收紧，所以「写在前面」才会压制下限。
    #   反过来 `pressable { }.size(24.dp)` 是**安全**的（size 在内层，被下限夹成 48），
    #   因此只向回看、不向后再查 —— 这条顺序区分是本规则的全部要点。
    #
    # 【为什么用「按行回走」而不是纯正则】修饰符链常跨 5~10 行，且中间夹 KDoc 注释
    #   （ChatPanels 那段隔了 12 行注释）。这里以 strip_code 后的文本为准（注释变成空白，
    #   天然跳过），从触发行往上吃「以 `.` / `modifier =` / `Modifier` 开头的行」，
    #   遇到别的语句就停 —— 正好是一条链的边界。
    #
    # 【已知边界（宁可漏报，不可误报）】
    #   · 只用数值字面量比较：`DhKit.MIN_TOUCH.dp` 这类符号值不参与（无法在静态期解析）；
    #     这会让「`.size(HIT.dp)` 且 HIT=32」漏报，是有意的取舍。
    #   · 不跟进 `val m = Modifier.size(32.dp); Box(m.pressable{})` 这种变量间接。
    #   · 不过问 `sizeIn(maxWidth = …)`（全仓当前无此写法）。
    #   · `pressScaleClick` 不在判据内：它的契约就是「不施加 48dp 下限」
    #     （DhKit.kt 里写明专给自带轮廓的按钮用），报它等于误报。
    _e13_raw_lines = raw.split("\n")
    _e13_lines = code.split("\n")
    for _m in _PRESS_TRIGGER_RE.finditer(code):
        _trig_line = code.count("\n", 0, _m.start())
        _trig_name = "pressable" if ".pressable" in _m.group(0) else "minTouchTarget"
        _report = []
        # ① 同一行里触发点之前的那一段（`Modifier.size(32.dp).pressable{}` 单行写法）
        _same_line_start = code.rfind("\n", 0, _m.start()) + 1
        for _sm in _SIZE_CALL_RE.finditer(code[_same_line_start:_m.start()]):
            _report.append((_trig_line + 1, _sm.group(1), _sm.group(2)))
        # ② 同一条链上、触发点之前的行
        for _k in modifier_chain_lines(_e13_lines, _trig_line)[1:]:
            for _sm in _SIZE_CALL_RE.finditer(_e13_lines[_k]):
                _report.append((_k + 1, _sm.group(1), _sm.group(2)))
        for _size_line, _name, _val in _report:
            if float(_val) >= 48:
                continue
            # 允许标记：写在这行尺寸的上方（跨空行 / 注释块都认）
            if allow_marker_above(_e13_raw_lines, _e13_lines, _size_line - 1,
                                  ("// E13-ALLOW:",)):
                continue
            problems.append(
                f"E13 第 {_size_line} 行 .{_name}({_val}dp) 写在 .{_trig_name} 的**外层**"
                f"（触发点在第 {_trig_line + 1} 行）：外层固定尺寸会把内置的 48dp "
                f"minTouchTarget 压回 {_val}dp，命中区实际只有 {_val}dp。"
                f"改法：尺寸提到 ≥48dp，或把 .{_name}(…) 挪到 .{_trig_name}(…) 之后"
                f"（内层被下限夹住，安全）；确属刻意做小，请在第 {_size_line} 行上方加 "
                f"`// E13-ALLOW: <理由>`"
            )

    # E14 该带图标的组件，调用点不许省略 `iconRes`。
    #
    # 【为什么需要这一条】用户明确要求「做 UI 时多用 Lucide 图标，让界面优美」；
    #   工程已沉淀 137 个 `Icons.IC_*`（24dp 视口语义、线宽 2、圆角端点，取自
    #   lucide-static 官方 SVG）。但 `iconRes` 一律是**有默认值**的可选形参，
    #   漏传不会编译报错、也不会在界面上「坏掉」—— 只是那一行少了个识别锚点。
    #   于是它成为最典型的「新代码悄悄退化」点：谁都不会注意到，只有用户觉得
    #   「这页看着比那页土」。
    #
    # 【只拦「必须带」的那几个，不做一刀切】
    #   带 `iconRes` 形参的组件在 DhKit/DhForm/Components 里有一大把（20+），
    #   但绝大多数是「有图标更好、没有也成立」（SectionLabel / Card / Note …）。
    #   强制它们会导致大面积噪音 + 为了过门禁硬塞图标（反而变成装饰噪音）。
    #   因此判据是一份**显式的必查清单**，只放「图标是识别锚点」的两类：
    #     · DhKit.ActionRow —— 操作行。全仓 84 个调用点，图标用来在长列表里
    #       一眼定位「这行是干什么的」，是用户点名的「操作行要有图标」。 
    #     · DhState        —— 空态。图标是空态的视觉主体，没有它只剩一行灰字。
    #   明确**不查**（附实测数据，便于将来复议）：
    #     · DhKit.SectionLabel —— 分区标题。34 个调用点里 14 个没图标，
    #       且其形参注释写明「默认 0 = 与改造前逐像素一致」；强制会产出 14 条
    #       风格类噪音，且分区标题加图标常与紧邻的卡片图标打架。
    #     · DhKit.Page —— 页面顶栏**根本没有 iconRes 形参**（返回键是内部画的），
    #       想查也无从查起。
    #
    # 【为什么 DhKit.ActionRow 只认**限定名**】
    #   同名私有函数有两处：`ChatUi.kt:1230 private fun ActionRow(host)`（气泡操作条，
    #   参数是 host，跟操作行毫无关系）、`LamdaPage.kt:183 private fun ActionRow(
    #   name, iconRes, enabled, danger, onClick)`（iconRes 是**必填位置参数**）。
    #   若按非限定名匹配，前者会被误报、后者会因为「没写 iconRes = 」被误报 5 处 ——
    #   实测过这两类假红，所以这里要求写成 `DhKit.ActionRow(`。
    #   代价：`with(DhKit) { ActionRow(...) }` 这种写法查不到（全仓无此写法）。
    #   DhState 是 `ui/kit/Components.kt` 里的**顶层函数**，全仓只有一个声明、无同名遮蔽，
    #   故不要求前缀。但它常被写成**全限定名** `com.dollhouse.app.ui.kit.DhState(`
    #   （HEAD 树里 6 处这样写）—— 第一版正则用 `(?<![A-Za-z0-9_.])` 把带点前缀的全排除了，
    #   那 6 处会整片漏掉。所以两种规则都允许「任意包名前缀」。
    #
    # 【变量传参必须放行】`iconRes = if (a) Icons.IC_A else Icons.IC_B` 是合法写法，
    #   判据只看「有没有在顶层给 iconRes 赋过值」，不关心值长什么样，
    #   所以三元 / when / 函数返回 / 变量引用都会自动通过。
    # 【为什么要 `(?:[A-Za-z_][\w]*\.)*` 这个前缀】允许 `com.dollhouse.app.ui.kit.DhState(`
    #   这类全限定写法。去掉前缀就会整片漏报（实测 6 处被漏），而前缀过宽只会多匹配到
    #   「某个对象上的同名方法」—— 本工程没有这种情况。
    _E14_RULES = (
        # (调用点正则, 展示名, 位置参数里 iconRes 是第几个, 允许 null 吗)
        (r"(?<![A-Za-z0-9_])(?:[A-Za-z_][\w]*\.)*DhKit\s*\.\s*ActionRow\s*\(",
         "DhKit.ActionRow", 3, False),
        (r"(?<![A-Za-z0-9_])(?:[A-Za-z_][\w]*\.)*DhState\s*\(", "DhState", 4, False),
    )
    _e14_raw_lines = raw.split("\n")
    _e14_code_lines = code.split("\n")
    for _pat, _disp, _pos_idx, _allow_null in _E14_RULES:
        for _m in re.finditer(_pat, code):
            # 跳过函数声明本身（`fun DhState(` / `fun ActionRow(`）
            if re.search(r"fun\s+(?:[\w.]*\.)?\s*$", code[max(0, _m.start() - 30):_m.start()]):
                continue
            _open = code.rfind("(", 0, _m.end())
            _args = call_args(code, _open)
            if _args is None:
                continue                      # 括号不平衡交给 E3
            _ln = code.count("\n", 0, _m.start())
            if allow_marker_above(_e14_raw_lines, _e14_code_lines, _ln,
                                  ("// E14-ALLOW:",)):
                continue
            _found, _val = top_level_named_value(_args, "iconRes")
            if _found:
                if _val is not None and _val.strip() == "null" and not _allow_null:
                    pass                      # 显式 null = 明确不要图标，按缺失处理
                else:
                    continue
            else:
                # 没写命名实参：可能是位置传参（工程里 ActionRow 全用命名，但留兜底）
                if top_level_arg_count(_args) >= _pos_idx and "=" not in _args:
                    continue
            problems.append(
                f"E14 第 {_ln + 1} 行 {_disp}(…) 没有传 iconRes："
                f"这类元素必须有图标（Lucide `Icons.IC_*`，24dp 视口）作为识别锚点。"
                f"补 `iconRes = Icons.IC_XXX`；确属无图标场景，请在该行上方加 "
                f"`// E14-ALLOW: <理由>`"
            )

    # E16 `Text(fontSize = …, 但缺 lineHeight)`：多行文本会按默认 ~1.2× 行高排版，
    #     一旦统一到令牌的 1.4~1.6× 就会**变高 30~36%**，撑破/裁切固定布局。
    #
    # 【与 E11 的区别】E11 查的是「文字用了几何色」；E16 查的是「字号给了、行高没给」。
    #   两者都用「括号配对取 Text( 的实参区间」的手法，但判据完全不同，故不合并。
    #
    # 【本规则最可能静默失效的三个地方（写之前先想，写完逐个验证）】
    #   ① **调用写法**：`androidx.compose.material3.Text(` 这种全限定名会被
    #      `(?<![A-Za-z0-9_.])Text` 直接挡掉 —— E14 就栽在这上面（6 处漏报）。
    #      所以前缀写成 `(?:[A-Za-z_][\w]*\.)*` 显式允许任意包名。
    #   ② **深度算错**：取 TextStyle/Text 实参时若跳过 `(` 却没记深度，`)` 会把
    #      depth 减成 −1，后面的实参全部"看不见" —— E15 踩过（8 档只解析出 1 档）。
    #      本规则全程只用 `call_args` / 顶层扫描，不手写跳位。
    #   ③ **排除项过宽把自己关死**：例如把「任何 `style =`」都当"已有行高"，
    #      就会把 90% 的真实案例一起放行。所以只认 `DhTokens.type.*` 这一种。
    _E16_TEXT_RE = re.compile(r"(?<![A-Za-z0-9_.])(?:[A-Za-z_][\w]*\.)*Text\s*\(")
    # 【排除 #3】图表刻度 / 数字统计：这两处字号是「读数」而非「段落」，
    #   行高按令牌统一会把刻度挤歪。按文件排除（004 点名的 MiniChart、
    #   以及同类的 TokenStat 统计页）。
    _E16_SKIP_FILES = ("MiniChart.kt", "TokenStat.kt")
    # 【排除 #3 补充】等宽数字（tnum）同理：它是数字，不是段落。
    # 【排除 #4】微对位文本：004 说「DeveloperOptionsPage 有注释说明」。判据就取
    #   「紧邻上方 3 行注释里写明是微调/基线」——文档化了才放行，否则照报。
    _E16_MICRO_WORDS = ("微对位", "微调", "基线", "不参与行高", "不统一行高")
    _e16_raw_lines = raw.split("\n")
    _e16_lines = code.split("\n")
    _e16_funs = [(m.start(), m.group(1).split(".")[-1], m.end())
                 for m in re.finditer(r"\bfun\s+([A-Za-z_][\w.]*)\s*\(", code)]
    if path.name not in _E16_SKIP_FILES:
        for _m in _E16_TEXT_RE.finditer(code):
            _open = code.rfind("(", 0, _m.end())
            _args = call_args(code, _open)
            if _args is None:
                continue
            # 声明体本身不是调用（`fun Text(` 极少见，但别自报）
            if re.search(r"fun\s+(?:[\w.]*\.)?\s*$", code[max(0, _m.start() - 30):_m.start()]):
                continue
            _found_fs, _fs_val = top_level_named_value(_args, "fontSize")
            if not _found_fs:
                continue                      # 没给 fontSize → 不在本条范围（走 style 的人自带宽高）
            if top_level_named_value(_args, "lineHeight")[0]:
                continue                      # 排除 #5 的另一半：显式给了行高
            # 排除 #5：`style = DhTokens.type.*` / `DhDesign.Type.*` —— 令牌自带 lineHeight
            if re.search(r"style\s*=\s*[^,)\n]*(?:DhTokens\.type|DhDesign\.Type|\btype\.)",
                         _args):
                continue
            # 排除 #1：单行。maxLines = 1 / singleLine = true / softWrap = false
            if (re.search(r"maxLines\s*=\s*1\b", _args)
                    or re.search(r"singleLine\s*=\s*true", _args)
                    or re.search(r"softWrap\s*=\s*false", _args)):
                continue
            # 排除 #3 补充：等宽数字。
            # 【必须查 raw 而不是 _args】`strip_code` 会把字符串字面量抹成空格，
            #   而 tnum 恰恰写在字符串里（`fontFeatureSettings = "tnum"`）——
            #   查 strip 后的文本会**永远匹配不到**（实测这条曾静默失效）。
            #   strip_code 保持长度与换行不变，所以用同一个下标区间去 raw 里取，
            #   拿到的是带字符串原文的实参。
            _raw_args = raw[_open + 1:_open + 1 + len(_args)]
            if re.search(r"fontFeatureSettings\s*=\s*[^,)\n]*tnum", _raw_args):
                continue
            _ln = code.count("\n", 0, _m.start())
            # 排除 #4：上方 3 行注释里写明是微对位/微调
            _micro = False
            for _k in range(max(0, _ln - 3), _ln):
                if any(w in _e16_raw_lines[_k] for w in _E16_MICRO_WORDS):
                    _micro = True
                    break
            if _micro:
                continue
            if allow_marker_above(_e16_raw_lines, _e16_lines, _ln, ("// E16-ALLOW:",)):
                continue
            # 排除 #2：固定高度容器。静态期看不到「谁包着它」，退一步用
            #   「所在函数体里出现固定 .height(N.dp) / .size(N.dp)」近似 ——
            #   这是个**粗粒度、偏漏报**的抑制（宁可漏报不误报），
            #   代价是同一个函数里别的自由高度文本也被一起放过。
            _fn = None
            for _st, _nm, _en in _e16_funs:
                if _st < _m.start():
                    _fn = (_nm, _en)
                else:
                    break
            if _fn:
                _br = code.find("{", _fn[1])
                _body = block_span(code, _br) if _br > 0 else None
                if _body and (re.search(r"\.height\(\s*[0-9]+", _body)
                              or re.search(r"\.size\(\s*[0-9]+", _body)):
                    continue
            problems.append(
                f"E16 第 {_ln + 1} 行 Text(fontSize = {_fs_val}) 缺 lineHeight："
                f"多行时按默认 ~1.2× 排版，与令牌 1.4~1.6× 差 30%+ 会导致撑破/裁切。"
                f"请补 `lineHeight = N.sp`（或用 `style = DhTokens.type.*`）；"
                f"确属单行/固定高度/微对位，请在上方加 `// E16-ALLOW: <理由>`"
            )

    # E17 同一个调用的**具名实参重复**（如 `Text(lineHeight = …, lineHeight = …)`）。
    #
    # 【为什么必须有这一条】2026-11 一天里 CI 挂了三次，其中一次的根因就是
    #   两个人并发改同一处、各加了一个 `lineHeight =`，位置不同所以都留下了：
    #       Text(
    #           lineHeight = DhTokens.type.sub.lineHeight,   // 003 加的
    #           fontSize = UiKit.FS_SUB.sp,
    #           lineHeight = DhTokens.type.sub.lineHeight,   // 004 加的
    #       )
    #   编译器报的是 `None of the following candidates is applicable` —— 完全看不出
    #   是「重复参数」。E5 查的是重复**顶层声明**，管不到具名实参。
    #   这条规则成本极低（一次 O(n) 括号表 + 2.4 万次区间扫描），却能省掉一整轮 CI。
    #
    # 【判定：只看「同一个调用」的「深度 0」】
    #   `outer(a = 1, inner(a = 2))` 不是重复 —— 后者在自己那层括号里，深度 > 0。
    #   用「按括号/方括号/花括号同步升降的深度」实现：只有 depth == 0 的 `name =`
    #   才是**这个调用**的具名实参；lambda（`{ … }`）与嵌套调用里的一律不算。
    #
    # 【为什么用 strip_code 后的文本（和 E16 的 tnum 相反）】
    #   本条要规避的正是「字符串/注释里出现 `a=1, a=2`」这种假阳性 →
    #   用 strip 后文本（字符串与注释已抹成空格）**天然正确**。
    #   E16 那条要判断的是字符串**内容**（tnum），才必须回 raw 里取 —— 两者方向相反。
    #
    # 【最可能失效的三个地方（写之前先列，写完逐个反向验证）】
    #   ① 括号配对失败 → 该调用被跳过。故统计失败数，非 0 就单独报一条，
    #      不让「整条规则静默不跑」（E15 的教训）。
    #   ② 深度算错把嵌套当同层（E15 踩过：跳过 `(` 却没记深度）。
    #      这里每个字符都过一遍升降，不手写跳位。
    #   ③ 把**声明体的默认值**误判成具名实参：
    #      `fun F(a: Int = 0, b: Int = 1)` 里两个 `Int = ` 会被看成同名实参 `Int`。
    #      所以：函数/类/构造声明整条跳过；另外对每个 `name =` 检查「本段逗号之后
    #      到名字之间是否出现过 `:`」（`name: Type = v` 就是声明形态）→ 跳过。
    _E17_CALL_RE = re.compile(r"(?<![A-Za-z0-9_.])([A-Za-z_][\w.]*)\s*\(")
    _E17_KEYWORDS = {"if", "for", "while", "when", "catch", "return", "switch",
                     "super", "this", "fun", "else", "do", "try", "throw", "in", "is"}
    _e17_pm = paren_map(code)
    _e17_pairfail = 0
    for _m in _E17_CALL_RE.finditer(code):
        _cname = _m.group(1).split(".")[-1]
        if _cname in _E17_KEYWORDS:
            continue
        # 声明体跳过（函数 / 类 / 接口 / 对象 / 次构造函数）
        if re.search(r"\b(fun|class|interface|object|constructor)\s+(?:[\w.]*\.)?\s*$",
                     code[max(0, _m.start() - 40):_m.start()]):
            continue
        _open = code.rfind("(", 0, _m.end())
        _close = _e17_pm.get(_open)
        if _close is None:
            _e17_pairfail += 1
            continue
        _args = code[_open + 1:_close]
        # 快速通道：实参里没有 `=` 就不可能有具名实参（2.4 万 → 2686，省 2 秒）
        if "=" not in _args:
            continue
        _seen = {}
        _d = 0
        _i = 0
        _seg = 0                     # 本段（顶层逗号之后）的起点，用于判断 `name: Type =`
        while _i < len(_args):
            _ch = _args[_i]
            if _ch in "([{":
                _d += 1
            elif _ch in ")]}":
                _d -= 1
            elif _ch == "," and _d == 0:
                _seg = _i + 1
            elif _d == 0:
                _nm = re.match(r"([A-Za-z_]\w*)\s*=(?!=)", _args[_i:])
                if _nm and (_i == 0 or not (_args[_i - 1].isalnum() or _args[_i - 1] in "_.")):
                    if ":" in _args[_seg:_i]:
                        _i += _nm.end()      # 声明形态 `name: Type = v`，不是具名实参
                        continue
                    _key = _nm.group(1)
                    _line = code.count("\n", 0, _open + 1 + _i) + 1
                    if _key in _seen:
                        problems.append(
                            f"E17 第 {_line} 行调用 {_cname}(…) 的具名实参 `{_key} =` 重复"
                            f"（首次出现在第 {_seen[_key]} 行）：编译器的报错形如 "
                            f"`None of the following candidates is applicable`，很难看出根因。"
                            f"大概率是两人并发改同一处各加了一次 —— 删掉其中一处。"
                        )
                    else:
                        _seen[_key] = _line
                    _i += _nm.end()
                    continue
            _i += 1
    if _e17_pairfail:
        problems.append(
            f"E17 有 {_e17_pairfail} 个调用的括号配对失败、无法检查重复实参："
            f"说明源码括号不平衡（E3 会另外报）；本条在这些调用上**没生效**"
        )

    return problems


def main():
    files = sorted(SRC.rglob("*.kt"))
    total = 0
    bad_files = 0
    warn_total = 0      # E16 警告条数（不阻塞）
    warn_files = 0      # E16 警告涉及的文件数
    for f in files:
        rel = "/" + str(f).replace("\\", "/")
        if any(rel.endswith(e) or e in rel for e in EXEMPT):
            continue
        probs = check_file(f)
        if not probs:
            continue
        # 【E16 分阶段启用】未开 --e16-block 时把 E16 从「错误」里摘出来，
        #   既不进 bad_files 也不进 total → 退出码完全不受它影响。
        hard = [p for p in probs if E16_BLOCK or not p.startswith("E16")]
        warn = [] if E16_BLOCK else [p for p in probs if p.startswith("E16")]
        if hard:
            bad_files += 1
            print(f"FAIL {f}")
            for p in hard:
                print(f"       {p}")
                total += 1
        if warn:
            warn_files += 1
            print(f"WARN {f}")
            for p in warn:
                print(f"       WARN {p}")
                warn_total += 1
    # E15 专项：DesignSystem.kt 在 EXEMPT 里（check_file 不会跑它），
    #   而「TypeSp 必须与 Type.of 同步」恰恰只能在这个文件内部校验，故单独调一次。
    # 用 rglob 定位而不是写死路径：早先写死 `SRC / "ui/design/DesignSystem.kt"` 少了
    # `com/dollhouse/app` 前缀，文件不存在 → `if _ds.exists()` 为假 → E15 静默从未执行。
    # 这种「门禁自己悄悄不跑」是最危险的形态，所以这里按文件名在所有源文件里找。
    for _ds in [f for f in files if f.name == "DesignSystem.kt"]:
        _e15 = check_type_sp_consistency(_ds)
        if _e15:
            bad_files += 1
            print(f"FAIL {_ds}")
            for _p in _e15:
                print(f"       {_p}")
                total += 1

    if warn_total:
        print(f"\nE16 警告 {warn_total} 处（不阻塞，待行高补齐后转阻塞）"
              f" —— 涉及 {warn_files} 个文件；降到 ≤5 处时给 CI 加 `--e16-block`")
    if bad_files:
        print(f"\n检查 {len(files)} 个文件：{bad_files} 个有问题，共 {total} 处")
        return 1
    if not QUIET:
        if warn_total:
            print(f"检查 {len(files)} 个文件：0 个错误，E16 警告 {warn_total} 处（不阻塞）")
        else:
            print(f"检查 {len(files)} 个文件：未发现问题")
    return 0


if __name__ == "__main__":
    sys.exit(main())