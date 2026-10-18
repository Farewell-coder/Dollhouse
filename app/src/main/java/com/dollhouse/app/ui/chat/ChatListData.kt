package com.dollhouse.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 【职责】聊天列表页的**数据聚合**：把「会话清单 + 会话历史 + 人偶封面」拼成
 *          一份可直接渲染的卡片数据（[Row]），并负责未读计数。
 *
 * 【为什么单独一层】聊天列表要显示的五样东西（头像 / 名字 / 最近一条 / 时间 / 未读）
 *   分散在三个来源：会话清单（`conv_index`：id/名字/时间）、会话历史（`conv_<id>`：消息体）、
 *   [DollStore]（人偶封面）。若把这段拼装写进 Composable，重组一次就要重读一遍盘；
 *   抽成纯函数后，UI 只在「变更信号」变化时调一次，且单元测试可以直接断言。
 *
 * 【为什么不缓存】会话最多几十条、每条历史是内存里的 JSON 字符串解析，
 *   实测开销远小于一次磁盘读。缓存反而要处理失效（发消息 / 改名 / 删除都要清），
 *   是典型的「为了快一点引入三个新 bug」。这里保持无状态。
 *
 * 【线程】读盘 + 解析，建议在 IO 线程调用；纯内存部分无锁（SharedPreferences 自身线程安全）。
 */
object ChatListData {

    /**
     * 列表里的一张卡片。
     *
     * @param id      会话 id（[ChatSessions] 的口径）。
     * @param title   会话名。
     * @param preview 最近一条消息的预览（已压成单行、已截断）；空会话给「还没有消息」。
     * @param time    清单里的 `updated` 时间戳（毫秒）；0 = 未知。
     * @param unread  未读条数（0 = 已读完，不显示红点）。
     * @param avatar  头像位图（已按 96px 降采样）；null = 用默认图标。
     * @param isPet   是否属于人偶池（用于卡片右下角的语义标记）。
     * @param isSelf  是否属于自聊池。
     * @param dollName 绑定人偶的显示名（空 = 未绑定）。**仅供搜索匹配**，不直接上屏。
     */
    data class Row(
        val id: String,
        val title: String,
        val preview: String,
        val time: Long,
        val unread: Int,
        val avatar: Bitmap?,
        val isPet: Boolean,
        val isSelf: Boolean,
        val dollName: String = ""
    )

    /** 预览文案的字数上限（超出截断加省略号）。 */
    private const val PREVIEW_MAX = 48

    /** 头像解码的目标边长（px）：列表头像在 3x 屏上最大约 56dp ≈ 168px，96 足够且省内存。 */
    private const val AVATAR_PX = 96

