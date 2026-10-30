package com.dollhouse.app.data

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.pet.AnimBaker

/** 默认模型预热：只补首屏和核心交互需要的三项，避免首启批量渲染阻塞输入。 */
object DefaultModelWarmup {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    @JvmStatic
    fun schedule(host: Activity) {
        val needed = arrayOf(DollAnim.SLOT_ALWAYS_ID, DollAnim.SLOT_DRAG_ID, DollAnim.SLOT_EDGE_ID).any {
            DollAnim.byId(host, DollStore.DEFAULT_ID, it)?.frames.isNullOrEmpty()
        }
        if (!needed) return
        val ticket = ++generation
        // 先让首帧、首段点击和窗口动画完成，再开始耗时烘帧。
        ui.postDelayed({ runNext(host, ticket, 0) }, 1500L)
    }

    @JvmStatic
    fun cancel() {
        generation++
    }

    private fun runNext(host: Activity, ticket: Int, index: Int, retries: Int = 0) {
        if (ticket != generation || host.isFinishing || host.isDestroyed) return
        if (host.hasWindowFocus().not()) {
            if (retries < 3) ui.postDelayed({ runNext(host, ticket, index, retries + 1) }, 800L)
            return
        }
        val ids = arrayOf(DollAnim.SLOT_ALWAYS_ID, DollAnim.SLOT_DRAG_ID, DollAnim.SLOT_EDGE_ID)
        if (index >= ids.size) return
        Thread({
            val ctx = host.applicationContext
            val id = DollStore.DEFAULT_ID
            val actionId = ids[index]
            val before = DollAnim.byId(ctx, id, actionId)
            if (before != null && before.builtin && before.frames.isEmpty()) {
                val scale = DollProfile.scale(ctx, id) / 100.0f
                val out = AnimBaker.ensure(ctx, id, actionId, scale)
                val latest = DollAnim.byId(ctx, id, actionId)
                if (out.isNotEmpty() && latest != null && latest.frames.isEmpty() &&
                    latest.assetPath == before.assetPath && latest.name == before.name && latest.fps == before.fps
                ) {
                    DollAnim.update(ctx, id, latest.copy(frames = out))
                }
            }
            ui.postDelayed({ runNext(host, ticket, index + 1) }, 800L)
        }, "dh-default-warmup-$index").start()
    }
}
