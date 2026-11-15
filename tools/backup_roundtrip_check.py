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
    # exportPersona 用 .all 但只按白名单取值 —— 校验它确实按清单取
    p_start, p_body = find_fun_body(arch_lines, r"\bprivate fun exportPersona\(")
    if p_start:
        whitelist_ok = ("PERSONA_BASES" in p_body) and re.search(r"for\s*\(\s*b\s+in\s+PERSONA_BASES", p_body)
        print("· exportPersona 按 PERSONA_BASES 白名单取值：%s" % ("✓" if whitelist_ok else "✗"))
        if not whitelist_ok:
            add(P0, "人设段可能整份导出", "exportPersona 未按白名单取键，可能把供应商密钥密文一并带出", "%s:%d" % (ARCHIVE, p_start))
            p0_hits.append("exportPersona")

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
    ("人设", None, r"p\.contains\(k\)", "只写本机没有的键"),
    ("动作库", None, r"mergeAnim\(", "按 id 只追加缺的"),
    ("记忆", None, r"Mem\.findByTitle\(", "同标题跳过"),
    ("记忆连接", None, r"Mem\.addLink\(", "自带去重"),
    ("记忆画像", None, r"Mem\.profile\(ctx, dollId\)\.isNotEmpty\(\)", "已存在则不动"),
    ("聊天历史", None, r'p\.contains\("conv_" \+ id\)', "已有历史则跳过"),
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
    cond_ok = bool(re.search(r"if\s*\(\s*!\s*rebound\s*&&\s*mergedLen\s*==\s*mineLen\s*&&\s*mineRaw\.isNotEmpty\(\)\s*\)", rab))
    print("   · 跳过条件同时含 !rebound 与长度未变：%s" % ("✓" if cond_ok else "✗"))
    if not order_ok:
        add(P1, "restoreAnim 判定顺序仍反", "先判写盘再 rebind 会让来源机死路径残留", "%s:%d" % (ARCHIVE, ra_start))
    if not cond_ok:
        add(P2, "restoreAnim 跳过条件不严", "条件未同时覆盖「未追加」与「未改写」", "%s:%d" % (ARCHIVE, ra_start))
    print("   判定：%s\n" % ("修复到位 ✓（rebind 先行，判定看的是改写后的结果）"
                          if (order_ok and cond_ok) else "修复不完整 ✗"))

    return fix1_ok, (order_ok and cond_ok)


# ---------------------------------------------------------------- 汇总

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
    check_api_existence(repo)
    check_path_conventions(repo)
    fix1, fix2 = check_teammate_fixes(repo)

    verdict, code = summarize(p0)
    print()
    print("队友修复复核结论：")
    print("  · 图片多帧去重键分两类：%s" % ("通过" if fix1 else "不通过"))
    print("  · restoreAnim 先 rebind 再判：%s" % ("通过" if fix2 else "不通过"))
    return code


if __name__ == "__main__":
    sys.exit(main())