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

    /**
     * 【r20·安全】判断本次 Intent 的发起方是否可信。
     *
     * 【判定口径】满足任一条即为可信：
     *   ① 同一 UID —— 本应用自己发起的 Intent（含 adb `run-as`）；
     *   ② shell（2000）/ root（0）—— adb 调试与自动化平台的默认身份。
     *
     * 【为什么不用自定义 permission】那样 adb `am start` 也得先授权，破坏现有终端用法；
     *   而这里要挡的只是「普通第三方 App 悄悄滥用重活」，用调用者身份判断最直接、零配置。
     *
     * 【为什么 now 取不到就放行】部分 ROM 在 Activity 刚创建时调用者身份可能取不到
     *   （进程刚 fork 完、binder 身份尚未就绪，或老系统 `callingPackage` 为 null）。
     *   此时若一律拒绝，会让本来合法的 adb 用法变成「有时行有时不行」的玄学问题。
     *   取不到按「不拦」处理 —— 最坏情况退回到加固前的行为（可被滥用），但不会破坏正常使用。
     *   这是有意的取舍，不是遗漏。
     */
    private fun isTrustedCaller(): Boolean {
        return try {
            val caller = callerUid() ?: return true
            val myUid = android.os.Process.myUid()
            if (caller == myUid) {
                return true
            }
            // shell = 2000，root = 0：adb / Shizuku / root 自动化都落在这两个身份上。
            caller == 2000 || caller == 0
        } catch (t: Throwable) {
            // 任何异常都不拦（理由同上：宁可退回旧行为，也不要制造随机失败）。
            true
        }
    }

    /**
     * 【r20·兼容】取本次 Intent 的发起方 UID。
     *
     * 【为什么不能用一个属性了事】能直接给出「谁把我拉起来」的几个 API 全是分版本引入的：
     *   · `launchedFromUid`（API 34+）直接给 UID，最准；
     *   · `currentCaller`（API 29~33）返回的是一个 `ComponentCaller` **对象**，
     *     要做 binder 往返才拿到 UID，而且它本身就是 API 29 才有的；
     *   · `callingPackage`（API 1+）全版本都有，但 adb 场景下可能为 null。
     *   本工程 minSdk = 24，直接用高档 API 会在老系统上抛 `NoSuchMethodError`
     *   —— 那等于「为了防滥用，反而把老设备全部搞崩」。所以必须分档，且每档都有兜底。
     *
     * 取值顺序：
     *   ① API 34+：`launchedFromUid`（≥0 即可直接返回）；
     *   ② API 29+：`launchedFromPackage` / `callingPackage` → `PackageManager.getPackageUid`；
     *   ③ API 24~28：仅 `callingPackage`（adb 场景通常为 null）；
     *   ④ 都拿不到 → 返回 null，由上层按「不拦」处理。
     */
    private fun callerUid(): Int? {
        // 【第一档 API 34+】`launchedFromUid` 直接给出「是谁把我拉起来的」的 UID，
        //   最准确：adb `am start` 得到 2000(shell)，Shizuku 同样 2000，root 得到 0，
        //   第三方 App 得到它自己的 uid。这是唯一一个「一字不差」的答案。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                val uid = launchedFromUid
                if (uid >= 0) {
                    return uid
                }
            } catch (unused: Throwable) {
                // 极少数 ROM 上该属性可能抛异常，落到下一档。
            }
        }
        // 【第二档 API 29+】拿不到 UID 就退一步拿包名，再查 PackageManager 换算成 UID。
        //   `launchedFromPackage` 对 adb 场景返回 "com.android.shell"，同样能对上。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val pkg = launchedFromPackage ?: callingPackage ?: return null
                return packageManager.getPackageUid(pkg, 0)
            } catch (unused: Throwable) {
                return null
            }
        }
        // 【第三档 API 24~28】老系统只有 `callingPackage`，且 adb 场景下它是 null
        //   （老的 `am start` 不带 --caller 就没有调用者信息）。拿不到就返回 null，
        //   由上层按「不拦」处理 —— 行为与加固前一致，不会误伤正常用法。
        return try {
            val pkg = callingPackage ?: return null
            packageManager.getPackageUid(pkg, 0)
        } catch (unused: Throwable) {
            null
        }
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
        //
        // 【r20·安全收紧】本 Activity 是 exported="true"，任何第三方应用都能发这条 Intent。
        //   而烘帧是**写磁盘 + 长时间占 CPU** 的重活，且 `doll` 参数原来完全不校验 ——
        //   等于给外部开了一个「用别人的手机算我的活」的口子（耗电、发热、写满私有目录）。
        //   启停那三个动作（ON/OFF/TOGGLE）是产品明确要对外开放的能力，保持开放；
        //   烘帧不是，它是**开发者 / 终端工具**，只对「同一个 UID」或「shell / root」
        //   开放 —— 前者是本应用自己（含 adb run-as），后者是 adb 调试与自动化平台。
        //   普通第三方 App 走不到这里，避免被滥用。
        if (ACT_BAKE == action) {
            if (!isTrustedCaller()) {
                com.dollhouse.app.core.Logs.w(
                    "DollhouseBake", "拒绝来自外部应用的烘帧请求"
                )
                return
            }
            // 【参数校验】doll 必须是实际存在的人偶 id，避免被塞任意字符串去探测 / 刷目录。
            val raw = intent.getStringExtra("doll")
            val doll = if (raw.isNullOrEmpty()) {
                com.dollhouse.app.data.DollStore.DEFAULT_ID
            } else {
                raw
            }
            if (com.dollhouse.app.data.DollStore.byId(this, doll) == null) {
                com.dollhouse.app.core.Logs.w("DollhouseBake", "未知人偶 id，拒绝烘帧：" + doll)
                return
            }
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
