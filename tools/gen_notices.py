#!/usr/bin/env python3
"""
第三方许可声明生成器（THIRD_PARTY_NOTICES.md 的表格 + licenses/bundled/ 的随附文本）

【为什么必须有这个脚本】
  `THIRD_PARTY_NOTICES.md` 之前是**手写维护**的，2026-11 的审计实测后果：
    · 声明表 79 行 / 72 个唯一坐标，而发行包实际解析 **155 个制品** → 漏 83 条；
    · 30 条版本过时（最夸张的 kotlin-stdlib 写 1.9.24、实际 2.2.10，差一个大版本）；
    · 7 行重复，且 `androidx.fragment` 两行版本互相矛盾（1.5.1 vs 1.3.6）。
  这不是"补一遍"能解决的：下一次升依赖立刻again过期。所以改为**从构建产物生成**。

【权威数据源】`app/build/outputs/sdk-dependencies/release/sdkDependencies.txt`
  由 AGP 在 `:app:assembleRelease` 时自动产出（本机 0.2.8 构建为 3079 行 / 155 个 maven 制品），
  含每个制品的 groupId / artifactId / version / sha256。它是"这次发行包里到底有什么"的
  唯一可信答案 —— 比 `build.gradle` 的声明准确（那只是"我要什么"），也比手抄准确。

【本脚本做什么】
  1. 解析 sdkDependencies.txt → 坐标清单；
  2. 逐个从 Gradle 缓存定位制品（jar/aar）与 POM，读 POM 的 `<licenses>` 段定许可；
  3. 扫制品内的随附许可文本（`third_party_licenses.*` / `META-INF/LICENSE*` / `NOTICE*`），
     需要随包保留的复制到 `licenses/bundled/`；
  4. 渲染 `THIRD_PARTY_NOTICES.md`：**顶部「代码与图标」手写段原样保留**（Lucide / Shizuku /
     lamda 不是 maven 制品，不能自动生成），只重写「实际发行构建依赖」这一节；
  5. `--check` 只比对不写盘，不一致就非 0 退出 —— 挂进 CI，让"依赖变了但声明没更新"立刻红。

【三条设计纪律】
  · **不臆造许可**：拿不到 POM 就明说"需人工确认"，绝不猜成 Apache-2.0；
    唯一的兜底是脚本内 [LICENSE_OVERRIDES]，逐条写明理由，可审计。
  · **不刷屏 bundled/**：Apache-2.0 的 130 多份 AAR 内 `LICENSE.txt` 内容与
    `licenses/Apache-2.0.txt` 逐字相同，**一份通用文本即可覆盖**（Apache-2.0 §4(a) 允许），
    所以同内容的一律跳过；只提取"组件专属"的（`third_party_licenses.*`、`NOTICE*`、
    以及非 Apache 的许可原文）。
  · **不做破坏性删除**：多余的 bundled 文件只报告，要清理得显式传 `--prune`。

【用法】
  python3 tools/gen_notices.py            # 只报告差异，不写盘（默认，安全）
  python3 tools/gen_notices.py --check    # CI 门禁：有差异 → 退出码 1
  python3 tools/gen_notices.py --write    # 生成并写盘（表格 + bundled/）
  python3 tools/gen_notices.py --write --prune   # 顺带删掉不再需要的 bundled 文件
  python3 tools/gen_notices.py --verbose  # 打印每个制品的处置明细

退出码：0 = 一致/成功；1 = --check 发现不一致；2 = 数据源或前置条件缺失（不是"不一致"）。

【已知限制（写在这里，免得下一个人以为它无所不能）】
  · 制品必须已在**本机 Gradle 缓存**里（`~/.gradle/caches/modules-2/files-2.1/`）。
    CI 上要跑本脚本，得先跑一次 `assembleRelease`（或至少把依赖解析下来）。
  · 许可字段只读 POM 的 `<licenses>`；POM 缺失时见 [LICENSE_OVERRIDES]。
  · 无法判定"某组件是否真的进了 dex"（R8 之后可能整个模块被裁掉）。本表口径是
    **"解析到的依赖"而非"字节码里实际存在的类"** —— 与改造前的口径一致，且更保守（宁多不少）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- 常量与路径

ROOT = Path(__file__).resolve().parent.parent
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"
LIC_DIR = ROOT / "licenses"
BUNDLED = LIC_DIR / "bundled"
APACHE_TXT = LIC_DIR / "Apache-2.0.txt"

#: AGP 产出的依赖元数据（相对仓库根）
SDK_DEPS = ROOT / "app/build/outputs/sdk-dependencies/release/sdkDependencies.txt"

#: 生成节的分界标题。它**之前**的内容原样保留（手写段），它**之后**由本脚本重写。
GENERATED_HEADING = "## 实际发行构建依赖"

#: Gradle 缓存根（可用环境变量覆盖，便于在别处跑）
GRADLE_CACHE = Path(
    os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
) / "caches/modules-2/files-2.1"

#: POM 里拿不到许可时的兜底。**每条都要写理由**，且只在有独立依据时加。
#: 键为 `group:artifact`（不带版本 —— 同一个包跨版本许可极少变；真变了就把版本写进键）。
LICENSE_OVERRIDES = {
    # Guava 发布的占位制品：真正的代码在 com.google.guava:guava 里，本制品 POM 无 <licenses> 段。
    # 上游仓库（github.com/google/guava）声明 Apache-2.0，故按 Apache-2.0 记。
    "com.google.guava:listenablefuture": "The Apache Software License, Version 2.0",
    # Okio 1.x 的 POM 在本地缓存里没有（只缓存了 jar）；其上游仓库声明 Apache-2.0。
    "com.squareup.okio:okio": "The Apache Software License, Version 2.0",
}

#: 需要"复制到 licenses/bundled/"的随附文件判定（AAR 根 / JAR 内 / classes.jar 内）
THIRD_PARTY_RE = re.compile(r"^third_party_licenses\.(json|txt)$", re.I)
META_LICENSE_RE = re.compile(r"^META-INF/.*(licen[cs]e|notice)", re.I)

#: 不参与"随附文件"扫描的容器噪声（避免把 130 份 Apache LICENSE.txt 全抄出来）
SKIP_IF_SAME_AS_APACHE = True


def log(msg: str) -> None:
    print(msg)


def _rel(p: Path) -> str:
    """仓库内路径 → 相对路径（便于阅读）；不在仓库内的原样返回。

    【为什么要这个】写盘日志里 `path.relative_to(ROOT)` 在"脚本被复制到别处跑"
    或单测里会直接抛 ValueError —— 日志不该让主流程崩掉。
    """
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def _fail(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    """前置条件不满足：打印原因并以**退出码 2** 结束。

    【为什么与 --check 的 1 分开】两者语义完全不同：
      1 = 声明表与依赖**不一致**（要人去 --write 并提交）；
      2 = **跑不起来**（缺数据源、文件结构变了）。
    CI 上必须能区分 —— 否则"环境没准备好"会被误读成"许可声明过期"。
    """
    print(msg, file=sys.stderr)
    raise SystemExit(2)


# ---------------------------------------------------------------- 1. 读依赖元数据


LIB_RE = re.compile(
    r'maven_library\s*\{\s*'
    r'groupId:\s*"([^"]+)"\s*'
    r'artifactId:\s*"([^"]+)"\s*'
    r'version:\s*"([^"]+)"',
    re.S,
)


def read_sdk_deps(path: Path) -> list[tuple[str, str, str]]:
    """从 AGP 的 sdkDependencies.txt 里取出全部 maven 坐标。

    【格式稳定性】这是 protobuf 的文本表示，字段顺序固定为 groupId / artifactId / version；
    用一个宽松正则即可，且比引入 protobuf 解析器更抗升级（字段多了也不影响）。
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    out: list[tuple[str, str, str]] = []
    seen = set()
    for g, a, v in LIB_RE.findall(text):
        key = (g, a, v)
        if key in seen:
            continue
        seen.add(key)
        out.append(key)
    out.sort(key=lambda t: (t[0].lower(), t[1].lower(), t[2]))
    return out


