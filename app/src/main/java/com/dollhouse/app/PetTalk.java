package com.dollhouse.app;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;
/**
 * 【职责】人偶迷你聊天的纯逻辑层：三击召唤后收下的这条消息怎么发、气泡怎么展示、点一下怎么翻页。
 *
 * 【入口】只由 PetService 调用（talk / onBubbleTap / isBusy / cancel / release）。
 *
 * 【交互】不持有任何 View：说话、清泡、切行数全部经 Host 回调转给 PetService；
 *          会话历史复用 ChatHistoryStore 的独立版（ctx + history），存档键与全屏聊天页完全一致。
 *
 * 【共用】请求组装走 ChatHistoryStore.buildRequest(ctx, history)，
 *          人设 / 好感度 / 思考档位 / 长期记忆 / 学习示例 / 图片策略与全屏页同源，不会分叉。
 *
 * 【取舍】不传 tools：气泡只有两行，联网结果没有展示位，且少一轮往返更跟手。
 *          完整工具能力仍在全屏聊天页（首页 →「打开聊天」）。
 *
 * 【扩展】改气泡翻页规则只改 onBubbleTap；改请求参数只改 request。
 *
 * 【坑】PetBus 的 listener 是单个静态引用，本类绝不能 register，否则会把 ChatPanel 顶掉。
 */
