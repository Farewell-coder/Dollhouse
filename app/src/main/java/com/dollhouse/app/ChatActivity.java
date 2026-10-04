package com.dollhouse.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * 【职责】聊天页面的宿主 Activity（独立窗口，可从桌面图标或桌宠点进来）。
 *
 * 【交互】只负责建一个 ChatPanel 并把它铺满内容视图；所有对话逻辑都在 ChatPanel 里。
 *
 * 【坑】桌宠场景下用的是常驻浮窗（ChatWindow），不是这个 Activity；两条路径共用 ChatPanel。
 *       悬浮窗里点顶栏的上下文环时，由 ChatWindow 带着 extra 跳到这里再开统计页——
 *       因为悬浮窗的 Context 链里没有 Activity，TokenStat.open 找不到宿主。
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
            public void onOpenFullScreen() {
            }

            @Override
            public void onOpenTokenStat() {
                TokenStat.open(ChatActivity.this);
            }

            @Override
            public void onClose() {
                ChatActivity.this.finish();
            }
        }, true);
        this.panel = chatPanel;
        setContentView(chatPanel);
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
        }
    }
}