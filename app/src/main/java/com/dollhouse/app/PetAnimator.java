package com.dollhouse.app;

import android.os.SystemClock;

/**
 * 【职责】桌宠的动画推进：呼吸相位、眨眼、待机小动作、跳跃、落地、拖拽倾斜、视线跟随、表情计时。
 *
 * 【入口】由 PetView 构造时创建（new PetAnimator(this)），PetView 的公开方法全部转发到本类；
 *         每帧由 PetView.frame 这个 Runnable 调 tick() 驱动。
 *
 * 【交互】动画状态字段仍由 PetView 持有（包级可见），本类只读写不持有——
 *         这样 PetView 的绘制代码（buildMesh / onDraw / drawEmote）不用改一行；
 *         invalidate / postDelayed / removeCallbacks / dp 全部回调 host。
 *
 * 【扩展】新增待机动作：加一个 IDLE_* 常量 + 在 startIdleAction 里排时长 + 在 tickIdle 里做形变；
 *         新增表情：在 PetView 加 EXPR_* 常量 + 在 emoteColor / emoteGlyph 里配色与字形。
 *
 * 【坑】tick() 里的 delta 上限是 120ms（防止后台切回来一次跳太多）；
 *       动画状态字段不能加 private，否则 PetView 绘制会编译不过。
 */
final class PetAnimator {
    private final PetView host;
    /**
     * 趴姿（peek）下眨眼间隔的下界（秒）。
     * 【v2.10.2 修】原实现这里写的是 PetView.DRAG_TILT_MAX —— 那是个拖拽倾角常量（8.0f），
     *   语义完全不对，只是数值碰巧能用；现收口成语义正确的独立常量，值保持 8.0f 不变，
     *   以免改动趴姿下的眨眼频率（保功能优先）。
     */
    private static final float PEEK_BLINK_MIN_SEC = 8.0f;
    /** 站立待机时眨眼间隔的下界（秒）。 */
    private static final float BLINK_MIN_SEC = 3.0f;
    PetAnimator(PetView host) {
        this.host = host;
    }
    private float nextBlinkDelay() {
        // 【v2.10.2】原两个分支的随机项一模一样，合并；只差下界。
        return ((float) Math.random()) * 4.0f + (host.peek ? PEEK_BLINK_MIN_SEC : BLINK_MIN_SEC);
    }
    public void startAnim() {
        if (host.animating) {
            return;
        }
        host.animating = true;
        host.lastFrame = SystemClock.uptimeMillis();
        host.postDelayed(host.frame, 16L);
    }

    public void stopAnim() {
        host.animating = false;
        host.removeCallbacks(host.frame);
    }

    public void playJump() {
        host.jumpT = 0.0f;
        host.blinkT = 0.0f;
        setExpression(2, 1100L);
    }

    public void setTilt(float f) {
        host.tilt = f;
        host.invalidate();
    }

    private void startIdleAction() {
        int random;
        do {
            random = ((int) (Math.random() * 5.0d)) + 1;
            if (random != host.lastIdleAction) {
                break;
            }
        } while (Math.random() < 0.7d);
        host.lastIdleAction = random;
        host.idleAction = random;
        host.idleT = 0.0f;
        if (random == 1) {
            host.idleDuration = 1.5f;
            return;
        }
        if (random == 2) {
            host.idleDuration = 2.8f;
            return;
        }
        if (random == 3) {
            host.idleDuration = 1.8f;
        } else if (random == PetView.IDLE_NOD) {
            host.idleDuration = 1.2f;
        } else {
            host.idleDuration = 0.8f;
        }
    }

    public void pokeIdle(float f) {
        host.idleNextIn = Math.min(host.idleNextIn, f);
    }

    private void tickIdle(float f) {
        host.idleScaleY = 1.0f;
        host.idleLiftPx = 0.0f;
        host.idleEyeScale = 1.0f;
        host.idleTilt = 0.0f;
        float f2 = host.idleT;
        if (f2 >= 0.0f) {
            float f3 = f2 + (f / host.idleDuration);
            host.idleT = f3;
            if (f3 >= 1.0f) {
                host.idleT = -1.0f;
                host.idleAction = 0;
                host.idleNextIn = (((float) Math.random()) * 17.0f) + 13.0f;
                return;
            }
            float sin = (float) Math.sin(f3 * 3.141592653589793d);
            int i = host.idleAction;
            if (i == 1) {
                host.idleScaleY = (0.05f * sin) + 1.0f;
                host.idleLiftPx = host.dp(6.0f) * sin;
                return;
            }
            if (i == 2) {
                host.gazeTarget = (float) Math.sin(host.idleT * 3.141592653589793d * 2.0d);
                host.gazeCountdown = 1.2f;
                return;
            } else if (i == 3) {
                host.idleEyeScale = 1.0f - (sin * PetView.EYE_V);
                return;
            } else if (i == PetView.IDLE_NOD) {
                host.idleScaleY = 1.0f - (((float) Math.sin((host.idleT * 3.141592653589793d) * 4.0d)) * 0.05f);
                return;
            } else {
                if (i != PetView.IDLE_HOP) {
                    return;
                }
                host.idleLiftPx = host.dp(16.0f) * sin;
                return;
            }
        }
        if (host.dragging || host.peek || host.lean != 0.0f) {
            return;
        }
        float f4 = host.idleNextIn - f;
        host.idleNextIn = f4;
        if (f4 <= 0.0f) {
            startIdleAction();
        }
    }

