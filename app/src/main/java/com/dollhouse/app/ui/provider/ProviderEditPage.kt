package com.dollhouse.app.ui.provider

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.ai.ApiClient
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit

/**
 * 【职责】添加 / 编辑供应商页。
 *
 * 【表单】名称 / API Key（密码态 + 眼睛）/ Base Url / 路径 → 三个开关 → 取消 / 保存。
 *        协议统一为「自定义（OpenAI 兼容）」：中转站与各家官网都按它对接，不再有协议页签。
 *
 * 【交互铁律】① 名称 / Key / BaseUrl 全由用户填，程序绝不覆盖；
 *        ② 保存做必填校验，漏写协议头自动补 https:// 并在提示里说明；
 *        ③ 保存成功后自动跑一次连通性测试，失败只提示、不回滚保存（规格书要求）。
 *
 * 【隐私】Key 输入框默认密码态；写盘走 KeyVault 加密，页面本身不落明文。
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

    /** 当前编辑中的表单状态（每次进页重建一份，不存静态，避免多实例串味）。 */
    private class Form {
        var id: String = ""
        var protocol: String = Provider.PROTO_OPENAI
        var created: Boolean = false
        var name: EditText? = null
        var key: EditText? = null
        var base: EditText? = null
        var path: EditText? = null
        var enabled: UiKit.Switch? = null
        var respApi: UiKit.Switch? = null
        var resendReason: UiKit.Switch? = null
        var hint: TextView? = null
        var preview: TextView? = null
    }

    /** 独立页形态：从设置页直接进（不带胶囊底栏）。 */
    internal fun build(act: Activity, providerId: String?): View {
        return build(act, providerId, false)
    }

    /**
     * 嵌入式形态：作为供应商详情页「配置」tab 的内容。
     * 【与独立页的差别只有一处】底部左键文案「取消」→「返回列表」。
     *   保存成功后留在本页这一点独立页本来就这样（保存分支只提示、不 back），无需分支。
     * 【为什么不复制一份页】复制会让「供应商编辑」出现两套样式，改一处必漏一处。
     */
    internal fun buildEmbedded(act: Activity, providerId: String?): View {
        return build(act, providerId, true)
    }

    private fun build(act: Activity, providerId: String?, embedded: Boolean): View {
        val ctx: Context = act
        val f = Form()
        val src = if (providerId == null || providerId.isEmpty())
            null else ProviderStore.findProvider(ctx, providerId)
        if (src != null) {
            f.id = src.id
            f.protocol = src.protocol
            f.created = true
        }

        val root = ApiPageKit.pageRoot(ctx)
        root.addView(
            UiKit.topBar(ctx, if (f.created) "编辑供应商" else "添加供应商",
                "自定义（OpenAI 兼容）", View.OnClickListener {
                    ProviderNav.back(act)
                })
        )

        val host = ApiPageKit.contentHost(ctx)


        // —— 主表单 ——
        val box = ApiPageKit.card(ctx)
        val nameField = ApiPageKit.labeledInput(ctx, box, "名称", "例如：001", false)
        f.name = nameField
        val keyField = ApiPageKit.labeledInput(ctx, box, "API Key", "输入 API 密钥", true)
        f.key = keyField
        addEye(ctx, box, keyField)
        val baseField = ApiPageKit.labeledInput(ctx, box, "API Base Url", "例如：https://api.deepseek.com/v1", false)
        f.base = baseField
        val pathField = ApiPageKit.labeledInput(ctx, box, "API 路径", "默认 /chat/completions", false)
        f.path = pathField
        if (src != null) {
            nameField.setText(src.name)
            keyField.setText(ProviderStore.keyOf(ctx, src))
            baseField.setText(src.baseUrl)
            pathField.setText(src.chatPath)
        }
        host.addView(box)

        // —— 常用端点（一键填入，省得手抄漏 /v1） ——
        val pre = ApiPageKit.card(ctx)
        pre.addView(ApiPageKit.sectionTitle(ctx, "常用端点（点一下填进上面的 API Base Url）"))
        pre.addView(presetRow(ctx, baseField))
        host.addView(pre)

        // —— 地址预览：下单前先看清究竟会请求哪个 URL ——
        val preview = ApiPageKit.note(ctx, "")
        f.preview = preview
        host.addView(preview)
        bindPreview(f)
        refreshPreview(f)

        // —— 开关组 ——
        val sw = ApiPageKit.card(ctx)
        sw.addView(ApiPageKit.sectionTitle(ctx, "开关"))
        f.enabled = switchRow(ctx, sw, "启用", "关掉后它名下的模型不会出现在聊天页选择器里（数据保留）",
            src == null || src.enabled)
        f.respApi = switchRow(ctx, sw, "Response API",
            "开启后对话走 /responses 而不是 chat completions", src != null && src.useResponseApi)
        f.resendReason = switchRow(ctx, sw, "回传历史思考过程",
            "把历史消息里的思考内容再次随请求发出；关掉则剥离", src != null && src.resendHistoryReasoning)
        host.addView(sw)


        val hint = ApiPageKit.note(ctx, "")
        f.hint = hint
        hint.visibility = View.GONE
        host.addView(hint)

        // —— 底部：取消 / 保存 ——
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, ApiPageKit.dp(ctx, 14), 0, ApiPageKit.dp(ctx, 8))
        val cancel = UiKit.outlineChip(ctx, if (embedded) "返回列表" else "取消")
        cancel.setOnClickListener {
            ProviderNav.back(act)
        }
        bar.addView(cancel)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1.0f))
        val test = UiKit.outlineChip(ctx, "测试连接")
        test.setOnClickListener {
            runTest(ctx, f, false)
        }
        val tlp2 = LinearLayout.LayoutParams(-2, -2)
        tlp2.rightMargin = ApiPageKit.dp(ctx, 8)
        bar.addView(test, tlp2)
        val save = UiKit.primaryChip(ctx, "保存")
        save.setOnClickListener {
            runTest(ctx, f, true)
        }
        bar.addView(save)
        host.addView(bar)

        root.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))
        // 新建页自动带上通用默认值；编辑页不动用户已存的值。
        if (src == null) {
            baseField.setText(ModelRules.defaultBaseUrl(f.protocol))
            pathField.setText(ModelRules.defaultChatPath(f.protocol))
        }
        return root
    }

    /** 密码框的「显示 / 隐藏」切换。 */
    private fun addEye(ctx: Context, box: LinearLayout, field: EditText) {
        val eye = TextView(ctx)
        eye.text = "显示"
        eye.setTextSize(UiKit.FS_SUB)
        eye.setTextColor(UiKit.ACC)
        eye.typeface = Typeface.DEFAULT_BOLD
        eye.isClickable = true
        eye.gravity = Gravity.RIGHT
        eye.setPadding(ApiPageKit.dp(ctx, 2), ApiPageKit.dp(ctx, 6), ApiPageKit.dp(ctx, 2), 0)
        eye.setOnClickListener {
            val now = field.inputType
            val plain = (now and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
            // 【修·必须】切换密码态会重排文字并把光标甩到末尾，
            //   用户接着敲的字符就会跑到已经填好的 Key 后面（输入被改写）。
            //   这里先存下原选区，改完再放回去。
            val cs = field.text
            val len = cs?.length ?: 0
            var selStart = Math.min(field.selectionStart, len)
            var selEnd = Math.min(field.selectionEnd, len)
            if (selStart < 0) {
                selStart = len
            }
            if (selEnd < 0) {
                selEnd = len
            }
            field.inputType = InputType.TYPE_CLASS_TEXT or
                (if (plain) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                 else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            field.setSelection(Math.min(selStart, selEnd), Math.max(selStart, selEnd))
            eye.text = if (plain) "隐藏" else "显示"
        }
        box.addView(eye)
    }

    /** 常用端点横向条：每项点一下把地址填进 Base Url 并把光标放到末尾。 */
    private fun presetRow(ctx: Context, base: EditText): View {
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, ApiPageKit.dp(ctx, 8), 0, 0)
        for (i in PRESETS.indices) {
            val url = PRESETS[i][1]
            val c = UiKit.chip(ctx, PRESETS[i][0])
            c.setOnClickListener {
                base.setText(url)
                val ed = base.text
                base.setSelection(if (ed == null) 0 else ed.length)
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = ApiPageKit.dp(ctx, 6)
            row.addView(c, lp)
        }
        hs.addView(row)
        return hs
    }

    /** 监听 Base Url / 路径，实时刷新地址预览。 */
    private fun bindPreview(f: Form) {
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(e: Editable?) {
                refreshPreview(f)
            }
        }
        if (f.base != null) {
            f.base?.addTextChangedListener(w)
        }
        if (f.path != null) {
            f.path?.addTextChangedListener(w)
        }
    }

    /**
     * 复算并展示「对话地址 + 模型列表地址」。
     * 【口径】与真正发请求时同一套代码（ApiClient -> ModelRules/ApiEndpoint），
     *   页面上看到什么，请求就打向什么，不再有两个口径。
     */
    private fun refreshPreview(f: Form?) {
        val preview = f?.preview
        if (f == null || preview == null || preview.parent == null) {
            return
        }
        val p = Provider()
        p.protocol = f.protocol
        p.baseUrl = ModelRules.normalizeBaseUrl(text(f.base))
        p.chatPath = text(f.path)
        val chat = ApiClient.chatUrl(p, "")
        if (chat.isEmpty()) {
            preview.setText("对话地址：还没填 API Base Url")
            return
        }
        preview.setText("对话地址：" + chat + "\n模型列表：" + ApiClient.modelsUrl(p))
    }

    /** 一行开关，返回句柄供保存时读值。 */
    private fun switchRow(ctx: Context, dest: LinearLayout, name: String,
                          desc: String?, on: Boolean): UiKit.Switch {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, ApiPageKit.dp(ctx, 10), 0, ApiPageKit.dp(ctx, 10))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        col.addView(t)
        if (desc != null && desc.length > 0) {
            val d = TextView(ctx)
            d.text = desc
            d.setTextSize(UiKit.FS_TINY)
            d.setTextColor(UiKit.SUB)
            d.setPadding(0, ApiPageKit.dp(ctx, 2), 0, 0)
            col.addView(d)
        }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1.0f))
        val sw = UiKit.Switch(ctx)
        sw.setOn(on)
        sw.isClickable = true
        sw.setOnClickListener {
            sw.setOn(!sw.isOn(), true)
        }
        r.addView(sw)
        dest.addView(r)
        return sw
    }

    private fun text(e: EditText?): String {
        return if (e == null || e.text == null) "" else e.text.toString().trim()
    }

    /**
     * 保存 + 连通性测试。
     * 【顺序】先校验（不过就只提示、不写盘）→ 写盘 → 再跑测试（失败不影响已保存的数据）。
     */
    private fun runTest(ctx: Context, f: Form, save: Boolean) {
        val p = Provider()
        p.id = f.id
        p.name = text(f.name)
        p.protocol = f.protocol
        p.baseUrl = ModelRules.normalizeBaseUrl(text(f.base))
        p.chatPath = text(f.path)
        p.enabled = f.enabled == null || f.enabled!!.isOn()
        p.useResponseApi = f.respApi != null && f.respApi!!.isOn()
        p.resendHistoryReasoning = f.resendReason != null && f.resendReason!!.isOn()
        val rawKey = text(f.key)

        if (save) {
            val err = ModelRules.validate(p)
            if (err.length > 0) {
                hint(ctx, f, false, err)
                return
            }
            // 用户漏写协议头时自动补上，并在提示里说清。
            val rawBase = text(f.base)
            val patched = rawBase.length > 0 && !rawBase.startsWith("http://") &&
                !rawBase.startsWith("https://")
            if (rawKey.length == 0) {
                hint(ctx, f, false, "请填写 API Key")
                return
            }
            val all = ProviderStore.providers(ctx)
            if (ModelRules.nameExists(all, p.name, p.id)) {
                hint(ctx, f, false, "已有同名供应商，换一个名字")
                return
            }
            val keep = if (f.created) ProviderStore.keyOf(ctx, ProviderStore.findProvider(ctx, p.id)) else ""
            p.apiKey = KeyVault.encrypt(rawKey)
            if (p.apiKey.isEmpty()) {
                if (f.created && rawKey == keep) {
                    // 用户没改 Key（读回的就是明文），加密失败时保留原密文，不写空。
                    val old = ProviderStore.findProvider(ctx, p.id)
                    p.apiKey = if (old == null) "" else old.apiKey
                }
                if (p.apiKey.isEmpty()) {
                    // 【修·必须】不再静默写空：否则存下去的就是「无密钥」，下次请求必 401。
                    hint(ctx, f, false, "密钥加密失败，未能保存，请重试或重启应用后重填")
                    return
                }
            }
            if (p.id.isEmpty()) {
                p.id = ProviderStore.newId()
            }
            ProviderStore.saveProvider(ctx, p)
            f.id = p.id
            f.created = true
            if (patched) {
                f.base?.setText(p.baseUrl)
            }
            // 保存成功后自动跑一次连通性测试（规格书要求）：失败只提示，不回滚已存数据。
            // 页面不重建 —— 重建会把这条提示一起清掉，用户就看不到测试结果了。
            hint(ctx, f, true, "已保存，正在测试连接…")
            ProviderNav.testConnection(ctx, p, object : ProviderNav.TestCallback {
                override fun onDone(ok: Boolean, detail: String?) {
                    hint(ctx, f, ok, if (ok) "已保存 · " + detail else "已保存，但连接没通过：" + detail)
                }
            })
            return
        }
        // 只测试不保存：用当前表单值直接打一次 /models。
        if (rawKey.length == 0) {
            hint(ctx, f, false, "请填写 API Key")
            return
        }
        p.apiKey = KeyVault.encrypt(rawKey)
        if (p.apiKey.isEmpty()) {
            hint(ctx, f, false, "密钥加密失败，无法测试，请重试或重启应用后重填")
            return
        }
        hint(ctx, f, true, "正在测试连接…")
        ProviderNav.testConnection(ctx, p, object : ProviderNav.TestCallback {
            override fun onDone(ok: Boolean, detail: String?) {
                hint(ctx, f, ok, detail)
            }
        })
    }

    private fun hint(ctx: Context, f: Form, ok: Boolean, detail: String?) {
        val h = f.hint
        if (h == null || h.parent == null) {
            return
        }
        h.setText(if (detail == null) "" else detail)
        h.setTextColor(if (ok) UiKit.OK else UiKit.ERR)
        UiKit.reveal(h)
    }
}
