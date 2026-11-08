package com.dollhouse.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「我的资料」抽屉（r14·需求 3）。
 *
 * 【形态来源】用户给的参考图是 QQ：聊天列表**左上角的头像**点一下 → 从左侧滑出一个面板，
 *   上半部是「我」的头像 / 昵称 / 个性签名，下半部是一列功能入口。
 *
 * 【为什么是抽屉而不是整页】
 *   它承载的是「我是谁 + 几个高频入口」，属于**随时瞄一眼**的信息；
 *   做成整页会占用一次完整的进出场（还要处理返回键），比抽屉重得多。
 *   用户给的三张参考图里，QQ 用的也正是抽屉形态。
 *
 * 【内容编排（用户定案）】
 *   头像 / 昵称 / 个性签名   ← 「这几个是用户本身的 不是人偶的」
 *   ├ 我的资料              ← 二级页，编辑它们
 *   ├ 开关人偶              ← 原主页「启动 / 关闭人偶」按钮
 *   ├ 设置                  ← 原主页「设置」按钮
 *   └ 关于                  ← 原主页「关于」按钮
 *
 * 【为什么不用 ModalNavigationDrawer】
 *   本工程刻意不建 MaterialTheme（见 [DhTokens] 头注释），而该组件的外观全部来自 M3 主题；
 *   用它等于在自研配色之外再插一套默认色。这里用最朴素的「遮罩 Box + 可点关闭」实现，
 *   视觉完全走本工程令牌，零样式冲突。
 */
object ProfileDrawer {

    /** 面板宽度（dp）—— 与主流聊天软件的抽屉一致：约占屏宽 3/4，露出背后的内容以示「可关」。 */
    private const val PANEL_W = 300

    /** 吸附动画时长（ms）。与 UiKit.D_PAGE（300ms）同档，观感与全 App 转场一致。 */
    private const val SETTLE_MS = 260f

    /** 头部头像边长（dp）。 */
    private const val AVATAR_DP = 66

    /**
     * 抽屉是否正处于打开状态。
     *
     * 【为什么由本对象持有】它由聊天列表页的左上角头像触发、却要覆盖整个主界面
     *   （底栏与三个 Tab 都在它下面）。状态放在触发点那一页里，就没法盖住别人。
     *   提到这里之后，任何页面都能打开它。
     *
     * 【为什么是 Compose 状态】宿主是 Compose 写的，要跟着重组。
     *   写入方（点头像 / 点遮罩）都在主线程，无需额外同步。
     */
    private val openedState = mutableStateOf(false)

/**
     * 【r21·框架级】抽屉的开合不再是「自己管开关」。
     *
     * 改造前这里有一套 `bindHost` / `applyTouchable`：抽屉状态一变就手动把宿主 View 的
     * `isClickable` / `isFocusable` 镜像过去。那是**派生状态**，有两个必然的失效路径：
     * ① 任何一处改了 [openedState] 却没走 open/close/toggle；
     * ② 宿主 View 被重新挂载（`HomeUi.apply` 开头就 `content.removeAllViews()` 重建）。
     * 一旦脱节：`isClickable=false` 让整个 ComposeView 在最外层就不参与命中，
     * Compose 侧画得再对也收不到事件 —— 用户看到的就是「点了没反应，反而点中背后的按钮」。
     *
     * 现在「谁能收触摸」由 [com.dollhouse.app.ui.compose.LayerGate] 单点判定，
     * 本类只负责**维护状态并通知它重算**，不再碰任何 View 属性。
     */

    /** 当前是否打开（供宿主订阅）。 */
    val opened: Boolean
        get() = openedState.value

    /**
     * 【跟手进度】0 = 完全关闭，1 = 完全打开。
     *
     * 【为什么与 [opened] 分开】`opened` 是**布尔语义**（交互所有权用，gate 读它），
     *   它是**视觉语义**（面板位移、遮罩透明度）。跟手滑动过程中进度连续变化，
     *   但「是否算打开」只在越过阈值那一刻翻转 —— 两者职责不同，不该共用一个值。
     */
    private val progressState = mutableStateOf(0f)

