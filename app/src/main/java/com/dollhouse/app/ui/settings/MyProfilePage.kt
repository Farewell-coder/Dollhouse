package com.dollhouse.app.ui.settings

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.ProfileState
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「我的资料」二级页（r14·需求 3）：编辑头像 / 昵称 / 个性签名。
 *
 * 【为什么是独立页而不是就地编辑】用户定案：「我的资料」是一个**二级页**。
 *   就地编辑会让抽屉里混进输入框与保存按钮，抽屉就不再是「瞄一眼就走」的轻量层。
 *
 * 【保存口径·r17】用户定案：
 *   · 右上角**保存按钮**：点击 → 落盘 → 返回；
 *   · 左上角**返回按钮**：点击 → 不落盘 → 直接返回（放弃本次改动）；
 *   · **系统返回键 / 手势返回**：与左上角返回同义（不落盘、直接返回）。
 *
 * 【为什么返回路径也要落盘（历史 bug 的教训）】改造前只有 `DhKit.Page(onBack=…)` 里调
 *   `commit()`，而系统返回走 `MainActivity.onBackPressed → PageStack.handleBack → pop()`，
 *   `pop()` 只跑 `fireLeave(key)`、**不经过 onBack lambda** —— commit() 永不执行、昵称丢失。
 *   现在把「保存」提为唯一入口（按钮显式调用），返回路径一律走 [discard]（不落盘），
 *   语义单一，不再依赖「返回时顺手保存」这种隐式行为。
 */
object MyProfilePage {

    /** 页面 key（进页面栈用）。 */
    const val KEY = "settings:myprofile"
    /** 预览头像边长（dp）。 */
    private const val PREVIEW_DP = 84


