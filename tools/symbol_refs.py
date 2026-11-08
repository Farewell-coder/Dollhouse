#!/usr/bin/env python3
"""
Dollhouse 公开符号引用检查（防「删文件误伤同文件里的其它符号」）

【为什么需要这个脚本】
  本轮真实事故：删除 `MessageMenu.kt`（原生 Dialog 菜单）时，该文件**末尾还定义着**
  `object MultiSelect`。删文件的依据是「MessageMenu 这个符号 0 处引用」，
  但没有覆盖「文件里还有别的公开符号」——于是 `ChatUi` 的 9 处 `MultiSelect` 引用
  全部变成 `Unresolved reference`，整轮 CI（8 分钟）白跑。

  同类风险还有：改包名、搬文件、合并类时漏掉某个 top-level 声明。

【它做什么】
  1. 扫描全部 .kt，收集每个文件的 **top-level 公开声明**（object / class / fun / val / const val）。
  2. 对每个声明，检查是否有**其它文件**引用它。
  3. 报告「零外部引用」的声明 —— 这些可能是：
       · 合法的私有实现（应当加 private）→ 提示而非错误
       · 真正的死代码 → 应删除
       · **被误删文件的残留引用**（反向：引用存在但定义没了）→ 这是本脚本最重要的检查

  第 4 项检查最关键：对每个「看起来像已知符号」的引用，若全仓找不到定义、
  也不是标准库/Compose/本工程已知外部包，就报 UNRESOLVED。
  这是在没有编译器的情况下，最接近「Unresolved reference」的本地信号。

用法：python3 tools/symbol_refs.py [--quiet]
退出码：0 = 没有悬空引用（零外部引用的声明只提示，不算失败）
"""
import re
import sys
from pathlib import Path

# 【--path 的用处】反向验证：把源码拷到临时目录、注入一个假名字，确认门禁真的会红。
#   不能反向验证的门禁等于没有（本工程反复吃过这个亏）。
SRC = Path(sys.argv[sys.argv.index("--path") + 1]) if "--path" in sys.argv else Path("app/src/main/java")
QUIET = "--quiet" in sys.argv

# 已知的「不需要在本仓找到定义」的名字来源：JDK / Kotlin / Compose / Android / 依赖库 / 同包工具
KNOWN_EXTERNAL_PREFIX_HINT = (
    "android", "androidx", "kotlin", "kotlinx", "java", "javax", "org",
    "com.google", "dev.rikka", "rikka", "androidx.compose",
)

# 声明形态：顶层（顶格）或 object/class 内一层（4 空格缩进）
DECL_RE = re.compile(
    r"^(?P<indent>\s{0,4})(?:(?:public|internal|private|protected)\s+)?"
    r"(?:(?:data|sealed|abstract|open|enum|annotation|companion|inline|suspend|operator|"
    r"infix|tailrec|external|const)\s+)*"
    r"(?P<kind>object|class|interface|fun|val|var)\s+"
    r"(?:(?:<[^>]+>)\s*)?"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)",
    re.M,
)

# 明显不是「本工程符号」的关键字与常见类型
# 【噪音表】Kotlin/JDK 内置类型与 Common 形态。这些名字一定"找不到本仓定义"，
#   但它们是语言的一部分。第二版曾因漏了 ByteArray / BooleanArray / Character
#   而报出 47 条假红 —— 逐个补，而不是靠放宽阈值。
NOISE = {
    "if", "else", "for", "while", "when", "try", "catch", "return", "fun", "val", "var",
    "class", "object", "interface", "companion",
    # Kotlin / JDK 基本类型与其包装
    "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Short", "Byte",
    "Unit", "Any", "Nothing", "Number", "Character",
    "ByteArray", "CharArray", "ShortArray", "IntArray", "LongArray", "FloatArray",
    "DoubleArray", "BooleanArray", "Array",
    "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet",
    "ArrayList", "LinkedHashSet", "HashMap", "LinkedHashMap", "HashSet",
    "Pair", "Triple", "Sequence", "Iterable", "Collection",
    "Exception", "Throwable", "RuntimeException", "IllegalStateException",
    "IllegalArgumentException", "Error", "IndexOutOfBoundsException",
    "Runnable", "Thread", "System", "Math", "StringBuilder", "Object",
    # Compose 常见类型（多为 import 后使用，个别处以全限定引用）
    "Modifier", "Color", "Text", "Row", "Column", "Box", "Spacer", "Image", "Icon",
    "Alignment", "Arrangement", "Offset", "Size", "Dp", "TextStyle", "FontWeight",
    "RoundedCornerShape", "PaddingValues", "KeyboardOptions", "KeyboardActions",
    # JDK / Kotlin 标准库里以 PascalCase 出现的名字（这类永远找不到本仓定义）
    "Charsets", "Class", "Integer", "Runtime", "Regex", "RegexOption", "Thread",
    "InterruptedException", "IllegalStateException", "NullPointerException",
    # Kotlin 注解（以 PascalCase 出现、无本仓定义）
    "Deprecated", "DeprecationLevel", "OptIn", "Suppress", "Throws", "JvmStatic",
    "JvmField", "JvmOverloads", "Serializable", "Target", "Retention",
    # 本仓里以「函数名」形式出现、但定义在同文件更高层的私有扩展（正则抓不到）
    "AndroidColor", "ComposeDialog", "EdgeCutLine", "CenterMarker",
}


