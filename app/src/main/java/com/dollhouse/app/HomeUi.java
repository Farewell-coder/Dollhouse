package com.dollhouse.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 【职责】首页与设置页的装配与状态同步：把 buildUi() 产出的长列表拆成首页 / 设置页两页。
 *
 * 【入口】MainActivity 装配主界面时调用；页内按钮回调触发状态刷新。
 *
 * 【交互】卡片与控件工厂走 HomeCards（详见 HomeCards）；折叠与卡片清单走 SettingsPage / SettingsRegistry；
 *         启动 / 停止经 Intent 发给 PetService；权限与开关状态读 PetPrefs。
 *
 * 【坑】不新建 Activity、不改清单；结构不符合预期就整体放弃，界面退回原样
 *       （TAG_HOME / TAG_SET 找不到就当没这回事）——改页面结构时务必保证这两个 tag 还在。
 *
 * 首页   = 标题 + 副标题 + 人偶 + 状态 + 启动/关闭 + 打开聊天 + 设置
 * 设置页 = [← 设置] 头部 + 卡片(权限 / 聊天与Token / 外观 / 记忆 / 操作 / 关于)
 */
public final class HomeUi {
    private static final String LOG_TAG = "Dollhouse";
    private static final String TAG_HOME = "feiyu_home_page";
    private static final String TAG_SET = "feiyu_settings_page";
    /** 滚动位置的存储键（首页 / 设置页各一份）。 */
    private static final String KEY_HOME = "home";
    private static final String KEY_SET = "set";
    private static final String TAG_TOGGLE = "feiyu_home_toggle";
    private static final String TAG_PETS = "feiyu_home_pets";
    private static final String TAG_PERM_NOTIF = "feiyu_perm_notif";
    /** Shizuku 授权行的 View tag：四态文案由 ShizukuBridge 提供，不走 bindPermRow 的两态常量。 */
    private static final String TAG_PERM_SHIZUKU = HomeCards.TAG_PERM_SHIZUKU;
    /** 第一级开关：隐藏后台（最近任务）卡片。 */
    private static final String TAG_HIDE_RECENTS = "feiyu_hide_recents";
    /** 通知权限的运行时申请回调码。 */
    private static final int REQ_NOTIF = 102;
    /** Shizuku 授权请求码（结果经 ShizukuBridge 的 listener 回来）。 */
    private static final int REQ_SHIZUKU = 103;

    /** 卡片标题：旧分组。 */
    private static final String T_WEB = "\u8054\u7f51\u641c\u7d22";
    private static final String T_LEARN = "\u5b66\u4e60\uff08\u70b9\u8d5e \u2192 \u793a\u8303\uff09";
    private static final String T_ICON = "\u5e94\u7528\u56fe\u6807";
    private static final String T_CHAT_OLD = "\u804a\u5929\u8bbe\u7f6e\uff08\u4e91\u7aef API\uff09";
    private static final String T_OP_OLD = "\u64cd\u4f5c\u65b9\u5f0f";
    private static final String T_BG = "\u804a\u5929\u80cc\u666f";
    private static final String T_FOOTER = "\u8bf4\u660e\uff1a";
    /** 卡片标题：新框架。 */
    private static final String T_PERM = "\u6743\u9650";
    /** 权限卡片内两行子项的左侧名称。 */
    private static final String T_PERM_OVERLAY_NAME = "\u60ac\u6d6e\u7a97\u6743\u9650";
    private static final String T_PERM_NOTIF_NAME = "\u901a\u77e5\u6743\u9650";
    /** Shizuku 授权行（权限卡片下级第一项）：让本应用与 AI 拿到 adb shell 级能力。 */
    private static final String T_PERM_SHIZUKU_NAME = "Shizuku \u6388\u6743";
    /** 权限卡片下级：保活分组的说明行与子项名称（@需求：保活权限挂「权限」卡片下级）。 */
    private static final String T_PERM_BATTERY_NAME = "\u7535\u6c60\u4f18\u5316\u767d\u540d\u5355";
    /** 【v2.9.6】后台耗电管理：ColorOS 冻结后台应用的开关页（OplusHansManager freeze）。 */
    private static final String T_GUIDE_BG_POWER_NAME = "\u540e\u53f0\u8017\u7535\u7ba1\u7406";
    private static final String T_GUIDE_AUTOSTART_NAME = "\u5141\u8bb8\u81ea\u542f\u52a8";
    private static final String T_GUIDE_BG_ACTIVITY_NAME = "\u5141\u8bb8\u540e\u53f0\u6d3b\u52a8";
    /** 保活子项的 View tag，供 syncPerm 定位与点击分发。 */
    private static final String TAG_PERM_BATTERY = "feiyu_perm_battery";
    private static final String TAG_GUIDE_BG_POWER = "feiyu_guide_bg_power";
    private static final String TAG_GUIDE_AUTOSTART = "feiyu_guide_autostart";
    private static final String TAG_GUIDE_BG_ACTIVITY = "feiyu_guide_bg";
    /** 第一级开关标题。 */
    private static final String T_HIDE_RECENTS = "\u9690\u85cf\u540e\u53f0\u5361\u7247";
    private static final String T_CHAT_NEW = "\u804a\u5929";
    private static final String T_DOLL_NEW = "\u4eba\u5076";
    private static final String T_LOOK_NEW = "\u5916\u89c2";
    private static final String T_EMPTY_HINT = "\u6682\u65e0\u66f4\u591a\u7684\u4eba\u5076";
    private static final String T_SCALE = "调整比例";
    private static final String T_SCALE_HINT = "点加减号调整，范围 0% ~ 100%，每档 10%。";
    private static final String TAG_DOLL_SCALE = "feiyu_doll_scale";
    private static final String T_OP_NEW = "\u64cd\u4f5c";
    private static final String[] T_EMPTY = {
            "\u5916\u89c2", "\u8bb0\u5fc6", "\u5173\u4e8e"
    };
    /** 「记忆」卡片下级：自动总结开关 / 触发阈值 / 记忆库入口。 */
    private static final String T_MEM_AUTO = "\u81ea\u52a8\u603b\u7ed3";
    private static final String T_MEM_THRESHOLD = "\u89e6\u53d1\u9608\u503c";
    private static final String T_MEM_LIB = "\u6253\u5f00\u8bb0\u5fc6\u5e93";
    private static final String TAG_MEM_AUTO = "feiyu_mem_auto";
    private static final String TAG_MEM_THRESHOLD = "feiyu_mem_threshold";
    private static final String TAG_MEM_LIB = "feiyu_mem_lib";
    /** 卡片右侧状态文案。 */
    /** 主题相关文案（「外观」卡片下级）。 */
    private static final String T_THEME_MODE = "\u4e3b\u9898\u6a21\u5f0f";
    private static final String T_THEME_MONET = "\u83ab\u5948\u4e3b\u9898\u8272";
    /** 莫奈取色不可用时的提示。 */
    private static final String T_THEME_MONET_NO = "\u58c1\u7eb8\u53d6\u8272\u4e0d\u53ef\u7528\uff0c\u4ecd\u7528\u5185\u7f6e\u914d\u8272";
    /** 主题行的 View tag，供 syncTheme 定位与点击分发。 */
    private static final String TAG_THEME_MODE = "feiyu_theme_mode";
    private static final String TAG_THEME_MONET = "feiyu_theme_monet";
    /** 【v2.10.0】「关于」卡片里那一行入口按钮的 tag。 */
    private static final String TAG_ABOUT_ENTRY = "feiyu_about_entry";
    /** 【需求】权限卡片下级的磁贴开关与开关指令（两者是同一条「启停」通道的两种入口）。 */
    private static final String T_TILE = "快捷设置磁贴";
    private static final String T_CMD = "开关指令";
    private static final String T_CMD_COPY = "复制 \u203a";
    private static final String T_CMD_COPIED = "已复制 \u2713";
    /** 复制出去的外部链接：同一条链接兼管开与关（PetLinkActivity 里按运行状态自行判断）。 */
    private static final String T_CMD_LINK = "dollhouse://pet/toggle";
    private static final String TAG_TILE_SWITCH = "feiyu_tile_switch";
    private static final String TAG_CMD_ROW = "feiyu_cmd_row";
    /** 人偶当前是否处于「已启动」状态，仅用于切换首页那颗按钮的文案。 */
    private static boolean running;

