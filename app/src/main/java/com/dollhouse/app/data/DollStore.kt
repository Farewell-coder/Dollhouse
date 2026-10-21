package com.dollhouse.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dollhouse.app.R
import com.dollhouse.app.core.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * 【职责】人偶（Doll）数据模型与持久化：列表增删改、启用态、封面 / 形象图落盘、导出。
 *
 * 【为什么不用 Room】本模块只有几十条记录、字段全是短字符串，用 Room 要引 kapt 注解处理器
 *   与 schema 迁移，收益为负。工程既有模块（PetPrefs / ProviderStore）全是 SharedPreferences，
 *   这里跟随同一约定：一个 JSON 数组塞进 `feiyu_pet` 的 `dolls_json` 键。
 *
 * 【图片为什么不复用 ImageStore】ImageStore 有 MAX_FILES=80 的自动清理（按时间删旧图），
 *   人偶图属于「用户资产」，被后台清理掉就是事故。故另开 filesDir/doll 目录，只增不自动删。
 *
 * 【线程】读写在主线程（与 PetPrefs 同约束）；缓存为 @Volatile，写后立即失效。
 */
object DollStore {
    /** 默认配置的固定 id：内置、不可删除、始终排在列表首位。 */
    const val DEFAULT_ID = "default"
    /** 默认配置的显示名。 */
    const val DEFAULT_NAME = "默认配置"
    private const val KEY_DOLLS = "dolls_json"
    private const val DIR = "doll"
    private const val PREFIX_COVER = "cover_"
    private const val PREFIX_SPRITE = "sprite_"

    /** 日志标签（本对象只用它记录封面裁剪失败这类可恢复问题）。 */
    private const val TAG = "DollhouseDollStore"

    /**
     * 一个人偶。
     *
     * @param id      唯一标识；[DEFAULT_ID] 为内置默认配置。
     * @param name    显示名。
     * @param cover   封面图绝对路径；空串 = 回落到形象图，再空则回落内置 `pet_front`。
     * @param sprite  形象图绝对路径；空串 = 用内置 `pet_front`。
     * @param enabled 是否启用。允许多个同时启用，启动时逐个挂窗展示。
     * @param builtin 内置项（不可删除）。
     * @param group   【r14】所属分组名。**空串 = 未分组**（默认），列表里归到「未分组」组。
     *                【为什么存名字而不是分组 id】分组清单由 [PetPrefs.dollGroups] 持有，
     *                删组只需从清单里去掉名字，成员的 group 变成「清单里没有的值」，
     *                读取端一律按「未分组」展示 —— 不需要一次批量改写所有人偶。
     */
    data class Doll(
        val id: String,
        val name: String,
        val cover: String = "",
        val sprite: String = "",
        val enabled: Boolean = false,
        val builtin: Boolean = false,
        val group: String = ""
    )
    /** 【r14】「未分组」的展示名（列表里的默认分组标题）。 */
    const val GROUP_NONE = "未分组"

    @Volatile
    private var cache: MutableList<Doll>? = null

    /* ============================ 读取 ============================ */

    /** 全部人偶；内置默认配置永远在首位（列表被外部改坏时也会被这里补回）。 */
    @JvmStatic
    @Synchronized
    fun all(ctx: Context): List<Doll> {
        val list = load(ctx)
        return ArrayList(list)
    }

    /** 已启用的人偶（多个人偶可同时启用）。 */
    @JvmStatic
    @Synchronized
    fun enabled(ctx: Context): List<Doll> {
        val out = ArrayList<Doll>()
        for (d in load(ctx)) {
            if (d.enabled) {
                out.add(d)
            }
        }
        return out
    }

    /** 按 id 取一个；取不到返回 null。 */
    @JvmStatic
    @Synchronized
    fun byId(ctx: Context, id: String?): Doll? {
        if (id == null) {
            return null
        }
        for (d in load(ctx)) {
            if (d.id == id) {
                return d
            }
        }
        return null
    }

    /** 默认配置（内置项）。 */
    @JvmStatic
    @Synchronized
    fun defaultDoll(ctx: Context): Doll {
        return byId(ctx, DEFAULT_ID) ?: Doll(DEFAULT_ID, DEFAULT_NAME, builtin = true)
    }

    /* ============================ 写入 ============================ */

