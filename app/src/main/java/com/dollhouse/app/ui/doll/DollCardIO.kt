package com.dollhouse.app.ui.doll

import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PersonaIO
import com.dollhouse.app.ui.home.HomeUi
import java.io.File

/**
 * 【职责】角色卡的**导入**入口。
 *
 * ## v0.2.8 起这里只剩导入，为什么
 * 改造前本对象同时管「导出角色卡」。那个按钮只导出**一只**人偶的人设与角色设定，
 * 但它和其它入口一起并列成三个同名的「导出」，用户以为那就是备份 ——
 * 换到新机后才发现聊天记录、长期记忆、封面图片全都不在包里。
 *
 * 正确的通道是设置页的「一键全量备份」（[com.dollhouse.app.data.AllInOneArchive]），
 * 它在 `persona` 段里**逐字段**包含了这里能导出的全部内容，外加聊天 / 记忆 / 图片。
 * 于是本轮删掉导出，只留导入 ——
 * **导入不是备份场景**：用户要从网上下载酒馆（Tavern）角色卡，全量存档替代不了它。
 * （导出能力没有被丢掉，只是合并进了全量存档；`PersonaIO.exportOwn / exportTavern`
 *  仍留在 data 层，如需恢复单独导出可以再接回来。）
 *
 * 【为什么复用 PickFileActivity】工程已有一条成熟的「选文件 → 复制到缓存 → 回调路径」
 *   通道（见 [PickFileActivity.FileListener]），动作导入用的就是它。这里不再另起一套，
 *   用户看到的选择器与动作导入完全一致 —— 不引入第二种交互。
 */
object DollCardIO {

    /**
     * 走文件选择器导入一张角色卡。
     *
     * @param onDone 导入成功后回调（调用方据此刷新界面）
     */
    @JvmStatic
    fun pickImport(act: MainActivity, dollId: String, onDone: () -> Unit) {
        val listener = object : PickFileActivity.FileListener {
            override fun onFiles(paths: List<String>, failed: Boolean) {
                // 【单文件语义】角色卡就是一份 json，多选没有意义；取第一份即可。
                if (failed || paths.isEmpty()) {
                    HomeUi.showNotice(act, "导入失败", "没能读取选中的文件，请重试。")
                    return
                }
                doImport(act, dollId, paths[0], onDone)
            }
        }
        PickFileActivity.setFileListener(listener)
        PickFileActivity.startFiles(act, false)
    }

    private fun doImport(act: MainActivity, dollId: String, path: String, onDone: () -> Unit) {
        val text = try {
            File(path).readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            Logs.w("DollhousePersona", "读取角色卡失败", t)
            HomeUi.showNotice(act, "导入失败", "读不了这个文件，换一个再试。")
            return
        }
        val imported = PersonaIO.parse(text)
        if (imported == null) {
            // 【先认「选错通道」这种最常见的错】用户手上刚做完备份，最容易
            //   顺手把 `dollhouse-backup-xxx.json` 拖进"导入角色卡"。
            //   只说「认不出这张卡」会让人以为文件坏了，于是回头重做备份。
            //   这里直接把正确的入口指给他（判定用信封的 kind 标识，只扫文本前缀，
            //   不解析整份 —— 全量存档可能有几十 MB）。
            if (looksLikeFullBackup(text)) {
                HomeUi.showNotice(
                    act, "这是一份全量备份",
                    "它装的是整台手机的内容，不是单张角色卡。\n" +
                        "请到「设置 → 备份与恢复 → 从备份恢复」导入。"
                )
                return
            }
            // 【提示要具体】用户常见的错是把普通文本 / 图片当角色卡拖进来，
            //   所以这里把「支持什么」讲清楚，而不是只说一句「格式不对」。
            HomeUi.showNotice(
                act, "认不出这张卡",
                "支持酒馆（Tavern）角色卡与 Dollhouse 导出的角色卡（.json）。"
            )
            return
        }
        if (!PersonaIO.apply(act, dollId, imported)) {
            HomeUi.showNotice(act, "导入失败", "写入时出错了，请重试。")
            return
        }
        val who = imported.name.ifEmpty { "这张卡" }
        HomeUi.showNotice(
            act, "已导入 " + imported.source + " 角色卡",
            who + " 的设定已覆盖到这只人偶上。"
        )
        onDone()
    }

    /**
     * 这段文本是不是一份全量存档。
     *
     * 【为什么只查前缀 4 KiB】与 [com.dollhouse.app.data.BackupEnvelope.looksLike] 同口径：
     *   本格式的 `kind` 是信封的第一个键，正常备份 30 字节内就命中。
     *   但这里拿到的是**已经读进内存的 String**（PickFileActivity 给的是路径，
     *   我们为了解析角色卡必然要读全文），不能再为判定多转一次；
     *   只看开头既省事，也避免在 40 MB 文本上做 contains 全文扫描。
     */
    private fun looksLikeFullBackup(text: String): Boolean {
        val head = if (text.length > 4096) text.substring(0, 4096) else text
        return head.contains("dollhouse.backup")
    }
}