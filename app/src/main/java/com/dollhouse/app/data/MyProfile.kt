package com.dollhouse.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.dollhouse.app.core.Logs
import java.io.File

/**
 * 【职责】「我」这一侧的资料：头像 / 昵称 / 个性签名。
 *
 * 【为什么必须单独开一个对象（而不是塞进 DollStore）】
 *   `DollStore` 管的是**人偶**（角色），本对象管的是**用户自己**。两者在数据上没有从属关系：
 *   人偶可以有几十只、可删可导；「我」永远只有一个，且**不随任何人偶的增删而变化**。
 *   混在一起的直接后果是「删掉所有人偶之后，我的头像也没了」。
 *
 * 【为什么键放 PetPrefs、编解码放这里】
 *   三个值都是同一份 SharedPreferences 里的标量，读写入口统一走 [PetPrefs.get] 才能
 *   复用它的死键清理与容错；但头像的**文件**要落到私有目录（不能写进 SharedPreferences），
 *   这部分只有本对象知道。故：键在 PetPrefs，文件与兜底在本对象。
 *
 * 【线程】写盘走 SharedPreferences 的 apply（异步）；读盘为纯内存映射，可在主线程调。
 */
object MyProfile {

    /** 头像文件名前缀（存进 ImageStore 的私有目录）。 */
    private const val PREFIX_AVATAR = "my_avatar_"

    private const val TAG = "DollhouseMyProfile"

    /** 用户昵称的默认值（空着不好看，也不能和「人偶」混淆）。 */
    const val DEFAULT_NAME = "我"

    /** 昵称长度上限（字段本身的约束，与输入框的提示一致）。 */
    const val NAME_MAX = 20

    /** 个性签名长度上限。 */
    const val BIO_MAX = 60

    /* ============================ 昵称 ============================ */

    /**
     * 昵称。空 / 未设 → [DEFAULT_NAME]。
     * 【为什么不返回空串】聊天页的抽屉顶部要显示一行名字，返回空串会让那一行塌掉。
     */
    fun name(ctx: Context?): String {
        if (ctx == null) {
            return DEFAULT_NAME
        }
        return try {
            val v = PetPrefs.get(ctx).getString("my_name", "") ?: ""
            if (v.trim().isEmpty()) DEFAULT_NAME else v.trim()
        } catch (ignored: Throwable) {
            DEFAULT_NAME
        }
    }

    /** 落一次昵称（超长截断，空白回退到默认）。 */
    fun setName(ctx: Context?, raw: String?) {
        if (ctx == null) {
            return
        }
        val v = (raw ?: "").trim().let { if (it.length > NAME_MAX) it.substring(0, NAME_MAX) else it }
        try {
            PetPrefs.get(ctx).edit().putString("my_name", v).apply()
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
        }
    }

    /* ============================ 个性签名 ============================ */

