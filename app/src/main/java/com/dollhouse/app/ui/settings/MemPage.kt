package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.MemDb
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.UiKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 【职责】总记忆库的独立页：列出 AI 自主（或用户要求）写下的长期记忆条目。
 *
 * 【入口】设置页「记忆」卡片里的「记忆库」按钮 → open()。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose（规格书主线），**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic open/closeIfOpen`，仍挂 `android.R.id.content` 且打同一个 [TAG_PAGE]，
 *   仍走 `UiKit.openPage/closePage` 的页面栈与转场。调用方（`SheetPanel` / `HomeUi`）零改动。
 *
 * 【为什么页根还是 View】`UiKit.openPage(content, page, tag)` 收的是 `View`，
 *   且 [com.dollhouse.app.ui.theme.GlobalBackground.installPage] 要把背景图铺在**页根的 background** 上。
 *   所以用 [ComposeHost.createView] 产出 `ComposeView` 当「页」，内部 100% Compose。
 *
 * 【与 View 版的差异】删除一条后不再 `closeIfOpen + open` 整页重建（会闪一下），
 *   改为 `refresh` 状态自增触发重组重读 [MemDb] —— 数据语义与顺序完全一致。
 *
 * 【坑】页面靠 tag 认领，任何 Context 都能调 closeIfOpen()；找不到 Activity 就静默返回
 *         （悬浮窗场景没有 Activity，所以入口只放在设置页里）。
 */
object MemPage {
    private const val LOG_TAG = "Dollhouse"

    private const val TAG_PAGE = "feiyu_mem_page"

    /** 返回键用：记忆库页开着就关掉并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return try {
            val act = findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
            UiKit.closePage(old)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 设置页「记忆库」按钮的落地动作。 */
    @JvmStatic
    fun open(ctx: Context) {
        try {
            val act = findActivity(ctx) ?: return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            // Compose 运行所需 owner（原生 Activity 不自动装），幂等。
            ComposeHost.installForActivity(act)
            val page = ComposeHost.createView(act) { MemContent(act) }
            UiKit.openPage(content, page, TAG_PAGE)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
            i++
        }
        return null
    }

    /** 页面正文（Compose）。列表顺序与 View 版一致：最新的在最上面。 */
    @Composable
    private fun MemContent(act: Activity) {
        val c = DhTokens.colors
        var refresh by remember { mutableStateOf(0) }
        val arr = remember(refresh) { MemDb.list(act) }
        val items = remember(refresh) {
            val n = arr.length()
            if (n <= 0) emptyList() else (n - 1 downTo 0).mapNotNull { arr.optJSONObject(it) }
        }
        DhKit.Page(
            title = "记忆库",
            sub = "她记下的事",
            onBack = { closeIfOpen(act) }
        ) {
            if (items.isEmpty()) {
                DhKit.Card {
                    Text(
                        text = "还没有记下什么",
                        color = c.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            } else {
                items.forEach { o ->
                    MemItem(o) {
                        MemDb.remove(act.applicationContext, o.optString("id", ""))
                        refresh++
                    }
                }
            }
            Text(
                text = "她在聊天里觉得值得记的事会自己写进来，下次开口前会带上这些内容。\n"
                    + "共 " + items.size + " 条，最多保留 " + MemDb.MAX_ENTRIES + " 条，"
                    + "超出时丢最旧的。数据只在这台设备上。",
                modifier = Modifier.padding(start = 2.dp, top = 14.dp, end = 2.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_TINY + 5f).sp
            )
        }
    }

    /** 一条记忆：标题 + 时间 + 正文 + 删除。← `MemPage.item`。 */
    @Composable
    private fun MemItem(o: JSONObject, onDelete: () -> Unit) {
        val c = DhTokens.colors
        val title = o.optString("title", "")
        DhKit.Card {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (title.isEmpty()) "（无标题）" else title,
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "删除",
                    modifier = Modifier
                        .pressable(onDelete)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = c.err,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = fmtTime(o.optLong("ts", 0L)),
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = o.optString("text", ""),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_BTN + 6f).sp
            )
        }
    }

    private fun fmtTime(ts: Long): String {
        if (ts <= 0L) {
            return ""
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
        } catch (unused: Throwable) {
            ""
        }
    }
}
