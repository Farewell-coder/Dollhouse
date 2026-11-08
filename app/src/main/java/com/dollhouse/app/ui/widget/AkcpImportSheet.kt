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
import com.dollhouse.app.data.AkcpImport
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 【职责】把 Aivora 导出的 `.aivora` 文件（AKCP 1.0）导入成本 App 的供应商与模型。
 *
 * 【为什么做】用户希望「Aivora 里配好的服务，在 Dollhouse 里直接可用」。
 *   该格式是 Aivora 公开的开放协议（见 [AkcpImport] 顶部注释），
 *   不依赖它的账号、服务器或 SDK，第三方可独立实现。
 *
 * 【交互】四步、四个独立弹窗（沿用本工程既有做法：一次只显示一个 Dialog，
 *   避免嵌套弹窗的时序问题）：
 *   ① 文件选择（系统文件选择器，不限 MIME，选完按后缀过滤）
 *   ② 凭据输入（文件独立的 52 位 Base32，可带连字符）
 *   ③ 解密（Argon2id 在后台线程跑，弹窗只显示进度）
 *   ④ 预览 + 冲突选择 + 写入（API Key 全程打码）
 *
 * 【冲突口径】规范要求「不得静默覆盖已有配置」。同名时默认跳过，
 *   用户可显式改为覆盖（只更新地址与密钥，不动已有模型的设置）或另存副本。
 *
 * 【坑】① 必须后台线程解密：Argon2id 要 64 MiB 内存，主线程会 ANR。
 *        ② 明文密钥只在内存里流转：落盘一律 KeyVault 加密，日志不写密钥内容。
 *        ③ 选完文件拿到的是 cacheDir 副本（名如 raw_<时间戳>_<随机>.aivora）：
 *          后缀保真、原名不保真，界面照实显示副本名。
 */
object AkcpImportSheet {

    private const val TAG = "Dollhouse"

    /** 冲突处理策略。 */
    private const val MODE_SKIP = 0
    private const val MODE_OVERWRITE = 1
    private const val MODE_DUPLICATE = 2

    /** 入口：先选文件，选完再一步步往下走。 */
    @JvmStatic
    fun show(act: Activity?) {
        val activity = act ?: return
        PickFileActivity.setFileListener { paths, _ ->
            PickFileActivity.setFileListener(null)
            val first = if (paths.isEmpty()) null else paths[0]
            // 用户取消：什么也不弹，避免「点取消反被弹一脸提示」。
            if (first == null) {
                return@setFileListener
            }
            val f = File(first)
            // 规范第 1 条：只认小写 .aivora；改名成 .json / .akcp 必须在入口就拒绝。
            if (!AkcpImport.acceptsName(f.name)) {
                tip(
                    activity, "无法导入", "这不是 Aivora 导出文件。\n\n" +
                        "只接受以小写 .aivora 结尾的文件；改过后缀的文件按协议要求一律拒绝。"
                )
                return@setFileListener
            }
            if (f.length() > AkcpImport.MAX_FILE_SIZE) {
                tip(activity, "无法导入", "文件超过 16 MiB，不可能是一个合法的 AKCP 容器。")
                return@setFileListener
            }
            val bytes = try {
                f.readBytes()
            } catch (t: Throwable) {
                Logs.w(TAG, "读取 aivora 文件失败", t)
                null
            }
            if (bytes == null) {
                tip(activity, "无法导入", "读取文件失败，请确认文件还能打开后重试。")
                return@setFileListener
            }
            credentialDialog(activity, bytes, f.name)
        }
        PickFileActivity.startFiles(activity, false)
    }