# 【kotlin.* 默认导入】这些名字在 Kotlin 里**不需要 import**（语言默认导入），
#   所以「全仓找不到 import 行、也找不到本仓定义」是**正常形态**，不是悬空引用。
#   【为什么单列一张表而不是塞进 NOISE】NOISE 是「JDK/Compose 短名」的大杂烩，
#   混进去以后没人知道这些名字有特殊依据；单列 + 这条注释，下一个人一看就懂。
#   【起因】`@Deprecated(replaceWith = ReplaceWith("..."))` 曾被误报成 UNRESOLVED，
#   于是 UiKit 的弃用注解只好省略 replaceWith —— 门禁误报把正常写法逼走了。
#   现在它们都在白名单里，可以放心写。
KOTLIN_DEFAULT_IMPORTS = {
    # 注解本身与其参数类型
    "Deprecated", "DeprecationLevel", "ReplaceWith", "Suppress", "OptIn",
    "SinceKotlin", "ExperimentalStdlibApi", "ExperimentalMultiplatform",
    # 其它常用的默认导入（kotlin / kotlin.io / kotlin.text 的对象与类型）
    "Unit", "Any", "Nothing", "Result", "LazyThreadSafetyMode",
}


def strip_comments_and_strings(text):
    """把注释与字符串挖空，避免误把注释里的词当引用。"""
    out = list(text)
    i, n, depth = 0, len(text), 0
    while i < n:
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
            if text[i] != "\n":
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
            j = n if j < 0 else j
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
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] in ('"', "\n"):
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                if text[k] != "\n":
                    out[k] = " "
            i = j + 1
            continue
        if text[i] == "'":
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


