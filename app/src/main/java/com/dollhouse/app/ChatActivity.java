package com.dollhouse.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * 【职责】聊天页面的宿主 Activity（独立窗口，可从桌面图标或桌宠点进来）。
 *
 * 【交互】只负责建一个 ChatPanel 并把它铺满内容视图；所有对话逻辑都在 ChatPanel 里。
 *
 * 【坑】桌宠侧走的是迷你输入框（PetTalkInput）+ 气泡，另有一条独立的全屏页路径；
 *       两条路径共用 ChatPanel。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class ChatActivity extends Activity {
    /** 进本页后要顺带打开的页面标识（目前只有「令牌消耗统计」）。 */
    static final String EXTRA_PAGE = "page";
    static final String PAGE_TOKEN = "token_stat";
    private ChatPanel panel;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // 先按当前主题档位把配色刷进 UiKit，再建界面。
        ThemeManager.apply(this);
        ChatPanel chatPanel = new ChatPanel(this, new ChatPanel.Controller() {
            @Override
            public void onDrag(float f, float f2) {
            }

            @Override
            public void onOpenTokenStat() {
                TokenStat.open(ChatActivity.this);
            }

            @Override
            public void onClose() {
                ChatActivity.this.finish();
                // 【丝滑】关闭全屏聊天页时淡出，不用系统默认的硬切。
                ChatActivity.this.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }, true);
        this.panel = chatPanel;
        setContentView(chatPanel);
        // 【状态栏嵌入】聊天页同样铺满 + 状态栏透明；ChatPanel 顶栏自己让出高度。
        UiKit.applyEdgeToEdge(this);
        this.panel.refreshHint();
        // 从悬浮窗带 extra 跳进来时，落地就顺手把统计页叠上（延后一拍，等内容视图量完）。
        if (PAGE_TOKEN.equals(pageExtra())) {
            chatPanel.post(new Runnable() {
                @Override
                public void run() {
                    TokenStat.open(ChatActivity.this);
                }
            });
        }
    }

    /** 本次启动要顺带打开的页面标识；没有就返回 null。 */
    private String pageExtra() {
        try {
            return getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PAGE);
        } catch (Throwable unused) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatPanel chatPanel = this.panel;
        if (chatPanel != null) {
            chatPanel.refreshHint();
            // 【修·聊天背景不生效】用户去设置页选完背景图再回来时，本 Activity 可能没销毁、
            //   直接复用旧的 ChatPanel（singleTop），只在构造时调过一次 applyBackground()，
            //   于是「选完图聊天页背景没变化」。每次回前台重套一次即可（幂等）。
            chatPanel.applyBackground();
        }
    }
    /**
     * 【修·返回键】抽屉开着时先关抽屉，而不是直接退出聊天页。
     *   原实现没有这个方法，ChatPanel.closeDrawerIfOpen() 全工程零调用点 ——
     *   左侧抽屉一打开，按返回键就整个退出去，只能点遮罩关。
     *   与 MainActivity 的处理顺序保持一致：先让页面内的浮层吃掉返回键，再交给系统。
     */
    @Override
    public void onBackPressed() {
        if (this.panel != null && this.panel.closeDrawerIfOpen()) {
            return;
        }
        super.onBackPressed();
    }
}