    /**
     * 新增一个人偶（**空白模板**）。名字自动去重：新模板、新模板 2、新模板 3…
     * 返回新建项。
     *
     * 【r13 用户定案·必须真的是空白】用户原话：「添加的新模板是空白模板 不要用这个 金鱼娘的人偶了
     *   里面的每项设置都是默认值 动作是空的 让用户自己来添加」。
     *   所以这里除了建记录，还要显式把动作库标记成「明确为空」——
     *   否则 [DollAnim.all] 的自愈逻辑会立刻把 19 个内置动作补进来，新模板根本不空。
     */
    @JvmStatic
    @Synchronized
    fun addTemplate(ctx: Context, name: String = "新模板"): Doll {
        val list = load(ctx)
        val doll = Doll(id = newId(), name = uniqueName(list, name))
        list.add(doll)
        save(ctx, list)
        // 【必须在这里落标记】见 [DollAnim.markEmpty] 的注释：等用户第一次进动作页才落就晚了。
        DollAnim.markEmpty(ctx, doll.id)
        return doll
    }

    /**
     * 本地导入：把用户选的图片复制进私有目录，作为一个新人偶。
     * 导入失败（读不到图）返回 null。
     */
    @JvmStatic
    @Synchronized
    fun importLocal(ctx: Context, uri: Uri?, name: String = "本地导入"): Doll? {
        if (uri == null) {
            return null
        }
        val saved = saveImage(ctx, uri, PREFIX_SPRITE)
        if (saved.isEmpty()) {
            return null
        }
        return addDoll(ctx, saved, name)
    }

    /**
     * 从「选图通道」拷出的字节副本入库，返回新建项；失败返回 null。
     *
     * 【为什么另开一条】「本地导入」走 `PickFileActivity`（它负责兼容各 ROM 的相册 / 照片选择器），
     *   本方法吃的是它递过来的 cacheDir 字节副本 —— 按字节拷过来的原图，不解码不转码，
     *   透明度与画质完整保留，正好符合人偶形象图的要求。
     * 【为什么不用 Uri】Uri 的读权限绑在发起选择的那次 Activity 上，`finish()` 后可能立即失效；
     *   拿稳定路径则没有时效问题。
     */
    @JvmStatic
    @Synchronized
    fun importRaw(ctx: Context, path: String?, name: String = "本地导入"): Doll? {
        if (path.isNullOrEmpty()) {
            return null
        }
        val src = File(path)
        val saved = saveImageFile(ctx, src, PREFIX_SPRITE)
        // 无论成败都清掉中间副本：它只是选图通道的临时产物，留着白占空间。
        try {
            src.delete()
        } catch (t: Throwable) {
        }
        if (saved.isEmpty()) {
            return null
        }
        return addDoll(ctx, saved, name)
    }

    /** 改名 / 换封面 / 换形象图 / 改启用态：整体覆盖一项，id 不存在则忽略。 */
    @JvmStatic
    @Synchronized
    fun update(ctx: Context, doll: Doll?) {
        if (doll == null) {
            return
        }
        val list = load(ctx)
        for (i in list.indices) {
            if (list[i].id == doll.id) {
                // 内置项的 builtin 位与 id 不可被外部改写，其余字段照收。
                list[i] = doll.copy(id = list[i].id, builtin = list[i].builtin)
                save(ctx, list)
                return
            }
        }
    }

