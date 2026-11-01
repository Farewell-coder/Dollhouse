package com.dollhouse.app.ui.widget

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.AexImport
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 【职责】把 Aivora 通过 AEX 1.0（Aivora Exchange Protocol）分享的 `.akcp` 文件
 *        导入成本 App 的供应商与模型。
 *
 * 【与 AkcpImportSheet 的关系】那是 AKCP 1.0（`.aivora`），这是 AEX 1.0（`.akcp`）。
 *   两者容器 Magic、KDF、载荷字段完全不同，所以是两套独立实现，
 *   只在入口处按内容 Magic 分流（[AexImport.detect]）：用户选错文件也能自动走对的那条。
 *
 * 【交互】四步、四个独立弹窗（沿用本工程做法：一次只显示一个 Dialog，避免嵌套时序问题）：
 *   ① 文件选择；② 凭据输入；③ 后台解密；④ 预览 + 勾选 + 冲突策略 + 写入。
 *
 * 【冲突口径】规范要求不得未经确认覆盖数据。同名默认跳过，
 *   用户可显式改为覆盖（只更新地址与密钥）或另存副本。
 *
 * 【批量不连坐】某条写入失败只记入摘要，其余照常提交。
 */
object AexImportSheet {

    private const val TAG = "Dollhouse"

    /** 冲突处理策略。 */
    private const val MODE_SKIP = 0
    private const val MODE_OVERWRITE = 1
    private const val MODE_DUPLICATE = 2

    /* ============================ ① 入口 ============================ */

    /** 从供应商页按钮进入：先选文件。 */
    @JvmStatic
    fun show(act: Activity?) {
        val activity = act ?: return
        PickFileActivity.setFileListener { paths, _ ->
            PickFileActivity.setFileListener(null)
            val first = if (paths.isEmpty()) null else paths[0]
            if (first == null) {
                return@setFileListener
            }
            val f = File(first)
            val bytes = readBytes(f)
            if (bytes == null) {
                tip(activity, "无法导入", "读取文件失败，请确认文件还能打开后重试。")
                return@setFileListener
            }
            dispatch(activity, bytes, f.name)
        }
        PickFileActivity.startFiles(activity, false)
    }

    /**
     * 外部入口（系统分享 / 打开方式）已经拿到字节时直接用，不再弹文件选择器。
     *
     * 【为什么单独给这个入口】Intent 里的 content:// URI 必须用 ContentResolver 读，
     *   读出来的字节直接送进同一条解密链路即可，避免先落盘成临时文件再多一次 IO。
     */
    @JvmStatic
    fun startWithBytes(act: Activity?, bytes: ByteArray?, fileName: String?) {
        val activity = act ?: return
        if (bytes == null) {
            tip(activity, "无法导入", "读取分享的文件失败，请重试。")
            return
        }
        if (bytes.size > AexImport.MAX_FILE_SIZE) {
            tip(activity, "无法导入", "文件超过 16 MiB，不可能是合法的 AEX 容器。")
            return
        }
        val name = if (fileName.isNullOrEmpty()) "shared.akcp" else fileName
        dispatch(activity, bytes, name)
    }

    /**
     * 按内容分流：AKCP 交给旧链路，AEX 走本链路，都不是则给出可读提示。
     *
     * 【为什么先看内容再看后缀】规范 4.1 明确不能只凭扩展名判断有效性；
     *   反过来，用户把文件改过名也不该直接判死——Magic 才是真凭据。
     */
    private fun dispatch(activity: Activity, bytes: ByteArray, fileName: String) {
        when (AexImport.detect(bytes)) {
            AexImport.Kind.AKCP -> AkcpImportSheet.startWithBytes(activity, bytes, fileName)
            AexImport.Kind.AEX -> credentialDialog(activity, bytes, fileName)
            AexImport.Kind.UNKNOWN -> {
                if (AexImport.acceptsName(fileName)) {
                    // 后缀对、Magic 不对：交给解析器给出「结构不完整」的准确报错。
                    credentialDialog(activity, bytes, fileName)
                } else {
                    tip(
                        activity, "无法导入",
                        "这个文件既不是 AEX（.akcp）也不是 AKCP（.aivora）容器。\n\n" +
                            "请确认是从 Aivora 里「分享配置」导出的原始文件，而不是改名后的副本。"
                    )
                }
            }
        }
    }

    private fun readBytes(f: File): ByteArray? {
        return try {
            if (f.length() > AexImport.MAX_FILE_SIZE) null else f.readBytes()
        } catch (t: Throwable) {
            Logs.w(TAG, "读取 akcp 文件失败", t)
            null
        }
    }

