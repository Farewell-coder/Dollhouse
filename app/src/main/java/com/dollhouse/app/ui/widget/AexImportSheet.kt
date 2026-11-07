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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.AexDeepLink
import com.dollhouse.app.data.DollhouseJsonImport
import com.dollhouse.app.data.AexImport
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 【职责】把 Aivora 通过 AEX 1.1（Aivora Exchange Protocol）分享的 `.akcp` 文件
 *        或 `dollhouse://aivora-import` 深链，导入成本 App 的供应商与模型。
 *
 * 【与 AkcpImportSheet 的关系】那是 AKCP 1.0（`.aivora`），这是 AEX 1.1（`.akcp` / 深链）。
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

    /**
     * 从供应商页按钮进入：先选文件。
     *
     * 【支持一次选多个】AEX 允许一个 `.akcp` 里装多条配置，也允许一次分享多个文件；
     *   用户手上有一堆单条文件时不该逼他逐个国家地导入。
     *   这里开多选，选完把每个文件都解密一遍，**最后合并成一次预览** ——
     *   用户只面对一张清单、一次确认，不用连着点 N 次弹窗。
     *
     * 【为什么在入口就把「读不出字节」的文件报掉】等进了解密流程才发现某个文件读不了，
     *   用户已经填过凭证了，白输一次。这里读完就分流：全部失败才提示，部分失败记 warning。
     */
    @JvmStatic
    fun show(act: Activity?) {
        val activity = act ?: return
        PickFileActivity.setFileListener { paths, _ ->
            PickFileActivity.setFileListener(null)
            if (paths.isEmpty()) {
                return@setFileListener
            }
            // 逐个读字节；读不出来的先记下名字，稍后一并告知，不打断其余文件的导入。
            val loaded = ArrayList<ByteArray>()
            val names = ArrayList<String>()
            val failed = ArrayList<String>()
            for (p in paths) {
                val f = File(p)
                val bytes = readBytes(f)
                if (bytes == null) {
                    failed.add(f.name)
                    continue
                }
                if (bytes.size > AexImport.MAX_FILE_SIZE) {
                    failed.add(f.name + "（超过 16 MiB）")
                    continue
                }
                loaded.add(bytes)
                names.add(f.name)
            }
            if (loaded.isEmpty()) {
                tip(activity, "无法导入", "选中的文件都没能读取。\n\n" + failed.joinToString("\n"))
                return@setFileListener
            }
            if (failed.isNotEmpty()) {
                // 部分失败：仍继续导入读成功的那些，但必须先说清楚哪些没进来。
                tip(
                    activity, "部分文件已跳过",
                    "以下文件无法读取或超过大小上限，已跳过：\n\n" + failed.joinToString("\n") +
                        "\n\n其余 " + loaded.size + " 个文件继续导入。"
                ) { dispatchMulti(activity, loaded, names) }
                return@setFileListener
            }
            dispatchMulti(activity, loaded, names)
        }
        // 多选：AEX 的批量导入靠这里打开。
        PickFileActivity.startFiles(activity, true)
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
     * 批量入口（系统分享多个文件 / 一次选了多个文件）：合并成一次凭据 + 一次预览。
     *
     * 【与 startWithBytes 的关系】单文件仍走上面那条（行为不变），
     *   多文件才走这里。两者最终都汇到 [dispatchMulti]。
     */
    @JvmStatic
    fun startWithBatch(act: Activity?, bytes: List<ByteArray>, names: List<String>) {
        val activity = act ?: return
        if (bytes.isEmpty()) {
            tip(activity, "无法导入", "没有可读的文件，请重试。")
            return
        }
        // 逐个过一遍大小上限，超限的剔除并记账（不阻断其余文件）。
        val okBytes = ArrayList<ByteArray>()
        val okNames = ArrayList<String>()
        val tooBig = ArrayList<String>()
        for (i in bytes.indices) {
            val n = names.getOrElse(i) { "shared.akcp" }
            if (bytes[i].size > AexImport.MAX_FILE_SIZE) {
                tooBig.add(n)
            } else {
                okBytes.add(bytes[i])
                okNames.add(n)
            }
        }
        if (okBytes.isEmpty()) {
            tip(activity, "无法导入", "选中的文件都超过 16 MiB：\n\n" + tooBig.joinToString("\n"))
            return
        }
        val go = { dispatchMulti(activity, okBytes, okNames) }
        if (tooBig.isNotEmpty()) {
            tip(
                activity, "部分文件已跳过",
                "以下文件超过 16 MiB，已跳过：\n\n" + tooBig.joinToString("\n") +
                    "\n\n其余 " + okBytes.size + " 个文件继续导入。"
            ) { go() }
        } else {
            go()
        }
    }

    /* ============================ ② 深链入口（AEX 1.1） ============================ */

    /**
     * 深链解析失败：只显示用户能看懂的原因，不打印链接 / payload。
     */
    @JvmStatic
    fun showDeepLinkError(act: Activity?, message: String?) {
        val activity = act ?: return
        tip(activity, "无法导入链接", if (message.isNullOrBlank()) "链接无效，请重新从 Aivora 导出。" else message)
    }

    /**
     * 深链确认：Aivora 的 `dollhouse://aivora-import` 是**明文**通道，
     * 没有凭证、没有加密。规范要求必须由用户点「导入」后才能入库，
     * 因此这里沿用文件通道的预览弹窗，只是多一行「来源 / 地址 / 密钥尾号」供核对。
     *
     * 【模型】AEX 1.1 的深链不带 models，确认后会按 `{baseUrl}/models` 拉一次；
     *   失败只提示，不阻断导入。
     */
    @JvmStatic
    fun startWithDeepLink(act: Activity?, result: AexDeepLink.Result?) {
        val activity = act ?: return
        val payload = result?.payload
        if (result == null || !result.ok() || payload == null) {
            showDeepLinkError(activity, result?.message())
            return
        }
        val profile = AexDeepLink.toProfile(payload)
        val warnings = ArrayList<String>()
        warnings.add("深链为明文通道，请确认来源后再导入；导入后会尝试从服务端获取模型列表。")
        if (payload.apiKey.isEmpty()) {
            warnings.add("这条配置没有带密钥，导入后需要自己补。")
        }
        val preview = AexImport.Result(listOf(profile), null, warnings)
        previewDialog(
            activity, preview, "Aivora 深链",
            sourceLabel = "来源：Aivora 外部链接（明文）",
            external = true
        )
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
            // 【v0.2.0·自家 JSON】不需要凭证（它没加密），直接进预览。
            //   走的是同一套「预览 → 勾选 → 冲突 → 写入」链路，只是来源不同。
            AexImport.Kind.DOLLHOUSE_JSON -> dollhouseJsonPreview(activity, bytes, fileName)
            AexImport.Kind.UNKNOWN -> {
                if (AexImport.acceptsName(fileName)) {
                    // 后缀对、Magic 不对：交给解析器给出「结构不完整」的准确报错。
                    credentialDialog(activity, bytes, fileName)
                } else {
                    tip(
                        activity, "无法导入",
                        "这个文件既不是 AEX（.akcp）、AKCP（.aivora），也不是 Dollhouse 导出的 JSON。\n\n" +
                            "请确认是从 Aivora 里「分享配置」导出的原始文件，或是本 App「导出配置」生成的文件。"
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

    /**
     * 一条简单的「说明 + 知道了」弹窗。
     *
     * @param onClose 用户点掉之后要接着做的事（可空）。用于「部分文件跳过 → 继续导入其余」
     *                这种必须**先让用户读完提示、再往下走**的场景。
     *                走 Dialog.setOnDismissListener，返回键 / 点外部 / 点按钮都能覆盖。
     */
    private fun tip(act: Activity, title: String, message: String, onClose: (() -> Unit)? = null) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, title, if (onClose == null) null else { { onClose.invoke() } }) {
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
                    DhForm.Button(text = "知道了") { ref.get()?.dismiss() }
                }
            }
        )
    }

    /**
     * 多文件合并分发：把每个文件认一遍容器类型，再合并成**一次**凭据 + 一次预览。
     *
     * 【为什么必须合并】若对每个文件各跑一遍「凭据 → 解密 → 预览」，用户选了 5 个文件
     *   就要输 5 次凭证、关 5 次弹窗。而这些文件通常是同一批导出的，凭证本来就一样。
     *
     * 【容错】允许混着来：
     *   · 旧版 AKCP（`.aivora`）单独收集，稍后交回旧流程处理（它有自己的解码链路）；
     *   · 认不出的文件不阻断其余文件，记进 skippedNames 最后统一告知；
     *   · AEX 文件合成一个待解密批次。
     */
    private fun dispatchMulti(act: Activity, list: List<ByteArray>, names: List<String>) {
        val aexBytes = ArrayList<ByteArray>()
        val aexNames = ArrayList<String>()
        val akcpBytes = ArrayList<ByteArray>()
        val akcpNames = ArrayList<String>()
        val jsonBytes = ArrayList<ByteArray>()
        val jsonNames = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (i in list.indices) {
            val b = list[i]
            val n = names.getOrElse(i) { "shared.akcp" }
            when (AexImport.detect(b)) {
                AexImport.Kind.AEX -> {
                    aexBytes.add(b)
                    aexNames.add(n)
                }
                AexImport.Kind.AKCP -> {
                    akcpBytes.add(b)
                    akcpNames.add(n)
                }
                // 【v0.2.0·自家 JSON】与外部格式并列收集，最后一起进预览。
                AexImport.Kind.DOLLHOUSE_JSON -> {
                    jsonBytes.add(b)
                    jsonNames.add(n)
                }
                else -> {
                    // 后缀像 .akcp 但 Magic 不认识：交给解析器报「结构不完整」比在这里猜更准。
                    if (AexImport.acceptsName(n)) {
                        aexBytes.add(b)
                        aexNames.add(n)
                    } else {
                        unknown.add(n)
                    }
                }
            }
        }
        if (aexBytes.isEmpty() && akcpBytes.isEmpty() && jsonBytes.isEmpty()) {
            tip(
                act, "无法导入",
                "选中的文件都不是 AEX（.akcp）、旧版 AKCP（.aivora）或 Dollhouse 导出文件：\n\n" + unknown.joinToString("\n")
            )
            return
        }
        // 认不出的先告知，用户读完再继续导入能认的那些。
        val proceed = {
            if (akcpBytes.isNotEmpty()) {
                // 旧格式逐个走原链路（它自带后缀校验与解码，不在这里重写）。
                for (i in akcpBytes.indices) {
                    AkcpImportSheet.startWithBytes(act, akcpBytes[i], akcpNames[i])
                }
            }
            if (aexBytes.isNotEmpty()) {
                credentialDialogMulti(act, aexBytes, aexNames)
            }
            // 【自家 JSON】无凭证、无加密，直接进预览（与单文件入口同一条路）。
            for (i in jsonBytes.indices) {
                dollhouseJsonPreview(act, jsonBytes[i], jsonNames[i])
            }
        }
        if (unknown.isNotEmpty()) {
            tip(
                act, "部分文件已跳过",
                "以下文件既不是 .akcp、.aivora，也不是 Dollhouse 导出文件，已跳过：\n\n" + unknown.joinToString("\n")
            ) { proceed() }
        } else {
            proceed()
        }
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
            DhForm.Button(text = "取消", primary = false) { ref.get()?.dismiss() }
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            DhForm.Button(text = "解密并预览") {
                if (!AexImport.credentialLooksValid(cred)) {
                    error = "凭据不能为空（Aivora 生成的通常形如 XXXX-XXXX-…）"
                } else {
                    ref.get()?.dismiss()
                    decryptingDialog(act, bytes, fileName, cred)
                }
            }
        }
    }

    /* ============================ ②b 批量凭据 ============================ */

    /**
     * 批量凭据输入：多个文件共用一次凭证输入。
     *
     * 【为什么不复用单文件版】单文件版的 body 里写死了「一个 bytes / 一个 fileName」，
     *   批量要展示「共 N 个文件」并一次性解密全部。逻辑差异大，单独一份比塞条件分支清楚。
     *
     * 【串行解密】AEX-SECURITY §3 明确要求「禁止并发触发（重复点击导致多次派生的，
     *   必须在 UI 层串行化）」。这里用 decrypted 计数 + 单线程顺序解析保证：
     *   用户点一次只跑一轮，且同一时刻只有一个 PBKDF2 在算。
     */
    private fun credentialDialogMulti(act: Activity, list: List<ByteArray>, names: List<String>) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "导入 AEX 配置（" + list.size + " 个文件）") {
                CredentialBodyMulti(act, list, names, ref)
            }
        )
    }

    @Composable
    private fun CredentialBodyMulti(
        act: Activity,
        list: List<ByteArray>,
        names: List<String>,
        ref: AtomicReference<Dialog?>
    ) {
        val c = DhTokens.colors
        var cred by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        Text(
            text = "共 " + list.size + " 个文件",
            color = c.title,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fontsBold
        )
        // 只列文件名前几个，多了给个省略，避免弹窗被文件名撑爆。
        Text(
            text = names.take(3).joinToString("、") +
                if (names.size > 3) " 等 " + names.size + " 个" else "",
            modifier = Modifier.padding(top = DhDesign.Space.xs.dp),
            color = c.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts
        )
        Text(
            text = "请输入 Aivora 生成这些文件时给出的凭据。\\n" +
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
            DhForm.Button(text = "取消", primary = false) { ref.get()?.dismiss() }
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            DhForm.Button(text = "解密并预览") {
                if (!AexImport.credentialLooksValid(cred)) {
                    error = "凭据不能为空（Aivora 生成的通常形如 XXXX-XXXX-…）"
                } else {
                    ref.get()?.dismiss()
                    decryptingDialogMulti(act, list, names, cred)
                }
            }
        }
    }

    /**
     * 批量解密：逐个文件串行解析，把成功的条目合并成一次预览。
     *
     * 【错误处理口径】
     *   · 全部失败 → 直接报第一个失败原因（用户最需要知道「为什么一个都没成」）；
     *   · 部分失败 → 继续用成功的那些出预览，并在预览顶部列出失败文件名与原因；
     *   · 全部成功 → 正常出预览。
     *   绝不做的事：失败时把已解出的部分明文丢掉 —— 那是「部分成功」场景下用户最想要的产物。
     */
    private fun decryptingDialogMulti(
        act: Activity,
        list: List<ByteArray>,
        names: List<String>,
        cred: String
    ) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "正在校验") {
                Text(
                    text = "正在解密 " + list.size + " 个文件，每个约需一秒…",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        )
        Thread({
            // 单线程内顺序解析：天然串行，不会同时跑多个 PBKDF2。
            val merged = ArrayList<AexImport.AexProfile>()
            val warnings = ArrayList<String>()
            val failures = ArrayList<String>()
            var firstReason: AexImport.Reason? = null
            for (i in list.indices) {
                val n = names.getOrElse(i) { "shared.akcp" }
                val r = AexImport.parse(list[i], cred, n)
                if (r.ok()) {
                    merged.addAll(r.profiles)
                    warnings.addAll(r.warnings)
                } else {
                    if (firstReason == null) {
                        firstReason = r.reason
                    }
                    failures.add(n + "：" + r.message())
                }
            }
            act.runOnUiThread {
                if (act.isFinishing) {
                    return@runOnUiThread
                }
                ref.get()?.dismiss()
                if (merged.isEmpty()) {
                    tip(act, "导入失败", firstReason?.let { messageOf(it) } ?: "所有文件都无法解密。")
                    return@runOnUiThread
                }
                // 把失败项也当成 warning 塞进去，用户能在预览页看到「哪几个没进来」。
                val allWarn = ArrayList<String>()
                if (failures.isNotEmpty()) {
                    allWarn.add("以下文件未能导入：")
                    allWarn.addAll(failures)
                }
                allWarn.addAll(warnings)
                val name0 = if (list.size == 1) names.firstOrNull() ?: "shared.akcp" else list.size.toString() + " 个文件"
                previewDialog(act, AexImport.Result(merged, null, allWarn), name0)
            }
        }, "dh-aex-decrypt-multi").start()
    }

    /** 把 Reason 翻成用户文案（多文件场景里直接要一个字符串）。 */
    private fun messageOf(reason: AexImport.Reason): String {
        return AexImport.Result(emptyList(), reason).message()
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

    /* ============================ ③' 自家 JSON 预览 ============================ */

    /**
     * 【v0.2.0·自家导出文件导回】把 `ExportSheet` 产出的 JSON 接进同一条预览链路。
     *
     * 【为什么复用它而不是另写一套界面】用户对「导入配置」的心理模型只有一个：
     *   先看到清单、勾选要哪些、决定同名怎么办、确认写入。文件是 AEX 还是自家 JSON
     *   对用户是同一个动作的两种来源，界面理应一致。写入侧也因此不需要第二套逻辑。
     *
     * 【与 AEX 链路的唯一差别】无需凭证（本格式不加密），所以跳过凭据弹窗直接解析。
     *
     * 【私有字段的保留】[AexImport.AexProfile] 只装得下名称/地址/密钥/模型，
     *   而自家导出里还有 `chatPath` / `useResponseApi` / `allowCleartext` 等字段。
     *   这些由 [applyDollhouseJson] 在写入时从原始字节里补回 —— 见那里的说明。
     */
    private fun dollhouseJsonPreview(act: Activity, bytes: ByteArray, fileName: String) {
        val r = DollhouseJsonImport.parse(bytes)
        if (r.error.isNotEmpty()) {
            tip(act, "无法导入", r.error)
            return
        }
        val warnings = ArrayList<String>()
        if (r.notice.isNotEmpty()) {
            warnings.add(r.notice)
        }
        if (r.droppedModels > 0) {
            warnings.add("有 " + r.droppedModels + " 个模型因为找不到所属供应商被跳过。")
        }
        val result = AexImport.Result(r.profiles, null, warnings)
        // 【v0.2.0】私有字段的补回不再靠旁路传递：解析时已把原始条目挂在
        //   `AexProfile.sourceProvider` 上，写入侧直接用（见 [applyDollhouseJson]）。
        previewDialog(act, result, fileName)
    }

    /**
     * 把自家 JSON 里那些 [AexImport.AexProfile] 装不下的字段补回 [Provider]。
     *
     * 【为什么必须补】导出是「完整备份」的语义。若 `chatPath` / `useResponseApi` /
     *   `allowCleartext` 丢了，用户重建出来的供应商行为会和备份前不一样 ——
     *   最典型的是自建站：`allowCleartext = false` 时明文 HTTP 会被代码层拒绝，
     *   表现为「导入成功，但一发请求就失败」，用户根本查不出原因。
     *
     * 【数据从哪来】`DollhouseJsonImport.toProfile` 解析时已把原始条目挂到
     *   [AexImport.AexProfile.sourceProvider] 上；这里直接读它，不必再回文件里按名字查。
     *   好处是「这份数据属于哪次导入」天然随对象生命周期走 —— 预览被取消、对象被丢弃，
     *   不会留下任何可能被下次导入误读的残留。
     *
     * @return 是否把字段补上了（false = 这条配置不是自家 JSON 来的）
     */
    private fun applyDollhouseJson(ctx: Context, p: AexImport.AexProfile, target: Provider): Boolean {
        // 【v0.2.0·按对象取，不靠旁路】原始条目挂在 profile 上，见 `AexProfile.sourceProvider`。
        //   null 即「不是自家 JSON 来的」（AEX / AKCP 路径），直接不处理。
        val src = p.sourceProvider ?: return false
        val restored = Provider.fromJson(src)
        if (restored.chatPath.isNotEmpty()) {
            target.chatPath = restored.chatPath
        }
        target.useResponseApi = restored.useResponseApi
        target.resendHistoryReasoning = restored.resendHistoryReasoning
        target.allowCleartext = restored.allowCleartext
        return true
    }

    /* ============================ ④ 预览 ============================ */

    private fun previewDialog(
        act: Activity,
        result: AexImport.Result,
        fileName: String,
        sourceLabel: String = "文件：" + fileName,
        external: Boolean = false
    ) {
        val ref = AtomicReference<Dialog?>()
        ref.set(
            DhForm.show(act, "确认导入") {
                PreviewBody(act, result, fileName, ref, sourceLabel, external)
            }
        )
    }

    @Composable
    private fun PreviewBody(
        act: Activity,
        result: AexImport.Result,
        fileName: String,
        ref: AtomicReference<Dialog?>,
        sourceLabel: String,
        external: Boolean
    ) {
        val c = DhTokens.colors
        val profiles = result.profiles
        var mode by remember { mutableStateOf(MODE_SKIP) }
        // 逐条勾选：默认全选（规范要求「允许用户选择需要导入的配置」）。
        var picked by remember { mutableStateOf(BooleanArray(profiles.size) { true }) }
        var done by remember { mutableStateOf(false) }
        var showExtra by remember { mutableStateOf(false) }
        var summary by remember { mutableStateOf("") }
        // 提交按钮状态：写入是同步的，所以只有 idle → loading → success 三段；
        // 若写入抛异常，[apply] 会把失败条目记进 summary，按钮停在 success（导入流程已给出结论）。
        var submitState by remember { mutableStateOf(DhForm.SubmitState.IDLE) }
        val hasExtra = profiles.any { it.hasExtra() }
        val local = ProviderStore.providers(act)
        val conflicts = profiles.count { p -> local.any { it.name == p.name } }
        val pickedCount = picked.count { it }

        if (!done) {
            // 【v0.2.4·接上 v0.2.3 生成的图标】文件通道与深链的来源不同，图标也不同：
            //   文件用 file-input，深链用 link-2。此前这一行纯文字，用户要靠读「来源：」二字
            //   才知道这次是从文件来的还是从链接来的 —— 图标一眼就能分辨。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        if (external) com.dollhouse.app.R.drawable.ic_link_2
                        else com.dollhouse.app.R.drawable.ic_file_input
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = c.acc
                )
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sourceLabel,
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Text(
                        text = "共 " + profiles.size + " 条配置" +
                            (if (conflicts > 0) "，其中 " + conflicts + " 条与本机供应商同名" else ""),
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = DhKit.LH_SUB.sp
                    )
                }
            }
            if (external && profiles.isNotEmpty()) {
                // 深链是明文通道，确认页必须让用户核对来源；密钥只露尾 4 位。
                Text(
                    text = "地址：" + profiles[0].baseUrl + "\n密钥：" +
                        AexDeepLink.maskKeyTail(profiles[0].apiKey),
                    modifier = Modifier.padding(top = DhDesign.Space.md.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_TINY + 5f).sp
                )
            }
            // 【非致命提示】次版本偏新、个别条目被跳过等全部摆出来，不藏。
            if (result.warnings.isNotEmpty()) {
                // 【v0.2.4·警告行加图标】此前警告与普通说明长得一模一样，只是颜色偏红；
                //   加一枚三角警告后，用户扫一眼就知道「这几行要先读」。
                Row(Modifier.padding(top = DhDesign.Space.md.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            com.dollhouse.app.R.drawable.ic_warning
                        ),
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp),
                        tint = c.err
                    )
                    Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                    Text(
                        text = result.warnings.joinToString("\n"),
                        color = c.err,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = DhKit.LH_SUB.sp
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = DhDesign.Space.md.dp)
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
                        modifier = Modifier.padding(top = DhDesign.Space.xs.dp),
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
                    fontFamily = DhTokens.fonts
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.Button(text = "取消", primary = false) { ref.get()?.dismiss() }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                // 【v0.2.4·四态按钮】原来是 PrimaryChip：按下去立刻变页面，中间没有任何
                //   「点到了 / 在处理 / 成了」的信号。导入要写好几次存储、还要联网拉模型，
                //   期间用户只能干等，容易连点。现在走 SubmitButton：转圈 → 完成。
                DhForm.SubmitButton(
                    text = if (pickedCount == 0) "请先勾选" else "导入 " + pickedCount + " 条",
                    state = if (pickedCount == 0) DhForm.SubmitState.IDLE else submitState,
                ) {
                    if (pickedCount == 0 || submitState == DhForm.SubmitState.LOADING) {
                        return@SubmitButton
                    }
                    submitState = DhForm.SubmitState.LOADING
                    val selected = ArrayList<AexImport.AexProfile>()
                    profiles.forEachIndexed { i, p -> if (picked[i]) selected.add(p) }
                    val outcome = apply(act, selected, mode)
                    summary = outcome.summary
                    done = true
                    submitState = DhForm.SubmitState.SUCCESS
                    // 【AEX 1.1】文件/深链都不再携带 models；导入成功后按 baseUrl 拉一次。
                    //   失败只追加提示，不撤销已经写入的供应商。
                    if (outcome.targets.isNotEmpty()) {
                        summary += "\n\n正在获取模型列表…"
                        fetchModels(act, outcome.targets) { extra ->
                            summary += "\n" + extra
                        }
                    }
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
                // 【需求】导入完成后进入供应商页，而不是把人晾在原地。
                //   此时供应商列表已经写好，跳过去正好能立刻看到刚导入的条目。
                //   与聊天页那条提示条的修法一致：不在弹窗里直接操作页面栈，
                //   而是把意图交给宿主 Activity（见 ProviderNav.open 的注释）。
                DhForm.Button(text = "去查看") {
                    ref.get()?.dismiss()
                    act.runOnUiThread {
                        runCatching { com.dollhouse.app.ui.provider.ProviderNav.open(act) }
                    }
                }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                DhForm.Button(text = "留在此页", primary = false) { ref.get()?.dismiss() }
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
    private class ApplyOutcome(val summary: String, val targets: List<Provider>)

    private fun apply(act: Activity, profiles: List<AexImport.AexProfile>, mode: Int): ApplyOutcome {
        val ctx: Context = act.applicationContext
        var created = 0
        var updated = 0
        var skipped = 0
        var modelsAdded = 0
        val failed = ArrayList<String>()
        // 【AEX 1.1】需要在导入成功后补拉 /models 的供应商。
        val targets = ArrayList<Provider>()

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
                        // 【v0.2.0·自家 JSON 的私有字段】覆盖模式下也补回去。
                        //   放在 saveProvider 之前，保证一次写入就带上全部字段。
                        applyDollhouseJson(ctx, p, same)
                        ProviderStore.saveProvider(ctx, same)
                        modelsAdded += addModels(ctx, same.id, p)
                        if (needsModelFetch(p)) {
                            targets.add(same)
                        }
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
                        // 【v0.2.0·自家 JSON 的私有字段】新建时同样要带上，
                        //   否则「备份 → 恢复」出来的供应商会和备份前行为不一致。
                        applyDollhouseJson(ctx, p, np)
                        ProviderStore.saveProvider(ctx, np)
                        modelsAdded += addModels(ctx, np.id, p)
                        if (needsModelFetch(p)) {
                            targets.add(np)
                        }
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
        return ApplyOutcome(sb.toString(), targets)
    }

    /**
     * 只有「没有模型列表」且不是自家 JSON 备份的条目才需要补拉。
     * 自家 JSON 是用户自己的完整备份，不该在恢复时偷偷联网。
     */
    private fun needsModelFetch(p: AexImport.AexProfile): Boolean {
        return p.sourceProvider == null &&
            p.models.isEmpty() &&
            p.defaultModel.isEmpty() &&
            p.hasBaseUrl && p.baseUrl.isNotBlank() &&
            p.hasApiKey && p.apiKey.isNotBlank()
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

    /**
     * AEX 1.1：导入后按 `{baseUrl}/models` 补拉模型。
     *
     * 【容错】任何一家失败都只记摘要，不影响已写入的供应商；网络失败、Key 无权限、
     *   接口不规范都算「没取到」，用户可以稍后到供应商详情里手动添加。
     * 【线程】[ProviderNav.fetchModels] 自己开线程并在主线程回调，这里不阻塞 UI。
     */
    private fun fetchModels(act: Activity, targets: List<Provider>, onDone: (String) -> Unit) {
        val ctx: Context = act.applicationContext
        var remaining = targets.size
        var added = 0
        val failed = ArrayList<String>()
        for (pv in targets) {
            ProviderNav.fetchModels(act, pv, object : ProviderNav.ModelsCallback {
                override fun onDone(models: MutableList<String>?, error: String?) {
                    if (models != null && models.isNotEmpty()) {
                        for (name in models) {
                            if (name.isBlank() || ModelStore.modelExists(ctx, pv.id, name, null)) {
                                continue
                            }
                            val m = AiModel()
                            m.id = ProviderStore.newId()
                            m.displayName = name
                            m.providerId = pv.id
                            m.kind = AiModel.KIND_CHAT
                            m.enabled = true
                            ModelStore.saveModel(ctx, m)
                            added++
                        }
                    } else {
                        failed.add(pv.name + "：" + (error ?: "接口没返回模型列表"))
                    }
                    remaining--
                    if (remaining == 0) {
                        val sb = StringBuilder()
                        if (added > 0) {
                            sb.append("已从服务端获取模型 ").append(added).append(" 个。")
                        }
                        if (failed.isNotEmpty()) {
                            if (sb.isNotEmpty()) {
                                sb.append("\n")
                            }
                            sb.append("以下供应商没取到模型，可稍后手动添加：\n")
                                .append(failed.joinToString("\n"))
                        }
                        if (sb.isEmpty()) {
                            sb.append("没有获取到模型，可稍后手动添加。")
                        }
                        onDone(sb.toString())
                    }
                }
            })
        }
    }

    /**
     * 【v0.2.0·预览只显示名称】
     *
     * 【为什么删掉端点与密钥三行】用户明确要求「这个界面只用显示主名字，下面那两行小小字不用显示」，
     *   并补充了动机：「断点和密钥在哪里展示 我怕 泄露」。
     *   弹窗是**导入前的确认页**，用户此刻要判定的只是「这几条要不要导」，
     *   名称足够；端点与密钥属于导入后到详情页/编辑页再看的细节。
     *   删掉后本弹窗不再调用 [AexImport.maskKey]，等于把这个页面上的密钥展示面整体移除。
     *
     * 【对齐方式】只剩一行文字后不再需要 `Top`：勾选框与单行名称居中对齐更稳，
     *   否则勾选字符会贴着行顶、视觉上下坠。
     *
     * 【名称去重】`provider` 与 `name` 相同时不再拼括号 —— 否则会渲染成
     *   「DeepSeek（DeepSeek）」，既占位又难看。
     */
    @Composable
    private fun PreviewRow(p: AexImport.AexProfile, checked: Boolean, onToggle: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = DhDesign.Space.sm.dp).pressable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 自绘勾选框：工程里没有现成 checkbox 组件，用字符保持视觉一致、零依赖。
            Text(
                text = if (checked) "☑" else "☐",
                color = if (checked) c.acc else c.sub,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = p.name + if (p.provider.isNotEmpty() && p.provider != p.name) "（" + p.provider + "）" else "",
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
}
