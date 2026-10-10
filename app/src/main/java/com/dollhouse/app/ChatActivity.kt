package com.dollhouse.app

import android.app.Activity
import android.os.Bundle
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】聊天页面的宿主 Activity（独立窗口，可从桌面图标或桌宠点进来）。
 *
 * 【交互】只负责建一个 ChatPanel 并把它铺满内容视图；所有对话逻辑都在 ChatPanel 里。
 *
 * 【坑】桌宠侧走的是迷你输入框（PetTalkInput）+ 气泡，另有一条独立的全屏页路径；
 *       两条路径共用 ChatPanel。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class ChatActivity : Activity() {
    private var panel: ChatPanel? = null

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // 先按当前主题档位把配色刷进 UiKit，再建界面。
        ThemeManager.apply(this)
        // 【接收处夹紧】apply 后立刻夹紧正文/副标题/提示对比度，并记基线供就地换主题重映射。
        UiKit.clampPaletteContrast()
        ThemeRefresh.rememberPalette()
        val chatPanel = ChatPanel(this, object : ChatPanel.Controller {
            override fun onDrag(f: Float, f2: Float) {
            }

            override fun onOpenTokenStat() {
                TokenStat.open(this@ChatActivity)
            }

            override fun onClose() {
                this@ChatActivity.finish()
                // 【丝滑】关闭全屏聊天页时淡出，不用系统默认的硬切。
                this@ChatActivity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, true)
        this.panel = chatPanel
        setContentView(chatPanel)
        // 【状态栏嵌入】聊天页同样铺满 + 状态栏透明；ChatPanel 顶栏自己让出高度。
        UiKit.applyEdgeToEdge(this)
        this.panel!!.refreshHint()
        // 【状态恢复】系统日夜切换触发的重建：把滚动位置回填回去（本类未登记任何输入框，
        //   Bundle 通道不含任何明文文本）。滚动仍走既有对齐机制，行为不变。
        ThemeRefresh.restoreState(window?.decorView, bundle)
        // 【纯内存草稿】在途输入（含 API Key / 地址 / 模型名 / 聊天正文）只经
        //   onRetainNonConfigurationInstance 跨重建传递：不进 Bundle / 文件 / 日志；
        //   在界面构建完成后回填，回填即清空。（getLastNonConfigurationInstance 仅 onCreate 内有效）
        ThemeRefresh.restoreInputs(window?.decorView,
                lastNonConfigurationInstance as? ThemeRefresh.InputDraft)
        // 从悬浮窗带 extra 跳进来时，落地就顺手把统计页叠上（延后一拍，等内容视图量完）。
        if (PAGE_TOKEN == pageExtra()) {
            chatPanel.post {
                TokenStat.open(this@ChatActivity)
            }
        }
    }

    /** 重建保命：存下滚动位置与页面路由，供 [ThemeRefresh.restoreState] 回填。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putAll(ThemeRefresh.saveState(window?.decorView))
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 【重建保命·纯内存】配置变更重建时，把当前树上全部在途输入采集为纯内存草稿跨实例传递；
     *   刻意不用 Bundle：隐私文本绝不落盘、不进日志。回填后由 [ThemeRefresh.restoreInputs] 清空。
     */
    override fun onRetainNonConfigurationInstance(): Any? {
        return ThemeRefresh.captureInputs(window?.decorView)
    }

    /** 本次启动要顺带打开的页面标识；没有就返回 null。 */
    private fun pageExtra(): String? {
        return try {
            if (intent == null) null else intent.getStringExtra(EXTRA_PAGE)
        } catch (unused: Throwable) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        val chatPanel = this.panel
        if (chatPanel != null) {
            chatPanel.refreshHint()
            // 【修·聊天背景不生效】用户去设置页选完背景图再回来时，本 Activity 可能没销毁、
            //   直接复用旧的 ChatPanel（singleTop），只在构造时调过一次 applyBackground()，
            //   于是「选完图聊天页背景没变化」。每次回前台重套一次即可（幂等）。
            chatPanel.applyBackground()
        }
    }

    /**
     * 【修·返回键】抽屉开着时先关抽屉，而不是直接退出聊天页。
     *   原实现没有这个方法，ChatPanel.closeDrawerIfOpen() 全工程零调用点 ——
     *   左侧抽屉一打开，按返回键就整个退出去，只能点遮罩关。
     *   与 MainActivity 的处理顺序保持一致：先让页面内的浮层吃掉返回键，再交给系统。
     */
    override fun onBackPressed() {
        val chatPanel = this.panel
        if (chatPanel != null && chatPanel.closeDrawerIfOpen()) {
            return
        }
        super.onBackPressed()
    }

    companion object {
        /** 进本页后要顺带打开的页面标识（目前只有「令牌消耗统计」）。 */
        const val EXTRA_PAGE = "page"
        const val PAGE_TOKEN = "token_stat"
    }
}
