package com.dollhouse.app.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.BackgroundCropActivity
import com.dollhouse.app.ChatActivity
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.R
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.chat.ChatSettingsSection
import com.dollhouse.app.ui.settings.SettingsPage
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】首页全部控件的搭建：标题栏、人偶图、启停按钮、聊天设置输入区、背景与透明度、页脚说明。
 * 【入口】MainActivity.onCreate 里 setContentView(HomeScreenBuilder.build(this))。
 * 【交互】建好的控件写回 MainActivity 的包级字段，供 refresh / ChatSettingsSection 使用；
 *         搭完后依次交给 SettingsPage 切卡片、HomeUi 做二次重排。
 * 【坑】控件顺序与文案是 SettingsPage 分组匹配、HomeUi 重排的契约：
 *       标题文字、三个输入框 hint 前缀、按钮文本一旦改动，首页会整体退回未整理状态。
 */
object HomeScreenBuilder {

    /**
     * 按「实际显示宽度」解码人偶首屏图。
     * 【为何需要】原实现直接 decodeResource 出 391×512 全图，只为在首页显示 190dp（≈570px
     *   宽屏下更小）；ARGB8888 一张就占 0.76MB，冷启动白掏内存。
     *   这里用 inSampleSize 把解码尺寸压到不超过目标宽度的 2 倍，肉眼无差、内存降到 1/4。
     */
    private fun decodePet(ctx: Context, targetPx: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeResource(ctx.resources, R.drawable.pet_front, o)
            if (o.outWidth <= 0) {
                return null
            }
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetPx && sample * 2 <= 8) {
                sample *= 2
            }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeResource(ctx.resources, R.drawable.pet_front, o2)
        } catch (ignored: Throwable) {
            return null
        }
    }

    /** 搭出首页根视图；控件实例通过 act 的包级字段回传。 */
    @JvmStatic
    fun build(act: MainActivity): View {
        val scroll = ScrollView(act)
        scroll.setBackgroundColor(UiKit.OPTION)

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val pad = Math.round(act.dp(20.0f))
        // 【状态栏高度不在这里留】本 box 经 HomeUi 拆分后只作「设置页」骨架，
        //   而设置页首行就是 HomeCards.buildHeader → UiKit.topBar，它自带状态栏留白。
        //   这里再留一次会变成双倍（实测标题被推到状态栏下方 100dp 以外）。
        //   顶部也归零：顶栏自己已有 12dp 上内边距，这才是标准 appBar padding。
        box.setPadding(pad, 0, pad, pad)
        scroll.addView(box, ViewGroup.LayoutParams(-1, -2))

        buildHeaderSection(act, box)
        buildLegacyButtons(act, box)
        buildChatSettingsSection(act, box)
        buildLookSection(act, box)
        return scroll
    }

    /** 首页头部：标题 / 副标题 / 人偶 / 状态行。
     *  【不可改】这四个控件依次是 box 的前 4 个 child，HomeUi.apply 按索引取用。 */
    private fun buildHeaderSection(act: MainActivity, box: LinearLayout) {
        // 【v2.10.0】标题美化：字号加大、加粗、加字间距，颜色仍走 UiKit.TITLE（跟随主题）。
        // 【v2.10.1】字号 30sp → 36sp、顶部留白 6dp → 34dp：标题整体下移并再放大一档，
        //   与下方人偶区的距离拉开，视觉重心不再贴着屏幕顶部。
        val title = TextView(act)
        title.text = "Dollhouse"
        title.setTextSize(36.0f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.06f
        title.setTextColor(UiKit.TITLE)
        title.gravity = Gravity.CENTER
        title.setPadding(0, Math.round(act.dp(34.0f)), 0, Math.round(act.dp(4.0f)))
        box.addView(title)

        val subtitle = TextView(act)
        subtitle.text = "\u70b9\u5979\u8bf4\u8bdd \u00b7 \u62d6\u7740\u8d70 \u00b7 \u957f\u6309\u6253\u5f00\u8fd9\u4e2a\u9762\u677f"
        subtitle.setTextSize(UiKit.FS_SUB)
        subtitle.setTextColor(UiKit.SUB)
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, Math.round(act.dp(4.0f)), 0, Math.round(act.dp(14.0f)))
        box.addView(subtitle)

        val pet = ImageView(act)
        pet.setImageBitmap(decodePet(act, Math.round(act.dp(190.0f))))
        pet.adjustViewBounds = true
        val petLp = LinearLayout.LayoutParams(Math.round(act.dp(190.0f)), -2)
        petLp.gravity = Gravity.CENTER
        box.addView(pet, petLp)

        act.status = TextView(act)
        act.status.setTextSize(UiKit.FS_SUB)
        act.status.setTextColor(UiKit.TITLE)
        act.status.gravity = Gravity.CENTER
        act.status.setPadding(0, Math.round(act.dp(12.0f)), 0, Math.round(act.dp(12.0f)))
        box.addView(act.status)
    }

    /** 旧版入口按钮：悬浮窗权限 / 启动桌宠 / 停止桌宠 / 打开聊天（由 HomeUi 拆除并复用）。 */
    private fun buildLegacyButtons(act: MainActivity, box: LinearLayout) {
        // 1. 悬浮窗权限
        act.overlayBtn = mkButton(act, "1. \u6388\u4e88\u300c\u663e\u793a\u5728\u5176\u4ed6\u5e94\u7528\u4e0a\u5c42\u300d")
        act.overlayBtn.setOnClickListener {
            act.requestOverlay()
        }
        UiKit.primary(act.overlayBtn, act)
        box.addView(act.overlayBtn)

        // 2. 启动桌宠
        act.startBtn = mkButton(act, "2. \u542f\u52a8\u684c\u5ba0")
        act.startBtn.setOnClickListener {
            act.onStartClicked()
        }
        box.addView(act.startBtn)

        // 停止桌宠
        act.stopBtn = mkButton(act, "\u505c\u6b62\u684c\u5ba0")
        act.stopBtn.setOnClickListener {
            val intent = Intent(act, PetService::class.java)
            intent.action = PetService.ACTION_STOP
            act.startService(intent)
            // 【v2.10.0】原用 act.status 做延迟句柄：状态行已被首页移除（不再挂在视图树上），
            //   未 attach 的 View 的 postDelayed 只会排进 runqueue、永远不会执行。
            //   改用 DecorView 当句柄，它任何时候都在树上。
            act.window.decorView.postDelayed({
                act.refresh()
            }, 300L)
        }
        box.addView(act.stopBtn)

        // 打开聊天
        val openChat = mkButton(act, "\u6253\u5f00\u804a\u5929\uff08\u70b9\u684c\u5ba0\u4e5f\u80fd\u8fdb\uff09")
        openChat.setOnClickListener {
            act.startActivity(Intent(act, ChatActivity::class.java))
            // 【丝滑】跳全屏聊天页时淡入，不用系统默认的硬切。
            act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        box.addView(openChat)
    }

    /** 聊天设置分组：三个输入框 + 保存/测试同排 + 结果行。 */
    private fun buildChatSettingsSection(act: MainActivity, box: LinearLayout) {
        // 聊天设置分组标题（SettingsPage 按此文本识别分组，不可改）。
        box.addView(sectionTitle(act, "\u804a\u5929\u8bbe\u7f6e\uff08\u4e91\u7aef API\uff09"))

        act.keyInput = mkInput(act, "\u8f93\u5165 API \u5bc6\u94a5", "", true)
        act.modelInput = mkInput(act, "\u4f8b\u5982\uff1adeepseek-v4-flash\uff1b\u70b9\u53f3\u4fa7\u56fe\u6807\u62c9\u53d6\u53ef\u7528\u5217\u8868", "", false)
        act.urlInput = mkInput(act, "\u4f8b\u5982\uff1ahttps://api.openai.com/v1/chat/completions", "", false)
        box.addView(act.keyInput)
        box.addView(act.modelInput)
        box.addView(act.urlInput)

        // 保存 / 测试按钮同排；SettingsProfilePanel 会把这一排整体搬到「模型配置」下方。
        val buttonBar = LinearLayout(act)
        buttonBar.orientation = LinearLayout.HORIZONTAL

        val saveLp = LinearLayout.LayoutParams(0, -2, 1.0f)
        saveLp.topMargin = Math.round(act.dp(8.0f))
        saveLp.rightMargin = Math.round(act.dp(4.0f))
        val save = Button(act)
        save.text = "\u4fdd\u5b58\u8bbe\u7f6e"
        styleBarButton(save, act)
        save.layoutParams = saveLp
        save.setOnClickListener {
            ChatSettingsSection.saveSettings(act)
        }
        buttonBar.addView(save)

        val testLp = LinearLayout.LayoutParams(0, -2, 1.0f)
        testLp.topMargin = Math.round(act.dp(8.0f))
        testLp.leftMargin = Math.round(act.dp(4.0f))
        val test = Button(act)
        test.text = "\u6d4b\u8bd5\u8fde\u63a5"
        styleBarButton(test, act)
        test.layoutParams = testLp
        test.setOnClickListener {
            ChatSettingsSection.testConnection(act)
        }
        buttonBar.addView(test)
        box.addView(buttonBar)

        act.testResult = TextView(act)
        act.testResult.setTextSize(UiKit.FS_SUB)
        act.testResult.setTextColor(UiKit.TITLE)
        act.testResult.setPadding(0, Math.round(act.dp(10.0f)), 0, 0)
        // 打标签：SettingsProfilePanel 据此把它从卡片尾部摘出来紧贴「测试连接」下方（修复结果看不见）。
        act.testResult.tag = SettingsPage.TAG_TEST_RESULT
        box.addView(act.testResult)
    }

    /** 操作方式说明 + 聊天背景选图/清除/透明度。 */
    private fun buildLookSection(act: MainActivity, box: LinearLayout) {
        // 操作方式
        box.addView(sectionTitle(act, "\u64cd\u4f5c\u65b9\u5f0f"))
        box.addView(hintText(act, 0,
                "\u00b7 \u5355\u51fb\u5979\uff1a\u8df3\u4e00\u4e0b\uff0c\u5e76\u968f\u673a\u8bf4\u4e00\u53e5\n"
                        + "\u00b7 \u8fde\u70b9\u4e09\u4e0b\uff1a\u53ec\u5524\u8ff7\u4f60\u8f93\u5165\u6846\uff0c\u53d1\u6d88\u606f\u8ddf\u5979\u804a\n"
                        + "\u00b7 \u62d6\u52a8\u5979\uff1a\u62d6\u5230\u5c4f\u5e55\u4e24\u4fa7\u8fb9\u7f18\u4f1a\u5438\u9644\u5e76\u63a2\u5934\uff0c\u4e22\u5728\u4e2d\u95f4\u5c31\u505c\u5728\u539f\u5730"))

        // 聊天背景
        box.addView(sectionTitle(act, "\u804a\u5929\u80cc\u666f"))
        act.bgBtn = mkButton(act, "")
        act.bgBtn.tag = HomeCards.TAG_BG_PICK
        act.bgBtn.setOnClickListener {
            // 【背景裁剪】原先进 PickFileActivity 选图后直接铺满聊天页，比例不对就被拉伸；
            //   现在跳裁剪页：选图 → 框选预览 → 按框落盘，聊天页拿到的永远不变形。
            try {
                act.startActivity(Intent(act, BackgroundCropActivity::class.java))
            } catch (t: Throwable) {
                Logs.w("DollhouseHome", "ignored", t)
            }
        }
        box.addView(act.bgBtn)

        val clearBg = mkButton(act, "\u6e05\u9664\u80cc\u666f")
        clearBg.tag = HomeCards.TAG_BG_CLEAR
        clearBg.setOnClickListener {
            PetPrefs.setChatBackground(act, "")
            act.notifyPetService()
            act.refreshLocalUi()
        }
        box.addView(clearBg)

        // 【需求】原「选一张图当聊天页的背景…」两段说明文字整段删除（用户定案）。
        act.bgAlphaLabel = TextView(act)
        act.bgAlphaLabel.setTextSize(UiKit.FS_SUB)
        act.bgAlphaLabel.setTextColor(UiKit.TITLE)
        act.bgAlphaLabel.setPadding(0, Math.round(act.dp(10.0f)), 0, 0)
        box.addView(act.bgAlphaLabel)

        val alpha = UiKit.Slider(act)
        alpha.setMax(100)
        alpha.setProgress(PetPrefs.chatBgAlpha(act))
        // 【d17】由系统 SeekBar 换成自绘 UiKit.Slider：胶囊轨道 + 刻度点 + 终点标记 + 实时百分比，
        //   形状由本 App 自己画（系统 SeekBar 的拇指/轨道样式随 ROM 走，tintList 只能改色不能改形，
        //   做不出参考图那种形态）；配色读 UiKit.ACC / LINE / TITLE，莫奈切换时自动整体变色。
        alpha.setOnChange(object : UiKit.Slider.OnChange {
            override fun onChanged(value: Int, fromUser: Boolean) {
                PetPrefs.setChatBgAlpha(act, value)
                act.updateBgAlphaLabel(value)
                if (fromUser) {
                    act.notifyPetService()
                }
            }
        })
        val alphaLp = LinearLayout.LayoutParams(-1, -2)
        alphaLp.topMargin = Math.round(act.dp(4.0f))
        box.addView(alpha, alphaLp)
        act.updateBgAlphaLabel(PetPrefs.chatBgAlpha(act))
    }

    /** 分组标题：16sp 加粗标题色，上间距 24dp。 */
    @JvmStatic
    fun sectionTitle(act: MainActivity, text: String): TextView {
        val t = TextView(act)
        t.text = text
        t.setTextSize(16.0f)
        t.setTextColor(UiKit.TITLE)
        t.setPadding(0, Math.round(act.dp(24.0f)), 0, Math.round(act.dp(4.0f)))
        return t
    }

    /** 说明小字：12sp 副色，可指定上间距。 */
    @JvmStatic
    fun hintText(act: MainActivity, topDp: Int, text: String): TextView {
        val t = TextView(act)
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(UiKit.SUB)
        if (topDp > 0) {
            t.setPadding(0, Math.round(act.dp(topDp.toFloat())), 0, 0)
        }
        t.text = text
        return t
    }

    /** 次按钮：白底描边、全宽、上间距 8dp。 */
    @JvmStatic
    fun mkButton(act: MainActivity, text: String): Button {
        val b = Button(act)
        b.text = text
        b.isAllCaps = false
        UiKit.secondary(b, act)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Math.round(act.dp(10.0f))
        b.layoutParams = lp
        return b
    }

    /** 单行输入框；password=true 时掩码显示。 */
    @JvmStatic
    fun mkInput(act: MainActivity, hint: String, value: String?, password: Boolean): EditText {
        val e = EditText(act)
        e.hint = hint
        e.setText(value ?: "")
        e.setTextSize(UiKit.FS_BTN)
        e.isSingleLine = true
        e.inputType = if (password) 524433 else 524289
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Math.round(act.dp(6.0f))
        e.layoutParams = lp
        return e
    }

    /** 同排按钮统一样式：走 UiKit 次按钮（白底描边），与首页其它按钮保持一致。 */
    private fun styleBarButton(b: Button, act: MainActivity) {
        b.isAllCaps = false
        b.setTextSize(UiKit.FS_BTN)
        b.typeface = Typeface.DEFAULT_BOLD
        val pad = Math.round(act.dp(14.0f))
        b.setPadding(pad, pad, pad, pad)
        UiKit.secondary(b, act)
    }
}