    /**
     * 【r13·需求B】把「人偶动作库里某一帧」设为封面。
     *
     * 【r13 修 bug·「从动作库选一帧」失效】用户原话：
     *   「第一个功能的作用是 选择之后 放一个正常比例的外形 然后这个玩意是会跟着它的三个形态
     *   自动缩放的 就是不用选三次」。
     *
     *   【曾经的 bug】本方法只把那一帧**原尺寸**存下来、只写 [Doll.cover]，
     *   **不写 coverSet**。于是：
     *   ① 切列数时 [coverForCols] 找不到该列数的图 → 回落这张原图 → 比例不符（留边或被裁）；
     *   ② 一旦用户改用「用自己的图片」（那条路径会写 coverSet），
     *      coverSet 里全是裁剪页产出的图，**这张帧图就被彻底顶掉**，再切回「选一帧」也没用 ——
     *      正是用户说的「使用了第二个就没法用第一个了」。
     *
     *   【修法】这里改为**自动按三种列数比例各裁一张**（等价于用户手动裁三次，
     *   但只点一次），三张一起写进 coverSet。这样：
     *   · 选完即用，无需再操作；
     *   · 切 1 / 2 / 3 列自动换成对应比例那张（用户说的「跟着三个形态自动缩放」）；
     *   · 两条路径产出的数据结构一致，随时可互相覆盖，不再互斥。
     *
     * 【为什么复用 saveImageFile】编码格式（PNG 无损）、缩放口径（最长边 1024）、命名规则
     *   必须与其它导入路径一致，否则后续读取 / 导出会在不同来源的资产上分叉。
     */
    @JvmStatic
    @Synchronized
    fun setCoverFromFrame(ctx: Context, dollId: String?, framePath: String?): Boolean {
        if (dollId.isNullOrEmpty() || framePath.isNullOrEmpty()) {
            return false
        }
        val src = File(framePath)
        if (!src.exists() || src.length() <= 0L) {
            return false
        }
        // 【先按三种比例裁】裁不出来（图坏了）就退回「只存原图」的老路径，至少不丢功能。
        val three = cropToThreeRatios(ctx, src)
        if (three != null) {
            // 当前列数那一张挂进 cover；三张路径都记进 coverSet。
            val idx = Math.max(0, Math.min(2, PetPrefs.dollCols(ctx) - 1))
            // 【注意】three[idx] 是非空 String（空串表示该比例没裁成功），
            //   所以这里要判空串，不能用 `?:`（那会被编译器判定为恒取左侧）。
            val chosen = three[idx].ifEmpty {
                three.firstOrNull { it.isNotEmpty() } ?: ""
            }
            if (chosen.isNotEmpty()) {
                if (applyCover(ctx, dollId, chosen)) {
                    setCoverSet(ctx, dollId, three)
                    return true
                }
            }
        }
        // 【兜底】退回原逻辑（只存一张）。
        val saved = saveImageFile(ctx, src, PREFIX_COVER)
        if (saved.isEmpty()) {
            return false
        }
        return applyCover(ctx, dollId, saved)
    }

    /**
     * 【r13】把一张图按「1 / 2 / 3 列」三种封面比例各裁一份，返回三条路径（顺序 = 列数）。
     *
     * 【怎么裁】以源图**中心**为基准，取每种比例下能容纳的最大矩形 ——
     *   即「居中裁剪」（center-crop），这也是所有看图软件缩略图的通行做法：
     *   主体通常在画面中央，居中裁最不容易切掉关键部分。
     *   用户想要别的构图时，可以改用「用自己的图片」那条路径手工框选。
     *
     * 【为什么以最长边为准】源帧图的长宽比任意；对每种目标比例，
     *   取「短边铺满、长边居中截掉」的矩形，保证零留白（与卡片 `ContentScale.Crop` 一致）。
     *
     * @return 三条绝对路径；整体失败返回 null（调用方走兜底）。
     */
    private fun cropToThreeRatios(ctx: Context, src: File): Array<String>? {
        var full: Bitmap? = null
        try {
            full = BitmapFactory.decodeFile(src.absolutePath) ?: return null
            val w = full.width
            val h = full.height
            if (w <= 0 || h <= 0) {
                return null
            }
            val out = Array(3) { "" }
            for (i in 0 until 3) {
                val cols = i + 1
                // 【取实测优先的比例】与裁剪页同一口径（见 PetPrefs.dollCoverRatio）。
                val ratio = coverRatioFor(ctx, cols)
                if (ratio <= 0.01f) {
                    continue
                }
                // 目标比例 = 宽/高。在源图里找能容纳该比例的**最大居中矩形**。
                var cw = w
                var ch = Math.round(w / ratio)
                if (ch > h) {
                    ch = h
                    cw = Math.round(h * ratio)
                }
                if (cw <= 0 || ch <= 0) {
                    continue
                }
                val left = (w - cw) / 2
                val top = (h - ch) / 2
                val cut = Bitmap.createBitmap(full, left, top, cw, ch)
                val f = File(coversDir(ctx), "frame_cover_" + cols + "_" + System.currentTimeMillis() + ".png")
                FileOutputStream(f).use { os ->
                    cut.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }
                if (cut !== full) {
                    cut.recycle()
                }
                out[i] = f.absolutePath
            }
            return if (out.any { it.isNotEmpty() }) out else null
        } catch (t: Throwable) {
            Logs.w(TAG, "cropToThreeRatios failed", t)
            return null
        } finally {
            if (full != null && !full.isRecycled) {
                full.recycle()
            }
        }
    }

    /** 取某列数下的封面比例（实测优先，回落推导值）。与裁剪页同一口径。 */
    private fun coverRatioFor(ctx: Context, cols: Int): Float {
        val measured = PetPrefs.coverRatioMeasured(ctx, cols)
        if (measured > 0.01f) {
            return measured
        }
        val arr = PetPrefs.DOLL_COVER_RATIOS
        return arr[Math.max(0, Math.min(arr.size - 1, cols - 1))]
    }

