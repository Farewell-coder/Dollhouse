package com.dollhouse.app.ui.doll

import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PersonaIO
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.home.HomeUi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】角色卡的导入 / 导出入口（r13 新增）。
 *
 * 【为什么复用 PickFileActivity】工程已有一条成熟的「选文件 → 复制到缓存 → 回调路径」
 *   通道（见 [PickFileActivity.FileListener]），动作导入用的就是它。这里不再另起一套，
 *   用户看到的选择器与动作导入完全一致 —— 不引入第二种交互。
 *
 * 【导出落哪】写到 App 外部私有目录的 `export/persona/`（与 [DollStore.exportAll] 同一片区域），
 *   再从那里分享出去。**不直接写用户可见目录**：那张卡里可能有人设私密内容，
 *   落到公共目录会被相册 / 文件管理器扫到。由用户主动「分享」才外流。
 *
 * 【为什么不弹输入框让用户写路径】Android 10+ 的分区存储下，让用户手打路径是反人类设计；
 *   工程的既有做法是「落到自己的目录 → 分享」，与备份功能同款。
 */
object DollCardIO {

    /** 导出文件的存放目录名（相对 App 外部私有目录）。 */
    private const val DIR = "export/persona"

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

    /** 选导出格式。 */
    @JvmStatic
    fun showExport(act: MainActivity, dollId: String) {
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, "导出角色卡") {
            val name = DollStore.byId(act, dollId)?.name ?: ""
            DhForm.Note("选择导出格式：")
            DhKit.ActionRow(
                    label = "Dollhouse 格式",
                    iconRes = Icons.IC_PACKAGE,
                    trailing = "字段最全"
                ) {
                write(act, dollId, name, false)
                dlgRef?.dismiss()
            }
            DhForm.Note("本软件专用，含注入位置、输出替换等全部设置，换手机 / 备份用这个。")
            DhKit.ActionRow(
                    label = "酒馆（Tavern）格式",
                    iconRes = Icons.IC_EXTERNAL
                ) {
                write(act, dollId, name, true)
                dlgRef?.dismiss()
            }
            DhForm.Note("能导入到其他同类软件；但装不下注入位置与输出替换，这部分会丢掉。")
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 18.dp)
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.DialogButton(text = "取消", primary = false) { dlgRef?.dismiss() }
            }
        }
    }

    private fun write(act: MainActivity, dollId: String, name: String, tavern: Boolean) {
        try {
            val persona = DollProfile.persona(act, dollId)
            val spec = DollProfile.spec(act, dollId)
            val text = if (tavern) {
                PersonaIO.exportTavern(name, persona, spec)
            } else {
                PersonaIO.exportOwn(name, persona, spec)
            }
            val dir = File(act.getExternalFilesDir(null), DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                HomeUi.showNotice(act, "导出失败", "建不了导出目录。")
                return
            }
            val safe = if (name.isEmpty()) "persona" else name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val suffix = if (tavern) "_tavern.json" else ".json"
            val f = File(dir, safe + suffix)
            f.writeText(text, Charsets.UTF_8)
            Logs.i("DollhousePersona", "[export] " + f.absolutePath + " len=" + text.length)
            HomeUi.showNotice(
                act, "已导出",
                "保存在：" + f.absolutePath
            )
        } catch (t: Throwable) {
            Logs.w("DollhousePersona", "导出失败", t)
            HomeUi.showNotice(act, "导出失败", "写入时出错了，请重试。")
        }
    }
}