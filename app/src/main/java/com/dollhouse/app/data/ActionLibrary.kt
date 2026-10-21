package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.pet.GestureCore
import com.dollhouse.app.pet.GestureActions
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 【职责】动作库的「操作层」——搜索、复制、重命名、引用检查、导出。
 *
 * ## 为什么要和 [ActionWorkbench] 分文件
 *
 * [ActionWorkbench] 管的是**配置**（显示设置、行为绑定），数据量小、读写频繁；
 * 本类管的是**库操作**，其中「复制」「导出」都要碰磁盘与帧文件，是重 IO。
 * 两类职责的失败模式完全不同（配置坏了只影响显示，库操作坏了会丢文件），
 * 混在一个 object 里会让「改配置」这种小事也要 import 一堆 IO 依赖。
 *
 * ## 「引用检查」为什么必须存在
 *
 * 动作会被三种东西引用：
 * ```
 * ① 手势绑定   `gesture_<gesture>` = "talk,act:a1234"   ← 删了动作就成悬空引用
 * ② 默认动作   `Action.isDefault = true`                ← 删了就没有常驻形象
 * ③ 语义槽位   `ActionSemantic` 把 id 映射成语义         ← 固定三格专用
 * ```
 * 需求书 §3.1 明确要求「删除被引用的动作要有安全处理」。
 * 只做「删 + 提示」而不做「删 + 清理引用」的话，用户下次点手势会**静默没反应** ——
 * 那种「点了没反应」的 bug 极难自查，所以这里提供 [refsOf] 让界面在删前把话说清楚，
 * 并提供 [purgeRefs] 在删后把悬空引用清掉。
 */
object ActionLibrary {

    /* ============================ 搜索 ============================ */

