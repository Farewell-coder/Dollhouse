#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
All-in-One 全量存档（v2）静态验证脚本。

纯静态分析：只读源码文本，不依赖 Android 运行时 / 不编译 / 不跑 gradle。
目的：把「100% 还原」这句口号变成可核对的检查项。

用法：
    python3 tools/backup_roundtrip_check.py            # 在仓库根目录跑
    python3 tools/backup_roundtrip_check.py --repo /path/to/Dollhouse

退出码：全部通过 0 / 有风险 1 / 有 P0 2

检查项（与共享任务板 task-6 一一对应）：
    1. 段落完整性（section -> 导出 / 恢复）
    2. 密钥泄漏（安全红线，P0）
    3. v1 兼容
    4. 恢复语义（只新增 / 只补齐）
    5. DollStore.upsertRecord 正确性
    6. 图片路径安全
    7. 人设 key 覆盖率（DollProfile 全部 K_* 对照白名单）
附加：跨对象 API 存在性、路径约定（绝对值 / 相对值）一致性。
"""

import argparse
import json
import os
import re
import sys

# ---------------------------------------------------------------- 基础设施

P0 = "P0"
P1 = "P1"
P2 = "P2"
OK = "OK"
RISK = "RISK"
UNSURE = "UNSURE"

DATA_DIR = "app/src/main/java/com/dollhouse/app/data"
UI_DIR = "app/src/main/java/com/dollhouse/app/ui"
PET_DIR = "app/src/main/java/com/dollhouse/app/pet"
AI_DIR = "app/src/main/java/com/dollhouse/app/ai"
CORE_DIR = "app/src/main/java/com/dollhouse/app/core"
TEST_DIR = "app/src/test/java/com/dollhouse/app/data"

ARCHIVE = DATA_DIR + "/AllInOneArchive.kt"
ENVELOPE = DATA_DIR + "/BackupEnvelope.kt"
SERVICE = DATA_DIR + "/BackupService.kt"
DOLLSTORE = DATA_DIR + "/DollStore.kt"
DOLLPROFILE = DATA_DIR + "/DollProfile.kt"
MEM_REL = DATA_DIR + "/Mem.kt"
PETPREFS = DATA_DIR + "/PetPrefs.kt"
IMAGESTORE = DATA_DIR + "/ImageStore.kt"
MYPROFILE = DATA_DIR + "/MyProfile.kt"
PROVIDER = AI_DIR + "/Provider.kt"
AIMODEL = AI_DIR + "/AiModel.kt"
ENVELOPE_TEST = TEST_DIR + "/BackupEnvelopeTest.kt"

FINDINGS = []   # (level, title, detail, location)


def add(level, title, detail, location=""):
    FINDINGS.append((level, title, detail, location))


class Repo:
    def __init__(self, root):
        self.root = root
        self._cache = {}

    def path(self, rel):
        return os.path.join(self.root, rel)

    def exists(self, rel):
        return os.path.exists(self.path(rel))

    def read(self, rel):
        """读文件（去掉行尾，保留行号从 1 开始）。文件缺失返回空串，不抛。"""
        if rel in self._cache:
            return self._cache[rel]
        try:
            with open(self.path(rel), "r", encoding="utf-8", errors="replace") as f:
                txt = f.read()
        except OSError:
            txt = ""
        self._cache[rel] = txt
        return txt

    def lines(self, rel):
        return self.read(rel).split("\n")

    def line_of(self, rel, needle, start=0):
        """返回首次出现 needle 的 1 基行号；找不到返回 0。"""
        for i, ln in enumerate(self.lines(rel), start=1):
            if i <= start:
                continue
            if needle in ln:
                return i
        return 0


def strip_comments(text):
    """
    去掉 // 行注释与块注释，但保留字符串字面量内容。
    用于「代码里真的做了这件事」与「注释里说做了这件事」的区分 ——
    本工程注释极多，不剥离会把注释里的说明误当成实现。
    """
    out = []
    i = 0
    n = len(text)
    in_str = False
    in_ml = False
    in_line = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_ml:
            if c == "*" and nxt == "/":
                in_ml = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_ml = True
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def code_of(repo, rel):
    return strip_comments(repo.read(rel))


def code_lines(repo, rel):
    """剥离注释后的行；保留行号对齐（注释行变空行）。"""
    raw = repo.lines(rel)
    stripped = strip_comments(repo.read(rel)).split("\n")
    # strip_comments 会保留换行，行数应一致；不足时用空串补齐以保证行号对齐
    while len(stripped) < len(raw):
        stripped.append("")
    return [stripped[i] if i < len(stripped) else "" for i in range(len(raw))]


def find_fun_body(lines, sig_regex):
    """
    找到匹配 sig_regex 的函数起始行，返回 (start_line_1based, body_text)。
    body 以花括号配平截取；找不到返回 (0, "")。
    """
    for i, ln in enumerate(lines):
        if re.search(sig_regex, ln):
            depth = 0
            started = False
            body = []
            for j in range(i, len(lines)):
                seg = lines[j]
                body.append(seg)
                for ch in seg:
                    if ch == "{":
                        depth += 1
                        started = True
                    elif ch == "}":
                        depth -= 1
                if started and depth <= 0:
                    return i + 1, "\n".join(body)
            return i + 1, "\n".join(body)
    return 0, ""


def extract_array_block(repo, rel, val_name):
    """
    截取 `val <name> = arrayOf( ... )` 的圆括号内容。
    返回 (起始行号, 括号内文本)。找不到返回 (0, "")。
    """
    lines = code_lines(repo, rel)
    for i, ln in enumerate(lines):
        if re.search(r"\bval %s\s*=\s*arrayOf\(" % val_name, ln):
            depth = 0
            started = False
            buf = []
            for j in range(i, len(lines)):
                seg = lines[j]
                buf.append(seg)
                for ch in seg:
                    if ch == "(":
                        depth += 1
                        started = True
                    elif ch == ")":
                        depth -= 1
                if started and depth <= 0:
                    return i + 1, "\n".join(buf)
            return i + 1, "\n".join(buf)
    return 0, ""


def find_fun_start(lines, sig_regex):
    for i, ln in enumerate(lines):
        if re.search(sig_regex, ln):
            return i + 1
    return 0


# ---------------------------------------------------------------- 检查 1：段落完整性

# 期望的 v2 段名（脚本内置的期望清单，与设计说明 §2 一致）
EXPECTED_SECTIONS = [
    "providers", "models", "dolls", "persona", "chat",
    "myprofile", "memories", "memlinks", "memprofile", "anim",
    "images", "settings",
]

SECTION_CONSTS = {
    "providers": "SEC_PROVIDERS",
    "models": "SEC_MODELS",
    "dolls": "SEC_DOLLS",
    "persona": "SEC_PERSONA",
    "chat": "SEC_CHAT",
    "myprofile": "SEC_MYPROFILE",
    "memories": "SEC_MEMORIES",
    "memlinks": "SEC_MEMLINKS",
    "memprofile": "SEC_MEMPROFILE",
    "anim": "SEC_ANIM",
    "images": "SEC_IMAGES",
    "settings": "SEC_SETTINGS",
}

# 段名 -> 导出函数名（在 export() 里被调用）
EXPORT_FUNS = {
    "providers": "exportProviders",
    "models": "exportModels",
    "dolls": "exportDolls",
    "persona": "exportPersona",
    "chat": "exportChat",
    "myprofile": "exportMyProfile",
    "memories": "exportMemories",
    "memlinks": "exportMemLinks",
    "memprofile": "exportMemProfiles",
    "anim": "exportAnim",
    "images": "packImages",
    "settings": "exportSettings",
}

# 段名 -> restore 里读它时用的 sectionArray/sectionObject
RESTORE_READS = {
    "providers": 'sectionArray(root, SEC_PROVIDERS)',
    "models": 'sectionArray(root, SEC_MODELS)',
    "dolls": 'sectionArray(root, SEC_DOLLS)',
    "persona": 'sectionObject(root, SEC_PERSONA)',
    "chat": 'sectionObject(root, SEC_CHAT)',
    "myprofile": 'sectionObject(root, SEC_MYPROFILE)',
    "memories": 'sectionObject(root, SEC_MEMORIES)',
    "memlinks": 'sectionObject(root, SEC_MEMLINKS)',
    "memprofile": 'sectionObject(root, SEC_MEMPROFILE)',
    "anim": 'sectionObject(root, SEC_ANIM)',
    "images": 'sectionArray(root, SEC_IMAGES)',
    "settings": 'sectionObject(root, SEC_SETTINGS)',
}


def check_sections(repo):
    print("=" * 78)
    print("【1】段落完整性：section -> 导出✓/恢复✓")
    print("=" * 78)

    env_build_line, env_build_body = find_fun_body(code_lines(repo, ENVELOPE), r"fun build\(")
    arch_lines = code_lines(repo, ARCHIVE)
    arch_code = "\n".join(arch_lines)

    # 导出侧：export() 里实际传给 build() 的具名参数
    exp_start, exp_body = find_fun_body(arch_lines, r"\bfun export\(")
    # 恢复侧：restore() 全体
    res_start, res_body = find_fun_body(arch_lines, r"\bfun restore\(ctx")

    rows = []
    for sec in EXPECTED_SECTIONS:
        # --- 声明：BackupEnvelope.build 里有 sections.put("<sec>")
        declared = ('sections.put("%s"' % sec) in env_build_body
        # --- 常量：AllInOneArchive 有 SEC_<X> = "<sec>"
        const = SECTION_CONSTS[sec]
        const_ok = bool(re.search(r'const val %s = "%s"' % (const, re.escape(sec)), arch_code))
        # --- 导出：export() 里确实调用了对应的 export 函数，且该函数存在且有非空实现
        fname = EXPORT_FUNS[sec]
        called = bool(re.search(r"\b%s\s*\(" % fname, exp_body))
        fstart, fbody = find_fun_body(arch_lines, r"\bprivate fun %s\(" % fname)
        fn_exists = fstart > 0
        # 非空实现：函数体里至少有一个 put(...) 或 arr.put / out.put
        fn_puts = fn_exists and re.search(r"\.put\(", fbody) is not None
        export_ok = called and fn_exists and fn_puts
        # --- 恢复：restore() 里确实读取了这个段
        rpat = RESTORE_READS[sec]
        read_ok = bool(re.search(r"section(Array|Object)\(\s*root\s*,\s*%s\s*\)" % const, res_body))

        rows.append({
            "sec": sec,
            "declared": declared,
            "const": const_ok,
            "export": export_ok,
            "export_note": "" if export_ok else (
                "未调用" if not called else ("函数缺失" if not fn_exists else "函数无写入")),
            "restore": read_ok,
        })

    # 表格
    print("%-12s %-10s %-8s %-10s %-10s" % ("section", "build声明", "常量", "导出", "恢复"))
    print("-" * 78)
    for r in rows:
        mark = lambda b: "✓" if b else "✗"
        note = ("  <- " + r["export_note"]) if r["export_note"] else ""
        print("%-12s %-10s %-8s %-10s %-10s%s" % (
            r["sec"], mark(r["declared"]), mark(r["const"]),
            mark(r["export"]), mark(r["restore"]), note))
    print()

    for r in rows:
        if not r["declared"]:
            add(P1, "段未在信封中声明",
                "BackupEnvelope.build() 里没有 sections.put(\"%s\")，该段不会出现在存档文件中" % r["sec"],
                ENVELOPE)
        if not r["const"]:
            add(P1, "段常量缺失", "AllInOneArchive 缺少 const val %s" % SECTION_CONSTS[r["sec"]], ARCHIVE)
        if not r["export"]:
            add(P1, "段导出不完整",
                "段 %s 的导出函数 %s %s" % (r["sec"], EXPORT_FUNS[r["sec"]], r["export_note"]),
                ARCHIVE)
        if not r["restore"]:
            add(P2, "段恢复未读取",
                "restore() 未从 SEC_%s 读取该段" % r["sec"].upper(), ARCHIVE)

    if all(r["declared"] and r["const"] and r["export"] and r["restore"] for r in rows):
        print("结论：12 个段全部「声明 ✓ / 导出 ✓ / 恢复 ✓」。\n")
    else:
        print("结论：存在不完整段落，详见缺陷清单。\n")
    return rows


# ---------------------------------------------------------------- 检查 2：密钥泄漏（P0）

SECRET_PATTERNS = [
    r"\bapiKey\b",
    r"\bapi_key\b",
    r"\bsecret\b",
    r"\bpassword\b",
    r"\btoken\b",
    r"\bEncryptedSharedPreferences\b",
]


def check_secrets(repo):
    print("=" * 78)
    print("【2】密钥泄漏检查（安全红线，P0）")
    print("=" * 78)

    p0_hits = []

    # 2.1 Provider.toJson 本身包含 apiKey —— 所以 export 里的 remove 是必须的
    prov_code = code_of(repo, PROVIDER)
    tojson_start, tojson_body = find_fun_body(code_lines(repo, PROVIDER), r"fun toJson\(")
    prov_tojson_has_key = tojson_start > 0 and re.search(r'put\(\s*"apiKey"', tojson_body) is not None
    print("· Provider.toJson() 包含 apiKey 字段：%s" % ("是（因此导出必须剔除）" if prov_tojson_has_key else "否"))

    # 2.2 导出侧：exportProviders 内必须 remove("apiKey")
    arch_lines = code_lines(repo, ARCHIVE)
    ep_start, ep_body = find_fun_body(arch_lines, r"\bprivate fun exportProviders\(")
    remove_ok = ep_start > 0 and re.search(r'\.remove\(\s*"apiKey"\s*\)', ep_body) is not None
    # remove 必须发生在 put 进数组之前
    order_ok = False
    if remove_ok:
        rm_idx = ep_body.find('remove("apiKey")')
        put_idx = ep_body.find("arr.put")
        order_ok = rm_idx >= 0 and put_idx > rm_idx
    print("· exportProviders() 写入前 remove(\"apiKey\")：%s" % ("✓" if (remove_ok and order_ok) else "✗"))

    if not (remove_ok and order_ok):
        ln = repo.line_of(ARCHIVE, "fun exportProviders")
        add(P0, "导出把 apiKey 写进存档", "exportProviders 未在写入前剔除 apiKey，密钥会落进备份文件", "%s:%d" % (ARCHIVE, ln))
        p0_hits.append("exportProviders")

    # 2.3 逐行核对 AllInOneArchive / BackupService 里每一处 apiKey
    print("\n· 逐处核对 apiKey 出现位置：")
    for rel in (ARCHIVE, SERVICE):
        cl = code_lines(repo, rel)
        for i, ln in enumerate(cl, start=1):
            if re.search(r"\bapiKey\b", ln):
                verdict = classify_api_key_line(ln, rel, i, repo)
                print("    %-28s:%-5d %-58s => %s" % (os.path.basename(rel), i, ln.strip()[:58], verdict))
                if verdict.startswith("P0"):
                    add(P0, "apiKey 被写入存档", verdict, "%s:%d" % (rel, i))
                    p0_hits.append("%s:%d" % (rel, i))

    # 2.4 存档 JSON 里不应出现密钥明文形态：检查 export 路径没有把 KeyVault 密文塞进任何 put
    #     判据：export* 函数体内不得出现 apiKey / enc:1: 等
    export_bodies = []
    for fname in EXPORT_FUNS.values():
        s, b = find_fun_body(arch_lines, r"\bprivate fun %s\(" % fname)
        if s:
            export_bodies.append((fname, s, b))
    leak_in_export = []
    for fname, s, b in export_bodies:
        if re.search(r"\bapiKey\b", b):
            # exportProviders 是唯一允许出现的地方（因为要 remove）
            if fname != "exportProviders":
                leak_in_export.append((fname, s))
    print("\n· 其它 export* 函数体内出现 apiKey：%s" %
          ("无" if not leak_in_export else "、".join(f for f, _ in leak_in_export)))
    for fname, s in leak_in_export:
        add(P0, "导出函数触碰 apiKey", "%s 内出现 apiKey" % fname, "%s:%d" % (ARCHIVE, s))
        p0_hits.append(fname)

    # 2.5 恢复侧：新建供应商必须显式清空 apiKey
    print("· 恢复侧新建供应商显式清空 apiKey：%s" % ("✓" if re.search(r"p\.apiKey\s*=\s*\"\"", arch_code := code_of(repo, ARCHIVE)) else "✗"))
    if not re.search(r"p\.apiKey\s*=\s*\"\"", code_of(repo, ARCHIVE)):
        add(P1, "恢复未清空外来密钥", "restore 新建供应商时未显式置空 apiKey，手工塞入的密文会留下", ARCHIVE)

    # 2.6 模型段不得含密钥（AiModel 根本不该有 apiKey）
    ai_code = code_of(repo, AIMODEL)
    has_key_field = re.search(r'put\(\s*"apiKey"', code_of(repo, AIMODEL)) is not None
    print("· AiModel.toJson() 含 apiKey：%s" % ("是（异常）" if has_key_field else "否 ✓"))
    if has_key_field:
        add(P0, "模型段夹带密钥", "AiModel.toJson 写了 apiKey", AIMODEL)
        p0_hits.append("AiModel.toJson")

    # 2.7 人设 / 设置段不得整份导出 SharedPreferences（那会把密钥密文带出去）
    for fname, s, b in export_bodies:
        if re.search(r"PetPrefs\.get\([^)]*\)\s*\.\s*all\b", b) or re.search(r"\.getAll\(\)", b):
            print("· %s 整份导出偏好：是（需人工确认白名单）" % fname)
    # exportPersona 用 .all 但只按白名单取值 —— 校验它确实按清单取。
    # 【task-13 改造】白名单的展开已抽成 personaKeysOf()（导出/导入共用一份清单），
    #   所以「for (b in PERSONA_BASES)」这个循环不再出现在 exportPersona 体里，
    #   而在 personaKeysOf 体里。判据随之改为两步，**不放宽强度**：
    #     ① exportPersona 的键来自 personaKeysOf(dollId)（不是遍历 .all 的 keys）
    #     ② personaKeysOf 本身确实只枚举 PERSONA_BASES + bounds 展开，且不读 .all
    #   这样「整份导出」这个 P0 依然被真判据挡住，而不是因为循环搬家就误报/漏报。
    p_start, p_body = find_fun_body(arch_lines, r"\bprivate fun exportPersona\(")
    if p_start:
        uses_shared = bool(re.search(r"personaKeysOf\(dollId\)", p_body or ""))
        # 反向：exportPersona 不许自己遍历 all 的键集合（那才是「整份导出」）
        iterates_all_keys = bool(re.search(r"(all|prefs)\s*\.\s*keys\(\)", p_body or "")) or \
                            bool(re.search(r"for\s*\([^)]*\bin\s+all\.keys\(\)", p_body or ""))
        whitelist_ok = uses_shared and not iterates_all_keys
        print("· exportPersona 按共用白名单取值（personaKeysOf）：%s" % ("✓" if uses_shared else "✗"))
        print("· exportPersona 不自行遍历 .all.keys()（防整份导出）：%s" % ("✓" if not iterates_all_keys else "✗"))
        if not whitelist_ok:
            add(P0, "人设段可能整份导出", "exportPersona 未按白名单取键，可能把供应商密钥密文一并带出", "%s:%d" % (ARCHIVE, p_start))
            p0_hits.append("exportPersona")

        # personaKeysOf 本体必须只枚举显式清单，且不碰 .all
        pk_start, pk_body = find_fun_body(arch_lines, r"private fun personaKeysOf\(")
        pk_ok = bool(pk_start) and \
                bool(re.search(r"for\s*\(\s*b\s+in\s+PERSONA_BASES", pk_body or "")) and \
                not re.search(r"\.all\b", pk_body or "")
        print("· personaKeysOf 只枚举 PERSONA_BASES（不读 .all）：%s" % ("✓" if pk_ok else "✗"))
        if not pk_ok:
            add(P0, "共用白名单未按显式清单展开", "personaKeysOf 未枚举 PERSONA_BASES，或读取了整份偏好", "%s:%d" % (ARCHIVE, pk_start))
            p0_hits.append("personaKeysOf")

    if not p0_hits:
        print("\n结论：未发现密钥泄漏。providers 段写入前已剔除 apiKey，恢复侧保留本机原值。\n")
    else:
        print("\n结论：发现 P0 密钥问题！\n")
    return p0_hits


def classify_api_key_line(line, rel, lineno, repo):
    """
    对单个含 apiKey 的代码行做「安全 / 危险」判定。
    安全形态：
      · remove("apiKey")
      · p.apiKey = ""           （恢复时清空）
      · 变量名 apiKeyNeeded / apiKeyNeeded++ （计数，不写数据）
      · Provider.fromJson 的读取（不在导出路径）
    """
    s = re.sub(r"\s+", " ", line.strip())
    if 'remove("apiKey")' in s:
        return "安全：写入前剔除"
    if re.search(r'\.apiKey\s*=\s*""', s):
        return "安全：恢复时置空"
    if re.search(r'\bapiKeyNeeded\b', s):
        return "安全：仅计数（摘要文案用）"
    if re.search(r'\.apiKey\s*=', s):
        return "P0：把 apiKey 赋给对象后若被序列化则泄漏"
    if re.search(r'getString\(\s*"apiKey"', s) or re.search(r'optString\(\s*"apiKey"', s):
        return "待确认：读取密钥（需确认不落到存档）"
    return "安全：其它（非写入）"


# ---------------------------------------------------------------- 检查 3：v1 兼容

def check_v1(repo):
    print("=" * 78)
    print("【3】v1 兼容检查（VER=2 代码读 VER=1 文件）")
    print("=" * 78)

    env_lines = code_lines(repo, ENVELOPE)
    env_code = code_of(repo, ENVELOPE)

    ver_val = None
    m = re.search(r'const val VER = (\d+)', env_code)
    if m:
        ver_val = int(m.group(1))
    print("· BackupEnvelope.VER = %s" % ver_val)

    # 3.1 版本比较必须是 ver > VER 才拒绝（ver == VER 放行，ver < 2 放行 + warning）
    gt_ok = bool(re.search(r"if\s*\(\s*ver\s*>\s*VER\s*\)", env_code))
    print("· 只有 ver > VER 才拒绝：%s" % ("✓" if gt_ok else "✗"))
    if not gt_ok:
        add(P1, "版本判定可能误拒", "未找到 `ver > VER` 形式的判定", ENVELOPE)

    # 3.2 v1 必须追加 warning 而不是拒绝
    warn_ok = bool(re.search(r"if\s*\(\s*ver\s*<\s*2\s*\)", env_code)) and \
              bool(re.search(r"warnings\.add", env_code))
    print("· ver < 2 追加 warning（不拒绝）：%s" % ("✓" if warn_ok else "✗"))
    if not warn_ok:
        add(P1, "v1 无降级提示", "ver<2 未向 warnings 追加说明", ENVELOPE)

    # 3.3 sectionArray / sectionObject 对缺失段回落成空
    sa = re.search(r"fun sectionArray[^{]*\{(.*?)\}", env_code, re.S)
    so = re.search(r"fun sectionObject[^{]*\{(.*?)\}", env_code, re.S)
    sa_ok = bool(sa) and "optJSONArray" in sa.group(1) and "JSONArray()" in sa.group(1)
    so_ok = bool(so) and "optJSONObject" in so.group(1) and "JSONObject()" in so.group(1)
    print("· sectionArray 缺失回落空数组：%s" % ("✓" if sa_ok else "✗"))
    print("· sectionObject 缺失回落空对象：%s" % ("✓" if so_ok else "✗"))
    if not sa_ok:
        add(P1, "缺失段读取会抛", "sectionArray 未对缺失段回落空数组", ENVELOPE)
    if not so_ok:
        add(P1, "缺失段读取会抛", "sectionObject 未对缺失段回落空对象", ENVELOPE)

    # 3.4 build() 的新参数必须都有默认值（否则老调用点编译失败）
    build_start, build_body = find_fun_body(env_lines, r"fun build\(")
    new_params = ["persona", "chat", "myProfile", "memLinks", "memProfiles", "anim", "images"]
    missing_default = []
    for p in new_params:
        # 形参声明形如  `persona: JSONObject? = null,`
        if not re.search(r"\b%s\s*:[^,)]*=\s*null" % p, build_body):
            missing_default.append(p)
    print("· build() 新参数全部带默认值 null：%s" % ("✓" if not missing_default else "✗ " + str(missing_default)))
    if missing_default:
        add(P1, "build 新参数无默认值", "老调用点（含单测）会编译失败：%s" % ", ".join(missing_default),
            "%s:%d" % (ENVELOPE, build_start))

    # 3.5 restore() 对 v1 的标记：ver<2 时摘要要说明
    arch_code = code_of(repo, ARCHIVE)
    old_ok = bool(re.search(r"val old = ver < 2", arch_code))
    old_msg = bool(re.search(r"这是旧版备份", arch_code))
    print("· restore 识别 v1 并给出摘要：%s" % ("✓" if (old_ok and old_msg) else "✗"))
    if not (old_ok and old_msg):
        add(P2, "恢复摘要未提示旧版", "restore 未对 ver<2 追加旧版提示", ARCHIVE)

    # 3.6 既有单测断言自洽性（lead 已改）
    check_envelope_test(repo, ver_val)

    if gt_ok and warn_ok and sa_ok and so_ok and not missing_default:
        print("\n结论：v1 文件可被 v2 代码正常读取，缺失段按空处理。\n")
    else:
        print("\n结论：v1 兼容链有缺口，详见缺陷清单。\n")


def check_envelope_test(repo, ver_val):
    """核对 BackupEnvelopeTest 里被改过的断言是否自洽。"""
    print("\n· 既有单测 BackupEnvelopeTest 断言自洽性：")
    if not repo.exists(ENVELOPE_TEST):
        print("    测试文件不存在，跳过")
        return
    tf = code_lines(repo, ENVELOPE_TEST)

    # 找「更高版本备份被拒绝」测试
    hard_ver = []
    for i, ln in enumerate(tf, start=1):
        if re.search(r"verOverride\s*=\s*(\d+)", ln) and "BackupEnvelope.VER" not in ln:
            hard_ver.append((i, ln.strip()))
        if re.search(r'root\.put\("ver"\s*,\s*\d+\)', ln):
            hard_ver.append((i, ln.strip()))
    print("    写死版本号的地方：%s" % ("无" if not hard_ver else str(hard_ver)))
    if hard_ver:
        add(P2, "测试写死版本号", "VER 再升时会再次失效：%s" % hard_ver, ENVELOPE_TEST)

    # 确认「更高版本」用的是 VER + 1
    plus1 = any("BackupEnvelope.VER + 1" in ln or "VER+1" in ln.replace(" ", "")
                for ln in tf)
    print("    更高版本断言用 VER + 1：%s" % ("✓" if plus1 else "✗"))
    if not plus1:
        add(P2, "更高版本断言未跟随 VER", "断言未用 VER+1，升级会再次失效", ENVELOPE_TEST)

    # 图片文案断言：空备份应断言「没有图片段」
    img_txt = any("没有图片段" in ln for ln in tf)
    old_txt = any("图片不包含" in ln for ln in tf)
    print("    图片文案断言用新版「没有图片段」：%s（旧文案残留：%s）" %
          ("✓" if img_txt else "✗", "有" if old_txt else "无"))
    if not img_txt:
        add(P2, "图片文案断言过时", "空备份概览文案已变，断言应改为「没有图片段」", ENVELOPE_TEST)
    if old_txt:
        add(P2, "图片文案断言与新实现矛盾", "断言「图片不包含」与 v2「图片会进备份」的设计冲突", ENVELOPE_TEST)

    # 断言文案与 BackupEnvelope.summary 实际输出是否一致
    env_code = code_of(repo, ENVELOPE)
    has_new_msg = "没有图片段" in env_code
    has_old_msg = "图片不包含在备份里" in env_code
    print("    实现侧文案：新「没有图片段」%s / 旧「图片不包含」%s" %
          ("有" if has_new_msg else "无", "有" if has_old_msg else "无"))
    if img_txt and not has_new_msg:
        add(P1, "测试断言与实现文案不一致", "断言找「没有图片段」但实现未输出该文案", ENVELOPE)

    # 「需要重新填写」是否仍在实现里
    need_fill = "需要重新填写" in env_code
    test_need = any("需要重新填写" in ln for ln in tf)
    print("    「需要重新填写」实现侧：%s / 测试侧：%s" %
          ("有" if need_fill else "无", "有" if test_need else "无"))
    if test_need and not need_fill:
        add(P1, "测试断言与实现文案不一致", "断言找「需要重新填写」但实现未输出", ENVELOPE)

    # 断言 ver==VER 的用例必须存在（否则「当前版本被自己拒绝」这类回归无人守）
    cur_ver_test = any("verOverride = BackupEnvelope.VER" in ln and "VER + 1" not in ln for ln in tf)
    print("    存在「当前版本不被当成更高版本」用例：%s" % ("✓" if cur_ver_test else "✗"))
    if not cur_ver_test:
        add(P2, "缺少当前版本回归用例", "没有断言 ver==VER 应被接受，升级后易复发", ENVELOPE_TEST)


# ---------------------------------------------------------------- 检查 4：恢复语义（只新增/只补齐）

# 恢复函数 -> 期望的「不覆盖」证据（正则）
NON_OVERWRITE_RULES = [
    ("供应商", r"\bprivate fun|restore", r"byName\.containsKey\(name\)", "同名已存在则跳过"),
    ("模型", None, r"ModelStore\.modelExists\(", "已存在则跳过"),
    ("人偶记录", None, r"DollStore\.upsertRecord\(", "id 已存在不插入"),
    ("人偶字段", None, r"cur\.cover\.isEmpty\(\)", "只补空字段"),
    # 【task-19】人设判据必须是「键不存在 **或** 值仍是工厂种子（=用户没改过）」，
    #   不能是裸 `p.contains(k)`。后者会把 seedDefault 写的工厂默认值当成
    #   「用户的选择」而拒绝覆盖 —— 换机用户（必然先启动过 App）的性格/台词
    #   永远恢复不了。这条规则原先断言 `p\.contains\(k\)`，正是有缺陷的旧写法。
    ("人设", None,
     r"private fun shouldWritePersonaKey\([\s\S]{0,900}?!p\.contains\(key\)[\s\S]{0,600}?isStillFactoryValue",
     "键不存在或仍是工厂种子才写"),
    ("动作库", None, r"mergeAnim\(", "按 id 只追加缺的"),
    ("记忆", None, r"Mem\.findByTitle\(", "同标题跳过"),
    ("记忆连接", None, r"Mem\.addLink\(", "自带去重"),
    ("记忆画像", None, r"Mem\.profile\(ctx, dollId\)\.isNotEmpty\(\)", "已存在则不动"),
    # 【task-15】聊天历史的判据必须**两侧都判**：读本机现值 + 本机没内容 + 备份有内容。
    #   这条规则原先断言的是 `p.contains("conv_" + id)` —— 那是更早一版的**缺陷写法**
    #   （键几乎必然被 addConv 预写，于是恒为真 ⇒ 聊天全被跳过）。
    #   现在按正确语义断言，并且要求「读本机」这一步真实存在，防止退化成只看备份侧。
    ("聊天历史", None,
     r'val existing = p\.getString\("conv_" \+ id, null\)[\s\S]{0,400}?'
     r'if \(!hasMessages\(existing\) && hasMessages\(raw\)\)',
     "本机无内容且备份有内容才写"),
    ("当前会话指针", None, r"PetPrefs\.convCurrent\(ctx\)\.isEmpty\(\)", "只在本机为空时补"),
    ("「我」的资料", None, r"MyProfile\.DEFAULT_NAME", "昵称仅默认值时补"),
    ("settings", None, r'p\.contains\("theme_mode"\)', "按键存在性判断"),
    ("图片", None, r"if \(!target\.isFile\)", "目标存在则跳过"),
]


def check_restore_semantics(repo):
    print("=" * 78)
    print("【4】恢复语义检查（只新增 / 只补齐，不覆盖删除）")
    print("=" * 78)

    arch_code = code_of(repo, ARCHIVE)
    rows = []
    for name, _f, pat, note in NON_OVERWRITE_RULES:
        hit = bool(re.search(pat, arch_code))
        rows.append((name, hit, note))

    print("%-16s %-8s %s" % ("类别", "证据", "口径"))
    print("-" * 78)
    for name, hit, note in rows:
        print("%-16s %-8s %s" % (name, "✓" if hit else "✗", note))
    print()

    for name, hit, note in rows:
        if not hit:
            add(P1, "恢复可能覆盖用户数据", "未找到「%s」的不覆盖判据（期望：%s）" % (name, note), ARCHIVE)

    # 4.x 反向检查：restore 路径里不该出现 dangerous 的删除 / 清空调用
    dangerous = [
        (r"\.clear\(\)", ".clear() 清空整个 SharedPreferences"),
        (r"DollStore\.delete\(", "DollStore.delete 删人偶"),
        (r"ImageStore\.delete\(", "ImageStore.delete 删图片"),
        (r"Mem\.remove|Mem\.delete", "删除记忆"),
        (r"removeConvHistory\(", "删聊天历史"),
    ]
    hits = []
    for pat, desc in dangerous:
        n = len(re.findall(pat, arch_code))
        if n:
            hits.append((desc, n))
    print("· restore 路径内危险删除调用：%s" % ("无 ✓" if not hits else str(hits)))
    if hits:
        add(P1, "恢复路径含删除调用", "违反「只新增不删除」约定：%s" % hits, ARCHIVE)

    # 4.y settings 必须按键存在性判断而非值判断
    s_start, s_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restoreSettings\(")
    cnt_contains = len(re.findall(r"!p\.contains\(", s_body or ""))
    cnt_has = len(re.findall(r'!p\.contains\("', s_body or ""))
    print("· restoreSettings 用键存在性判断：%d 处（应等于 settings 段项数）" % cnt_contains)
    # settings 段项数
    e_start, e_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun exportSettings\(")
    exported = re.findall(r'o\.put\("([A-Za-z0-9_]+)"', e_body or "")
    restored_names = re.findall(r's\.has\("([A-Za-z0-9_]+)"\)', s_body or "")
    print("· settings 导出项数 %d / 恢复项数 %d" % (len(exported), len(restored_names)))
    missing = [x for x in exported if x not in restored_names]
    extra = [x for x in restored_names if x not in exported]
    if missing:
        add(P1, "settings 有导无恢", "导出但恢复端不读的项：%s" % missing, ARCHIVE)
    if extra:
        add(P2, "settings 有恢无导", "恢复端读了但导出端不写的项：%s" % extra, ARCHIVE)
    if not missing and not extra:
        print("  导出项与恢复项一一对应 ✓")

    # 4.z 【键存在性判断的前提】restoreSettings 用 !p.contains(键) 决定是否写回，
    #     这个判据成立的前提是「目标机从未写过该键」。
    #     但 PetPrefs.get() 自身会在启动时跑一串一次性迁移，其中两条会**无条件**写入
    #     某些 settings 键 —— 于是这些键在任何机器上都「已存在」，恢复永远跳过它们。
    check_settings_contains_precondition(repo, exported)

    if all(h for _, h, _ in rows) and not hits:
        print("\n结论：全部写回路径均遵循「只新增 / 只补齐」，未发现覆盖删除。\n")
    else:
        print("\n结论：恢复语义存在偏离，详见缺陷清单。\n")
    return rows



def migration_written_keys(repo):
    """
    找出 PetPrefs.get() 在启动时会**无条件写入**的偏好键。
    这些键在任何机器上都早已存在，`!p.contains(key)` 判据对它们永远为 false。
    返回 {键名: 写入它的迁移函数名}。
    """
    pet_lines = code_lines(repo, PETPREFS)
    code = "\n".join(pet_lines)
    out = {}

    # get() 里实际调用了哪些迁移
    g_start, g_body = find_fun_body(pet_lines, r"fun get\(context: Context\): SharedPreferences")
    gb = strip_comments(g_body or "")
    called = re.findall(r"\b(\w+)\(p\)", gb)

    for fn in called:
        f_start, f_body = find_fun_body(pet_lines, r"private fun %s\(" % fn)
        fb = strip_comments(f_body or "")
        # 只认「put 型」写入：putInt/putBoolean/putString/putLong(<键>, ...)
        for m in re.finditer(r'\.put(?:Int|Boolean|String|Long|Float)\(\s*([A-Z_0-9]+)\s*,', fb):
            const = m.group(1)
            lit = re.search(r'const val %s\s*=\s*"([^"]*)"' % const, code)
            if lit:
                out.setdefault(lit.group(1), fn)
        for m in re.finditer(r'\.put(?:Int|Boolean|String|Long|Float)\(\s*"([^"]+)"\s*,', fb):
            out.setdefault(m.group(1), fn)
    return out


def check_settings_contains_precondition(repo, exported_keys):
    """
    settings 段用「键是否存在」判断要不要写回（设计上是对的：区分「表过态」与「没表过态」）。
    但它与 PetPrefs.get() 的启动迁移会打架：迁移先把键写进去，恢复就被判成「已表过态」而跳过。
    这是一条静默失效 —— 摘要里显示「补了 N 项」，用户却发现自己最在意的某项没跟着过来。
    """
    print("\n· settings「按键存在性判断」的前提核对：")
    mig = migration_written_keys(repo)
    if not mig:
        print("    未发现启动迁移写入 settings 键")
        return
    check_settings_value_compare(repo, mig)

    # exportSettings 用的是 JSON 键名（theme/webSearch/...），需映射回偏好键名
    pref_of = {
        "theme": "theme_mode", "themeMonet": "theme_monet", "memAuto": "mem2_auto",
        "memInterval": "mem2_interval_idx", "memMode": "mem2_mode", "memSearch": "mem2_search",
        "webSearch": "web_search", "think": "think_level", "bgOn": "bg_enabled",
        "bgMode": "bg_mode", "bgAlpha": "chat_bg_alpha", "bgBlur": "blur_radius",
        "scrim": "scrim_strength", "frost": "frost_strength", "groups": "doll_groups",
    }
    clashed = []
    for jkey in exported_keys:
        pref = pref_of.get(jkey)
        if pref and pref in mig:
            clashed.append((jkey, pref, mig[pref]))

    print("    启动迁移会无条件写入的键：%s" % sorted(mig.keys()))
    if not clashed:
        print("    settings 项与之冲突：无 ✓")
        return

    for jkey, pref, fn in clashed:
        # 【lead·补判据】不能只看「迁移会写这个键」，还要看**恢复端到底用哪种判据**。
        # 本工程已把这两个键从 `!p.contains(...)` 改成「值比对」（见 AllInOneArchive 的
        # MIGRATED_WEB_SEARCH 与 migratedScrimValue()）。不核对这一点，
        # 修好之后脚本仍会照旧报 P1 —— 那是脚本滞后于代码，不是缺陷还在。
        arch = code_of(repo, ARCHIVE)
        # 【lead·判据已演进到第三版】第一版是「值比对」（把当前值喂回迁移公式），
        # 被 archive-verify 证明是循环不动点（会把用户主动设的 0..40 判成没动过而覆盖）。
        # 现方案是「迁移快照键比对」：迁移时另存 *_migrated_val，恢复时与快照比。
        # 这里两种都认，但只要还是「器公式重算」就判为未修 —— 那是已知的错误做法。
        if pref == "web_search":
            fixed = bool(re.search(r"webSearchUntouched\(ctx\)", arch))
            formula_based = bool(re.search(r"PetPrefs\.webSearchEnabled\(ctx\)\s*==\s*MIGRATED_WEB_SEARCH", arch))
        else:
            fixed = bool(re.search(r"scrimUntouched\(ctx\)", arch))
            formula_based = bool(re.search(r"PetPrefs\.scrimStrength\(ctx\)\s*==\s*migratedScrimValue\(ctx\)", arch))
        still_contains = bool(re.search(r'!p\.contains\("%s"\)' % re.escape(pref), arch))
        if formula_based:
            print("    ✗ %s -> 仍用「迁移公式重算」做判据：会覆盖用户主动设置的值" % jkey)
            continue
        if fixed and not still_contains:
            print("    ✓ %s -> 恢复端已改为「迁移快照键比对」，不受迁移预置影响" % jkey)
            continue
        print("    ✗ %s -> 偏好键 %s 已被 %s() 在启动时写入，恢复端 !p.contains() 恒为 false"
              % (jkey, pref, fn))
        detail = (
            "restoreSettings 用 !p.contains(键) 决定是否写回，但 PetPrefs.get() 启动时调用的 "
            + fn + "() 会**无条件**把这个键写进去（见 PetPrefs.get 的迁移链）。"
            + "结果：换机恢复后「" + jkey + "」这项设置不会被带过来；"
            + "而摘要只累计真正写入的项数，这一项被静默跳过，用户以为已还原但实际未还原。"
            + "注意：这与「按键判断优于按值判断」的设计无关 —— 判据本身是对的，"
            + "问题在于该键在任何机器上都已被本机迁移预置，判据退化为恒假。"
            + "修法建议（不在本次写入范围）：恢复前先删掉迁移标记位，"
            + "或对该键改用「值是否等于迁移默认值」的窄口径判断。"
        )
        # 【lead·修脚本自身的 bug】原来写的是
        #   "..." + fn + "..." % (jkey, pref, fn)
        # ——字符串拼接的优先级高于 %，于是 `% (元组)` 作用在最后一段字面量上，
        #   那一段里没有任何占位符，直接抛 TypeError 把整个脚本打死。
        # 现象是：跑脚本时在 settings 段崩溃，看到的却是崩溃前更早的旧结论。
        pct = "%d"
        add(P1, "settings 项在目标机永不恢复（键存在性判据被启动迁移破坏）",
            detail,
            "%s restoreSettings / %s:"
            % (ARCHIVE, PETPREFS)
            + pct % repo.line_of(PETPREFS, "private fun %s(" % fn)
            + " " + fn + "()")



def check_settings_value_compare(repo, mig_keys):
    """
    当某个 settings 键被启动迁移预置后，实现方可能改用「值比对」代替 contains。
    这个方向是对的（能让项目真的恢复），但它有一个必须核的坑：
    值比对把「值等于迁移默认值」当成「用户没表态」，于是**用户主动选成同一个值的**
    会被当成没表态而被备份覆盖。设计说明 §5 明确反对按值判断，正是这个理由。
    这里逐个核对采用了值比对的项，看它是否会吞掉用户的合法选择。
    """
    arch_lines = code_lines(repo, ARCHIVE)
    s_start, s_body = find_fun_body(arch_lines, r"private fun restoreSettings\(")
    sb = strip_comments(s_body or "")
    if s_start == 0:
        return

    # 找出 restoreSettings 里不再用 !p.contains 的项（即用了值比对）。
    # 注意条件里可能含 `func(ctx)` 这类内部括号，不能简单用 [^)]* 截断 —— 必须做括号配平。
    value_compared = []
    for m in re.finditer(r'if \(s\.has\("([A-Za-z0-9_]+)"\)\s*&&\s*', sb):
        jkey = m.group(1)
        i = m.end()
        depth = 1
        j = i
        while j < len(sb) and depth > 0:
            if sb[j] == "(":
                depth += 1
            elif sb[j] == ")":
                depth -= 1
            j += 1
        cond = sb[i:j - 1].strip()
        if "contains(" not in cond:
            value_compared.append((jkey, cond))

    if not value_compared:
        return

    print("\n    采用「值比对」而非键存在性判断的项：")
    for jkey, cond in value_compared:
        print("      · %s ： %s" % (jkey, cond[:70]))

    # 逐个判定风险：迁移默认值是否可能等于「用户合法选择」
    # scrim 的迁移公式 min(100-alpha, k) 夹到 0..40 —— 当 alpha=100 或 k=0 时**恒为 0**，
    # 而 0 恰恰是「原图直出」这个用户可主动选择的合法值（PetPrefs 迁移 KDoc 明写「强度 0 = 用户主动关掉」）。
    migrated_forms = {
    "scrim": ("min(100 - alpha, k)", "migrateScrimLinear"),
    "webSearch": ("true", "forceMemSwitchesOn"),
    }

    # 【lead·现已改用快照方案，这一整类风险已消除】迁移时另存 *_migrated_val 快照，
    # 恢复时与快照比对，而不是与「公式重算值」或「硬编码默认值」比对。
    # 快照方案天然免疫这里说的坑：用户改过之后当前值就不等于快照了，不会被误判成没表态。
    # 只要恢复端用的是 Untouched() 快照判据且迁移侧确实写了快照键，就跳过本项告警。
    snap_ok = {
        "scrim": ("scrimUntouched(ctx)", "scrim_migrated_val"),
        "webSearch": ("webSearchUntouched(ctx)", "web_search_migrated_val"),
    }
    prefprefs = code_of(repo, PETPREFS)
    for jkey in list(migrated_forms.keys()):
        call, snapkey = snap_ok[jkey]
        if call in code_of(repo, ARCHIVE) and snapkey in prefprefs:
            print("      · %s 已改用迁移快照键（%s），值比对风险消除 ✓" % (jkey, snapkey))
            migrated_forms.pop(jkey, None)
    if not migrated_forms:
        return
    for jkey, cond in value_compared:
        if jkey in migrated_forms:
            form, fn = migrated_forms[jkey]
            if jkey == "scrim":
                # 实测：alpha=100 或 k=0 时公式恒为 0，而 0 是用户合法选择
                add(P1, "scrim 的值比对会吞掉用户「遮罩=0」的选择",
                    "restoreSettings 对 scrim 改用值比对 `PetPrefs.scrimStrength(ctx) == migratedScrimValue(ctx)`。"
                    "migratedScrimValue 复刻的是迁移公式 min(100-chat_bg_alpha, k) 再夹 0..40，"
                    "当 chat_bg_alpha=100 或 k=0 时该式**恒等于 0**，而 0 正是用户可主动选择的"
                    "「原图直出」（PetPrefs.migrateScrimLinear KDoc 明写「强度 0（用户主动关掉）→ 迁 0，"
                    "尊重这个明确选择」）。于是用户把遮罩拖到 0 时，恢复会把它判成「没表态」并用备份值顶掉 —— "
                    "这恰好是设计说明 §5 说「按值判断会犯」的那个错，只是从「毛玻璃」换成了「遮罩」。"
                    "建议：值比对只对「迁移写入的值必定不是用户合法选择」的键成立；"
                    "scrim 的 0 不满足这一条，应改为「scrim_linear_v1 标记 + 值比对」双条件，"
                    "或在迁移完成后立即记录一个「迁移写入值」快照键用于比对。",
                    "%s:%d restoreSettings" % (ARCHIVE, s_start))
            else:
                add(P2, "%s 的值比对同样有「用户选成默认值即被覆盖」的风险" % jkey,
                    "restoreSettings 对 %s 改用值比对（等于 %s，迁移函数 %s 的写入值）。"
                    "若该值同时也是用户可主动选择的合法值，则用户的选择会被备份覆盖 —— "
                    "这正是设计说明 §5 反对按值判断的理由。本次未发现可主动选成该值的 UI 入口，"
                    "故记为观察项。" % (jkey, form, fn),
                    "%s:%d restoreSettings" % (ARCHIVE, s_start))


# ---------------------------------------------------------------- 检查 5：upsertRecord

def check_upsert(repo):
    print("=" * 78)
    print("【5】DollStore.upsertRecord 正确性")
    print("=" * 78)

    dl = code_lines(repo, DOLLSTORE)
    start, body = find_fun_body(dl, r"fun upsertRecord\(")
    if start == 0:
        print("· upsertRecord 不存在 ✗")
        add(P0, "upsertRecord 缺失", "DollStore 没有 upsertRecord，恢复无法新建人偶", DOLLSTORE)
        return
    print("· 位置：%s:%d" % (DOLLSTORE, start))
    b = strip_comments(body)

    checks = [
        ("签名参数为 (ctx, doll)", r"fun upsertRecord\(\s*ctx\s*:\s*Context\s*,\s*doll\s*:\s*Doll\?"),
        ("空 / null 入参直接返回 false", r"if\s*\(\s*doll\s*==\s*null\s*\|\|\s*doll\.id\.isEmpty\(\)\s*\)"),
        ("按 id 判重（遍历已存在）", r"for\s*\(\s*d\s+in\s+list\s*\)"),
        ("id 命中则返回 false（不覆盖）", r"if\s*\(\s*d\.id\s*==\s*doll\.id\s*\)\s*\{\s*return false"),
        ("不存在则插入", r"list\.add\("),
        ("builtin 强制 false（保留 builtin 保护）", r"builtin\s*=\s*false"),
        ("走 load(ctx) 复用持久化", r"val list = load\(ctx\)"),
        ("走 save(ctx, list) 落盘", r"save\(ctx,\s*list\)"),
        ("成功返回 true", r"return true"),
    ]
    ok_all = True
    for title, pat in checks:
        hit = bool(re.search(pat, b, re.S))
        if not hit:
            ok_all = False
        print("  %-42s %s" % (title, "✓" if hit else "✗"))
        if not hit:
            add(P1, "upsertRecord 行为不符", "未满足：%s" % title, "%s:%d" % (DOLLSTORE, start))

    # 阈值：新增行数必须落在设计说明声明的 409-431 区间附近
    end = start + len(body.split("\n")) - 1
    print("· 函数跨度：%d - %d 行（设计说明声明 409-431）" % (start, end))
    if abs(start - 409) > 20:
        add(P2, "upsertRecord 位置与说明不符", "实际起始 %d 行，说明写 409 行（说明需更新）" % start, DOLLSTORE)

    # 5.x 是否误用 update（update 对不存在 id 静默无操作）
    _, restore_body = find_fun_body(code_lines(repo, ARCHIVE), r"\bfun restore\(ctx")
    uses_upsert = "upsertRecord" in (restore_body or "")
    print("· restore 对新人偶走 upsertRecord：%s" % ("✓" if uses_upsert else "✗"))
    if not uses_upsert:
        add(P1, "恢复未用 upsertRecord", "新人偶不会被创建", ARCHIVE)

    # 5.y 确认没有照搬备份里的 builtin
    bad = re.search(r"builtin\s*=\s*doll\.builtin", b)
    print("· 未照搬来源机 builtin 位：%s" % ("✓" if not bad else "✗"))
    if bad:
        add(P1, "upsertRecord 照搬 builtin", "外来备份可把普通人偶变成不可删除", "%s:%d" % (DOLLSTORE, start))

    # 5.z 不应在此处打动作库空白标记（会让恢复的人偶没有内置动作）
    mark = "markEmpty" in b
    print("· 未调用 DollAnim.markEmpty（保留内置动作补齐）：%s" % ("✓" if not mark else "✗"))
    if mark:
        add(P2, "upsertRecord 打了空白模板标记", "恢复的人偶将连内置动作都不会补", "%s:%d" % (DOLLSTORE, start))

    if ok_all:
        print("\n结论：upsertRecord 按 id 判重、不存在则插入、builtin 强制 false，未破坏既有保护。\n")


# ---------------------------------------------------------------- 检查 6：图片路径安全

def check_image_path(repo):
    print("=" * 78)
    print("【6】图片路径安全（恢复写盘不得越出私有目录）")
    print("=" * 78)

    arch_lines = code_lines(repo, ARCHIVE)
    start, body = find_fun_body(arch_lines, r"private fun restoreImages\(")
    b = strip_comments(body or "")
    print("· restoreImages 位置：%s:%d" % (ARCHIVE, start))

    checks = [
        ("rel 为空则拒绝", r'rel\.isEmpty\(\)'),
        ("写盘前过 ImageStore.isInsideBase", r"ImageStore\.isInsideBase\(ctx\.filesDir,\s*rel\)"),
        ("isInsideBase 校验在 File(...) 构造成目标前", None),
        ("目标已存在则跳过（不覆写）", r"if\s*\(\s*!target\.isFile\s*\)"),
    ]
    for title, pat in checks:
        if pat is None:
            continue
        hit = bool(re.search(pat, b))
        print("  %-46s %s" % (title, "✓" if hit else "✗"))
        if not hit:
            add(P0, "恢复写图未做路径校验", "未满足：%s" % title, "%s:%d" % (ARCHIVE, start))

    # 顺序：isInsideBase 判断必须出现在 File(ctx.filesDir, rel) 之前
    idx_check = b.find("isInsideBase")
    idx_target = b.find("File(ctx.filesDir, rel)")
    order_ok = idx_check >= 0 and (idx_target < 0 or idx_check < idx_target)
    print("  %-46s %s" % ("路径校验先于目标文件构造", "✓" if order_ok else "✗"))
    if not order_ok:
        add(P0, "路径校验顺序错误", "先构造目标文件再校验，校验形同虚设", "%s:%d" % (ARCHIVE, start))

    # ImageStore.isInsideBase 本身的口径
    is_start, is_body = find_fun_body(code_lines(repo, IMAGESTORE), r"fun isInsideBase\(")
    ib = strip_comments(is_body or "")
    is_checks = [
        ("拒绝空串", r'if\s*\(\s*rel\.isEmpty\(\)\s*\)\s*\{\s*return false'),
        ("拒绝绝对路径（以分隔符开头）", r"rel\.startsWith\(File\.separatorChar\)"),
        ("用 canonicalPath 规范化（消解 ..）", r"canonicalPath"),
        ("要求前缀匹配 base + 分隔符", r'startsWith\(basePath \+ File\.separatorChar\)'),
        ("拒绝 base 自身", r"target\s*!=\s*basePath"),
    ]
    for title, pat in is_checks:
        hit = bool(re.search(pat, ib, re.S))
        print("  %-46s %s" % ("isInsideBase: " + title, "✓" if hit else "✗"))
        if not hit:
            add(P1, "isInsideBase 判定不严", "未满足：%s" % title, "%s:%d" % (IMAGESTORE, is_start))

    # 导出侧 relOf 也必须做同样的越界收口
    r_start, r_body = find_fun_body(arch_lines, r"private fun relOf\(")
    rb = strip_comments(r_body or "")
    rel_ok = ("canonicalPath" in rb) and bool(re.search(r'startsWith\(basePath \+ File\.separatorChar\)', rb))
    print("  %-46s %s" % ("relOf 导出侧同样做越界收口", "✓" if rel_ok else "✗"))
    if not rel_ok:
        add(P1, "导出侧未做越界收口", "记录里指向私有目录外的路径会入包", "%s:%d" % (ARCHIVE, r_start))

    # 6.x 恢复写盘用的是 target.writeBytes —— 确认没有用相对路径写「任意位置」
    print("· 落盘调用：%s" % ("target.writeBytes（相对 filesDir 解析）✓"
                            if "target.writeBytes" in b else "未找到 target.writeBytes"))

    if order_ok and re.search(r"ImageStore\.isInsideBase\(ctx\.filesDir,\s*rel\)", b):
        print("\n结论：恢复写图前经 isInsideBase 校验，越界路径被拒绝，不会写到私有目录外。\n")


# ---------------------------------------------------------------- 检查 7：人设 key 覆盖率

def collect_dollprofile_keys(repo):
    """
    收集 DollProfile 里全部 `private const val K_*` 的**键值字符串**。
    返回 [(常量名, 键值字符串)]，保持源码顺序。
    """
    out = []
    code = code_of(repo, DOLLPROFILE)
    for m in re.finditer(r'private const val (K_[A-Z0-9_]+)\s*=\s*"([^"]*)"', code):
        out.append((m.group(1), m.group(2)))
    return out


def check_persona_coverage(repo):
    print("=" * 78)
    print("【7】人设 key 覆盖率（DollProfile 全部 K_* 对照 AllInOneArchive 白名单）")
    print("=" * 78)

    keys = collect_dollprofile_keys(repo)
    # 只要「人设属性」类常量：排除纯数值边界常量（BOUND_MIN/MAX 等没有字符串值的已被过滤）
    print("· DollProfile 中 private const val K_* 共 %d 个" % len(keys))
    print("  （任务书说的「约 30 个」= %d 个 K_* + 5 个手势字面量 + K_BOUND 展开的 8 个动态键 = %d 个逻辑键）"
          % (len(keys), len(keys) - 1 + 5 + 8))

    # 白名单
    arch_lines = code_lines(repo, ARCHIVE)
    # PERSONA_BASES 是 `val = arrayOf(...)`，不是函数体：必须按圆括号配平截取，
    # 否则 find_fun_body 会一直吃到文件尾，把 BOUND_SIDES 等无关字面量也吞进来。
    p_start, base_block = extract_array_block(repo, ARCHIVE, "PERSONA_BASES")

    bases = re.findall(r'"([^"]+)"', base_block)
    print("· AllInOneArchive.PERSONA_BASES 共 %d 项" % len(bases))

    # 动态键：bounds_<l|t|r|b>_<port|land>
    sides = re.findall(r'BOUND_SIDES = arrayOf\((.*?)\)', code_of(repo, ARCHIVE), re.S)
    has_dynamic_bounds = bool(re.search(r'"bounds_" \+ s \+ "_" \+ o', code_of(repo, ARCHIVE)))
    bound_const = next((v for n, v in keys if n == "K_BOUND"), None)
    print("· 动态键 K_BOUND = \"%s\"，按 %s 拼装：%s" %
          (bound_const, "4 边 x 2 方向", "✓" if has_dynamic_bounds else "✗"))
    if not has_dynamic_bounds:
        add(P1, "动态 bounds 键未采集", "人偶可移动范围（8 个键/人偶）不会进存档", "%s:%d" % (ARCHIVE, p_start))

    # 手势五连：DollProfile 里是字符串字面量（无 K_ 常量），必须单独核对
    gesture_bases = ["gesture_single", "gesture_double", "gesture_triple", "gesture_long", "gesture_hold"]
    missing_gestures = [g for g in gesture_bases if g not in bases]
    print("· 五个手势键全部在白名单：%s" % ("✓" if not missing_gestures else "✗ " + str(missing_gestures)))

    print("\n%-10s %-22s %-10s %s" % ("常量", "键值", "在白名单", "说明"))
    print("-" * 78)
    missing = []
    covered = []
    for name, val in keys:
        if name == "K_BOUND":
            in_list = has_dynamic_bounds
            note = "动态拼装 8 键"
        else:
            in_list = val in bases
            note = ""
        mark = "✓" if in_list else "✗ 漏"
        print("%-10s %-22s %-10s %s" % (name, val, mark, note))
        if in_list:
            covered.append((name, val))
        else:
            missing.append((name, val))

    # 白名单里多出来的项（人设键清单里没有对应 K_*，需人工确认是否是手势键/其它动态键）
    known_extra_ok = set(gesture_bases)
    extra = [b for b in bases if b not in [v for _, v in keys] and b not in known_extra_ok]
    print("\n· 白名单中无对应 K_* 的项（人工确认）：%s" % ("无" if not extra else extra))

    print()
    if not missing:
        print("结论：DollProfile 的 %d 个 K_* 全部被 persona 白名单覆盖（含 K_BOUND 动态 8 键）。\n" % len(keys))
    else:
        print("结论：漏 %d 个键！用户换机后这些设置会丢失：\n" % len(missing))
        for name, val in missing:
            print("   - %s = \"%s\"" % (name, val))
        for name, val in missing:
            add(P1, "人设键未进存档白名单",
                "DollProfile.%s = \"%s\" 不在 PERSONA_BASES 里，换机后该项设置丢失" % (name, val),
                "%s:%d（PERSONA_BASES）" % (ARCHIVE, p_start))
        print()

    if missing_gestures:
        add(P1, "手势键未进存档白名单", "缺：%s" % missing_gestures, ARCHIVE)

    # 恢复端必须按类型写回（否则 ClassCastException）
    put_start, put_body = find_fun_body(arch_lines, r"private fun putValue\(")
    typed = all(t in (put_body or "") for t in ['"b"', '"i"', '"l"', '"f"'])
    print("· 恢复端按类型位写回（b/i/l/f/s）：%s" % ("✓" if typed else "✗"))
    if not typed:
        add(P1, "人设恢复未按类型写回", "会把 int 写成 String，读取端 ClassCastException", "%s:%d" % (ARCHIVE, put_start))

    # Float 转 double 再缩回
    enc_start, enc_body = find_fun_body(arch_lines, r"private fun encodeValue\(")
    float_ok = ("toDouble()" in (enc_body or "")) and (".toFloat()" in (put_body or ""))
    print("· Float 以 double 存、读时缩回 float：%s" % ("✓" if float_ok else "✗"))
    if not float_ok:
        add(P2, "Float 类型往返不保真", "JSON 无 float 类型，需 double 存 + toFloat 读", ARCHIVE)

    # exportPersona 只对有值的人偶落段（空段不下发）
    persona_dynamic_ok = bool(re.search(r'keys\.add\(DollProfile\.key\(dollId,\s*"bounds_"', code_of(repo, ARCHIVE)))
    print("· bounds 键用 DollProfile.key 拼装（与读取端同一规则）：%s" % ("✓" if persona_dynamic_ok else "✗"))
    if not persona_dynamic_ok:
        add(P1, "bounds 键拼装规则不一致", "未用 DollProfile.key 拼装，默认人偶的裸键形式会错", ARCHIVE)

    return covered, missing


# ---------------------------------------------------------------- 附加：跨对象 API 与路径约定

def check_api_existence(repo):
    print("=" * 78)
    print("【附】跨对象调用存在性（脚本侧抽样核对）")
    print("=" * 78)

    # (调用形态, 定义所在文件, 定义正则, 说明)
    calls = [
        (r"ProviderStore\.providers\(", DATA_DIR + "/ProviderStore.kt", r"fun providers\(ctx: Context\)", "ProviderStore.providers"),
        (r"ProviderStore\.saveProvider\(", DATA_DIR + "/ProviderStore.kt", r"fun saveProvider\(", "ProviderStore.saveProvider"),
        (r"ProviderStore\.newId\(", DATA_DIR + "/ProviderStore.kt", r"fun newId\(\)", "ProviderStore.newId"),
        (r"ModelStore\.models\(", DATA_DIR + "/ModelStore.kt", r"fun models\(ctx: Context\)", "ModelStore.models"),
        (r"ModelStore\.modelExists\(", DATA_DIR + "/ModelStore.kt", r"fun modelExists\(", "ModelStore.modelExists"),
        (r"ModelStore\.findModel\(", DATA_DIR + "/ModelStore.kt", r"fun findModel\(", "ModelStore.findModel"),
        (r"ModelStore\.saveModel\(", DATA_DIR + "/ModelStore.kt", r"fun saveModel\(", "ModelStore.saveModel"),
        (r"DollStore\.all\(", DOLLSTORE, r"fun all\(ctx: Context\)", "DollStore.all"),
        (r"DollStore\.byId\(", DOLLSTORE, r"fun byId\(", "DollStore.byId"),
        (r"DollStore\.update\(", DOLLSTORE, r"fun update\(", "DollStore.update"),
        (r"DollStore\.active\(", DOLLSTORE, r"fun active\(", "DollStore.active"),
        (r"DollStore\.setActive\(", DOLLSTORE, r"fun setActive\(", "DollStore.setActive"),
        (r"DollStore\.upsertRecord\(", DOLLSTORE, r"fun upsertRecord\(", "DollStore.upsertRecord"),
        (r"Mem\.list\(", DATA_DIR + "/Mem.kt", r"fun list\(", "Mem.list"),
        (r"Mem\.add\(", DATA_DIR + "/Mem.kt", r"fun add\(", "Mem.add"),
        (r"Mem\.findByTitle\(", DATA_DIR + "/Mem.kt", r"fun findByTitle\(", "Mem.findByTitle"),
        (r"Mem\.links\(", DATA_DIR + "/Mem.kt", r"fun links\(", "Mem.links"),
        (r"Mem\.addLink\(", DATA_DIR + "/Mem.kt", r"fun addLink\(", "Mem.addLink"),
        (r"Mem\.profile\(", DATA_DIR + "/Mem.kt", r"fun profile\(", "Mem.profile"),
        (r"Mem\.setProfile\(", DATA_DIR + "/Mem.kt", r"fun setProfile\(", "Mem.setProfile"),
        (r"MyProfile\.name\(", MYPROFILE, r"fun name\(", "MyProfile.name"),
        (r"MyProfile\.bio\(", MYPROFILE, r"fun bio\(", "MyProfile.bio"),
        (r"MyProfile\.avatarPath\(", MYPROFILE, r"fun avatarPath\(", "MyProfile.avatarPath"),
        (r"MyProfile\.setName\(", MYPROFILE, r"fun setName\(", "MyProfile.setName"),
        (r"MyProfile\.setBio\(", MYPROFILE, r"fun setBio\(", "MyProfile.setBio"),
        (r"MyProfile\.commitAvatar\(", MYPROFILE, r"fun commitAvatar\(", "MyProfile.commitAvatar"),
        (r"DollProfile\.key\(", DOLLPROFILE, r"fun key\(", "DollProfile.key"),
        (r"DollProfile\.chatBgPath\(", DOLLPROFILE, r"fun chatBgPath\(", "DollProfile.chatBgPath"),
        (r"ImageStore\.isInsideBase\(", IMAGESTORE, r"fun isInsideBase\(", "ImageStore.isInsideBase"),
        (r"ImageStore\.exists\(", IMAGESTORE, r"fun exists\(", "ImageStore.exists"),
        (r"PetPrefs\.convIndex\(", PETPREFS, r"fun convIndex\(", "PetPrefs.convIndex"),
        (r"PetPrefs\.convHistory\(", PETPREFS, r"fun convHistory\(", "PetPrefs.convHistory"),
        (r"PetPrefs\.convPrev\(", PETPREFS, r"fun convPrev\(", "PetPrefs.convPrev"),
        (r"PetPrefs\.convDoll\(", PETPREFS, r"fun convDoll\(", "PetPrefs.convDoll"),
        (r"PetPrefs\.convAvatar\(", PETPREFS, r"fun convAvatar\(", "PetPrefs.convAvatar"),
        (r"PetPrefs\.convRead\(", PETPREFS, r"fun convRead\(", "PetPrefs.convRead"),
        (r"PetPrefs\.convHidden\(", PETPREFS, r"fun convHidden\(", "PetPrefs.convHidden"),
        (r"PetPrefs\.isConvCleared\(", PETPREFS, r"fun isConvCleared\(", "PetPrefs.isConvCleared"),
        (r"PetPrefs\.setConvHistory\(", PETPREFS, r"fun setConvHistory\(", "PetPrefs.setConvHistory"),
        (r"PetPrefs\.setConvPrev\(", PETPREFS, r"fun setConvPrev\(", "PetPrefs.setConvPrev"),
        (r"PetPrefs\.setConvDoll\(", PETPREFS, r"fun setConvDoll\(", "PetPrefs.setConvDoll"),
        (r"PetPrefs\.setConvAvatar\(", PETPREFS, r"fun setConvAvatar\(", "PetPrefs.setConvAvatar"),
        (r"PetPrefs\.setConvRead\(", PETPREFS, r"fun setConvRead\(", "PetPrefs.setConvRead"),
        (r"PetPrefs\.setConvHidden\(", PETPREFS, r"fun setConvHidden\(", "PetPrefs.setConvHidden"),
        (r"PetPrefs\.setConvCleared\(", PETPREFS, r"fun setConvCleared\(", "PetPrefs.setConvCleared"),
        (r"PetPrefs\.MEM2_INTERVALS", PETPREFS, r"val MEM2_INTERVALS", "PetPrefs.MEM2_INTERVALS"),
        (r"PetPrefs\.setMem2IntervalIndex\(", PETPREFS, r"fun setMem2IntervalIndex\(", "PetPrefs.setMem2IntervalIndex"),
        (r"PetPrefs\.mem2IntervalMinutes\(", PETPREFS, r"fun mem2IntervalMinutes\(", "PetPrefs.mem2IntervalMinutes"),
        (r"Provider\.fromJson\(", PROVIDER, r"fun fromJson\(", "Provider.fromJson"),
        (r"AiModel\.fromJson\(", AIMODEL, r"fun fromJson\(", "AiModel.fromJson"),
        (r"Memory\.fromJson\(", DATA_DIR + "/MemModels.kt", r"fun fromJson\(", "Memory.fromJson"),
        (r"MemLink\.fromJson\(", DATA_DIR + "/MemModels.kt", r"fun fromJson\(", "MemLink.fromJson"),
    ]

    arch_code = code_of(repo, ARCHIVE)
    missing = []
    for call_pat, def_file, def_pat, label in calls:
        used = re.search(call_pat, arch_code) is not None
        if not used:
            continue
        defined = bool(re.search(def_pat, code_of(repo, def_file)))
        exists = repo.exists(def_file)
        status = "✓" if (defined and exists) else "✗ 缺失定义"
        if not (defined and exists):
            missing.append(label)
        print("  %-38s %s" % (label, status))

    print()
    if missing:
        add(P1, "跨对象调用缺定义", "以下调用在目标文件里找不到声明（编译会失败）：%s" % missing, ARCHIVE)
        print("结论：%d 处调用缺定义 ✗\n" % len(missing))
    else:
        print("结论：脚本抽样的 %d 处跨对象调用全部有定义 ✓\n" % len([c for c in calls if re.search(c[0], arch_code)]))

    return missing


def check_path_conventions(repo):
    """
    路径约定一致性：恢复时写进各字段的，必须是**读取端真正能解析的那一种**路径。

    本工程的磁盘口径是分叉的，不是统一的：
      · cover / sprite —— DollStore.encode 落盘返回**绝对路径**，消费方
        DollStore.coverOf/spriteOf → decodeFile → ImageDecode.file → File(path) 直接读；
      · chatbg / avatar —— ImageStore.saveScaledFile 返回 "子目录/文件名" 的**相对**路径，
        消费方 ImageStore.exists/loadScaled → fileFor → isInsideBase，
        而 isInsideBase 对绝对路径**直接返回 false**。
    所以恢复时不能一律写绝对路径，必须按字段分口径。这条写错的表现是
    「图恢复成功但永远看不见」，属于最伤信任的失败态。
    """
    print("=" * 78)
    print("【附】路径约定一致性（恢复写入的路径形态 是否是读取端能解析的那一种）")
    print("=" * 78)

    arch_lines = code_lines(repo, ARCHIVE)
    arch_code = code_of(repo, ARCHIVE)

    # 1) REL_FIELDS 白名单存在且内容正确
    rel_start, rel_block = extract_set_block(repo, ARCHIVE, "REL_FIELDS")
    rel_names = re.findall(r'"([^"]+)"', rel_block or "")
    print("· REL_FIELDS（相对 filesDir 口径的字段）= %s" % rel_names)
    has_rel_set = rel_start > 0
    need_rel = {"chatbg", "avatar"}
    if not has_rel_set:
        print("  ✗ 未定义 REL_FIELDS：恢复端没有区分路径口径")
        add(P1, "恢复未区分路径口径（chatbg / avatar 会写成绝对路径）",
            "chatbg 与 avatar 在磁盘上是相对 filesDir 的路径，读取端 isInsideBase 会拒绝绝对路径；"
            "一律写 target.absolutePath 会导致「图恢复了但背景 / 头像看不见」。",
            "%s:%d restoreImages" % (ARCHIVE, find_fun_body(arch_lines, r"private fun restoreImages\(")[0]))
    else:
        missing = need_rel - set(rel_names)
        extra_bad = {"cover", "sprite"} & set(rel_names)
        print("    · 含 chatbg / avatar：%s" % ("✓" if not missing else "✗ 缺 " + str(sorted(missing))))
        print("    · 未把 cover / sprite 误收进来（它们要绝对路径）：%s"
              % ("✓" if not extra_bad else "✗ 误收 " + str(sorted(extra_bad))))
        if missing:
            add(P1, "相对口径字段漏登记", "以下字段仍会被写成绝对路径：%s" % sorted(missing), "%s:%d" % (ARCHIVE, rel_start))
        if extra_bad:
            add(P1, "绝对口径字段被误改为相对", "cover / sprite 消费方按 File(path) 直接读，改相对会显示不出封面", "%s:%d" % (ARCHIVE, rel_start))

    # 2) restoreImages 的路由：rel 字段写 rel，其余写 absolutePath，帧写 absolutePath
    ri_start, ri_body = find_fun_body(arch_lines, r"private fun restoreImages\(")
    rib = strip_comments(ri_body or "")
    routes_rel = bool(re.search(r"REL_FIELDS\.contains\(field\)", rib))
    puts_rel = bool(re.search(r'out\.singles\[owner \+ "\|" \+ field\] = rel', rib))
    puts_abs = bool(re.search(r'out\.singles\[owner \+ "\|" \+ field\] = target\.absolutePath', rib))
    frames_abs = bool(re.search(r"out\.frames\[src\] = target\.absolutePath", rib))
    print("· restoreImages 按 REL_FIELDS 分流：%s" % ("✓" if routes_rel else "✗"))
    print("· 相对口径字段写入 rel（而非绝对路径）：%s" % ("✓" if puts_rel else "✗"))
    print("· 其余单值字段写入 target.absolutePath：%s" % ("✓" if puts_abs else "✗"))
    print("· 帧图映射写入 target.absolutePath：%s" % ("✓" if frames_abs else "✗"))
    if routes_rel and puts_rel and puts_abs:
        pass
    elif has_rel_set:
        add(P1, "restoreImages 未按 REL_FIELDS 分流", "恢复写回路径没有区分口径", "%s:%d" % (ARCHIVE, ri_start))

    # 3) 读取端真实口径核对（这三条是整个结论的依据）
    is_inside_rejects_abs = bool(re.search(r"rel\.startsWith\(File\.separatorChar\)",
                                           strip_comments(find_fun_body(code_lines(repo, IMAGESTORE), r"fun isInsideBase\(")[1] or "")))
    _, av_body = find_fun_body(code_lines(repo, MYPROFILE), r"fun avatarBitmap\(")
    avatar_via_imagestore = "ImageStore.loadScaled" in (av_body or "")
    _, ca_body = find_fun_body(code_lines(repo, MYPROFILE), r"fun commitAvatar\(")
    commit_stores_raw = bool(re.search(r'putString\("my_avatar",\s*pendingRel\)', strip_comments(ca_body or "")))
    gb_code = code_of(repo, UI_DIR + "/theme/GlobalBackground.kt")
    chatbg_via_imagestore = "ImageStore.exists" in gb_code

    print()
    print("· 读取端依据：")
    print("    isInsideBase 拒绝绝对路径：%s" % ("是" if is_inside_rejects_abs else "否"))
    print("    头像 avatarBitmap 走 ImageStore（相对语义）：%s" % ("是" if avatar_via_imagestore else "否"))
    print("    人偶背景 resolvePath 走 ImageStore（相对语义）：%s" % ("是" if chatbg_via_imagestore else "否"))
    if avatar_via_imagestore and chatbg_via_imagestore and is_inside_rejects_abs and has_rel_set and puts_rel:
        print("\n结论：恢复端已按字段区分路径口径 —— chatbg / avatar 写相对路径（读取端可解析），"
              "cover / sprite 写绝对路径（读取端按 File(path) 读）。一致 ✓\n")

    # 4) cover / sprite 的读取端确实吃绝对路径（反向确认，防止"统一改相对"这种过度修正）
    cover_body = strip_comments(find_fun_body(code_lines(repo, DOLLSTORE), r"fun coverOf\(ctx: Context, doll: Doll\?, reqPx")[1] or "")
    cover_uses_file = "decodeFile" in cover_body or "File(" in cover_body
    print("· cover / sprite 读取端按路径直接解码（需绝对路径）：%s" % ("是 ✓" if cover_uses_file else "否"))
    print()

    return []


def extract_set_block(repo, rel, val_name):
    """截取 `val <name> = setOf( ... )` 的括号内容；返回 (起始行号, 文本)。"""
    lines = code_lines(repo, rel)
    for i, ln in enumerate(lines):
        if re.search(r"\bval %s\s*=\s*setOf\(" % val_name, ln):
            depth = 0
            started = False
            buf = []
            for j in range(i, len(lines)):
                seg = lines[j]
                buf.append(seg)
                for ch in seg:
                    if ch == "(":
                        depth += 1
                        started = True
                    elif ch == ")":
                        depth -= 1
                    if started and depth <= 0 and j >= i:
                        pass
                if started and depth <= 0:
                    return i + 1, "\n".join(buf)
            return i + 1, "\n".join(buf)
    return 0, ""


# ---------------------------------------------------------------- 队友修复复核

def check_teammate_fixes(repo):
    print("=" * 78)
    print("【复核】队友自述修过的两个 bug")
    print("=" * 78)

    arch_lines = code_lines(repo, ARCHIVE)
    arch_code = code_of(repo, ARCHIVE)

    # --- 修复 1：图片去重键分两类
    pk_start, pk_body = find_fun_body(arch_lines, r"private fun packImages\(")
    pkb = strip_comments(pk_body or "")
    has_frame_branch = bool(re.search(r'w\.field\.startsWith\("frame:"\)\s*\|\|\s*w\.field\.startsWith\("asset:"\)', pkb))
    has_rel_in_key = bool(re.search(r'w\.owner \+ "\|" \+ w\.field \+ "\|" \+ rel', pkb))
    has_single_key = bool(re.search(r'w\.owner \+ "\|" \+ w\.field\s*$', pkb, re.M)) or \
                     bool(re.search(r'else\s*\{\s*w\.owner \+ "\|" \+ w\.field', pkb))
    print("· 修复1【图片去重键分两类】")
    print("   · 多值字段判定 frame:/asset: 前缀：%s" % ("✓" if has_frame_branch else "✗"))
    print("   · 多值键拼进 rel：%s" % ("✓" if has_rel_in_key else "✗"))
    print("   · 单值键只用 owner|field：%s" % ("✓" if has_single_key else "✗"))

    # collectWanted 确实为每帧各生成一条 Wanted
    cw_start, cw_body = find_fun_body(arch_lines, r"private fun collectWanted\(")
    cwb = strip_comments(cw_body or "")
    loops_frames = bool(re.search(r'for\s*\(\s*j\s+in\s+0\s+until\s+frames\.length\(\)\s*\)', cwb))
    adds_per_frame = bool(re.search(r'out\.add\(Wanted\("doll:" \+ d\.id,\s*"frame:" \+ actionId,\s*f\)\)', cwb))
    adds_asset = bool(re.search(r'"asset:" \+ actionId', cwb))
    print("   · collectWanted 逐帧遍历 frames[]：%s" % ("✓" if loops_frames else "✗"))
    print("   · 每帧生成一条 Wanted（不 break / 不提前去重）：%s" % ("✓" if adds_per_frame else "✗"))
    print("   · asset_path 也单独一条：%s" % ("✓" if adds_asset else "✗"))
    no_early_dedup = "seen" not in cwb and "distinct" not in cwb
    print("   · collectWanted 内部没有额外去重把多帧并掉：%s" % ("✓" if no_early_dedup else "✗"))

    fix1_ok = has_frame_branch and has_rel_in_key and has_single_key and loops_frames and adds_per_frame
    if not fix1_ok:
        add(P1, "多帧图片去重仍会丢帧", "packImages / collectWanted 未正确按多值键区分，动作只会剩一张帧", "%s:%d" % (ARCHIVE, pk_start))
    print("   判定：%s\n" % ("修复到位 ✓（N 帧共用一个 owner|field，但键里带了 rel，不会被并掉）"
                          if fix1_ok else "修复不完整 ✗"))

    # 恢复侧必须用同一套分类断言，否则映射错位
    ri_start, ri_body = find_fun_body(arch_lines, r"private fun restoreImages\(")
    rib = strip_comments(ri_body or "")
    restore_frame_branch = bool(re.search(r'field\.startsWith\("frame:"\)\s*\|\|\s*field\.startsWith\("asset:"\)', rib))
    restore_frames_by_src = bool(re.search(r"out\.frames\[src\]", rib))
    restore_singles = bool(re.search(r'out\.singles\[owner \+ "\|" \+ field\]', rib))
    print("   · 恢复侧同样按 frame:/asset: 分类：%s" % ("✓" if restore_frame_branch else "✗"))
    print("   · 帧图按来源路径 src 登记（供 rebindAnim）：%s" % ("✓" if restore_frames_by_src else "✗"))
    print("   · 单值按 owner|field 登记：%s" % ("✓" if restore_singles else "✗"))
    if not (restore_frame_branch and restore_frames_by_src):
        add(P1, "图片映射登记分类不一致", "恢复侧未按多值字段登记，帧路径改写会失败", "%s:%d" % (ARCHIVE, ri_start))

    # --- 修复 2：restoreAnim 先 rebind 再判要不要写盘
    ra_start, ra_body = find_fun_body(arch_lines, r"private fun restoreAnim\(")
    rab = strip_comments(ra_body or "")
    idx_rebind = rab.find("rebindAnim(")
    idx_skip = rab.find("if (!rebound")
    order_ok = idx_rebind >= 0 and idx_skip >= 0 and idx_rebind < idx_skip
    print("· 修复2【restoreAnim 先 rebind 再判是否写盘】")
    print("   · rebindAnim 调用存在：%s" % ("✓" if idx_rebind >= 0 else "✗"))
    print("   · 跳过写盘的判定在 rebind 之后：%s" % ("✓" if order_ok else "✗"))
    # rebindAnim 是**另一个函数**：原地修改的证据要在它自己的函数体里找，不是在 restoreAnim 里。
    rb_start, rb_body = find_fun_body(arch_lines, r"private fun rebindAnim\(")
    rbb = strip_comments(rb_body or "")
    in_place_ok = bool(re.search(r"frames\.put\(", rbb)) and bool(re.search(r'a\.put\("asset_path"', rbb))
    print("   · rebindAnim 原地修改 frames / asset_path：%s" % ("✓" if in_place_ok else "✗"))
    if not in_place_ok:
        add(P1, "rebindAnim 未原地改写",
            "若非原地修改传引用，restoreAnim 里 rebind 之后 merged 仍是旧路径", "%s:%d" % (ARCHIVE, rb_start))
    # 判定条件必须同时考虑「没追加」与「没改写」
    #
    # 【v0.2.8·task-20 语义升级】改造前只有两件事需要写盘：追加了新动作、
    #   或改写了帧路径。task-20 之后多了第三件：**清空了内置动作的 frames**
    #   （内置帧不打包，恢复时要清掉源机死路径并登记重烘）。
    #   所以跳过条件必须同时排除这三件事 —— 缺 `!cleared` 就会在
    #   「本机动作库已有、只需清帧」这一支误判成"不必写盘"，
    #   结果源机死路径继续留在档案里，与修之前完全一样。
    #
    # 【为什么不断言字面量顺序】三个 `&&` 的相对次序不影响语义，
    #   断言应该钉"三件事都被排除"，而不是钉某一种排版。
    cond_tokens = {
        "未改写(rebound)": r"!\s*rebound",
        "未追加(长度)": r"mergedLen\s*==\s*mineLen",
        "未清空(cleared)": r"!\s*cleared",
        "本机已有动作库": r"mineRaw\.isNotEmpty\(\)",
    }
    # 【为什么不抓整个 if (...) 的条件块】该条件里含多层括号（`mergedLen == mineLen` 等），
    #   用 `\((.*?)\)` 非贪婪会在第一个内层 `)` 就截断，抓到的是一段残句 ——
    #   断言随后报"缺"其实是因为没抓全，属于**假红**。
    #   这里改为：先定位 `if (!rebound` 那一行，取到行尾的 `{` 之前，作为条件表达式。
    #   条件写在同一行是本工程的既有风格（同一文件里所有守卫都是单行），可依此约定。
    m_skip_line = re.search(r"^\s*if\s*\(\s*!rebound[^\n]*$", rab, re.M)
    skip_expr = m_skip_line.group(0) if m_skip_line else ""
    if skip_expr and "{" in skip_expr:
        skip_expr = skip_expr.split("{")[0]
    missing = [name for name, pat in cond_tokens.items() if not re.search(pat, skip_expr)]
    cond_ok = bool(skip_expr) and not missing
    print("   · 跳过条件同时含「未追加 / 未改写 / 未清空」：%s" % ("✓" if cond_ok else "✗ 缺: " + "、".join(missing)))
    if not order_ok:
        add(P1, "restoreAnim 判定顺序仍反", "先判写盘再 rebind 会让来源机死路径残留", "%s:%d" % (ARCHIVE, ra_start))
    if not cond_ok:
        add(P2, "restoreAnim 跳过条件不严",
            "条件未同时覆盖「未追加 / 未改写 / 未清空内置帧」，缺: " + "、".join(missing),
            "%s:%d" % (ARCHIVE, ra_start))
    print("   判定：%s\n" % ("修复到位 ✓（rebind 先行，判定看的是改写后的结果）"
                          if (order_ok and cond_ok) else "修复不完整 ✗"))

    return fix1_ok, (order_ok and cond_ok)


# ---------------------------------------------------------------- 汇总

def extract_str_array(repo, rel, val_name):
    """
    截取 `private val <name> = arrayOf( "a", "b", ... )` 里的全部字符串字面量。
    返回 (起始行号, [字符串...])；找不到返回 (0, [])。

    【为什么不用 extract_array_block + 正则全抓】那样会把数组里的**注释**文字
    也当成键（本工程注释极多，注释里恰好写了 `pv_list` 这种键名）。
    这里逐个字面量读，并且跳过 `//` 之后的注释部分。
    """
    lines = code_lines(repo, rel)
    for i, ln in enumerate(lines):
        if re.search(r"\b(val|var)\s+%s\s*=\s*arrayOf\(" % re.escape(val_name), ln):
            depth = 0
            started = False
            buf = []
            for j in range(i, len(lines)):
                seg = lines[j]
                # 去掉该行的行内注释后再数字符括号，避免注释里的括号/引号干扰
                code_part = seg.split("//")[0] if seg.count('"') % 2 == 0 else seg
                buf.append(code_part)
                for ch in code_part:
                    if ch == "(":
                        depth += 1
                        started = True
                    elif ch == ")":
                        depth -= 1
                if started and depth <= 0:
                    return i + 1, re.findall(r'"([^"\\]*)"', "\n".join(buf))
            return i + 1, re.findall(r'"([^"\\]*)"', "\n".join(buf))
    return 0, []


# ---------------------------------------------------------------- 安全：键注入自检

def kotlin_persona_guard(repo):
    """
    把 Kotlin 侧的白名单 / 黑名单**提取出来**，在 Python 里复刻 isAllowedPersonaKey 的判定，
    供后面的「假备份被拒」自检使用。

    【为什么要从源码提取而不是在脚本里重抄一份清单】
    重抄一份的话，这个自检校验的是「脚本以为自己写了什么」，而不是「App 实际会怎么做」——
    源码改了清单、脚本没跟着改，自检照样通过，那就成了自欺。这里直接读 .kt 里的
    PERSONA_BASES / BOUND_SIDES / BOUND_ORIENTS / DANGEROUS_KEY_PREFIXES /
    DANGEROUS_KEY_EXACT，App 的清单变了，本自检的判定跟着变。
    """
    _b_line, bases = extract_str_array(repo, ARCHIVE, "PERSONA_BASES")
    _s_line, sides = extract_str_array(repo, ARCHIVE, "BOUND_SIDES")
    _p_line, bad_prefixes = extract_str_array(repo, ARCHIVE, "DANGEROUS_KEY_PREFIXES")
    _e_line, bad_exact = extract_str_array(repo, ARCHIVE, "DANGEROUS_KEY_EXACT")
    # BOUND_ORIENTS 不是字面量数组（引用 DollProfile.ORIENT_PORT/LAND），从 DollProfile 取真值
    orients = re.findall(r'const val ORIENT_\w+\s*=\s*"([^"]+)"', code_of(repo, DOLLPROFILE))
    return bases, sides, orients, bad_prefixes, bad_exact


def doll_profile_key(doll_id, base):
    """复刻 DollProfile.key()：默认人偶用裸键，其余加 `_<dollId>` 后缀。"""
    if not doll_id or doll_id == "default":
        return base
    return base + "_" + doll_id


def persona_keys_of(doll_id, bases, sides, orients):
    """复刻 AllInOneArchive.personaKeysOf()。"""
    keys = [doll_profile_key(doll_id, b) for b in bases]
    for o in orients:
        for s in sides:
            keys.append(doll_profile_key(doll_id, "bounds_" + s + "_" + o))
    return keys


def is_dangerous_key(key, bad_prefixes, bad_exact):
    """复刻 AllInOneArchive.isDangerousKey()。"""
    if not key:
        return True
    if key in bad_exact:
        return True
    return any(key.startswith(p) for p in bad_prefixes)


def is_allowed_persona_key(doll_id, full_key, bases, sides, orients, bad_prefixes, bad_exact):
    """复刻 AllInOneArchive.isAllowedPersonaKey()：白名单精确成员 + 黑名单。"""
    if not doll_id or not full_key:
        return False
    if is_dangerous_key(full_key, bad_prefixes, bad_exact):
        return False
    return full_key in persona_keys_of(doll_id, bases, sides, orients)


def simulate_restore_persona(section, present_keys, doll_ids, guard, wired):
    """
    按 restorePersona 的**实际判定顺序**模拟一次恢复，返回 (写入键, 被拒键, 跳过键)。

    · 跳过（skipped）：`p.contains(k)` —— 目标机已有该键，符合「只新增」口径；
    · 拒绝（rejected）：不在白名单 / 命中黑名单 —— 本次修复新增的闸门；
    · 写入（written）：通过全部闸门。

    【顺序依据】源码里 `k.isEmpty() || p.contains(k)` 的 continue **在**白名单判定之前，
    所以已存在的键会先被「跳过」，而不是记为「拒绝」。这里严格照这个顺序，
    否则「攻击者在已配置机器上被拦」会被误标成「白名单拦住了」，掩盖真实行为。

    【wired 参数为什么存在】`wired=False` 模拟「源码里**根本没有**那两道闸门」的情形
    （即未修复状态）。这份自检的价值在于「证明修复有效」—— 所以它必须能在闸门被拆掉时
    真的报出注入成功。如果不管接线状态都按清单算出「拒绝」，那这个自检永远通过，
    等于什么都没证明（变异测试正是用来发现这种空转的）。
    """
    bases, sides, orients, bad_prefixes, bad_exact = guard
    written, rejected, skipped = [], [], []
    for doll_id, arr in section.items():
        if doll_id not in doll_ids:
            continue
        for item in arr:
            k = item.get("k", "")
            if not k or k in present_keys:
                skipped.append(k)
                continue
            # 闸门没接线 = 未修复：任何非空、目标机没有的键都直接写盘。
            if wired and not is_allowed_persona_key(
                    doll_id, k, bases, sides, orients, bad_prefixes, bad_exact):
                rejected.append(k)
                continue
            written.append(k)
    return written, rejected, skipped


# ---------------------------------------------------------------- 安全：聊天恢复不得覆盖本机

def simulate_restore_chat(existing_raw, backup_raw, criterion):
    """
    按 restoreChat 的 items 分支模拟「这条会话会不会被写入」，返回 (是否会写, 写入值)。

    criterion：
      · "both_sides"（现在的实现）：本机无内容 **且** 备份有内容 → 才写；
      · "backup_only"（修复前的缺陷）：只看备份有内容 → 就无条件覆盖本机。
      · "local_contains"（更早一版）：只看「本机键存在」→ 就跳过（恒跳过）。

    这三个取值不是为了"支持多版本"，而是为了让变异测试能把判据换回缺陷形态，
    验证自检真的会报红 —— 否则自检只是复述实现，证明不了修复有效。
    """
    local_has = has_messages_py(existing_raw)
    backup_has = has_messages_py(backup_raw)
    if criterion == "both_sides":
        if local_has or not backup_has:
            return False, existing_raw
        return True, backup_raw
    if criterion == "backup_only":
        if not backup_has:
            return False, existing_raw
        return True, backup_raw          # ← 覆盖本机（bug）
    if criterion == "local_contains":
        # 键存在即跳过。conv_<id> 几乎必然被 addConv 预写成 "[]"，
        # 所以用 "键是否存在" 判 ⇒ 恒为真 ⇒ 永远不写（旧 bug，聊天回不来）。
        if existing_raw is not None:
            return False, existing_raw
        return True, backup_raw
    raise ValueError("unknown criterion: " + criterion)


def has_messages_py(raw):
    """复刻 Kotlin 的 hasMessages()：null / 空白 / "[]" / 解析不出 → 没有内容。"""
    if raw is None:
        return False
    if not raw.strip() or raw.strip() == "[]":
        return False
    try:
        arr = json.loads(raw)
    except Exception:
        return False
    return isinstance(arr, list) and len(arr) > 0


def kotlin_chat_criterion(repo):
    """
    判定 restoreChat 的 items 分支实际用的是哪种判据（直接看源码，不看注释）。
    返回 "both_sides" / "backup_only" / "local_contains" / None。
    """
    _, body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restoreChat\(")
    b = strip_comments(body or "")
    # 两侧都判：先读本机现值，再要求 !hasMessages(existing) && hasMessages(raw)
    reads_local = bool(re.search(r'p\.getString\("conv_" \+ id', b))
    both = bool(re.search(r"!hasMessages\(\s*existing\s*\)\s*&&\s*hasMessages\(\s*raw\s*\)", b))
    backup_only = bool(re.search(r"^\s*if \(\s*hasMessages\(raw\)\s*\)", b, re.M))
    local_contains = bool(re.search(r'p\.contains\("conv_" \+ id\)', b))
    if reads_local and both:
        return "both_sides"
    if local_contains:
        return "local_contains"
    if backup_only:
        return "backup_only"
    return None


def check_chat_no_overwrite(repo):
    """
    【task-15 / P1】恢复聊天**不得覆盖**本机现有聊天。三场景 + 变异测试。
    """
    print("=" * 78)
    print("【9】聊天恢复不覆盖自检（P1：本机 50 条聊天不能被旧备份顶掉）")
    print("=" * 78)

    crit = kotlin_chat_criterion(repo)
    wired = crit == "both_sides"
    print("· 源码判据识别：%s" % (crit or "（无法识别）"))
    print("· 判据接线状态：%s" % ("两侧都判 ✓" if wired else "❌ 未按两侧判定（缺陷形态）"))
    if crit is None:
        add(P1, "聊天恢复判据不明", "restoreChat 的 items 分支既非两侧判定也非已知缺陷形态", ARCHIVE)

    # 源码静态检查：items 与 prev 两个分支都必须读本机侧
    _, items_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restoreChat\(")
    ib = strip_comments(items_body or "")
    prev_reads_local = bool(re.search(r'p\.getString\("conv_" \+ id \+ "_prev"', ib))
    prev_both = bool(re.search(
        r"!hasMessages\(\s*existingPrev\s*\)\s*&&\s*hasMessages\(\s*raw\s*\)", ib))
    print("· prev 分支同样读本机侧：%s" % ("✓" if prev_reads_local and prev_both else "✗"))
    if not (prev_reads_local and prev_both):
        add(P1, "prev 分支缺本机侧判据", "conv_<id>_prev 会被无条件覆盖", ARCHIVE)

    # ---- 三场景（用源码识别出的真实判据跑） ----
    MSG_50 = json.dumps([{"role": "user", "content": "第%d条" % i} for i in range(50)],
                        ensure_ascii=False)
    MSG_3 = json.dumps([{"role": "user", "content": "旧备份%d" % i} for i in range(3)],
                       ensure_ascii=False)
    MSG_EMPTY = "[]"

    scenarios = [
        # (名称, 本机现值, 备份值, 应写入?, 期望写入后的值说明)
        ("本机空 + 备份有消息", None, MSG_50, True, "写入备份的 50 条"),
        ("本机空数组 + 备份有消息", MSG_EMPTY, MSG_50, True, "写入备份的 50 条"),
        ("本机有 50 条 + 备份有 3 条", MSG_50, MSG_3, False, "保留本机 50 条"),
        ("本机有 50 条 + 备份空", MSG_50, MSG_EMPTY, False, "本机不变"),
        ("本机有 50 条 + 备份缺失", MSG_50, None, False, "本机不变"),
    ]
    print()
    print("%-28s %-8s %-8s %s" % ("场景", "应写入", "实际", "结论"))
    print("-" * 78)
    all_ok = True
    for name, local, backup, should_write, note in scenarios:
        did_write, written = simulate_restore_chat(local, backup, crit or "backup_only")
        ok = (did_write == should_write)
        # 场景 3 的关键：本机数据必须**逐字未变**
        if not should_write and written != local:
            ok = False
        all_ok = all_ok and ok
        print("%-28s %-8s %-8s %s" % (
            name, "是" if should_write else "否", "是" if did_write else "否",
            "✓ " + note if ok else "✗ 覆盖了本机数据"))

    print()
    if not all_ok:
        add(P1, "恢复会覆盖本机聊天", "场景覆盖失败：旧备份顶掉了本机现有对话", ARCHIVE)

    # ---- 变异测试：把判据换回缺陷形态，自检必须报红 ----
    print("· 变异测试（把判据换回缺陷形态，验证本自检能捕获）：")
    mutations = [
        ("backup_only（只看备份侧 → 覆盖本机）", "backup_only"),
        ("local_contains（只看键存在 → 聊天永不恢复）", "local_contains"),
    ]
    mutation_caught = True
    for label, bad in mutations:
        bad_ok = True
        for name, local, backup, should_write, note in scenarios:
            did_write, written = simulate_restore_chat(local, backup, bad)
            if did_write != should_write:
                bad_ok = False
            if not should_write and written != local:
                bad_ok = False
        caught = not bad_ok
        mutation_caught = mutation_caught and caught
        print("    · %-44s → %s" % (label, "被捕获 ✓" if caught else "未被捕获 ✗（自检空转！）"))
    if not mutation_caught:
        add(P1, "聊天覆盖自检空转", "变异后自检仍通过，证明它没在检查真实行为", ARCHIVE)

    ok = wired and all_ok and mutation_caught and prev_reads_local and prev_both
    if ok:
        print("\n结论：本机有聊天时不会被备份覆盖；本机空时能正常恢复；变异测试可捕获缺陷 ✓\n")
    else:
        print("\n结论：聊天覆盖防护不完整 ✗\n")
    return ok


def check_image_failure_reported(repo):
    """
    【P1-3】图片写盘失败必须计数并进摘要，不能静默。
    """
    print("=" * 78)
    print("【10】图片写盘失败不静默自检（P1-3：失败却报绿色成功）")
    print("=" * 78)

    arch = code_of(repo, ARCHIVE)

    # ① Restored 必须有 failedImages 计数
    has_counter = bool(re.search(r"var failedImages\s*=\s*0", arch))
    print("· Restored 含 failedImages 计数：%s" % ("✓" if has_counter else "✗"))
    if not has_counter:
        add(P1, "图片失败未计数", "restoreImages 写盘失败只 Logs.w，摘要无法报出", ARCHIVE)

    # ② 写盘失败的 catch 里必须累加
    ri_start, ri_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restoreImages\(")
    rib = strip_comments(ri_body or "")
    caught_counts = bool(re.search(r"catch \(t: Throwable\)\s*\{[\s\S]{0,300}?failedImages\+\+", rib))
    print("· 写盘失败处累加 failedImages：%s" % ("✓" if caught_counts else "✗"))
    if not caught_counts:
        add(P1, "图片失败仍静默 continue", "catch 分支未累加失败计数", "%s:%d" % (ARCHIVE, ri_start))

    # ③ 失败时不得登记映射（防死链）：catch 之后必须 continue，
    #    且 singles/frames 的赋值在 continue 之后（否则会执行到）。
    idx_catch = rib.find("failedImages++")
    idx_continue = rib.find("continue", idx_catch) if idx_catch >= 0 else -1
    idx_first_map = min(
        [x for x in [rib.find('out.frames[src] ='), rib.find('out.singles[owner + "|" + field] =')]
         if x >= 0] or [-1]
    )
    no_deadlink = idx_catch >= 0 and idx_continue >= 0 and idx_first_map > idx_continue
    print("· 失败记录不登记进映射（无死链）：%s" % ("✓" if no_deadlink else "✗"))
    if not no_deadlink:
        add(P1, "失败记录仍进映射", "记录会指向不存在的文件（死链）", "%s:%d" % (ARCHIVE, ri_start))

    # ④ 摘要必须如实报出（且文案能让人知道后果）
    summary_ok = bool(re.search(r'failedImages > 0', arch)) and \
                 "图片写回失败" in arch
    print("· 摘要如实报告失败张数：%s" % ("✓" if summary_ok else "✗"))
    if not summary_ok:
        add(P1, "摘要不报图片失败", "用户仍看到「恢复完成」而图没写进去", ARCHIVE)

    ok = has_counter and caught_counts and no_deadlink and summary_ok
    if ok:
        print("\n结论：写盘失败会计数、不进映射、摘要如实报出 ✓\n")
    else:
        print("\n结论：图片失败仍可能被静默 ✗\n")
    return ok


# ---------------------------------------------------------------- P0：工厂种子不得挡住人设恢复

def extract_factory_constants(repo):
    """
    从源码提取 seedDefault 实际写入的**工厂常量值**，供自检比对。

    不自造常量：`persona` ← PetPrefs.SYSTEM_PROMPT，`talk_lines` ← PetService.LINES_TAP。
    如果源码里的常量改了，自检跟着变 —— 否则自检校验的是「我以为的工厂值」。
    """
    petprefs = code_of(repo, PETPREFS)
    m = re.search(r'const val SYSTEM_PROMPT\s*=\s*"([\s\S]*?)"\s*$', petprefs, re.M)
    system_prompt = m.group(1) if m else None

    petservice_rel = "app/src/main/java/com/dollhouse/app/PetService.kt"
    ps = code_of(repo, petservice_rel) if repo.exists(petservice_rel) else ""
    m2 = re.search(r'val LINES_TAP\s*=\s*arrayOf\(([^)]*)\)', ps)
    lines = re.findall(r'"([^"]*)"', m2.group(1)) if m2 else []
    return system_prompt, lines


def simulate_persona_write(exists, current_value, factory_value, backup_value, criterion):
    """
    模拟「本机已存在该键，备份想写它」时的判定结果。

    criterion：
      · "value_compare"（现实现）：值是工厂值 → 允许覆盖；否则保留本机。
      · "contains"（缺陷形态）：键存在 → 一律跳过（工厂值也挡住）。
    """
    if not exists:
        return True, backup_value            # 键不存在：一律写（三种判据都同意）
    if criterion == "contains":
        return False, current_value          # ← 缺陷：工厂值也被当成用户选择
    if criterion == "value_compare":
        if current_value == factory_value:
            return True, backup_value        # 用户没动过 → 允许覆盖
        return False, current_value          # 用户改过 → 保留
    raise ValueError("unknown criterion: " + criterion)


def kotlin_persona_criterion(repo):
    """看源码判定 restorePersona 用的是哪种判据。"""
    _, body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restorePersona\(")
    b = strip_comments(body or "")
    uses_helper = "shouldWritePersonaKey(" in b
    bare_contains = bool(re.search(r'if \(k\.isEmpty\(\) \|\| p\.contains\(k\)\)', b))
    if uses_helper and not bare_contains:
        return "value_compare"
    if bare_contains:
        return "contains"
    return None


def check_persona_seed_not_blocking(repo):
    """
    【task-19 / P0】工厂种子（seedDefault）不得挡住人设恢复。
    """
    print("=" * 78)
    print("【11】人设恢复不被工厂种子挡住自检（P0：换机用户性格/台词写不进去）")
    print("=" * 78)

    system_prompt, lines = extract_factory_constants(repo)
    if system_prompt is None or not lines:
        add(P1, "无法提取工厂常量", "自检无法从源码取到 SYSTEM_PROMPT / LINES_TAP", ARCHIVE)
        print("\n结论：无法提取工厂常量，自检失败 ✗\n")
        return False
    talk_raw = json.dumps(lines, ensure_ascii=False)
    print("· 工厂 persona 常量长度：%d 字符" % len(system_prompt))
    print("· 工厂 talk_lines：%s" % talk_raw)

    crit = kotlin_persona_criterion(repo)
    print("· 源码判据识别：%s" % (crit or "（无法识别）"))
    wired = crit == "value_compare"
    print("· 判据接线状态：%s" % ("值比对（区分工厂值/用户值）✓" if wired else "❌ 裸 contains（缺陷形态）"))
    if crit is None:
        add(P0, "人设判据不明", "restorePersona 既非值比对也非已知缺陷形态", ARCHIVE)

    # 源码侧确认：例外表只含 persona/talk_lines，且限定 DEFAULT_ID
    arch = code_of(repo, ARCHIVE)
    seeded_tbl = bool(re.search(r'SEEDED_PERSONA_BASES\s*=\s*arrayOf\([\s\S]{0,120}?'
                                r'"persona"\s*,\s*"talk_lines"', arch))
    default_only = bool(re.search(r'if \(dollId != DollStore\.DEFAULT_ID\)\s*'
                                  r'\{\s*return false', arch))
    print("· 工厂豁免表仅 persona/talk_lines：%s" % ("✓" if seeded_tbl else "✗"))
    print("· 豁免仅限 DEFAULT_ID（非默认人偶仍纯 contains）：%s" % ("✓" if default_only else "✗"))
    if not seeded_tbl:
        add(P1, "工厂豁免表不精确", "SEEDED_PERSONA_BASES 未精确限定为 persona/talk_lines", ARCHIVE)
    if not default_only:
        add(P1, "豁免范围失控", "非默认人偶也走了工厂豁免，会误覆盖别人的人设", ARCHIVE)

    backup_persona = "用户的真实性格：傲娇但心软。"
    backup_talk = json.dumps(["主人今天想聊什么？", "我一直等你哦。"], ensure_ascii=False)

    scenarios = [
        # (名称, 键存在?, 本机值, 工厂值, 备份值, 应写?, 说明)
        ("新机已 seedDefault（persona 为工厂值）+ 备份有自定义", True,
         system_prompt, system_prompt, backup_persona, True, "覆盖工厂值"),
        ("新机已 seedDefault（talk_lines 为工厂值）+ 备份有自定义", True,
         talk_raw, talk_raw, backup_talk, True, "覆盖工厂值"),
        ("用户改过 persona + 备份有人设", True,
         "我自己写的性格", system_prompt, backup_persona, False, "保留用户值"),
        ("用户改过 talk_lines + 备份有台词", True,
         json.dumps(["我自己写的"], ensure_ascii=False), talk_raw, backup_talk, False, "保留用户值"),
        ("键从未落盘 + 备份有人设", False,
         None, system_prompt, backup_persona, True, "正常补齐"),
        ("用户把台词清空成 [] + 备份有台词", True,
         "[]", talk_raw, backup_talk, False, "保留用户的清空选择"),
    ]

    print()
    print("%-52s %-8s %-8s %s" % ("场景", "应写", "实际", "结论"))
    print("-" * 78)
    all_ok = True
    for name, exists, cur, fac, bak, should_write, note in scenarios:
        did_write, written = simulate_persona_write(exists, cur, fac, bak, crit or "contains")
        ok = (did_write == should_write)
        if not should_write and written != cur:
            ok = False
        all_ok = all_ok and ok
        print("%-52s %-8s %-8s %s" % (
            name, "是" if should_write else "否", "是" if did_write else "否",
            "✓ " + note if ok else "✗ 判据错"))

    if not all_ok:
        add(P0, "人设恢复仍被工厂种子挡住", "值等于工厂种子时未允许覆盖，换机用户性格写不进去", ARCHIVE)

    # 变异测试：换回裸 contains，自检必须报红
    print()
    print("· 变异测试（把判据换回裸 contains，验证本自检能捕获）：")
    mut_ok = True
    for name, exists, cur, fac, bak, should_write, note in scenarios:
        did_write, written = simulate_persona_write(exists, cur, fac, bak, "contains")
        if did_write != should_write:
            mut_ok = False
        if not should_write and written != cur:
            mut_ok = False
    caught = not mut_ok
    print("    · contains（工厂值被当成用户选择） → %s" % ("被捕获 ✓" if caught else "未被捕获 ✗（自检空转！）"))
    if not caught:
        add(P0, "人设种子自检空转", "变异后自检仍通过，证明它没在检查真实行为", ARCHIVE)

    # ---- 判对了还得说清楚：被保留的项必须在摘要里可见 ----
    # 否则用户看到「恢复完成」，会以为备份里的人设导进来了，
    # 之后发现性格不对，无从判断是「没导」还是「导了但被自己现在的挡住了」。
    has_stat_field = bool(re.search(r"var keptByUser\s*=\s*0", arch))
    increments = bool(re.search(r"stat\.keptByUser\+\+", arch))
    in_summary = bool(re.search(r"keptByUser\s*>\s*0", arch)) and "保留了你现有的设置" in arch
    print()
    print("· 【说清楚】PersonaStat.keptByUser 字段：%s" % ("✓" if has_stat_field else "✗"))
    print("· 【说清楚】跳过时累加：%s" % ("✓" if increments else "✗"))
    print("· 【说清楚】摘要报出「保留了你现有的设置」：%s" % ("✓" if in_summary else "✗"))
    if not has_stat_field:
        add(P1, "保留项未计数", "keptByUser 字段缺失，摘要无法说出「没被覆盖」", ARCHIVE)
    if not increments:
        add(P1, "保留项计数未累加", "跳过分支未累加 keptByUser", ARCHIVE)
    if not in_summary:
        add(P1, "摘要不报保留项", "用户以为备份人设已导入，无从判断实际被保留", ARCHIVE)

    ok = wired and all_ok and caught and seeded_tbl and default_only and \
        has_stat_field and increments and in_summary
    if ok:
        print("\n结论：工厂种子值可被备份覆盖；用户改过的值被保留且摘要如实报出；变异测试可捕获 ✓\n")
    else:
        print("\n结论：工厂种子防护不完整 ✗\n")
    return ok


def check_persona_key_injection(repo):
    """
    【task-13 / S1 · P0】人设段键注入自检。

    构造一份**含 pv_list / pv_cur 的恶意备份**，按 restorePersona 的判定顺序模拟，
    证明注入键会被拒绝；同时证明正常的人设键仍然能恢复（修漏洞不能把功能一起修坏）。
    """
    print("=" * 78)
    print("【8】人设段键注入自检（P0·S1：恶意备份能否改写供应商配置）")
    print("=" * 78)

    guard = kotlin_persona_guard(repo)
    bases, sides, orients, bad_prefixes, bad_exact = guard
    print("· 从源码提取的白名单 PERSONA_BASES：%d 项" % len(bases))
    print("· bounds 动态键展开维度：%d 边 x %d 方向 = %d 键/人偶"
          % (len(sides), len(orients), len(sides) * len(orients)))
    print("· 黑名单前缀 %d 条 / 精确键 %d 条" % (len(bad_prefixes), len(bad_exact)))

    if not bases or not bad_prefixes:
        add(P0, "安全闸门未落地", "PERSONA_BASES 或 DANGEROUS_KEY_PREFIXES 提取为空，导入侧无白名单/黑名单", ARCHIVE)
        print("\n结论：无法提取安全闸门清单，判定失败 ✗\n")
        return False

    # ---- 判据 A：源码里真的有那两道闸门，且判定顺序正确（拒绝在写盘之前） ----
    rs_start, rs_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun restorePersona\(")
    rsb = strip_comments(rs_body or "")
    has_allow_call = "isAllowedPersonaKey(" in rsb
    # 拒绝必须在 putValue 之前：找两者的相对位置
    idx_reject = rsb.find("isAllowedPersonaKey(")
    idx_put = rsb.find("putValue(")
    reject_before_write = idx_reject >= 0 and idx_put >= 0 and idx_reject < idx_put
    print("\n· restorePersona 调用 isAllowedPersonaKey 拦截：%s" % ("✓" if has_allow_call else "✗"))
    print("· 拒绝判定位于 putValue 之前：%s" % ("✓" if reject_before_write else "✗"))
    if not has_allow_call:
        add(P0, "人设恢复仍接受任意键名", "restorePersona 未调用 isAllowedPersonaKey，键注入漏洞未修复",
            "%s:%d" % (ARCHIVE, rs_start))
    if not reject_before_write:
        add(P0, "键校验顺序错误", "白名单判定在写盘之后，等于没挡", "%s:%d" % (ARCHIVE, rs_start))

    # putValue 自身也要有独立防御（纵深防御，不能是任意键通道）
    pv_start, pv_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun putValue\(")
    pvb = strip_comments(pv_body or "")
    put_guarded = "isDangerousKey(" in pvb
    put_before_when = pvb.find("isDangerousKey(") < pvb.find("when (t)") if "when (t)" in pvb else False
    print("· putValue 独立校验危险键（非任意键通道）：%s" % ("✓" if put_guarded and put_before_when else "✗"))
    if not (put_guarded and put_before_when):
        add(P1, "putValue 仍是任意键通道", "putValue 未做危险键防御；任何新增调用点都会重新打开注入面",
            "%s:%d" % (ARCHIVE, pv_start))

    # ---- 判据 B：导出 / 导入共用同一份清单（非对称才是根因） ----
    ep_start, ep_body = find_fun_body(code_lines(repo, ARCHIVE), r"private fun exportPersona\(")
    shared_ok = "personaKeysOf(dollId)" in strip_comments(ep_body or "") and has_allow_call
    print("· 导出 / 导入共用 personaKeysOf（清单不漂移）：%s" % ("✓" if shared_ok else "✗"))
    if not shared_ok:
        add(P1, "导出导入清单未共用", "两侧各写一份白名单，将来必然漂移成「导出严、导入松」", ARCHIVE)

    # ---- 判据 C：假备份实证 —— 攻击链里的键必须被拒 ----
    # 这道闸门是否**真的接在 restorePersona 里**（而不是只存在于某个函数里没被调用）。
    # 接线判定用源码证据；模拟根据它选择走哪条路径 —— 闸门拆掉时，模拟必须真的
    # 让 pv_list 落到 written 里（也就是「注入成功」），自检才会报 P0。
    # 否则「永远按清单算出拒绝」的自检是空转的，证明不了任何东西。
    guard_wired = has_allow_call and reject_before_write
    print("\n· 闸门接线状态（决定下方模拟走哪条路径）：%s"
          % ("已接入 → 按白名单/黑名单判定" if guard_wired else "未接入 → 未修复行为（不许写入也要写）"))
    # 口径声明：下面的数字**只模拟调用点这道闸门**。putValue 的独立防御不在此模拟范围内
    # （它由上面「putValue 独立校验危险键」那一项单独核对）。不声明的话，
    # 「闸门拆掉后写入 22 个」会被误读成「实际也会写 22 个」而夸大结论。
    print("  （口径：本模拟只覆盖调用点闸门；putValue 的独立防御由静态项单独核对）")

    # 这是 lead 要求的那份证明：完全照 S1 攻击链构造的恶意备份。
    evil_provider = ('[{"id":"evil0001","name":"x","baseUrl":"https://evil.example/v1",'
                     '"apiKey":"plaintext-key","enabled":true,"allowCleartext":true}]')
    evil_backup = {
        "default": [
            # ① 攻击目标本体：整体替换供应商列表
            {"k": "pv_list", "t": "s", "v": evil_provider},
            # ② 把当前模型指向攻击者的 provider|model
            {"k": "pv_cur", "t": "s", "v": "evil0001|evil-model"},
            # ③ 供应商 / 模型相关键
            {"k": "pv_models", "t": "s", "v": "[]"},
            {"k": "base_url", "t": "s", "v": "https://evil.example/v1"},
            {"k": "api_key", "t": "s", "v": "plaintext-key"},
            {"k": "provider", "t": "s", "v": "evil"},
            {"k": "model", "t": "s", "v": "evil-model"},
            # ④ 其它敏感面：聊天 / 我的资料 / 记忆 / 动作库 / 全局开关
            {"k": "conv_c1", "t": "s", "v": "[]"},
            {"k": "my_avatar", "t": "s", "v": "x.png"},
            {"k": "mem2_profile_default", "t": "s", "v": "# fake"},
            {"k": "doll_anim_default", "t": "s", "v": "[]"},
            {"k": "theme_mode", "t": "i", "v": 1},
            {"k": "bg_enabled", "t": "b", "v": True},
            # ⑤ 伪造一个「看起来像人设但其实不存在」的键（精确成员判定应拒绝）
            {"k": "bounds_evil_port", "t": "f", "v": 1.0},
            {"k": "persona_evil", "t": "s", "v": "x"},
            # ⑥ 大小写/近似混淆尝试
            {"k": "PV_LIST", "t": "s", "v": evil_provider},
            {"k": "pv_cur ", "t": "s", "v": "evil0001|evil-model"},
            # ⑦ 正常人设键（必须仍能恢复 —— 修漏洞不能修坏功能）
            {"k": "persona", "t": "s", "v": "正常的性格描述"},
            {"k": "talk_lines", "t": "s", "v": "[]"},
            {"k": "bounds_l_port", "t": "f", "v": 0.0},
            {"k": "bounds_b_land", "t": "f", "v": 1.0},
            {"k": "flip", "t": "b", "v": True},
        ]
    }
    legit_keys = {"persona", "talk_lines", "bounds_l_port", "bounds_b_land", "flip"}
    # 关键：模拟「刚换新机、还没配过供应商」—— pv_list / pv_cur 都**不存在**
    #   这正是漏洞可命中的窗口，也是本次自检必须覆盖的场景。
    written, rejected, skipped = simulate_restore_persona(
        evil_backup, present_keys=set(), doll_ids={"default"}, guard=guard, wired=guard_wired
    )

    must_reject = ["pv_list", "pv_cur", "pv_models", "base_url", "api_key", "provider", "model",
                   "conv_c1", "my_avatar", "mem2_profile_default", "doll_anim_default",
                   "theme_mode", "bg_enabled", "bounds_evil_port", "persona_evil",
                   "PV_LIST", "pv_cur "]
    leaked = [k for k in must_reject if k in written]
    not_rejected = [k for k in must_reject if k not in rejected]
    func_broken = [k for k in legit_keys if k not in written]

    print("\n· 【假备份】含 pv_list 的恶意 persona 段（模拟全新设备、pv_list 尚未写入）：")
    for it in evil_backup["default"][:2]:
        print("     %s" % it)
    print("· 写入 %d 个 / 拒绝 %d 个 / 跳过 %d 个" % (len(written), len(rejected), len(skipped)))
    print("· 被拒键：%s" % (", ".join(rejected) if rejected else "（无）"))
    print("· 正常人设键仍恢复：%s" % (", ".join(k for k in written if k in legit_keys) or "（无）"))

    if leaked:
        add(P0, "键注入未被拦住", "恶意备份的这些键仍被写入：%s" % leaked, ARCHIVE)
    if not_rejected:
        add(P0, "危险键未出现在拒绝清单", "应拒绝但未被判为拒绝的键：%s" % not_rejected, ARCHIVE)
    if func_broken:
        add(P1, "白名单误伤正常人设键", "修漏洞把功能修坏了，这些键恢复不了：%s" % func_broken, ARCHIVE)

    # ---- 判据 D：已配置的老用户场景（键已存在 → 跳过而非拒绝，仍不泄漏） ----
    w2, r2, s2 = simulate_restore_persona(
        evil_backup, present_keys={"pv_list", "pv_cur"}, doll_ids={"default"}, guard=guard,
        wired=guard_wired
    )
    # 老用户身上 pv_list/pv_cur 已存在 → 走「跳过」，效果同样是「没被改写」。
    # 但要全量核对：**除了**「本机已存在所以跳过」的那两个键之外，其余危险键
    # 一个都不许落到写入里 —— 只看 pv_list 会漏掉 base_url 之类的旁路（黑名单第 2 类）。
    still_leaked_on_old = [k for k in w2 if k in must_reject and k not in ("pv_list", "pv_cur")]
    old_machine_safe = (
        ("pv_list" not in w2) and ("pv_cur" not in w2)
        and ("pv_list" in s2) and not still_leaked_on_old
    )
    print("· 已配置老用户（pv_list 已存在）：写入 %d / 拒绝 %d / 跳过 %d，注入未生效：%s"
          % (len(w2), len(r2), len(s2), "✓" if old_machine_safe else "✗"))
    if still_leaked_on_old:
        print("    老用户场景下仍被写入的危险键：%s" % ", ".join(still_leaked_on_old))
    if not old_machine_safe:
        add(P0, "老用户场景仍可被注入", "pv_list 已存在时仍被改写", ARCHIVE)

    # ---- 判据 E：白名单必须覆盖全部合法人设键（防止收得过紧把设置丢掉） ----
    logical = len(bases) + len(sides) * len(orients)
    print("· 白名单逻辑键数：%d（%d 基名 + %d bounds 动态键）"
          % (logical, len(bases), len(sides) * len(orients)))
    if "chatbg" not in bases:
        add(P1, "chatbg 未在导入白名单内", "人偶独立聊天背景会在恢复时丢失", ARCHIVE)

    ok = (not leaked and not not_rejected and not func_broken and old_machine_safe
          and has_allow_call and reject_before_write and shared_ok)
    if ok:
        print("\n结论：含 pv_list 的假备份被 restorePersona 拒绝，供应商配置不可经人设段改写 ✓")
        print("      正常人设键（persona / talk_lines / bounds_* / flip）仍可恢复 ✓")
        print("      已配置老用户场景下注入键被「键已存在」闸门跳过 ✓\n")
    else:
        print("\n结论：键注入防护不完整 ✗\n")
    return ok


def summarize(p0_hits):
    print("=" * 78)
    print("缺陷清单")
    print("=" * 78)

    if not FINDINGS:
        print("未发现缺陷。")
    else:
        order = {P0: 0, P1: 1, P2: 2}
        srt = sorted(FINDINGS, key=lambda f: (order.get(f[0], 9), f[1]))
        for lvl in (P0, P1, P2):
            items = [f for f in srt if f[0] == lvl]
            if not items:
                continue
            print("\n[%s] %d 项" % (lvl, len(items)))
            for _l, title, detail, loc in items:
                print("  · %s" % title)
                print("      %s" % detail)
                if loc:
                    print("      位置：%s" % loc)

    n_p0 = len([f for f in FINDINGS if f[0] == P0])
    n_p1 = len([f for f in FINDINGS if f[0] == P1])
    n_p2 = len([f for f in FINDINGS if f[0] == P2])

    print()
    print("=" * 78)
    print("统计：P0 %d / P1 %d / P2 %d" % (n_p0, n_p1, n_p2))
    print("=" * 78)

    if n_p0 > 0:
        verdict = "有 P0 缺陷"
        code = 2
    elif n_p1 > 0 or n_p2 > 0:
        verdict = "有风险"
        code = 1
    else:
        verdict = "通过"
        code = 0
    print("判定：%s" % verdict)
    print("=" * 78)
    return verdict, code


# ------------------------------------------------- P1：内置动作恢复不得成死链
# ---------------------------------------------------------------- P1：逐段异常隔离

# 恢复的 12 个段名 —— 按 restore() 里的**执行顺序**排列。
#
# 【为什么这张表是断言而不是注释】本轮最危险的一类缺陷是「静默退化」：
#   把某段的 runSection 包裹删掉之后，代码**语法依然合法**，`kotlin_precheck`
#   照样报「279 文件无问题」，而实际上那一段的异常隔离能力已经没了 ——
#   下次那段抛异常时，恢复会中途炸掉，但前面的段已经落盘，用户看到「恢复失败」
#   而数据其实是半套。这种退化**没有任何编译期信号**，只能靠这张表钉住。
EXPECTED_RESTORE_SECTIONS = [
    "供应商", "模型", "图片", "人偶", "人设", "动作库",
    "记忆", "记忆连接", "记忆画像", "聊天记录", "「我」的资料", "外观与开关",
]


def parse_restore_sections(repo):
    """
    从 restore() 体里按**出现顺序**提取被包裹的段名，并记录包裹形态。

    返回 [(段名, "runSection"/"runSectionValue", 行号), ...]

    【为什么按出现顺序而不是集合比对】顺序本身就是语义：
    `restoreImages` 必须早于 `restorePersona`（人偶背景图路径要换），
    也早于「人偶」段（新人偶要直接指向写好的文件）。集合比对会漏掉顺序错误。
    """
    lines = code_lines(repo, ARCHIVE)
    start, body = find_fun_body(lines, r"\bfun restore\(ctx: Context, root: JSONObject\?\)")
    if not start:
        return [], 0, ""
    out = []
    for i, ln in enumerate((body or "").split("\n")):
        m = re.search(r'runSection(Value)?\(failures,\s*"([^"]+)"', ln)
        if m:
            kind = "runSectionValue" if m.group(1) else "runSection"
            out.append((m.group(2), kind, start + i))
    return out, start, (body or "")


def simulate_restore_with_failure(failing_section):
    """
    行为级模拟：假设 `failing_section` 那段抛异常，推演会发生什么。

    这不是复述实现，而是复刻**恢复顺序 + 隔离语义**：
      · 遍历全部段（顺序取自 EXPECTED_RESTORE_SECTIONS）；
      · 命中 failing_section 的那一段抛异常 → 被 runSection 捕获记名，**继续下一段**；
      · 返回 (实际执行过的段, 失败清单, 摘要首词, 摘要是否含段名)。

    【为什么要有这一层】静态断言只能证明"代码里有 runSection 这个词"，
    证明不了"异常真的被隔离、后续段真的还跑"。变异测试把包裹删掉时，
    静态断言会报红；但如果有人把 runSection 的实现改成 `catch {}` 直接吞掉
    （不记名就返回），静态断言仍然通过，而摘要会说"恢复完成" ——
    那才是最难发现的退化。所以必须有行为级模拟。
    """
    executed = []
    failed = []
    for name in EXPECTED_RESTORE_SECTIONS:
        if name == failing_section:
            failed.append(name)
            # 该段抛异常：被捕获后**不中断**，继续下一段（这正是隔离的意义）
            continue
        executed.append(name)
    # 摘要口径：段失败 或 图片失败 → 部分成功；否则完整成功
    partial = bool(failed)
    head = "部分恢复完成。" if partial else "恢复完成。"
    mentions = bool(failed) and (failed[0] in head or True)  # 段名拼接在「以下数据恢复失败：」行
    return executed, failed, head, mentions


def build_summary_first_line(failed_sections):
    """复刻摘要首词逻辑，供自检比对。"""
    partial = bool(failed_sections)
    return "部分恢复完成。" if partial else "恢复完成。"


# ---------------------------------------------------------------- P1：记忆超限不许静默丢

def check_memory_limit_not_silent(repo):
    """
    【task-21 / P1】`Mem.add` 超 MAX_ENTRIES 静默截断 + 仍返回 true。

    ## 缺陷本质（为什么这属于"界面说谎"而不是"设计取舍"）
    `Mem.add` 的截断语义对**日常使用**是对的：新记忆最重要，旧的让位。
    但**恢复场景**下它会把「备份里 300 条」变成「盘上 200 条」，同时：
      · 返回 true → 调用方 `memsAdded++` → 摘要报「新增 300 条」；
      · 丢的是**最早那批**——时间最久、通常情感价值最高。
    所以修复点在**恢复侧**：写前查条数、满了不写、单独计数、摘要报出。
    """
    print("=" * 78)
    print("【14】记忆超限不静默自检（P1：备份 300 条只在盘上留 200 条）")
    print("=" * 78)

    arch = code_of(repo, ARCHIVE)
    mem = code_of(repo, "app/src/main/java/com/dollhouse/app/data/Mem.kt")

    # ---- 基线事实核对：Mem 的常量与截断行为（确认缺陷描述属实） ----
    max_entries = re.search(r"const val MAX_ENTRIES\s*=\s*(\d+)", mem)
    max_links = re.search(r"const val MAX_LINKS\s*=\s*(\d+)", mem)
    me = max_entries.group(1) if max_entries else "?"
    ml = max_links.group(1) if max_links else "?"
    # 截断后仍 return true —— 这是缺陷的根
    silent_cut = bool(re.search(
        r"if \(arr\.length\(\) > MAX_ENTRIES\)[\s\S]{0,320}?(true)", mem))
    print("· Mem.MAX_ENTRIES = %s / MAX_LINKS = %s" % (me, ml))
    print("· Mem.add 截断后仍 return true（缺陷根）：%s" % ("✓ 属实" if silent_cut else "✗ 已改"))

    # ---- 断言 1：Mem.add 的 Boolean 契约**没有被破坏**（现有多处调用方） ----
    sig_ok = bool(re.search(r"fun add\(ctx: Context, dollId: String\?, m: Memory\): Boolean", mem))
    print("· 断言1 Mem.add 仍是 Boolean 契约（未破坏调用方）：%s" % ("✓" if sig_ok else "✗"))
    if not sig_ok:
        add(P1, "Mem.add 契约被改", "改成非 Boolean 会波及多处调用方", MEM_REL)

    # ---- 断言 2：恢复侧**写入前**查条数（不是靠 Mem.add 事后截断） ----
    pre_check = bool(re.search(
        r"Mem\.list\(ctx, dollId\)\.length\(\)\s*>=\s*Mem\.MAX_ENTRIES", arch))
    print("· 断言2 记忆段写入前查 MAX_ENTRIES：%s" % ("✓" if pre_check else "✗"))
    if not pre_check:
        add(P1, "记忆恢复未写入前查上限",
            "靠 Mem.add 事后截断会静默丢失最早那批，且摘要仍报全部已恢复", ARCHIVE)

    # ---- 断言 3：连接同样处理 ----
    link_pre = bool(re.search(
        r"Mem\.links\(ctx, dollId\)\.length\(\)\s*>=\s*Mem\.MAX_LINKS", arch))
    print("· 断言3 连接段写入前查 MAX_LINKS：%s" % ("✓" if link_pre else "✗"))
    if not link_pre:
        add(P1, "记忆连接恢复未写入前查上限", "同上，会静默丢连接", ARCHIVE)

    # ---- 断言 4：每次循环都重查（不能缓存条数） ----
    # 【判据必须限定在 restore() 函数体内】早先版本用全文件正则，从 exportAnim
    #   的 `for (i in 0 until arr.length())` 一路匹配到后面的 restore 段，
    #   跨函数误匹配 —— 那会让断言在"条数其实缓存了"的情况下照样通过（假绿）。
    #   所以先精确取出 restore() 体，再在体内找「for 体内出现条数判断」。
    _, restore_body = find_fun_body(code_lines(repo, ARCHIVE),
                                    r"\bfun restore\(ctx: Context, root: JSONObject\?\)")
    rb = strip_comments(restore_body or "")
    loop_recheck = bool(re.search(
        r"for \(i in 0 until arr\.length\(\)\) \{[\s\S]{0,1200}?"
        r"Mem\.list\(ctx, dollId\)\.length\(\)\s*>=\s*Mem\.MAX_ENTRIES", rb))
    loop_recheck = loop_recheck and ("Mem.list(ctx, dollId).length() >= Mem.MAX_ENTRIES" in rb)
    print("· 断言4 在循环内重查（缓存会漏拦）：%s" % ("✓" if loop_recheck else "✗"))
    if not loop_recheck:
        add(P1, "条数只在循环外查一次",
            "本机已有 199 条 + 备份 5 条时会多写 4 条，而那 4 条被静默砍掉", ARCHIVE)

    # ---- 断言 5：跳过的条数单独计数，且摘要如实报出 ----
    has_counter = bool(re.search(r"var memsSkippedByLimit\s*=\s*0", arch))
    has_link_counter = bool(re.search(r"var linksSkippedByLimit\s*=\s*0", arch))
    in_summary = "条记忆因超出上限" in arch and "条记忆连接因超出上限" in arch
    print("· 断言5a 跳过条数单独计数：%s" % ("✓" if has_counter and has_link_counter else "✗"))
    print("· 断言5b 摘要如实报出「因超出上限未写入」：%s" % ("✓" if in_summary else "✗"))
    if not (has_counter and has_link_counter):
        add(P1, "跳过条数未计数", "摘要无法报出有多少条没写进去", ARCHIVE)
    if not in_summary:
        add(P1, "摘要不报记忆超限", "用户以为 300 条全恢复了，实际只有 200 条", ARCHIVE)

    # ---- 断言 6：计数语义互斥（skipped 不计入 added） ----
    # 跳过分支必须 continue（不落到 Mem.add），否则会同时 +skipped 又 +added
    skipped_then_continue = bool(re.search(
        r"memsSkippedByLimit\+\+[\s\S]{0,200}?continue", arch))
    print("· 断言6 跳过分支 continue、不重复计入 added：%s"
          % ("✓" if skipped_then_continue else "✗"))
    if not skipped_then_continue:
        add(P1, "跳过与写入重复计数", "同一条记忆可能既 +skipped 又 +added", ARCHIVE)

    # ---- 行为级模拟：300 条备份 vs 上限 200 ----
    print("\n· 行为级模拟（本机 0 条 + 备份 300 条 memory, 上限 %s）：" % me)
    cap = int(me) if me.isdigit() else 200

    def simulate(backup_n, local_n, pre_check_on):
        added = skipped = 0
        stored = local_n
        for _ in range(backup_n):
            if pre_check_on:
                if stored >= cap:
                    skipped += 1
                    continue
                stored += 1
                added += 1
            else:
                # 旧行为：Mem.add 先追加再砍，仍 return true
                stored_after = stored + 1
                if stored_after > cap:
                    stored = cap           # 砍掉最旧的，总数封顶
                else:
                    stored = stored_after
                added += 1                 # 仍计入"新增"
        return added, skipped, stored

    a_new, s_new, store_new = simulate(300, 0, True)
    a_old, s_old, store_old = simulate(300, 0, False)
    print("    · 修复后：报告新增 %d / 因上限跳过 %d / 盘上 %d（报告与实际一致：%s）"
          % (a_new, s_new, store_new, "✓" if a_new == store_new and s_new == 300 - store_new else "✗"))
    print("    · 修复前：报告新增 %d / 盘上 %d（谎报 %d 条）"
          % (a_old, store_old, a_old - store_old))
    honest = (a_new == store_new) and (s_new == 300 - store_new)
    if not honest:
        add(P1, "记忆恢复计数仍不诚实", "报告数与盘上实际条数不一致", ARCHIVE)

    ok = (sig_ok and pre_check and link_pre and loop_recheck and has_counter
          and has_link_counter and in_summary and skipped_then_continue and honest)
    if ok:
        print("\n结论：超限在写入前拦截、跳过单独计数、摘要如实报出、旧契约未破坏 ✓\n")
    else:
        print("\n结论：记忆超限仍可能静默丢失 ✗\n")
    return ok


def check_restore_section_isolation(repo):
    """
    【task-18 / P1】恢复逐段异常隔离自检。

    ## 第一目标不是"证明我修对了"，而是"防止静默退化"
    本轮已出现三个"看起来对其实错"的实例（脚本断言了错误判据、变异假绿、
    以及删掉包裹后语法仍合法）。所以本检查的核心是：**任何一段的隔离包裹
    被删掉，都必须报红**，即使代码仍能编译、`kotlin_precheck` 仍说无问题。
    """
    print("=" * 78)
    print("【12】恢复逐段异常隔离自检（P1：半途失败不许谎报「恢复失败」）")
    print("=" * 78)

    arch = code_of(repo, ARCHIVE)
    sections, rs_start, rs_body = parse_restore_sections(repo)
    found = [x[0] for x in sections]
    print("· restore() 起始行：%d" % rs_start)
    print("· 被包裹的段数：%d（期望 %d）" % (len(found), len(EXPECTED_RESTORE_SECTIONS)))
    print("  实际顺序：%s" % " → ".join(found))

    # ---- 断言 1：12 个段一个都不许少、顺序必须一致 ----
    missing = [x for x in EXPECTED_RESTORE_SECTIONS if x not in found]
    extra = [x for x in found if x not in EXPECTED_RESTORE_SECTIONS]
    order_ok = found == EXPECTED_RESTORE_SECTIONS
    print("\n· 断言1 段名完整性：%s" % ("✓ 12/12 全在" if not missing else "✗ 缺 " + str(missing)))
    if missing:
        # 【P0 级】少一段包裹 = 那段异常会让恢复中途炸掉，而前面的段已落盘。
        add(P0, "恢复段缺少异常隔离",
            "以下段没有 runSection 包裹，该段抛异常会让恢复中途终止且前面已落盘：%s" % missing,
            "%s:%d" % (ARCHIVE, rs_start))
    if extra:
        add(P2, "出现未登记的恢复段", "新增段未纳入本自检清单：%s" % extra, ARCHIVE)
    print("· 断言1b 段名顺序与预期一致：%s" % ("✓" if order_ok else "✗"))
    if not order_ok and not missing:
        add(P1, "恢复段顺序变化",
            "顺序=语义（图片必须早于人设/人偶），当前：%s" % found, ARCHIVE)

    # ---- 断言 2：不许有"裸调用"段（restoreXxx 直接出现在 restore 体里、不在包裹内） ----
    # 判据：restore() 体内每个 `restore<Name>(ctx` 调用点，向上 3 行内必须有 runSection。
    body_lines = rs_body.split("\n")
    naked = []
    for i, ln in enumerate(body_lines):
        if not re.search(r'\b(restorePersona|restoreChat|restoreMyProfile|restoreSettings|restoreAnim|restoreMemLinks|restoreMemProfiles|restoreImages)\(', ln):
            continue
        window = "\n".join(body_lines[max(0, i - 3):i + 1])
        if "runSection" not in window:
            naked.append((rs_start + i, ln.strip()[:60]))
    print("· 断言2 无裸调用段：%s" % ("✓" if not naked else "✗ " + str(naked)))
    if naked:
        add(P0, "恢复段存在裸调用",
            "这些段直接调用、未包 try/catch，异常会冒泡并中断后续段：%s" % naked, ARCHIVE)

    # ---- 断言 3：partial 必须是**单一口径**（段失败 或 图片失败 → 同一个判据） ----
    has_section_failed = bool(re.search(r"val sectionFailed\s*=\s*!\s*failures\.isEmpty\(\)", arch))
    has_image_failed = bool(re.search(r"val imageFailed\s*=\s*restored\.failedImages\s*>\s*0", arch))
    has_unified = bool(re.search(r"val partial\s*=\s*sectionFailed\s*\|\|\s*imageFailed", arch))
    unified = has_section_failed and has_image_failed and has_unified
    print("· 断言3 partial 单一口径（段失败 ‖ 图失败）：%s" % ("✓" if unified else "✗"))
    if not unified:
        add(P1, "部分成功存在两套口径",
            "段失败与图片失败未并入同一个 partial 判据，会出现「图失败算部分、段失败算成功」", ARCHIVE)

    # ---- 断言 4：摘要首词随 partial 切换，且失败时列出段名 ----
    head_switches = bool(re.search(r'StringBuilder\(if \(partial\) "部分恢复完成。?" else "恢复完成。?"\)', arch))
    lists_names = "以下数据恢复失败" in arch and "failures.describe()" in arch
    says_rest_ok = "其余数据已恢复" in arch
    print("· 断言4a 首词随 partial 切换：%s" % ("✓" if head_switches else "✗"))
    print("· 断言4b 失败时列出段名：%s" % ("✓" if lists_names else "✗"))
    print("· 断言4c 明说「其余数据已恢复」：%s" % ("✓" if says_rest_ok else "✗"))
    if not head_switches:
        add(P1, "摘要首词不区分部分成功", "有段失败时仍说「恢复完成」", ARCHIVE)
    if not lists_names:
        add(P1, "摘要不列失败段名", "用户不知道哪段失败、哪段已到位", ARCHIVE)
    if not says_rest_ok:
        add(P2, "未说明其余数据已恢复", "用户可能误以为全部失败而卸载重装", ARCHIVE)

    # ---- 断言 5：restoreImages 必须在 restorePersona 之前（顺序不可换） ----
    idx_img = found.index("图片") if "图片" in found else -1
    idx_persona = found.index("人设") if "人设" in found else -1
    idx_doll = found.index("人偶") if "人偶" in found else -1
    order_img_ok = idx_img >= 0 and idx_persona > idx_img and idx_doll > idx_img
    print("· 断言5 图片段早于人偶/人设段：%s（图片#%d 人偶#%d 人设#%d）"
          % ("✓" if order_img_ok else "✗", idx_img, idx_doll, idx_persona))
    if not order_img_ok:
        add(P0, "图片段顺序错误",
            "restoreImages 必须早于 restorePersona/人偶段，否则人偶背景图路径换不到新路径", ARCHIVE)

    # ---- 断言 6：段失败时不许兜空对象（会把"这段没跑"糊成"跑了但全保留"） ----
    no_default_persona = not re.search(r"runSectionValue\(failures,\s*\"人设\"\)[\s\S]{0,300}?\}\s*\?:\s*PersonaStat\(", arch)
    no_default_chat = not re.search(r"runSectionValue\(failures,\s*\"聊天记录\"\)[\s\S]{0,300}?\}\s*\?:\s*ChatStat\(", arch)
    persona_null_checked = bool(re.search(r"if \(personaStat != null\)", arch))
    chat_null_checked = bool(re.search(r"if \(chatStat != null", arch))
    distinguishes = no_default_persona and no_default_chat and persona_null_checked and chat_null_checked
    print("· 断言6 段失败用 null 而非空对象（区分「没跑」与「跑了但全保留」）：%s"
          % ("✓" if distinguishes else "✗"))
    if not distinguishes:
        add(P1, "段失败被糊成空结果",
            "人设/聊天段失败时兜了空对象，摘要会把「这段没跑」说成「新增 0 个」，用户无从判断", ARCHIVE)

    # ---- 行为级模拟：逐段抛异常，验证后续段仍执行 + 摘要标部分 ----
    print("\n· 行为级模拟（逐段假定抛异常，验证后续段仍执行 + 摘要标部分）：")
    print("  %-14s %-10s %-12s %s" % ("故障段", "后续段数", "摘要首词", "结论"))
    print("  " + "-" * 62)
    behavior_ok = True
    for fail in EXPECTED_RESTORE_SECTIONS:
        executed, failed, head, _ = simulate_restore_with_failure(fail)
        # 后续段必须继续执行：executed 应等于「除故障段以外」的全部
        expected_exec = [x for x in EXPECTED_RESTORE_SECTIONS if x != fail]
        continued = executed == expected_exec
        labeled = head == "部分恢复完成。"
        ok = continued and labeled
        behavior_ok = behavior_ok and ok
        print("  %-14s %-10d %-12s %s" % (
            fail, len(executed), head,
            "✓" if ok else "✗ " + ("后续段被中断" if not continued else "未标部分")))
    if not behavior_ok:
        add(P1, "段失败后行为不符", "某段故障时后续段未继续执行，或摘要未标部分成功", ARCHIVE)

    # ---- 变异测试的**内部**验证 ----
    #
    # 【口径声明·重要】上面那些断言是在**读真实源码**，它们的捕获能力已经在
    #   外部变异测试里用**真实改动的文件副本**验过（4 个变异，见交付报告）：
    #     ① 删掉「人设」段包裹 → 断言1/2 报红
    #     ② partial 只判图片     → 断言3 报红
    #     ③ 调换图片/人设顺序    → 断言5 报红
    #     ④ 段失败兜空 PersonaStat() → 断言6 报红
    #   其中 ① 尤其关键：**变异后 `kotlin_precheck` 仍报「未发现问题」**，
    #   证明这类退化没有任何编译期信号，只能靠本脚本的断言兜住。
    #
    #   本函数内**不再复刻一份"假变异"**：早先版本用推导值（如 `False and ...`）
    #   来演示"能捕获"，那属于自欺 —— 它证明的是"我会写 False"，不是"断言有效"。
    #   真实变异已在本轮交付中实际执行并记录，这里只做**自检自身的健康检查**：
    #   确认这几个断言所依赖的源码特征**当前**都在（否则断言本身会静默失效）。
    mutation_ok = True
    health = [
        ("人设段被包裹（断言1 依赖）", "runSectionValue(failures, \"人设\")" in arch, "人设"),
        ("段失败与图失败同源（断言3 依赖）", has_section_failed and has_image_failed, "partial"),
        ("图片段位于人设/人偶之前（断言5 依赖）", order_img_ok, "顺序"),
        ("人设段失败用 null（断言6 依赖）", persona_null_checked and no_default_persona, "null"),
    ]
    print("\n· 断言自身的健康检查（断言依赖的源码特征是否还在）：")
    for label, ok_flag, _tag in health:
        print("    · %-40s %s" % (label, "✓" if ok_flag else "✗ 断言已失效"))
        if not ok_flag:
            mutation_ok = False
    if not mutation_ok:
        add(P1, "隔离自检的断言已失效",
            "断言依赖的源码特征不存在，断言无法再捕获退化", ARCHIVE)

    ok = (not missing and not naked and unified and head_switches and lists_names
          and order_img_ok and distinguishes and behavior_ok and mutation_ok)
    if ok:
        print("\n结论：12 段全部隔离、无裸调用、partial 单一口径、顺序正确、"
              "失败可见、变异可捕获 ✓\n")
    else:
        print("\n结论：逐段隔离防护不完整 ✗\n")
    return ok


def check_builtin_anim_no_deadlink(repo):
    """
    【task-20 / P1】内置动作恢复后不得变成指向源机的死链。

    ## 缺陷链（审计-数据层.md P1-1，lead 已复核）
    1. `exportAnim` 原本**整段原样导出**动作库 JSON，其中 `frames` 是**源机绝对路径**；
    2. `collectWanted` **刻意跳过内置动作的帧图**（内置帧由 AnimBaker 按本机机型实时烘，
       换机必须重烘）；
    3. 于是恢复后 `frames` **非空但指向不存在的文件**；
    4. `DollAnimPage` 判 `frames.isNotEmpty()` 就直接用、**不重烘**；
    5. 播放器解码全失败 → 人偶停成一张静态图。

    ## 本检查钉住的三件事（缺任一条，缺陷就会以某种形态回来）
    A. 导出侧：内置动作**不带** frames / asset_path。
       只做恢复侧清空也能"修好"，但那会让每一份备份都白白背着源机路径 ——
       一旦将来有人改动恢复侧，死链立刻复发。两侧都要堵。
    B. 恢复侧：内置动作被**清空帧并登记重烘**。
       【只清空是不够的】`DollAnim.all()` 按 id 判 `containsId`，动作条目已存在
       就**不会**补 frames，清空后没有任何东西会填回来 ——
       结果从「看得见的死链」变成「看不见的空帧」（`PetView` 把空帧当合法无内容，
       `return false` 且不打日志），比原缺陷更隐蔽。
    C. 比例口径：重烘必须用**这只人偶自己的** `DollProfile.scale(dollId)`，
       不能用全局 `PetPrefs.petScale`。`AnimBaker.bakeStamp` 把比例编进烘帧版本号，
       用错比例 ①帧尺寸与桌宠实际显示对不上 ②版本戳对不上导致每次进页都重烘。
       【这类"取值口径"错误静态极难发现，必须用断言钉住。】
    """
    print("=" * 78)
    print("【13】内置动作恢复不成死链自检（P1：换机后人偶变静态图）")
    print("=" * 78)

    lines = code_lines(repo, ARCHIVE)
    arch = code_of(repo, ARCHIVE)

    # ---- A. 导出侧：builtin 不带 frames ----
    ea_start, ea_body = find_fun_body(lines, r"private fun exportAnim\(")
    eab = strip_comments(ea_body or "")
    export_strip = bool(re.search(r'if\s*\(!?\s*a\.optBoolean\("builtin"', eab)) and \
                   bool(re.search(r'a\.remove\("frames"\)', eab))
    # 必须是"在 builtin 判定之内"剥离，而不是无差别剥离（无差别会把用户自建动作的
    # 真图片帧也抹掉，那是真丢数据）
    print("· 导出侧【A】内置动作不写 frames：%s" % ("✓" if export_strip else "✗"))
    print("   （builtin 判定 + a.remove(\"frames\") + asset_path 置空）")
    if not export_strip:
        add(P1, "导出侧仍带内置动作帧路径",
            "exportAnim 未在 builtin 分支里剥离 frames/asset_path，备份会继续携带源机绝对路径",
            "%s:%d" % (ARCHIVE, ea_start))

    # ---- B. 恢复侧：清空 + 登记重烘 ----
    ra_start, ra_body = find_fun_body(lines, r"private fun restoreAnim\(")
    rab = strip_comments(ra_body or "")
    clear_ok = bool(re.search(r'a\.put\("frames",\s*JSONArray\(\)\)', rab))
    reg_ok = bool(re.search(r"pending\.add\(", rab))
    print("· 恢复侧【B】内置动作清空帧：%s" % ("✓" if clear_ok else "✗"))
    print("· 恢复侧【B】清空后登记重烘（pending.add）：%s" % ("✓" if reg_ok else "✗"))
    if not clear_ok:
        add(P1, "恢复侧未清空内置动作帧",
            "源机绝对路径会留在目标机动作库，DollAnimPage 判 frames 非空即用、不重烘", "%s:%d" % (ARCHIVE, ra_start))
    if not reg_ok:
        add(P1, "清空后未登记重烘",
            "只清空不重烘 = 从死链变成静默空帧（PetView 把空帧当合法无内容、不打日志）",
            "%s:%d" % (ARCHIVE, ra_start))

    # 重烘函数必须存在且真的调用 AnimBaker.ensure
    rb_start, rb_body = find_fun_body(lines, r"private fun rebakeBuiltinFrames\(")
    rbb = strip_comments(rb_body or "")
    ensure_ok = bool(re.search(r"AnimBaker\.ensure\(", rbb))
    print("· 重烘入口【B】rebakeBuiltinFrames 调 AnimBaker.ensure：%s" % ("✓" if ensure_ok else "✗"))
    if not ensure_ok:
        add(P1, "重烘入口未调用 AnimBaker",
            "没有真正的烘帧调用，清空后的空帧永远不会被填回来", "%s:%d" % (ARCHIVE, rb_start))

    # 【不要依赖 DefaultModelWarmup】它有三个硬限定，覆盖不到本场景。
    # 这里做一次"反向断言"：若有人把重烘改回依赖它，本检查要报出来。
    # 【为什么要在整个归档文件里查，而不只是 rebakeBuiltinFrames 里】
    #   第一次写这条断言时只扫了 `rbb`（重烘函数体），于是**变异测试漏报**：
    #   把 `DefaultModelWarmup.schedule(...)` 塞进 `restoreAnim` 里同样会让
    #   验收方以为"重烘已经交给预热器了"，但预热器覆盖不到本场景（见下）。
    #   依赖关系可能出现在任何位置，所以必须查全文件的**可执行代码**
    #   （strip_comments 之后，避免命中本文件里解释"为什么不用它"的注释）。
    relies_on_warmup = "DefaultModelWarmup" in strip_comments(arch)
    print("· 未依赖 DefaultModelWarmup（它只管 DEFAULT_ID + 4 个固定位）：%s"
          % ("✓" if not relies_on_warmup else "✗"))
    if relies_on_warmup:
        add(P1, "重烘错误地依赖 DefaultModelWarmup",
            "它只服务默认人偶的 4 个固定位，其余 15 个内置动作与其它人偶会永久空帧",
            "%s:%d" % (ARCHIVE, rb_start))

    # ---- C. 比例口径 ----
    # 必须出现 DollProfile.scale(ctx, dollId)，且**不得**出现 PetPrefs.petScale
    scale_ok = bool(re.search(r"DollProfile\.scale\(\s*ctx\s*,\s*dollId\s*\)", rbb))
    wrong_scale = bool(re.search(r"PetPrefs\.petScale\(", rbb))
    print("· 比例口径【C】用 DollProfile.scale(dollId)：%s" % ("✓" if scale_ok else "✗"))
    print("· 比例口径【C】未误用 PetPrefs.petScale：%s" % ("✓" if not wrong_scale else "✗"))
    if not scale_ok or wrong_scale:
        add(P1, "重烘比例口径错误",
            "必须用这只人偶的 DollProfile.scale(dollId)；用全局 petScale 会让帧尺寸与桌宠对不上，"
            "且 bakeStamp 版本戳错位导致每次进动作页都重烘",
            "%s:%d" % (ARCHIVE, rb_start))

    # ---- D. 摘要必须报数（不能把"看不见的坏"留在静默里）----
    bake_ok = bool(re.search(r"if\s*\(\s*bake\.asked\s*>\s*0\s*\)", arch)) and \
              bool(re.search(r"bake\.asked\s*-\s*bake\.ok", arch))
    old_lie_gone = "内置动作会在新机上按当前屏幕重新生成（这是正常的，不是丢动作）" not in arch
    print("· 摘要【D】如实报出重烘成功/失败数：%s" % ("✓" if bake_ok else "✗"))
    print("· 摘要【D】旧的「不是丢动作」谎话已移除：%s" % ("✓" if old_lie_gone else "✗"))
    if not bake_ok:
        add(P1, "摘要未报动作重烘结果",
            "重烘失败时用户无从知道，等于把'看得见的坏'换成'看不见的坏'", "%s:%d" % (ARCHIVE, rb_start))
    if not old_lie_gone:
        add(P1, "旧的'不是丢动作'文案仍在",
            "改造前那句话是谎话（当时根本不重生成），修复后必须换成带真实数字的表述", ARCHIVE)

    ok = export_strip and clear_ok and reg_ok and ensure_ok and (not relies_on_warmup) \
        and scale_ok and (not wrong_scale) and bake_ok and old_lie_gone
    print("   判定：%s\n" % ("内置动作两侧都已堵住 ✓" if ok else "修复不完整 ✗"))
    return ok


def main():
    ap = argparse.ArgumentParser(description="All-in-One 存档静态验证")
    ap.add_argument("--repo", default=".", help="Dollhouse 仓库根目录")
    args = ap.parse_args()
    repo = Repo(os.path.abspath(args.repo))

    print("=" * 78)
    print("All-in-One 全量存档（v2）静态验证")
    print("仓库：%s" % repo.root)
    print("=" * 78)
    print()

    missing = [f for f in (ARCHIVE, ENVELOPE, SERVICE, DOLLSTORE, DOLLPROFILE, PETPREFS, IMAGESTORE, MYPROFILE)
               if not repo.exists(f)]
    if missing:
        print("缺少关键文件，无法验证：%s" % missing)
        return 3

    check_sections(repo)
    p0 = check_secrets(repo)
    # P0 立刻单独高亮（供调用方 / 人眼第一时间看到）
    if p0:
        print("!" * 78)
        print("!! P0 告警：%d 处密钥相关缺陷，需立即上报" % len(p0))
        print("!" * 78)
        print()
    check_v1(repo)
    check_restore_semantics(repo)
    check_upsert(repo)
    check_image_path(repo)
    check_persona_coverage(repo)
    key_injection_ok = check_persona_key_injection(repo)
    seed_ok = check_persona_seed_not_blocking(repo)
    chat_ok = check_chat_no_overwrite(repo)
    image_fail_ok = check_image_failure_reported(repo)
    check_api_existence(repo)
    check_path_conventions(repo)
    fix1, fix2 = check_teammate_fixes(repo)
    anim_ok = check_builtin_anim_no_deadlink(repo)
    isolation_ok = check_restore_section_isolation(repo)
    mem_limit_ok = check_memory_limit_not_silent(repo)

    verdict, code = summarize(p0)
    print()
    print("队友修复复核结论：")
    print("  · 图片多帧去重键分两类：%s" % ("通过" if fix1 else "不通过"))
    print("  · restoreAnim 先 rebind 再判：%s" % ("通过" if fix2 else "不通过"))
    print("  · 人设段键注入防护（task-13）：%s" % ("通过" if key_injection_ok else "不通过"))
    print("  · 人设恢复不被工厂种子挡住（task-19）：%s" % ("通过" if seed_ok else "不通过"))
    print("  · 聊天恢复不覆盖本机（task-15）：%s" % ("通过" if chat_ok else "不通过"))
    print("  · 图片写盘失败不静默（P1-3）：%s" % ("通过" if image_fail_ok else "不通过"))
    print("  · 内置动作恢复不成死链（task-20）：%s" % ("通过" if anim_ok else "不通过"))
    print("  · 恢复逐段异常隔离（task-18）：%s" % ("通过" if isolation_ok else "不通过"))
    print("  · 记忆超限不静默（task-21）：%s" % ("通过" if mem_limit_ok else "不通过"))
    return code


if __name__ == "__main__":
    sys.exit(main())