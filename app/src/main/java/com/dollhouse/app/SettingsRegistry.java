package com.dollhouse.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

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
final class SettingsRegistry {
    private SettingsRegistry() {
    }
    /** 全部已声明的分组。 */
    static final List<SettingsCard> CARDS = new ArrayList<SettingsCard>();
    static {
        CARDS.add(new ApiCard());
        CARDS.add(new SimpleCard("联网搜索", false, false));
        CARDS.add(new SimpleCard("操作方式", false, false));
        CARDS.add(new SimpleCard("学习（点赞 -> 示范）", false, false));
        CARDS.add(new SimpleCard("应用图标", false, false));
        CARDS.add(new SimpleCard("聊天背景", false, false));
        CARDS.add(new SimpleCard("本地模型（离线，不花 token）", true, false));
        CARDS.add(new SimpleCard("联网搜索", true, false));
        CARDS.add(new SimpleCard("学习（点赞 -> 示范）", true, false));
    }
    /**
     * 按标题找声明；找不到返 null。
     * 同一标题同时有「卡片」与「摘除」两条声明时，摘除优先（与拆前两数组各自匹配的行为一致）。
     */
    static SettingsCard find(String title) {
        if (title == null) {
            return null;
        }
        SettingsCard hit = null;
        for (int i = 0; i < CARDS.size(); i++) {
            SettingsCard card = CARDS.get(i);
            if (!title.equals(card.title())) {
                continue;
            }
            if (card.dropped()) {
                return card;
            }
            if (hit == null) {
                hit = card;
            }
        }
        return hit;
    }
    /** 该标题是否已登记（卡片或摘除都算）。 */
    static boolean known(String title) {
        return find(title) != null;
    }
    /** 该标题是否是被下线摘除的组。 */
    static boolean dropped(String title) {
        SettingsCard settingsCard = find(title);
        return settingsCard != null && settingsCard.dropped();
    }
    /** 仅当登记为卡片（未摘除）时才成立。 */
    static boolean card(String title) {
        SettingsCard settingsCard = find(title);
        return settingsCard != null && !settingsCard.dropped();
    }
    /** 普通卡片：只声明标题与状态。 */
    static final class SimpleCard implements SettingsCard {
        private final String title;
        private final boolean dropped;
        private final boolean open;
        SimpleCard(String title, boolean dropped, boolean open) {
            this.title = title;
            this.dropped = dropped;
            this.open = open;
        }
        @Override
        public String title() {
            return this.title;
        }
        @Override
        public boolean dropped() {
            return this.dropped;
        }
        @Override
        public boolean openByDefault() {
            return this.open;
        }
        @Override
        public void decorate(Context ctx, LinearLayout body, List<View> all, int from, int to) {
        }
    }
    /** API 卡片：装好后做字段整理 + 模型下拉框注入。 */
    static final class ApiCard implements SettingsCard {
        @Override
        public String title() {
            return "聊天设置（云端 API）";
        }
        @Override
        public boolean dropped() {
            return false;
        }
        @Override
        public boolean openByDefault() {
            // 默认一律收起：首次进入设置页不该有一张卡自动摊开。
            return false;
        }
        @Override
        public void decorate(Context ctx, LinearLayout body, List<View> all, int from, int to) {
            // 【改造】卡片下只留两行入口；多套配置 / 三个输入框 / 测试连接 / 模型清单
            // 全部搬进「配置 API」独立页。原控件对象仍在 all 里，字段引用不会失效。
            body.removeAllViews();
            body.addView(ApiConfigPage.entryRow(ctx, ApiConfigPage.TAG_ENTRY_CFG, "配置 API",
                    "", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ApiConfigPage.open(v.getContext());
                        }
                    }));
            body.addView(ApiConfigPage.entryRow(ctx, ApiConfigPage.TAG_ENTRY_TOKEN, "查看 Token",
                    null, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            TokenStat.open(v.getContext());
                        }
                    }));
        }
    }
}
