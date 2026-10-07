package com.dollhouse.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 【职责】输入行工具条上三个按钮共用的底部弹出面板基座 + 模型配置选择面板。
 *
 * 【入口】ChatPanel 的工具条按钮（鲸鱼 = 模型配置、灯泡 = 思考程度、加号 = 功能）。
 *
 * 【交互】遮罩与面板都叠在「父链里第一个 FrameLayout」（与 ChatDrawer 同一套挂载逻辑）；
 *         模型配置的读写全走 ProviderStore，点一下等于把当前供应商与模型切过去。
 *
 * 【扩展】再加一个工具条按钮 = 加一个 showXxx 静态方法，复用 open/close 两处即可。
 *
 * 【坑】本类不能用系统 Dialog —— 桌宠浮窗场景的 Context 链里没有 Activity，
 *       拿不到 window token；所以和 ChatDrawer 一样全部手搭 View。
 *       面板是「按需新建、关闭时整个摘掉」，不缓存复用，避免监听器累积。
 */
final class SheetPanel {

    /** 模型配置面板的遮罩 tag，用于判断是否已打开。 */
    private static final String TAG_MODEL = "feiyu_sheet_model";
    private static final String TAG_THINK = "feiyu_sheet_think";
    private static final String TAG_MEM = "feiyu_sheet_mem";

    private SheetPanel() {
    }

    /* ------------------------------ 基座 ------------------------------ */

