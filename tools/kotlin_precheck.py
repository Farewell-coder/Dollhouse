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


def strip_code(text):
    """把字符串与注释替换成空格，返回等长文本，用于括号平衡与引用检测。"""
    out = list(text)
    i = 0
    n = len(text)
    depth = 0
    while i < n:
        c = text[i]
        # 块注释（可嵌套）
        if depth > 0:
            if text.startswith("/*", i):
                depth += 1
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if text.startswith("*/", i):
                depth -= 1
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c != "\n":
                out[i] = " "
            i += 1
            continue
        if text.startswith("/*", i):
            depth = 1
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
    return "".join(out)


def check_file(path: Path):
    raw = path.read_text(encoding="utf-8", errors="replace")
    code = strip_code(raw)
    problems = []

    # E3 括号平衡
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

    return problems


def main():
    files = sorted(SRC.rglob("*.kt"))
    total = 0
    bad_files = 0
    for f in files:
        rel = "/" + str(f).replace("\\", "/")
        if any(rel.endswith(e) or e in rel for e in EXEMPT):
            continue
        probs = check_file(f)
        if probs:
            bad_files += 1
            print(f"FAIL {f}")
            for p in probs:
                print(f"       {p}")
                total += 1
    if bad_files:
        print(f"\n检查 {len(files)} 个文件：{bad_files} 个有问题，共 {total} 处")
        return 1
    if not QUIET:
        print(f"检查 {len(files)} 个文件：未发现问题")
    return 0


if __name__ == "__main__":
    sys.exit(main())