# ---------------------------------------------------------------- 2. 定位制品与 POM


def cache_dir(group: str, artifact: str, version: str) -> Path:
    return GRADLE_CACHE / group / artifact / version


def find_files(group: str, artifact: str, version: str, suffix: str) -> list[Path]:
    """在 Gradle 缓存里找某个坐标的 `*.jar` / `*.aar` / `*.pom`。

    【为什么不是固定路径】缓存布局是 `<group>/<artifact>/<version>/<sha1>/<file>`，
    那个 sha1 目录名我们无从预知；只能 glob 一层。
    """
    d = cache_dir(group, artifact, version)
    if not d.is_dir():
        return []
    return sorted(p for p in d.glob("*/*") if p.is_file() and p.name.endswith(suffix))


def read_pom_license(group: str, artifact: str, version: str) -> tuple[str, str]:
    """返回 (许可名, 依据)。拿不到就返回 ("", 原因) —— **绝不猜**。"""
    poms = find_files(group, artifact, version, ".pom")
    if poms:
        try:
            t = poms[0].read_text(encoding="utf-8", errors="replace")
            m = re.search(r"<licenses?>(.*?)</licenses?>", t, re.S)
            if m:
                names = re.findall(r"<name>(.*?)</name>", m.group(1), re.S)
                if names:
                    return names[0].strip(), "POM"
                urls = re.findall(r"<url>(.*?)</url>", m.group(1), re.S)
                if urls:
                    return urls[0].strip(), "POM(url)"
            reason = "POM 无 <licenses> 段"
        except Exception as e:  # noqa: BLE001 - 解析失败不该中断整批
            reason = f"POM 解析失败: {e}"
    else:
        reason = "缓存无 POM"
    # 【顺序很重要】POM 拿不到（缺段/缺文件/解析失败）时才看兜底表；
    # 兜底表也查不到就**如实说"需人工确认"**，绝不默认成 Apache-2.0 ——
    # 旧表最大的问题就是"没查到的都算 Apache-2.0"。
    key = f"{group}:{artifact}"
    if key in LICENSE_OVERRIDES:
        return LICENSE_OVERRIDES[key], "脚本内兜底表"
    return "", reason + "，需人工确认"