    /** 打开本页（压栈）。 */
    fun open(act: MainActivity) {
        ComposeHost.installForActivity(act)
        PageStack.push(act, KEY) { build(act) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        val c = DhTokens.colors
        // 【重读信号】与抽屉共用 [ProfileState]：本页保存后 bump，抽屉头部随之刷新。
        val tick = ProfileState.tick
        var name by remember(tick) { mutableStateOf(MyProfile.name(act).let { if (it == MyProfile.DEFAULT_NAME) "" else it }) }
        var bio by remember(tick) { mutableStateOf(MyProfile.bio(act)) }
        // 【草稿态头像】null = 未改动（沿用已生效的头像）；"" = 用户点了「移除」；
        //   其它 = 暂存文件的相对路径（还没写进 my_avatar）。点「保存」才转正。
        var pendingAvatar by remember(tick) { mutableStateOf<String?>(null) }
        // 预览位图：草稿优先，其次回落到已生效头像。用 remember 缓存，避免每帧解码。
        val avatar = remember(tick, pendingAvatar) {
            val p = pendingAvatar
            when {
                p == null -> MyProfile.avatarBitmap(act, 384)
                p.isEmpty() -> null
                else -> MyProfile.bitmapOf(act, p, 384)
            }
        }
        // 「移除头像」的可见性：已生效有头像，或草稿态有图（且未标记移除）时都该能点。
        val canRemove = (pendingAvatar?.isNotEmpty() == true) ||
                (pendingAvatar == null && MyProfile.hasAvatar(act))

        /**
         * 落盘昵称 / 签名 / 头像。
         *
         * 【只在「保存」按钮里调用】用户口径：右上角保存 = 落盘并返回；左上角返回 = 不落盘直接返回。
         *   改造前把 commit() 挂在 onBack 上，而系统返回路径（PageStack.pop → fireLeave）
         *   并不经过 onBack lambda —— commit() 永不执行，这就是「填了名字存不上」的根因。
         *   现在 commit 只由显式按钮触发，返回路径一律不落盘，语义唯一、不再依赖隐式行为。
         *
         * 【为什么只写一次而不是边打字边存】输入过程中每帧写 SharedPreferences 会产生
         *   大量无意义磁盘写；点「保存」写一次，语义上也正好是「编辑完成」。
         */
        fun commit() {
            MyProfile.setName(act, name)
            MyProfile.setBio(act, bio)
            // 头像：草稿态才需要动盘；null 表示用户没碰头像。
            val p = pendingAvatar
            if (p != null) {
                if (p.isEmpty()) {
                    MyProfile.clearAvatar(act)
                } else {
                    MyProfile.commitAvatar(act, p)
                }
                pendingAvatar = null
            }
            ProfileState.bump()
        }

        /** 放弃本次改动：把还没转正的暂存头像文件删掉（已生效的头像不受影响）。 */
        fun discard() {
            val p = pendingAvatar
            if (p != null && p.isNotEmpty()) {
                MyProfile.dropPendingAvatar(act, p)
            }
            pendingAvatar = null
        }

        DhKit.Page(
            title = "我的资料",
            sub = "这些是「你」的资料，不是人偶的",
            // 【左上角返回 = 放弃】丢弃草稿、不落盘，直接退一层。系统返回键 / 手势走同一条路径
            //   （MainActivity.onBackPressed → PageStack.handleBack → pop）。
            onBack = {
                discard()
                PageStack.pop(act)
            },
            actions = {
                // 【右上角保存 = 落盘并返回】图标按钮（Lucide save），语义与左上角返回明确区分。
                DhKit.IconButton(
                    iconRes = Icons.IC_SAVE,
                    sizeDp = Design.SZ_MD,
                    color = c.title,
                    label = "保存"
                ) {
                    commit()
                    PageStack.pop(act)
                }
            }
        ) {
            // ---------- 头像 ----------
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(PREVIEW_DP.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.soft),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp: android.graphics.Bitmap? = avatar
                    if (bmp != null && !bmp.isRecycled) {
                        // 【为什么先取局部 val】`avatar` 是 `by remember` 委托属性（有自定义 getter），
                        //   Kotlin 无法对它做 smart cast。取一次局部引用后判断，之后就是普通可空类型。
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(iconRes = Icons.IC_CIRCLE_USER, sizeDp = Design.SZ_XL, color = c.sub)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    DhKit.ActionRow(
                        label = "更换头像",
                        iconRes = Icons.IC_IMAGE_PLUS,
                        // 【宽度】ActionRow 是整行宽，这里放在 Column 里需要限宽，
                        //   否则会把右侧撑出屏幕（Row 的 weight 在固定宽容器里才生效）。
                        modifier = Modifier.width(180.dp)
                    ) {
                        // 【草稿】只暂存、不落盘；点右上角「保存」才真正生效。
                        pickAvatar(act) { path ->
                            val staged = MyProfile.stageAvatar(act, path)
                            if (staged.isNotEmpty()) {
                                pendingAvatar = staged
                            }
                        }
                    }
                    // 【r19·引导】用户反馈「找不到头像入口」，加一行小字说明点击哪里。
                    Text(
                        text = "点击「更换头像」→ 从相册选一张",
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                    )
                    if (canRemove) {
                        DhKit.ActionRow(
                            label = "移除头像",
                            iconRes = Icons.IC_TRASH,
                            modifier = Modifier.width(180.dp)
                        ) {
                            // 【草稿】先标记为「移除」，保存时才真正清掉；直接返回则原头像仍在。
                            //   若之前暂存过一张新图，把它删掉，免得留下孤儿文件。
                            val p = pendingAvatar
                            if (p != null && p.isNotEmpty()) {
                                MyProfile.dropPendingAvatar(act, p)
                            }
                            pendingAvatar = ""
                        }
                    }
                }
            }
            DhForm.Note("头像只存在这台设备上，不会上传。")

            // ---------- 昵称 ----------
            DhForm.Input(
                value = name,
                onValueChange = {
                    // 【就地限长】与 [MyProfile.NAME_MAX] 同口径；超长直接不收，
                    //   比事后提示更直接（用户不会打出半截被截断的怪异感）。
                    if (it.length <= MyProfile.NAME_MAX) {
                        name = it
                    }
                },
                label = "昵称",
                hint = MyProfile.DEFAULT_NAME
            )
            DhForm.Note("留空就用「" + MyProfile.DEFAULT_NAME + "」，最多 " + MyProfile.NAME_MAX + " 个字。")

            // ---------- 个性签名 ----------
            DhForm.Input(
                value = bio,
                onValueChange = {
                    if (it.length <= MyProfile.BIO_MAX) {
                        bio = it
                    }
                },
                label = "个性签名",
                hint = "一句话介绍自己",
                trailing = {
                    Text(
                        text = bio.length.toString() + "/" + MyProfile.BIO_MAX,
                        color = c.line,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            )
            DhForm.Note("「签名」和「个性」是同一个东西，改这里就够了。")
        }
    }

    /**
     * 选一张头像（【r20】只从相册选）。
     *
     * 【为什么只给相册】用户定案：「相册就行了，没有人会去文件里找头像」。
     *   原来走的是 `startFiles`（系统文件选择器，不限 MIME）—— 用户要在文件树里翻，
     *   那不符合换头像的心智模型。
     *
     * 【为什么仍走 raw 通道】默认通道会把图交给 `ImageStore` 存成 **JPEG**（有损、
     *   丢掉透明通道），不适合头像这类用户资产。raw 通道回传的是原件字节副本，
     *   再由 [MyProfile.stageAvatar] 自己缩放落盘。
     *   所以改用 `setRawListener + startRawImages`：选择器是相册，保真度不变。
     */
    private fun pickAvatar(act: MainActivity, onPicked: (String) -> Unit) {
        // 【必须写成 object 表达式】[PickFileActivity.RawListener] 是普通 interface（不是
        //   `fun interface`），Kotlin 不认 lambda 写法 —— 直接塞 lambda 会报
        //   "Argument type mismatch ... but RawListener? was expected"。
        PickFileActivity.setRawListener(object : PickFileActivity.RawListener {
            override fun onRaw(path: String?) {
                if (!path.isNullOrEmpty()) {
                    act.runOnUiThread { onPicked(path) }
                }
            }
        })
        PickFileActivity.setPurpose("my_avatar")
        PickFileActivity.startRawImages(act)
    }
}