    /** 【r13·需求B】把用户自己的图片设为封面（走选图通道拿到的本地路径）。 */
    @JvmStatic
    @Synchronized
    fun setCoverFromFile(ctx: Context, dollId: String?, srcPath: String?): Boolean {
        if (dollId.isNullOrEmpty() || srcPath.isNullOrEmpty()) {
            return false
        }
        val saved = saveImageFile(ctx, File(srcPath), PREFIX_COVER)
        if (saved.isEmpty()) {
            return false
        }
        return applyCover(ctx, dollId, saved)
    }

    /** 【r13·需求E】把用户自己裁剪好的图片设为封面。
     *
     * 【与 setCoverFromFile 的区别】那个直接存原图，卡片里再靠 `ContentScale.Fit` 缩 ——
     *   比例不符时要么留边、要么被裁。这里存的是**按卡片封面框比例裁好的**图，
     *   铺进卡片正好贴合，所见即所得。
     *
     * @param srcPath 裁剪页落盘的临时图路径（调用方负责用完删除）。
     */
    @JvmStatic
    @Synchronized
    fun setCoverFromCrop(ctx: Context, dollId: String?, srcPath: String?): Boolean {
        if (dollId.isNullOrEmpty() || srcPath.isNullOrEmpty()) {
            return false
        }
        val f = File(srcPath)
        if (!f.exists() || f.length() <= 0L) {
            return false
        }
        val saved = saveImageFile(ctx, f, PREFIX_COVER)
        if (saved.isEmpty()) {
            return false
        }
        return applyCover(ctx, dollId, saved)
    }

    /** 【r13·需求E】人偶私有目录（`filesDir/doll`），封面裁剪页落盘用。 */
    @JvmStatic
    fun coversDir(ctx: Context): File {
        val d = dir(ctx)
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /**
     * 【r13·需求E】记下「三种列数各自的封面图」路径集合。
     *
     * 【为什么单独存一份】[setCoverFromCrop] 只把当前列数那一张挂进 [Doll.cover]。
     *   用户切列数时要能换成对应比例的那张 —— 三张都在盘上，但得知道哪张对应第几列。
     *   这里把三条路径按 `[1列, 2列, 3列]` 的顺序记进一个键；
     *   切换列数时由列表页读它换 [Doll.cover]（见 [coverForCols]）。
     *
     * @param paths 三条绝对路径（允许个别为空，空位表示那一种比例没裁）。
     */
    @JvmStatic
    @Synchronized
    fun setCoverSet(ctx: Context, dollId: String?, paths: Array<String>) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        try {
            PetPrefs.setCoverSet(ctx, dollId, paths.joinToString("\n"))
        } catch (t: Throwable) {
        }
    }

    /**
     * 【r13·需求E】取某只人偶在指定列数下应该用的封面路径。
     *
     * @param cols 1 / 2 / 3（即列表排布列数）。
     * @return 对应路径；没裁过该比例时返回空串（调用方回落到 [Doll.cover]）。
     */
    @JvmStatic
    fun coverForCols(ctx: Context, dollId: String?, cols: Int): String {
        if (dollId.isNullOrEmpty()) {
            return ""
        }
        val raw = try {
            PetPrefs.coverSet(ctx, dollId)
        } catch (t: Throwable) {
            ""
        }
        if (raw.isEmpty()) {
            return ""
        }
        val arr = raw.split("\n")
        val i = Math.max(0, Math.min(arr.size - 1, cols - 1))
        val p = arr[i]
        if (p.isEmpty() || !File(p).exists()) {
            return ""
        }
        return p
    }

    /**
     * 【r13·需求E】把封面切换成「指定列数对应的那一张」。
     *
     * 【调用时机】用户在列表页切列数时。没裁过该比例就什么都不做（保留当前封面）。
     */
    @JvmStatic
    @Synchronized
    fun applyCoverForCols(ctx: Context, dollId: String?, cols: Int): Boolean {
        val p = coverForCols(ctx, dollId, cols)
        if (p.isEmpty()) {
            return false
        }
        return applyCover(ctx, dollId, p)
    }

    /** 【r13·需求B】清空封面（回到「未设置」：列表用占位、形象图不受影响）。 */
    @JvmStatic
    @Synchronized
    fun clearCover(ctx: Context, dollId: String?): Boolean {
        return applyCover(ctx, dollId, "")
    }

    private fun applyCover(ctx: Context, dollId: String?, cover: String): Boolean {
        val list = load(ctx)
        for (i in list.indices) {
            if (list[i].id == dollId) {
                list[i] = list[i].copy(cover = cover)
                save(ctx, list)
                return true
            }
        }
        return false
    }

