package com.dollhouse.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】聊天气泡面板本体：消息列表、输入行、附件条、AI 请求与流式回填。
 *
 * 【交互】对外通过 Controller 回调把「拖动 / 关闭」交给宿主（ChatActivity 或 ChatWindow）；模型请求走 DeepSeekClient，联网工具走 WebSearch，图片附件走 ImageStore。
 *
 * 【坑】history 是整段对话上下文，每次请求都会带上；MAX_TOOL_ROUNDS 限制联网工具的最大轮次，防止 AI 反复查资料不收敛。改这里要小心 token 统计（TokenStat）与好感度解析的联动。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class ChatPanel extends LinearLayout implements PickFileActivity.Listener {
    // 【交互】气泡构造已拆到 ChatBubbles；messages / scroller / history 因此改为包级可见。
    private static final int MAX_TOOL_ROUNDS = 3;
    private TextView attachLabel;
    private LinearLayout attachStrip;
    private ImageView attachThumb;
    private boolean browsingArchives;
    private final ChatPanel.Controller controller;
    private TextView hint;
    JSONArray history;
    private EditText input;
    private LinearLayout inputRow;
    LinearLayout messages;
    private String pendingImage;
    private String pendingText;
    private String pendingTextName;
    boolean requestHasImage;
    ScrollView scroller;
    private ImageView sendBtn;
    /** 【v2.8】工具条右侧的「记忆总结中」：仅在总结在途时可见，结束后消失。 */
    private TextView memoBusy;
    /** 【v2.8】聊天记录里「ⓘ 历史对话摘要」那条节点，总结完成后据此滚过去。 */
    private View summaryNode;
    /** 【v2.8】最近一次补回的「更早历史」条数；0 = 当前不是展开态。 */
    int prevCount;
    /** 【v2.8】展开态下补回批的最后一个节点：它滑出视口上沿就收回去。 */
    private View prevTailView;
    /** 【v2.8】收回过程中的重入闸：程序化滚动同样会触发滚动回调。 */
    private boolean collapsing;
    /** 【v2.8】补回「更早的历史」时的一次性重铺：期间不自动滚底，否则展开态会被判据二立刻收回。 */
    boolean holdScroll;
    /** 【v2.8】在途的「滚到摘要分割线」预绘制回调：重铺前必须摘掉，否则会落到已移除的节点上。 */
    private android.view.ViewTreeObserver.OnPreDrawListener pendingScroll;
    /** 【v2.8】「记忆总结中」的期望状态：工具条被归档页整体隐藏时用它重放。 */
    private boolean memoBusyOn;
    /** 【v2.9.2】失败/中止提示显示中：期间 setMemoBusy(false) 不得隐藏它。 */
    private boolean flashHold;
    /** 【v2.9.2】在途的 flash 收起计时器：新 flash 前先撤旧的，避免互踩。 */
    private Runnable pendingFlash;
    /** 输入行上方的工具条：模型配置 / 思考程度 / 记忆。 */
    private LinearLayout toolRow;
    /** 【三件套】思考参数被服务端拒（参数类 400）后置位：只降级重试一次，避免死循环。 */
    private boolean thinkDegraded;
    TextView thinkingLabel;
    View thinkingView;
    private boolean waiting;
    /** 上下文占用环（顶栏右侧），按本地估算字符数刷新。 */
    private CtxRing ctxRing;
    /** 当前在途请求的取消句柄；null 表示没有正在跑的请求。 */
    private DeepSeekClient.Task task;
    /** 【思考框】本次请求发出的时刻（毫秒），用于算「思考了 X 秒」。 */
    private long askStartMs;
    /** 抽屉层：包住整块面板，不改变 ChatPanel 的构造签名与父子结构。 */
    private ChatDrawer drawer;
    static final Pattern AFFECTION_HEAD = Pattern.compile("^\\s*[\\[【]\\s*好感度\\s*[:：]\\s*([+-]?\\d+)\\s*[\\]】]\\s*");
    static final Pattern AFFECTION_ANY = Pattern.compile("[\\[【]\\s*好感度\\s*[:：]\\s*[+-]?\\d+\\s*[\\]】]");
    public interface Controller {
        void onClose();
        void onDrag(float f, float f2);
        void onOpenFullScreen();
        /** 点顶栏的上下文环：打开「令牌消耗统计」页。
         *  【坑】悬浮窗场景的 Context 链里没有 Activity，宿主必须自己想办法（见 ChatWindow）。 */
        void onOpenTokenStat();
    }
    // 构造：搭骨架 → 载入历史 → 刷新输入行 → 套用聊天背景。
    public ChatPanel(Context context, ChatPanel.Controller controller, boolean z) {
        super(context);
        this.history = new JSONArray();
        this.waiting = false;
        this.browsingArchives = false;
        this.requestHasImage = false;
        this.thinkDegraded = false;
        this.controller = controller;
        setOrientation(1);
        setBackground(UiKit.roundStroke(UiKit.OPTION, UiKit.CHAT_BORDER, getContext(), 16));
        build(z);
        // 【交互】记忆工具需要 Context，在这里登记一次（幂等）；联网工具是否下发看用户开关。
        ChatToolRegistry.ensure(getContext().getApplicationContext());
        ChatHistoryStore.loadHistory(this);
        renderHistory();
        refreshInputRow();
        refreshCtxRing();
        applyBackground();
    }
    // 尺寸换算：统一走 UiKit，避免多处重复实现。
    private int dp(float f) {
        return UiKit.dp(getContext(), f);
    }
    /** 【交互】气泡与占位控件的实际实现在 ChatBubbles；以下为薄壳，保持内部调用点不变。 */
    public void addBubble(String str, boolean z) {
        ChatBubbles.addBubble(this, str, z);
    }

    private void addBubble(String str, boolean z, int i) {
        ChatBubbles.addBubble(this, str, z, i);
    }

    private void addBubble(String str, boolean z, int i, String str2) {
        ChatBubbles.addBubble(this, str, z, i, str2);
    }

    private void scrollToBottom() {
        ChatBubbles.scrollToBottom(this);
    }

    private void addThinking(String str) {
        ChatBubbles.addThinking(this, str);
    }

    private void setThinkingLabel(String str) {
        ChatBubbles.setThinkingLabel(this, str);
    }

    private void removeThinking() {
        ChatBubbles.removeThinking(this);
    }

    // 一次性搭出消息区 / 输入行 / 附件条三层结构。
    private void build(boolean z) {
        LinearLayout linearLayout = new LinearLayout(getContext());
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        // 【观感】左内边距收到 6dp：图标自带 38dp 命中区，视觉重心本就已经贴角，
        // 再留 14dp 整条顶栏会显得往右下偏移。
        linearLayout.setPadding(dp(6.0f), dp(8.0f), dp(6.0f), dp(8.0f));
        addView(linearLayout, new LinearLayout.LayoutParams(-1, -2));
        TextView textView = new TextView(getContext());
        textView.setText("Dollhouse");
        textView.setTextSize(16.0f);
        textView.setTextColor(UiKit.TITLE);
        textView.setPadding(0, dp(4.0f), 0, dp(4.0f));
        // 【顺序】标题的 addView 挪到返回键之后（见下方）：标题 weight=1 会吃掉剩余宽度，
        // 若先加标题，返回键会被挤到头部最右侧，箭头朝左「指向标题」，观感就是方向不对。
        getContext();
        View.OnTouchListener onTouchListener = new View.OnTouchListener() {            private float lastX;
            private float lastY;
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int actionMasked = motionEvent.getActionMasked();
                if (actionMasked == 0) {
                    this.lastX = motionEvent.getRawX();
                    this.lastY = motionEvent.getRawY();
                    return true;
                }
                if (actionMasked != 2) {
                    return false;
                }
                float rawX = motionEvent.getRawX();
                float rawY = motionEvent.getRawY();
                if (ChatPanel.this.controller != null) {
                    ChatPanel.this.controller.onDrag(rawX - this.lastX, rawY - this.lastY);
                }
                this.lastX = rawX;
                this.lastY = rawY;
                return true;
            }
        };
        textView.setOnTouchListener(onTouchListener);
        linearLayout.setOnTouchListener(onTouchListener);
        // 【观感】返回键钉在左上角：改成无底扁平图标 + 38dp 命中区，靠 UiKit.press 的
        // 缩放反馈表达可点。原先是白底描边胶囊，在顶栏里比标题还抢眼，所以显得突兀。
        ImageView backIcon = UiKit.iconView(getContext(), Icons.IC_ARROW_LEFT, UiKit.FS_ICON, UiKit.TITLE);
        backIcon.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                if (ChatPanel.this.controller != null) {
                    ChatPanel.this.controller.onClose();
                }
            }
        });
        linearLayout.addView(backIcon, iconLp(0));
        // 抽屉排第二：同样扁平，字号更小、色阶更淡，层级低于返回键。
        ImageView drawerIcon = UiKit.iconView(getContext(), Icons.IC_MENU, UiKit.FS_ICON, UiKit.SUB);
        drawerIcon.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                ChatPanel.this.openDrawer();
            }
        });
        linearLayout.addView(drawerIcon, iconLp(dp(2.0f)));
        // 标题放在返回键之后：先加两个图标保证返回键贴在最左侧。
        linearLayout.addView(textView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        // 上下文占用环：环形 = 已用/剩余，中心数字 = 占用百分比；点开现有「令牌消耗统计」页。
        CtxRing ring = new CtxRing(getContext());
        this.ctxRing = ring;
        ring.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                if (ChatPanel.this.controller != null) {
                    ChatPanel.this.controller.onOpenTokenStat();
                }
            }
        });
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(dp(30.0f), dp(30.0f));
        ringLp.leftMargin = dp(2.0f);
        ringLp.rightMargin = dp(2.0f);
        linearLayout.addView(ring, ringLp);
        TextView textView2 = new TextView(getContext());
        this.hint = textView2;
        textView2.setTextSize(UiKit.FS_SUB);
        this.hint.setTextColor(UiKit.HINT_FG);
        // 【圆角】提示条原为满宽直角背景，会把面板圆角盖成直角；改圆角色块并左右留边。
        this.hint.setBackground(UiKit.round(UiKit.HINT_BG, getContext(), 10));
        this.hint.setPadding(dp(14.0f), dp(7.0f), dp(14.0f), dp(7.0f));
        UiKit.collapse(this.hint);
        this.hint.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                Intent intent = new Intent(ChatPanel.this.getContext(), (Class<?>) MainActivity.class);
                intent.setFlags(335544320);
                ChatPanel.this.getContext().startActivity(intent);
            }
        });
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.leftMargin = dp(10.0f);
        hintLp.rightMargin = dp(10.0f);
        hintLp.topMargin = dp(6.0f);
        addView(this.hint, hintLp);
        ScrollView scrollView = new ScrollView(getContext());
        this.scroller = scrollView;
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 【v2.8】展开「更早的历史」后，滑到看不见那批消息时自动收回（数据不丢，入口重现）。
        scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                onScrolled(scrollY);
            }
        });
        LinearLayout linearLayout2 = new LinearLayout(getContext());
        this.messages = linearLayout2;
        linearLayout2.setOrientation(1);
        this.messages.setPadding(dp(12.0f), dp(8.0f), dp(12.0f), dp(8.0f));
        this.scroller.addView(this.messages, new ViewGroup.LayoutParams(-1, -2));
        addView(this.scroller, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        // 【三件套】输入行上方的工具条：🐳 模型配置 / 💡 思考程度 / ＋ 记忆。
        // 【坑】三个图标要自己撑出 38dp 命中区（flatIcon 只画字，不量宽高），
        //       与顶栏返回键同一套尺寸，点起来才不会漏。
        LinearLayout toolWrap = new LinearLayout(getContext());
        this.toolRow = toolWrap;
        toolWrap.setOrientation(0);
        toolWrap.setGravity(16);
        ImageView modelIcon = UiKit.iconView(getContext(), Icons.IC_SETTINGS, UiKit.FS_ICON, UiKit.TITLE);
        modelIcon.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                SheetPanel.showModels(ChatPanel.this.getContext(), ChatPanel.this.findLayer());
            }
        });
        toolWrap.addView(modelIcon, iconLp(0));
        ImageView thinkIcon = UiKit.iconView(getContext(), Icons.IC_BRAIN, UiKit.FS_ICON, UiKit.SUB);
        thinkIcon.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                SheetPanel.showThink(ChatPanel.this.getContext(), ChatPanel.this.findLayer(), ChatPanel.this);
            }
        });
        toolWrap.addView(thinkIcon, iconLp(dp(2.0f)));
        ImageView memIcon = UiKit.iconView(getContext(), Icons.IC_PLUS, UiKit.FS_ICON, UiKit.SUB);
        memIcon.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                SheetPanel.showMemory(ChatPanel.this.getContext(), ChatPanel.this.findLayer(), ChatPanel.this);
            }
        });
        toolWrap.addView(memIcon, iconLp(dp(2.0f)));
        // 【修·附件断链】工具条补一个「图片」入口。
        //   ChatPanel.openPicker() 原本全工程零调用点，PickFileActivity 的图片回调
        //   （onPicked -> pendingImage）永远收不到东西 —— 「给她发图片」这条链路是死的。
        ImageView imgIcon = UiKit.iconView(getContext(), Icons.IC_IMAGE, UiKit.FS_ICON, UiKit.SUB);
        imgIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ChatPanel.this.openPicker();
            }
        });
        toolWrap.addView(imgIcon, iconLp(dp(2.0f)));
        // 【v2.8】右侧的「记忆总结中」：weight=1 吃掉三个图标之后的全部留白，文字贴右。
        // 【坑】只在总结在途时可见（GONE 不参与布局），所以平时三个图标的位置与之前完全一致。
        TextView busy = new TextView(getContext());
        this.memoBusy = busy;
        busy.setText("记忆总结中");
        busy.setTextSize(UiKit.FS_TINY);
        busy.setTextColor(UiKit.CHAT_CHIP_MUTE);
        busy.setGravity(8388629);
        // 【观感】面板极窄时不许换行，否则会把整条工具条撑高。
        busy.setSingleLine(true);
        busy.setEllipsize(android.text.TextUtils.TruncateAt.END);
        busy.setPadding(dp(6.0f), 0, dp(2.0f), 0);
        UiKit.collapse(busy);
        toolWrap.addView(busy, new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams toolLp = new LinearLayout.LayoutParams(-1, -2);
        toolLp.leftMargin = dp(10.0f);
        toolLp.rightMargin = dp(10.0f);
        toolLp.bottomMargin = dp(2.0f);
        // 【修·附件断链】附件预览条：缩略图 + 说明 + 移除按钮。
        //   原先 attachStrip / attachThumb / attachLabel 三个字段只有读取方，从来没有创建代码，
        //   refreshAttachStrip() 里 `attachStrip == null` 直接 return —— 选了图也看不到、去不掉。
        this.attachStrip = new LinearLayout(getContext());
        this.attachStrip.setOrientation(0);
        this.attachStrip.setGravity(16);
        this.attachStrip.setBackground(UiKit.round(UiKit.CARD, getContext(), 16));
        this.attachStrip.setPadding(dp(8.0f), dp(6.0f), dp(6.0f), dp(6.0f));
        this.attachThumb = new ImageView(getContext());
        this.attachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(40.0f), dp(40.0f));
        thumbLp.rightMargin = dp(8.0f);
        this.attachStrip.addView(this.attachThumb, thumbLp);
        this.attachLabel = new TextView(getContext());
        this.attachLabel.setTextSize(UiKit.FS_TINY);
        this.attachLabel.setTextColor(UiKit.SUB);
        this.attachLabel.setSingleLine(true);
        this.attachLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        this.attachStrip.addView(this.attachLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));
        ImageView attachClear = UiKit.iconView(getContext(), Icons.IC_CLOSE, 14.0f, UiKit.SUB);
        attachClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ChatPanel.this.clearAttachment();
            }
        });
        this.attachStrip.addView(attachClear, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(-1, -2);
        attachLp.leftMargin = dp(10.0f);
        attachLp.rightMargin = dp(10.0f);
        attachLp.bottomMargin = dp(2.0f);
        this.attachStrip.setLayoutParams(attachLp);
        // 初始收起（仍占据布局，由 alpha/显隐控制）。
        this.attachStrip.setVisibility(View.GONE);
        this.attachStrip.setAlpha(0f);
        addView(this.attachStrip);
        addView(this.toolRow, toolLp);
        LinearLayout linearLayout3 = new LinearLayout(getContext());
        this.inputRow = linearLayout3;
        linearLayout3.setOrientation(0);
        this.inputRow.setGravity(16);
        // 【圆角】输入行贴在最底部：它若用满宽直角背景，会把面板根布局的 16dp 圆角盖成直角。
        // 改走 round() 圆角，并给底边留出与父级一致的 16dp，避免出现「外圆内方」的接缝。
        this.inputRow.setBackground(UiKit.round(UiKit.CARD, getContext(), 16));
        this.inputRow.setPadding(dp(10.0f), dp(8.0f), dp(6.0f), dp(8.0f));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.leftMargin = dp(10.0f);
        inputLp.rightMargin = dp(10.0f);
        inputLp.bottomMargin = dp(10.0f);
        addView(this.inputRow, inputLp);
        EditText editText = new EditText(getContext());
        this.input = editText;
        editText.setHint("跟她说点什么…");
        this.input.setMaxLines(4);
        this.input.setInputType(147457);
        // 【圆角】原实现没给输入框设背景，用的是系统默认直角下划线；统一走 UiKit.field（8dp 圆角）。
        UiKit.field(this.input, getContext());
        // 【顺序】field() 内部会 setTextSize(14)；要保住聊天框的 15sp 必须放在它之后。
        this.input.setTextSize(UiKit.FS_BTN);
        this.inputRow.addView(this.input, new LinearLayout.LayoutParams(0, -2, 1.0f));
        // 【观感】发送键改成 38dp 圆形图标：原来的长条「发送」白字紫底在输入行里像块招牌，
        // 视觉上比输入框还重。改成圆形 + 单字图标后与输入行的圆角胶囊同一套语汇。
        ImageView button = new ImageView(getContext());
        this.sendBtn = button;
        UiKit.sendButton(this.sendBtn, getContext());
        this.sendBtn.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View view) {
                // 【交互】同一个按钮两副面孔：空闲时「发送」，等待回复时「停止」。
                if (ChatPanel.this.waiting) {
                    ChatPanel.this.stopGenerating();
                } else {
                    ChatPanel.this.onSend();
                }
            }
        });
        this.inputRow.addView(this.sendBtn, sendLp());

    }

    /** 输入行右侧的圆形发送键：固定 38dp 方形，靠 sendButton 的 999dp 圆角成圆。 */
    private LinearLayout.LayoutParams sendLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(UiKit.HIT_DP), dp(UiKit.HIT_DP));
        lp.leftMargin = dp(8.0f);
        return lp;
    }

    /** 顶栏 / 工具条图标的命中区：固定边长，左外边距由调用方给。 */
    private LinearLayout.LayoutParams iconLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(UiKit.HIT_DP), dp(UiKit.HIT_DP));
        lp.leftMargin = leftMargin;
        return lp;
    }

    /** 请求在途时把发送键切成「停止」，回来再切回「发送」。 */
    private void setWaiting(boolean z) {
        this.waiting = z;
        ImageView button = this.sendBtn;
        if (button == null) {
            return;
        }
        button.animate().cancel();
        button.animate().alpha(0f).setDuration(UiKit.D_MICRO).setInterpolator(UiKit.EASE_STD)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        button.setImageResource(z ? Icons.IC_CLOSE : Icons.IC_SEND);
                        Icons.tint(button, UiKit.ON_ACC);
                        button.animate().alpha(1f).setDuration(UiKit.D_MICRO).setInterpolator(UiKit.EASE_STD).start();
                    }
                }).start();
    }

    /** 用户点「停止」：断掉在途连接，静默收尾（不报网络错误）。 */
    public void stopGenerating() {
        DeepSeekClient.Task t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.task = null;
        setWaiting(false);
        removeThinking();
    }
    /** 请求在途时把发送键切成「停止」，回来再切回「发送」。 */
    public void refreshHint() {
        if (PetPrefs.hasKey(getContext())) {
            UiKit.collapse(this.hint);
        } else {
            this.hint.setText("还没填 API key，点这里去设置 →");
            UiKit.reveal(this.hint);
        }
    }
    // 从偏好里取出接口地址与密钥（缺任一就算没配好）。
    private String[] endpoint() {
        return new String[]{PetPrefs.baseUrl(getContext()), PetPrefs.apiKey(getContext()), PetPrefs.model(getContext())};
    }
    // 能否发消息：必须有配置且当前不在等待回复。
    private boolean canChat() {
        return PetPrefs.hasKey(getContext());
    }
    // 弹起键盘并聚焦输入框。
    public void focusInput() {
        this.input.requestFocus();
    }
    // 把历史逐条铺成气泡。
    private void renderHistory() {
        // 【v2.8·P0 防护】上一次总结留下的「滚到分割线」回调必须在重铺前摘掉：
        //        它捕获的是旧的 summaryNode，removeAllViews 后该节点已脱离 messages，
        //        预绘制阶段再做 offsetDescendantRectToMyCoords 会直接抛异常崩进程。
        cancelPendingScroll();
        this.messages.removeAllViews();
        // 【v2.8】重铺时旧节点引用一律作废，先清空再按当前历史重建。
        this.summaryNode = null;
        this.prevTailView = null;
        // 【v2.8】入口放在「空历史」判断之前：自动收回后若一条正文都不剩，也得能从界面上点回来。
        if (ChatHistoryStore.hasPrev(getContext())) {
            addLoadEarlierEntry();
        }
        if (this.history.length() == 0) {
            addBubble("我是小肥鱼～ 有什么想跟我说的吗？", false);
            UiKit.staggerCapped(this.messages, 12);
            return;
        }
        // 【v2.8·P3】补回批不再按下标推导（见 mergePrev 打的 _prev 标记），这里只判「有没有摘要头」。
        for (int i = 0; i < this.history.length(); i++) {
            JSONObject optJSONObject = this.history.optJSONObject(i);
            if (optJSONObject == null) {
                continue;
            }
            // 【新增】头部摘要（kind=summary）原本被 !"system" 判据静默跳过，现在渲染成可展开的分割标题。
            if ("summary".equals(optJSONObject.optString("kind"))) {
                this.summaryNode = ChatBubbles.addSummaryDivider(this, optJSONObject.optString("content"));
                continue;
            }
            String optString = optJSONObject.optString("role");
            if (!"system".equals(optString)) {
                addBubble(optJSONObject.optString("content"), "user".equals(optString), i, optJSONObject.optString("image", null));
                // 【v2.8·P3】补回批的尾部节点按来源标记认（不再靠「头部摘要数 + prevCount」的下标推导）。
                if (ChatHistoryStore.isPrevItem(optJSONObject)) {
                    this.prevTailView = this.messages.getChildAt(this.messages.getChildCount() - 1);
                }
            }
        }
        UiKit.staggerCapped(this.messages, 12);
    }

    /** 【v2.8】聊天记录顶部的「点击加载更早的历史记录」入口（补回即清空暂存，入口随之消失）。 */
    private void addLoadEarlierEntry() {
        ChatBubbles.addLoadEarlier(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 【v2.8】记下补回的条数：这是「展开态」的唯一凭据，滑过分割线时按它收回去。
                int n = ChatHistoryStore.prependPrev(ChatPanel.this);
                if (n > 0) {
                    ChatPanel.this.prevCount = n;
                    // 【坑】不能走 reloadHistory：它会把刚设好的 prevCount 清零。
                    //        prependPrev 已把合并结果写进 prefs 与 host.history，直接重铺即可。
                    // 【v2.8·P1】这趟重铺压住自动滚底，并暂缓一帧收回判据：
                    //   补回批挂在顶部，一旦被排队的「滚到底」推到底部，就会立刻命中判据二，
                    //   展开态刚建立就被收回去（功能等于失效）。
                    // 【v2.8·N3】用 try/finally 兜底：renderHistory 万一抛异常，
                    //   holdScroll 必须复位，否则自动滚底与自动收回会永久失效。
                    ChatPanel.this.holdScroll = true;
                    try {
                        renderHistory();
                        UiKit.scrollToTop(ChatPanel.this.scroller);
                    } finally {
                        ChatPanel.this.holdScroll = false;
                    }
                    ChatPanel.this.post(new Runnable() {
                        @Override
                        public void run() {
                            // 【坑】队列里可能还排着更早 addBubble 时 post 的 fullScroll，
                            //       等它跑完再拉回补回批顶部，用户看到的才是「刚展开的那批」。
                            UiKit.scrollToTop(ChatPanel.this.scroller);
                        }
                    });
                    refreshCtxRing();
                }
            }
        });
    }

    /**
     * 【v2.8】总结在途时把工具条右侧的「记忆总结中」亮起来，结束后收起。
     * 【坑】调用方（DeepSeekClient 回调）本就在主线程，所以直接设可见性；
     *       若换成 View.post，面板 detached 时会把任务挂到重新 attach 才执行，提示可能永久残留。
     */
    void setMemoBusy(final boolean busy) {
        this.memoBusyOn = busy;
        final TextView t = this.memoBusy;
        if (t == null) {
            return;
        }
        // 【v2.9.2】新的总结开始了：让上一次的失败提示让位，并把文字复位成进行中文案
        //（原来只改可见性不复位文字，会导致「总结中」显示的还是上次的失败文案）。
        if (busy) {
            this.flashHold = false;
            if (this.pendingFlash != null) {
                removeCallbacks(this.pendingFlash);
                this.pendingFlash = null;
            }
        }
        // 【v2.9.2·P0】总结结束时必须避开正在显示的 flash 提示：
        //  回调 finally 里必调 setMemoBusy(false)，若在此隐藏，flashMemo 的 setVisibility(0)
        //  会被同一帧改回 8，不绘制中间态 —— 用户依然「点了没反应」。
        final boolean keepVisible = !busy && this.flashHold;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (busy) {
                t.setText("记忆总结中");
            }
            if (!keepVisible) {
                UiKit.showHide(t, busy);
            }
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                if (busy) {
                    t.setText("记忆总结中");
                }
                if (!keepVisible) {
                    UiKit.showHide(t, busy);
                }
            }
        });
    }

    /**
     * 【v2.8】总结完成后把视图滚到「ⓘ 历史对话摘要」那条分割线，让「已收起」一眼可见。
     * 【坑】必须等新视图量好再算位置：直接 post 会跑在布局之前，getTop() 拿到 0 → 滚到顶部。
     *       跨 messages 这层包装取坐标要用 offsetDescendantRectToMyCoords，不能只信 getTop()。
     */
    void scrollToSummary() {
        final View box = this.summaryNode;
        if (box == null) {
            return;
        }
        // 【v2.8·P0】重入保护：同一时刻只留一个在途回调，新的直接顶掉旧的。
        cancelPendingScroll();
        android.view.ViewTreeObserver.OnPreDrawListener l = new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // 【v2.8·P0 防护】必须在自摘之前再验一次祖先关系：
                // 若这期间又发生过一次重铺（reloadHistory / showChat / clearChat / 另一次收回），
                // box 的 mParent 已被置空，它不再是 messages 的后代，
                // 此时 offsetDescendantRectToMyCoords 会抛 IllegalArgumentException 直接崩进程。
                // renderHistory() 已主动摘监听，这里只是兜底的第二道闸。
                if (box.getParent() != messages) {
                    cancelPendingScroll();
                    return true;
                }
                cancelPendingScroll();
                try {
                    android.graphics.Rect r = new android.graphics.Rect();
                    box.getDrawingRect(r);
                    messages.offsetDescendantRectToMyCoords(box, r);
                    scroller.smoothScrollTo(0, Math.max(0, r.top - dp(8.0f)));
                } catch (Throwable unused) {
                    // 【兜底】节点在预绘制与绘制之间被摘掉时同样会走到这里，静默放弃定位，
                    //         不滚动总好过崩掉进程。
                }
                return true;
            }
        };
        this.pendingScroll = l;
        box.getViewTreeObserver().addOnPreDrawListener(l);
    }

    /**
     * 【v2.8】摘掉在途的「滚到摘要分割线」回调。
     * 【实现】box 与 messages 同在一棵 window 视图树上，View.getViewTreeObserver() 返回的
     *       就是同一个 mAttachInfo.mTreeObserver，所以直接对 messages 的 VTO 摘除即可。
     *       两条登记路径（MemSummarizer 的 host.post、onScrolled 内）都在 attach 之后，
     *       不会出现「各自 lazy 建 floating observer」对不上的情况。
     * 【兜底】万一真摘不掉，onPreDraw 里的祖先关系检查也会挡住坐标换算，不会崩。
     */
    private void cancelPendingScroll() {
        android.view.ViewTreeObserver.OnPreDrawListener l = this.pendingScroll;
        if (l == null) {
            return;
        }
        this.pendingScroll = null;
        try {
            this.messages.getViewTreeObserver().removeOnPreDrawListener(l);
        } catch (Throwable unused) {
        }
    }

    /**
     * 【v2.8】滚动回调：展开态下补回批整批滑出视口上沿（看不到上面那批消息了），就自动收回。
     * 【坑】程序化滚动也会触发本回调，所以收回过程中用 collapsing 挡住重入。
     */
    private void onScrolled(int scrollY) {
        if (this.collapsing || this.holdScroll || this.prevCount <= 0) {
            return;
        }
        View v = this.prevTailView;
        if (v == null) {
            return;
        }
        // 【v2.8】两种收口：批次整批被推到视口上沿之上；或已经滑到列表底部且滚过了头。
        int contentH = this.messages.getHeight();
        int viewH = this.scroller.getHeight();
        boolean atBottom = scrollY > 0 && viewH > 0 && scrollY + viewH >= contentH - 1;
        if (!shouldCollapse(v.getBottom(), scrollY, atBottom)) {
            return;
        }
        final int n = this.prevCount;
        this.collapsing = true;
        boolean ok = ChatHistoryStore.collapsePrev(this, n);
        // 【坑】不论成败都清掉展开态：失败说明 history 结构已经在展开期间被改过，
        //       留着 prevCount 会让每帧滚动回调都重试一次（白白构建 JSON），得不偿失；
        //       数据一条不动，用户仍在展开态里，只是不再自动收回。
        this.prevCount = 0;
        this.prevTailView = null;
        if (ok) {
            reloadHistory();
            scrollToSummary();
        }
        post(new Runnable() {
            @Override
            public void run() {
                ChatPanel.this.collapsing = false;
            }
        });
    }

    /**
     * 【v2.8】是否该把补回的历史收回去（纯函数，便于离线复算）。
     * 【判据一】补回批最后一个节点的底边已经滚到视口上沿之上（tailBottom - scrollY &lt;= 0），
     *          即「上面那批消息已经看不见了」。
     * 【判据二】已经滚到列表底部并继续拉：底部态同样算「看不到上面那批」，一并收回。
     */
    static boolean shouldCollapse(int tailBottom, int scrollY, boolean atBottom) {
        return (tailBottom - scrollY <= 0) || atBottom;
    }
    public void refreshInputRow() {
        LinearLayout linearLayout;
        LinearLayout linearLayout2 = this.inputRow;
        if (linearLayout2 != null) {
            UiKit.showHide(linearLayout2, !this.browsingArchives);
        }
        // 【三件套】工具条与输入行同进同退：翻看归档时一起收起，否则点了弹不出面板。
        LinearLayout toolWrap = this.toolRow;
        if (toolWrap != null) {
            UiKit.showHide(toolWrap, !this.browsingArchives);
            // 【v2.8】工具条被整体隐藏时子视图状态会保留，但这里显式重放一次，
            //         避免将来改动 refreshInputRow 时把「记忆总结中」弄丢。
            TextView busy = this.memoBusy;
            if (busy != null) {
                // 【v2.9.2】重放时也要认 flash：否则切归档/回聊天会把失败提示提前抹掉。
                UiKit.showHide(busy, this.memoBusyOn || this.flashHold);
            }
        }
        if (!this.browsingArchives || (linearLayout = this.attachStrip) == null) {
            refreshAttachStrip();
        } else {
            UiKit.collapse(linearLayout);
        }
    }
    // 套用用户选的聊天背景图与透明度。
    public void applyBackground() {
        if (this.scroller == null) {
            return;
        }
        String chatBackground = PetPrefs.chatBackground(getContext());
        Bitmap loadScaled = (chatBackground == null || chatBackground.isEmpty()) ? null : ImageStore.loadScaled(getContext(), chatBackground, 1080);
        if (loadScaled == null) {
            this.scroller.setBackground(null);
            return;
        }
        BitmapDrawable bitmapDrawable = new BitmapDrawable(getResources(), loadScaled);
        bitmapDrawable.setGravity(119);
        bitmapDrawable.setAlpha((PetPrefs.chatBgAlpha(getContext()) * 255) / 100);
        this.scroller.setBackground(bitmapDrawable);
        this.scroller.setAlpha(0.6f);
        this.scroller.animate().alpha(1f).setDuration(UiKit.D_MICRO).setInterpolator(UiKit.EASE_DECEL).start();
    }
    public void openPicker() {
        if (this.browsingArchives) {
            return;
        }
        PickFileActivity.setListener(this);
        PickFileActivity.setPurpose(ImageStore.DIR);
        PickFileActivity.start(getContext());
    }
    @Override
    public void onPicked(String str, String str2) {
        if (str != null) {
            this.pendingImage = str;
            this.pendingText = null;
        } else if (str2 != null) {
            this.pendingText = str2;
            this.pendingImage = null;
        }
        refreshAttachStrip();
    }
    @Override
    public void onFailed(String str) {
        // 读文件失败静默：不产生任何浮层反馈。
    }
    public void clearAttachment() {
        this.pendingImage = null;
        this.pendingText = null;
        refreshAttachStrip();
    }
    // 刷新附件条显示（缩略图 + 文件名 + 清除按钮）。
    private void refreshAttachStrip() {
        LinearLayout linearLayout = this.attachStrip;
        if (linearLayout == null) {
            return;
        }
        if (this.pendingImage == null && this.pendingText == null) {
            UiKit.collapse(linearLayout);
            this.attachThumb.setImageDrawable(null);
            return;
        }
        UiKit.reveal(linearLayout);
        if (this.pendingImage != null) {
            UiKit.reveal(this.attachThumb);
            this.attachThumb.setImageBitmap(ImageStore.loadScaled(getContext(), this.pendingImage, 160));
            this.attachLabel.setText("已选图片，会一起发给她");
            return;
        }
        UiKit.collapse(this.attachThumb);
        this.attachThumb.setImageDrawable(null);
        String str = this.pendingText;
        int max = Math.max(1, str != null ? str.length() / 1024 : 0);
        this.attachLabel.setText("已选文本内容（约 " + max + " KB），会拼在她看到的消息里");
    }
    public void showChat() {
        this.browsingArchives = false;
        refreshInputRow();
        renderHistory();
        refreshHint();
    }

    /* ------------------------- 多会话 / 上下文环 / 抽屉 ------------------------- */

    /**
     * 打开左侧抽屉。
     * 【坑】挂载层是「父链里第一个 FrameLayout」——ChatActivity 里是 android.R.id.content，
     *       悬浮窗里是 ChatWindow.root。找不到就退回自己的父容器，至少不会崩。
     */
    public void openDrawer() {
        if (this.drawer == null) {
            this.drawer = new ChatDrawer(this);
        }
        ViewGroup layer = findLayer();
        if (layer == null) {
            return;
        }
        this.drawer.open(layer);
    }

    /** 抽屉 / 确认框共用的挂载层。 */
    ViewGroup findLayer() {
        android.view.ViewParent p = getParent();
        while (p instanceof View) {
            if (p instanceof FrameLayout) {
                return (FrameLayout) p;
            }
            p = ((View) p).getParent();
        }
        return p instanceof ViewGroup ? (ViewGroup) p : null;
    }

    /** 抽屉关着就关掉并返回 true（给返回键用）。 */
    public boolean closeDrawerIfOpen() {
        ChatDrawer d = this.drawer;
        if (d != null && d.isOpen()) {
            d.close();
            return true;
        }
        return false;
    }

    /** 会话切换 / 新建 / 删除后：重新载入历史并整屏重铺。 */
    public void reloadHistory() {
        // 【v2.8】展开态是「当前会话这份 history」的瞬态：切了会话/重建了视图一律作废，
        //         否则旧 count 会在新会话里误把正文当成补回批摘掉。
        this.prevCount = 0;
        this.prevTailView = null;
        ChatHistoryStore.loadHistory(this);
        renderHistory();
        refreshCtxRing();
    }
