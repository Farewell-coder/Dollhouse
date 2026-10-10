package com.dollhouse.app.ui.provider

import android.app.Activity
import android.view.View
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import com.dollhouse.app.ai.ApiClient
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】添加 / 编辑供应商页。
 *
 * 【表单】名称 / API Key（密码态 + 显示切换）/ Base Url / 路径 → 三个开关 → 取消 / 保存。
 *        协议统一为「自定义（OpenAI 兼容）」：中转站与各家官网都按它对接，不再有协议页签。
 *
 * 【交互铁律】① 名称 / Key / BaseUrl 全由用户填，程序绝不覆盖；
 *        ② 保存做必填校验，漏写协议头自动补 https:// 并在提示里说明；
 *        ③ 保存成功后自动跑一次连通性测试，失败只提示、不回滚保存（规格书要求）。
 *
 * 【隐私】Key 输入框默认密码态；写盘走 KeyVault 加密，页面本身不落明文。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose。**对外契约一字未动**：
 *   仍是 `object` + `internal build / buildEmbedded(act, id): View`，仍由 [ProviderNav] 分派。
 */
object ProviderEditPage {

    /**
     * 内置常用端点：主流官网协议 + 常见中转站写法。
     * 【为什么内置】中转站地址每家不同、还常带 /v1，用户手抄极易漏版本段 ——
     *   而漏版本段正是「别的软件能用、这里连不上」的高频原因之一。
     * 【口径】一律带版本段；用户选完仍可自己改。
     */
    private val PRESETS = arrayOf(
        arrayOf("OpenAI", "https://api.openai.com/v1"),
        arrayOf("DeepSeek", "https://api.deepseek.com/v1"),
        arrayOf("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        arrayOf("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
        arrayOf("Kimi", "https://api.moonshot.cn/v1"),
        arrayOf("硅基流动", "https://api.siliconflow.cn/v1"),
        arrayOf("火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
        arrayOf("百度千帆", "https://qianfan.baidubce.com/v2"),
        arrayOf("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"),
        arrayOf("讯飞星火", "https://spark-api-open.xf-yun.com/v1"),
        arrayOf("阶跃星辰", "https://api.stepfun.com/v1"),
        arrayOf("OpenRouter", "https://openrouter.ai/api/v1"),
    )

    /** 独立页形态：从设置页直接进（不带胶囊底栏）。 */
    internal fun build(act: Activity, providerId: String?): View {
        return build(act, providerId, false, 0, null)
    }
    /**
     * 嵌入式形态：作为供应商详情页「配置」tab 的内容。
     * 【与独立页的差别只有一处】底部左键文案「取消」→「返回列表」。
     *   保存成功后留在本页这一点独立页本来就这样（保存分支只提示、不 back），无需分支。
     * 【为什么不复制一份页】复制会让「供应商编辑」出现两套样式，改一处必漏一处。
     */
    internal fun buildEmbedded(act: Activity, providerId: String?): View {
        return build(act, providerId, true, 0, null)
    }
    /**
     * 嵌入式形态（带让位与滚动上报）。
     *
     * 【为什么需要这两个参数】壳层（[ProviderDetailPage]）的悬浮玻璃底栏要两件事：
     *   ① 内容底部留出胶囊的高度，否则最后一行被盖住；
     *   ② 内容滚动时把底栏淡出 / 淡回。
     *   壳层是 View 树，拿不到 Compose 的 `scrollState`，也无法从 View 树里找到
     *   Compose 的滚动容器（`verticalScroll` 不是 `android.widget.ScrollView`）。
     *   故由本页把「让位 dp」与「滚动回调」交给 [DhKit.Page]，桥接不泄漏到壳层。
     */
    internal fun buildEmbedded(
        act: Activity,
        providerId: String?,
        bottomPadDp: Int,
        onScroll: ((y: Int) -> Unit)?
    ): View {
        return build(act, providerId, true, bottomPadDp, onScroll)
    }
    private fun build(
        act: Activity,
        providerId: String?,
        embedded: Boolean,
        bottomPadDp: Int,
        onScroll: ((y: Int) -> Unit)?
    ): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) {
            EditContent(act, providerId, embedded, bottomPadDp, onScroll)
        }
    }
    /**
     * 页面正文（Compose）。
     *
     * 【为什么状态全用 remember 而不是 View 句柄】View 版靠 `EditText` / `Switch` 句柄读值，
     *   Compose 侧表单状态就是一组 `mutableStateOf` —— 保存时直接读，不再有「句柄为空」的分支。
     */
    @Composable
    private fun EditContent(
        act: Activity,
        providerId: String?,
        embedded: Boolean,
        bottomPadDp: Int = 0,
        onScroll: ((y: Int) -> Unit)? = null
    ) {
        val c = DhTokens.colors
        val src = remember(providerId) {
            if (providerId == null || providerId.isEmpty()) null
            else ProviderStore.findProvider(act, providerId)
        }
        val created = src != null

        var name by remember(src?.id) { mutableStateOf(src?.name ?: "") }
        var key by remember(src?.id) { mutableStateOf(if (src == null) "" else ProviderStore.keyOf(act, src)) }
        var base by remember(src?.id) { mutableStateOf(src?.baseUrl ?: "") }
        var path by remember(src?.id) {
            // 新建页自动带上通用默认值；编辑页不动用户已存的值。
            mutableStateOf(if (src == null) ModelRules.defaultChatPath(Provider.PROTO_OPENAI) else src.chatPath)
        }
        var enabled by remember(src?.id) { mutableStateOf(src == null || src.enabled) }
        var respApi by remember(src?.id) { mutableStateOf(src != null && src.useResponseApi) }
        var resendReason by remember(src?.id) { mutableStateOf(src != null && src.resendHistoryReasoning) }
        var keyPlain by remember { mutableStateOf(false) }
        var hintText by remember { mutableStateOf<String?>(null) }
        var hintOk by remember { mutableStateOf(false) }
        // 【保存后必须留住 id】新建态保存成功才拿到真实 id；开关的即时写盘要用它，
        //   不能继续读 providerId（那还是进页时的空串，写盘会造出一条孤儿记录）。
        var pid by remember(src?.id) { mutableStateOf(src?.id ?: providerId ?: "") }
        var saved by remember { mutableStateOf(created) }

        // 新建态补上通用默认 Base Url（编辑态不动用户已存值）。
        if (!created && base.isEmpty()) {
            base = ModelRules.defaultBaseUrl(Provider.PROTO_OPENAI)
        }

        DhKit.Page(
            title = if (saved) "编辑供应商" else "添加供应商",
            sub = "自定义（OpenAI 兼容）",
            onBack = { ProviderNav.back(act) },
            bottomPadDp = bottomPadDp,
            onScroll = onScroll
        ) {
            // —— 主表单 ——
            DhKit.Card {
                DhForm.Input(name, { name = it }, label = "名称", hint = "例如：001")
                DhForm.Input(
                    value = key,
                    onValueChange = { key = it },
                    label = "API Key",
                    hint = "输入 API 密钥",
                    password = !keyPlain,
                    trailing = {
                        Text(
                            text = if (keyPlain) "隐藏" else "显示",
                            modifier = Modifier.pressable { keyPlain = !keyPlain },
                            color = c.acc,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fontsBold
                        )
                    }
                )
                DhForm.Input(base, { base = it }, label = "API Base Url", hint = "例如：https://api.deepseek.com/v1")
                DhForm.Input(path, { path = it }, label = "API 路径", hint = "默认 /chat/completions")
            }

            // —— 常用端点（一键填入，省得手抄漏 /v1） ——
            DhKit.Card {
                DhKit.SectionLabel("常用端点（点一下填进上面的 API Base Url）")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    for (p in PRESETS) {
                        DhForm.Chip(text = p[0], modifier = Modifier.padding(end = 6.dp)) {
                            base = p[1]
                        }
                    }
                }
            }

            // —— 地址预览：下单前先看清究竟会请求哪个 URL ——
            DhForm.Note(previewOf(base, path))

            // —— 开关组 ——
            DhKit.Card {
                DhKit.SectionLabel("开关")
                SwitchRow("启用", "关掉后它名下的模型不会出现在聊天页选择器里（数据保留）", enabled) { v ->
                    enabled = v
                    // 【响应性】供应商一被停用，它名下的模型全部退出可用集，可能直接让「打开聊天」入口失去条件；
                    //   与模型开关同样即时落盘 + 立刻重算，不依赖返回主页触发 onResume。
                    //   仅对已存在的供应商写盘：新增态还没落过盘，此时写盘会凭空造出一条记录。
                    if (saved && pid.isNotEmpty()) {
                        ProviderStore.setProviderEnabled(act, pid, v)
                    }
                    HomeUi.syncChatEntryNow(act)
                }
                SwitchRow("Response API", "开启后对话走 /responses 而不是 chat completions", respApi) { respApi = it }
                SwitchRow(
                    "回传历史思考过程",
                    "把历史消息里的思考内容再次随请求发出；关掉则剥离",
                    resendReason
                ) { resendReason = it }
            }

            if (hintText != null) {
                DhForm.Note(hintText!!, color = if (hintOk) c.ok else c.err)
            }

            // —— 底部：取消 / 测试连接 / 保存 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhForm.OutlineChip(if (embedded) "返回列表" else "取消") { ProviderNav.back(act) }
                Spacer(Modifier.weight(1f))
                DhForm.OutlineChip("测试连接") {
                    // 只测试不保存：用当前表单值直接打一次 /models，结果就地回显。
                    val raw = key.trim()
                    if (raw.isEmpty()) {
                        hintOk = false
                        hintText = "请填写 API Key"
                    } else {
                        val probe = buildProvider(pid, name, base, path, enabled, respApi, resendReason)
                        probe.apiKey = KeyVault.encrypt(raw)
                        if (probe.apiKey.isEmpty()) {
                            hintOk = false
                            hintText = "密钥加密失败，无法测试，请重试或重启应用后重填"
                        } else {
                            hintOk = true
                            hintText = "正在测试连接…"
                            ProviderNav.testConnection(act, probe, object : ProviderNav.TestCallback {
                                override fun onDone(ok: Boolean, detail: String?) {
                                    hintOk = ok
                                    hintText = detail
                                }
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                DhForm.PrimaryChip("保存") {
                    val r = save(act, src, pid, name, key, base, path, enabled, respApi, resendReason)
                    if (r.id != null) {
                        // 新建态保存成功后页面标题要变；栈顶路由也要换成真实 id，
                        // 否则切「模型」栏时详情页解析不出 id，会落到「供应商已不存在」兜底页。
                        pid = r.id
                        saved = true
                    }
                    hintOk = true
                    hintText = r.text
                    if (r.id != null) {
                        // 保存成功后自动跑一次连通性测试（规格书要求）：失败只提示，不回滚已存数据。
                        val savedPv = ProviderStore.findProvider(act, r.id)
                        if (savedPv != null) {
                            ProviderNav.testConnection(act, savedPv, object : ProviderNav.TestCallback {
                                override fun onDone(ok: Boolean, detail: String?) {
                                    hintOk = ok
                                    hintText = if (ok) "已保存 · " + detail else "已保存，但连接没通过：" + detail
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    /** 一行开关（左标题 + 可选说明，右开关）。 */
    @Composable
    private fun SwitchRow(name: String, desc: String?, on: Boolean, onToggle: (Boolean) -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, color = c.title, fontSize = UiKit.FS_BTN.sp, fontFamily = DhTokens.fonts)
                if (desc != null && desc.isNotEmpty()) {
                    Text(
                        text = desc,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            DhKit.Switch(checked = on, onCheckedChange = onToggle)
        }
    }

    /** 按当前表单值装配一个 Provider（不写盘）。 */
    private fun buildProvider(
        id: String,
        name: String,
        base: String,
        path: String,
        enabled: Boolean,
        respApi: Boolean,
        resendReason: Boolean
    ): Provider {
        val p = Provider()
        p.id = id
        p.name = name.trim()
        p.protocol = Provider.PROTO_OPENAI
        p.baseUrl = ModelRules.normalizeBaseUrl(base.trim())
        p.chatPath = path.trim()
        p.enabled = enabled
        p.useResponseApi = respApi
        p.resendHistoryReasoning = resendReason
        return p
    }

    /**
     * 复算并展示「对话地址 + 模型列表地址」。
     * 【口径】与真正发请求时同一套代码（ApiClient -> ModelRules/ApiEndpoint），
     *   页面上看到什么，请求就打向什么，不再有两个口径。
     */
    private fun previewOf(base: String, path: String): String {
        val p = Provider()
        p.protocol = Provider.PROTO_OPENAI
        p.baseUrl = ModelRules.normalizeBaseUrl(base.trim())
        p.chatPath = path.trim()
        val chat = ApiClient.chatUrl(p, "")
        if (chat.isEmpty()) {
            return "对话地址：还没填 API Base Url"
        }
        return "对话地址：" + chat + "\n模型列表：" + ApiClient.modelsUrl(p)
    }

    /** 保存结果：id 非空 = 已写盘。 */
    private class Saved(val id: String?, val text: String)

    /**
     * 保存。
     * 【顺序】先校验（不过就只提示、不写盘）→ 写盘 → 由调用方再跑连通性测试。
     */
    private fun save(
        act: Activity,
        src: Provider?,
        pid: String,
        name: String,
        key: String,
        base: String,
        path: String,
        enabled: Boolean,
        respApi: Boolean,
        resendReason: Boolean
    ): Saved {
        val created = src != null
        val p = buildProvider(pid, name, base, path, enabled, respApi, resendReason)
        val rawKey = key.trim()

        val err = ModelRules.validate(p)
        if (err.isNotEmpty()) {
            return Saved(null, err)
        }
        val rawBase = base.trim()
        val patched = rawBase.isNotEmpty() && !rawBase.startsWith("http://") && !rawBase.startsWith("https://")
        if (rawKey.isEmpty()) {
            return Saved(null, "请填写 API Key")
        }
        val all = ProviderStore.providers(act)
        if (ModelRules.nameExists(all, p.name, p.id)) {
            return Saved(null, "已有同名供应商，换一个名字")
        }
        val keep = if (created) ProviderStore.keyOf(act, ProviderStore.findProvider(act, p.id)) else ""
        p.apiKey = KeyVault.encrypt(rawKey)
        if (p.apiKey.isEmpty()) {
            if (created && rawKey == keep) {
                // 用户没改 Key（读回的就是明文），加密失败时保留原密文，不写空。
                val old = ProviderStore.findProvider(act, p.id)
                p.apiKey = if (old == null) "" else old.apiKey
            }
            if (p.apiKey.isEmpty()) {
                // 【修·必须】不再静默写空：否则存下去的就是「无密钥」，下次请求必 401。
                return Saved(null, "密钥加密失败，未能保存，请重试或重启应用后重填")
            }
        }
        if (p.id.isEmpty()) {
            p.id = ProviderStore.newId()
        }
        ProviderStore.saveProvider(act, p)
        val text = if (patched) "已保存（地址已补 https://），正在测试连接…" else "已保存，正在测试连接…"
        return Saved(p.id, text)
    }
}