package com.dollhouse.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.io.FileOutputStream

/**
 * 【职责】人偶封面的「选图 → 按三种卡片比例各裁一次 → 保存」一站式页面。
 *
 * 【需求原文】「还有这个选自己的图片 请定位到相册 并且得有编辑图片功能 像第二幅图一样
 *   但是 比例得重新调整 符合那个比例 同时 得选择三次比例 上面有明确的第几次 截取就是
 *   下一个比例返回就是上一个比例 因为三种排列方式大小不同」。
 *
 * 【为什么要裁三次】人偶列表支持 1 / 2 / 3 列三种排布（见 `DollListScreen` 的列数菜单），
 *   而卡片封面框的宽高比随列数变化很大（1 列 0.68 / 2 列 0.90 / 3 列 1.21，推导见
 *   [PetPrefs.DOLL_COVER_RATIOS]）。同一张方图铺到这三种框里，要么两侧留白、
 *   要么裁掉主体。所以让用户**按每种比例各框一次** —— 他虽然只上传一张图，
 *   但三种排布下看到的都是他亲自定的构图。
 *
 * 【三次怎么走】第 1 次裁完点「下一个比例」进第 2 次，第 3 次裁完点「保存」收工；
 *   顶部有明确的「第 N / 3 次」指示与当前比例名，随时可点「上一个比例」回退重裁
 *   （之前的框选结果会被记住，回来时原样恢复，不用重来）。
 *
 * 【与背景裁剪页的区别】`BackgroundCropActivity` 是「一次裁一张、写进聊天背景」；
 *   本页是「连裁三张、按列数落进人偶封面」。两者的交互骨架相似（拖动 / 双指缩放 / 网格），
 *   但**结果语义与落盘路径完全不同**，硬合成一个会立刻出现一堆 `if (是封面)` 分支。
 *   故另开一页，只共享「裁剪视图」这段几何（各自持有自己的实现，因为封面框是**固定比例**、
 *   背景框是**自适应比例**，几何口径本就不一样）。
 *
 * 【落盘】三张裁好的图分别存成 `cover1.png` / `cover2.png` / `cover3.png` 到人偶目录，
 *   并把「当前列数对应那一张」写进 [DollStore.Doll.cover]；切列数时由列表页按需换图
 *   （见 [DollStore.setCoverForCols]）。
 */
class CoverCropActivity : Activity() {

    private var crop: CropView? = null

    /** 目标人偶 id（从 Intent 带入）。 */
    private var dollId: String = ""

    /** 当前是第几次裁剪（0 基：0 = 一列 / 1 = 两列 / 2 = 三列）。 */
    private var step = 0

    /**
     * 每一步各自记住的框选状态。
     *
     * 【为什么必须分开存】用户在三种比例下来回切时，每次的构图偏好不一样
     *   （竖图在 1 列下要往上取，横图在 3 列下要往左取）。共用一个状态会让
     *   回退时看到「上一次比例留下的偏移」，非常困惑。
     *   索引与 [PetPrefs.DOLL_COVER_RATIOS] 一一对应。
     */
    private val savedMatrix = arrayOfNulls<FloatArray>(3)

    /**
     * 当前步骤的**只读镜像**，供 Compose 画「第 N / 3 次」与主按钮文案。
     *
     * 【为什么不把 [step] 本身变成 Compose 状态】[step] 不只是界面状态：
     *   [cropOne] 在保存时会把 step 临时改成 0 / 1 / 2（借它切比例算选区），算完再还原。
     *   一旦它成为 Compose 状态，等于把「落盘循环的游标」暴露给重组 ——
     *   文案与几何的时序会互相牵连，而这正是这类页面最容易出事的地方。
     *   所以这里只镜像一个副本：**几何仍由 [refreshStep] 命令式驱动，Compose 只读不写。**
     */
    private val uiStep = mutableStateOf(0)