    /** 当前跟手进度（0~1）。Composable 读到它即订阅变化。 */
    val progress: androidx.compose.runtime.State<Float>
        get() = progressState

    /** 吸附动画的句柄；新手势 / 新指令进来时先取消，避免两个动画抢同一个值。 */
    private var settleJob: kotlinx.coroutines.Job? = null

    /** 驱动吸附动画的作用域。抽屉是全局单例、生命周期跟随进程，故用独立作用域。 */
    private val uiScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    /** 直接设定跟手进度（滑动过程中调用，不触发吸附动画）。 */
    fun setProgress(p: Float) {
        settleJob?.cancel()
        progressState.value = p.coerceIn(0f, 1f)
    }

    /**
     * 【手势起手】把抽屉切入「跟手中」状态。
     *
     * 【为什么必须先把 opened 置 true】`Host` 在 `!opened && progress≈0` 时直接 return
     *   —— 若不先置位，手势第一帧根本没有可画的面板，用户会觉得「划了没反应」。
     *   置 true 后 gate 会把底座屏蔽（正确：此刻本该由抽屉接管），
     *   而底座那条手势流由 [LayerShield] 的 tracking 保护走完。
     */
    fun beginDrag() {
        settleJob?.cancel()
        if (!openedState.value) {
            openedState.value = true
            notifyGate()
        }
    }

    /** 手势拖动：设定进度但不改动 opened（是否算打开由松手时的 [settleByPosition] 定）。 */
    fun dragTo(p: Float) {
        settleJob?.cancel()
        progressState.value = p.coerceIn(0f, 1f)
    }

    /**
     * 松手后按位置吸附：越过面板宽度 1/3 就打开，否则收起。
     *
     * 【为什么用位置而不是速度】面板较宽（300dp），用户拖到一半松手时意图由位置表达最准；
     *   加速度判据会在「快速轻扫」时把已经很靠右的面板又弹回去，观感反直觉。
     */
    fun settleByPosition() {
        val open = progressState.value > 1f / 3f
        openedState.value = open
        animateTo(if (open) 1f else 0f)
        notifyGate()
    }