    /**
     * 个性签名（「签名」与「个性」是同一个东西 —— 用户定案）。
     * 未设返回空串；由 UI 决定显示占位文案还是留空。
     */
    fun bio(ctx: Context?): String {
        if (ctx == null) {
            return ""
        }
        return try {
            PetPrefs.get(ctx).getString("my_bio", "") ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    /** 落一次个性签名（超长截断）。 */
    fun setBio(ctx: Context?, raw: String?) {
        if (ctx == null) {
            return
        }
        val v = (raw ?: "").trim().let { if (it.length > BIO_MAX) it.substring(0, BIO_MAX) else it }
        try {
            PetPrefs.get(ctx).edit().putString("my_bio", v).apply()
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
        }
    }

    /* ============================ 头像 ============================ */

    /** 头像在私有目录里的相对路径；空串 = 没设过。 */
    fun avatarPath(ctx: Context?): String {
        if (ctx == null) {
            return ""
        }
        return try {
            PetPrefs.get(ctx).getString("my_avatar", "") ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * 是否真的有一张可用的头像图。
     * 【为什么判文件存在】路径可能指向一张被系统清理掉的文件；
     *   只判路径非空会让 UI 渲染出一个空白的圆。
     */
    fun hasAvatar(ctx: Context?): Boolean {
        if (ctx == null) {
            return false
        }
        val p = avatarPath(ctx)
        if (p.isEmpty()) {
            return false
        }
        return try {
            ImageStore.exists(ctx, p)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 从选图通道回传的 Uri 落一张新头像。
     *
     * 【为什么复制到私有目录】相册 Uri 的读权限绑在发起选择的那次 Activity 上，
     *   进程重启后即失效。图像资产一律「拷贝入库」是本工程既有口径（见 ImageStore）。
     *
     * @return 成功返回 true
     */
    fun setAvatarFromUri(ctx: Context?, uri: Uri?): Boolean {
        if (ctx == null || uri == null) {
            return false
        }
        return try {
            val saved = ImageStore.saveFromUri(ctx, uri, PREFIX_AVATAR)
            if (saved.isEmpty()) {
                false
            } else {
                PetPrefs.get(ctx).edit().putString("my_avatar", saved).apply()
                true
            }
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
            false
        }
    }

    /** 清掉头像（回到「圆形图标占位」的样子）。 */
    fun clearAvatar(ctx: Context?) {
        if (ctx == null) {
            return
        }
        try {
            PetPrefs.get(ctx).edit().putString("my_avatar", "").apply()
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
        }
    }

    /**
     * 从**本地文件路径**落一张新头像（r14）。
     *
     * 【为什么要这一条】头像的选图走 [PickFileActivity] 的「原始文件」通道，
     *   它回传的是**已复制到缓存目录的路径**而不是 Uri（Uri 的读权限有生命周期问题，
     *   见 [ImageStore] 的注释）。所以必须有一个吃路径的入口。
     *
     * 【为什么不走 Uri.fromFile】`file://` Uri 在部分 ROM 上会被 ContentResolver 拒绝；
     *   直接解码文件更可控，也不依赖任何 ROM 行为。
     *
     * 【怎么存】解码 → 按最长边 512 缩放 → JPEG(88) 写进 [ImageStore.dir]。
     *   头像是小圆图，512 已远超显示所需；再大只是白占空间与内存。
     *
     * @return 成功返回 true
     */
    fun setAvatarFromFile(ctx: Context?, path: String?): Boolean {
        if (ctx == null || path.isNullOrEmpty()) {
            return false
        }
        return try {
            // 【收口到 ImageStore】解码 / 缩放 / 落盘 / 清理旧文件这套活法全工程只应有一份
            //   （见 [ImageStore.saveScaledFile]）—— r14 之前本类里曾有一份私有副本，
            //   两处参数（512 / 88）一旦分叉，头像与其它图片资产的质量就不一致了。
            val rel = ImageStore.saveScaledFile(ctx, path, PREFIX_AVATAR, AVATAR_MAX, 88)
            if (rel.isEmpty()) {
                false
            } else {
                PetPrefs.get(ctx).edit().putString("my_avatar", rel).apply()
                true
            }
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
            false
        }
    }

    /** 头像文件的最长边（px）。 */
    private const val AVATAR_MAX = 512

    /**
     * 解码头像成小位图（边长按 [maxDim] 上限缩放）。
     *
     * 【为什么不缓存】头像只有两处显示（抽屉顶部 + 设定页预览），量极小；
     *   加缓存反而要处理「换头像后缓存失效」，得不偿失。
     * 【失败返回 null】调用方按「无图」渲染占位图标，绝不崩。
     */
    fun avatarBitmap(ctx: Context?, maxDim: Int): Bitmap? {
        if (ctx == null) {
            return null
        }
        val p = avatarPath(ctx)
        if (p.isEmpty()) {
            return null
        }
        return try {
            val b = ImageStore.loadScaled(ctx, p, maxDim)
            if (b == null || b.isRecycled) null else b
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 【是否存在真实文件】供导出 / 备份时判断要不要带图。
     * 【为什么不复用 hasAvatar】那个还判了路径非空；本方法只回答「文件在不在」。
     */
    fun avatarFile(ctx: Context?): File? {
        if (ctx == null) {
            return null
        }
        val p = avatarPath(ctx)
        if (p.isEmpty()) {
            return null
        }
        return try {
            val f = ImageStore.fileFor(ctx, p)
            if (f != null && f.exists()) f else null
        } catch (ignored: Throwable) {
            null
        }
    }
}