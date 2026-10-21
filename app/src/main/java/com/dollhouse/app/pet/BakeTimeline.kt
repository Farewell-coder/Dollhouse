package com.dollhouse.app.pet

import com.dollhouse.app.data.DollAnim

/**
 * 【职责】内置动作的**逐帧时间线** —— 每个动作第 i 帧该用哪一组烘帧参数。
 *
 * ## 为什么单独抽出来
 *
 * 这段逻辑原先在 `AnimBaker.timeline()` 里，是 `private` 且和 Android 类同处一个文件。
 * 后果是**它没法被 JVM 单测覆盖**：想验证「贴边动作的帧里到底有没有倾斜」，
 * 只能装到真机上看，或者靠人肉读代码。而这里恰恰出过两次同类事故：
 *
 * 1. `IDX_EYE_SCALE` 漏赋 1 → 每一帧的眼睛都被压扁（不崩、不报错，只是「哪里怪怪的」）；
 * 2. `KIND_PEEK` 把 `EDGE_LEAN × sin(πt)` 画进帧里 → 循环播成「站直 → 歪下 → 站直」，
 *    也就是用户报的「反复进出贴边」。
 *
 * 两件都属于「**能编译、能播、但语义错**」，只有断言才挡得住。所以把纯计算搬到这里：
 * 不碰 `Bitmap` / `Canvas` / `Context`，输入 `(kind, i, n)`、输出写进 `FloatArray`。
 *
 * ## 契约
 * - 一律先 `BakeParams.reset(v)`：保证每一位都是**语义中性值**
 *   （`jumpT`/`landT` = −1、`idleScaleY`/`idleEyeScale` = 1、其余 0）。
 *   这样「补了其中一位、漏了同性质另一位」在结构上不可能再发生。
 * - `t = i / (n - 1)`，**首末帧恰好是 0 与 1**。循环动作必须让 `t=0` 与 `t=1`
 *   的参数一致（用整轮相位、不要用 `sin(πt)` 这种首末为 0 的包络），否则循环处会跳。
 */
object BakeTimeline {