    // 工具类：只提供静态方法，禁止实例化。
    private HomeUi() {
    }

    /** smali 侧唯一入口，在 SettingsFold.apply 之后调用一次。 */
    public static void apply(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            ScrollView sv = findScrollView(decor);
            if (sv == null || sv.getChildCount() == 0) {
                return;
            }
            View child = sv.getChildAt(0);
            if (!(child instanceof LinearLayout)) {
                return;
            }
            final LinearLayout box = (LinearLayout) child;
            if (box.getOrientation() != LinearLayout.VERTICAL || box.getChildCount() < 9) {
                return;
            }
            final ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            final Context ctx = activity;

            final View vTitle = box.getChildAt(0);
            final View vSub = box.getChildAt(1);
            final View vPet = box.getChildAt(2);
            final View vStatus = box.getChildAt(3);
            View vOverlay = box.getChildAt(4);
            if (!(vTitle instanceof TextView) || !(vSub instanceof TextView)
                    || !(vPet instanceof ImageView) || !(vStatus instanceof TextView)) {
                return;
            }

            // ---- 设置页要删掉的三个按钮 + 首页复用的控件实例 ----
            Button bStart = null;
            Button bStop = null;
            Button bChat = null;
            for (int i = 0; i < box.getChildCount(); i++) {
                View v = box.getChildAt(i);
                if (!(v instanceof Button)) {
                    continue;
                }
                String t = UiKit.textOf(v);
                if (t == null) {
                    continue;
                }
                // 注意：按钮文本带序号前缀（如「2. 启动桌宠」），只能用 contains 匹配。
                if (t.contains("\u542f\u52a8\u684c\u5ba0")) {
                    bStart = (Button) v;
                } else if (t.contains("\u505c\u6b62\u684c\u5ba0")) {
                    bStop = (Button) v;
                } else if (t.contains("\u6253\u5f00\u804a\u5929")) {
                    bChat = (Button) v;
                }
            }
            if (bStart == null || bStop == null || bChat == null) {
                // 结构不符，整体放弃，保持原样可用。
                return;
            }
            // 两个图片功能的入口按钮：从设置页摘出，稍后重排进「外观」。
            // 【修·根因】这两个按钮创建在「聊天背景」分组里，SettingsPage.apply 会先把它们
            //   包进那张卡片的 body；按 box 顶层遍历必然找不到（旧实现在这里恒为 null，
            //   于是两个按钮既没被搬进「外观」、也没被美化 —— 就是「外观下两个功能失效」）。
            //   改按 tag 在整棵视图树里递归查找；且背景按钮的文本要等 onResume 的
            //   refreshLocalUi 才写入，创建时是空串，文本匹配同样靠不住，必须用 tag。
            Button bBg = findButtonByTag(activity, HomeCards.TAG_BG_PICK);
            Button bClear = findButtonByTag(activity, HomeCards.TAG_BG_CLEAR);
            // 注意：它们此刻多半已不是 box 的直接子，box.removeView 对非直接子是空操作，
            //       必须按控件自己的父容器摘除，否则下面 addView 到「外观」会因已有父而抛异常。
            detachFromParent(bBg);
            detachFromParent(bClear);
            // 按钮从设置页摘掉（控件实例仍有效，首页通过 performClick 复用其逻辑）。
            box.removeView(bStart);
            box.removeView(bStop);
            box.removeView(bChat);
            if (vOverlay != null) {
                box.removeView(vOverlay);
            }

            // ---- 设置页：摘旧卡片、留新卡片 ----
            View cardChat = null;
            View cardOp = null;
            LinearLayout cardBg = null;
            List<View> kill = new ArrayList<View>();
            for (int i = 0; i < box.getChildCount(); i++) {
                View v = box.getChildAt(i);
                String t = HomeCards.cardTitle(v);
                if (t == null) {
                    if (v instanceof TextView) {
                        String s = UiKit.textOf(v);
                        if (s != null && s.startsWith(T_FOOTER)) {
                            kill.add(v);
                        }
                    }
                    continue;
                }
                if (T_CHAT_OLD.equals(t)) {
                    cardChat = v;
                } else if (T_OP_OLD.equals(t)) {
                    cardOp = v;
                } else if (T_BG.equals(t)) {
                    // 【修】原来整张「聊天背景」卡片都被摘掉，可卡里除了两个已搬到「外观」的
                    //   按钮，还留着「背景透明度」的说明、标签与滑条 —— 摘卡片等于把透明度
                    //   调节整个弄没了（全工程没有第二处重建滑条的代码）。改成保留这张卡片，
                    //   稍后把它的正文整体并进「外观」。
                    if (v instanceof LinearLayout) {
                        cardBg = (LinearLayout) v;
                    }
                } else if (T_WEB.equals(t) || T_LEARN.equals(t) || T_ICON.equals(t)) {
                    // 联网搜索 / 学习 = 功能取消；应用图标 = 功能入口已提到「外观」里
                    // 做成按钮，卡片本体（只剩说明文字）不再需要。
                    kill.add(v);
                }
            }
            for (int i = 0; i < kill.size(); i++) {
                box.removeView(kill.get(i));
            }
            HomeCards.setCardTitle(cardChat, T_CHAT_NEW);
            HomeCards.setCardTitle(cardOp, T_OP_NEW);

            // ---- 首页 ----
            // 【v2.10.0】副标题（标题下的引导小字）与状态行（人偶下的小字）整行移除：
            //   只是不挂进新容器，控件本身与 HomeScreenBuilder 里的索引顺序都不动，
            //   HomeUi.apply 开头基于 childCount / 索引的结构契约因此依旧成立。
            box.removeView(vTitle);
            box.removeView(vSub);
            box.removeView(vPet);
            box.removeView(vStatus);

            LinearLayout petHolder = new LinearLayout(ctx);
            petHolder.setOrientation(LinearLayout.VERTICAL);
            // 【v2.10.1】人偶贴上半区底部：整组垂直居中会让人偶与下方的按钮隔出大片空白，
            //   改成横向居中 + 纵向靠底，再用底部 padding 垫出与按钮区的呼吸距离。
            petHolder.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            petHolder.setPadding(0, 0, 0, UiKit.dp(ctx, 26));
            petHolder.setTag(TAG_PETS);
            petHolder.addView(vPet, new LinearLayout.LayoutParams(-2, -2));

            final Button toggle = HomeCards.mkButton(ctx, "\u542f\u52a8\u4eba\u5076", true);
            Button open = HomeCards.mkButton(ctx, "\u6253\u5f00\u804a\u5929", false);
            Button setting = HomeCards.mkButton(ctx, "\u8bbe\u7f6e", false);

            // 【v2.10.1】按钮组贴下半区顶部（同理：居中会在人偶与按钮间留出大片空白）。
            //   顶部 padding 与 petHolder 的底部 padding 相加，就是人偶脚底到第一颗按钮的净间距。
            LinearLayout buttonsBox = new LinearLayout(ctx);
            buttonsBox.setOrientation(LinearLayout.VERTICAL);
            buttonsBox.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            buttonsBox.setPadding(0, UiKit.dp(ctx, 26), 0, 0);
            buttonsBox.addView(toggle);
            buttonsBox.addView(open);
            buttonsBox.addView(setting);

            final LinearLayout homeBox = new LinearLayout(ctx);
            homeBox.setOrientation(LinearLayout.VERTICAL);
            int pad = UiKit.dp(ctx, 20);
            homeBox.setPadding(pad, pad, pad, pad);
            homeBox.addView(vTitle);
            // 【v2.10.0】人偶区与按钮区各占剩余空间的一半：人偶自然落在上半区、
            //   三按钮落在下半区，中间留白由 weight 撑满（ScrollView 已开 fillViewport）。
            homeBox.addView(petHolder, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            homeBox.addView(buttonsBox, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            // 首页入场：标题→人偶→按钮依次淡入上移。
            // 换主题触发的重建跳过入场动画：否则整页会重新淡入一次，
            // 看起来就像按钮集体消失、再一个个冒出来。用户手动进页面时照旧播动画。
            if (!PetPrefs.themeRestore(ctx)) {
                UiKit.enter(vTitle, 0);
                UiKit.enter(petHolder, 80);
                UiKit.enter(toggle, 160);
                UiKit.enter(open, 200);
                UiKit.enter(setting, 240);
            }
            toggle.setTag(TAG_TOGGLE);

            final ScrollView homeScroll = new ScrollView(ctx);
            homeScroll.setBackgroundColor(UiKit.BG);
            // 【v2.10.0】让内容不足一屏时也撑满：上面的 weight 才会真的生效。
            homeScroll.setFillViewport(true);
            // 关掉滑动到头部的拉伸辉光：那是系统默认装饰，与本 App 的卡片质感不搭。
            homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            homeScroll.addView(homeBox);
            homeScroll.setTag(TAG_HOME);

            syncToggle(ctx);
            final Button fStart = bStart;
            final Button fStop = bStop;
            final Button fChat = bChat;
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = !running;
                    syncToggle(v.getContext());
                    if (now) {
                        fStart.performClick();
                    } else {
                        fStop.performClick();
                    }
                    toggle.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            syncToggle(v.getContext());
                            toggle.setText(running ? "\u5173\u95ed\u4eba\u5076" : "\u542f\u52a8\u4eba\u5076");
                        }
                    }, 600L);
                }
            });
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fChat.performClick();
                }
            });
            setting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(activity, false);
                }
            });
            // 【改动】人偶本身不再点进全屏聊天页；聊天的合法入口只剩上方「打开聊天」。
            //   人偶的三击行为在桌宠悬浮窗里（PetService.triple_tap），与本页无关。

            // ---- 设置页重排 ----
            LinearLayout header = HomeCards.buildHeader(activity, ctx);

            LinearLayout cardPerm = HomeCards.buildCard(ctx, T_PERM);
            LinearLayout permBody = (LinearLayout) cardPerm.getChildAt(1);
            // ---- 权限卡片下级第一项：Shizuku 授权（四态：已授权 / 未授权 / 服务未运行 / 未安装）----
            // 【为什么放最前】它是本应用与 AI 拿到 shell 级能力的总开关，其余权限只影响人偶本身。
            //   文案与可点性由 ShizukuBridge.state 决定，走 bindShizukuRow 绑定（不是两态 bindPermRow）。
            permBody.addView(HomeCards.permRow(ctx, T_PERM_SHIZUKU_NAME, TAG_PERM_SHIZUKU));
            // 权限子项：左名称 + 右开关（隐藏最近任务卡片）；
            // 其余行 左名称 + 右状态（未授权时整行可点去授权）。
            UiKit.Switch hideRecents = new UiKit.Switch(ctx);
            hideRecents.setTag(TAG_HIDE_RECENTS);
            hideRecents.setOn(PetPrefs.hideRecents(ctx));
            permBody.addView(HomeCards.switchRow(ctx, T_HIDE_RECENTS, hideRecents));
            permBody.addView(HomeCards.permRow(ctx, T_PERM_OVERLAY_NAME, HomeCards.TAG_PERM_OVERLAY));
            permBody.addView(HomeCards.permRow(ctx, T_PERM_NOTIF_NAME, TAG_PERM_NOTIF));
            // ---- 保活分组（「权限」卡片的下级）：让桌宠被划掉 / 冻结 / 重启后还能自己回来 ----
            // 电池优化白名单：可查状态（isIgnoringBatteryOptimizations），红绿字 + 可点。
            permBody.addView(HomeCards.permRow(ctx, T_PERM_BATTERY_NAME, TAG_PERM_BATTERY));
            // 【v2.10.0】以下三项系统查不到授权状态：一律「去设置 ›」，
            //  点击各自直达对应系统页（分发逻辑见 guideClicked）；
            //  其中「后台耗电管理」就是 ColorOS 冻结本 App 的开关页。
            permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_BG_POWER_NAME, TAG_GUIDE_BG_POWER));
            permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_AUTOSTART_NAME, TAG_GUIDE_AUTOSTART));
            permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_BG_ACTIVITY_NAME, TAG_GUIDE_BG_ACTIVITY));
            // ---- 【需求】开关通道（「权限」卡片下级）：磁贴开关 + 开关指令，两者是同一条启停通道的两种入口 ----
            // 磁贴开关：右侧是自绘开关，开=请系统把磁贴放进快捷面板，关=打开面板让你长按移除；
            //  真实状态由 TileService 的 onTileAdded / onTileRemoved 回写 PetPrefs.tileAdded。
            UiKit.Switch tileSw = new UiKit.Switch(ctx);
            tileSw.setTag(TAG_TILE_SWITCH);
            tileSw.setOn(PetPrefs.tileAdded(ctx), false);
            permBody.addView(HomeCards.switchRow(ctx, T_TILE, tileSw, new HomeCards.OnChanged() {
                @Override
                public void onChanged(boolean on, Context c) {
                    onTileSwitchChanged(on, c);
                }
            }));
            // 开关指令：整行可点，右侧是「复制 ›」；点一下把同一条链接复制走（开与关共用）。
            LinearLayout cmdRow = HomeCards.valueRow(ctx, T_CMD, TAG_CMD_ROW);
            HomeCards.setRowValue(cmdRow, T_CMD_COPY);
            cmdRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyToggleLink(v.getContext());
                }
            });
            permBody.addView(cmdRow);
            permBody.setVisibility(View.VISIBLE);
            // 首帧就按真实授权状态渲染两行，不等 onResume。
            syncPerm(activity);
            syncHideRecents(activity);

            // 「外观」：两个图片功能入口。
            //   聊天背景按钮 = 选一张图（PickFileActivity -> ImageStore -> PetPrefs.chatBackground）
            //   清除背景按钮 = 撤销上面那张图
            // 用的是原按钮实例，原生监听器原样保留，这里只统一外观。
            LinearLayout cardLook = HomeCards.buildCard(ctx, T_LOOK_NEW);
            LinearLayout lookBody = (LinearLayout) cardLook.getChildAt(1);
            if (bBg != null) {
                HomeCards.styleFeature(ctx, bBg);
                lookBody.addView(bBg);
            }
            if (bClear != null) {
                HomeCards.styleFeature(ctx, bClear);
                lookBody.addView(bClear);
            }
            // 主题模式：整行可点，右侧显示当前档位名；点击弹单选面板，选完立即重建界面。
            LinearLayout themeRow = HomeCards.valueRow(ctx, T_THEME_MODE, TAG_THEME_MODE);
            themeRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (v.getContext() instanceof Activity) {
                        pickThemeMode((Activity) v.getContext());
                    }
                }
            });
            lookBody.addView(themeRow);
            // 莫奈主题色：跟随壁纸主色派生整套配色；取不到壁纸时提示并自动关掉。
            UiKit.Switch monetSw = new UiKit.Switch(ctx);
            monetSw.setTag(TAG_THEME_MONET);
            monetSw.setOn(ThemeManager.monet(ctx), false);
            lookBody.addView(HomeCards.switchRow(ctx, T_THEME_MONET, monetSw,
                    new HomeCards.OnChanged() {
                        @Override
                        public void onChanged(boolean on, Context c) {
                            onMonetChanged(on, c);
                        }
                    }));
            // 「聊天背景」卡片正文（透明度说明 / 标签 / 滑条）并进「外观」。
            //   【修】这张卡片原来被整张 kill 掉，透明度调节就此消失；两个按钮已被摘走，
            //   剩下的正文整体搬过来，页面层级也更少一层。
            if (cardBg != null) {
                LinearLayout bgBody = (LinearLayout) cardBg.getChildAt(1);
                List<View> bgChildren = new ArrayList<View>();
                for (int i = 0; i < bgBody.getChildCount(); i++) {
                    bgChildren.add(bgBody.getChildAt(i));
                }
                for (int i = 0; i < bgChildren.size(); i++) {
                    View c = bgChildren.get(i);
                    bgBody.removeView(c);
                    // 顶部间距由「外观」卡片内既有行给出，这里抹掉原有的 10dp 上边距。
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                    clp.topMargin = 0;
                    lookBody.addView(c, clp);
                }
            }
            syncTheme(activity);

            // 「人偶」：目前为空位，后续加人偶时往这里塞。
            LinearLayout cardDoll = HomeCards.buildCard(ctx, T_DOLL_NEW);
            LinearLayout dollBody = (LinearLayout) cardDoll.getChildAt(1);
            HomeCards.addHint(ctx, dollBody, T_EMPTY_HINT);
            final Activity dollAct = activity;
            LinearLayout scaleRow = HomeCards.stepperRow(ctx, T_SCALE, TAG_DOLL_SCALE,
                    new Runnable() {
                        @Override
                        public void run() {
                            stepPetScale(dollAct, -1);
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            stepPetScale(dollAct, 1);
                        }
                    });
            dollBody.addView(scaleRow);
            HomeCards.setStepperValue(scaleRow, PetPrefs.petScaleDisplay(PetPrefs.petScale(activity)) + "%");
            HomeCards.addHint(ctx, dollBody, T_SCALE_HINT);

            box.removeAllViews();
            box.addView(header);
            box.addView(cardPerm);
            if (cardChat != null) {
                box.addView(cardChat);
            }
            box.addView(cardDoll);
            box.addView(cardLook);
            if (cardOp != null) {
                box.addView(cardOp);
            }
            // 「记忆」：自动总结开关 + 触发阈值 + 记忆库入口。
            // 开关与阈值是「上下文总结 / 压缩」的两个旋钮；记忆库是 AI 自主写下的长期记忆，独立于压缩。
            LinearLayout cardMem = HomeCards.buildCard(ctx, T_EMPTY[1]);
            LinearLayout memBody = (LinearLayout) cardMem.getChildAt(1);
            final Context memCtx = ctx;
            UiKit.Switch memSw = new UiKit.Switch(ctx);
            memSw.setTag(TAG_MEM_AUTO);
            memSw.setOn(PetPrefs.memAuto(ctx), false);
            memBody.addView(HomeCards.switchRow(ctx, T_MEM_AUTO, memSw,
                    new HomeCards.OnChanged() {
                        @Override
                        public void onChanged(boolean on, Context c) {
                            PetPrefs.setMemAuto(c, on);
                            syncMem(c);
                        }
                    }));
            final LinearLayout memBodyRef = memBody;
            LinearLayout thresholdRow = HomeCards.stepperRow(ctx, T_MEM_THRESHOLD, TAG_MEM_THRESHOLD,
                    new Runnable() {
                        @Override
                        public void run() {
                            PetPrefs.setMemThresholdIndex(memCtx, PetPrefs.memThresholdIndex(memCtx) - 1);
                            syncMem(memCtx);
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            PetPrefs.setMemThresholdIndex(memCtx, PetPrefs.memThresholdIndex(memCtx) + 1);
                            syncMem(memCtx);
                        }
                    });
            memBodyRef.addView(thresholdRow);
            Button memLib = HomeCards.mkButton(ctx, T_MEM_LIB, false);
            memLib.setTag(TAG_MEM_LIB);
            memLib.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MemPage.open(v.getContext());
                }
            });
            memBodyRef.addView(memLib);
            syncMem(activity);
            box.addView(cardMem);
            // 「关于」：原来是展开式卡片，v2.10.0 改成一行入口，点击进整页（AboutPage）。
            //   卡片外壳与标题「关于」保留不动 —— SettingsPage 按标题文本分组，改了会整体错位。
            LinearLayout cardAbout = HomeCards.buildCard(ctx, T_EMPTY[2], true);
            LinearLayout aboutBody = (LinearLayout) cardAbout.getChildAt(1);
            Button aboutEntry = HomeCards.mkButton(ctx, "关于本软件", false);
            aboutEntry.setTag(TAG_ABOUT_ENTRY);
            aboutEntry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AboutPage.open(v.getContext());
                }
            });
            aboutBody.addView(aboutEntry);
            box.addView(cardAbout);

            // 两页共用同一底色，切页不再跳色。
            sv.setBackgroundColor(UiKit.BG);
            sv.setTag(TAG_SET);

            // ---- 组装两页，替换掉原来的单页内容 ----
            // 关键：设置页那棵 sv 此刻仍挂在 content 上，直接 addView 到别的父容器会抛
            // IllegalStateException。必须先 detach，且摘掉首页四件套之后再执行，
            // 否则异常会被外层 catch 吞掉、界面停在「已摘控件但首页未接上」的半拆状态。
            content.removeAllViews();
            FrameLayout host = new FrameLayout(ctx);
            host.addView(homeScroll);
            host.addView(sv);
            content.addView(host);
            // 主题切换会重建界面：重建后回到切换前那一页。
            // 这两个标志不在这里清，交给 HomeUi.restoreScroll 统一消费——
            // 它既要判断回哪一页，也要据此决定滚哪个页面的位置。
            boolean toSettings = PetPrefs.themeOnSettings(activity);
            show(activity, !toSettings);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            // 呈现层调整，任何意外都不允许影响原有功能。
        }
    }

    /** 以后增加人偶时调用：把新的展示控件塞进首页的人偶容器。 */
    public static void addDoll(Activity activity, View doll) {
        View holder = find(activity, TAG_PETS);
        if (holder instanceof LinearLayout && doll != null) {
            ((LinearLayout) holder).addView(doll);
        }
    }

    /** 每次回到前台时调用：刷新首页按钮文案与「权限」卡片两行状态。 */
    public static void sync(Context ctx) {
        try {
            syncToggle(ctx);
            syncPerm(ctx);
            syncHideRecents(ctx);
            syncTheme(ctx);
            syncMem(ctx);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    // 刷新权限卡片两行状态：已授权 / 未授权；未授权时整行可点去授权。
    private static void syncPerm(Context ctx) {
        if (!(ctx instanceof Activity)) {
            return;
        }
        Activity act = (Activity) ctx;
        bindPermRow(find(act, HomeCards.TAG_PERM_OVERLAY), hasOverlay(ctx));
        bindPermRow(find(act, TAG_PERM_NOTIF), hasNotif(ctx));
        // 电池优化白名单：进了白名单才算「已授权」（isIgnoringBatteryOptimizations=true）。
        bindPermRow(find(act, TAG_PERM_BATTERY), ignoringBattery(ctx));
        // Shizuku 授权行：四态（已授权 / 未授权 / 服务未运行 / 未安装），只有「已授权」不可点。
        bindShizukuRow(find(act, TAG_PERM_SHIZUKU), ShizukuBridge.state(ctx));
        // 磁贴开关：系统没有查询接口，只能按 TileService 回调回写的值显示。
        View tileRow = find(act, TAG_TILE_SWITCH);
        if (tileRow instanceof UiKit.Switch) {
            ((UiKit.Switch) tileRow).setOn(PetPrefs.tileAdded(ctx), false);
        }
    }

    /**
     * 磁贴开关被拨动：开 = 请求系统把磁贴加进快捷面板；关 = 打开快捷面板让你长按移除。
     *
     * 【为什么「关」不能直接移除】Android 没有 requestRemoveTileService 这种 API，
     *   移除磁贴只能由用户在面板里长按拖走；能做的只有把面板打开、把话说明白。
     * 【为什么开关状态不在这里写死】系统会异步回 onTileAdded / onTileRemoved，
     *   由那两个回调回写 PetPrefs.tileAdded 才是最准的；这里只做乐观预置，失败再回滚。
     */
    static void onTileSwitchChanged(boolean on, Context ctx) {
        if (on) {
            boolean ok = requestAddTile(ctx);
            PetPrefs.setTileAdded(ctx, ok ? PetPrefs.tileAdded(ctx) : false);
            if (!ok) {
                openQuickSettings(ctx);
            }
        } else {
            PetPrefs.setTileAdded(ctx, false);
            openQuickSettings(ctx);
        }
    }

    /**
     * 请求系统把本应用的磁贴放进快捷面板（Android 13+ 才有这个接口）。
     * 低版本 / 失败返回 false，由调用方退回「打开面板手动添加」。
     */
    private static boolean requestAddTile(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        try {
            StatusBarManager sbm = (StatusBarManager) ctx.getSystemService("statusbar");
            if (sbm == null) {
                return false;
            }
            sbm.requestAddTileService(
                    new ComponentName(ctx, PetTileService.class),
                    ctx.getString(R.string.app_name),
                    Icon.createWithResource(ctx, R.drawable.ic_tile_pet),
                    ctx.getMainExecutor(),
                    // 这个回调系统要求非 null：传 null 会在系统回结果时 NPE，必须给空实现。
                    new java.util.function.Consumer<Integer>() {
                        @Override
                        public void accept(Integer code) {
                        }
                    });
            return true;
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    /** 打开快捷设置面板（用户在那里长按磁贴可移除或拖动排序）。 */
    private static void openQuickSettings(Context ctx) {
        try {
            Intent i = new Intent("android.settings.QUICK_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /**
     * 「开关指令」行被点：把 dollhouse://pet/toggle 复制进系统剪贴板。
     * 反馈用本行右侧文字变化（复制 ›  →  已复制 ✓），符合「不许用浮层短提示」的硬约束。
     */
    static void copyToggleLink(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("Dollhouse", T_CMD_LINK));
            if (ctx instanceof Activity) {
                View row = find((Activity) ctx, TAG_CMD_ROW);
                if (row != null) {
                    HomeCards.setRowValue(row, T_CMD_COPIED);
                }
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    // 是否已加入电池优化白名单（无需权限即可查询）。查询异常一律当作「未加入」。
    private static boolean ignoringBattery(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;
            }
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    /**
     * 权限行点击分发（由 HomeCards.permRow 统一回调）：按 tag 决定去哪申请。
     * 未知 tag 一律不动作。
     */
    static void permClicked(String tag, Context ctx) {
        if (HomeCards.TAG_PERM_OVERLAY.equals(tag)) {
            requestOverlay(ctx);
        } else if (TAG_PERM_NOTIF.equals(tag)) {
            requestNotif(ctx);
        } else if (TAG_PERM_BATTERY.equals(tag)) {
            requestIgnoreBattery(ctx);
        } else if (TAG_PERM_SHIZUKU.equals(tag)) {
            shizukuClicked(ctx);
        }
    }

    /**
     * Shizuku 授权行点击：按四态分别落地。
     * 已授权时该行不可点（bindShizukuRow 已关掉 clickable），走不到这里。
     */
    static void shizukuClicked(Context ctx) {
        switch (ShizukuBridge.state(ctx)) {
            case ShizukuBridge.S_NOT_INSTALLED:
                // 没装管理器：没有可跳转的目标，只能用自绘弹窗把话说清楚（禁止 Toast/Snackbar）。
                showShizukuMissing(ctx);
                break;
            case ShizukuBridge.S_NOT_RUNNING:
                // 管理器在但服务没跑：拉起管理器，用户在里面启动服务。
                // 【兜底】拿不到启动 Intent 时不能「点了没反应」——给一句说明（禁止 Toast）。
                if (!ShizukuBridge.openManager(ctx)) {
                    showShizukuDialog(ctx, "\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                            + "\u8bf7\u624b\u52a8\u6253\u5f00\u5b83\u5e76\u542f\u52a8\u670d\u52a1\uff0c\u518d\u56de\u5230\u8fd9\u91cc\u3002");
                }
                break;
            default:
                // 【老版兼容】server < v11 的 Shizuku 没有「运行时授权」这套机制，
                //   requestPermission 在老版上不弹框、不会有结果回调 —— 点了等于没反应。
                //   老版的做法是：在管理器界面里手动勾选本应用（勾上即视为已授权）。
                if (ShizukuBridge.needManagerForGrant()) {
                    if (!ShizukuBridge.openManager(ctx)) {
                        showShizukuDialog(ctx, "\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                                + "\u4f60\u7684 Shizuku \u7248\u672c\u8f83\u65e7\uff0c\u9700\u8981\u5728\u7ba1\u7406\u5668\u91cc\u624b\u52a8\u52fe\u9009\u672c\u5e94\u7528\u3002");
                    } else {
                        showShizukuDialog(ctx, "\u4f60\u7684 Shizuku \u7248\u672c\u8f83\u65e7\u3002\n\n"
                                + "\u8bf7\u5728\u7ba1\u7406\u5668\u91cc\u628a\u672c\u5e94\u7528\u52fe\u9009\u4e3a\u300c\u5df2\u6388\u6743\u300d\uff0c"
                                + "\u7136\u540e\u56de\u5230\u8fd9\u91cc\u3002");
                    }
                    break;
                }
                // 未授权：由 server 弹系统确认框，结果经 listener 回来再刷新这一行。
                ShizukuBridge.requestPermission(REQ_SHIZUKU);
                break;
        }
    }

    /** 「未安装 Shizuku 管理器」说明弹窗：无 Toast 约束下的唯一合法反馈通道。 */
    private static void showShizukuMissing(Context ctx) {
        showShizukuDialog(ctx, "\u672a\u68c0\u6d4b\u5230 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                + "\u5148\u5b89\u88c5 Shizuku \u5e76\u542f\u52a8\u5176\u670d\u52a1\uff08\u9700\u914d\u5408 adb / \u65e0\u7ebf\u8c03\u8bd5\uff09\uff0c"
                + "\u518d\u56de\u5230\u8fd9\u91cc\u6388\u6743\u3002\u6388\u6743\u540e\u672c\u5e94\u7528\u4e0e AI \u90fd\u80fd\u4f7f\u7528\u7cfb\u7edf\u7ea7\u80fd\u529b\u3002");
    }

    /** Shizuku 授权行的通用说明弹窗（无 Toast 约束下的唯一合法反馈通道）。 */
    private static void showShizukuDialog(Context ctx, String message) {
        Activity act = UiKit.findActivity(ctx);
        if (act == null) {
            return;
        }
        UiKit.showDialog(act, T_PERM_SHIZUKU_NAME,
                UiKit.dialogMessage(act, message), "\u77e5\u9053\u4e86", null, null, null);
    }

    /**
     * 保活引导行点击分发（由 HomeCards.guideRow 回调）：只能拉起系统页面，由用户手动开。
     * 硬约束：绝不使用 am start 抢用户前台，全部走标准 Settings Intent。
     */
    static void guideClicked(String tag, Context ctx) {
        // 【v2.9.6】按 tag 精确分发到各自的详细页面（用户要求「跳转到详细位置一点」）。
        //  每一支都先试「直达页」，失败再退到应用详情页 —— 各厂商 ROM 改过组件名是常态，
        //  硬编码直达页不能作为唯一路径，否则在别家机器上会点了没反应。
        if (TAG_GUIDE_BG_POWER.equals(tag)) {
            // 后台耗电管理：ColorOS 冻结后台应用（OplusHansManager freeze）的开关页。
            //  本 App 「记忆总结中卡住」的根因就在这一页（本机实测该组件可 resolve）。
            if (!openComponent(ctx, "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerAppsBgSetting")) {
                requestAppDetails(ctx);
            }
        } else {
            // 自启动 / 后台活动：ColorOS 上无公开组件（已全机扫描确认无 Startup*Activity），
            //  只能退到本应用详情页，用户在那里完成设置。
            requestAppDetails(ctx);
        }
    }

    /**
     * 跳系统「电池优化」列表：SDK>=23 带包名直达本应用的豁免页；
     * 厂商改过该页会失败，退回不带包名的不受限列表。任何失败都静默吞掉。
     */
    static void requestIgnoreBattery(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
                i.setData(Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        try {
            Intent i = new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        // 【v2.9.6】两层都失败时兜底到应用详情页，绝不静默什么都不发生。
        requestAppDetails(ctx);
    }

    /** 跳本应用详情页：自启动 / 后台活动 / 后台弹出界面这类私有开关都从此页进。 */
    static void requestAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /**
     * 【v2.9.6】尝试打开指定组件；组件不存在或权限不足时返回 false（不抛）。
     * 用 ComponentName 显式指定，比 action 更精确：ColorOS 上同一设置项常被包一层
     * 自定义壳，用 action 会落到笼统的列表页，用户还得自己再找一层。
     */
    private static boolean openComponent(Context ctx, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    /** 【v2.9.6】打开本应用在系统设置里的指定页；失败返回 false。 */
    private static boolean openAppSetting(Context ctx, String cls) {
        return openComponent(ctx, "com.android.settings", cls);
    }

    /**
     * 权限行按状态双向绑定：
     * 已授权 —— 绿字「已授权」、不可点；
     * 未授权 —— 红字「未授权」、整行可点（点行触发行内存的 onClick）。
     */
    private static void bindPermRow(View row, boolean granted) {
        if (!(row instanceof LinearLayout)) {
            return;
        }
        LinearLayout r = (LinearLayout) row;
        if (r.getChildCount() < 2) {
            return;
        }
        View last = r.getChildAt(1);
        if (last instanceof TextView) {
            ((TextView) last).setText(granted ? HomeCards.S_OK : HomeCards.S_NO);
            UiKit.setTextColorAnimated((TextView) last, granted ? UiKit.OK : UiKit.ERR);
        }
        r.setClickable(!granted);
    }

    /**
     * Shizuku 授权行按四态绑定：右侧文案由 ShizukuBridge 给（已授权 / 未授权 / 服务未运行 / 未安装）；
     * 只有「已授权」是绿字不可点，其余三态都红字可点（分别去授权 / 去启动服务 / 去装管理器）。
     */
    private static void bindShizukuRow(View row, int state) {
        if (!(row instanceof LinearLayout)) {
            return;
        }
        LinearLayout r = (LinearLayout) row;
        if (r.getChildCount() < 2) {
            return;
        }
        View last = r.getChildAt(1);
        boolean granted = state == ShizukuBridge.S_GRANTED;
        if (last instanceof TextView) {
            ((TextView) last).setText(ShizukuBridge.stateText(state));
            UiKit.setTextColorAnimated((TextView) last, granted ? UiKit.OK : UiKit.ERR);
        }
        r.setClickable(!granted);
    }

    // 通知是否可用：API>=33 查运行时权限，24~32 查 areNotificationsEnabled（API 24 起有）。
    private static boolean hasNotif(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    // SDK>=23 走 Settings.canDrawOverlays；低版本一律视为已授予。
    private static boolean hasOverlay(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return Settings.canDrawOverlays(ctx);
            }
            return true;
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    // 刷新首页那颗「启动人偶 / 关闭人偶」按钮的文案。
    private static void syncToggle(Context ctx) {
        running = isServiceRunning(ctx);
        if (!(ctx instanceof Activity)) {
            return;
        }
        View t = find((Activity) ctx, TAG_TOGGLE);
        if (t instanceof Button) {
            ((Button) t).setText(running ? "\u5173\u95ed\u4eba\u5076" : "\u542f\u52a8\u4eba\u5076");
        }
    }

    // 用 ActivityManager 代查 PetService 是否活着；查不到时沿用上次结果。
    private static boolean isServiceRunning(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return running;
            }
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                String cn = info.service == null ? null : info.service.getClassName();
                if (PetService.class.getName().equals(cn)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        return false;
    }

    /** 返回键：设置页 -> 首页。返回 true 表示这次返回已被消费。 */
    public static boolean handleBack(Activity activity) {
        try {
            View settings = find(activity, TAG_SET);
            View home = find(activity, TAG_HOME);
            if (settings == null || home == null || settings.getVisibility() != View.VISIBLE) {
                return false;
            }
            // 与前进路径保持同一种过渡：交叉淡入，而非硬切。
            showDiff(home.getParent() instanceof ViewGroup ? (ViewGroup) home.getParent() : null,
                    home, settings);
            return true;
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            return false;
        }
    }

    /* ------------------------------ 设置页构件 ------------------------------ */
    /**
     * 刷新「记忆」卡片：开关状态 + 阈值档位值。
     * 【坑】步进器的中间文本每档显示的是「条数」，但点击改的是档位下标，
     *       两者必须都走 PetPrefs 的同一份数据，否则重进页面会回跳。
     */
    private static void syncMem(Context ctx) {
        try {
            if (!(ctx instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) ctx;
            View sw = find(activity, TAG_MEM_AUTO);
            if (sw instanceof UiKit.Switch) {
                ((UiKit.Switch) sw).setOn(PetPrefs.memAuto(ctx), false);
            }
            View row = find(activity, TAG_MEM_THRESHOLD);
            if (row != null) {
                int idx = PetPrefs.memThresholdIndex(ctx);
                HomeCards.setStepperValue(row, PetPrefs.MEM_THRESHOLDS[idx] + " \u6761");
                View minus = row.findViewWithTag(TAG_MEM_THRESHOLD + "_minus");
                if (minus != null) {
                    minus.setEnabled(idx > 0);
                    minus.setAlpha(idx > 0 ? 1.0f : 0.35f);
                }
                View plus = row.findViewWithTag(TAG_MEM_THRESHOLD + "_plus");
                if (plus != null) {
                    int last = PetPrefs.MEM_THRESHOLDS.length - 1;
                    plus.setEnabled(idx < last);
                    plus.setAlpha(idx < last ? 1.0f : 0.35f);
                }
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }
    /**
     * 开关落地：先写偏好，再把本应用在「最近任务」里的任务卡片刻成排除 / 恢复。
     * AppTask 只作用于本应用自己的任务，无需额外运行时权限；任何异常都静默吞掉，
     * 不能让一个可选装饰性开关影响主功能。切换后需下次进入前台才完全生效 —— 这是
     * 系统 recents 缓存的固有行为，不是缺陷。
     */
    static void onHideRecentsChanged(boolean hide, Context ctx) {
        PetPrefs.setHideRecents(ctx, hide);
        applyHideRecents(ctx);
    }

    private static void applyHideRecents(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || Build.VERSION.SDK_INT < 21) {
                return;
            }
            boolean hide = PetPrefs.hideRecents(ctx);
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                task.setExcludeFromRecents(hide);
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /** 每次回到前台时按偏好重新落一次（系统可能已重建任务记录）。 */
    private static void syncHideRecents(Context ctx) {
        View row = null;
        if (ctx instanceof Activity) {
            row = find((Activity) ctx, TAG_HIDE_RECENTS);
        }
        if (row instanceof UiKit.Switch) {
            ((UiKit.Switch) row).setOn(PetPrefs.hideRecents(ctx), false);
        }
        applyHideRecents(ctx);
    }
    // 刷新「外观」卡片里的主题行：主题模式显示当前档位名，莫奈开关按偏好回填。
    private static void syncTheme(Context ctx) {
        if (!(ctx instanceof Activity)) {
            return;
        }
        Activity act = (Activity) ctx;
        View modeRow = find(act, TAG_THEME_MODE);
        if (modeRow != null) {
            HomeCards.setRowValue(modeRow, ThemeManager.MODE_NAMES[ThemeManager.mode(ctx)]);
        }
        View monetRow = find(act, TAG_THEME_MONET);
        if (monetRow instanceof UiKit.Switch) {
            ((UiKit.Switch) monetRow).setOn(ThemeManager.monet(ctx), false);
        }
    }
    // 主题模式选择面板：四档单选，选完写偏好并立即重建当前界面。
    private static void pickThemeMode(final Activity act) {
        if (act == null) {
            return;
        }
        final int cur = ThemeManager.mode(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < ThemeManager.MODE_NAMES.length; i++) {
            col.addView(modeOption(act, i, i == cur, cur, holder));
        }
        holder[0] = UiKit.showDialog(act, T_THEME_MODE, col, null, null, null, null);
    }

    // 单个主题模式选项：选中项淡紫底 + 加粗 + 右侧勾，点选后关窗并立即应用。
    private static View modeOption(final Activity act, final int idx, boolean selected,
                                   final int cur, final Dialog[] holder) {
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setBackground(UiKit.rowBg(act, selected ? UiKit.OPTION : UiKit.SOFT));
        int pad = UiKit.dp(act, 12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(act, 8);
        row.setLayoutParams(lp);

        TextView t = new TextView(act);
        t.setText(ThemeManager.MODE_NAMES[idx]);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // 主题选中态：描边对勾图标（无选中则占位保持行高一致）。
        View mark = selected
                ? Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
                : new View(act);
        row.addView(mark, new LinearLayout.LayoutParams(UiKit.dp(act, 22), -2));

        UiKit.press(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (idx == cur) {
                    return;
                }
                ThemeManager.setMode(act, idx);
                applyThemeNow(act);
            }
        });
        return row;
    }
    // 莫奈主题色开关：先在后台取色，拿到颜色再写偏好并重建界面。
    // 取色绝不能在主线程做（壁纸是整屏大图），否则切开关会卡住界面。
    private static void onMonetChanged(boolean on, Context ctx) {
        if (!on) {
            ThemeManager.setMonet(ctx, false);
            if (ctx instanceof Activity) {
                applyThemeNow((Activity) ctx);
            }
            return;
        }
        // 打开：先看缓存，有就立刻生效；没有就后台解一次，回来再生效。
        if (ThemeManager.hasMonetColor(ctx)) {
            ThemeManager.setMonet(ctx, true);
            if (ctx instanceof Activity) {
                applyThemeNow((Activity) ctx);
            }
            return;
        }
        final Context app = ctx.getApplicationContext();
        final Activity act = ctx instanceof Activity ? (Activity) ctx : null;
        ThemeManager.monetHueAsync(ctx, new ThemeManager.HueCallback() {
            @Override
            public void onHue(boolean ok) {
                if (!ok) {
                    ThemeManager.setMonet(app, false);
                    syncTheme(app);
                    return;
                }
                ThemeManager.setMonet(app, true);
                if (act != null) {
                    applyThemeNow(act);
                }
            }
        });
    }
    // 主题改动后立即生效：刷 UiKit 配色 -> 通知桌宠重读 -> 重建当前界面。
    private static void applyThemeNow(Activity act) {
        if (act == null) {
            return;
        }
        ThemeManager.apply(act);
        notifyPetRefresh(act);
        // 记住当前停在哪一页 + 滚到哪：recreate 会重跑 onCreate，
        // 不记的话会被弹回首页、滚动位置也会丢。
        boolean toSettings = !isHomeVisible(act);
        PetPrefs.setThemeOnSettings(act, toSettings);
        saveScroll(act, toSettings);
        // 一次性标志：只有「换主题触发的重建」才还原滚动位置，冷启动不还原。
        PetPrefs.setThemeRestore(act, true);
        try {
            act.recreate();
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    // 记下当前页的滚动位置（首页 / 设置页各记一份）。
    private static void saveScroll(Activity act, boolean settingsPage) {
        View page = find(act, settingsPage ? TAG_SET : TAG_HOME);
        if (page instanceof ScrollView) {
            int y = ((ScrollView) page).getScrollY();
            PetPrefs.setScrollY(act, settingsPage ? KEY_SET : KEY_HOME, y);
        }
    }

    // 换主题重建后把滚动位置滚回去（消费一次性标志，冷启动不受影响）。
    static void restoreScroll(Activity act) {
        try {
            if (!PetPrefs.themeRestore(act)) {
                return;
            }
            // 消费两个一次性标志：换主题重建才还原，冷启动不受影响。
            PetPrefs.setThemeRestore(act, false);
            boolean settingsPage = PetPrefs.themeOnSettings(act);
            PetPrefs.setThemeOnSettings(act, false);
            View page = find(act, settingsPage ? TAG_SET : TAG_HOME);
            if (!(page instanceof ScrollView)) {
                return;
            }
            final ScrollView sv = (ScrollView) page;
            final int y = PetPrefs.scrollY(act, settingsPage ? KEY_SET : KEY_HOME);
            if (y <= 0) {
                return;
            }
            // 布局还没量完时 scrollTo 会被后续 measure 覆盖。先试一次，
            // 没生效就再等一拍重试——否则位置会掉回顶部。
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.scrollTo(0, y);
                    if (sv.getScrollY() != y) {
                        sv.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                sv.scrollTo(0, y);
                            }
                        }, 80L);
                    }
                }
            });
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }
    /** 调整人偶比例：delta 为 -1 / +1，每档 10%（内部与显示同步走 10 档）。 */
    private static void stepPetScale(Activity act, int delta) {
        int cur = PetPrefs.petScale(act);
        PetPrefs.setPetScale(act, cur + (delta * PetPrefs.PET_SCALE_STEP));
        syncDollScale(act);
        notifyPetRefresh(act);
    }

    /** 把当前比例刷进步进器显示（界面已装配时才找得到控件）。 */
    private static void syncDollScale(Activity act) {
        View row = find(act, TAG_DOLL_SCALE);
        if (row != null) {
            HomeCards.setStepperValue(row, PetPrefs.petScaleDisplay(PetPrefs.petScale(act)) + "%");
        }
    }

    // 当前是否停在首页（首页可见即算首页）。
    private static boolean isHomeVisible(Activity act) {
        View home = find(act, TAG_HOME);
        return home != null && home.getVisibility() == View.VISIBLE;
    }
    // 主题切换后让常驻桌宠重读颜色（服务没在跑时静默忽略）。
    private static void notifyPetRefresh(Context ctx) {
        try {
            if (!isServiceRunning(ctx)) {
                return;
            }
            Intent i = new Intent(ctx, PetService.class);
            i.setAction(PetService.ACTION_REFRESH);
            ctx.startService(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    // 去系统设置开悬浮窗：SDK>=23 可带 package 直接定位到本应用那一项。
    // 厂商可能改过这个页面，带包名失败就退回不带包名的通用页。
    static void requestOverlay(Context ctx) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 23) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored2) {
                Logs.w(LOG_TAG, "ignored", ignored2);
            }
        }
    }

    // 通知权限：33+ 走标准运行时申请；24~32 没有这个运行时权限，只能跳到通知设置页。
    static void requestNotif(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                return;
            }
            // 【v2.9.6】先直达本应用的通知详情页（进去就是本 App 的开关列表）；
            //  失败再退回通用 action 形式。
            if (openAppSetting(ctx, "com.android.settings.Settings$AppNotificationSettingsActivity")) {
                return;
            }
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    // 首页 / 设置页二选一切换：只改可见性 + 淡入位移，不重建任何视图。
    static void show(Activity activity, boolean home) {
        try {
            View settings = find(activity, TAG_SET);
            View homeView = find(activity, TAG_HOME);
            if (settings == null || homeView == null) {
                return;
            }
            if (PetPrefs.themeRestore(activity)) {
                // 换主题重建：直接落到目标页，不重播动画，免得又闪一次。
                homeView.setVisibility(home ? View.VISIBLE : View.GONE);
                settings.setVisibility(home ? View.GONE : View.VISIBLE);
                return;
            }
            // 切页：目标页淡入、旧页同步淡出（两页同宿主，必须交叉，否则会糊在一起）。
            showDiff(homeView.getParent() instanceof ViewGroup
                    ? (ViewGroup) homeView.getParent() : null,
                    home ? homeView : settings, home ? settings : homeView);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /**
     * 同一宿主内的「两页二选一」：目标页淡入 + 旧页同步淡出。
     * 【为何不用 UiKit.enter】enter 只管入场；旧页若仍可见，两页会同时叠着糊一下。
     */
    private static void showDiff(final ViewGroup host, final View in, final View out) {
        if (host == null || in == null || out == null) {
            return;
        }
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        out.setAlpha(1f);
        in.animate().alpha(1f).setDuration(UiKit.D_LAYER).setInterpolator(UiKit.EASE_DECEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (out.getAlpha() < 0.05f) {
                            out.setVisibility(View.GONE);
                        }
                    }
                }).start();
        out.animate().alpha(0f).setDuration(UiKit.D_MICRO).setInterpolator(UiKit.EASE_ACCEL).start();
    }
    // 按 tag 在 decorView 里找控件（本轮改造的通用定位手段）。
    private static View find(Activity activity, String tag) {
        View decor = activity == null || activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return null;
        }
        return ((ViewGroup) decor).findViewWithTag(tag);
    }

    /**
     * 按 tag 在整棵视图树里找一个 Button。
     * 【为什么不用 HomeCards.cardTitle 那套顶层遍历】这两个按钮会被 SettingsPage 收进卡片 body，
     *   已不在顶层；findViewWithTag 是深度递归，且只有 Button 会被打上这两个 tag，
     *   不会误抓到同名的卡片标题 TextView。
     */
    private static Button findButtonByTag(Activity activity, String tag) {
        View v = find(activity, tag);
        return v instanceof Button ? (Button) v : null;
    }

    /** 按控件自己的父容器摘除（box.removeView 只对直接子有效，对深层子节点是空操作）。 */
    private static void detachFromParent(View v) {
        if (v == null) {
            return;
        }
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        }
    }

    // 深度优先找第一个 ScrollView：首页整页都挂在它下面。
    private static ScrollView findScrollView(View v) {
        if (v == null) {
            return null;
        }
        if (v instanceof ScrollView) {
            return (ScrollView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView r = findScrollView(g.getChildAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

}