# ---------------------------------------------------------------- 3. 扫随附许可文本


def read_apache_text() -> bytes:
    try:
        return APACHE_TXT.read_bytes()
    except OSError:
        return b""


def _same_as_apache(data: bytes, apache: bytes) -> bool:
    """这份文本是否已被通用的 `licenses/Apache-2.0.txt` 覆盖（那样不必再存一份）。

    【为什么不是简单的 == 比较】实测 52 个 AndroidX AAR 里的 `META-INF/androidx/**/LICENSE.txt`
    是 Apache-2.0 的正文，但**比我们仓库那份短**（10175 vs 11358 字节 —— 我们那份多了文件末尾
    "How to apply" 附录）。逐字节不等，可归一化后它是我们那份的**子串**。
    所以要按「互为子串」判，否则会把 52 份几乎一样的 Apache 文本全抄进 bundled/，
    反而把真正的组件专属文本（ML Kit 的 third_party_licenses、jakarta 的 NOTICE、
    BouncyCastle 的许可）淹掉。
    """
    if not apache:
        return False

    def norm(b: bytes) -> bytes:
        return re.sub(rb"\s+", b" ", b).strip().lower()

    n_data, n_apache = norm(data), norm(apache)
    # 太短的文本不参与「互为子串」判定，避免偶然命中（真许可都远超此长度）。
    if len(n_data) < 500:
        return False
    return n_data in n_apache or n_apache in n_data


def _bundled_name(group: str, artifact: str, version: str, inner: str) -> str:
    """bundled/ 里的文件名：`<group>-<artifact>-<version>-<内部路径拍平>`。

    【为什么要拍平内部路径】AAR 里 `META-INF/androidx/compose/ui/ui/LICENSE.txt` 这类
    同 basename 的文件极多，只留 basename 会互相覆盖。
    """
    flat = inner.replace("/", "-")
    return f"{group}-{artifact}-{version}-{flat}"