def main():
    files = sorted(SRC.rglob("*.kt"))
    raw = {}
    code = {}
    for f in files:
        t = f.read_text(encoding="utf-8", errors="replace")
        raw[f] = t
        code[f] = strip_comments_and_strings(t)

    # 1) 收集全部顶层声明
    decls = {}   # name -> [(file, kind)]
    for f in files:
        for m in DECL_RE.finditer(code[f]):
            name = m.group("name")
            if name in NOISE:
                continue
            decls.setdefault(name, []).append((f, m.group("kind")))

    # 2) 检查「悬空引用」
    #
    # 【性能】第一版对每个候选符号都扫一遍全仓正则，273 个文件 × 数千符号 →
    #   跑 4 分钟还没完（本地直接超时），等于不能进 CI 门禁。
    #   改成一次性建「标识符 → 出现在哪些文件」倒排索引，之后 O(1) 查。
    #
    # 【只查 PascalCase 的对象/类引用】全大写常量(NAV)、枚举值(NONE)、
    #   平台类(OnClickListener) 会带来大量假红；Kotlin 里本工程定义的
    #   object / class 几乎都是「首字母大写 + 其余含小写」。
    ref_pattern = re.compile(r"(?<![\w.])([A-Z][a-z][A-Za-z0-9_]{1,})\s*(?:[.(])")

    index = {}   # name -> set(file)
    for f in files:
        for m in ref_pattern.finditer(code[f]):
            index.setdefault(m.group(1), set()).add(f)

    # 已知外部来源：本仓 import 过的全限定名末段
    imported = set()
    for f in files:
        for m in re.finditer(r"^import\s+([\w.]+)", raw[f], re.M):
            imported.add(m.group(1).split(".")[-1])

    # 【判据收紧：只报「跨文件」的悬空引用】
    #   同一文件里定义的内部类 / 局部类（如 BlockRenderer 里的 CardBlock）本来就合法，
    #   在「全仓找不到定义」的粗判据下会被误报。真实事故（MultiSelect）的形态是
    #   **定义在 A 文件、被 B 文件引用，而 A 的定义没了** —— 那必然表现为
    #   「某个名字被引用它的文件之外的文件用到，但全仓没有定义」。
    # 【最终判据：只看「本工程包内被显式 import 的名字」】
    #   正则无法可靠做名字解析：全大写常量、枚举、内部类、注解、JDK 类都会混进来，
    #   前几版分别报出 1976 / 47 / 27 条假红，等于没有信号。
    #   真正可靠的强信号只有一个：**Kotlin 源码里 `import com.dollhouse....Xxx`**
    #   —— 那一定是跨文件使用的本工程符号。若全仓找不到 Xxx 的定义，
    #   必然是「定义被删/改名而引用还在」，也就是 MultiSelect 那类事故。
    # 【判据：同包内的 PascalCase 使用 + 全仓无定义】
    #   Kotlin 里**同包**符号不需要 import，所以「靠 import 判断」会漏掉最常见的事故形态
    #   （MultiSelect 与 ChatPanel 同在 ui.chat 包，删除后 ChatUi 只是报 Unresolved reference，
    #   不会有任何 import 行消失）。
    #
    #   因此必须回到「使用方」判断，但要压掉假红。压法：只看
    #     · 首字母大写 + 含小写的 PascalCase（本工程 object/class 的形态）
    #     · 该名字在**引用它的文件里没有定义**
    #     · 该名字**全仓没有定义**（def_here 为空）
    #   JDK/Compose 的短名（Charsets / Deprecated / Class…）由 NOISE + imported 兜住。
    unresolved = {}
    for name, where in index.items():
        if (name in decls or name in imported or name in NOISE
                or name in KOTLIN_DEFAULT_IMPORTS):
            continue
        # 【定义识别必须宽松】之前只认 `object|class|interface` + 顶格，
        #   于是 `private fun BlockRenderer(...)`、`@Composable private fun DetailPage`
        #   、带注解的 `private class Stat` 全被当成「无定义」，报出 27 条假红。
        #   这里改成：只要文件里出现该名字的**任意声明形态**（含 fun/val/任意缩进）即算有定义。
        def_here = [f for f in where
                    if re.search(
                        r"(?:object|class|interface|fun|val|var)\s+" + re.escape(name) + r"\b",
                        code[f])]
        if def_here:
            continue
        unresolved[name] = where

    # 3) 报告
    failed = False
    real_unresolved = unresolved
    if real_unresolved:
        failed = True
        print("== 悬空引用（有使用、全仓找不到定义，且不是已知 import）==")
        for n in sorted(real_unresolved)[:30]:
            where = sorted({str(x).replace("app/src/main/java/", "") for x in real_unresolved[n]})
            print(f"  UNRESOLVED {n}  ← {', '.join(where[:2])}"
                  + (f" 等 {len(where)} 处" if len(where) > 2 else ""))

    # 4) 提示：零外部引用的顶层声明（可能是死代码 / 应加 private）
    orphans = []
    for name, defs in decls.items():
        if len(defs) > 1:
            continue
        f, kind = defs[0]
        if kind not in ("object", "class", "interface", "fun"):
            continue
        users = index.get(name, set())
        if not users - {f}:
            orphans.append((str(f).replace("app/src/main/java/", ""), name, kind))

    if orphans and not QUIET:
        print("\n== 零引用的顶层声明（可能是死代码，或应改为 private）==")
        for f, n, k in orphans[:15]:
            print(f"  UNUSED {k} {n}  ← {f}")
        if len(orphans) > 15:
            print(f"  … 另有 {len(orphans) - 15} 项")

    if failed:
        print(f"\n检查 {len(files)} 个文件：发现 {len(real_unresolved)} 个悬空引用")
        return 1
    if not QUIET:
        print(f"\n检查 {len(files)} 个文件：无悬空引用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
