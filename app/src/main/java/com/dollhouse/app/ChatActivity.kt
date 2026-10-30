package com.dollhouse.app

import android.app.Activity
import android.os.Bundle
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.compose.PageStack
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
        // 【r20·撤掉「自动切会话」】r19 曾在这里加过「currentId 没绑人偶就切到第一只启用人偶」，
        //   那是误判：真凶是 ChatPanel.renderHistory 的硬编码占位气泡（已删），不在会话选择上。
        //   【为什么必须撤】自聊池（conv_current）的会话本就不绑人偶，dollOfConv 返回空即触发，
        //   结果会把主动想自聊的用户强制切到人偶会话 —— 属于修 bug 时新造的回归。
        //   会话切换仍由用户在各入口显式选择（聊天列表 / 卡片），本页不再自作主张。
        val chatPanel = ChatPanel(this, object : ChatPanel.Controller {
            override fun onDrag(f: Float, f2: Float) {
            }

            override fun onOpenTokenStat() {
                TokenStat.open(this@ChatActivity)
            }

            override fun onClose() {
                PageTransitions.finish(this@ChatActivity)
            }
        }, true)
        this.panel = chatPanel
        setContentView(chatPanel)
        addContentView(com.dollhouse.app.ui.compose.ComposeHost.createView(this) {
            com.dollhouse.app.ui.compose.OperationRecorderButton()
        }, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
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
            // 【r13 修 bug·「返回再进来就中断 AI 发话」】回前台重铺一次历史。
            //
            // 【为什么需要】用户发出消息后立刻返回列表，模型回来时视图可能已 detach ——
            //   那条回复由 [com.dollhouse.app.ui.chat.ChatPanel.handleReply] 照常落盘，
            //   但**没有气泡可加**。回到本页时若不重铺，屏幕上就看不到它，
            //   用户会以为「AI 没回 / 中断了」。
            //   reloadHistory 会从盘上读回完整历史并整屏重铺，把离屏期间那轮补上。
            //
            // 【幂等】重铺只是「按盘上数据重建界面」，不产生任何写入；
            //   没有离屏消息时重铺结果与现状一致，不会闪。
            chatPanel.reloadHistory()
        }
        // 【K-02】本页也会挂覆盖页（点「Token 统计」进 TokenStat），回前台的交互闸门同样要重算，
        //   否则「从别的应用切回来，Token 统计页点不动、或者下层被穿过」。
        com.dollhouse.app.ui.home.ResumeSettle.settle(this)
    }

    /**
     * 【修·返回键】先让页面内的覆盖层吃掉返回键，再交给系统。
     * 【r13·入口收拢】原先还要先关「左侧会话抽屉」——抽屉已移除（会话切换统一走
     *   聊天列表页），这里只剩覆盖层一层判断，语义更简单。
     */
    override fun onBackPressed() {
        // 【层级】本页也会挂覆盖层（点「Token 统计」进 TokenStat）：先退层，再退页。
        //   与 MainActivity 同一优先级口径 —— 浮层 > 覆盖层 > 宿主自身的退栈。
        if (PageStack.handleBack(this)) {
            return
        }
        PageTransitions.finish(this)
    }

    /**
     * 【层级回收】本页对应的页面层序分桶随实例一起丢掉，避免层内 View 被常驻引用。
     *   ChatActivity 不做路由存档（重建后不还原覆盖层），故这里只回收、不保存。
     */
    override fun onDestroy() {
        PageStack.reset(this)
        super.onDestroy()
    }

    companion object {
        /** 进本页后要顺带打开的页面标识（目前只有「令牌消耗统计」）。 */
        const val EXTRA_PAGE = "page"
        const val PAGE_TOKEN = "token_stat"
    }
}