#: 候选的随附许可文件。AAR 根的 `third_party_licenses.*` 是 ML Kit 那套的固定位置。
def collect_bundled(group: str, artifact: str, version: str, apache: bytes, verbose: bool):
    """返回 (要写入的 {文件名: 内容}, 跳过的说明列表)。"""
    out: dict[str, bytes] = {}
    skipped: list[str] = []

    for suffix in (".aar", ".jar"):
        for f in find_files(group, artifact, version, suffix):
            try:
                z = zipfile.ZipFile(f)
            except Exception:
                continue
            entries = list(z.namelist())

            # (a) AAR 根的 third_party_licenses.json / .txt —— 组件专属，必须保留
            for n in entries:
                base = n.rsplit("/", 1)[-1]
                # 只要 AAR/JAR **根**下的那一份（`n.count("/") == 0`）；
                # 嵌在子目录里的同名文件属于别的分发形态，不重复抄。
                if THIRD_PARTY_RE.match(base) and n.count("/") == 0:
                    out[_bundled_name(group, artifact, version, base)] = z.read(n)

            # (b) META-INF 下的 LICENSE / NOTICE
            for n in entries:
                if n.endswith("/") or not META_LICENSE_RE.match(n):
                    continue
                try:
                    data = z.read(n)
                except Exception:
                    continue
                if SKIP_IF_SAME_AS_APACHE and _same_as_apache(data, apache):
                    skipped.append(f"{n}（与 Apache-2.0.txt 同文，已由通用文本覆盖）")
                    continue
                out[_bundled_name(group, artifact, version, n)] = data

            # (c) AAR 里的 classes.jar 也可能带 META-INF/LICENSE
            if suffix == ".aar" and "classes.jar" in entries:
                try:
                    import io

                    z2 = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
                    for n in z2.namelist():
                        if not META_LICENSE_RE.match(n):
                            continue
                        data = z2.read(n)
                        if SKIP_IF_SAME_AS_APACHE and _same_as_apache(data, apache):
                            skipped.append(f"classes.jar!{n}（同上）")
                            continue
                        out[_bundled_name(group, artifact, version, "classes-" + n)] = data
                except Exception:
                    pass

            # (d) 【BouncyCastle 特例】许可文本被编译进 `**/LICENSE.class`，jar 里没有纯文本。
            #     它的 <clinit> 就是「按顺序 ldc 每一行 + append」，所以可以从常量池 + 字节码
            #     顺序**完整还原原文**（见 extract_license_from_class 的注释与自检）。
            for n in entries:
                if n.endswith("/LICENSE.class"):
                    text = extract_license_from_class(z.read(n))
                    if text:
                        out[_bundled_name(group, artifact, version,
                                          n[:-len(".class")] + "-extracted.txt")] = \
                            text.encode("utf-8")
                        if verbose:
                            log(f"    · 从 {n} 还原许可原文 {len(text)} 字符")

            if verbose and out:
                log(f"    扫描 {f.name}: 命中 {len(out)} 个随附文件")
    return out, skipped


# ------------------------------------------------- 3b. 从 .class 常量池还原许可文本


