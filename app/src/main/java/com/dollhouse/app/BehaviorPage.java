package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 【职责】设置页「对话行为」独立页：思考程度 / 联网搜索 / 自动保存记忆 / 记忆库入口。
 *
 * 【为什么从旧页搬出来】这些开关原本混在「提供商」的第三个页签里，与供应商概念毫无关系；
 *        拆出来之后「提供商」页只管供应商与模型，行为开关各归其位。
 *
 * 【口径】三项开关的读写接口一个都没变（PetPrefs 的 thinkLevel / webSearchEnabled / memAutoSave），
 *        所以聊天侧、记忆侧的既有调用点全部零改动。
 */
final class BehaviorPage {

    private BehaviorPage() {
    }

    static View build(final Activity act) {
        final Context ctx = act;
        LinearLayout root = ApiPageKit.pageRoot(ctx);
        root.addView(UiKit.topBar(ctx, "对话行为", "思考程度与开关", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.handleBack(ctx);
            }
        }));
        final LinearLayout host = ApiPageKit.contentHost(ctx);
        paint(ctx, host);
        root.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));
        return root;
    }

    /**
     * 把设备控制能力放进对话行为页：与小肥鱼的能力开关放在一处，用户不用记两个地方。
     * 【为什么放这里】lamda 服务是否开着，与「联网搜索」一样属于「她能做什么」的一类；
     *   安装 / 启停这些动手的活仍在 lamda 子页，这里只放一个入口行 + 一句状态说明。
     */
    private static void addDevice(final Context ctx, final LinearLayout host) {
        LinearLayout db = ApiPageKit.card(ctx);
        db.addView(ApiPageKit.sectionTitle(ctx, "设备操作"));
        db.addView(ApiPageKit.row(ctx, "lamda 设备控制", "\u203a", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 【为什么用 push 不用 openAt】从本页进去要能原路退回本页，而不是一路退回设置页。
                android.app.Activity act = ApiPageKit.findActivity(ctx);
                if (act != null) {
                    ProviderNav.push(act, ProviderNav.R_LAMDA);
                } else {
                    ProviderNav.openAt(ctx, ProviderNav.R_LAMDA);
                }
            }
        }));
        db.addView(ApiPageKit.note(ctx, "开启后小肥鱼能点按、滑动、打开应用、读取界面。"
                + "需要 Shizuku 授权，并在 lamda 页把服务启动起来，没启动则本能力不下发。"
                + "页内可打开「自动保活」，服务掉了会自动拉回来。"));
        host.addView(db);
    }

    /** 整页重画：思考程度点了要立刻换高亮，重建比局部改样式稳妥。 */
    private static void paint(final Context ctx, final LinearLayout host) {
        host.removeAllViews();

        LinearLayout box = ApiPageKit.card(ctx);
        box.addView(ApiPageKit.sectionTitle(ctx, "对话行为"));
        TextView tl = new TextView(ctx);
        tl.setText("思考程度");
        tl.setTextSize(UiKit.FS_BTN);
        tl.setTextColor(UiKit.TITLE);
        tl.setTypeface(Typeface.DEFAULT_BOLD);
        tl.setPadding(0, ApiPageKit.dp(ctx, 12), 0, ApiPageKit.dp(ctx, 8));
        box.addView(tl);
        for (int row = 0; row < (PetPrefs.THINK_NAMES.length + 2) / 3; row++) {
            LinearLayout rl = new LinearLayout(ctx);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int idx = row * 3 + c;
                if (idx >= PetPrefs.THINK_NAMES.length) {
                    break;
                }
                TextView chip = UiKit.outlineChip(ctx, PetPrefs.THINK_NAMES[idx]);
                boolean on = idx == PetPrefs.thinkLevel(ctx);
                if (on) {
                    chip.setTextColor(UiKit.ON_ACC);
                    UiKit.primary(chip, ctx);
                }
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PetPrefs.setThinkLevel(ctx, idx);
                        paint(ctx, host);
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                if (c < 2) {
                    clp.rightMargin = ApiPageKit.dp(ctx, 6);
                }
                rl.addView(chip, clp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = row == 0 ? 0 : ApiPageKit.dp(ctx, 6);
            box.addView(rl, rlp);
        }
        box.addView(ApiPageKit.note(ctx, "只对支持 reasoning_effort 的服务端真正生效，其余服务端靠提示词兜底。"));
        final UiKit.Switch web = ModelEditKit.switchRow(ctx, box, "联网搜索",
                "遇到时效性问题先联网查一下再回答", PetPrefs.webSearchEnabled(ctx));
        web.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !web.isOn();
                web.setOn(now, true);
                PetPrefs.setWebSearchEnabled(ctx, now);
            }
        });
        final UiKit.Switch mem = ModelEditKit.switchRow(ctx, box, "自动保存记忆",
                "聊完自动把值得记的内容写进记忆库", PetPrefs.memAutoSave(ctx));
        mem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !mem.isOn();
                mem.setOn(now, true);
                PetPrefs.setMemAutoSave(ctx, now);
            }
        });
        host.addView(box);

        // —— 设备操作入口 ——
        addDevice(ctx, host);

        // —— 记忆库入口 ——
        LinearLayout mb = ApiPageKit.card(ctx);
        mb.addView(ApiPageKit.sectionTitle(ctx, "记忆"));
        mb.addView(ApiPageKit.row(ctx, "查看记忆库", "\u203a", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.closeIfOpen(ctx);
                MemPage.open(ctx);
            }
        }));
        mb.addView(ApiPageKit.note(ctx, "记忆库单独一页，可逐条查看与删除。"));
        host.addView(mb);
    }
}