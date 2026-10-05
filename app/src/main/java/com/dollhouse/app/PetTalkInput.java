package com.dollhouse.app;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
/**
 * 【职责】三击人偶后出现的迷你输入框：一个输入框 + 一个发送键，挂在 WindowManager 上的独立悬浮窗。
 *
 * 【入口】只由 PetService 调用（show / hide / setBusy / moveTo / release）。
 *
 * 【交互】落点由 PetService 算好（宽 = 人偶宽、位于人偶正下方），本类只负责显示与收键盘；
 *          任何收起路径都回调 Host.onClosed，让宿主判断该不该退掉人偶的趴姿。
 *
 * 【扩展】改样式只动 build()；改收起时长只动 AUTO_HIDE_MS。
 *
 * 【收起规则】框里有未发送的内容时永不自动收起（TextWatcher + scheduleAutoHide 双重保证），
 *             空框 60 秒无操作才收。这样长文本输入不会再被中途吞掉。
 *
 * 【坑】窗口 flags 必须与 ChatWindow 一致（不含 FLAG_NOT_FOCUSABLE），
 *        EditText 才拿得到焦点、键盘才弹得出来；摘窗之前要先 hideSoftInputFromWindow。
 */
public class PetTalkInput {
    /** 输入框高度（dp）。 */
    static final float HEIGHT_DP = 46.0f;
    /**
     * 无操作自动收起时长。
     * 【坑】旧值 15 秒太短：打字时软键盘是独立窗口，点它不会重置计时，
     *   打满 15 秒整组（含已输入的文字）会被收掉 —— 用户报的「根本无法正常输入」。
     *   现在配合 TextWatcher：框内有内容时根本不排计时，清空 / 发出后才重新计时。
     */
    private static final long AUTO_HIDE_MS = 60000L;
    interface Host {
        /** 收起时回调（发送 / 超时 / 点人偶等所有路径）。 */
        void onClosed();
        /** 点发送键或回车：文本已 trim，非空。 */
        void onSend(String text);
        /** 输入法高度变化（0 = 收起）。宿主需要据此把人偶与输入框整组上移。 */
        void onIme(int imeBottom);
    }
    private final Context ctx;
    private final PetTalkInput.Host host;
    private final WindowManager wm;
    private final DisplayMetrics dm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private EditText input;
    private ImageView button;
    private boolean added = false;
    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            PetTalkInput.this.hide();
        }
    };
    PetTalkInput(Context context, PetTalkInput.Host host) {
        this.ctx = context;
        this.host = host;
        this.wm = (WindowManager) context.getSystemService("window");
        this.dm = context.getResources().getDisplayMetrics();
    }
    boolean isShowing() {
        return this.added;
    }
    /** 显示输入框；x / y / w 由宿主算好（已夹进屏幕）。 */
    void show(int x, int y, int w) {
        if (this.added) {
            focus();
            return;
        }
        build();
        this.lp = new WindowManager.LayoutParams(w, UiKit.dp(this.ctx, HEIGHT_DP),
                overlayType(), 16777248, -3);
        this.lp.gravity = 8388659;
        // 【坑】16 = 只有 ADJUST_RESIZE，没有 STATE_VISIBLE，悬浮窗的键盘可能不弹。
        //   20 = ADJUST_RESIZE | STATE_VISIBLE。
        this.lp.softInputMode = 20;
        this.lp.x = Math.max(0, x);
        this.lp.y = Math.max(0, y);
        try {
            this.wm.addView(this.root, this.lp);
            this.added = true;
        } catch (Throwable unused) {
            this.added = false;
            this.root = null;
            return;
        }
        focus();
        scheduleAutoHide();
    }
    /** 收起：藏键盘 + 摘窗 + 回调宿主。 */
    void hide() {
        if (!this.added) {
            return;
        }
        this.added = false;
        this.ui.removeCallbacks(this.autoHide);
        hideIme();
        try {
            this.wm.removeView(this.root);
        } catch (Throwable unused) {
        }
        this.root = null;
        this.input = null;
        this.button = null;
        this.host.onClosed();
    }
    /**
     * 收起但不回调宿主：宿主自己接管后续。
     * 【为什么需要】发完消息约定「只收输入框、人偶继续趴着等回复」，
     *   而 hide() 会回调 onClosed，宿主默认语义是「整组退掉（exitPeek）」——
     *   会把刚摆好的趴姿与「思考中」气泡一起清掉，所以发完这一步必须用静默收窗。
     */
    void hideQuiet() {
        if (!this.added) {
            return;
        }
        this.added = false;
        this.ui.removeCallbacks(this.autoHide);
        hideIme();
        try {
            this.wm.removeView(this.root);
        } catch (Throwable unused) {
        }
        this.root = null;
        this.input = null;
        this.button = null;
    }
    /** 服务销毁：不回调宿主（宿主正在拆自己）。 */
    void release() {
        this.added = false;
        this.ui.removeCallbacksAndMessages(null);
        if (this.root != null) {
            hideIme();
            try {
                this.wm.removeView(this.root);
            } catch (Throwable unused) {
            }
        }
        this.root = null;
        this.input = null;
        this.button = null;
    }
    /** 请求在途时禁用发送键，避免连点重复发。 */
    void setBusy(boolean z) {
        ImageView textView = this.button;
        if (textView == null) {
            return;
        }
        textView.setEnabled(!z);
        textView.setAlpha(z ? 0.5f : 1.0f);
    }
    /** 人偶移动 / 比例变化后重新落位（坐标由宿主算好）。 */
    void moveTo(int x, int y, int w) {
        if (!this.added || this.lp == null) {
            return;
        }
        this.lp.width = w;
        this.lp.x = Math.max(0, x);
        this.lp.y = Math.max(0, y);
        try {
            this.wm.updateViewLayout(this.root, this.lp);
        } catch (Throwable unused) {
        }
    }
    private void build() {
        LinearLayout bar = new LinearLayout(this.ctx);
        bar.setOrientation(0);
        bar.setGravity(16);
        int pad = UiKit.dp(this.ctx, 6.0f);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackground(UiKit.roundStroke(UiKit.CARD, UiKit.CHAT_BORDER, this.ctx, 14.0f));
        bar.setElevation(UiKit.dp(this.ctx, 6.0f));
        EditText editText = new EditText(this.ctx);
        editText.setSingleLine(true);
        editText.setHint("说点什么…");
        editText.setHintTextColor(UiKit.SUB);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_ACTION_SEND);
        editText.setBackgroundColor(Color.TRANSPARENT);
        editText.setTextSize(UiKit.FS_BTN);
        editText.setTextColor(UiKit.TITLE);
        editText.setPadding(UiKit.dp(this.ctx, 8.0f), 0, UiKit.dp(this.ctx, 8.0f), 0);
        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                if (i == 4) {
                    PetTalkInput.this.submit();
                    return true;
                }
                if (keyEvent == null || keyEvent.getKeyCode() != 66) {
                    return false;
                }
                PetTalkInput.this.submit();
                return true;
            }
        });
        // 【核心修复】原来只有 ACTION_DOWN 会重置计时，软键盘是另一个窗口、点它不算，
        //   于是「打满 15 秒」必然被收窗，已输入的文字一起丢。这里补 TextWatcher，
        //   每次内容变化都重新计时；再配合 scheduleAutoHide 的「有内容不排计时」，
        //   只要框里还有字就永远不会自动收起。
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }
            @Override
            public void afterTextChanged(android.text.Editable editable) {
                PetTalkInput.this.scheduleAutoHide();
            }
        });
        editText.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == 0) {
                    PetTalkInput.this.scheduleAutoHide();
                }
                return false;
            }
        });
        this.input = editText;
        bar.addView(editText, new LinearLayout.LayoutParams(0, -1, 1.0f));
        ImageView textView = new ImageView(this.ctx);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                PetTalkInput.this.submit();
            }
        });
        this.button = textView;
        UiKit.sendButton(textView, this.ctx);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UiKit.dp(this.ctx, 62.0f), -1);
        btnLp.leftMargin = UiKit.dp(this.ctx, 6.0f);
        bar.addView(textView, btnLp);
        // 输入法让位：键盘弹起时把整条栏顶到键盘上方（与 ChatWindow 同款处理）。
        bar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    bottom = windowInsets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    bottom = windowInsets.getSystemWindowInsetBottom();
                }
                PetTalkInput.this.onIme(bottom);
                return windowInsets;
            }
        });
        this.root = bar;
    }
    /**
     * 键盘高度变化：自己不摆位，转给宿主。
     * 【理由】迷你聊天是一组（人偶 + 输入框），只抬框不抬人偶就会上下分家。
     */
    private void onIme(int ime) {
        this.host.onIme(Math.max(0, ime));
    }
    private void submit() {
        if (this.input == null) {
            return;
        }
        String trim = this.input.getText().toString().trim();
        if (trim.isEmpty()) {
            return;
        }
        this.input.setText("");
        this.host.onSend(trim);
    }
    private void focus() {
        EditText editText = this.input;
        if (editText == null) {
            return;
        }
        // 【坑】不能紧跟 addView 同步抢焦点：那一刻窗口还没 attach 完，
        //   requestFocus 拿不到 focus、showSoftInput 也拿不到 window token，键盘就弹不出来。
        //   推到下一帧执行。
        editText.post(new Runnable() {
            @Override
            public void run() {
                PetTalkInput.this.focusNow();
            }
        });
    }

    private void focusNow() {
        EditText editText = this.input;
        if (editText == null) {
            return;
        }
        editText.requestFocus();
        try {
            InputMethodManager imm = (InputMethodManager) this.ctx.getSystemService("input_method");
            if (imm != null) {
                imm.showSoftInput(editText, 0);
            }
        } catch (Throwable unused) {
        }
    }
    private void hideIme() {
        EditText editText = this.input;
        if (editText == null) {
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager) this.ctx.getSystemService("input_method");
            if (imm != null) {
                imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
            }
        } catch (Throwable unused) {
        }
    }
    private void scheduleAutoHide() {
        this.ui.removeCallbacks(this.autoHide);
        EditText editText = this.input;
        // 【核心修复】有未发送的内容就不排自动收起：收起会把用户正在打的字一起吞掉。
        if (editText != null && editText.getText() != null && editText.getText().length() > 0) {
            return;
        }
        this.ui.postDelayed(this.autoHide, AUTO_HIDE_MS);
    }
    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
    }
}