/** 手动触发一次上下文总结 + 压缩（真删除）。 */
    /** 手动总结：点了就总结，不设条数门槛（内容为空时才挡）。 */
    public void summarizeNow() {
        // 【v2.9.1】每个静默 return 都补一条可见提示：全工程禁用 Toast，
        // 不给反馈用户只会以为「点了没反应」，无法区分「真失败」与「本就没得总结」。
        Logs.i("DollhouseMemo", "[入口] 点按立即总结 histLen=" + this.history.length()
                + " running=" + MemSummarizer.isRunning());
        if (MemSummarizer.isRunning()) {
            Logs.i("DollhouseMemo", "[入口] 被挡: 上一次还在进行中");
            flashMemo("上一次总结还在进行中");
            return;
        }
        if (this.history.length() < 1) {
            Logs.i("DollhouseMemo", "[入口] 被挡: 历史为空");
            flashMemo("当前对话是空的，没什么可总结的");
            return;
        }
        // 【v2.9.5】总结前先收回展开态：补回批是「临时回看」，要点早已进过摘要，
        //  把它们留在 history 里会被 transcript 当成新内容重复总结一遍
        //  （用户报障「第一次总结的内容没有被销毁」）。
        //  收回 = 原样退回暂存位（数据一条不丢），之后本次只总结「摘要 + 之后的消息」。
        if (this.prevCount > 0) {
            boolean folded = ChatHistoryStore.collapsePrev(this, this.prevCount);
            Logs.i("DollhouseMemo", "[入口] 收回展开态 ok=" + folded
                    + " prevCount=" + this.prevCount);
            this.prevCount = 0;
            // 立即重铺：无论后续总结成败，界面上都要与内存一致（不再显示那批临时回看的消息）。
            renderHistory();
        }
        MemSummarizer.summarizeNow(this, null);
    }

    /** 【v2.9.1】在工具条右侧短暂显示一条提示（总结失败 / 被拒等），3 秒后自动收起。 */
    void flashMemo(String msg) {
        final TextView t = this.memoBusy;
        if (t == null || msg == null || msg.trim().isEmpty()) {
            Logs.i("DollhouseMemo", "[flash] 丢弃 t=" + (t != null)
                    + " msgEmpty=" + (msg == null || msg.trim().isEmpty()));
            return;
        }
        // 【v2.9.2·P2-1】同一时间只允许一个计时器：旧的先撤，否则连续两次失败点击时，
        //  较旧的计时器会在 3 秒时把较新的提示提前抹掉。
        if (this.pendingFlash != null) {
            removeCallbacks(this.pendingFlash);
            this.pendingFlash = null;
        }
        // 【v2.9.2·P0】置 flashHold：让紧随其后的 setMemoBusy(false) 别把它同帧隐藏掉。
        //  回调 finally 必调 setMemoBusy(false)，不设这个标志的话 setVisibility(0)
        //  会被立刻改回 8，同一帧完成、不绘制中间态 —— 用户什么都看不到。
        this.flashHold = true;
        Logs.i("DollhouseMemo", "[flash] 显示提示 len=" + msg.length());
        t.setText(msg);
        UiKit.reveal(t);
        this.pendingFlash = new Runnable() {
            @Override
            public void run() {
                ChatPanel.this.pendingFlash = null;
                ChatPanel.this.flashHold = false;
                // 期间若另一次总结开始了，就别把它的「记忆总结中」收掉。
                if (!ChatPanel.this.memoBusyOn) {
                    t.setText("记忆总结中");
                    UiKit.collapse(t);
                }
            }
        };
        postDelayed(this.pendingFlash, 3000L);
    }


    /** 复制最后一条助手回复（气泡操作条的「⧉ 复制」）。 */
    public void copyLastReply() {
        for (int i = this.history.length() - 1; i >= 0; i--) {
            JSONObject o = this.history.optJSONObject(i);
            if (o != null && "assistant".equals(o.optString("role"))) {
                ChatBubbles.copyText(this, o.optString("content", ""));
                return;
            }
        }
    }


    /** 清空当前对话（先二次确认）。 */
    public void confirmClearChat() {
        ChatDrawer d = this.drawer;
        if (d == null) {
            d = new ChatDrawer(this);
            this.drawer = d;
            d.open(findLayer());
        }
        d.confirm("清空当前对话", "这段对话会从本机彻底删掉，不能恢复。", "清空", new Runnable() {
            @Override
            public void run() {
                clearChat();
            }
        });
    }

    /** 真清空：历史归零（摘要与总记忆库不动，那是两份独立数据）。 */
    public void clearChat() {
        // 【v2.8】展开态一并作废：历史已归零，留着旧 count 会在后续滚动里误摘新正文。
        this.prevCount = 0;
        this.prevTailView = null;
        this.history = new JSONArray();
        ChatHistoryStore.saveHistory(this);
        // 【新增】连「更早的历史」暂存位一起清掉，否则清空后记录顶上还挂着「加载更早」。
        PetPrefs.removeConvPrev(getContext(), ChatSessions.currentId(getContext()));
        renderHistory();
        refreshCtxRing();
    }

    /** 本地估算的当前上下文占用字符数（人名提示词 + 记忆 + 历史正文）。 */
    public int ctxUsedChars() {
        int n = PetPrefs.SYSTEM_PROMPT.length();
        n += MemDb.recentText(getContext(), MemDb.MAX_INJECT_CHARS).length();
        for (int i = 0; i < this.history.length(); i++) {
            JSONObject o = this.history.optJSONObject(i);
            if (o != null) {
                n += o.optString("content", "").length();
            }
        }
        return n;
    }

    /**
     * 刷新顶栏的进度环。
     *
     * 【口径 v2.10.0】当前聊天条数 ÷ 记忆触发阈值，与 MemSummarizer 的 byCount 触发条件同源：
     *  涨到 100% 就是自动总结该触发的那一刻（触发后保留 KEEP_TAIL 条，环会回落到 8/阈值）。
     *  ctxUsedChars() 仍保留：MemSummarizer.maybeAuto 的 byRatio 分支还在用它，不能删。
     */
    public void refreshCtxRing() {
        CtxRing ring = this.ctxRing;
        if (ring == null) {
            return;
        }
        int threshold = Math.max(1, PetPrefs.memThreshold(getContext()));
        ring.setRatio(this.history.length() / (float) threshold);
    }
    public void regenerate() {
        if (this.waiting) {
            return;
        }
        if (this.history.length() == 0) {
            return;
        }
        JSONArray jSONArray = this.history;
        JSONObject optJSONObject = jSONArray.optJSONObject(jSONArray.length() - 1);
        if (optJSONObject == null || !"assistant".equals(optJSONObject.optString("role"))) {
            return;
        }
        JSONArray jSONArray2 = this.history;
        jSONArray2.remove(jSONArray2.length() - 1);
        ChatHistoryStore.saveHistory(this);
        renderHistory();
        setWaiting(true);
        addThinking("重新想过…");
        // 【v0.0.1】只有人偶专属会话才让人偶头顶说话：其他对话框的回复不再上气泡。
        if (ChatSessions.isPet(getContext())) {
            PetBus.say("让我再想想…", 4000L);
        }
        askModel(ChatHistoryStore.buildRequest(this), 0, true);
    }
    public void onSend() {
        String str;
        if (this.waiting) {
            return;
        }
        if (this.browsingArchives) {
            return;
        }
        String trim = this.input.getText().toString().trim();
        String str2 = this.pendingImage;
        String str3 = this.pendingText;
        if (trim.isEmpty() && str2 == null && str3 == null) {
            return;
        }
        if (!canChat()) {
            Intent intent = new Intent(getContext(), (Class<?>) MainActivity.class);
            intent.setFlags(335544320);
            getContext().startActivity(intent);
            return;
        }
        if (str3 != null) {
            if (trim.isEmpty()) {
                str = "";
            } else {
                str = trim + "\n\n";
            }
            trim = str + "【文件内容】\n" + str3;
        }
        if (str2 != null && trim.isEmpty()) {
            trim = "看看这张图。";
        }
        this.input.setText("");
        clearAttachment();
        addBubble(trim, true, -1, str2);
        ChatHistoryStore.push(this, "user", trim, str2);
        if (ChatSessions.isPet(getContext())) {
            PetBus.say(str2 != null ? "让我看看…" : "让我想想…", 0 != 0 ? 6000L : 2500L);
        }
        setWaiting(true);
        addThinking(str2 != null ? "正在看图…" : "正在思考…");
        askModel(ChatHistoryStore.buildRequest(this), 0, true);
    }
    public void askModel(final JSONArray jSONArray, final int i, final boolean z) {
        String[] endpoint = endpoint();
        // 【交互】记忆工具始终可用（AI 自己决定记什么）；联网工具按用户开关决定。
        JSONArray buildTools = z ? buildTools() : null;
        // 【思考框】计时起点：这一刻算「模型开始思考」。
        // 【坑】非流式请求（stream=false）拿不到思考阶段的起止，只能量整段往返；
        //       工具轮里每次重发 askModel 都会重置，所以显示的是「最后一轮」的耗时。
        this.askStartMs = System.currentTimeMillis();
        this.task = new DeepSeekClient.Task();
        DeepSeekClient.chatRaw(endpoint[1], endpoint[0], (0 == 0 && this.requestHasImage) ? PetPrefs.visionModel(getContext()) : endpoint[2], jSONArray, buildTools, samplingParams(false), this.task, new DeepSeekClient.RawCallback() {            @Override
            public void onMessage(JSONObject jSONObject, String str) {
                int i2;
                // 用户主动停止：静默收尾，不要当故障报出来。
                if (DeepSeekClient.STOPPED.equals(str)) {
                    return;
                }
                if (str != null) {
                    if (str.startsWith("TOOLS_UNSUPPORTED") && z) {
                        ChatPanel.this.askModel(jSONArray, i, false);
                        return;
                    }
                    // 【三件套】思考参数被服务端拒（参数类 400）时，剥掉参数只重试一次。
                    // 【坑】必须限一次，否则同一条错误会无限重试把对话卡死。
                    if (!ChatPanel.this.thinkDegraded
                            && ThinkLevel.shouldInject(PetPrefs.thinkLevel(ChatPanel.this.getContext()))
                            && str.indexOf("400") >= 0) {
                        ChatPanel.this.thinkDegraded = true;
                        ChatPanel.this.askModel(jSONArray, i, z);
                        return;
                    }
                    ChatPanel.this.finishWithError(str);
                    return;
                }
                JSONArray optJSONArray = jSONObject == null ? null : jSONObject.optJSONArray("tool_calls");
                if (optJSONArray != null && optJSONArray.length() > 0 && (i2 = i) < 3) {
                    ChatPanel.this.runTools(jSONArray, optJSONArray, jSONObject, i2);
                    return;
                }
                if (jSONObject != null) {
                    TokenStat.recordFrom(ChatPanel.this.getContext(), jSONObject);
                }
                // 【思考框】正文与思考内容分别取出：原来只取 content，reasoning_content 拿到就丢。
                // 【坑】耗时用「发起请求 → 回调回来」的整段近似（非流式无法只量思考段），
                //       夹在网络往返与正文生成之间，数值会偏长。
                String contentText = jSONObject == null ? "" : jSONObject.optString("content", "");
                String reasoningText = jSONObject == null ? "" : jSONObject.optString("reasoning_content", "");
                long costMs = System.currentTimeMillis() - ChatPanel.this.askStartMs;
                ChatPanel.this.handleReply(contentText, reasoningText, costMs);
            }
        });
    }
    private JSONObject samplingParams(boolean z) {
        JSONObject jSONObject = new JSONObject();
        try {
            if (z) {
                jSONObject.put("temperature", 0.7d);
                jSONObject.put("top_p", 0.9d);
                jSONObject.put("repeat_penalty", 1.15d);
                jSONObject.put("max_tokens", 220);
                JSONArray jSONArray = new JSONArray();
                jSONArray.put("\n用户：");
                jSONArray.put("\n主人：");
                jSONArray.put("\nUser:");
                jSONArray.put("\nAssistant:");
                jSONArray.put("\nassistant:");
                jSONObject.put("stop", jSONArray);
            } else {
                jSONObject.put("temperature", 1.2d);
                jSONObject.put("max_tokens", 500);
            }
            // 【三件套】思考程度（灯泡）：按档位补请求体字段。
            // 【坑】只有 2~5 档（加深）才发一个 reasoning_effort；0/1 档一律不发 ——
            //       enable_thinking 之类非标准字段在非流式请求下会被 Qwen 硬 400。
            //       若仍被服务端拒（参数类 400），onMessage 里会剥掉参数重试一次。
            int thinkLevel = PetPrefs.thinkLevel(getContext());
            if (!this.thinkDegraded && ThinkLevel.shouldInject(thinkLevel)) {
                ThinkLevel.parameters(jSONObject, thinkLevel);
            }
        } catch (Throwable unused) {
        }
        return jSONObject;
    }
    public void runTools(final JSONArray jSONArray, final JSONArray jSONArray2, final JSONObject jSONObject, final int i) {
        setThinkingLabel("我去查一下…");
        if (ChatSessions.isPet(getContext())) {
            PetBus.say("我去查一下…", 12000L);
        }
        new Thread(new Runnable() {            @Override
            public void run() {
                try {
                    JSONObject jSONObject2 = new JSONObject();
                    jSONObject2.put("role", "assistant");
                    jSONObject2.put("content", jSONObject.optString("content", ""));
                    jSONObject2.put("tool_calls", jSONArray2);
                    jSONArray.put(jSONObject2);
                } catch (Throwable unused) {
                }
                for (int i2 = 0; i2 < jSONArray2.length(); i2++) {
                    JSONObject optJSONObject = jSONArray2.optJSONObject(i2);
                    if (optJSONObject != null) {
                        JSONObject optJSONObject2 = optJSONObject.optJSONObject("function");
                        String optString = optJSONObject2 == null ? "" : optJSONObject2.optString("name", "");
                        String optString2 = optJSONObject2 != null ? optJSONObject2.optString("arguments", "{}") : "{}";
                        String doSearch = ChatPanel.this.runTool(optString, optString2);
                        try {
                            JSONObject jSONObject3 = new JSONObject();
                            jSONObject3.put("role", "tool");
                            jSONObject3.put("tool_call_id", optJSONObject.optString("id"));
                            jSONObject3.put("content", doSearch);
                            jSONArray.put(jSONObject3);
                        } catch (Throwable unused3) {
                        }
                    }
                }
                ChatPanel.this.post(new Runnable() {                    @Override
                    public void run() {
                        if (ChatPanel.this.isAttachedToWindow()) {
                            ChatPanel.this.askModel(jSONArray, i + 1, true);
                        }
                    }
                });
            }
        }, "feiyu-tools").start();
    }
    /** 【交互】联网工具已收口到 ChatToolRegistry / SearchTool；以下两处为兼容薄壳。 */
    public String doSearch(String str) {
        return ChatToolRegistry.execute("web_search", "{\"query\":\"" + str + "\"}");
    }

    private JSONArray buildTools() {
        // 【三件套】两个开关都从加号面板来：联网工具 + 记忆工具。
        return ChatToolRegistry.buildSchema(PetPrefs.webSearchEnabled(getContext()),
                PetPrefs.memAutoSave(getContext()));
    }

    /** 工具分发的宿主侧入口：入参原样透传给注册表，由各工具自己解析（search 取 query、remember 取 title/text）。 */
    private String runTool(String str, String str2) {
        return ChatToolRegistry.execute(str, str2);
    }

    public void finishWithError(String str) {
        this.task = null;
        if (isAttachedToWindow()) {
            setWaiting(false);
            removeThinking();
            addBubble("（出错了：" + str + "）", false);
        }
    }
    /** 兼容入口：不带思考内容的旧签名（外部契约不变）。 */
    public void handleReply(String str) {
        handleReply(str, "", 0L);
    }

    /**
     * 【思考框】带推理内容与耗时的版本。
     * 【分流】
     *   正文非空 → 自聊池先铺思考框再铺气泡（折叠条正好落在气泡正上方）；人偶池照旧只铺气泡。
     *   正文为空 → 人偶池拿思考内容顶上（她必须开口，不能只剩空气泡）；
     *              自聊池只铺思考框，既不铺空气泡也不写历史（思考内容不落盘）。
     *   两者都空 → 走既有错误气泡。
     */
    public void handleReply(String str, String reasoning, long costMs) {
        int i;
        boolean z;
        this.task = null;
        if (!isAttachedToWindow()) {
            return;
        }
        setWaiting(false);
        removeThinking();
        String body = str == null ? "" : str;
        String think = reasoning == null ? "" : reasoning.trim();
        boolean pet = ChatSessions.isPet(getContext());
        if (body.trim().isEmpty()) {
            if (think.isEmpty()) {
                finishWithError("模型返回了空内容");
                return;
            }
            if (pet) {
                body = think;
                think = "";
            } else {
                ChatBubbles.addThinkingBox(this, think, costMs);
                return;
            }
        }
        Matcher matcher = AFFECTION_HEAD.matcher(body);
        if (matcher.find()) {
            try {
                i = Integer.parseInt(matcher.group(1));
            } catch (Throwable unused) {
                i = 0;
            }
            z = true;
        } else {
            i = 0;
            z = false;
        }
        String trim = AFFECTION_ANY.matcher(body).replaceAll("").trim();
        if (trim.isEmpty()) {
            trim = z ? "……" : body.trim();
        }
        if (z) {
            PetBus.affection(PetPrefs.addAffection(getContext(), i));
        }
        ChatHistoryStore.push(this, "assistant", trim);
        if (!pet && !think.isEmpty()) {
            ChatBubbles.addThinkingBox(this, think, costMs);
        }
        addBubble(trim, false, this.history.length() - 1);
        if (pet) {
            PetBus.say(ChatHistoryStore.bubbleVersion(trim), 5000L);
        }
    }
}