    /**
     * 【r13】把某个动作的**某一帧**设为这只人偶的形象图（桌面上真正画出来的那张）。
     *
     * 【与封面的区别】封面只影响列表卡片；形象图决定桌宠长什么样。
     *   两者都从「动作库的某一帧」来，用户不必再单独准备图片。
     */
    @JvmStatic
    @Synchronized
    fun setSpriteFromFrame(ctx: Context, dollId: String?, framePath: String?): Boolean {
        if (dollId.isNullOrEmpty() || framePath.isNullOrEmpty()) {
            return false
        }
        val saved = saveImageFile(ctx, File(framePath), PREFIX_SPRITE)
        if (saved.isEmpty()) {
            return false
        }
        val list = load(ctx)
        for (i in list.indices) {
            if (list[i].id == dollId) {
                list[i] = list[i].copy(sprite = saved)
                save(ctx, list)
                return true
            }
        }
        return false
    }

    /** 切换启用态并落盘，返回切换后的状态。内置默认配置同样可切换（它只是不可删除）。 */
    @JvmStatic
    @Synchronized
    fun toggleEnabled(ctx: Context, id: String?): Boolean {
        if (id == null) {
            return false
        }
        val list = load(ctx)
        for (i in list.indices) {
            if (list[i].id == id) {
                val next = !list[i].enabled
                list[i] = list[i].copy(enabled = next)
                save(ctx, list)
                // 【r12 用户定案】「取消启用」= 这只人偶从屏幕消失且**不会自己回来**
                //   ⇒ 算「人偶消失」，给它打上消失时刻；重新启用时据此决定要不要回初始位置。
                //   （PetPrefs 与本类同包，无需 import。）
                if (!next) {
                    PetPrefs.setHiddenAt(ctx, id, System.currentTimeMillis())
                }
                return next
            }
        }
        return false
    }

    /**
     * 复制一项：新 id、名字加「副本」后缀、封面与形象图沿用同一文件（不重复占盘）。
     * 内置项也能复制（复制出来的不是内置，可删）。
     */
    @JvmStatic
    @Synchronized
    fun duplicate(ctx: Context, id: String?): Doll? {
        if (id == null) {
            return null
        }
        val list = load(ctx)
        for (d in list) {
            if (d.id == id) {
                val copy = d.copy(
                    id = newId(),
                    name = uniqueName(list, d.name + " 副本"),
                    enabled = false,
                    builtin = false
                )
                list.add(copy)
                save(ctx, list)
                return copy
            }
        }
        return null
    }

    /**
     * 删除一项。**v0.1.0：内置默认配置也可以删**（删完立刻重建一个出厂的）。
     *
     * ## 用户定案的语义
     * 「如果我默认配置里把这个动作删掉了，我只需要把默认配置删掉，默认配置则会恢复，
     *   同时动作也恢复了」——所以删默认配置 = **彻底重置**：
     *   配置（手势 / 比例 / 初始位置 / 屏幕范围 / 回归时间 / 人格 / 背景 / 记忆）、
     *   动作（19 个归位的内置动作 + 三个固定壳的默认内容）全部回到出厂状态。
     *
     * ## 为什么不是「阻止删除」而是「删了重建」
     * 改造前这里有 `if (builtin) return false`，界面上也不给默认配置显示删除键。
     *   但那样用户就没有任何办法把默认配置玩坏后恢复 —— 只能清数据 / 重装。
     *   现在给它一条正当的「重置」路径，同时自定义人偶仍然可以删光。
     *
     * 【图片】不删文件：可能被复制项 / 历史项共用，孤儿文件由「清理未被引用图片」另行处理。
     */
    @JvmStatic
    @Synchronized
    fun remove(ctx: Context, id: String?): Boolean {
        if (id == null) {
            return false
        }
        val list = load(ctx)
        var hit = -1
        for (i in list.indices) {
            if (list[i].id == id) {
                hit = i
                break
            }
        }
        if (hit < 0) {
            return false
        }
        list.removeAt(hit)
        save(ctx, list)
        // 【为什么必须清配置】r10 起每只人偶都有自己的一份独立配置（手势 / 比例 / 初始位置 /
        //   屏幕范围 / 回归时间 / 交谈 / 性格，见 [DollProfile]）。人偶删了、配置留着，
        //   下一次「新建」万一复用同一个 id，新人偶就会凭空继承上一只的设定 —— 可见的数据串味。
        DollProfile.clear(ctx, id)
        // 【v0.1.0】动作也要清：删人偶之后再新建、万一复用同一个 id，
        //   旧动作会「复活」；而且默认配置的重置语义要求动作一起回出厂。
        DollAnim.clearAll(ctx, id)
        if (id == DEFAULT_ID) {
            // 默认配置删掉 → 立刻重建一个出厂的（排在首位）。
            rebuildDefault(ctx)
        }
        return true
    }