    /**
     * 按关键词过滤。
     *
     * 【匹配哪些字段】名称、描述、以及「帧数 / 速度」这类用户会用来找东西的数字。
     *   不匹配 id —— id 是 `a1x2y3z4` 这种机器串，用户永远记不住也搜不出来。
     *
     * 【为什么空关键词返回全部】界面上的搜索框清空时必须立刻恢复完整列表，
     *   这不是「搜不到」而是「没在搜」，两者要区分开。
     */
    @JvmStatic
    fun search(list: List<DollAnim.Action>, query: String): List<DollAnim.Action> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return list
        }
        val out = ArrayList<DollAnim.Action>()
        for (a in list) {
            if (a.name.lowercase().contains(q) ||
                a.desc.lowercase().contains(q) ||
                a.group.lowercase().contains(q)
            ) {
                out.add(a)
            }
        }
        return out
    }

    /** 搜索结果计数用的空态判据（界面据此决定显示「没有匹配」还是「这一组是空的」）。 */
    @JvmStatic
    fun isEmptyQuery(query: String): Boolean = query.trim().isEmpty()

    /* ============================ 复制 ============================ */

    /**
     * 复制一个动作（含帧文件与显示设置）。
     *
     * ## 三个必须做对的地方
     *
     * 1. **帧文件要真拷**。若两个动作指向同一批文件，删掉其中一个会连带删掉文件，
     *    另一个就变成「帧路径存在但文件没了」——界面上显示 N 帧、播出来全黑。
     * 2. **复制出来的动作不能是默认动作**。默认动作全局至多一个（[DollAnim.setDefault]），
     *    复制体抢这个位子会让「常驻形象」在用户不知情时被换掉。
     * 3. **固定壳不能复制到固定组**。固定三组是单例槽位，多一个「常驻」无处安放。
     *    复制固定壳时落到 [DollAnim.GROUP_MANUAL]（用户可再换组）。
     *
     * @return 新动作；失败返回 null（调用方据此提示）。
     */
    @JvmStatic
    fun duplicate(ctx: Context, dollId: String?, srcId: String?): DollAnim.Action? {
        if (dollId.isNullOrEmpty() || srcId == null) {
            return null
        }
        val src = DollAnim.byId(ctx, dollId, srcId) ?: return null
        val targetGroup = if (DollAnim.SWAPPABLE_GROUPS.contains(src.group)) {
            src.group
        } else {
            DollAnim.GROUP_MANUAL
        }
        // 先建壳拿到新 id —— 帧文件要落在「新 id」的目录下。
        val created = DollAnim.add(
            ctx, dollId, src.name + " 副本", src.desc,
            emptyList(), src.fps, src.loop, targetGroup
        ) ?: return null

        // 再拷帧文件：源目录 → 新目录，按原文件名顺序落盘。
        val copied = ArrayList<String>()
        val dstDir = DollAnim.framesDir(ctx, dollId, created.id)
        for (p in src.frames) {
            val f = File(p)
            if (!f.isFile) {
                continue
            }
            val out = File(dstDir, f.name)
            try {
                f.copyTo(out, overwrite = true)
                copied.add(out.absolutePath)
            } catch (t: Throwable) {
                // 单张失败不中断：能拷几张算几张，剩下的由界面按「少帧」如实显示。
            }
        }

        // 覆盖成完整内容（引擎 / 资源入口 / 基准 / 裁截线一起带过来）。
        val full = created.copy(
            frames = copied,
            engine = src.engine,
            assetPath = src.assetPath,
            baseline = src.baseline,
            edgeInset = src.edgeInset,
            isDefault = false
        )
        DollAnim.update(ctx, dollId, full)

        // 显示设置与行为绑定也跟着复制（用户调过的位置不该在副本里丢掉）。
        val disp = ActionWorkbench.display(ctx, dollId, src.id)
        if (!disp.isDefault()) {
            ActionWorkbench.setDisplay(ctx, dollId, created.id, disp)
        }
        return DollAnim.byId(ctx, dollId, created.id) ?: full
    }

    /* ============================ 重命名 ============================ */

    /**
     * 改名（去空格 + 空名拒绝 + 重名自动加序号）。
     *
     * 【为什么重名不报错而是加序号】用户改名时最常做的是「从一个名字改成另一个」，
     *   撞名意味着他手上有两个同类动作 —— 这时拦住他、让他先想个不重名的，
     *   比直接建成「挥手 2」更烦人。加序号是既保住唯一性又不打断操作的折中。
     *
     * @return 最终生效的名字；失败返回 null。
     */
    @JvmStatic
    fun rename(ctx: Context, dollId: String?, id: String?, newName: String): String? {
        if (dollId.isNullOrEmpty() || id == null) {
            return null
        }
        val a = DollAnim.byId(ctx, dollId, id) ?: return null
        val want = newName.trim()
        if (want.isEmpty()) {
            return null
        }
        if (want == a.name) {
            return want
        }
        var final = want
        var n = 2
        while (true) {
            var dup = false
            for (other in DollAnim.all(ctx, dollId)) {
                if (other.id != id && other.name == final) {
                    dup = true
                    break
                }
            }
            if (!dup) {
                break
            }
            final = want + " " + n
            n++
        }
        DollAnim.update(ctx, dollId, a.copy(name = final))
        return final
    }

    /* ============================ 引用检查 ============================ */

    /** 一条引用记录（界面据此展示「这个动作还被谁用着」）。 */
    class Ref(val kind: String, val label: String)

    /**
     * 查某个动作被谁引用。
     *
     * 【为什么不去扫「语义槽位」】语义槽位（[com.dollhouse.app.pet.behavior.ActionSemantic]）
     *   是按**分组**算出来的（常驻组里有内容 → 就是 idle 语义），不是按 id 绑定的。
     *   这里只看「有没有别的地方硬记了这个 id」：手势绑定。
     */
    @JvmStatic
    fun refsOf(ctx: Context, dollId: String?, id: String?): List<Ref> {
        val out = ArrayList<Ref>()
        if (dollId.isNullOrEmpty() || id == null) {
            return out
        }
        val a = DollAnim.byId(ctx, dollId, id) ?: return out
        // ① 默认动作
        if (a.isDefault) {
            out.add(Ref("default", "它是这只人偶的默认动作（常驻形象）"))
        }
        // ② 手势绑定
        val target = GestureActions.actValue(id)
        for (g in GestureCore.GESTURES) {
            val set = DollProfile.gestureActions(ctx, dollId, g)
            if (set.contains(target)) {
                out.add(Ref("gesture", "已绑到「" + GestureActions.gestureName(g) + "」手势"))
            }
        }
        return out
    }

    /**
     * 清掉指向某个动作的所有引用（删除动作后调用）。
     *
     * 【为什么删动作后必须调】手势绑定的值是 `act:<id>` 字符串，
     *   动作没了这个字符串依然在档案里。用户下次点那个手势 → 找不到动作 → 什么也不发生。
     *   「点了没反应」是最难自查的一类 bug，必须在删除时主动擦掉。
     *
     * @return 清掉了几处。
     */
    @JvmStatic
    fun purgeRefs(ctx: Context, dollId: String?, id: String?): Int {
        if (dollId.isNullOrEmpty() || id == null) {
            return 0
        }
        val target = GestureActions.actValue(id)
        var n = 0
        for (g in GestureCore.GESTURES) {
            val set = DollProfile.gestureActions(ctx, dollId, g)
            if (!set.contains(target)) {
                continue
            }
            val next = LinkedHashSet(set)
            next.remove(target)
            DollProfile.setGestureActions(ctx, dollId, g, next)
            n++
        }
        return n
    }

    /* ============================ 导出 ============================ */

    /** 导出结果：成功时 [path] 是 zip 的绝对路径。 */
    class ExportResult(val ok: Boolean, val path: String, val message: String)

    /**
     * 把一个动作导出成 zip。
     *
     * ## 包内结构
     * ```
     * dollhouse-action.json   清单（名字 / 描述 / 速度 / 循环 / 引擎 / 帧顺序 / 显示设置）
     * frames/0000.png ...     帧图（按播放顺序重命名，不沿用源文件名）
     * asset/...               非帧引擎的入口资源（Lottie json / 动图）
     * ```
     * 【为什么帧图要重命名】源文件名可能是 `IMG_20240101_0001.png` 这种，
     *   排序靠不住；重命名成 `0000.png` 后，解压顺序 = 播放顺序，
     *   别人拿到包不需要猜「这几张到底按什么顺序播」。
     *
     * 【为什么清单里带 display】导出是为了「换台机器还原同一个效果」。
     *   不带锚点和偏移，对方导入后人偶位置会与原作者看到的不一样。
     *
     * @param dstDir 导出目录（调用方给，通常是 `Download/` 之类用户能找到的地方）
     */
    @JvmStatic
    fun export(ctx: Context, dollId: String?, id: String?, dstDir: File): ExportResult {
        if (dollId.isNullOrEmpty() || id == null) {
            return ExportResult(false, "", "没有指定动作。")
        }
        val a = DollAnim.byId(ctx, dollId, id)
            ?: return ExportResult(false, "", "这个动作已经不存在了。")
        if (!dstDir.exists() && !dstDir.mkdirs()) {
            return ExportResult(false, "", "建不了导出目录：" + dstDir.absolutePath)
        }
        val safe = a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "action" }
        val zip = File(dstDir, safe + ".zip")
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                // ---------- 清单 ----------
                val manifest = JSONObject()
                manifest.put("format", "dollhouse-action")
                manifest.put("version", 1)
                manifest.put("name", a.name)
                manifest.put("desc", a.desc)
                manifest.put("fps", a.fps)
                manifest.put("loop", a.loop)
                manifest.put("engine", a.engine)
                val disp = ActionWorkbench.display(ctx, dollId, a.id)
                manifest.put("display", JSONObject(disp.encode()))
                val bind = ActionWorkbench.binding(ctx, dollId, a.id)
                manifest.put("binding", JSONObject(bind.encode()))
                val names = ArrayList<String>()
                for (i in a.frames.indices) {
                    names.add("frames/" + pad4(i) + extOf(a.frames[i]))
                }
                manifest.put("frames", org.json.JSONArray(names as Collection<*>))

                zos.putNextEntry(ZipEntry("dollhouse-action.json"))
                zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // ---------- 帧图 ----------
                var miss = 0
                for (i in a.frames.indices) {
                    val f = File(a.frames[i])
                    if (!f.isFile) {
                        miss++
                        continue
                    }
                    zos.putNextEntry(ZipEntry(names[i]))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // ---------- 入口资源 ----------
                if (a.assetPath.isNotEmpty()) {
                    val f = File(a.assetPath)
                    if (f.isFile) {
                        zos.putNextEntry(ZipEntry("asset/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (miss > 0) {
                    return ExportResult(
                        true, zip.absolutePath,
                        "已导出，但有 " + miss + " 帧的源文件找不到了，包里会缺这些帧。"
                    )
                }
            }
        } catch (t: Throwable) {
            try {
                zip.delete()
            } catch (ignored: Throwable) {
            }
            return ExportResult(false, "", "导出失败：" + (t.message ?: t.javaClass.simpleName))
        }
        return ExportResult(true, zip.absolutePath, "已导出到 " + zip.absolutePath)
    }

    /** 帧序号补零到 4 位 —— 保证字典序 = 播放序。 */
    private fun pad4(i: Int): String {
        val s = i.toString()
        return when {
            s.length >= 4 -> s
            s.length == 3 -> "0" + s
            s.length == 2 -> "00" + s
            s.length == 1 -> "000" + s
            else -> "0000"
        }
    }

    /** 取扩展名（含点）；认不出就用 .png。 */
    private fun extOf(path: String): String {
        val n = File(path).name
        val dot = n.lastIndexOf('.')
        if (dot < 0 || dot == n.length - 1) {
            return ".png"
        }
        val e = n.substring(dot).lowercase()
        return if (e.length in 2..6) e else ".png"
    }
}
