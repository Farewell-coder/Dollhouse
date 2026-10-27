package com.dollhouse.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build

/** 普通 Activity 页面共用 200ms 淡入淡出；不改变 Activity 的返回和生命周期。 */
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
        activity.finish()
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            activity.overridePendingTransition(0, 0)
        } else {
            activity.overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
        }
    }
}
