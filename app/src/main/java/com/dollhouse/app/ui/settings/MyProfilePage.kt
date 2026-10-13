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
 * 【为什么没有「保存」按钮】三项都是**改了即存**（与全工程其它设置页同一口径）：
 *   头像选完立刻落盘、昵称 / 签名在失焦 / 返回时落盘。
 *   多一个保存按钮只会制造「我改了没保存？」的疑虑。
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
        var avatar by remember(tick) { mutableStateOf(MyProfile.avatarBitmap(act, 384)) }
        /**
         * 落盘昵称 / 签名。
         *
         * 【为什么在返回时统一落盘而不是边打字边存】输入过程中每一帧都写 SharedPreferences
         *   会产生大量无意义的磁盘写（apply 虽异步，但队列会堆积）。
         *   离开页面时写一次，语义上也正好是「编辑完成」。
         */
        fun commit() {
            MyProfile.setName(act, name)
            MyProfile.setBio(act, bio)
            ProfileState.bump()
        }

        DhKit.Page(
            title = "我的资料",
            sub = "这些是「你」的资料，不是人偶的",
            onBack = {
                commit()
                PageStack.pop(act)
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
                        pickAvatar(act) {
                            avatar = MyProfile.avatarBitmap(act, 384)
                            ProfileState.bump()
                        }
                    }
                    if (MyProfile.hasAvatar(act)) {
                        DhKit.ActionRow(
                            label = "移除头像",
                            iconRes = Icons.IC_TRASH,
                            modifier = Modifier.width(180.dp)
                        ) {
                            MyProfile.clearAvatar(act)
                            avatar = null
                            ProfileState.bump()
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
     * 选一张头像。
     *
     * 【为什么走 PickFileActivity 的「原始文件」通道】工程已有一条成熟的
     *   「选文件 → 复制到缓存 → 回调本地路径」通道，动作导入 / 人偶导入都在用。
     *   复用它可以让头像也支持「相册 + 文件管理器」两条来源，而不必只认相册。
     */
    private fun pickAvatar(act: MainActivity, onDone: () -> Unit) {
        val listener = object : PickFileActivity.FileListener {
            override fun onFiles(paths: List<String>, failed: Boolean) {
                if (!failed && paths.isNotEmpty()) {
                    if (MyProfile.setAvatarFromFile(act, paths[0])) {
                        act.runOnUiThread { onDone() }
                    }
                }
            }
        }
        PickFileActivity.setPurpose("my_avatar")
        PickFileActivity.setFileListener(listener)
        PickFileActivity.startFiles(act, false)
    }
}