    public void setExpression(int i, long j) {
        host.expression = i;
        host.exprStartAt = SystemClock.uptimeMillis();
        host.exprDuration = Math.max(300L, j);
        host.invalidate();
    }

    public void clearExpression() {
        host.expression = 0;
    }

    public void setDragging(boolean z) {
        if (host.dragging == z) {
            return;
        }
        host.dragging = z;
        if (z) {
            setExpression(1, 900L);
        } else {
            host.landT = 0.0f;
            setExpression(3, 1100L);
        }
        host.invalidate();
    }

    public void setDragVelocity(float f) {
        host.dragVx = f;
    }

    public void lookAt(float f) {
        host.gazeTarget = Math.max(-1.0f, Math.min(1.0f, f));
        host.gazeCountdown = 1.6f;
    }

    public void setAffection(int i) {
        host.affection = PetPrefs.clampAffection(i);
        host.invalidate();
    }

    public void tick() {
        long uptimeMillis = SystemClock.uptimeMillis();
        float min = Math.min(120.0f, uptimeMillis - host.lastFrame) / 1000.0f;
        host.lastFrame = uptimeMillis;
        float f = host.phase + (1.7f * min);
        host.phase = f;
        if (f > 6.2831855f) {
            host.phase = f - 6.2831855f;
        }
        float f2 = host.jumpT;
        if (f2 >= 0.0f) {
            float f3 = f2 + (min / 0.42f);
            host.jumpT = f3;
            if (f3 >= 1.0f) {
                host.jumpT = -1.0f;
            }
        }
        float f4 = host.dragging ? 1.0f : 0.0f;
        float f5 = host.dragLift;
        host.dragLift = f5 + ((f4 - f5) * Math.min(1.0f, 11.0f * min));
        float max = host.dragging ? Math.max(-8.0f, Math.min(PetView.DRAG_TILT_MAX, (-host.dragVx) * 0.014f)) : 0.0f;
        float f6 = host.tilt;
        host.tilt = f6 + ((max - f6) * Math.min(1.0f, 9.0f * min));
        float f7 = host.landT;
        if (f7 >= 0.0f) {
            float f8 = f7 + (min / 0.38f);
            host.landT = f8;
            if (f8 >= 1.0f) {
                host.landT = -1.0f;
            }
        }
        if (host.expression != 0 && SystemClock.uptimeMillis() - host.exprStartAt > host.exprDuration) {
            host.expression = 0;
        }
        float f9 = host.blinkT;
        // 【v2.10.2】贴边偷看时不眨眼：眨眼是唯一会周期性把 busy 判定顶回 60fps 的自动动作，
        //   压掉它（以及 tickIdle 的待机小动作），贴边态才能稳定停在 EDGE_IDLE_MS 那一档。
        if (host.lean != 0.0f) {
            if (f9 >= 0.0f) {
                host.blinkT = -1.0f;
            }
        } else if (f9 >= 0.0f) {
            float f10 = f9 + (min / 0.14f);
            host.blinkT = f10;
            if (f10 >= 1.0f) {
                host.blinkT = -1.0f;
                host.blinkCountdown = nextBlinkDelay();
            }
        } else {
            float f11 = host.blinkCountdown - min;
            host.blinkCountdown = f11;
            if (f11 <= 0.0f) {
                host.blinkT = 0.0f;
            }
        }
        tickIdle(min);
        if (host.idleAction != 2) {
            float f12 = host.gazeCountdown - min;
            host.gazeCountdown = f12;
            if (f12 <= 0.0f) {
                int i = host.affection;
                host.gazeTarget = ((float) ((Math.random() * 2.0d) - 1.0d)) * (i >= 70 ? 0.35f : i < 30 ? 1.0f : PetView.TAILR_V);
                host.gazeCountdown = (((float) Math.random()) * 6.0f) + 4.0f;
            }
        }
        float f13 = host.gaze;
        host.gaze = f13 + ((host.gazeTarget - f13) * Math.min(1.0f, min * 3.5f));
        host.invalidate();
    }
}