def extract_license_from_class(data: bytes) -> str:
    """从 `LICENSE.class` 里**忠实还原**许可全文（不臆造、不联网）。

    适用的形态（BouncyCastle 就是这种）：许可被存成一个类，静态初始化块里
    `new StringBuilder` 后**顺序** `ldc "第 N 行"` + `append`，最后 `println`。

    做法：
      1. 解析 class 常量池，取出全部 CONSTANT_String；
      2. 找 `<clinit>` 的 `Code` 属性，按字节码顺序收集 `ldc` / `ldc_w` 引用的 String 下标；
      3. 按下标顺序拼回文本。
    这样得到的是**制品里真实存在的那份文本**，不是我抄的。

    【失败就返回空串】调用方会把"没有随附文本"如实报告出来，绝不伪造。
    """
    try:
        if data[:4] != b"\xca\xfe\xba\xbe":
            return ""
        i = 8
        (n, i) = struct.unpack_from(">H", data, i)[0], i + 2
        cp: dict[int, tuple] = {}
        k = 1
        while k < n:
            tag = data[i]
            i += 1
            if tag == 1:  # Utf8
                ln = struct.unpack_from(">H", data, i)[0]
                i += 2
                cp[k] = ("utf8", data[i:i + ln].decode("utf-8", "replace"))
                i += ln
            elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
                cp[k] = ("ref", struct.unpack_from(">H", data, i)[0])
                i += 2
            elif tag == 15:  # MethodHandle
                cp[k] = ("mh",)
                i += 3
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Int/Float/Field/Method/IMethod/NameType/Dyn/Indy
                cp[k] = ("u4",)
                i += 4
            elif tag in (5, 6):  # Long/Double 占两格
                cp[k] = ("wide",)
                i += 8
                k += 1
            else:
                return ""
            k += 1

        i += 2  # access_flags
        i += 2  # this_class
        i += 2  # super_class
        ic = struct.unpack_from(">H", data, i)[0]
        i += 2 + 2 * ic

        def skip_attributes(pos: int) -> int:
            ac = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            for _ in range(ac):
                pos += 2
                ln = struct.unpack_from(">I", data, pos)[0]
                pos += 4 + ln
            return pos

        fc = struct.unpack_from(">H", data, i)[0]
        i += 2
        for _ in range(fc):
            i += 6
            i = skip_attributes(i)

        mc = struct.unpack_from(">H", data, i)[0]
        i += 2
        code: bytes | None = None
        for _ in range(mc):
            i += 2  # access
            name_idx = struct.unpack_from(">H", data, i)[0]
            i += 2
            i += 2  # descriptor
            ac = struct.unpack_from(">H", data, i)[0]
            i += 2
            for _ in range(ac):
                an = struct.unpack_from(">H", data, i)[0]
                i += 2
                ln = struct.unpack_from(">I", data, i)[0]
                i += 4
                if cp.get(an, ("", ""))[1] == "Code" and cp.get(name_idx, ("", ""))[1] == "<clinit>":
                    code_len = struct.unpack_from(">I", data, i + 4)[0]
                    code = data[i + 8:i + 8 + code_len]
                i += ln
            if code is not None:
                break

        if not code:
            return ""
        order: list[int] = []
        p = 0
        while p < len(code):
            op = code[p]
            if op == 0x12:  # ldc
                order.append(code[p + 1])
                p += 2
            elif op == 0x13:  # ldc_w
                order.append(struct.unpack_from(">H", code, p + 1)[0])
                p += 3
            else:
                p += 1
        lines = []
        for idx in order:
            entry = cp.get(idx)
            if entry and entry[0] == "ref":
                s = cp.get(entry[1])
                if s and s[0] == "utf8":
                    lines.append(s[1])
        text = "\n".join(lines).strip()
        # 【自检】只接受"像许可文本"的结果：够长、且含关键措辞。
        # 避免把别的类（例如某个含大量字符串的常量类）误当许可抄出来。
        if len(text) < 200 or "copyright" not in text.lower():
            return ""
        return text + "\n"
    except Exception:
        return ""


# ---------------------------------------------------------------- 4. 渲染


def render_table(rows: list[dict], bundled_map: dict[str, list[str]]) -> str:
    """渲染「实际发行构建依赖」这一节（标题 + 说明 + 表格）。"""
    lines: list[str] = []
    lines.append(GENERATED_HEADING)
    lines.append("")
    lines.append(
        "> ⚙️ **本节由 `tools/gen_notices.py` 自动生成，请勿手工编辑。**"
        " 数据源是本次 release 构建产出的 `app/build/outputs/sdk-dependencies/release/sdkDependencies.txt`"
        "（AGP 自动生成，含**全部解析到的传递依赖**）；许可字段取自各组件 POM 的 `<licenses>` 段。"
    )
    lines.append("> ")
    lines.append(
        "> 口径说明：列入本表的是**解析到的依赖**，不代表 R8 后每个模块的字节码都保留；"
        "这比\"实际进包的类\"更保守（宁多不少）。POM 缺 `<licenses>` 或缓存缺 POM 的条目会**明说原因**，"
        "不臆测为 Apache-2.0。"
    )
    lines.append("> ")
    lines.append(
        "> 修改方式：先跑 `python3 tools/gen_notices.py` 看差异，确认后 `--write`；"
        "CI 用 `--check`，不一致即失败。顶部「代码与图标」一节是手写的（Lucide / Shizuku / lamda "
        "不是 maven 制品），脚本不会碰它。"
    )
    lines.append("")
    lines.append("| 组件 | POM 许可/条款 | 内附版权文件 |")
    lines.append("| --- | --- | --- |")
    for r in rows:
        coord = f"`{r['group']}:{r['artifact']}:{r['version']}`"
        lic = r["license"] or f"**{r['reason']}**"
        files = bundled_map.get(r["key"], [])
        if files:
            cell = "、".join(f"[{Path(f).name}](licenses/bundled/{f})" for f in files)
        else:
            cell = "未附独立文件"
        lines.append(f"| {coord} | {lic} | {cell} |")
    lines.append("")
    return "\n".join(lines)


