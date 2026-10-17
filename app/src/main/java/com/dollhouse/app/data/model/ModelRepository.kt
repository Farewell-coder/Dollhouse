package com.dollhouse.app.data.model

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 【职责】模型包的**元数据仓库**：记录这台设备上装了哪些模型包、各归哪只人偶、原始清单是什么。
 *
 * ## 为什么要有「仓库」这一层
 * 模型包解压出来是一堆普通文件，装完之后「谁装过、属于谁、能不能卸载」
 * 这些信息没地方存 —— 如果只靠扫描目录猜，就会出现
 * 「用户删了人偶、包还留在盘上」「同一个包装给两只人偶互相覆盖」之类的问题。
 *
 * ## 存储设计（跟随工程既有约定）
 * · 元数据：`feiyu_pet` 里的一个 JSON 数组，键 `doll_models_<dollId>`（按人偶分库，
 *   与 `DollAnim` 的 `doll_anim_<dollId>` 同款做法）。
 * · 资源文件：`filesDir/doll/model/<dollId>/<modelId>/...`，用包内相对路径落位。
 * · **原始 manifest 一起存**：这样「未知字段忽略并保留」才有落点
 *   （任务书 §3.7 校验规则要求），导出时也能原样带回去。
 *
 * ## 线程
 * 读写在主线程（与 `PetPrefs` 同约束）；解压由 [ModelImporter] 负责，可放后台。
 */
object ModelRepository {

    private const val TAG = "DollhouseModel"
    private const val KEY_PREFIX = "doll_models_"
    private const val DIR_ROOT = "model"

    /**
     * 一条安装记录。
     *
     * @param modelId   manifest 的 `id`（同一人偶内唯一）。
     * @param name      显示名（用户可见）。
     * @param specVersion 安装时的规范版本（用于「本包会不会太新」的提示）。
     * @param engine    主引擎。
     * @param actions   语义 → 落盘后的**绝对路径**（导入时已把包内相对路径解析完）。
     * @param rawManifest 原始清单 JSON 文本（含本版不认识的字段，导出时原样带回）。
     * @param installedAt 安装时间（毫秒），用于「最近导入」排序。
     */
    data class Installed(
        val modelId: String,
        val name: String,
        val specVersion: Int,
        val engine: String,
        val entry: String,
        val actions: Map<String, String>,
        val rawManifest: String,
        val installedAt: Long
    )

    // ============================ 读 ============================

