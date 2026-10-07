package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dollhouse.app.pet.PetToggle

/**
 * 【职责】外部链接的落地页：把 dollhouse://pet/{on|off|toggle} 或
 *         com.dollhouse.app.action.PET_{ON|OFF|TOGGLE} 翻译成一次桌宠启停。
 *
 * 【入口】浏览器、自动化软件、终端 am start 都能拉起；清单里 exported="true"。
 *
 * 【交互】翻译完立即 finish()，不显示任何界面；实际启停交给 PetToggle。
 *
 * 【坑】PetService 在清单里是 exported="false"，外部进程拿不到，
 *         所以必须由这个 Activity 中转；主题用 NoDisplay，
 *         Android 14 起要求这类 Activity 在 onCreate 内 finish，否则抛 IllegalStateException。
 */
class PetLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatch(intent)
        finish()
    }

    /** 按 Intent 内容决定 开 / 关 / 切换；信息不足时按「切换」处理。 */
    private fun dispatch(intent: Intent?) {
        if (intent == null) {
            return
        }
        val action = intent.action
        if (ACT_ON == action) {
            PetToggle.start(this)
            return
        }
        if (ACT_OFF == action) {
            PetToggle.stop(this)
            return
        }
        if (ACT_TOGGLE == action) {
            PetToggle.toggle(this)
            return
        }
        val data: Uri? = intent.data
        if (data != null && SCHEME == data.scheme) {
            val key = data.lastPathSegment
            if ("on".equals(key, ignoreCase = true) || "start".equals(key, ignoreCase = true)) {
                PetToggle.start(this)
                return
            }
            if ("off".equals(key, ignoreCase = true) || "stop".equals(key, ignoreCase = true)) {
                PetToggle.stop(this)
                return
            }
        }
        PetToggle.toggle(this)
    }

    companion object {
        /** 自定义 scheme：dollhouse://pet/on | off | toggle */
        internal const val SCHEME = "dollhouse"
        /** 显式动作形式：am start -a com.dollhouse.app.action.PET_TOGGLE */
        internal const val ACT_ON = "com.dollhouse.app.action.PET_ON"
        internal const val ACT_OFF = "com.dollhouse.app.action.PET_OFF"
        internal const val ACT_TOGGLE = "com.dollhouse.app.action.PET_TOGGLE"
    }
}