    /** 一条简单的「说明 + 知道了」弹窗。 */
    private fun tip(act: Activity, title: String, message: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, title) {
                Text(
                    text = message,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_BTN + 5f).sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    DhForm.PrimaryChip(text = "知道了") { ref.get()?.dismiss() }
                }
            }
        )
    }

    /* ============================ ② 凭据输入 ============================ */

    private fun credentialDialog(act: Activity, bytes: ByteArray, fileName: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "导入 AEX 配置") {
                CredentialBody(act, bytes, fileName, ref)
            }
        )
    }

    @Composable
    private fun CredentialBody(
        act: Activity,
        bytes: ByteArray,
        fileName: String,
        ref: AtomicReference<Dialog?>
    ) {
        val c = DhTokens.colors
        var cred by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        Text(
            text = "文件：" + fileName,
            color = c.title,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fontsBold
        )
        Text(
            text = "请输入 Aivora 生成这个文件时给出的凭据。\n" +
                "凭据只在本机用于解密：不保存、不上传、不写日志。",
            modifier = Modifier.padding(top = 8.dp),
            color = c.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts,
            lineHeight = (UiKit.FS_TINY + 5f).sp
        )
        Column(modifier = Modifier.padding(top = 12.dp)) {
            DhForm.Input(
                value = cred,
                onValueChange = {
                    cred = it
                    error = null
                },
                label = "文件凭据",
                hint = "XXXX-XXXX-…",
                singleLine = true
            )
        }
        val err = error
        if (err != null) {
            Text(
                text = err,
                modifier = Modifier.padding(top = 8.dp),
                color = c.err,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            DhForm.OutlineChip(text = "取消") { ref.get()?.dismiss() }
            Spacer(Modifier.width(10.dp))
            DhForm.PrimaryChip(text = "解密并预览") {
                if (!AexImport.credentialLooksValid(cred)) {
                    error = "凭据不能为空（Aivora 生成的通常形如 XXXX-XXXX-…）"
                } else {
                    ref.get()?.dismiss()
                    decryptingDialog(act, bytes, fileName, cred)
                }
            }
        }
    }

    /* ============================ ③ 解密 ============================ */

    private fun decryptingDialog(act: Activity, bytes: ByteArray, fileName: String, cred: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "正在校验") {
                Text(
                    text = "正在用 PBKDF2 校验并解密，大约需要一秒…",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        )
        // 【必须后台线程】PBKDF2-HMAC-SHA256 60 万轮，主线程跑就是 ANR。
        Thread({
            val result = AexImport.parse(bytes, cred, fileName)
            act.runOnUiThread {
                if (act.isFinishing) {
                    return@runOnUiThread
                }
                ref.get()?.dismiss()
                if (!result.ok()) {
                    tip(act, "导入失败", result.message())
                } else {
                    previewDialog(act, result, fileName)
                }
            }
        }, "dh-aex-decrypt").start()
    }

    /* ============================ ④ 预览 ============================ */

    private fun previewDialog(act: Activity, result: AexImport.Result, fileName: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "确认导入") {
                PreviewBody(act, result, fileName, ref)
            }
        )
    }

    @Composable
    private fun PreviewBody(
        act: Activity,
        result: AexImport.Result,
        fileName: String,
        ref: AtomicReference<Dialog?>
    ) {
        val c = DhTokens.colors
        val profiles = result.profiles
        var mode by remember { mutableStateOf(MODE_SKIP) }
        // 逐条勾选：默认全选（规范要求「允许用户选择需要导入的配置」）。
        var picked by remember { mutableStateOf(BooleanArray(profiles.size) { true }) }
        var done by remember { mutableStateOf(false) }
        var showExtra by remember { mutableStateOf(false) }
        var summary by remember { mutableStateOf("") }
        val hasExtra = profiles.any { it.hasExtra() }
        val local = ProviderStore.providers(act)
        val conflicts = profiles.count { p -> local.any { it.name == p.name } }
        val pickedCount = picked.count { it }

        if (!done) {
            Text(
                text = "文件：" + fileName + "\n共 " + profiles.size + " 条配置" +
                    (if (conflicts > 0) "，其中 " + conflicts + " 条与本机供应商同名" else ""),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            // 【非致命提示】次版本偏新、个别条目被跳过等全部摆出来，不藏。
            if (result.warnings.isNotEmpty()) {
                Text(
                    text = result.warnings.joinToString("\n"),
                    modifier = Modifier.padding(top = 6.dp),
                    color = c.err,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_TINY + 5f).sp
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp)
            ) {
                profiles.forEachIndexed { i, p ->
                    PreviewRow(p, picked[i]) {
                        val copy = picked.copyOf()
                        copy[i] = !copy[i]
                        picked = copy
                    }
                }
            }
            if (hasExtra) {
                Text(
                    text = "查看保存范围说明",
                    modifier = Modifier.padding(top = 8.dp).pressable { showExtra = !showExtra },
                    color = c.acc,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                if (showExtra) {
                    Text(
                        text = "部分配置带有本应用存不下的内容（说明文字、extra 扩展对象、" +
                            "以及本版不认识的字段）。导入只保留名称、地址、密钥、模型列表与启用状态，" +
                            "其余内容不会带过来。",
                        modifier = Modifier.padding(top = 6.dp),
                        color = c.err,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = (UiKit.FS_TINY + 5f).sp
                    )
                }
            }
            if (conflicts > 0) {
                Text(
                    text = "同名供应商处理方式",
                    modifier = Modifier.padding(top = 12.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    ModeChip("跳过", mode == MODE_SKIP) { mode = MODE_SKIP }
                    Spacer(Modifier.width(8.dp))
                    ModeChip("覆盖", mode == MODE_OVERWRITE) { mode = MODE_OVERWRITE }
                    Spacer(Modifier.width(8.dp))
                    ModeChip("另存副本", mode == MODE_DUPLICATE) { mode = MODE_DUPLICATE }
                }
                Text(
                    text = when (mode) {
                        MODE_OVERWRITE -> "覆盖只更新地址与密钥，不动该供应商已有的模型设置。"
                        MODE_DUPLICATE -> "新建一个带「副本」后缀的供应商，原配置保持不变。"
                        else -> "同名供应商保持原样，只导入不冲突的那些。"
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.OutlineChip(text = "取消") { ref.get()?.dismiss() }
                Spacer(Modifier.width(10.dp))
                DhForm.PrimaryChip(
                    text = if (pickedCount == 0) "请先勾选" else "导入 " + pickedCount + " 条"
                ) {
                    if (pickedCount == 0) {
                        return@PrimaryChip
                    }
                    val selected = ArrayList<AexImport.AexProfile>()
                    profiles.forEachIndexed { i, p -> if (picked[i]) selected.add(p) }
                    summary = apply(act, selected, mode)
                    done = true
                }
            }
        } else {
            Text(
                text = summary,
                color = c.sub,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_BTN + 5f).sp
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.PrimaryChip(text = "好") { ref.get()?.dismiss() }
            }
        }
    }

    /* ============================ 落库 ============================ */

    /**
     * 把预览里勾选的配置写成供应商 + 模型。
     *
     * 【批量一致性口径】规范第七节要求批量导入保证事务一致性。本实现的取舍：
     *   逐条提交，**单条内部**不留半成品（先校验再写），某一跳失败只影响该条，
     *   已成功的条目保持，最终摘要里明确列出失败条目。
     *   真做成「全成或全不成」需要 ProviderStore 支持批量事务，本版不改存储层 ——
     *   这是刻意取舍：宁可让用户看到「3 条成功 1 条失败」，也不假装原子性。
     *
     * 【坑】apiKey 必须先 KeyVault 加密再存 —— ProviderStore 里存的一律是密文。
     */
    private fun apply(act: Activity, profiles: List<AexImport.AexProfile>, mode: Int): String {
        val ctx: Context = act.applicationContext
        var created = 0
        var updated = 0
        var skipped = 0
        var modelsAdded = 0
        val failed = ArrayList<String>()

        for (p in profiles) {
            try {
                val same = findByName(ctx, p.name)
                when {
                    same != null && mode == MODE_SKIP -> skipped++
                    same != null && mode == MODE_OVERWRITE -> {
                        // 【空值 ≠ 缺失】规范 4.1：字段缺失表示「未设置」，存在但为空串表示「用户明确置空」。
                        //   覆盖时必须按这个语义走：缺失就不动原值，显式空串才清空。
                        //   早期写法无条件赋值，会把文件里没提供的 baseUrl / apiKey 覆盖成空，
                        //   等于用「不支持该字段」把用户已配好的供应商弄坏。
                        if (p.hasBaseUrl) {
                            same.baseUrl = ModelRules.normalizeBaseUrl(p.baseUrl)
                        }
                        if (p.hasApiKey) {
                            same.apiKey = KeyVault.encrypt(p.apiKey)
                        }
                        same.enabled = true
                        ProviderStore.saveProvider(ctx, same)
                        modelsAdded += addModels(ctx, same.id, p)
                        updated++
                    }
                    else -> {
                        val np = Provider()
                        np.id = ProviderStore.newId()
                        np.name = if (same != null) uniqueName(ctx, p.name) else p.name
                        np.protocol = Provider.PROTO_OPENAI
                        // 新建时缺失与空串结果相同（都是空），但照样按存在性写，保持语义一致。
                        np.baseUrl = if (p.hasBaseUrl) ModelRules.normalizeBaseUrl(p.baseUrl) else ""
                        np.apiKey = if (p.hasApiKey) KeyVault.encrypt(p.apiKey) else ""
                        np.enabled = true
                        ProviderStore.saveProvider(ctx, np)
                        modelsAdded += addModels(ctx, np.id, p)
                        created++
                    }
                }
            } catch (t: Throwable) {
                // 【不连坐】这一条失败不影响后面的条目；名字进摘要，方便用户重试。
                Logs.w(TAG, "AEX 写入失败：" + p.name, t)
                failed.add(p.name)
            }
        }

        val sb = StringBuilder("导入完成。")
        if (created > 0) {
            sb.append("\n新增供应商 ").append(created).append(" 个")
        }
        if (updated > 0) {
            sb.append("\n覆盖供应商 ").append(updated).append(" 个")
        }
        if (modelsAdded > 0) {
            sb.append("\n新增模型 ").append(modelsAdded).append(" 个")
        }
        if (skipped > 0) {
            sb.append("\n跳过同名供应商 ").append(skipped).append(" 个")
        }
        if (failed.isNotEmpty()) {
            sb.append("\n未能导入 ").append(failed.size).append(" 条：").append(failed.joinToString("、"))
        }
        sb.append("\n\n密钥已加密保存，可在供应商详情里核对。")
        return sb.toString()
    }

    private fun findByName(ctx: Context, name: String): Provider? {
        for (p in ProviderStore.providers(ctx)) {
            if (p.name == name) {
                return p
            }
        }
        return null
    }

    /** 同名时生成不重复名称：原名 +「（副本）」「（副本 2）」… */
    private fun uniqueName(ctx: Context, base: String): String {
        val names = ProviderStore.providers(ctx).map { it.name }.toSet()
        var name = base + "（副本）"
        var i = 2
        while (names.contains(name)) {
            name = base + "（副本 " + i + "）"
            i++
        }
        return name
    }

    /**
     * 按 AEX 的 models / defaultModel 建模型；已存在的同名模型不重复添加。
     *
     * 【为什么要补 defaultModel】协议里它与 models 是两个独立字段，
     *   可能不在数组内；漏掉会出现「导入成功但默认模型看不见」。
     */
    private fun addModels(ctx: Context, providerId: String, p: AexImport.AexProfile): Int {
        val names = ArrayList(p.models)
        if (p.defaultModel.isNotEmpty() && !names.contains(p.defaultModel)) {
            names.add(p.defaultModel)
        }
        var added = 0
        for (n in names) {
            if (ModelStore.modelExists(ctx, providerId, n, null)) {
                continue
            }
            val m = AiModel()
            m.id = ProviderStore.newId()
            m.displayName = n
            m.providerId = providerId
            m.kind = AiModel.KIND_CHAT
            m.enabled = true
            ModelStore.saveModel(ctx, m)
            added++
        }
        return added
    }

    @Composable
    private fun PreviewRow(p: AexImport.AexProfile, checked: Boolean, onToggle: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).pressable(onClick = onToggle),
            verticalAlignment = Alignment.Top
        ) {
            // 自绘勾选框：工程里没有现成 checkbox 组件，用字符保持视觉一致、零依赖。
            Text(
                text = if (checked) "☑" else "☐",
                color = if (checked) c.acc else c.sub,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = p.name + if (p.provider.isNotEmpty()) "（" + p.provider + "）" else "",
                    color = c.title,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fontsBold
                )
                // 【原样展示】baseUrl 不做任何美化改写，用户核对时看到的就是文件里的值。
                Text(
                    text = p.baseUrl,
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                Text(
                    text = "密钥 " + AexImport.maskKey(p.apiKey) + " · " +
                        (if (p.models.isEmpty()) "无模型列表" else p.models.size.toString() + " 个模型") +
                        (if (p.defaultModel.isEmpty()) "" else " · 默认 " + p.defaultModel),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    @Composable
    private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
        if (selected) {
            DhForm.PrimaryChip(text = text, onClick = onClick)
        } else {
            DhForm.OutlineChip(text = text) { onClick() }
        }
    }
}
