package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit

/**
 * 【职责】设置页「lamda 设备控制」页：安装 / 启动 / 停止服务，并说明 AI 能拿它做什么。
 *
 * 【为什么单开一页】安装包 204MB、要下载、要解包、状态要刷新，塞进设置卡片会把卡片撑成一屏。
 *        卡片下只留一个入口行，动手的事全在这页里。
 *
 * 【口径】本页不直接碰 Shizuku / 网络，全部经 LamdaManager；UI 只负责触发与显示结果。
 *
 * 【坑】所有耗时动作（下载 / 解包 / 启停）都必须丢到 worker 线程，结果经 Handler 回主线程刷新，
 *        否则解包期间的阻塞会把界面冻住。
 */
object LamdaPage {

    fun build(act: Activity): View {
        val ctx: Context = act
        val root = ApiPageKit.pageRoot(ctx)
        root.addView(UiKit.topBar(ctx, "lamda 设备控制", "让 AI 能操作手机（shell 级命令通道）", View.OnClickListener {
            ProviderNav.handleBack(ctx)
        }))
        val host = ApiPageKit.contentHost(ctx)
        root.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))
        paint(ctx, host)
        // 首次进入时异步探一次状态，避免用陈旧状态渲染。
        refreshStatus(host)
        return root
    }

    /** 整页重画（状态变化后重建，与 BehaviorPage 同风格）。 */
    private fun paint(ctx: Context, host: LinearLayout) {
        host.removeAllViews()

        val box = ApiPageKit.card(ctx)
        box.addView(ApiPageKit.sectionTitle(ctx, "服务状态"))
        val status = TextView(ctx)
        status.tag = "lamda_status"
        status.text = "正在检查…"
        status.setTextSize(UiKit.FS_BTN)
        status.setTextColor(UiKit.TITLE)
        status.setPadding(0, ApiPageKit.dp(ctx, 10), 0, ApiPageKit.dp(ctx, 6))
        // 【图标语义】探活未回来之前先挂一枚 spinner，让「等一下就有结果」这件事在界面成立；
        //   结果一到 setStatus 会把它换成勾 / 叉。
        Icons.stateIcon(status, Icons.IC_SPINNER, UiKit.SUB, 14.0f, 5)
        box.addView(status)
        val busyNow = LamdaManager.busyAction()
        if (busyNow != null) {
            box.addView(ApiPageKit.note(ctx, "正在" + busyLabel(busyNow) + "中，等它跑完再操作，"
                + "重复点容易把文件写坏。"))
        }
        host.addView(box)

        // 【为什么先算这几个态】按钮的文案与可用性都取决于「文件在不在、有没有动作在跑」；
        //   提前合成一个快照，避免在闭包里各算各的导致互相矛盾。
        // 【下载的第一重防范就在这里】本地有文件 → 按钮直接不可点，用户点不到就不会吃拒绝。
        val hasFile = LamdaManager.hasFile(ctx)
        val idle = busyNow == null

        val act1 = ApiPageKit.card(ctx)
        act1.addView(ApiPageKit.sectionTitle(ctx, "安装"))
        // 下载：有文件就置灰。理由见 LamdaManager.download —— 不静默覆盖、不静默续传。
        actRow(ctx, act1, "下载服务器包（约 204 MB）", idle && !hasFile, Runnable {
            download(ctx, host)
        })
        if (hasFile) {
            act1.addView(ApiPageKit.note(ctx, LamdaManager.downloadRefusedText(ctx) + "。"))
        }
        // 解包：重复解包由 LamdaManager.install 自己拦（已装过且包没变就直接回执），
        //  所以这里不弹确认 —— 手感上点一下立刻有反馈，比多一次弹窗更好。
        actRow(ctx, act1, "解包安装", idle, Runnable {
            runAsync(ctx, host, "准备解包…", object : Job {
                override fun run(): String {
                    val r = LamdaManager.install(ctx)
                    // 装好了就清掉静默期，让自动保活立刻接管（否则要等静默期走完才发现）。
                    if (r != null && r.contains("完成")) {
                        LamdaManager.resumeGuard()
                    }
                    return r
                }
            })
        })
        act1.addView(ApiPageKit.note(ctx, "服务器包不随 App 分发，首次使用需下载一次。"
            + "解包到 /data/local/tmp/server，全程以 shell 身份执行，不需要 root。"))
        host.addView(act1)

        val act2 = ApiPageKit.card(ctx)
        act2.addView(ApiPageKit.sectionTitle(ctx, "运行"))
        actRow(ctx, act2, "启动服务", idle, Runnable {
            runAsync(ctx, host, "正在启动…", object : Job {
                override fun run(): String {
                    // 手动启动视为用户明确要它开着，清掉静默期，恢复自动保活。
                    LamdaManager.resumeGuard()
                    return LamdaManager.start()
                }
            })
        })
        actRow(ctx, act2, "停止服务", idle, Runnable {
            runAsync(ctx, host, "正在停止…", object : Job {
                override fun run(): String {
                    // 【为什么先静默】开着自动保活时点「停止」，一秒后守护就会把它拉回来，
                    //   用户看到的是「停不掉」。进入静默期，尊重这最后一次手动操作。
                    LamdaManager.suppressGuard()
                    return LamdaManager.stop()
                }
            })
        })
        val auto = ModelEditKit.switchRow(ctx, act2, "自动保活",
            "服务掉了或手机重启后，自动把它拉回来",
            PetPrefs.lamdaAutoStart(ctx))
        auto.setOnClickListener {
            val now = !auto.isOn()
            auto.setOn(now, true)
            PetPrefs.setLamdaAutoStart(ctx, now)
            if (now) {
                // 用户刚开就希望它马上可用，别让他等下一轮巡检。
                LamdaManager.resumeGuard()
                runAsync(ctx, host, "正在检查服务…", object : Job {
                    override fun run(): String {
                        if (LamdaManager.probe()) {
                            return "服务运行中"
                        }
                        return if (LamdaManager.installed(ctx))
                            LamdaManager.start()
                        else
                            "（还没解包安装服务器包）"
                    }
                })
            }
        }
        act2.addView(ApiPageKit.note(ctx, "服务在本机 65000 端口提供 HTTP 与 MCP 接口。"
            + "开机后需要重新启动一次；打开上面的开关后，"
            + "只要小肥鱼在屏幕上（桌宠在运行），她会每隔一分钟看一眼服务，掉了就拉回来。"))
        host.addView(act2)

        // ---------------- 删除 ----------------
        val act3 = ApiPageKit.card(ctx)
        act3.addView(ApiPageKit.sectionTitle(ctx, "删除"))
        actRow(ctx, act3, "删除已安装的服务与下载的包", idle, Runnable {
            confirmDelete(ctx, host)
        })
        act3.addView(ApiPageKit.note(ctx, "会先停掉服务，再删掉 /data/local/tmp 下解包出来的文件，"
            + "最后删掉下载的服务器包（约 204 MB）。删完想再用，需要重新下载并解包。"))
        host.addView(act3)

        val box3 = ApiPageKit.card(ctx)
        val aiTitle = ApiPageKit.sectionTitle(ctx, "AI 能做什么")
        // 【图标语义】这一组讲的是「命令通道所以能做这些」——挂终端图标，与页面标题的
        //   「设备控制」呼应，用户一眼能看出这组讲的是 shell 能力而不是聊天能力。
        Icons.stateIcon(aiTitle, Icons.IC_TERMINAL, UiKit.SUB, 13.0f, 5)
        box3.addView(aiTitle)
        box3.addView(ApiPageKit.note(ctx, "服务启动后，小肥鱼会多出一组设备操作能力："
            + "打开 / 关闭某个应用、点按与长按、滑动、返回与回主页、唤醒屏幕、"
            + "读写剪贴板、输入文字，以及读取当前界面结构来判断自己该点哪里。"
            + "服务没启动时，这组能力不会下发给模型，聊天与其他功能不受影响。"))
        host.addView(box3)
    }

    /**
     * 动作行：统一处理「忙 / 条件不满足时置灰」。
     *
     * @param enabled false = 本行不可点并降低不透明度
     *                  （有别的动作在跑，或本动作的前置条件不满足，例如本地已有文件时的下载）
     */
    private fun actRow(ctx: Context, dest: LinearLayout, name: String,
                       enabled: Boolean, action: Runnable) {
        // 【为什么传 null 回调】ApiPageKit.row 的 cb 为 null 时不会设 clickable / 按压反馈，
        //   正好是「禁用」该有的样子；再叠一个 alpha 让灰得更明确，不用改公共组件。
        val cb: View.OnClickListener? = if (enabled) View.OnClickListener {
            action.run()
        } else null
        val r = ApiPageKit.row(ctx, name, "\u203a", cb)
        if (!enabled) {
            r.alpha = 0.4f
        }
        dest.addView(r)
    }

    /** 删除走确认；确认后异步执行。 */
    private fun confirmDelete(ctx: Context, host: LinearLayout) {
        val act = ApiPageKit.findActivity(ctx) ?: return
        val msg = TextView(ctx)
        msg.text = "会先停掉 lamda 服务，然后删除 /data/local/tmp 下解包出来的文件" +
            "和下载的服务器包（约 204 MB）。\n这个操作不能撤销，删完想再用需要重新下载并解包。"
        msg.setTextSize(UiKit.FS_TINY)
        msg.setTextColor(UiKit.TITLE)
        msg.setLineSpacing(ApiPageKit.dp(ctx, 3).toFloat(), 1.0f)
        UiKit.showDialog(act, "删除 lamda？", msg, "删除", View.OnClickListener {
            runAsync(ctx, host, "正在删除…", object : Job {
                override fun run(): String {
                    return LamdaManager.deleteAll(ctx)
                }
            })
        }, "取消", null)
    }

    /** 动作名的中文说法。 */
    private fun busyLabel(action: String): String {
        if ("download" == action) {
            return "下载"
        }
        if ("install" == action) {
            return "解包"
        }
        if ("start" == action) {
            return "启动"
        }
        if ("stop" == action) {
            return "停止"
        }
        if ("delete" == action) {
            return "删除"
        }
        return action
    }

    /* ------------------------------ 交互 ------------------------------ */

    /** 异步任务：把阻塞动作挪出主线程。 */
    private interface Job {
        fun run(): String
    }

    private fun runAsync(ctx: Context, host: LinearLayout, busy: String, job: Job) {
        setStatus(host, busy, UiKit.SUB)
        Thread(Runnable {
            val detail: String = try {
                job.run()
            } catch (t: Throwable) {
                "（执行失败：" + t.message + "）"
            }
            val fdetail = detail
            Handler(Looper.getMainLooper()).post {
                paint(ctx, host)
                // 【状态色】执行结果按成败上色，失败是红、其余保持中性灰。
                setStatus(host, fdetail, if (isFailure(fdetail)) UiKit.ERR else UiKit.SUB)
                refreshStatus(host)
            }
        }, "lamda-act").start()
    }

    /** 结果文案是否表示失败（runAsync 的兜底文案以「执行失败」开头）。 */
    private fun isFailure(s: String?): Boolean {
        return s != null && s.contains("执行失败")
    }

    private fun download(ctx: Context, host: LinearLayout) {
        setStatus(host, "开始下载…", UiKit.SUB)
        LamdaManager.download(ctx, object : LamdaManager.Progress {
            override fun onProgress(done: Long, total: Long) {
                Handler(Looper.getMainLooper()).post {
                    setStatus(host, "下载中 " + (done / 1048576L) + " / "
                        + (total / 1048576L) + " MB", UiKit.SUB)
                }
            }

            override fun onDone(ok: Boolean, detail: String?) {
                Handler(Looper.getMainLooper()).post {
                    setStatus(host, detail ?: "", if (ok) UiKit.SUB else UiKit.ERR)
                    refreshStatus(host)
                }
            }
        })
    }

    /** 异步探活 + 安装态，刷新状态行。 */
    private fun refreshStatus(host: LinearLayout) {
        val ctx = host.context
        Thread(Runnable {
            val text: String
            // 【状态色】这一行是四态语义，颜色必须跟着状态走：
            //   运行中 = 绿（可用）；其余三态都是「还不能用」，一律红（未授权/未安装同属待处理）。
            //   原先只 setText 不 setColor，四态一色，用户看不出好坏。
            val color: Int
            // 顺带把探活结论写进缓存：用户从这页返回聊天时，工具门控能立刻判定正确。
            if (LamdaManager.probe()) {
                text = "运行中"
                color = UiKit.OK
            } else if (LamdaManager.installed(ctx)) {
                text = "已安装，未运行"
                color = UiKit.ERR
            } else if (LamdaManager.pkgReady(ctx)) {
                text = "已下载，待解包"
                color = UiKit.ERR
            } else {
                text = "未安装"
                color = UiKit.ERR
            }

            val f = text
            val fc = color
            Handler(Looper.getMainLooper()).post {
                setStatus(host, f, fc)
            }
        }, "lamda-st").start()
    }

    /** 更新状态行文本 + 语义色；找不到就静默跳过（页面可能已被重建）。 */
    private fun setStatus(host: LinearLayout, text: String, color: Int) {
        try {
            val v = host.findViewWithTag<View>("lamda_status")
            if (v is TextView) {
                v.text = text
                // 颜色走插值，状态在四态之间跳动时不会「啪」地换色。
                UiKit.setTextColorAnimated(v, color)
                // 【状态图标】运行中 = 勾、其余三态 = 叉，与权限行同一套形状语义。
                Icons.stateIcon(v, if (color == UiKit.OK) Icons.IC_CHECK_CIRCLE else Icons.IC_X_CIRCLE,
                    color, 14.0f, 5)
            }
        } catch (ignored: Throwable) {
        }
    }
}
