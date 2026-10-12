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

    /**
     * 【r13】后台把某人偶的全部内置动作帧图烘出来（自检 / 预生成）。
     *
     * 【为什么后台】烘 10 个动作、合计 152 帧，主线程做会 ANR；
     *   [AnimBaker.ensure] 内部会把真正的渲染切回主线程，这里只负责排队与日志。
     * 【为什么只烘内置动作】用户导入的动作本来就已经是图片文件，不需要生成。
     * 【产出】放 app 私有目录，日志打「帧数」，便于终端侧核对。
     */
    private fun bakeAsync(dollId: String) {
        val app = applicationContext
        Thread({
            var ok = 0
            var fail = 0
            var frames = 0
            try {
                val scale = com.dollhouse.app.data.PetPrefs.petScale(app) / 100.0f
                for (p in com.dollhouse.app.data.DollAnim.PLANS) {
                    val out = com.dollhouse.app.pet.AnimBaker.ensure(app, dollId, p.id, scale)
                    if (out.size >= 2) {
                        ok++
                        frames += out.size
                        // 把路径登记进动作元数据：进动作页时直接可播，不再现烘。
                        val cur = com.dollhouse.app.data.DollAnim.byId(app, dollId, p.id)
                        if (cur != null && cur.frames.size != out.size) {
                            com.dollhouse.app.data.DollAnim.update(app, dollId, cur.copy(frames = out))
                        }
                    } else {
                        fail++
                    }
                }
            } catch (t: Throwable) {
                com.dollhouse.app.core.Logs.w("DollhouseBake", "bakeAsync failed", t)
            }
            com.dollhouse.app.core.Logs.i(
                "DollhouseBake",
                "BAKE_DONE doll=" + dollId + " ok=" + ok + " fail=" + fail + " frames=" + frames
            )
        }, "dh-bake-all").start()
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
        // 【r13】动画帧烘焙自检：把某人偶的全部内置动作逐帧烘成 PNG。
        //   【为什么做成外部指令而不是藏个按钮】工程本来就有 dollhouse://pet/* 这一族
        //   外部指令；帧烘焙是「一次性预生成」的重活，适合由终端 / 自动化触发，
        //   既方便排错，也让用户能在装机后立刻把帧图备好（不必先进每个动作页等一遍）。
        //   【用法】am start -a com.dollhouse.app.action.BAKE_ANIM --es doll default
        if (ACT_BAKE == action) {
            val doll = intent.getStringExtra("doll") ?: com.dollhouse.app.data.DollStore.DEFAULT_ID
            bakeAsync(doll)
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

        /**
         * 【r13】帧烘焙自检 / 预生成：
         * `am start -a com.dollhouse.app.action.BAKE_ANIM --es doll <dollId>`
         */
        internal const val ACT_BAKE = "com.dollhouse.app.action.BAKE_ANIM"
    }
}