def split_head(existing: str) -> str | None:
    """取出手写段（`## 实际发行构建依赖` 之前的一切）。找不到返回 None。"""
    idx = existing.find(GENERATED_HEADING)
    if idx < 0:
        return None
    return existing[:idx]


# ---------------------------------------------------------------- 5. 主流程


def build(verbose: bool = False) -> tuple[str, dict, list[str]]:
    """返回 (完整的新文件内容, 统计信息, 告警)。"""
    if not SDK_DEPS.is_file():
        _fail(
            "找不到依赖元数据：\n  " + str(SDK_DEPS) + "\n\n"
            "它是 AGP 在 `:app:assembleRelease` 时自动产出的。请二选一：\n"
            "  · 跑一次 release 构建（本项目编译走 GitHub CI，从 CI artifact 取回）；\n"
            "  · 或在本机执行 `./gradlew :app:assembleRelease`。\n"
            "本脚本不会用 build.gradle 的声明去「凑」这张表 —— 那正是旧表失真的原因。"
        )

    coords = read_sdk_deps(SDK_DEPS)
    if not coords:
        _fail(f"依赖元数据为空或格式变了：{SDK_DEPS}")

    apache = read_apache_text()
    rows: list[dict] = []
    bundled_map: dict[str, list[str]] = {}
    all_bundled: dict[str, bytes] = {}
    warnings: list[str] = []
    unknown = 0

    for g, a, v in coords:
        key = f"{g}:{a}:{v}"
        lic, basis = read_pom_license(g, a, v)
        if not lic:
            unknown += 1
        rows.append({
            "key": key, "group": g, "artifact": a, "version": v,
            "license": lic, "basis": basis, "reason": basis if not lic else "",
        })
        files, skipped = collect_bundled(g, a, v, apache, verbose)
        if files:
            bundled_map[key] = sorted(files.keys())
            all_bundled.update(files)
        if verbose and skipped:
            for s in skipped:
                log(f"    跳过 {s}")

    # 与现有文件比对头部
    if not NOTICES.is_file():
        _fail(f"找不到 {NOTICES}，本脚本只负责重写它的生成节。")
    existing = NOTICES.read_text(encoding="utf-8")
    head = split_head(existing)
    if head is None:
        _fail(
            f"`{NOTICES}` 里找不到分界标题 `{GENERATED_HEADING}`。\n"
            "本脚本只重写该标题之后的部分；请先确认文件结构，或手工补上这一行标题。"
        )

    new_text = head + render_table(rows, bundled_map)

    stats = {
        "coords": len(coords),
        "unknown": unknown,
        "bundled_files": len(all_bundled),
        "rows": len(rows),
    }
    return new_text, {"rows": rows, "bundled": all_bundled, "stats": stats}, warnings


def diff_rows(existing: str, new_text: str) -> tuple[list[str], list[str], list[str]]:
    """粗粒度比对：多了哪些坐标、少了哪些、许可字段变了哪些。"""
    def parse(t: str) -> dict[str, str]:
        d: dict[str, str] = {}
        for line in t.splitlines():
            m = re.match(r"\|\s*`([^`]+)`\s*\|\s*([^|]*)\|", line)
            if m:
                d[m.group(1).strip()] = m.group(2).strip()
        return d

    old, new = parse(existing), parse(new_text)
    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    changed = sorted(k for k in set(old) & set(new) if old[k] != new[k])
    return added, removed, changed