    /**
     * 生成第 [i] 帧的时间线参数（共 [n] 帧），写入 [v]（长度须为 [BakeParams.SIZE]）。
     *
     * @param kind    动作类型，取 `DollAnim.KIND_*`
     * @param eyeV    眯眼深度（`PetView.EYE_V`）。做成参数是为了让本对象**不引用 PetView**：
     *                它是 Android `View` 的子类，JVM 单测一创建类就会炸（Stub!）。
     *                默认值由调用方传入真值，测试可传任意值。
     * @param edgeLean 贴边倾角（`PetView.EDGE_LEAN`），同理做成参数。
     */
    fun fill(
        kind: String,
        i: Int,
        n: Int,
        v: FloatArray,
        eyeV: Float = 0.45f,
        edgeLean: Float = 25.0f
    ) {
        BakeParams.reset(v)
        val t = if (n <= 1) 0.0f else i / (n - 1).toFloat()
        val sinPi = Math.sin(t * Math.PI).toFloat()
        val sin2Pi = Math.sin(t * Math.PI * 2.0).toFloat()
        val sin4Pi = Math.sin(t * Math.PI * 4.0).toFloat()
        when (kind) {
            // ---------- 常驻动画 ----------
            DollAnim.KIND_BREATH -> v[0] = t * 6.2831855f
            DollAnim.KIND_BLINK -> {
                // 把 BlinkTimeline 的整轮映射到 n 帧上。
                val ms = t * BlinkTimeline.TOTAL_MS
                val p = BlinkTimeline.phaseAt(ms)
                v[9] = if (p < 0.0f) 0.0f else p
            }
            // 视线漂移：gaze 是网格里的 GAZE_AMP 横向位移，推两轮才看得出在「飘」。
            DollAnim.KIND_GAZE -> {
                v[11] = sin2Pi
                v[0] = t * 6.2831855f
            }
            // 尾巴摆动：buildMesh 里尾巴项按 phase*2.6 的 sin 摆动 ——
            //   给足相位跨度（两轮）才能看到「左右荡」。
            DollAnim.KIND_TAIL -> v[0] = t * 6.2831855f * 2.0f
            // 呆毛摆动：buildMesh 里呆毛项按 phase*3.4 的 sin 晃，比尾巴快。
            DollAnim.KIND_AHOGE -> v[0] = t * 6.2831855f * 3.0f
            // 头发波动：buildMesh 里头发项按 phase*1.6 的 sin，且是细密波 ——
            //   这里再叠一点纵向起伏，肉眼看得出「一片在动」。
            DollAnim.KIND_HAIR -> {
                v[0] = t * 6.2831855f * 3.0f
                v[4] = 1.0f + 0.012f * sin2Pi
            }

            // ---------- 待机小动作 ----------
            DollAnim.KIND_STRETCH -> {
                v[4] = 1.0f + 0.05f * sinPi
                v[3] = 1.0f * sinPi
            }
            DollAnim.KIND_LOOK -> {
                v[11] = sin2Pi
                v[9] = 0.0f
            }
            // 眯眼：idleEyeScale 直接进网格的 eyeQ（PetAnimator 的 3 号分支正是这个算法），
            //   眼睛会被压扁成一条缝 —— 语义与「眯眼」一致。
            DollAnim.KIND_SQUINT -> v[10] = 1.0f - (sinPi * eyeV)
            DollAnim.KIND_NOD -> v[4] = 1.0f - (sin4Pi * 0.05f)
            DollAnim.KIND_HOP -> v[3] = 1.0f * sinPi

            // ---------- 交互动作 ----------
            DollAnim.KIND_JUMP -> v[1] = t
            // 拖拽：dragLift 驱动「抬高」；再叠一点倾斜，像被拎着晃。
            DollAnim.KIND_DRAG -> {
                v[2] = 1.0f
                v[7] = sin2Pi * 6.0f
            }
            // 落地：landT 驱动「压扁」，onDraw 用 sin(πt)·AHOGE_RU。
            DollAnim.KIND_LAND -> v[5] = t

            // ---------- 贴边（固定壳 2：悬在屏幕边时循环）----------
            //
            // 【帧里不画倾斜 —— 这条是语义契约，不是风格选择】
            //   `PetView.onDraw` 在「有动作在播」时是：
            //       if (act != null && act.hasContent()) { act.draw(canvas); return }
            //   这一句在 `mesh()` / `PetTransform` 之前，所以 `lean` 只影响
            //   **几何包围盒（窗口宽高）**，从不会作用到动作画面本身。
            //   也就是说「帧里不画，系统会帮忙歪」并不成立 —— 帧里不画就是笔直的。
            //   姿态现在由 `ActionPlayerCore.leanProvider` 按 `lean` 旋转整幅画面。
            //
            // 【为什么要断言 IDX_LEAN 恒为 0】早期这里写的是
            //   `v[6] = EDGE_LEAN * sin(πt)`：首末帧直立、中间才歪，
            //   循环播放就成了「站直 → 歪下 → 站直」（用户报的「反复进出贴边」）。
            //   若将来又有人往这里写倾斜，会与播放层的旋转叠成 50°。
            //
            // 【留什么】只留悬停时的生命感：整轮呼吸 + 中段一次窄脉冲眨眼。
            DollAnim.KIND_PEEK -> {
                v[BakeParams.IDX_PHASE] = t * 6.2831855f
                // 中段眨一次：sin^8 造很窄的脉冲，只在 t≈0.5 附近生效，
                //   其余帧完全不受影响 —— 像「偶尔眨一下」，不是周期性抽搐。
                val pulse = Math.pow(Math.sin(t * Math.PI.toDouble()), 8.0).toFloat()
                v[BakeParams.IDX_BLINK] = if (pulse > 0.35f) {
                    BlinkTimeline.phaseAt((pulse - 0.35f) / 0.65f * BlinkTimeline.TOTAL_MS)
                } else {
                    -1f
                }
            }
            // 趴姿：走的是另一条路 —— `enterPeek` 里调 `setEdgePeek(false,false)`，
            //   `lean` 为 0，所以倾斜**应该**画进帧里。与贴边不是一回事，别一起改。
            DollAnim.KIND_PERCH -> {
                v[6] = edgeLean * 0.5f * sinPi
                v[7] = 8.0f * sinPi
                v[10] = 1.0f - 0.5f * sinPi
            }

            // ---------- 表情（头顶冒符号）----------
            // 【为什么表情也要给点身体动】原来三个表情只设了 v[0]（呼吸相位），
            //   11 帧肉眼几乎一样 —— 用户报的「1-4 帧缩略图看起来完全相同」。
            //   现在每个表情都叠一个**自己特有的**身体反应，缩略图一眼能分辨。
            DollAnim.KIND_SURPRISED -> {
                // 惊讶：先弹一下（跳起来一点）再落回。
                v[0] = t * 1.2f
                v[3] = 1.0f * sinPi
                v[4] = 1.0f + 0.06f * sinPi
            }
            DollAnim.KIND_HAPPY -> {
                // 开心：连续小弹跳（两轮），像蹦跶。
                v[0] = t * 1.2f
                v[3] = Math.abs(sin2Pi) * 1.0f
            }
            DollAnim.KIND_DIZZY -> {
                // 迷糊：身体左右打晃 + 轻微倾斜，正是「晕」的样子。
                v[0] = t * 1.2f
                v[7] = sin2Pi * 9.0f
                v[6] = sin2Pi * 5.0f
            }
        }
    }
}
