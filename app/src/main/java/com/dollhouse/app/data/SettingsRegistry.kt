package com.dollhouse.app.data

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.SettingsCard
import com.dollhouse.app.ui.widget.ApiPageKit

/**
 * 【职责】设置页分组注册表：哪些组是折叠卡片、哪些组整体摘除、哪张卡默认展开。
 *
 * 【入口】SettingsPage.isKnownTitle / isCard / isDropped / 默认展开判定。
 *
 * 【交互】只答问、不建视图；卡片专属整理通过 SettingsCard.decorate 回调。
 *
 * 【扩展】新增卡片 = 在 CARDS 里加一行；下线一个分组 = 加一行 dropped=true。
 *
 * 【坑】顺序即页面顺序；title 必须与页面文案逐字一致（带全角括号）；
 *        同一标题同时存在卡片与摘除声明时，摘除优先（与拆前行为一致）。
 */
object SettingsRegistry {

    /** 全部已声明的分组。 */
    @JvmField
    val CARDS: MutableList<SettingsCard> = ArrayList()

    init {
        CARDS.add(ApiCard())
        CARDS.add(SimpleCard("操作方式", false, false))
        CARDS.add(SimpleCard("应用图标", false, false))
        CARDS.add(SimpleCard("聊天背景", false, false))
        CARDS.add(SimpleCard("本地模型（离线，不花 token）", true, false))
        CARDS.add(SimpleCard("联网搜索", true, false))
        CARDS.add(SimpleCard("学习（点赞 -> 示范）", true, false))
    }

    /**
     * 按标题找声明；找不到返 null。
     * 同一标题同时有「卡片」与「摘除」两条声明时，摘除优先（与拆前两数组各自匹配的行为一致）。
     */
    @JvmStatic
    fun find(title: String?): SettingsCard? {
        if (title == null) {
            return null
        }
        var hit: SettingsCard? = null
        for (i in CARDS.indices) {
            val card = CARDS[i]
            if (title != card.title()) {
                continue
            }
            if (card.dropped()) {
                return card
            }
            if (hit == null) {
                hit = card
            }
        }
        return hit
    }

    /** 该标题是否已登记（卡片或摘除都算）。 */
    @JvmStatic
    fun known(title: String?): Boolean {
        return find(title) != null
    }

    /** 该标题是否是被下线摘除的组。 */
    @JvmStatic
    fun dropped(title: String?): Boolean {
        val settingsCard = find(title)
        return settingsCard != null && settingsCard.dropped()
    }

    /** 仅当登记为卡片（未摘除）时才成立。 */
    @JvmStatic
    fun card(title: String?): Boolean {
        val settingsCard = find(title)
        return settingsCard != null && !settingsCard.dropped()
    }

    /** 普通卡片：只声明标题与状态。 */
    class SimpleCard(private val title: String, private val dropped: Boolean, private val open: Boolean) : SettingsCard {
        override fun title(): String {
            return this.title
        }

        override fun dropped(): Boolean {
            return this.dropped
        }

        override fun openByDefault(): Boolean {
            return this.open
        }

        override fun decorate(ctx: Context, body: LinearLayout, all: List<View>, from: Int, to: Int) {
        }
    }

    /** API 卡片：装好后做字段整理 + 模型下拉框注入。 */
    class ApiCard : SettingsCard {
        override fun title(): String {
            return "聊天设置（云端 API）"
        }

        override fun dropped(): Boolean {
            return false
        }

        override fun openByDefault(): Boolean {
            // 默认一律收起：首次进入设置页不该有一张卡自动摊开。
            return false
        }

        override fun decorate(ctx: Context, body: LinearLayout, all: List<View>, from: Int, to: Int) {
            // 【改造】卡片下只留入口行；多套配置 / 三个输入框 / 测试连接 / 模型清单
            // 全部搬进「提供商」独立页。原控件对象仍在 all 里，字段引用不会失效。
            body.removeAllViews()
            body.addView(
                ApiPageKit.entryRow(ctx, ApiPageKit.TAG_ENTRY_CFG, "提供商",
                    null, View.OnClickListener { v ->
                        ProviderNav.open(v.context)
                    })
            )
            body.addView(
                ApiPageKit.entryRow(ctx, "feiyu_entry_behavior", "对话行为",
                    null, View.OnClickListener { v ->
                        ProviderNav.openAt(v.context, ProviderNav.R_BEHAVIOR)
                    })
            )
            body.addView(
                ApiPageKit.entryRow(ctx, "feiyu_entry_lamda", "lamda 设备控制",
                    null, View.OnClickListener { v ->
                        ProviderNav.openAt(v.context, ProviderNav.R_LAMDA)
                    })
            )
            body.addView(
                ApiPageKit.entryRow(ctx, ApiPageKit.TAG_ENTRY_TOKEN, "查看 Token",
                    null, View.OnClickListener { v ->
                        TokenStat.open(v.context)
                    })
            )
        }
    }
}