def main() -> int:
    ap = argparse.ArgumentParser(
        description="从 release 依赖元数据生成 THIRD_PARTY_NOTICES.md 与 licenses/bundled/",
    )
    ap.add_argument("--write", action="store_true", help="写盘（默认只报告差异）")
    ap.add_argument("--check", action="store_true", help="CI 门禁：不一致则退出码 1")
    ap.add_argument("--prune", action="store_true", help="删除不再需要的 bundled 文件（需配 --write）")
    ap.add_argument("--verbose", action="store_true", help="打印逐个制品的处置明细")
    args = ap.parse_args()

    if args.write and args.check:
        log("--write 与 --check 互斥：--check 不写盘。")
        return 2

    if not GRADLE_CACHE.is_dir():
        log(f"⚠️  Gradle 缓存不存在：{GRADLE_CACHE}")
        log("    许可字段会全部落到「缓存无 POM」，bundled/ 也会是空的。")

    new_text, data, _warnings = build(args.verbose)
    rows, bundled, stats = data["rows"], data["bundled"], data["stats"]
    existing = NOTICES.read_text(encoding="utf-8")

    log("=" * 78)
    log(f"数据源: {_rel(SDK_DEPS)}  （{SDK_DEPS.stat().st_size} 字节）")
    log(f"解析到 {stats['coords']} 个 maven 制品 → 表格 {stats['rows']} 行")
    lic_missing = stats["unknown"]
    log(f"许可字段: {stats['coords'] - lic_missing} 条取自 POM/兜底表，{lic_missing} 条需人工确认")
    log(f"随附文件: 需写入 licenses/bundled/ 的 {stats['bundled_files']} 个")
    log("=" * 78)

    added, removed, changed = diff_rows(existing, new_text)
    log(f"与现有 {NOTICES.name} 比对：")
    log(f"  新增 {len(added)} 行；删除 {len(removed)} 行；许可字段变化 {len(changed)} 行")
    for k in added[:12]:
        log(f"    + {k}")
    if len(added) > 12:
        log(f"    … 另有 {len(added) - 12} 条")
    for k in removed[:12]:
        log(f"    - {k}")
    if len(removed) > 12:
        log(f"    … 另有 {len(removed) - 12} 条")
    for k in changed[:8]:
        log(f"    ~ {k}")
    if len(changed) > 8:
        log(f"    … 另有 {len(changed) - 8} 条")

    if lic_missing:
        log("")
        log(f"⚠️  {lic_missing} 条拿不到许可字段（已如实标为「需人工确认」）：")
        for r in rows:
            if not r["license"]:
                log(f"    {r['key']}  ← {r['reason']}")

    same = new_text == existing
    log("")
    log(f"生成结果{'与现有文件一致' if same else '与现有文件不一致'}。")

    if args.check:
        missing_files = [f for f in bundled if not (BUNDLED / f).is_file()]
        if not same or missing_files:
            if missing_files:
                log(f"另有 {len(missing_files)} 个随附文件缺失于 licenses/bundled/：")
                for f in missing_files[:10]:
                    log(f"    {f}")
            log("--check：声明与依赖不一致 → 退出码 1（请跑 --write 后提交）")
            return 1
        log("--check：通过")
        return 0

    if not args.write:
        log("（未写盘。确认无误后加 --write）")
        return 0

    # ---- 写盘 ----
    # 【顺序】先落 bundled/，再写表格。表里会 `[xxx](licenses/bundled/…)` 引用这些文件，
    #   反过来做的话，一旦 bundled 写失败就留下一份"指向不存在文件"的声明表 ——
    #   那比不改更糟（对外声称附了许可文本，实际没有）。
    BUNDLED.mkdir(parents=True, exist_ok=True)
    wrote = 0
    for name, content in sorted(bundled.items()):
        p = BUNDLED / name
        if p.is_file() and p.read_bytes() == content:
            continue
        p.write_bytes(content)
        wrote += 1
    log(f"已写入 {wrote} 个随附文件到 licenses/bundled/（共 {len(bundled)}）")

    NOTICES.write_text(new_text, encoding="utf-8")
    log(f"已更新 {_rel(NOTICES)}")

    if args.prune:
        stale = sorted(p.name for p in BUNDLED.iterdir()
                       if p.is_file() and p.name not in bundled)
        for name in stale:
            (BUNDLED / name).unlink()
        if stale:
            log(f"--prune：删除 {len(stale)} 个不再需要的文件")
            for n in stale[:10]:
                log(f"    - {n}")
    else:
        stale = sorted(p.name for p in BUNDLED.iterdir()
                       if p.is_file() and p.name not in bundled)
        if stale:
            log(f"提示：licenses/bundled/ 里另有 {len(stale)} 个本次未生成的文件（未删除）。")
            log("      确认无引用后可加 --prune 清理。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
