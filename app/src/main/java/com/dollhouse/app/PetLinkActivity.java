package com.dollhouse.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 【职责】外部链接的落地页：把 dollhouse://pet/{on|off|toggle} 或
 *         com.dollhouse.app.action.PET_{ON|OFF|TOGGLE} 翻译成一次桌宠启停。
 *
 * 【入口】浏览器、自动化软件、终端 am start 都能拉起；清单里 exported="true"。
 *
 * 【交互】翻译完立即 finish()，不显示任何界面；实际启停交给 PetToggle。
 *
 * 【坑】PetService 在清单里是 exported="false"，外部进程拿不到，
 *        所以必须由这个 Activity 中转；主题用 NoDisplay，
 *        Android 14 起要求这类 Activity 在 onCreate 内 finish，否则抛 IllegalStateException。
 */
public class PetLinkActivity extends Activity {

    /** 自定义 scheme：dollhouse://pet/on | off | toggle */
    static final String SCHEME = "dollhouse";
    /** 显式动作形式：am start -a com.dollhouse.app.action.PET_TOGGLE */
    static final String ACT_ON = "com.dollhouse.app.action.PET_ON";
    static final String ACT_OFF = "com.dollhouse.app.action.PET_OFF";
    static final String ACT_TOGGLE = "com.dollhouse.app.action.PET_TOGGLE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dispatch(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dispatch(intent);
        finish();
    }

    /** 按 Intent 内容决定 开 / 关 / 切换；信息不足时按「切换」处理。 */
    private void dispatch(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACT_ON.equals(action)) {
            PetToggle.start(this);
            return;
        }
        if (ACT_OFF.equals(action)) {
            PetToggle.stop(this);
            return;
        }
        if (ACT_TOGGLE.equals(action)) {
            PetToggle.toggle(this);
            return;
        }
        Uri data = intent.getData();
        if (data != null && SCHEME.equals(data.getScheme())) {
            String key = data.getLastPathSegment();
            if ("on".equalsIgnoreCase(key) || "start".equalsIgnoreCase(key)) {
                PetToggle.start(this);
                return;
            }
            if ("off".equalsIgnoreCase(key) || "stop".equalsIgnoreCase(key)) {
                PetToggle.stop(this);
                return;
            }
        }
        PetToggle.toggle(this);
    }
}