    /**
     * 弹一个底部面板。
     * 【坑】遮罩是全屏 FrameLayout，点空白处关闭；面板本身 setClickable(true) 吃掉点击，
     *       否则点面板内部也会被遮罩的 onClick 当成点空白而关掉。
     */
    private static FrameLayout open(ViewGroup layer, String tag, Context ctx, int heightDp) {
        if (layer == null) {
            return null;
        }
        // 同一个面板已经开着就先摘掉，避免连点叠好几层。
        View old = layer.findViewWithTag(tag);
        if (old != null) {
            layer.removeView(old);
            return null;
        }
        // 顺手关掉别的面板：三个工具条按钮是互斥的。
        closeAll(layer);

        final FrameLayout shade = new FrameLayout(ctx);
        shade.setTag(tag);
        shade.setBackgroundColor(UiKit.SCRIM);
        shade.setClickable(true);
        shade.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.slideDownOut(shade);
            }
        });

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiKit.round(UiKit.CARD, ctx, 18));
        panel.setClickable(true);
        int pad = UiKit.dp(ctx, 16);
        panel.setPadding(pad, UiKit.dp(ctx, 14), pad, UiKit.dp(ctx, 18));

        // 顶部拖拽条：纯装饰，用来表明「这是从底部弹上来的」。
        View grip = new View(ctx);
        grip.setBackground(UiKit.round(UiKit.LINE, ctx, 3));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 40), UiKit.dp(ctx, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = UiKit.dp(ctx, 10);
        panel.addView(grip, glp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, heightDp <= 0 ? -2 : UiKit.dp(ctx, heightDp));
        plp.gravity = Gravity.BOTTOM;
        // 面板左右留一点边，避免贴着屏幕边缘显得满。
        plp.leftMargin = UiKit.dp(ctx, 8);
        plp.rightMargin = UiKit.dp(ctx, 8);
        plp.bottomMargin = UiKit.dp(ctx, 8);
        shade.addView(panel, plp);
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        panel.setTag(tag + "_panel");
        // 底部面板：遮罩淡入 + 面板自底部滑入（原先直接 addView，瞬间弹出）。
        panel.post(new Runnable() {
            @Override
            public void run() {
                UiKit.slideUpIn(shade, panel);
            }
        });
        return shade;
    }

    /** 取回 open() 建的面板容器；没开就返回 null。 */
    private static LinearLayout body(FrameLayout shade) {
        if (shade == null || shade.getChildCount() == 0) {
            return null;
        }
        View v = shade.getChildAt(0);
        return v instanceof LinearLayout ? (LinearLayout) v : null;
    }

    private static void closeAll(ViewGroup layer) {
        String[] tags = {TAG_MODEL, TAG_THINK, TAG_MEM};
        for (int i = 0; i < tags.length; i++) {
            View v = layer.findViewWithTag(tags[i]);
            if (v != null) {
                UiKit.slideDownOut(v);
            }
        }
    }

    /** 面板顶部的小标题。 */
    private static TextView title(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(16.0f);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, UiKit.dp(ctx, 4));
        return t;
    }

    private static TextView sub(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextColor(UiKit.SUB);
        t.setLineSpacing(UiKit.dp(ctx, 2), 1.0f);
        t.setPadding(0, 0, 0, UiKit.dp(ctx, 8));
        return t;
    }

    /** 面板里的一行：可点，选中态给底色。 */
    private static LinearLayout row(Context ctx, String name, String right, boolean active,
                                    View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.round(active ? UiKit.CHAT_CHIP_ON : UiKit.SOFT, ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 6);
        r.setLayoutParams(lp);
        int pad = UiKit.dp(ctx, 12);
        r.setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10));

        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));

        if (right != null && !right.isEmpty()) {
            TextView v = new TextView(ctx);
            v.setText(right);
            v.setTextSize(UiKit.FS_SUB);
            v.setTextColor(UiKit.SUB);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            vlp.leftMargin = UiKit.dp(ctx, 8);
            r.addView(v, vlp);
        }
        if (onClick != null) {
            r.setOnClickListener(onClick);
            UiKit.press(r);
        }
        return r;
    }

    /* ------------------------------ 1. 模型配置 ------------------------------ */

    /**
     * 弹「模型配置」面板：供应商 → 模型两级分组。点第一级展开它启用的模型，点模型即选中。
     *
     * 【数据来源】ProviderStore + ModelRules.enabledGroups：供应商禁用则整组不出现，
     *        模型禁用则不列。这里不再维护任何「收藏」概念 —— 启用的模型本来就该可见。
     *
     * 【交互】同一时刻只展开一组，展开下一组时把上一组收干净（此前两组同开，谁是谁的下级看着就糊）。
     *
     * 【为什么不能懒加载】本方法在桌宠浮窗场景调用，Context 链里没有 Activity，
     *        弹系统对话框和起 Activity 都不可靠；所以列表在弹出时一次性建完。
     */
    static void showModels(Context ctx, ViewGroup layer) {
        FrameLayout shade = open(layer, TAG_MODEL, ctx, 0);
        LinearLayout body = body(shade);
        if (body == null) {
            return;
        }
        body.addView(title(ctx, "模型配置"));
        final List<ModelRules.ModelGroup> groups = ModelRules.enabledGroups(
                ProviderStore.providers(ctx), ModelStore.models(ctx));
        final ProviderStore.Selection sel = ProviderStore.current(ctx);
        body.addView(sub(ctx, "点供应商展开它的模型，再点一个模型就切过去。共 "
                + groups.size() + " 个启用中的供应商。\n供应商或模型被禁用时不在这里出现，"
                + "要增删改去设置页「聊天设置（云端 API）」的「提供商」里操作。"));
        ScrollView sc = new ScrollView(ctx);
        final OpenRow open = new OpenRow();
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list, new ViewGroup.LayoutParams(-1, -2));
        for (int i = 0; i < groups.size(); i++) {
            final ModelRules.ModelGroup g = groups.get(i);
            boolean activeGroup = sel.provider != null && g.providerId.equals(sel.providerId);
            String right = g.models.size() + " 个模型";
            list.addView(buildProviderSlot(ctx, open, g, sel, activeGroup, right, layer));
        }
        if (groups.isEmpty()) {
            list.addView(labelOf(ctx, "还没有可用的供应商。去设置页「聊天设置（云端 API）」的「提供商」里"
                    + "添加一个，并在它下面启用至少一个聊天模型。"));
        }
        // 【坑】这里必须用 wrap_content，不能用 (0, weight=1)：
        //       面板是 wrap_content 弹上来的，权重子在 wrap_content 父容器里会被量成 0 高，
        //       结果整个配置列表被折叠成一条线，点开面板什么都看不到。
        //       内容多到超屏时由 FrameLayout 的 AT_MOST 约束收紧，ScrollView 照旧能滚。
        body.addView(sc, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 构建一行「供应商 + 可展开模型清单」：点供应商切换展开，点模型切到该模型。 */
    private static LinearLayout buildProviderSlot(Context ctx, final OpenRow open,
                                                  final ModelRules.ModelGroup g,
                                                  final ProviderStore.Selection sel,
                                                  boolean activeGroup, String right, final ViewGroup layer) {
        // 每一行外面套一个竖向容器：上面是供应商行，下面挂模型清单（展开时才长出来）。
        LinearLayout slot = new LinearLayout(ctx);
        slot.setOrientation(LinearLayout.VERTICAL);
        slot.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout models = new LinearLayout(ctx);
        models.setOrientation(LinearLayout.VERTICAL);
        models.setVisibility(8);
        final boolean[] opened = {false};
        // 【坑】row() 是纯工厂，不会自己挂上去 —— 必须接住返回值 addView，
        //       否则供应商行压根不进布局（面板看着是空的）。
        LinearLayout head = row(ctx, (activeGroup ? "● " : "") + g.providerName, right, activeGroup,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 【归属】先把上一组收干净：两组同时挂着，
                        //   「谁是下级」在观感上就糊了 —— 用户报的正是这个。
                        if (open.models != null && open.models != models) {
                            UiKit.collapse(open.models);
                            open.models.removeAllViews();
                            if (open.opened != null) {
                                open.opened[0] = false;
                            }
                            open.models = null;
                            open.opened = null;
                        }
                        if (opened[0]) {
                            opened[0] = false;
                            open.models = null;
                            open.opened = null;
                            // 【丝滑】模型清单收起用淡出，不再一下消失。
                            UiKit.collapse(models);
                            models.removeAllViews();
                            return;
                        }
                        opened[0] = true;
                        open.models = models;
                        open.opened = opened;
                        // 【丝滑】模型清单展开用淡入，不再一下冒出来。
                        UiKit.reveal(models);
                        models.removeAllViews();
                        for (int k = 0; k < g.models.size(); k++) {
                            final AiModel m = g.models.get(k);
                            boolean on = sel.modelId != null && sel.modelId.equals(m.id);
                            models.addView(modelRow(ctx, m.displayName, on,
                                    new View.OnClickListener() {
                                        @Override
                                        public void onClick(View x) {
                                            ProviderStore.setCurrent(ctx, g.providerId, m.id);
                                            closeAll(layer);
                                        }
                                    }));
                        }
                    }
                });
        slot.addView(head);
        slot.addView(models);
        return slot;
    }
    /**
     * 「当前展开的那一组」的句柄：只记容器和它自己的开关标志。
     * 【为什么需要】展开前要先把它收干净，否则两组的模型清单会同时挂在面板上，
     *   看着就像「不是这个供应商的下级也被列出来了」。
     */
    private static final class OpenRow {
        LinearLayout models;
        boolean[] opened;
    }
    /** 模型清单里的一行：选中态给底色，点一下就把「这套配置」的模型换成它。 */
    private static TextView modelRow(Context ctx, String name, boolean active,
                                     View.OnClickListener onClick) {
        TextView t = new TextView(ctx);
        // 【图标语义】选中项用实心勾图标代替裸「✓ 」字符；未选中不再占位（原先的「· 」是视觉噪声）。
        t.setText(name);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(active ? UiKit.CHAT_CHIP_FG : UiKit.TITLE);
        if (active) {
            Icons.stateIcon(t, Icons.IC_CHECK, t.getCurrentTextColor(), 13.0f, 5);
        }
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackground(UiKit.round(active ? UiKit.CHAT_CHIP_ON : UiKit.CARD, ctx, 8));
        t.setPadding(UiKit.dp(ctx, 22), UiKit.dp(ctx, 9), UiKit.dp(ctx, 12), UiKit.dp(ctx, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 4);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        UiKit.press(t);
        return t;
    }

    /* ------------------------------ 2. 思考程度 ------------------------------ */

    /**
     * 弹「调整模型思考深度」面板：灯泡 + 当前档位名 + 6 档刻度滑块。
     * 【交互】点刻度或点档位名都直接存盘；滑块只做视觉指示，不参与手势
     *        （纯 View 的拖动会和父级滚动打架，点选更稳）。
     */
    static void showThink(Context ctx, ViewGroup layer, ChatPanel host) {
        FrameLayout shade = open(layer, TAG_THINK, ctx, 0);
        LinearLayout body = body(shade);
        if (body == null) {
            return;
        }
        body.addView(title(ctx, "调整模型思考深度"));
        body.addView(sub(ctx, "并不是所有模型都支持深度调整。服务端不认识这些参数时会忽略，不影响正常对话。"));

        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(rail, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = UiKit.dp(ctx, 12);
        body.addView(names, nlp);

        body.addView(sub(ctx, "\u300c不思考」= 明确要求她别绕弯子；「自动」= 交给服务端自己决定；再往后依次加深。"));

        new ThinkSheet(ctx, rail, names, host).rebuild();
    }

    /**
     * 思考程度面板的「灯泡 + 档位名 + 6 档刻度」这一块。
     * 【坑】做成内部类是为了拿到自身引用来重画：Java 的匿名内部类里拿不到自己的 Runnable，
     *       用静态字段存会串（同时开两次面板就指向后建的那个）。
     */
    private static final class ThinkSheet {
        private final Context ctx;
        private final LinearLayout rail;
        private final LinearLayout names;
        private final ChatPanel host;

        ThinkSheet(Context ctx, LinearLayout rail, LinearLayout names, ChatPanel host) {
            this.ctx = ctx;
            this.rail = rail;
            this.names = names;
            this.host = host;
        }

        /** 按当前档位整块重画：档位名、刻度高亮、6 个档位名的选中态一起刷。 */
        void rebuild() {
            final int level = PetPrefs.thinkLevel(ctx);
            rail.removeAllViews();
            names.removeAllViews();

            ImageView bulb = Icons.view(ctx, Icons.IC_BRAIN, 24.0f, UiKit.ACC);
            rail.addView(bulb, new LinearLayout.LayoutParams(UiKit.dp(ctx, 46), UiKit.dp(ctx, 46)));

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(UiKit.dp(ctx, 10), 0, 0, 0);

            TextView state = new TextView(ctx);
            state.setText(PetPrefs.THINK_NAMES[level]);
            state.setTextSize(19.0f);
            state.setTextColor(UiKit.ACC);
            state.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(state);

            // 轨道：每档一个等宽格子，选中那格给主色，视觉上就是「滑块停在这一档」。
            LinearLayout track = new LinearLayout(ctx);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = UiKit.dp(ctx, 8);
            box.addView(track, tlp);
            for (int i = 0; i < PetPrefs.THINK_NAMES.length; i++) {
                boolean on = i == level;
                TextView dot = new TextView(ctx);
                dot.setBackground(UiKit.round(on ? UiKit.ACC : UiKit.LINE, ctx, 3));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        0, UiKit.dp(ctx, on ? 8 : 5), 1.0f);
                dlp.rightMargin = i == PetPrefs.THINK_NAMES.length - 1 ? 0 : UiKit.dp(ctx, 5);
                dlp.gravity = Gravity.CENTER_VERTICAL;
                dot.setLayoutParams(dlp);
                dot.setClickable(true);
                dot.setOnClickListener(pick(i));
                track.addView(dot);
            }
            box.addView(labelOf(ctx, "拖不动也没关系，点刻度就行"));
            rail.addView(box, new LinearLayout.LayoutParams(0, -2, 1.0f));

            // 六个档位的名字横排，点名字同样生效（比点细刻度好按）。
            for (int i = 0; i < PetPrefs.THINK_NAMES.length; i++) {
                boolean on = i == level;
                TextView t = new TextView(ctx);
                t.setText(PetPrefs.THINK_NAMES[i]);
                t.setTextSize(UiKit.FS_SUB);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(on ? UiKit.ON_ACC : UiKit.SUB);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setPadding(0, UiKit.dp(ctx, 7), 0, UiKit.dp(ctx, 7));
                t.setBackground(UiKit.round(on ? UiKit.ACC : UiKit.SOFT, ctx, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                lp.rightMargin = i == PetPrefs.THINK_NAMES.length - 1 ? 0 : UiKit.dp(ctx, 5);
                t.setLayoutParams(lp);
                t.setClickable(true);
                t.setOnClickListener(pick(i));
                UiKit.press(t);
                names.addView(t);
            }
        }

        private View.OnClickListener pick(final int idx) {
            return new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PetPrefs.setThinkLevel(ctx, idx);
                    rebuild();
                }
            };
        }
    }

    private static TextView labelOf(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextColor(UiKit.SUB);
        t.setPadding(0, UiKit.dp(ctx, 10), 0, 0);
        return t;
    }

    /* ------------------------------ 3. 记忆（加号） ------------------------------ */

    /**
     * 弹「记忆」功能面板：发图 / 立即总结 / 打开记忆库。
     * 【入口】工具条上的加号。
     *
     * 【d19】原「自动保存记忆」「自动精简记忆」两个开关已移植到设置页「记忆」卡片下级；
     *   本面板因此只剩三个可点项，顶部标题与说明行也一并删掉，间距相应收紧。
     */
    static void showMemory(Context ctx, ViewGroup layer, final ChatPanel host) {
        FrameLayout shade = open(layer, TAG_MEM, ctx, 0);
        final LinearLayout body = body(shade);
        if (body == null) {
            return;
        }
        // 【需求】收紧：少了标题与两行开关说明后，公共 open() 的 14/18dp 内边距显得上下都空，
        //   本面板单独压到 10/12dp。（只改本面板，不动 open()，避免波及模型配置 / 思考程度两个面板。）
        int memPad = UiKit.dp(ctx, 16);
        body.setPadding(memPad, UiKit.dp(ctx, 10), memPad, UiKit.dp(ctx, 12));
        // 【需求】本面板只留三个可点项：发图 / 立即总结 / 打开记忆库。
        //   · 原「记忆」标题已删（面板本身不需要再自报家门）；
        //   · 「自动保存记忆」「自动精简记忆」两个开关已移植到设置页「记忆」卡片下级；
        //   · 「发图」行不再带副标题 —— 图标 + 「发图」二字已足够说明它是什么。
        body.addView(entryRow(ctx, Icons.IC_IMAGE, "发图", null, new Runnable() {
            @Override
            public void run() {
                closeAll(layer);
                if (host != null) {
                    host.openPicker();
                }
            }
        }));

        LinearLayout acts = new LinearLayout(ctx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        // 【需求】收紧：原先 14dp 的顶间距在少了标题与说明行之后显得空，收到 10dp。
        alp.topMargin = UiKit.dp(ctx, 10);
        acts.setLayoutParams(alp);

        // 【v2.3】原「立即整理记忆」入口按需求删除（记忆归并保留自动档）；
        //         原位置改为「立即总结」——把当前对话压成摘要，与右侧「打开记忆库」同构造、等宽同行。
        TextView sum = pill(ctx, "立即总结");
        sum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAll(layer);
                // 【v2.9.4】覆盖复审报的 P1：host==null 会让整条链路静默不执行。
                Logs.i("DollhouseMemo", "[入口] 面板点按 host=" + (host != null));
                if (host != null) {
                    host.summarizeNow();
                }
            }
        });
        acts.addView(sum, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView lib = pill(ctx, "打开记忆库");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        llp.leftMargin = UiKit.dp(ctx, 8);
        lib.setLayoutParams(llp);
        lib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAll(layer);
                MemPage.open(ctx);
            }
        });
        acts.addView(lib);
        body.addView(acts);
    }

    /**
     * 面板里的一行「图标 + 名称/说明 + ›」入口。
     * 样式：SOFT 圆角底 + 12dp 横向内边距 + 8dp 上间距，点一下先收面板再执行动作。
     * hint 传 null 或空串则不显示副标题行（与 ApiPageKit.entryRow 同一口径）。
     */
    private static LinearLayout entryRow(Context ctx, int icon, String name, String hint, final Runnable action) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 8);
        r.setLayoutParams(lp);
        int pad = UiKit.dp(ctx, 12);
        r.setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10));
        ImageView iv = UiKit.iconView(ctx, icon, 15.0f, UiKit.SUB);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.rightMargin = UiKit.dp(ctx, 10);
        iv.setLayoutParams(ilp);
        r.addView(iv);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        texts.addView(t);
        if (hint != null && hint.length() > 0) {
            TextView s = new TextView(ctx);
            s.setText(hint);
            s.setTextSize(UiKit.FS_TINY);
            s.setTextColor(UiKit.SUB);
            s.setPadding(0, UiKit.dp(ctx, 2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView go = new TextView(ctx);
        go.setText("\u203a");
        go.setTextSize(UiKit.FS_BTN);
        go.setTextColor(UiKit.SUB);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(go, new LinearLayout.LayoutParams(-2, -2));
        r.setClickable(true);
        UiKit.press(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    action.run();
                }
            }
        });
        return r;
    }

    /** 面板底部的胶囊按钮。 */
    private static TextView pill(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.CHAT_CHIP_FG);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UiKit.round(UiKit.CHAT_CHIP_BG, ctx, 12));
        t.setPadding(0, UiKit.dp(ctx, 11), 0, UiKit.dp(ctx, 11));
        t.setClickable(true);
        UiKit.press(t);
        return t;
    }
}