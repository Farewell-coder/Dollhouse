package com.dollhouse.app.ui.provider

import android.app.Activity
import android.view.View
import androidx.compose.foundation.horizontalScroll
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
import com.dollhouse.app.ai.ApiClient
import com.dollhouse.app.ai.ApiEndpoint
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
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.Design

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

    /**
     * 【v0.1.4】按已填地址反查它属于哪个内置端点，用于「选择服务」行回显。
     * 【为什么只做前缀包含】编辑老供应商时地址可能是用户自己改过的（末尾多一个 /v1/、
     *   或中转站套了一层），严格相等就一个都匹配不上；只要能认出是哪家就够用。
     * 【匹配顺序】按 PRESETS 顺序首次命中即返回，与「先 OpenAI 后中转站」的内置顺序一致。
     */
    private fun presetNameOf(url: String?): String? {
        val u = ApiEndpoint.ensure(url)
        if (u.isEmpty()) {
            return null
        }
        for (p in PRESETS) {
            val pu = ApiEndpoint.ensure(p[1])
            if (pu.isNotEmpty() && (u == pu || u.startsWith(pu + "/"))) {
                return p[0]
            }
        }
        return null
    }

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
        var base by remember(src?.id) {
            // 只在首次进入新建页时给默认值；用户之后删空或修改都不得被重组覆盖。
            mutableStateOf(src?.baseUrl ?: if (src == null) ModelRules.defaultBaseUrl(Provider.PROTO_OPENAI) else "")
        }
        var path by remember(src?.id) {
            // 新建页自动带上通用默认值；编辑页不动用户已存的值。
            mutableStateOf(if (src == null) ModelRules.defaultChatPath(Provider.PROTO_OPENAI) else src.chatPath)
        }
        var enabled by remember(src?.id) { mutableStateOf(src == null || src.enabled) }
        var respApi by remember(src?.id) { mutableStateOf(src != null && src.useResponseApi) }
        var resendReason by remember(src?.id) { mutableStateOf(src != null && src.resendHistoryReasoning) }
        // 【v0.1.4】明文 HTTP 授权：默认关（与改造前的安全口径一致），按供应商单独放开。
        var allowCleartext by remember(src?.id) { mutableStateOf(src != null && src.allowCleartext) }
        var keyPlain by remember { mutableStateOf(false) }
        var hintText by remember { mutableStateOf<String?>(null) }
        var hintOk by remember { mutableStateOf(false) }
        // 【保存后必须留住 id】新建态保存成功才拿到真实 id；开关的即时写盘要用它，
        //   不能继续读 providerId（那还是进页时的空串，写盘会造出一条孤儿记录）。
        var pid by remember(src?.id) { mutableStateOf(src?.id ?: providerId ?: "") }
        var saved by remember { mutableStateOf(created) }
        // 【v0.1.4】常用端点选择弹窗开关 + 当前选中项的名称（仅用于回显，不参与保存逻辑）。
        var presetSheet by remember { mutableStateOf(false) }
        var pickedName by remember(src?.id) {
            mutableStateOf(presetNameOf(src?.baseUrl))
        }

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
                // 【r16·图标】密钥属敏感信息，配一枚钥匙图标提醒「这里填的是凭据」。
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    DhKit.Icon(iconRes = Icons.IC_KEY_ROUND, sizeDp = Design.SZ_XS, color = c.sub)
                    Spacer(Modifier.width(6.dp))
                    DhForm.Note("密钥只加密存在本机，不会上传到我们这边。")
                }
                DhForm.Input(base, { base = it }, label = "API Base Url", hint = "例如：https://api.deepseek.com/v1")
                DhForm.Input(path, { path = it }, label = "API 路径", hint = "默认 /chat/completions")
            }

            // —— 常用端点（一键填入，省得手抄漏 /v1） ——
            // 【v0.1.4·改点击弹选择框】原来是一排横向滚动的 Chip：12 个端点只露出前 4 个，
            //   右边没有渐隐也没有箭头，用户根本看不出「还能往右滑」，以为只有这几个。
            //   改成「一行入口 + 弹窗列表」：入口行把当前填入的服务名显示出来，
            //   点开后在弹窗里一次性看全所有端点（纵向列表，不用猜能不能滑）。
            DhKit.Card {
                DhKit.SectionLabel("常用端点（点一下填进上面的 API Base Url）", iconRes = Icons.IC_PLUG)
                DhKit.ValueRow(
                    label = "选择服务",
                    value = pickedName ?: "未选择",
                    iconRes = Icons.IC_PLUG,
                    desc = "内置 12 家官网与中转站写法，也可选「空白模板」自己填",
                    onClick = { presetSheet = true }
                )
                DhForm.Note("自建或中转站常用「空白模板」自己填；填 http:// 地址需要打开下面的「允许明文 HTTP」。")
            }

            // —— 地址预览：下单前先看清究竟会请求哪个 URL ——
            DhForm.Note(previewOf(base, path))

            // —— 开关组 ——
            DhKit.Card {
                DhKit.SectionLabel("开关", iconRes = Icons.IC_SLIDERS_HORIZONTAL)
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
                // 【v0.1.4】明文 HTTP 授权。
                //   【为什么必须明说风险】打开后 Key 与聊天内容会以明文经过网络，
                //     同网段的人可以直接读到；这不是「高级选项」而是「安全让位」，
                //     说明文字必须写到用户能看懂后果的程度。
                SwitchRow(
                    "允许明文 HTTP",
                    "仅在你这台服务只支持 http:// 时打开。开启后密钥与聊天内容会明文传输，" +
                        "同一网络下的其他人可能读到。https:// 地址无需打开。",
                    allowCleartext
                ) { allowCleartext = it }
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
                DhForm.OutlineChip("测试连接", iconRes = Icons.IC_PLUG) {
                    // 只测试不保存：用当前表单值直接打一次 /models，结果就地回显。
                    val raw = key.trim()
                    if (raw.isEmpty()) {
                        hintOk = false
                        hintText = "请填写 API Key"
                    } else {
                        val probe = buildProvider(pid, name, base, path, enabled, respApi, resendReason, allowCleartext)
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
                    val r = save(act, src, pid, name, key, base, path, enabled, respApi, resendReason, allowCleartext)
                    if (r.id != null) {
                        // 新建态保存成功后页面标题要变；栈顶路由也要换成真实 id，
                        // 否则切「模型」栏时详情页解析不出 id，会落到「供应商已不存在」兜底页。
                        pid = r.id
                        saved = true
                        // 【v0.1.4·修「保存了却显示尚未保存」】
                        //   这里原先漏了这一步：只更新了本页的 pid / saved 两个本地状态，
                        //   没有把**路由栈顶**从「新建态」换成「真实 id 的详情路由」。
                        //   而切 tab 时详情页壳是现从栈顶解析 id 的（见
                        //   ProviderDetailPage 里 `ProviderNav.currentDetailId(providerId)`），
                        //   栈顶还是空 id → 解析出空串 → 模型页判定为新建态 →
                        //   显示「这个供应商还没保存」。
                        //   画面证据：配置页明明写着「已保存·连接正常（HTTP 200）」，
                        //   模型页却是「尚未保存」，两页对同一件事给出相反结论。
                        ProviderNav.replaceDetailNew(r.id)
                        // 通知外层壳：栈顶 id 已从空变成真实 id，按新 id 重建内容层。
                        //   不通知的话，用户「保存后不切 tab」时左侧仍是旧的占位/空表状态，
                        //   切到「模型」也只在 id 未变时才复用缓存 —— 两条路都得有这一步。
                        ProviderNav.notifyNewProviderSaved()
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

        // 【v0.1.4·常用端点选择弹窗】
        //   【为什么放 Page 外面】弹窗是另一层 Composable 树，放进 Page 的滚动内容里
        //     会跟着列表一起滚，语义也不对。放在页面根级，与滚动无关。
        if (presetSheet) {
            DhForm.Alert(
                title = "选择常用端点",
                onDismiss = { presetSheet = false },
                negText = "取消"
            ) {
                // 【为什么纵向铺开而不是再排一次横向】这里一次性把所有端点摆在眼前，
                //   不需要用户去猜「还能不能往右滑」——这正是原设计的核心缺陷。
                // 【为什么限高 + 纵向滚动】12 家 + 空白模板共 13 行，带说明的行每行约 60dp，
                //   全铺开会顶出屏幕（矮屏机型下底部几项直接看不见、也点不到）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (p in PRESETS) {
                        val hit = p[0] == pickedName
                        DhKit.ValueRow(
                            label = p[0],
                            value = if (hit) "已选" else null,
                            valueTint = c.acc,
                            iconRes = if (hit) Icons.IC_CHECK else Icons.IC_PLUG,
                            desc = p[1],
                            onClick = {
                                base = p[1]
                                pickedName = p[0]
                                presetSheet = false
                                hintOk = true
                                hintText = "已填入「" + p[0] + "」的地址，如与你手上的不同可直接改上面的输入框"
                            }
                        )
                    }
                    // 【空白模板】给第三方 / 自建服务留一个入口。
                    //   【为什么只清地址】名称、Key、路径都是用户自己填过的内容，
                    //     清掉等于逼他重填一遍；而地址是唯一必须换的东西。
                    DhKit.ValueRow(
                        label = "空白模板",
                        value = if (pickedName == null) "当前" else null,
                        valueTint = c.acc,
                        iconRes = Icons.IC_PLUG,
                        desc = "清空地址自己填，适合第三方中转站或自建服务",
                        onClick = {
                            base = ""
                            path = ModelRules.defaultChatPath(Provider.PROTO_OPENAI)
                            pickedName = null
                            presetSheet = false
                            hintOk = true
                            hintText = "地址已清空，请填你自己的 API Base Url（支持 http:// 与 https://）"
                        }
                    )
                }
            }
        }
    }
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
        resendReason: Boolean,
        allowCleartext: Boolean
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
        p.allowCleartext = allowCleartext
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
        resendReason: Boolean,
        allowCleartext: Boolean
    ): Saved {
        val created = src != null
        val p = buildProvider(pid, name, base, path, enabled, respApi, resendReason, allowCleartext)
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