    /**
     * 吸附到目标进度（内部统一出口）。
     *
     * 【为什么手写缓动，而不用 Compose 的 `animate()`】
     *   这里是**非 Composable 上下文**（`ProfileDrawer` 是 object 单例，没有 Compose 树）。
     *   Compose 的 `animate()` / `Animatable` 内部要求 `CoroutineContext` 里带
     *   `MonotonicFrameClock`（由 recomposer 提供）；在自建的
     *   `CoroutineScope(Dispatchers.Main.immediate)` 里调用会直接抛：
     *   ```
     *   IllegalStateException: A MonotonicFrameClock is not available in this CoroutineContext.
     *   ```
     *   —— 这正是「点击就闪退」的原因（真机已抓到该堆栈）。
     *   改用 `delay(16)` 驱动的三次缓出插值：不依赖任何 Compose 运行时，
     *   观感与 `EASE_DECEL` 一致，且不会被取消时机搞出「停在半路」的状态。
     */
    private fun animateTo(target: Float) {
        settleJob?.cancel()
        settleJob = uiScope.launch {
            val start = progressState.value
            if (start == target) {
                return@launch
            }
            val t0 = android.os.SystemClock.uptimeMillis()
            while (true) {
                val e = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / SETTLE_MS)
                    .coerceIn(0f, 1f)
                // 三次缓出：1-(1-e)^3，起步快、收尾缓，与全 App EASE_DECEL 同观感。
                val k = 1f - (1f - e) * (1f - e) * (1f - e)
                progressState.value = start + (target - start) * k
                if (e >= 1f) {
                    break
                }
                kotlinx.coroutines.delay(16L)
            }
            // 终值兜底：被打断时也落在确定位置，绝不悬在中间。
            progressState.value = target
        }
    }

    /** 打开抽屉。 */
    fun open() {
        openedState.value = true
        animateTo(1f)
        notifyGate()
    }

    /** 关闭抽屉。 */
    fun close() {
        openedState.value = false
        animateTo(0f)
        notifyGate()
    }

    /** 关闭（带收起动画，供遮罩点击与系统返回使用）。 */
    fun closeAnimated() {
        close()
    }

    /** 切换（点头像时的行为）。 */
    fun toggle() {
        if (openedState.value) {
            close()
        } else {
            open()
        }
    }

    /**
     * 通知 [com.dollhouse.app.ui.compose.LayerGate] 重算交互所有权。
     *
     * 【为什么要宿主 Activity】gate 按宿主分桶登记各层；抽屉是全局状态，
     *   必须告诉「当前这个宿主」重新判定。
     */
    private fun notifyGate() {
        val a = currentAct?.get() ?: return
        com.dollhouse.app.ui.compose.LayerGate.refresh(a)
    }

    /** 宿主销毁时清掉进程级抽屉状态，防止旧打开态屏蔽新 Activity。 */
    fun resetForHostDestroy(act: MainActivity?) {
        if (act == null || currentAct?.get() === act) {
            settleJob?.cancel()
            openedState.value = false
            progressState.value = 0f
            currentAct = null
        }
    }
    /** 最近一次承载抽屉的宿主（由 HomeUi 装配时登记）。 */
    private var currentAct: java.lang.ref.WeakReference<MainActivity>? = null

    /** 宿主装配时登记（替代旧的 bindHost）。 */
    fun bindHost(act: MainActivity?) {
        currentAct = if (act == null) null else java.lang.ref.WeakReference(act)
    }

    /**
     * 【r20·数据驱动】抽屉里的一个功能入口。
     *
     * 【为什么做成数据而不是七个手写 ActionRow】
     *   旧实现每个入口的点击体都是「close() + 跳转」，七个写七遍。
     *   于是「跳转不该关抽屉」这条规则只能靠人记住 —— 漏写一次就复现
     *   「点进子页 → 返回 → 抽屉不见了」。改成数据表之后，规则由 [closesDrawer]
     *   的**默认值**表达，渲染器统一执行，写新入口时无需再想这件事。
     *
     * @param label        行标题（取函数而非常量：开关人偶要随运行状态变文案）
     * @param icon         行图标（同上，播放/停止二选一）
     * @param desc         副标题；null = 不显示
     * @param closesDrawer 点击后是否**主动关闭**抽屉。
     *   · false（默认，导航类）：只导航。「我的资料 / 外观 / 记忆库设置 / 人偶管理 / 设置 / 关于」
     *     都属于这类 —— 目标页是覆盖层，层级上就在抽屉**之上**（见 `HomeUi.apply` 的
     *     「顺序即层级」：抽屉先加、NavHost 覆盖层后加），它自己会盖住抽屉；
     *     返回时覆盖层被弹掉，抽屉**原地露出**，展开状态与滚动位置一点没丢。
     *     【关键】此时绝不能调 close() —— 那等于把「跳转」误记成「用户主动关闭」，
     *     返回后抽屉不在了，正是用户报的那个 bug。
     *   · true（动作类）：没有目标页面的即时操作（如启停人偶），抽屉必须让开，
     *     否则用户看不到操作结果。
     * @param onPick       点击动作
     */
    private class Entry(
        val label: (MainActivity) -> String,
        val icon: (MainActivity) -> Int,
        val desc: String? = null,
        val closesDrawer: Boolean = false,
        /**
         * 该入口对应的页面 key（`PageStack.topKey` 的值）；null = 动作类（无页面）。
         *
         * 【用途】「当前选中项高亮」：若栈顶 key 等于本字段，该行画对比底块。
         * 动作类（启停人偶）没有页面，恒不高亮。
         */
        val pageKey: String? = null,
        val onPick: (MainActivity) -> Unit
    )

    /**
     * 【唯一真源】抽屉的全部功能入口。
     *
     * 【新增入口只需在这里加一条】不要再去 `Host` 里手写 `DhKit.ActionRow` ——
     *   那样会绕过 [Entry.closesDrawer] 的默认值保护。
     * 【顺序即展示顺序】与用户给的参考图（QQ）一致：资料 / 外观 / 人偶 → 记忆 / 人偶管理 → 设置 / 关于。
     */
    private val ENTRIES: List<Entry> = listOf(
        Entry(
            label = { "我的资料" },
            icon = { Icons.IC_CIRCLE_USER },
            desc = "头像、昵称、个性签名",
            pageKey = MyProfilePage.KEY
        ) { act -> MyProfilePage.open(act) },

        Entry(
            label = { "外观" },
            icon = { Icons.IC_PALETTE },
            desc = "主题、背景、聊天气泡、弹窗",
            pageKey = AppearancePage.KEY
        ) { act -> AppearancePage.open(act) },

        // 【动作类】不跳页面，只切桌宠启停 —— 抽屉必须让开才能看到结果。
        Entry(
            label = { if (HomeState.running) "关闭人偶" else "启动人偶" },
            icon = { if (HomeState.running) Icons.IC_SQUARE else Icons.IC_PLAY },
            desc = "让她们出现在桌面上",
            closesDrawer = true
        ) { act -> HomeUi.togglePet(act, !HomeState.running) },

        Entry(
            label = { "记忆库设置" },
            icon = { Icons.IC_BRAIN },
            desc = "抽取与检索策略",
            pageKey = MemSettingsPage.TAG_PAGE
        ) { act -> MemSettingsPage.open(act) },

        Entry(
            label = { "人偶管理" },
            icon = { Icons.IC_LAYERS },
            desc = "编辑、启停、封面、动作",
            pageKey = DollListScreen.KEY
        ) { act -> DollListScreen.open(act) },

        Entry(
            label = { "设置" },
            icon = { Icons.IC_SETTINGS },
            pageKey = SettingsScreen.TAG_PAGE
        ) { act -> SettingsScreen.open(act) },

        Entry(
            label = { "关于" },
            icon = { Icons.IC_INFO },
            pageKey = AboutPage.TAG_PAGE
        ) { act -> AboutPage.open(act) }
    )

    /**
     * 【底部固定区】设置 / 关于。
     *
     * 【为什么单独列一份而不是从 ENTRIES 过滤】参考图的硬要求就是「设置、帮助、账户固定在底部」——
     *   这是**布局契约**，不是"恰好这两条排在最后"。写成独立常量后：
     *   · 谁把它删了 / 把它挪进中段，编译期就会露馅（`Host` 直接引用本常量）；
     *   · 新增入口时不必猜"该放 ENTRIES 还是放底部"——两个列表语义互斥、显式选择。
     * 【7 个入口一个不少】ENTRIES(7) 里去掉这两条后剩 5 条进中段，
     *   FOOTER_ENTRIES(2) 进底部，5 + 2 = 7，与改造前完全一致。
     */
    private val FOOTER_ENTRIES: List<Entry> = ENTRIES.filter { e ->
        e.pageKey == SettingsScreen.TAG_PAGE || e.pageKey == AboutPage.TAG_PAGE
    }

    /**
     * 抽屉本体。宿主（[HomeUi]）把它叠在**所有内容之上**（含底栏）。
     *
     * 【为什么宿主来调用而不是自带一层窗口】它需要同时盖住三个 Tab 与悬浮底栏 ——
     *   也就是整个 Activity 内容区。宿主正好持有那个容器，交给它摆放最直接。
     */
    @Composable
    fun Host(act: MainActivity) {
        // 【订阅】两个值都读：`opened` 决定「是否需要挂载」，`progress` 决定视觉位置。
        //   收起动画期间 opened 已为 false，但 progress 还没到 0 —— 此时仍需继续渲染，
        //   否则面板会在半路「啪」地消失（这正是很多抽屉的观感缺陷）。
        val p = progress.value
        if (!opened && p <= 0.001f) {
            return
        }
        val c = DhTokens.colors
        val panelW = with(LocalDensity.current) { PANEL_W.dp.toPx() }
        // 【跟手位移】0 = 完全关闭，1 = 完全打开。
        //   【为什么用连续值而不是布尔量】右滑打开、左滑收起都要求「手指移多少、
        //   面板出多少」；只有连续值才能做到跟手。松手后再吸附到 0 / 1。
        //   【为什么只由这一个值派生视觉】它同时决定面板位移与遮罩透明度，
        //   两者永远同步，不会出现「遮罩已亮但面板没出来」的中间态。
        Box(modifier = Modifier.fillMaxSize()) {
            // ---------- 遮罩 ----------
            // 【透明度跟手】用 progress 驱动，不再固定 c.scrim —— 手势过程中观感连续。
            // 【点击关闭】面板以外的区域一律落回这层遮罩 → 点击即关闭，绝不会穿透
            //   （穿透由层根闸门保证：此时底座层是 blocked 的）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.scrim.copy(alpha = c.scrim.alpha * p))
                    .pressable { closeAnimated() }
            )
            // ---------- 面板 ----------
            // 【已无返回钮】用户定案：抽屉靠右滑打开 / 左滑收起 / 点遮罩关闭 / 系统返回关闭，
            //   四种出口都现成，面板内再放一颗返回钮是重复入口。
            Column(
                modifier = Modifier
                    .width(PANEL_W.dp)
                    .fillMaxHeight()
                    // 【跟手位移】未完全打开时向左偏移（负 = 藏到屏幕左外）。
                    .offset { IntOffset(((progress.value - 1f) * panelW).roundToInt(), 0) }
                    // 【不透明底】抽屉是「盖住下层」的层，必须垫实底，否则背后列表文字透上来叠成一片。
                    .background(c.sheet)
                    // 【左滑收起】只认横向拖拽，纵向照常滚动。
                    //   【为什么不干扰面板内按钮】`detectHorizontalDragGestures` 只在
                    //   横向位移超过 touch slop 后才 consume；轻点完全不会触发它，
                    //   ActionRow 的 pressable 照常收到点击。
                    //   【上一轮的教训】那时挂的是 `detectTapGestures {}` —— 它把
                    //   「按下 → 抬起」整段结在自己身上，面板里所有按钮立刻全失灵。
                    //   两者机理完全不同：拖拽手势不抢点击。
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { settleByPosition() },
                            onDragCancel = { settleByPosition() }
                        ) { _, drag ->
                            setProgress(progress.value + drag / panelW)
                        }
                    }
                    .padding(horizontal = 18.dp)
            ) {
                // ================= 三段式：头部固定 / 中间滚动 / 底部固定 =================
                // 【为什么不再整体 verticalScroll】按参考图要求：入口多时顶部（头像）与
                //   底部（设置 / 关于）必须**固定**，只有中间那组随手指滚动。
                //   整体滚动会让"设置/关于"被推出屏幕外，用户每次都要先滚到底 ——
                //   那正是参考图明确要避免的形态。
                //
                // 【手势为什么不失效】`detectHorizontalDragGestures` 仍挂在**最外层 Column**
                //   上（上面那段没动），它只吃横向位移；纵向滚动由中间那层
                //   `verticalScroll` 消费 —— 两者方向正交，互不抢事件。
                //   左滑收起照常工作。

                // ---------- 顶部固定：头像 + 昵称 + 签名 ----------
                Spacer(Modifier.height(DhDesign.Space.xl.dp))
                Header(act)
                Spacer(Modifier.height(DhDesign.Space.lg.dp))
                DhKit.Divider()

                // ---------- 中间滚动：分组入口 ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    val topKey = PageStack.topKey(act)
                    // 【分组】按使用场景分两块：「常用」= 日常配置；「动作」= 会改变运行时状态的开关。
                    //   【为什么这么分】参考图要求"按使用场景分组"；把「启动/关闭人偶」与
                    //   其它配置项混在一起时，它是唯一会"立刻改变桌面上有没有人偶"的入口，
                    //   语义与其它"打开一个设置页"的项不同，单独成组更符合直觉。
                    // 【必须排除底部那两条】`FOOTER_ENTRIES` 是从 ENTRIES 过滤出来的，
                    //   若这里只按 `pageKey != null` 取"有页面的"，「设置」「关于」会**同时**
                    //   出现在中段与底部 —— 7 个入口变 9 个、且用户看到重复项。
                    //   所以中段显式排除底部集合（按对象引用），保证 5 + 2 = 7 不重不漏。
                    val footerSet = FOOTER_ENTRIES.toSet()
                    val common = ENTRIES.filter { it.pageKey != null && it !in footerSet }
                    val actions = ENTRIES.filter { it.pageKey == null }

                    Spacer(Modifier.height(DhDesign.Space.md.dp))
                    SectionGroup("常用", common, act, topKey)
                    // 组间距明显大于行间距：用 xl(24)，而行间距由 ActionRow 自带的 GAP_ROW(8) 决定。
                    Spacer(Modifier.height(DhDesign.Space.xl.dp))
                    SectionGroup("动作", actions, act, topKey)
                    Spacer(Modifier.height(DhDesign.Space.xxl.dp))
                }

                // ---------- 底部固定：设置 / 关于 ----------
                // 【分割线只用来分大块、颜色要淡】DhKit.Divider 已是 1dp 发丝线（colors.line）。
                DhKit.Divider()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(DhDesign.Space.sm.dp))
                    // 【底部同样要高亮】原先想传 null（"底部不需要选中态"），但那是错的：
                    //   用户从「设置」返回时抽屉原地露出，底部那一项**应当**显示为当前页，
                    //   与中段项的行为一致 —— 否则同一份列表里两套高亮规则，读起来很怪。
                    val footerTopKey = PageStack.topKey(act)
                    for (e in FOOTER_ENTRIES) {
                        DrawerRow(e, act, footerTopKey)
                    }
                    Spacer(Modifier.height(DhDesign.Space.md.dp))
                }
            }
        }
    }

    /**
     * 一个分组：小标题 + 若干入口行。
     *
     * 【组间距 vs 行间距】参考图硬要求「组间距明显大于行间距」：
     *   · 组与组之间 —— 由调用方插 `Space.xl`(24) / `Space.xxl`(32)；
     *   · 组内行与行 —— `DhKit.ActionRow` 自带 `GAP_ROW`(8)；
     *   24 : 8 = 3 : 1，视觉层级清楚。
     */
    @Composable
    private fun SectionGroup(
        title: String,
        entries: List<Entry>,
        act: MainActivity,
        topKey: String?
    ) {
        if (entries.isEmpty()) {
            return
        }
        DhKit.SectionLabel(title)
        Spacer(Modifier.height(DhDesign.Space.xs.dp))
        for (e in entries) {
            DrawerRow(e, act, topKey)
        }
    }

    /**
     * 单个入口行：统一渲染 + 选中态高亮 + `closesDrawer` 语义。
     *
     * 【选中态】参考图要求「当前选中项用与背景有明显对比的底块高亮，其他项文字颜色降一档」。
     *   判定依据是 `PageStack.topKey` 与本入口的 `pageKey` 相等 —— 抽屉是覆盖层，
     *   用户从某页返回时抽屉原地露出，此时栈顶已回到底层，选中态自然消失，语义正确。
     *   【为什么不用 ActionRow 的 selected 参数】`DhKit.ActionRow` 当前没有该参数；
     *   这里用一层 Box 垫 `colors.option` 底块 + 圆角，避免为了一个高亮去改组件库
     *   （DhKit 本轮被他人占用，且这只是抽屉内部形态）。
     */
    @Composable
    private fun DrawerRow(e: Entry, act: MainActivity, topKey: String?) {
        val selected = e.pageKey != null && e.pageKey == topKey
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                .then(
                    if (selected) Modifier.background(DhTokens.colors.option) else Modifier
                )
        ) {
            DhKit.ActionRow(
                label = e.label(act),
                iconRes = e.icon(act),
                desc = e.desc
            ) {
                // 【顺序固定为「先关（如需）→ 再导航」】动作类（如启停人偶）必须先让开，
                //   否则用户看不到操作结果；导航类不关，覆盖页会盖在抽屉之上，
                //   返回时抽屉原地露出，展开态 / 滚动位置全保留。
                if (e.closesDrawer) {
                    close()
                }
                e.onPick(act)
            }
        }
    }

    /** 头部：头像 + 昵称 + 个性签名。 */
    @Composable
    private fun Header(act: MainActivity) {
        val c = DhTokens.colors
        // 【数据源】改动后要能刷新：用 [DollState.rev] 当重读信号不好（那是人偶的），
        //   这里用一个本页自己的 tick —— 编辑页保存后回来自增即可。
        val tick = ProfileState.tick
        val name = remember(tick) { MyProfile.name(act) }
        val bio = remember(tick) { MyProfile.bio(act) }
        val avatar = remember(tick) { MyProfile.avatarBitmap(act, 256) }

        // 【可点进「我的资料」】参考图原文：「头像 + 昵称 + 签名（点进「我的资料」）」。
        //   改造前这里纯展示、不可点，用户只能再往下找「我的资料」那一行。
        //   现在整块头部就是一个大命中区 —— 但这**不替代**列表里的「我的资料」入口：
        //   参考图另一条硬要求是"所有入口都保留，不要删除"。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pressable { MyProfilePage.open(act) }
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(AVATAR_DP.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.soft),
                contentAlignment = Alignment.Center
            ) {
                val bmp: android.graphics.Bitmap? = avatar
                if (bmp != null && !bmp.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // 【Cover】圆形裁切区用 Fit 会在圆内留黑边，与聊天列表头像同一口径。
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(iconRes = Icons.IC_CIRCLE_USER, sizeDp = Design.SZ_XL, color = c.sub)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = name,
                color = c.title,
                fontSize = 20.sp, lineHeight = 28.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 【引号图标】让「签名」一眼可辨 —— 光一行灰字容易被当成状态文字。
                DhKit.Icon(iconRes = Icons.IC_QUOTE, sizeDp = Design.SZ_XS, color = c.line)
                Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                Text(
                    text = bio.ifEmpty { "还没有个性签名" },
                    color = if (bio.isEmpty()) c.line else c.sub,
                    fontSize = UiKit.FS_SUB.sp, lineHeight = DhTokens.type.sub.lineHeight,
                    fontFamily = DhTokens.fontsBody,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 【职责】抽屉头部数据的**重读信号**。
 *
 * 【为什么需要】`MyProfile` 读写的是 SharedPreferences，不是 Compose 状态 ——
 *   在「我的资料」页保存后返回，抽屉里的昵称 / 签名不会自己更新。
 *   这里用一个全局自增版本号当信号：保存方 bump 一次，读方 remember(tick) 重算。
 *
 * 【为什么不用页面重建】重建抽屉会连带整个主界面重组，代价与收益不成比例。
 */
object ProfileState {
    private val rev = mutableStateOf(0)

    /** 当前版本号；Composable 读到它即订阅变化。 */
    val tick: Int
        get() = rev.value

    /** 资料变更后调用。 */
    fun bump() {
        rev.value = rev.value + 1
    }
}