    /**
     * 拉取某个池的全部会话卡片，**按最近活跃时间倒序**（最新的在最上面）。
     *
     * @param pool [ChatSessions.POOL_SELF] 或 [ChatSessions.POOL_PET]；
     *             传 null 表示「两个池都要」（合并成一个列表）。
     */
    fun load(ctx: Context, pool: String?): List<Row> {
        val all = ChatSessions.listAll(ctx)
        val out = ArrayList<Row>(all.length())
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (pool != null && pool != ChatSessions.poolOf(o)) {
                continue
            }
            // 【r13·列表删除=隐藏】被用户从列表删掉的会话不显示。
            //   【为什么不真删】需求原文：「这种删除不会清聊天记录」——
            //   删的是列表入口，不是会话数据（见 PetPrefs.convHidden 的说明）。
            val id = o.optString("id")
            if (PetPrefs.convHidden(ctx, id)) {
                continue
            }
            out.add(rowOf(ctx, o))
        }
        // 【排序】最近活跃的排前面；时间相同（同一毫秒建的）按 id 兜底，保证顺序稳定，
        //   否则列表在重组之间会自己换位，用户点下去可能点到隔壁那条。
        out.sortWith { a, b ->
            val dt = b.time - a.time
            if (dt != 0L) {
                if (dt > 0) 1 else -1
            } else {
                a.id.compareTo(b.id)
            }
        }
        return out
    }

    /** 组装单张卡片。 */
    private fun rowOf(ctx: Context, o: JSONObject): Row {
        val id = o.optString("id")
        val hist = historyOf(ctx, id)
        val last = lastMessage(hist)
        val total = replyCountOf(hist)
        val read = PetPrefs.convRead(ctx, id)
        return Row(
            id = id,
            title = o.optString("title", "新的对话"),
            preview = previewOf(last),
            time = o.optLong("updated", 0L),
            unread = Math.max(0, total - read),
            avatar = avatarOf(ctx, id),
            isPet = ChatSessions.POOL_PET == ChatSessions.poolOf(o),
            isSelf = ChatSessions.POOL_SELF == ChatSessions.poolOf(o),
            dollName = dollNameOf(ctx, id)
        )
    }

    /**
     * 会话绑定的人偶名（供搜索用）。
     *
     * 【为什么要它】用户记得住的是「小肥鱼」这种**人偶名**，未必记得住会话标题
     *   （标题可能是「新的对话」或很久以前自动取的第一句话）。
     *   把它纳入搜索词，用户输入人偶名就能定位到对话。
     */
    private fun dollNameOf(ctx: Context, convId: String): String {
        return try {
            val dollId = ChatSessions.dollOfConv(ctx, convId)
            if (dollId.isEmpty()) {
                ""
            } else {
                val d = DollStore.byId(ctx, dollId)
                d?.name ?: ""
            }
        } catch (unused: Throwable) {
            ""
        }
    }

    /* ============================ 历史解析 ============================ */

    /** 读某个会话的历史数组；读不到给空数组（不让列表因为一条坏数据整体失败）。 */
    private fun historyOf(ctx: Context, id: String): JSONArray {
        return try {
            JSONArray(PetPrefs.convHistory(ctx, id))
        } catch (unused: Throwable) {
            JSONArray()
        }
    }

    /**
     * 历史里的消息条数。
     *
     * 【只数真正的对话消息】历史里还混着摘要分隔（带 `_summary` 标记）与「更早历史」
     *   补回批（带 `_prev` 标记）。前者不是聊天内容、后者只是暂存位，两者都不算未读，
     *   否则用户会看到「明明没聊，未读却一直是 1」。
     */
    private fun countOf(hist: JSONArray): Int {
        var n = 0
        for (i in 0 until hist.length()) {
            val o = hist.optJSONObject(i) ?: continue
            if (isRealMessage(o)) {
                n++
            }
        }
        return n
    }

    /**
     * 【r13·未读口径】只数**助手**的消息条数。
     *
     * 【为什么】未读 = 对方发来而我没看过的消息。用户自己发的那条不该产生红点 ——
     *   需求原文：「自己的消息不需要红点显示」。
     *   旧实现用 [countOf]（user + assistant 都算）当已读水位，于是每次自己发言后
     *   `总数 - 已读水位` 的差会先变大再被 markRead 抹平，表现为列表上闪一下红点。
     *
     * 【怎么用】未读数 = [replyCountOf] - `conv_read_<id>`；
     *   [markRead] 也改用它写水位，两侧同口径才不会算出负数或假未读。
     */
    private fun replyCountOf(hist: JSONArray): Int {
        var n = 0
        for (i in 0 until hist.length()) {
            val o = hist.optJSONObject(i) ?: continue
            if (!isRealMessage(o)) {
                continue
            }
            if ("assistant" == o.optString("role", "")) {
                n++
            }
        }
        return n
    }

    /** 是不是一条「真正的对话消息」（排除摘要与补回批）。 */
    private fun isRealMessage(o: JSONObject): Boolean {
        if (o.optBoolean("_summary", false)) {
            return false
        }
        if (o.optBoolean("_prev", false)) {
            return false
        }
        val role = o.optString("role", "")
        return "user" == role || "assistant" == role
    }

    /** 取最后一条真实消息；没有就返回 null。 */
    private fun lastMessage(hist: JSONArray): JSONObject? {
        var i = hist.length() - 1
        while (i >= 0) {
            val o = hist.optJSONObject(i)
            if (o != null && isRealMessage(o)) {
                return o
            }
            i--
        }
        return null
    }

    /**
     * 生成预览文案。
     *
     * 【为什么带角色前缀】列表里一眼要能分清「上一条是我说的还是她说的」。
     *   这是微信 / Telegram 这类列表的通行做法（「你：…」）。
     * 【图片消息】只有 `image` 没有正文时给「[图片]」，不要让预览位空着。
     */
    private fun previewOf(o: JSONObject?): String {
        if (o == null) {
            return "还没有消息，点开说点什么吧"
        }
        val mine = "user" == o.optString("role", "")
        var body = ChatHistoryStore.oneLine(o.optString("content", ""))
        if (body.isEmpty()) {
            body = if (o.optString("image", "").isNotEmpty()) "[图片]" else ""
        }
        if (body.isEmpty()) {
            return if (mine) "你：…" else "…"
        }
        if (body.length > PREVIEW_MAX) {
            body = body.substring(0, PREVIEW_MAX) + "…"
        }
        return if (mine) "你：$body" else body
    }

    /* ============================ 头像 ============================ */

    /**
     * 头像优先序：会话自定义头像 → 绑定人偶的封面 → null（UI 用默认图标兜底）。
     *
     * 【为什么回落到人偶封面】本 App 的「会话」大多数是「和某只人偶聊」，
     *   让头像自动是她，比让每只都挂同一个默认图标有辨识度得多，且零配置成本。
     */
    private fun avatarOf(ctx: Context, convId: String): Bitmap? {
        val custom = PetPrefs.convAvatar(ctx, convId)
        if (custom.isNotEmpty()) {
            val b = decodeSquare(custom)
            if (b != null) {
                return b
            }
        }
        val dollId = PetPrefs.convDoll(ctx, convId)
        if (dollId.isEmpty()) {
            return null
        }
        return try {
            val doll = DollStore.byId(ctx, dollId) ?: return null
            // 【r24·修内存】本方法是**列表头像**链路，目标是 AVATAR_PX(96)×2 = 192px。
            //   原来调 `coverOf(ctx, doll)` 走默认 1024 → 一张 4MB，与上方注释
            //   「降到 96px 后每张约 36KB」的承诺直接矛盾（差 100 倍）。
            DollStore.coverOf(ctx, doll, AVATAR_PX * 2)
        } catch (unused: Throwable) {
            null
        }
    }

    /**
     * 解码头像：先读尺寸算降采样，再按目标边长解码。
     *
     * 【为什么必须降采样】封面原图最长边 1024、一张 ARGB_8888 就是 4MB；
     *   列表里十几张一起解码，低端机直接 OOM。降到 96px 后每张约 36KB。
     */
    private fun decodeSquare(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) {
            return null
        }
        // 【r18·收口】统一走 ImageDecode（采样逻辑一致，删掉本地重复实现）。
        //   目标边长取 AVATAR_PX*2（头像显示 2 倍余量保清晰度），与旧实现等价。
        return com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = AVATAR_PX * 2, reqHeight = AVATAR_PX * 2)
    }

    /* ============================ 操作 ============================ */

    /**
     * 把某会话标记为「已读完」—— 打开它时调用。
     *
     * 【为什么在打开时写】列表的未读是「历史条数 − 已读水位」，
     *   所以每次打开都要把水位推到当前条数，之后新来的消息才会重新计入未读。
     */
    fun markRead(ctx: Context, convId: String?) {
        if (convId == null || convId.isEmpty()) {
            return
        }
        // 【r13·未读口径】水位与未读计算必须同口径（都只数 assistant），
        //   否则「总数 - 水位」会算出负数或假未读（见 [replyCountOf]）。
        PetPrefs.setConvRead(ctx, convId, replyCountOf(historyOf(ctx, convId)))
    }

    /**
     * 【r13·已删除】原 `create(ctx)` 建新会话的方法已移除。
     *
     * 【为什么删】需求「不需要那个新的对话 每个人偶对应一个对话 想添加新的对话就添加新的人偶」。
     *   留着这个方法，日后有人想加个「＋」按钮时就会顺手调它 —— 又会造出孤儿会话。
     *   会话的唯一来源是 [ChatSessions.ensureForDoll]（人偶的投影），
     *   要新对话就去「人偶」新建人偶。
     */

    /** 删除会话，并清掉它的展示层偏好（头像 / 未读水位 / 绑定）。 */
    fun delete(ctx: Context, convId: String?) {
        if (convId == null || convId.isEmpty()) {
            return
        }
        // 【r13·列表删除=隐藏·语义改造】需求原文：
        //   「我希望这里的是可以删掉的……这种删除不会清聊天记录」
        //   「如果对话被删掉了 我要去联系人那里跟这个机器人再说一句话 这又可以在这里又显示了
        //    像QQ那种类型的 删除」
        //
        // 【旧语义（已废弃）】原来调 [ChatSessions.delete] 真删清单项 + 清历史。
        //   在「一人偶一对话」模型下这有两个恶果：
        //   ① 聊天记录被销毁 —— 与用户要的「不清记录」直接冲突；
        //   ② 清单里没了 `d_<dollId>`，下一轮 [ChatSessions.syncWithDolls] 又把它重建出来，
        //      表现为「删了还在」，正是用户报的 bug。
        //
        // 【新语义】只打一个「隐藏」标记（[PetPrefs.setConvHidden]），
        //   清单与历史**原封不动**。列表加载时跳过被隐藏的（见 [load]）。
        //   用户去「人偶」跟这只人偶说一句话 → 消息落库 → [ChatHistoryStore.push]
        //   清掉标记 → 条目自动回到列表。这就是 QQ 的行为。
        PetPrefs.setConvHidden(ctx, convId, true)
    }

    /**
     * 【r19·置顶】把会话顶到列表最前。
     *
     * 【实现】把该会话的 `updated` 时间戳改为当前时间 —— 列表按 `updated` 倒序排，
     *   改成最大即自然浮到顶部。不动存储结构，与「最新消息自动上浮」同一套机制。
     */
    fun pinToTop(ctx: Context, convId: String?) {
        if (convId == null || convId.isEmpty()) {
            return
        }
        ChatSessions.touch(ctx, convId)
    }

    /**
     * 【r13·列表删除=隐藏】「清空聊天记录」（真正销毁消息）。
     *
     * 【与 [delete] 的分工】delete = 只移出列表（记录保留）；本方法 = 记录归零。
     *   两者是不同诉求，不能合并 —— 用户明确要的是前者，但后者在
     *   「聊天页 → 清空当前对话」里仍然需要。
     */
    fun clearHistory(ctx: Context, convId: String?) {
        if (convId == null || convId.isEmpty()) {
            return
        }
        ChatSessions.clearMessages(ctx, convId)
    }

    /** 改名。 */
    fun rename(ctx: Context, convId: String?, title: String?) {
        if (convId == null || convId.isEmpty()) {
            return
        }
        ChatSessions.rename(ctx, convId, title)
    }
}