    /**
     * 裁剪切台的浮层配色（黑画布 + 照片之上）。
     *
     * 【为什么不走 DhTokens.palette】本页刻意不跟主题：`window.statusBarColor` 被强制成黑色，
     *   界面压在照片上。浅色主题的卡片色在照片上会糊成一片。
     *   这四个值逐项对应改造前的 `roundStroke(0x1AFFFFFF, 0x66FFFFFF)` / `(0x14FFFFFF, 0x59FFFFFF)`。
     */
    private val overlayFg = androidx.compose.ui.graphics.Color.White
    private val overlayBg = androidx.compose.ui.graphics.Color(0x1AFFFFFF)
    private val overlayLine = androidx.compose.ui.graphics.Color(0x66FFFFFF)
    private val toolBg = androidx.compose.ui.graphics.Color(0x14FFFFFF)
    private val toolLine = androidx.compose.ui.graphics.Color(0x59FFFFFF)

    /** 顶栏 / 底栏实测占用高度（像素），由页面量出来后回填给 CropView。 */
    private var insetTop = 0
    private var insetBottom = 0

    private fun applyInsets() {
        val c = crop
        if (c != null) {
            c.setInsets(insetTop, insetBottom)
        }
    }

    /**
     * 【修·点「换封面」同样会闪退】与 BackgroundCropActivity 完全同因：
     *   原实现 `PageTransitions.finish(this)` 内部又调 `activity.finish()`，
     *   虚分派回到本覆盖方法 → 无限递归 → StackOverflowError。
     *   【正确写法】`super.finish()` 才是父类的真收尾，动画在后面自己套。
     */
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !android.animation.ValueAnimator.areAnimatorsEnabled()
        ) {
            overridePendingTransition(0, 0)
        } else {
            overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        dollId = intent?.getStringExtra(EXTRA_DOLL) ?: ""
        if (dollId.isEmpty()) {
            Logs.w(TAG, "[无目标] 没带 dollId，直接收尾")
            finish()
            return
        }
        // 【重建】不重弹相册：本页要活到系统把选图结果投递回来。
        //   重建路径下界面照常搭出，等结果自己回来。
        window.statusBarColor = Color.BLACK
        setContentView(buildUi())
        UiKit.applyEdgeToEdge(this)
        if (bundle == null) {
            // 【修·「裁剪页一弹出来就消失」】原来在这里直接 `pick()`，
            //   而此刻窗口还没 attach 完成（本页是 Theme.Translucent，attach 更靠后）。
            //   部分 ROM / 系统版本上，此时 `startActivityForResult` 会**立刻以 CANCELED 回调**，
            //   于是 onActivityResult 走「没选图」分支 → finish() → 用户看到的就是「一闪就没了」。
            //   改到 decorView.post：等第一帧消息队列跑完（窗口已 attach）再拉起相册。
            //   `post` 在 View 未 attach 时仍会排队执行，故不会因为窗口没就绪而丢失。
            //   （与 BackgroundCropActivity 同一处修复、同一套注释，两页行为保持一致。）
            window.decorView.post { pick() }
        } else {
            Logs.i(TAG, "[重建] 不再重弹相册，等待结果")
        }
    }

    /* ============================ 界面 ============================ */

    /**
     * 建页面。
     *
     * 【为什么必须先 installForActivity】本页继承的是 `android.app.Activity`（不是
     *   ComponentActivity），它的 decorView 上**没有** `ViewTreeLifecycleOwner`。
     *   ComposeView 一 attach 就会抛 `ViewTreeLifecycleOwner not found from DecorView`。
     *   所以顺序必须是：装 owner → 建 ComposeView → setContentView。
     *   （`ChatActivity` 用的是同一个写法。）
     */
    private fun buildUi(): View {
        ComposeHost.installForActivity(this)
        return ComposeHost.createView(this) { CoverShell() }
    }

    /**
     * 封面裁剪页外壳：顶栏（取消 + 步骤指示）+ 底栏（工具 + 上一个 + 主操作）。
     *
     * 【改造边界·只换外壳】选图、解码、拖拽、双指缩放、边界夹紧、三次落盘的语义与代码
     *   **一行未动**；[CropView] 原封不动地由 [AndroidView] 装进来。
     *   步骤状态机同样未动：`step` 仍是普通 Int，[goStep] / [cropOne] / [saveAll] 的控制流照旧。
     *
     * 【insetTop / insetBottom 的时序·最容易出错的地方】改造前靠
     *   `addOnLayoutChangeListener` 量出顶栏底边、底栏顶边再回填给 CropView。
     *   这里换成 [onSizeChanged]，并让公式逐项对上：
     *     · 顶栏 → `onSizeChanged` 写在 `padding` **之前**（链上更外层），
     *       量到的就是「18dp 上边距 + 内容高」，与原实现里的 `b`（顶栏底边 y）相等；
     *     · 底栏 → 把 22dp 底部留白做成 Column **内部**的 Spacer，
     *       于是 Column 实测高度 = 原实现里的 `parent.height - t`（底栏高 + 底边距），
     *       不需要任何常数补偿；
     *     · 两处再各自 +10dp 余量，与原实现的 `+ UiKit.dp(10f)` 一致。
     *   两个 inset 先后到达是安全的：[CropView.setInsets] 只在值变化时才重排，
     *   且 `layout()` 内部对「还没量到」保留了 96dp / 170dp 的保守下限。
     */
    @Composable
    private fun CoverShell() {
        // 引擎实例：每次搭页面建一个，并登记到 `crop` 字段 ——
        //   saveAll / cropOne / onDestroy / applyInsets 仍然走这个入口，与改造前一致。
        val engine = remember { CropView(this).also { crop = it } }
        val gap = UiKit.dp(this, 10.0f)
        val index = uiStep.value
        val total = PetPrefs.DOLL_COVER_RATIOS.size
        // 改造前在 buildBottomBar 末尾调 refreshStep()；此刻 crop 已创建，等价位置就是这里。
        LaunchedEffect(Unit) { refreshStep() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            // 裁剪引擎：原封不动的 CropView
            AndroidView(
                factory = { _ ->
                    // 【坑·一个 View 只能有一个 parent】重建路径下 engine 可能还挂在旧树上。
                    (engine.parent as? ViewGroup)?.removeView(engine)
                    engine
                },
                modifier = Modifier.fillMaxSize()
            )

            // ---------- 顶栏：取消 + 「第 N / 3 次 · M 列排布」 ----------
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged {
                        insetTop = it.height + gap
                        applyInsets()
                    }
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = PAD_DP.dp, end = PAD_DP.dp)
            ) {
                // 【顺手去掉原来那个 ghost 占位】改造前为了让中间的文字真居中，
                //   在右侧放了一个 INVISIBLE 的「取消」当等宽占位。Compose 直接居中即可。
                Pill(
                    text = "取消",
                    bg = overlayBg,
                    line = overlayLine,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { finish() }
                )
                Text(
                    text = "第 " + (index + 1) + " / " + total + " 次 · " + (index + 1) + " 列排布",
                    modifier = Modifier.align(Alignment.Center),
                    // 【字族】步骤指示是「系统在报告事实」，按全 App 口径走系统字（section 16/600）。
                    style = DhTokens.type.section,
                    color = overlayFg
                )
            }

            // ---------- 底栏 ----------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged {
                        insetBottom = it.height + gap
                        applyInsets()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 第一行：复位 / 缩小 / 放大（与背景裁剪页同款，用户已经熟悉这套操作）。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolPill("复位") { crop?.reset() }
                    ToolPill("−") { crop?.zoomBy(1 / 1.25f) }
                    ToolPill("+") { crop?.zoomBy(1.25f) }
                }
                Spacer(Modifier.height(12.dp))
                // 第二行：上一个比例（次操作）+ 主操作。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 【可点判据】第 1 步没有上一步；置灰但仍然占位，避免按钮左右横跳。
                    ToolPill(
                        text = "上一个",
                        enabled = index > 0,
                        modifier = Modifier.alpha(if (index <= 0) 0.35f else 1f)
                    ) { goStep(index - 1) }
                    Box(
                        modifier = Modifier
                            .width(150.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DhTokens.palette.accent)
                            .clickable { onMainAction() },
                        // 【对齐门禁】有背景 + 只有一个文字，必须显式居中。
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (index >= total - 1) "保存" else "下一个比例",
                            // 【字重】文楷是单字重字体，写 600 会被合成加粗；这里固定 400，
                            //   层级由强调色底 + 白字承担（与全 App 清理双粗体的口径一致）。
                            style = DhTokens.type.body.copy(
                                fontFamily = DhTokens.fonts,
                                fontWeight = FontWeight.Normal
                            ),
                            color = overlayFg
                        )
                    }
                }
                // 22dp 底部留白放在 Column 内部：Column 实测高度即「底栏 + 底边距」。
                Spacer(Modifier.height(22.dp))
            }
        }
    }

    /**
     * 胶囊按钮：顶栏「取消」与底栏工具共用一套实现。
     *
     * 【为什么这里不用主题色】本页是「黑画布 + 照片」的裁剪台，`window.statusBarColor`
     *   也被强制成黑色。白色半透明浮层是这类页面的通行做法 —— 换成浅色主题的卡片色，
     *   压在照片上会糊成一片，反而更看不清。字形 / 字号 / 字重则**照全 App 口径**走令牌。
     */
    @Composable
    private fun Pill(
        text: String,
        bg: androidx.compose.ui.graphics.Color,
        line: androidx.compose.ui.graphics.Color,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(1.dp, line, RoundedCornerShape(999.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                // 【字重】文楷只有单字重，写 600 会被合成加粗（全 App 已清过一轮双粗体），
                //   所以这里固定 400。
                style = DhTokens.type.body.copy(
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.Normal
                ),
                color = overlayFg
            )
        }
    }

    /** 底部工具按钮：84×40、透明底 + 白描边 + 白字（与原 `tool()` 同尺寸）。 */
    @Composable
    private fun ToolPill(
        text: String,
        enabled: Boolean = true,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Pill(
            text = text,
            bg = toolBg,
            line = toolLine,
            modifier = modifier.width(84.dp).height(40.dp),
            enabled = enabled,
            onClick = onClick
        )
    }

    /**
     * 刷新步骤相关的界面文案与可按状态。
     *
     * 【只做两件事】把步骤号镜像给 Compose（文案 / 置灰由它派生）；
     *   再让裁剪框切到当前步的比例并恢复这一步之前框好的构图（与原实现逐字一致）。
     */
    private fun refreshStep() {
        uiStep.value = step
        // 让裁剪框切到当前步的比例，并恢复这一步之前框好的构图。
        crop?.applyStep(step, savedMatrix[step])
    }

    /**
     * 切到第 [target] 步。
     *
     * 【先存后切】切之前把当前框的偏移记进 [savedMatrix]，回来时才能原样恢复。
     */
    private fun goStep(target: Int) {
        if (target < 0 || target >= PetPrefs.DOLL_COVER_RATIOS.size) {
            return
        }
        val c = crop ?: return
        savedMatrix[step] = c.snapshot()
        step = target
        refreshStep()
    }

    /** 主按钮：最后一次保存，否则进下一步。 */
    private fun onMainAction() {
        if (step >= PetPrefs.DOLL_COVER_RATIOS.size - 1) {
            saveAll()
        } else {
            goStep(step + 1)
        }
    }

    /* ============================ 选图 ============================ */

    private fun pick() {
        try {
            startActivityForResult(pickImageIntent(), REQ_PICK)
        } catch (anf: ActivityNotFoundException) {
            Logs.w(TAG, "照片选择器不可用，降级", anf)
            try {
                startActivityForResult(compatImageIntent(), REQ_PICK)
            } catch (th: Throwable) {
                fail("打不开相册：" + th.message)
            }
        } catch (th: Throwable) {
            fail("打不开相册：" + th.message)
        }
    }

    /** 【必须显式请求相册】需求原文「选自己的图片 请定位到相册」。 */
    private fun pickImageIntent(): Intent {
        val intent = Intent("android.provider.action.PICK_IMAGES")
        intent.type = IMAGE_TYPE
        return intent
    }

    private fun compatImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = IMAGE_TYPE
        return intent
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != REQ_PICK) {
            return
        }
        val uri: Uri? = data?.data
        if (result != RESULT_OK || uri == null) {
            Logs.i(TAG, "[取消] 没选图，直接收尾")
            finish()
            return
        }
        Thread({
            val bmp = decode(uri)
            runOnUiThread {
                if (bmp == null) {
                    fail("这张图解不开")
                    return@runOnUiThread
                }
                crop?.setImage(bmp)
            }
        }, "dollhouse-cover-decode").start()
    }

    /** 解码成位图：先读尺寸再按需降采样。 */
    private fun decode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            // 【r20·修流泄漏】两处 openInputStream 原来都是「open → decodeStream → close」：
            //   decodeStream 抛异常（损坏图 / OOM）时 close 被跳过 → fd 泄漏。
            //   封面裁剪页反复选图会反复走到这里。
            contentResolver.openInputStream(uri)?.use { in1 ->
                BitmapFactory.decodeStream(in1, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            // 上限 2048：封面最终最长边 1024，2K 输入足够精细且不吃内存。
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            return contentResolver.openInputStream(uri)?.use { in2 ->
                BitmapFactory.decodeStream(in2, null, opts)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "解码失败", t)
            return null
        }
    }

    /* ============================ 落盘 ============================ */

    /**
     * 把三种比例各裁一张落盘，并把「当前列数那一张」设为封面。
     *
     * 【为什么不只存当前这一张】用户可能之后切换列数；若只存一张，
     *   切列数时就要拿不合比例的图去凑，等于白裁了这两次。三张一起存，切列数无缝。
     * 【落盘位置】人偶私有目录（`filesDir/doll/`），与其它封面资产同处一地，便于导出。
     */
    private fun saveAll() {
        val c = crop ?: return
        val bmp = c.getBitmap()
        if (bmp == null || !c.hasFrame()) {
            return
        }
        // 【先存当前步】用户可能直接点保存而没经过 goStep，这里补一次。
        savedMatrix[step] = c.snapshot()
        val paths = arrayOfNulls<String>(PetPrefs.DOLL_COVER_RATIOS.size)
        try {
            // 【原图需保留】裁每一张都要用原图；用第一张裁完后 bmp 不能被回收。
            for (i in paths.indices) {
                paths[i] = cropOne(bmp, i)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "裁剪落盘失败", t)
        }
        val okPaths = paths.filterNotNull()
        if (okPaths.isEmpty()) {
            HomeUi.showNotice(this, "保存失败", "没能写出封面图，请重试。")
            finish()
            return
        }
        // 只把「与当前列数匹配」的那张挂上去；其余留给切列数时用。
        val idx = Math.max(0, Math.min(paths.size - 1, PetPrefs.dollCols(this) - 1))
        val chosen = paths[idx] ?: okPaths[0]
        DollStore.setCoverFromCrop(this, dollId, chosen)
        DollStore.setCoverSet(this, dollId, paths.filterNotNull().toTypedArray())
        DollState.bump()
        HomeUi.notifyPetReload(this)
        Logs.i(TAG, "[保存] 封面已写入 " + chosen)
        setResult(RESULT_OK)
        finish()
    }

    /**
     * 按第 [i] 种比例裁一张。
     *
     * 【实现】借用 [CropView] 的几何：临时切到该步比例、应用该步的框选偏移、
     *   取选区、裁出、写文件，再切回原步。全程在调用方线程（保存按钮里），
     *   单帧操作，几十毫秒级，不会明显卡顿。
     */
    private fun cropOne(full: Bitmap, i: Int): String? {
        val c = crop ?: return null
        val backupStep = step
        val backupMatrix = c.snapshot()
        try {
            step = i
            c.applyStep(i, savedMatrix[i])
            val box = c.selection()
            if (box[2] <= 0 || box[3] <= 0) {
                return null
            }
            val cut = Bitmap.createBitmap(full, box[0], box[1], box[2], box[3])
            val f = File(DollStore.coversDir(this), "cover_" + (i + 1) + ".png")
            FileOutputStream(f).use { os ->
                cut.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.flush()
            }
            if (cut !== full) {
                cut.recycle()
            }
            return f.absolutePath
        } finally {
            // 【必须还原】否则界面会停在临时比例上，用户看到的框和他点之前不一样。
            step = backupStep
            c.applyStep(backupStep, backupMatrix)
            refreshStep()
        }
    }

    private fun fail(msg: String) {
        Logs.w(TAG, msg)
        HomeUi.showNotice(this, "打开失败", msg)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        crop?.release()
    }

    /* ============================ 裁剪视图 ============================ */

    /**
     * 封面裁剪视图：固定宽高比（按步切换）、图片始终铺满框、单指拖动 / 双指缩放。
     *
     * 【与背景裁剪页的 CropView 的差别】那边框的比例来自「聊天区实测比例」（随屏幕变），
     *   这边是**常量比例**（随列表列数变）。所以这里的框尺寸是算出来的确定值，
     *   不需要读偏好、也不需要 clamp 极端值。
     */
    private class CropView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val scrim = Paint()
        private val frame = Paint()
        private val grid = Paint()
        private val box = RectF()
        private var baseScale = 1.0f
        private var userScale = 1.0f
        private var insetTop = 0
        private var insetBottom = 0

        /** 当前目标宽高比（宽 / 高）。 */
        private var ratio = 1.0f

        private var ox = 0.0f
        private var oy = 0.0f
        private var lastX = 0.0f
        private var lastY = 0.0f
        private var lastSpan = 0.0f
        private var multi = false

        init {
            paint.isAntiAlias = true
            scrim.color = 0xA6000000.toInt()
            frame.color = Color.WHITE
            frame.style = Paint.Style.STROKE
            frame.strokeWidth = UiKit.dpf(ctx, 1.5f)
            grid.color = 0x4DFFFFFF
            grid.style = Paint.Style.STROKE
            grid.strokeWidth = UiKit.dpf(ctx, 0.8f)
        }

        fun setInsets(top: Int, bottom: Int) {
            if (insetTop == top && insetBottom == bottom) {
                return
            }
            insetTop = top
            insetBottom = bottom
            layout(true)
            invalidate()
        }

        /** 传 null 会被忽略（重建路径下的时序兜底）。 */
        fun setImage(b: Bitmap?) {
            if (b == null) {
                return
            }
            release()
            bmp = b
            userScale = 1.0f
            ox = 0.0f
            oy = 0.0f
            layout(false)
            invalidate()
        }

        fun getBitmap(): Bitmap? = bmp

        fun hasFrame(): Boolean = bmp != null && box.width() > 1f && box.height() > 1f

        fun release() {
            val b = bmp
            if (b != null && !b.isRecycled) {
                b.recycle()
            }
            bmp = null
        }

        fun reset() {
            userScale = 1.0f
            layout(false)
            invalidate()
        }

        fun zoomBy(f: Float) {
            if (bmp == null) {
                return
            }
            val cx = box.centerX()
            val cy = box.centerY()
            val sx = (cx - ox) / scale()
            val sy = (cy - oy) / scale()
            userScale = clamp(userScale * f, 1f, 6f)
            ox = cx - sx * scale()
            oy = cy - sy * scale()
            clampOffset()
            invalidate()
        }

        /** 当前框选状态的快照 —— 用于在三种比例之间来回恢复。
         *
         * 【存什么·关键设计】存「框中心对应源图中的相对位置 + 用户缩放」，**不存像素偏移**。
         *   原因：三种比例的框尺寸与位置都不一样（框宽从 376dp 变到 118dp），
         *   像素偏移 (ox, oy) 是相对某个具体框算出来的，换到另一个框上语义就废了。
         *   而「框中心落在源图的 38% 宽度处」这种**相对**描述在任何框尺寸下都成立 ——
         *   换算过去就是「我上次把主体放在画面中心，现在还在中心」，这正是用户期望的。
         *
         * @return [nx, ny, userScale]：nx/ny 为 0~1 的相对位置。
         */
        fun snapshot(): FloatArray {
            val b = bmp ?: return floatArrayOf(0.5f, 0.5f, userScale)
            val s = scale()
            val sx = if (b.width > 0) ((box.centerX() - ox) / s) / b.width else 0.5f
            val sy = if (b.height > 0) ((box.centerY() - oy) / s) / b.height else 0.5f
            return floatArrayOf(
                clamp(sx, 0f, 1f),
                clamp(sy, 0f, 1f),
                clamp(userScale, 1f, 6f)
            )
        }

        /**
         * 切到第 [i] 种比例。
         *
         * 【为什么框选状态可空】第一次进入某一步时没有历史状态 = 重新居中铺满；
         *   只有回退重进过的步才带着用户之前框好的构图。
         */
        fun applyStep(i: Int, state: FloatArray?) {
            val ratios = PetPrefs.DOLL_COVER_RATIOS
            if (i < 0 || i >= ratios.size) {
                return
            }
            // 【r13·用实测比例】第 i 步对应「i+1 列排布」。
            //   【为什么不用 ratios[i]】那是按 408dp 屏宽推导的**估算值**，与真实机型有偏差；
            //   列表页会把封面框的真实宽高比写进 `cover_ratio_<cols>`（见 DollListScreen 的
            //   onSizeChanged），这里优先取它 —— 裁出来的图才能与卡片严丝合缝地铺满。
            //   没实测过（首启直接改封面）才回落到估算值。
            val cols = i + 1
            val measured = PetPrefs.coverRatioMeasured(context, cols)
            ratio = if (measured > 0.01f) measured else ratios[i]
            // 先按新比例重排框（这一步会把 ox/oy 重置为居中且 baseScale 按新框重算）。
            layout(false)
            if (state != null) {
                restore(state)
            }
            invalidate()
        }

        /**
         * 按快照恢复构图。
         *
         * 【顺序不能反】必须先 [layout]（算出新框的 baseScale 与新框位置），
         *   再按新框换算 —— 否则用的是旧框的尺寸，恢复出来会偏。
         */
        private fun restore(state: FloatArray) {
            val b = bmp ?: return
            if (state.size < 3) {
                return
            }
            userScale = clamp(state[2], 1f, 6f)
            val s = scale()
            // 让「源图上的 (nx, ny) 那个点」重新落在新框的中心。
            ox = box.centerX() - (state[0] * b.width) * s
            oy = box.centerY() - (state[1] * b.height) * s
            clampOffset()
        }

        private fun scale(): Float = baseScale * userScale

        private fun clamp(v: Float, lo: Float, hi: Float): Float {
            return if (v < lo) lo else (if (v > hi) hi else v)
        }

        /**
         * 依据当前控件尺寸与目标比例，算出裁剪框，并让图片「按比例铺满框」。
         *
         * @param keepCenter true = 尺寸变化时保住框中心对应的源图点（避免图片跳动）。
         */
        private fun layout(keepCenter: Boolean) {
            val b = bmp ?: return
            val pad = UiKit.dpf(context, PAD_DP)
            val safe = UiKit.viewCutoutInsets(this)
            val padL = pad + safe[0]
            val padR = pad + safe[2]
            val availW = Math.max(1f, width - padL - padR)
            val top0 = Math.max(
                Math.max(UiKit.dpf(context, 96f), insetTop.toFloat()), safe[1].toFloat()
            )
            val bot0 = Math.max(
                Math.max(UiKit.dpf(context, 170f), insetBottom.toFloat()), safe[3].toFloat()
            )
            val availH = Math.max(1f, height - top0 - bot0)
            var w = availW
            var h = w / ratio
            if (h > availH) {
                h = availH
                w = h * ratio
            }
            val left = padL + (availW - w) / 2f
            val top = top0 + (availH - h) / 2f

            val oldCx = box.centerX()
            val oldCy = box.centerY()
            var sx0 = 0f
            var sy0 = 0f
            val had = box.width() > 0f
            if (keepCenter && had) {
                sx0 = (oldCx - ox) / scale()
                sy0 = (oldCy - oy) / scale()
            }
            box.set(left, top, left + w, top + h)
            baseScale = Math.max(box.width() / b.width, box.height() / b.height)
            if (keepCenter && had) {
                ox = box.centerX() - sx0 * scale()
                oy = box.centerY() - sy0 * scale()
            } else {
                ox = box.centerX() - b.width * scale() / 2f
                oy = box.centerY() - b.height * scale() / 2f
            }
            clampOffset()
        }

        private fun clampOffset() {
            val b = bmp ?: return
            val w = b.width * scale()
            val h = b.height * scale()
            ox = clamp(ox, box.right - w, box.left)
            oy = clamp(oy, box.bottom - h, box.top)
        }

        /** 当前选区（源图像素）：[left, top, width, height]。 */
        fun selection(): IntArray {
            val b = bmp ?: return intArrayOf(0, 0, 0, 0)
            val s = scale()
            var l = Math.round((box.left - ox) / s)
            var t = Math.round((box.top - oy) / s)
            var w = Math.round(box.width() / s)
            var h = Math.round(box.height() / s)
            l = Math.max(0, Math.min(l, b.width - 1))
            t = Math.max(0, Math.min(t, b.height - 1))
            w = Math.max(1, Math.min(w, b.width - l))
            h = Math.max(1, Math.min(h, b.height - t))
            return intArrayOf(l, t, w, h)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            layout(ow > 0 && oh > 0)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = bmp ?: return
            matrix.reset()
            matrix.postScale(scale(), scale())
            matrix.postTranslate(ox, oy)
            canvas.save()
            canvas.clipRect(box)
            canvas.drawBitmap(b, matrix, paint)
            canvas.restore()
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, box.top, scrim)
            canvas.drawRect(0f, box.bottom, w, h, scrim)
            canvas.drawRect(0f, box.top, box.left, box.bottom, scrim)
            canvas.drawRect(box.right, box.top, w, box.bottom, scrim)
            canvas.drawRect(box, frame)
            for (i in 1 until GRID) {
                val x = box.left + box.width() * i / GRID
                val y = box.top + box.height() * i / GRID
                canvas.drawLine(x, box.top, x, box.bottom, grid)
                canvas.drawLine(box.left, y, box.right, y, grid)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (bmp == null) {
                return true
            }
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = ev.x
                lastY = ev.y
                multi = false
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                multi = true
                lastSpan = span(ev)
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                val keep = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(keep)
                lastY = ev.getY(keep)
                multi = false
                return true
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (multi && ev.pointerCount >= 2) {
                    val s = span(ev)
                    if (lastSpan > 1f && s > 1f) {
                        zoomAt(midX(ev), midY(ev), s / lastSpan)
                    }
                    lastSpan = s
                    return true
                }
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                lastX = ev.x
                lastY = ev.y
                ox += dx
                oy += dy
                clampOffset()
                invalidate()
                return true
            }
            return true
        }

        private fun span(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) {
                return 1f
            }
            val dx = ev.getX(0) - ev.getX(1)
            val dy = ev.getY(0) - ev.getY(1)
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        private fun midX(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.x else (ev.getX(0) + ev.getX(1)) / 2f
        }

        private fun midY(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.y else (ev.getY(0) + ev.getY(1)) / 2f
        }

        private fun zoomAt(px: Float, py: Float, f: Float) {
            val sx = (px - ox) / scale()
            val sy = (py - oy) / scale()
            val next = clamp(userScale * f, 1f, 6f)
            if (next == userScale) {
                return
            }
            userScale = next
            ox = px - sx * scale()
            oy = py - sy * scale()
            clampOffset()
            invalidate()
        }
    }

    companion object {
        private const val TAG = "DollhouseCoverCrop"

        /** 选图请求码（与背景裁剪页错开：两页可能同时存在于任务栈里）。 */
        private const val REQ_PICK = 93

        /** 裁剪框相对屏幕的左右留白（dp）。 */
        private const val PAD_DP = 24.0f

        /** 裁剪框框内网格：三分线，帮着对齐构图。 */
        private const val GRID = 3

        /** 相册的 MIME 过滤（同样抽成常量，避免注释里出现那个会闭合注释的组合）。 */
        private const val IMAGE_TYPE = "image/*"

        /** Intent 键：目标人偶 id。 */
        const val EXTRA_DOLL = "dh_cover_doll"

        /** 打开本页给某只人偶改封面。 */
        @JvmStatic
        fun open(act: Activity, dollId: String) {
            try {
                val i = Intent(act, CoverCropActivity::class.java)
                i.putExtra(EXTRA_DOLL, dollId)
                PageTransitions.start(act, i)
            } catch (t: Throwable) {
                Logs.w(TAG, "打不开封面裁剪页", t)
            }
        }
    }
}