    /** 该人偶上已装的模型包（按安装时间倒序，最近的在前）。 */
    fun list(ctx: Context, dollId: String?): List<Installed> {
        if (dollId.isNullOrEmpty()) {
            return emptyList()
        }
        val raw = try {
            PetPrefs.get(ctx).getString(key(dollId), "")
        } catch (t: Throwable) {
            ""
        }
        if (raw.isNullOrEmpty()) {
            return emptyList()
        }
        val out = ArrayList<Installed>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("modelId", "")
                if (id.isEmpty()) {
                    continue
                }
                out.add(
                    Installed(
                        modelId = id,
                        name = o.optString("name", id),
                        specVersion = o.optInt("specVersion", ModelPackage.SPEC_VERSION),
                        engine = o.optString("engine", "sprite"),
                        entry = o.optString("entry", ""),
                        actions = readMap(o, "actions"),
                        rawManifest = o.optString("rawManifest", ""),
                        installedAt = o.optLong("installedAt", 0L)
                    )
                )
            }
        } catch (t: Throwable) {
            // 【为什么吞掉异常】一个坏条目不该让「所有已装模型都看不见」——
            //   用户会以为自己的模型全丢了。宁可少显示一条。
            Logs.w(TAG, "读取已装模型列表失败", t)
        }
        out.sortByDescending { it.installedAt }
        return out
    }

    /** 已装的 model id 集合（校验「重名」用）。 */
    fun installedIds(ctx: Context, dollId: String?): Set<String> {
        val out = HashSet<String>()
        for (m in list(ctx, dollId)) {
            out.add(m.modelId)
        }
        return out
    }

    /** 按 id 取一条记录。 */
    fun byId(ctx: Context, dollId: String?, modelId: String?): Installed? {
        if (modelId == null) {
            return null
        }
        for (m in list(ctx, dollId)) {
            if (m.modelId == modelId) {
                return m
            }
        }
        return null
    }

    // ============================ 写 ============================

    /**
     * 登记一个已安装的模型包（同 id 覆盖）。
     *
     * 【为什么允许覆盖】同一只人偶上重装同一个模型包是常见操作
     *   （作者更新了素材）。覆盖时**保留原安装时间**会让「最近导入」排序错乱，
     *   所以刷新为当前时间 —— 用户的直觉就是「我刚装的应该在最上面」。
     */
    fun put(ctx: Context, dollId: String?, item: Installed) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        val all = ArrayList(list(ctx, dollId))
        var idx = -1
        for (i in all.indices) {
            if (all[i].modelId == item.modelId) {
                idx = i
                break
            }
        }
        if (idx >= 0) {
            all[idx] = item
        } else {
            all.add(item)
        }
        save(ctx, dollId, all)
    }

    /**
     * 卸载：删元数据 + 删资源目录。
     *
     * 【为什么不删它带来的动作】动作是「人偶的动作库」里的一等公民，
     *   用户可能已经把它设成了默认动作、或绑到了手势上。
     *   删除它们要由调用方显式决定（见 [com.dollhouse.app.data.DollAnim.removeBySource]），
     *   本方法只负责把「模型包」这一层清干净。
     *
     * @return 是否真的删掉了记录。
     */
    fun remove(ctx: Context, dollId: String?, modelId: String?): Boolean {
        if (dollId.isNullOrEmpty() || modelId == null) {
            return false
        }
        val all = ArrayList(list(ctx, dollId))
        var hit = false
        val keep = ArrayList<Installed>(all.size)
        for (m in all) {
            if (m.modelId == modelId) {
                hit = true
            } else {
                keep.add(m)
            }
        }
        if (!hit) {
            return false
        }
        save(ctx, dollId, keep)
        // 资源目录整棵删掉（含 images/ 等子目录）。
        val dir = modelDir(ctx, dollId, modelId)
        deleteTree(dir)
        return true
    }

    // ============================ 文件布局 ============================

    /** 该人偶的模型包根目录。 */
    fun rootDir(ctx: Context, dollId: String): File {
        val d = File(File(ctx.filesDir, "doll/$DIR_ROOT"), dollId)
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /**
     * 某个模型包的解压目录。
     *
     * 【为什么不 mkdirs】调用方在「校验阶段」只想拿到路径去比对，
     *   这时建目录会让「导入被拒绝」也留下一个空目录 —— 盘上攒一堆空壳，
     *   用户看不出是什么，我们也解释不了。要建目录的调用方自己 mkdirs。
     */
    fun modelDir(ctx: Context, dollId: String, modelId: String): File {
        return File(rootDir(ctx, dollId), safeName(modelId))
    }

    /**
     * 把 id 变成安全的目录名。
     *
     * 【为什么必须过滤】model id 来自第三方 manifest，可能是 `../../x`
     *   或含 `/` —— 直接拼进路径就是路径穿越（任务书 §3.7 红线）。
     *   这里只允许字母数字与 `-_.`，其余一律换成 `_`。
     */
    fun safeName(id: String): String {
        val sb = StringBuilder()
        for (c in id) {
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        var s = sb.toString()
        while (s.startsWith(".")) {
            s = s.substring(1)
        }
        return if (s.isEmpty()) "model" else s
    }

    // ============================ 内部 ============================

    private fun key(dollId: String): String = KEY_PREFIX + dollId

    private fun save(ctx: Context, dollId: String, list: List<Installed>) {
        try {
            val arr = JSONArray()
            for (m in list) {
                val o = JSONObject()
                o.put("modelId", m.modelId)
                o.put("name", m.name)
                o.put("specVersion", m.specVersion)
                o.put("engine", m.engine)
                o.put("entry", m.entry)
                o.put("installedAt", m.installedAt)
                o.put("rawManifest", m.rawManifest)
                val a = JSONObject()
                for ((k, v) in m.actions) {
                    a.put(k, v)
                }
                o.put("actions", a)
                arr.put(o)
            }
            PetPrefs.get(ctx).edit().putString(key(dollId), arr.toString()).apply()
        } catch (t: Throwable) {
            Logs.w(TAG, "保存已装模型列表失败", t)
        }
    }

    private fun readMap(o: JSONObject, key: String): Map<String, String> {
        val sub = o.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = sub.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = sub.optString(k, "")
        }
        return out
    }

    private fun deleteTree(dir: File) {
        try {
            if (!dir.exists()) {
                return
            }
            val kids = dir.listFiles() ?: return
            for (one in kids) {
                if (one.isDirectory) {
                    deleteTree(one)
                } else {
                    one.delete()
                }
            }
            dir.delete()
        } catch (t: Throwable) {
            Logs.w(TAG, "删除模型目录失败", t)
        }
    }
}