    /**
     * 外部入口（系统分享 / 打开方式 / AEX 入口按内容分流过来）已经拿到字节时直接用，
     * 不再弹文件选择器。
     *
     * 【调用方】[AexImportSheet.dispatch]：文件内容是 AKCP Magic 时转交给这里，
     *   这样「用 AEX 的入口选了 .aivora 文件」也能正常导入，不用让用户回去换按钮。
     */
    @JvmStatic
    fun startWithBytes(act: Activity?, bytes: ByteArray?, fileName: String?) {
        val activity = act ?: return
        if (bytes == null) {
            tip(activity, "无法导入", "读取分享的文件失败，请重试。")
            return
        }
        if (bytes.size > AkcpImport.MAX_FILE_SIZE) {
            tip(activity, "无法导入", "文件超过 16 MiB，不可能是一个合法的 AKCP 容器。")
            return
        }
        val name = if (fileName.isNullOrEmpty()) "shared.aivora" else fileName
        // 【后缀仍要判】AKCP 规范强制「只能是小写 .aivora」；分流过来时用户可能是改名文件。
        if (!AkcpImport.acceptsName(name)) {
            tip(
                activity, "无法导入", "这不是 Aivora 导出文件。\n\n" +
                    "AKCP 容器只接受以小写 .aivora 结尾的文件；改过后缀的文件按协议要求一律拒绝。"
            )
            return
        }
        credentialDialog(activity, bytes, name)
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
                    fontFamily = DhTokens.fontsBody,
                    lineHeight = (UiKit.FS_BTN + 5f).sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    DhForm.Button(text = "知道了") { ref.get()?.dismiss() }
                }
            }
        )
    }

    /* ------------------------------ ② 凭据输入 ------------------------------ */
    private fun credentialDialog(act: Activity, bytes: ByteArray, fileName: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "导入 Aivora 配置") {
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
            text = "请输入这个文件导出时生成的凭据（52 位 Base32，可带连字符）。\n" +
                "凭据只在本机用于解密，不会被保存。",
            modifier = Modifier.padding(top = 8.dp),
            color = c.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fontsBody,
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
                hint = "XXXX-XXXX-XXXX-…",
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
                fontFamily = DhTokens.fontsBody
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            DhForm.Button(text = "取消", primary = false) { ref.get()?.dismiss() }
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            DhForm.Button(text = "解密并预览") {
                if (!AkcpImport.credentialLooksValid(cred)) {
                    error = "凭据应为 52 位 Base32（字母 A–Z 与数字 2–7）"
                } else {
                    ref.get()?.dismiss()
                    decryptingDialog(act, bytes, fileName, cred)
                }
            }
        }
    }

    /* ------------------------------ ③ 解密 ------------------------------ */
    private fun decryptingDialog(act: Activity, bytes: ByteArray, fileName: String, cred: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "正在解密") {
                Text(
                    text = "正在用 Argon2id 校验并解密，大约需要一两秒…",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp, lineHeight = DhTokens.type.body.lineHeight,
                    fontFamily = DhTokens.fontsBody
                )
            }
        )
        // 【必须后台线程】Argon2id 64 MiB / 3 轮，主线程跑必 ANR。
        Thread({
            val result = AkcpImport.parse(bytes, cred, fileName)
            act.runOnUiThread {
                if (act.isFinishing) {
                    return@runOnUiThread
                }
                ref.get()?.dismiss()
                if (!result.ok()) {
                    tip(act, "导入失败", result.message())
                } else {
                    previewDialog(act, result.configs, fileName)
                }
            }
        }, "dh-akcp-decrypt").start()
    }

    /* ------------------------------ ④ 预览 + 写入 ------------------------------ */
    private fun previewDialog(act: Activity, configs: List<AkcpImport.AkcpConfig>, fileName: String) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "确认导入") {
                PreviewBody(act, configs, fileName, ref)
            }
        )
    }

    @Composable
    private fun PreviewBody(
        act: Activity,
        configs: List<AkcpImport.AkcpConfig>,
        fileName: String,
        ref: AtomicReference<Dialog?>
    ) {
        val c = DhTokens.colors
        var mode by remember { mutableStateOf(MODE_SKIP) }
        var done by remember { mutableStateOf(false) }
        var showExtra by remember { mutableStateOf(false) }
        var summary by remember { mutableStateOf("") }
        val hasExtra = configs.any { it.hasExtra() }
        val local = ProviderStore.providers(act)
        val conflicts = configs.count { cfg -> local.any { it.name == cfg.configName } }
        if (!done) {
            Text(
                text = "文件：" + fileName + "\n共 " + configs.size + " 条配置" +
                    (if (conflicts > 0) "，其中 " + conflicts + " 条与本机供应商同名" else ""),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp, lineHeight = DhTokens.type.body.lineHeight,
                fontFamily = DhTokens.fontsBody
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = DhDesign.Space.md.dp)
            ) {
                configs.forEach { cfg -> PreviewRow(cfg) }
            }
            if (hasExtra) {
                Text(
                    text = "查看保存范围说明",
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .pressable { showExtra = !showExtra },
                    color = c.acc,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                if (showExtra) {
                    Text(
                        text = "部分配置带有本应用存不下的扩展字段。导入只会保留名称、地址、密钥、" +
                            "模型列表与启用状态，其余内容不会带过来。",
                        modifier = Modifier.padding(top = DhDesign.Space.xs.dp),
                        color = c.err,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fontsBody,
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
                Row(modifier = Modifier.padding(top = DhDesign.Space.sm.dp)) {
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
                    modifier = Modifier.padding(top = DhDesign.Space.xs.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fontsBody
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.Button(text = "取消", primary = false) { ref.get()?.dismiss() }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                DhForm.Button(text = "导入") {
                    summary = apply(act, configs, mode)
                    done = true
                }
            }
        } else {
            Text(
                text = summary,
                color = c.sub,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBody,
                lineHeight = (UiKit.FS_BTN + 5f).sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.Button(text = "好") { ref.get()?.dismiss() }
            }
        }
    }

    /**
     * 【v0.2.0·预览只显示名称】
     * 与 AEX 链路（`AexImportSheet.PreviewRow`）保持一致：删掉端点行与密钥行。
     * 【为什么两条链路必须一起改】它们渲染的是同一个「确认导入」弹窗，
     *   只改一条会让 `.aivora` 旧文件仍然把密钥打到屏幕上，等于改动落空。
     * 本函数现已不再调用 [AkcpImport.maskKey]。
     */
    @Composable
    private fun PreviewRow(cfg: AkcpImport.AkcpConfig) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = DhDesign.Space.sm.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cfg.configName,
                color = c.title,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fontsBold
            )
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

    /* ------------------------------ 落库 ------------------------------ */
    /**
     * 把预览里的配置写成供应商 + 模型。
     *
     * 【返回】给用户看的结果摘要。
     * 【坑】apiKey 必须先 KeyVault 加密再存 —— ProviderStore 存的一律是密文。
     */
    private fun apply(act: Activity, configs: List<AkcpImport.AkcpConfig>, mode: Int): String {
        val ctx: Context = act.applicationContext
        var created = 0
        var updated = 0
        var skipped = 0
        var modelsAdded = 0
        for (cfg in configs) {
            val same = findByName(ctx, cfg.configName)
            when {
                same != null && mode == MODE_SKIP -> skipped++
                same != null && mode == MODE_OVERWRITE -> {
                    same.baseUrl = ModelRules.normalizeBaseUrl(cfg.endpoint)
                    same.apiKey = KeyVault.encrypt(cfg.apiKey)
                    same.enabled = cfg.enabled
                    ProviderStore.saveProvider(ctx, same)
                    modelsAdded += addModels(ctx, same.id, cfg)
                    updated++
                }
                else -> {
                    val p = Provider()
                    p.id = ProviderStore.newId()
                    p.name = if (same != null) uniqueName(ctx, cfg.configName) else cfg.configName
                    p.protocol = Provider.PROTO_OPENAI
                    p.baseUrl = ModelRules.normalizeBaseUrl(cfg.endpoint)
                    p.apiKey = KeyVault.encrypt(cfg.apiKey)
                    p.enabled = cfg.enabled
                    ProviderStore.saveProvider(ctx, p)
                    modelsAdded += addModels(ctx, p.id, cfg)
                    created++
                }
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
     * 按 AKCP 的 models / default_model 建模型；已存在的同名模型不重复添加。
     *
     * 【为什么要补 default_model】协议里它是独立字段，可能不在 models 数组内；
     *   漏掉会出现「导入成功但默认模型看不见」。
     */
    private fun addModels(ctx: Context, providerId: String, cfg: AkcpImport.AkcpConfig): Int {
        val names = ArrayList(cfg.models)
        if (cfg.defaultModel.isNotEmpty() && !names.contains(cfg.defaultModel)) {
            names.add(cfg.defaultModel)
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
}