final class PetTalk {
    /** 气泡相位：空闲 / 思考中 / 两行截断 / 已展开。 */
    static final int PHASE_IDLE = 0;
    static final int PHASE_LOADING = 1;
    static final int PHASE_TRUNC = 2;
    static final int PHASE_EXPANDED = 3;
    /** 请求兜底超时：网络卡死时不能让人偶永远停在「思考中」。 */
    private static final long REQUEST_TIMEOUT_MS = 60000L;
    private static final String LOADING_TEXT = "思考中…";
    /**
     * 气泡每页目标行数。
     * 【改粒度只改 PetBubble.PAGE_SENTENCES】这里只是引用，避免两处各写一份。
     */
    private static final int BUBBLE_PAGE_SENTENCES = PetBubble.PAGE_SENTENCES;
    interface Host {
        /** 显示常驻气泡（不自动消失），相位由本类管理。 */
        void saySticky(String str);
        /** 显示临时气泡，ms 毫秒后自动消失（报错、缺配置这类即时反馈）。 */
        void sayTemp(String str, long ms);
        /** 收掉气泡并复位窗口高度。 */
        void clearBubble();
        /** 切换气泡最大行数（截断 / 展开）。 */
        void setBubbleLines(int i);
        /** 请求进行中开关：输入框据此禁用，避免连点重复发。 */
        void onBusy(boolean z);
        /** 当前气泡文案是否被截断（决定点一下是展开还是收掉）。 */
        boolean bubbleHasOverflow();
        /** 把一段长文本按标点分页展示（只显首屏），返回总页数。 */
        int showBubblePaged(String str, int i);
        /** 取第 i 页文本（翻页）。 */
        String bubblePage(int i);
    }
    private final Context ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 与本对话共用的历史数组；懒加载自 PetPrefs 的当前会话档。 */
    private JSONArray history;
    private DeepSeekClient.Task task;
    private int phase = PHASE_IDLE;
    /** 本次回复的未切片全文（翻页源头）。 */
    private String bubbleFull;
    /** 当前显示到第几页（翻页游标）。 */
    private int pageIndex = 0;
    /** 本次回复全文的总页数（切页上限）。 */
    private int totalPages = 0;
    /** 兜底超时是否已触发（用于区分「用户主动停」与「等太久」）。 */
    private boolean timedOut = false;
    /** 思考参数被服务端拒后是否已降级过（限一次，防止死循环）。 */
    private boolean thinkDegraded = false;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (PetTalk.this.task == null) {
                return;
            }
            PetTalk.this.timedOut = true;
            PetTalk.this.task.cancel();
        }
    };
    PetTalk(Context context, PetTalk.Host host) {
        this.ctx = context;
        this.host = host;
    }
    /** 当前是否有在途请求。 */
    boolean isBusy() {
        return this.task != null;
    }
    /**
     * 迷你输入框发出一条消息。
     * 【顺序】先落档（user）再请求，保证「发出去的消息」在切到全屏页时也已经看得到。
     */
    void talk(String str) {
        String trim = str == null ? "" : str.trim();
        if (trim.isEmpty() || this.task != null) {
            return;
        }
        if (!PetPrefs.hasKey(this.ctx)) {
            this.host.sayTemp("还没配置 API Key（首页 → 聊天设置）", 3600L);
            return;
        }
        reloadHistory();
        ChatHistoryStore.push(this.ctx, this.history, "user", trim);
        // 新一轮对话清掉上一轮的翻页状态，避免旧游标串到新回复上。
        this.bubbleFull = null;
        this.pageIndex = 0;
        this.totalPages = 0;
        this.phase = PHASE_LOADING;
        this.host.setBubbleLines(PetBubble.LINES_TRUNC);
        this.host.saySticky(LOADING_TEXT);
        this.host.onBusy(true);
        request();
    }
    /**
     * 点了一下气泡：按相位推进，返回 true 表示已消费这一下点击（不要再触发单击跳）。
     *
     * 【相位】思考中 → 不动（防误点打断）；还有下一片 → 翻页；最后一片 → 收掉。
     * 【变更 v2.5】旧行为是「两行截断 → 一次全展开 → 收掉」，只有一次翻页；
     *   用户要求「有消息没说完时点一下进下一片，说完了点一下取消」，故改成分页游标。
     * 【变更 v2.7】页边界改由 PetBubble 按「句」划分（每页两句完整的话，超三行退一句），
     *   游标从「行号」变为「页号」。
     */
    boolean onBubbleTap() {
        int i = this.phase;
        if (i == PHASE_LOADING) {
            // 思考中：不打断（也不把「思考中…」换成台词）。
            return true;
        }
        if (i == PHASE_TRUNC) {
            // 【v2.6】按「页」推进（不再是行号）；页边界由 PetBubble 按标点切好。
            int next = this.pageIndex + 1;
            String str = this.bubbleFull == null ? null : this.host.bubblePage(next);
            if (str != null && !str.trim().isEmpty()) {
                this.pageIndex = next;
                this.host.saySticky(str);
                return true;
            }
            // 已经最后一片（或取页失败）：收掉。
            //   【v2.7】不再用 totalPages 当上限：它是 showPaged 那一刻的快照，
            //   之后气泡宽度一变页数就会重算，拿旧上限判会早停 / 越界。
            //   直接看「下一页取不取得出来」，天然自洽。
            this.phase = PHASE_IDLE;
            this.host.clearBubble();
            return true;
        }
        if (i == PHASE_EXPANDED) {
            // 全文一页就放得下：点一下直接收掉。
            this.phase = PHASE_IDLE;
            this.host.clearBubble();
            return true;
        }
        return false;
    }
    /** 主动取消在途请求（收起输入框 / 服务销毁）。 */
    void cancel() {
        DeepSeekClient.Task task = this.task;
        if (task != null) {
            task.cancel();
        }
        // 同步复位：即使回调被 identity 校验丢弃，状态也不会卡在「思考中」。
        this.ui.removeCallbacks(this.timeout);
        this.task = null;
        this.phase = PHASE_IDLE;
        this.bubbleFull = null;
        this.pageIndex = 0;
        this.totalPages = 0;
    }
    /** 服务销毁：取消请求 + 摘掉所有回调，避免 Handler 泄漏。 */
    void release() {
        cancel();
        this.ui.removeCallbacksAndMessages(null);
        // 【坑】请求回调挂在 DeepSeekClient.MAIN 上，这里摘不掉；
        //   靠 onMessage 里的 identity 校验把迟到回调挡掉（见 request）。
        this.task = null;
        this.phase = PHASE_IDLE;
    }
    /**
     * 每次发消息前重读当前会话档。
     * 【为什么不能只读一次】全屏聊天页可能在这期间往同一个键里写了新消息；
     *   若本类还拿着旧数组整段回写，会把那边的新消息覆盖掉（丢消息）。
     */
    private void reloadHistory() {
        String id = ChatSessions.ensure(this.ctx);
        try {
            this.history = new JSONArray(PetPrefs.convHistory(this.ctx, id));
        } catch (Throwable unused) {
            this.history = new JSONArray();
        }
        // 【v2.8·P2-①】与全屏页同款载入收口：盘上若留着展开态（带 _prev 的补回批），
        //   就地收回暂存位并消费标记；否则这份带标记的数组会被当成普通历史，
        //   一旦超过 60 条就会在 trimForSave 里连中段一起丢掉。
        ChatHistoryStore.collapsePrevOnLoad(this.ctx, id, this.history);
    }
    private void request() {
        try {
            doRequest();
        } catch (Throwable t) {
            // 【兜底】组装请求体这一步理论上不会抛，但没有它就会永久停在「思考中」。
            this.ui.removeCallbacks(this.timeout);
            this.task = null;
            fail(String.valueOf(t.getMessage()));
        }
    }
    private void doRequest() {
        boolean[] hasImage = new boolean[1];
        JSONArray body = ChatHistoryStore.buildRequest(this.ctx, this.history, hasImage);
        JSONObject params = samplingParams();
        String model = hasImage[0] ? PetPrefs.visionModel(this.ctx) : PetPrefs.model(this.ctx);
        this.timedOut = false;
        this.ui.postDelayed(this.timeout, REQUEST_TIMEOUT_MS);
        final DeepSeekClient.Task mine = new DeepSeekClient.Task();
        this.task = mine;
        // 【坑·真 bug】chatRaw 的形参顺序是 (apiKey, baseUrl, model, ...)：
        //   写反了不报编译错，只会在运行时把 URL 拼成 https://<Key>/chat/completions，
        //   于是「全屏聊天正常、只有迷你框报服务器错误」。
        DeepSeekClient.chatRaw(PetPrefs.apiKey(this.ctx), PetPrefs.baseUrl(this.ctx), model,
                body, null, params, mine, new DeepSeekClient.RawCallback() {
            @Override
            public void onMessage(JSONObject jSONObject, String str) {
                // 【守卫】只认「当前在途的那一次」：release / 新请求已经换掉 task 时，
                //        迟到的旧回调直接丢弃，不能让它把新请求的状态清掉。
                if (PetTalk.this.task != mine) {
                    return;
                }
                PetTalk.this.onReply(jSONObject, str);
            }
        });
    }
    /** 采样参数：与全屏页聊天的默认档保持一致（不注入思考档位时只发温度与上限）。 */
    private JSONObject samplingParams() {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("temperature", 1.2d);
            jSONObject.put("max_tokens", 500);
            int thinkLevel = PetPrefs.thinkLevel(this.ctx);
            // 【三件套】与 ChatPanel 同款：思考参数被服务端拒（参数类 400）时剥掉重试一次，限一次。
            if (!this.thinkDegraded && ThinkLevel.shouldInject(thinkLevel)) {
                ThinkLevel.parameters(jSONObject, thinkLevel);
            }
        } catch (Throwable unused) {
        }
        return jSONObject;
    }
    private void onReply(JSONObject jSONObject, String str) {
        this.ui.removeCallbacks(this.timeout);
        this.task = null;
        if (DeepSeekClient.STOPPED.equals(str)) {
            finishStopped();
            return;
        }
        if (str != null) {
            // 【三件套】参数类 400：剥掉思考参数重试一次，避免同一个错误永久复现。
            if (!this.thinkDegraded && str.indexOf("400") >= 0
                    && ThinkLevel.shouldInject(PetPrefs.thinkLevel(this.ctx))) {
                this.thinkDegraded = true;
                request();
                return;
            }
            this.phase = PHASE_IDLE;
            this.host.onBusy(false);
            fail(str);
            return;
        }
        String content = jSONObject == null ? "" : jSONObject.optString("content", "");
        if (content.isEmpty()) {
            // 【兜底】模型返回空内容时不要往历史里塞一条空 assistant。
            this.phase = PHASE_IDLE;
            this.host.onBusy(false);
            fail("模型返回了空内容");
            return;
        }
        finish(content);
    }
    /** 用户主动停：静默收尾，不要当故障报出来；兜底超时则给一句人话。 */
    private void finishStopped() {
        boolean wasTimeout = this.timedOut;
        this.timedOut = false;
        this.phase = PHASE_IDLE;
        this.host.onBusy(false);
        if (wasTimeout) {
            fail("等太久了…");
        } else {
            this.host.clearBubble();
        }
    }
    /**
     * 回复落地：剥掉首行好感度记账、写档、贴气泡。
     * 【坑】好感度首行契约（[好感度:+3]）与全屏页共用同一对正则，
     *        改这里等于同时改两个入口，别只改一边。
     */
    private void finish(String str) {
        String raw = str == null ? "" : str;
        int delta = 0;
        boolean hasDelta = false;
        Matcher matcher = ChatPanel.AFFECTION_HEAD.matcher(raw);
        if (matcher.find()) {
            try {
                delta = Integer.parseInt(matcher.group(1));
                hasDelta = true;
            } catch (Throwable unused) {
                delta = 0;
            }
        }
        String text = ChatPanel.AFFECTION_ANY.matcher(raw).replaceAll("").trim();
        if (text.isEmpty()) {
            text = hasDelta ? "……" : raw.trim();
        }
        if (hasDelta) {
            PetBus.affection(PetPrefs.addAffection(this.ctx, delta));
        }
        ChatHistoryStore.push(this.ctx, this.history, "assistant", text);
        // 【分页 v2.7】首屏只显示第一页=前两句完整的话（按句末标点切，超三行退一句），
        //   全文留给翻页用；
        //   【坑】总页数必须由 PetView/PetBubble 按「全文」算，不能拿切片的长度算
        //   （越切越短，页数会漂）。
        this.bubbleFull = text;
        this.pageIndex = 0;
        this.phase = PHASE_TRUNC;
        this.host.onBusy(false);
        // 【v2.7】一片最多 PAGE_MAX_LINES(3) 行（用户说的「格子」）：按此上限排版，
        //   气泡高度就等于一片的高度，不会再出现「排了 10 行、只露 3 行」的多余留白。
        this.host.setBubbleLines(PetBubble.PAGE_MAX_LINES);
        this.totalPages = this.host.showBubblePaged(text, BUBBLE_PAGE_SENTENCES);
        if (this.totalPages <= 1) {
            // 一页放得下：没有下一片，点一下直接收掉（避免空点击）。
            this.phase = PHASE_EXPANDED;
        }
    }
    private void fail(String str) {
        this.phase = PHASE_IDLE;
        this.host.onBusy(false);
        this.host.sayTemp("（出错了：" + str + "）", 4200L);
    }
}
