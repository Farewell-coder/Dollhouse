package com.dollhouse.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build

/**
 * 普通 Activity 页面共用的淡入淡出；不改变 Activity 的返回和生命周期。
 *
 * 【时长对齐设计阶梯】入场 `page_fade_in` = 220ms（Motion.enter）、
 *   退场 `page_fade_out` = 180ms（Motion.exit）。
 *   改造前两者都是 200ms —— 而 Compose 覆盖页那边早就改成 220/180 了，
 *   于是「同一个 App 里两套转场快慢不同」：从聊天列表进设置（Compose 覆盖页）220ms，
 *   从桌面图示进聊天页（独立 Activity）200ms，用户能感觉到细微差别。
 */
internal object PageTransitions {
    fun start(activity: Activity, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
            return
        }
        if (Build.VERSION.SDK_INT >= 34) {
            activity.startActivity(
                intent,
                ActivityOptions.makeCustomAnimation(
                    activity, R.anim.page_fade_in, R.anim.page_fade_out
                ).toBundle()
            )
        } else {
            activity.startActivity(intent)
            activity.overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
        }
    }

    fun finish(activity: Activity) {
        // 【为什么不直接写 activity.finish()】若调用方正是一个覆盖了 finish() 的 Activity，
        //   这里会虚分派回它的覆盖实现，而覆盖实现通常又转调本方法 —— 无限递归 → StackOverflowError
        //   （BackgroundCropActivity / CoverCropActivity 就是被这个写法炸掉的）。
        //   覆盖 finish() 的页面必须自己调 super.finish()，不能转调本方法；
        //   这里保留仅为普通页面提供统一转场。
        activity.finish()
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            activity.overridePendingTransition(0, 0)
        } else {
            activity.overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
        }
    }
}