    /**
     * **【v0.1.0·新增】重建出厂状态的默认配置。**
     *
     * 【为什么单独抽出来】两条路都要它：
     *   ① 用户主动删除默认配置（见 [remove]）；
     *   ② 存档损坏 / 首次启动时 [load] 的兜底（保证列表里永远有一个默认配置）。
     *
     * 【「出厂状态」到底清掉了什么】
     *   · 人偶条目本身：名字 / 封面 / 形象全部回到默认（即新建一个 `Doll(DEFAULT_ID, ...)`）；
     *   · 它的全部独立配置（`DollProfile.clear`，含记忆库）；
     *   · 它的全部动作（`DollAnim.clearAll` → 下次 `all()` 会按种子版本补齐 19 个内置动作）。
     *   【不做的】不删用户自己导入的素材文件（可能被别的动作引用），
     *   由「清理未被引用图片」那条既有路径统一处理。
     */
    @JvmStatic
    @Synchronized
    fun rebuildDefault(ctx: Context) {
        val list = load(ctx)
        var idx = -1
        for (i in list.indices) {
            if (list[i].id == DEFAULT_ID) {
                idx = i
                break
            }
        }
        val fresh = Doll(DEFAULT_ID, DEFAULT_NAME, builtin = true)
        if (idx >= 0) {
            list[idx] = fresh
        } else {
            list.add(0, fresh)
        }
        save(ctx, list)
        // 配置与动作一起回出厂。
        DollProfile.clear(ctx, DEFAULT_ID, force = true)
        DollAnim.clearAll(ctx, DEFAULT_ID)
    }

    /* ============================ 图片 ============================ */

    /**
     * 把外部图片复制进人偶私有目录，返回落盘后的绝对路径；失败返回空串。
     * 【压缩】按最长边 1024 缩放（与 ImageStore 同口径），避免相册原图直接进内存。
     */
    @JvmStatic
    fun saveImage(ctx: Context, uri: Uri?, prefix: String): String {
        if (uri == null) {
            return ""
        }
        return try {
            // 【r20·修流泄漏】decodeStream 抛异常时原 close 被跳过 → fd 泄漏。
            val raw = ctx.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return ""
            if (raw == null) {
                return ""
            }
            encode(ctx, raw, prefix)
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 同 [saveImage]，但来源是**本地文件路径**（选图通道拷出的字节副本）。
     * 【为什么共用 [encode]】编码格式（PNG 无损）、缩放口径（最长边 1024）、命名规则必须与
     *   Uri 版完全一致，否则两条导入路径产出的资产会在后续读取 / 导出上分叉。
     */
    @JvmStatic
    fun saveImageFile(ctx: Context, src: File?, prefix: String): String {
        if (src == null || !src.isFile) {
            return ""
        }
        return try {
            val raw = BitmapFactory.decodeFile(src.absolutePath)
            if (raw == null) {
                return ""
            }
            encode(ctx, raw, prefix)
        } catch (t: Throwable) {
            ""
        }
    }

    /** 解码后的位图 → 缩放 → PNG 落盘，返回绝对路径；失败返回空串。两条导入路径共用。 */
    private fun encode(ctx: Context, raw: Bitmap, prefix: String): String {
        // 【r22·修位图泄漏】原写法只关流、不管位图：compress 抛异常（磁盘满 / OOM）时，
        //   `scaled` 与 `raw` 都不会被 recycle —— 一张 1024 长边的 ARGB 图约 4MB，
        //   用户连选几张坏图就能把堆推到 OOM。改成 finally 里统一回收。
        var scaled: Bitmap? = null
        return try {
            val s = scaleDown(raw)
            scaled = s
            val file = File(dir(ctx), prefix + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6) + ".png")
            // 【r20·修流泄漏】compress 抛异常（磁盘满 / OOM）时原 close 被跳过 → fd 泄漏。
            FileOutputStream(file).use { out ->
                s.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            file.absolutePath
        } catch (t: Throwable) {
            ""
        } finally {
            // 先回收缩放结果（可能与 raw 是同一个实例，所以要在 raw 之前判断）。
            val s = scaled
            if (s != null && s !== raw && !s.isRecycled) {
                s.recycle()
            }
            if (!raw.isRecycled) {
                raw.recycle()
            }
        }
    }

    /** 形象图路径 → 位图；空路径回落到内置 `pet_front`。 */
    @JvmStatic
    fun spriteOf(ctx: Context, doll: Doll?): Bitmap? {
        val path = if (doll == null) "" else doll.sprite
        if (path.isNotEmpty()) {
            val bmp = decodeFile(path)
            if (bmp != null) {
                return bmp
            }
        }
        return BitmapFactory.decodeResource(ctx.resources, R.drawable.pet_front)
    }

    /**
     * 封面路径 → 位图；**未设置封面时返回 null**（由调用方显示占位）。
     *
     * 【r13 修 bug·新建模板不该自带封面】用户原话：「创建的空白模板 封面得是空的
     *   不要再用这个封面了」。原来这里在封面为空时会一路回落
     *   「形象图 → 内置 pet_front」，于是空白模板也顶着一张内置人偶的图，
     *   用户以为「模板里已经有东西了」。
     *   现在语义收紧：**封面就是封面**，没设就是没设（null），不做任何回落。
     *
     * 【形象图不受影响】[spriteOf] 仍然保留「空 → 内置 pet_front」的回落，
     *   因为桌宠**必须**画出点东西；而列表封面只是一张缩略图，留空是合理的表达。
     */
    @JvmStatic
    fun coverOf(ctx: Context, doll: Doll?): Bitmap? {
        return coverOf(ctx, doll, 1024)
    }

    /**
     * 【r24·修内存与主线程卡顿】按目标边长解码封面。
     *
     * 【为什么需要这个重载】原来只有无参版本，内部写死 1024 —— 于是**所有**调用方
     * （46dp 的联系人头像、34dp 的群组成员头像、会话列表 96px 头像）都按 1024 解码：
     * 一张 1024×1024 的 ARGB_8888 = **4MB**。会话列表几十行就是上百 MB，
     *   而且这些解码还发生在**主线程的组合阶段**（`remember { coverOf(...) }`），
     *   表现是「进聊天列表 / 联系人页卡一下」。
     *
     * 【口径】缩略图一律按「显示 dp × 3」传，取 2 的幂附近的近似值即可；
     *   `ImageDecode` 内部会把 inSampleSize 向上取整，视觉零差异、内存按平方下降。
     *
     * @param reqPx 目标最长边（px）。传 <= 0 表示要原始尺寸（仅导出等场景用）
     */
    @JvmStatic
    fun coverOf(ctx: Context, doll: Doll?, reqPx: Int): Bitmap? {
        val path = if (doll == null) "" else doll.cover
        if (path.isEmpty()) {
            return null
        }
        return decodeFile(path, reqPx)
    }

    private fun decodeFile(path: String, reqPx: Int = 1024): Bitmap? {
        // 【r18·内存】统一走 ImageDecode 采样解码。
        //   下游 scaleDown 本来就压到 ≤1024，解码时直接采样到 1024 量级
        //   可省掉「全尺寸解码 → 再缩放」的中间全尺寸 Bitmap（峰值内存减半以上）。
        // 【r24】具体目标由调用方决定：缩略图传小值，别一律 1024。
        val target = if (reqPx <= 0) 1024 else reqPx
        return com.dollhouse.app.core.ImageDecode.file(path, reqWidth = target, reqHeight = target)
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val max = Math.max(w, h)
        if (max <= 1024) {
            return src
        }
        val ratio = 1024.0f / max
        val nw = Math.max(1, Math.round(w * ratio))
        val nh = Math.max(1, Math.round(h * ratio))
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        return if (out === src) src else out
    }

    /* ============================ 导出 ============================ */

    /**
     * 导出全部人偶：配置 JSON + 图片文件，落到外部私有目录 `export/doll-<时间戳>/`。
     * 返回导出目录绝对路径；失败返回空串。
     */
    @JvmStatic
    @Synchronized
    fun export(ctx: Context): String {
        return try {
            val stamp = System.currentTimeMillis()
            val base = File(ctx.getExternalFilesDir(null), "export/doll-" + stamp)
            if (!base.exists() && !base.mkdirs()) {
                return ""
            }
            val arr = JSONArray()
            for (d in load(ctx)) {
                val o = toJson(d)
                // 图片以文件名形式随包带走，路径不写进导出文件（换设备后路径必然失效）。
                if (d.cover.isNotEmpty()) {
                    copyInto(File(d.cover), base)
                }
                if (d.sprite.isNotEmpty()) {
                    copyInto(File(d.sprite), base)
                }
                arr.put(o)
            }
            val meta = JSONObject()
            meta.put("version", 1)
            meta.put("exportedAt", stamp)
            meta.put("dolls", arr)
            val f = File(base, "dolls.json")
            // 【r20·修流泄漏】write 抛异常时原 close 被跳过。
            FileOutputStream(f).use { out ->
                out.write(meta.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
            }
            base.absolutePath
        } catch (t: Throwable) {
            ""
        }
    }

    private fun copyInto(src: File, dir: File) {
        try {
            if (!src.exists()) {
                return
            }
            val dst = File(dir, src.name)
            if (dst.exists()) {
                return
            }
            src.inputStream().use { ins ->
                dst.outputStream().use { outs -> ins.copyTo(outs) }
            }
        } catch (t: Throwable) {
        }
    }

    /* ============================ 内部 ============================ */

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /** 把一份已落盘的形象图登记为新的人偶（名字自动去重）并持久化。两条导入路径共用。 */
    private fun addDoll(ctx: Context, spritePath: String, name: String): Doll {
        val list = load(ctx)
        val doll = Doll(id = newId(), name = uniqueName(list, name), sprite = spritePath)
        list.add(doll)
        save(ctx, list)
        return doll
    }

    private fun newId(): String {
        return "d" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().substring(0, 4)
    }

    /** 名字去重：重名则依次尝试「名字 2」「名字 3」… */
    private fun uniqueName(list: List<Doll>, want: String): String {
        var name = want
        var n = 2
        while (true) {
            var dup = false
            for (d in list) {
                if (d.name == name) {
                    dup = true
                    break
                }
            }
            if (!dup) {
                return name
            }
            name = want + " " + n
            n++
        }
    }

    private fun load(ctx: Context): MutableList<Doll> {
        val hit = cache
        if (hit != null) {
            return hit
        }
        val list = ArrayList<Doll>()
        try {
            val raw = PetPrefs.get(ctx).getString(KEY_DOLLS, "")
            if (!raw.isNullOrEmpty()) {
                val arr = JSONArray(raw)
                // 【r13·修 bug·列表重复】按 id 去重 —— 存档里同一 id 出现多次时，
                //   列表会把同一只人偶画好几遍（用户截图里那串「默认配置」）。
                //
                // 【为什么会出现重复】存档是一个 JSON 数组，由「读-改-写」维护；
                //   两个写操作若在「读」与「写」之间交错（例如服务侧与 UI 侧同时写），
                //   后写的会把先写的整份数组覆盖，或把同一个条目追加两次。
                //   在**唯一入口**（这里）按 id 收敛，比在每个写入点加锁更稳：
                //   无论历史存档被搞成什么样，读出来的一定是「一 id 一条」。
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty() || !seen.add(id)) {
                        continue
                    }
                    list.add(
                        Doll(
                            id = id,
                            name = o.optString("name", DEFAULT_NAME),
                            cover = o.optString("cover", ""),
                            sprite = o.optString("sprite", ""),
                            enabled = o.optBoolean("enabled", false),
                            builtin = o.optBoolean("builtin", false),
                            // 【r14·老数据迁移】缺键一律空串 = 未分组；
                            //   老存档里没有这个字段，读出来自动归到「未分组」，无需迁移脚本。
                            group = o.optString("group", "")
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            list.clear()
        }
        // 默认配置必须存在且排首位：首次启动 / 存档损坏时在这里补齐，调用方无需判断。
        var found = -1
        for (i in list.indices) {
            if (list[i].id == DEFAULT_ID) {
                found = i
                break
            }
        }
        if (found < 0) {
            list.add(0, Doll(DEFAULT_ID, DEFAULT_NAME, builtin = true))
        } else if (found > 0) {
            val d = list.removeAt(found)
            list.add(0, d.copy(builtin = true))
        } else {
            list[0] = list[0].copy(builtin = true)
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: MutableList<Doll>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(toJson(d))
        }
        PetPrefs.get(ctx).edit().putString(KEY_DOLLS, arr.toString()).commit()
        cache = list
    }

    private fun toJson(d: Doll): JSONObject {
        val o = JSONObject()
        o.put("id", d.id)
        o.put("name", d.name)
        o.put("cover", d.cover)
        o.put("sprite", d.sprite)
        o.put("enabled", d.enabled)
        o.put("builtin", d.builtin)
        // 【r14】分组归属。为空时不写键，保持老存档格式干净（读端缺键即「未分组」）。
        if (d.group.isNotEmpty()) {
            o.put("group", d.group)
        }
        return o
    }
}
