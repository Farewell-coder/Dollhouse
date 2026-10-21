package com.dollhouse.app.agent

import android.content.Context
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.device.ShizukuBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】工具注册表：集中登记全部可被模型调用的工具，并生成请求用的 tools schema。
 *
 * 【入口】ChatPanel.askModel → buildSchema()；ChatPanel.runTools → execute(name, args)。
 *
 * 【交互】不持有任何状态，纯查表；执行全在具体 ChatTool 实现里。
 *
 * 【扩展】新增工具 = 写好实现类，然后在 TOOLS 里加一行。
 *
 * 【坑】name() 必须唯一，重名时先注册的优先；未注册的名字一律回「没有名为 X 的工具。」。
 */
object ChatToolRegistry {
    private val TOOLS: MutableList<ChatTool> = ArrayList()
    /** 联网工具的注册名：它是否随请求下发由用户开关决定，执行入口则始终保留。 */
    private const val NAME_WEB = "web_search"
    /** 记忆工具的注册名：同上，是否下发由加号面板里的「自动保存记忆」决定。 */
    private const val NAME_REMEMBER = "remember"
    /** 系统命令工具的注册名：不下发时也不影响「未授权→不出现」的门控语义。 */
    private const val NAME_SHELL = "shell"
    /** 设备控制工具的注册名：lamda 服务没跑时不下发，避免模型反复空调用。 */
    private const val NAME_DEVICE = LamdaTool.NAME

    /**
     * 【运行期上下文】当前这轮工具调用属于哪只人偶。
     *
     * 【为什么必须存在】工具执行时只有 (name, arguments) 两个参数，拿不到「正在跟谁聊」。
     *   而记忆工具的写入必须落到**当前会话绑定的那只人偶**的库 —— 旧实现写死
     *   `DollStore.DEFAULT_ID`，于是所有 AI 自己记的东西都堆进「默认配置」一个桶，
     *   其它人偶永远读不到、彼此之间还会串。
     *
     * 【谁写它】ChatPanel.askModel / PetTalk 在发起请求或跑工具前调 [setDoll]。
     *   【谁读它】MemoryTool.execute。
     */
    private val DOLL = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** 声明「这一轮属于哪只人偶」。传空等于退回默认配置。 */
    @JvmStatic
    fun setDoll(dollId: String?) {
        DOLL.set(if (dollId.isNullOrEmpty()) null else dollId)
    }

    /** 当前这一轮的人偶 id；没设置时返回 null（调用方自行回落默认）。 */
    @JvmStatic
    fun doll(): String? {
        return DOLL.get()
    }

    init {
        TOOLS.add(SearchTool())
        // Shizuku 系统命令工具：常驻注册，是否真正下发由 buildSchema 的授权门控决定。
        TOOLS.add(ShellTool())
        // lamda 设备控制工具：同样常驻注册，门控在 buildSchema。
        TOOLS.add(LamdaTool())
    }

    /**
     * 注册需要 Context 的工具（记忆工具要拿偏好库）。
     * 【坑】幂等：ChatPanel 每次构造都会调，重复 add 会让 schema 里出现两份同名工具。
     */
    @JvmStatic
    fun ensure(ctx: Context?) {
        if (ctx == null) {
            return
        }
        for (i in TOOLS.indices) {
            if (NAME_REMEMBER == TOOLS[i].name()) {
                return
            }
        }
        TOOLS.add(MemoryTool(ctx))
    }

    /**
     * 按名字执行；未命中返回兜底文案，不抛异常。
     *
     * 【门控必须作用在执行侧，不能只作用在下发侧】
     *   `buildSchema` 只是决定「这次请求里带不带这个工具的说明书」。
     *   但模型是**有上下文记忆**的：同一段对话里它可能在前几轮见过 `web_search`，
     *   或者被系统提示词带着直接吐出一个 `web_search` 调用 —— 此时即使这一轮没给说明书，
     *   工具名依然会到达这里。旧实现只在下发侧门控、执行侧一律照跑，结果是：
     *   用户明明把「联网」关掉了，只要模型还记得这个工具名，它就能真的发出网络请求。
     *   同理，「自动保存记忆」关掉后，记忆工具仍可被调用写入。
     *
     *   因此这里和 [buildSchema] 共用同一份判据（[isAllowed]），不另写一遍条件 ——
     *   两边各写一遍是最典型的「改一处忘一处」来源。
     *
     * @param webSearch 本轮是否允许联网工具（来自用户开关）。
     * @param remember  本轮是否允许记忆写入（来自用户开关）。
     */
    @JvmStatic
    fun execute(name: String, arguments: String?, webSearch: Boolean, remember: Boolean): String {
        if (!isAllowed(name, webSearch, remember)) {
            // 不回「没有这个工具」：模型会当成自己记错名字，接着换个写法重试，烧上下文。
            // 直接说明「被主人关掉了」，它就会改用已有信息作答。
            return "工具 " + name + " 当前已被主人关闭，不要再次调用，请直接回答。"
        }
        return execute(name, arguments)
    }

    /** 按名字执行（不门控；仅供内部已确认放行的调用点使用）。 */
    @JvmStatic
    fun execute(name: String, arguments: String?): String {
        for (i in TOOLS.indices) {
            val chatTool = TOOLS[i]
            if (chatTool.name() == name) {
                return chatTool.execute(arguments)
            }
        }
        return "没有名为 " + name + " 的工具。"
    }

    /**
     * 生成请求体的 tools 数组；两个全关时返回空数组（上层按 length>0 判断，不会下发空 tools）。
     * webSearch=false 时不下发联网工具；remember=false 时不下发记忆写入工具
     * （执行入口始终保留，所以「手动整理记忆」之类不依赖它）。
     * 【门控】shell 工具只在 Shizuku 已授权且服务运行时才下发 —— 没权限还把它摆给模型，
     *   模型会反复调用、每次只拿到「未获得 Shizuku 授权」，白白烧上下文。
     *   这里用静态判定（不依赖 Context），调用方无需改签名。
     */
    @JvmStatic
    fun buildSchema(webSearch: Boolean, remember: Boolean): JSONArray {
        val jSONArray = JSONArray()
        try {
            for (i in TOOLS.indices) {
                val chatTool = TOOLS[i]
                // 【唯一判据】与 execute 共用 isAllowed —— 见那里的注释。
                if (!isAllowed(chatTool.name(), webSearch, remember)) {
                    continue
                }
                val jSONObject = JSONObject()
                jSONObject.put("type", "object")
                jSONObject.put("properties", chatTool.properties())
                jSONObject.put("required", chatTool.required())
                val jSONObject2 = JSONObject()
                jSONObject2.put("name", chatTool.name())
                jSONObject2.put("description", chatTool.describe())
                jSONObject2.put("parameters", jSONObject)
                val jSONObject3 = JSONObject()
                jSONObject3.put("type", "function")
                jSONObject3.put("function", jSONObject2)
                jSONArray.put(jSONObject3)
            }
        } catch (unused: Throwable) {
            return jSONArray
        }
        return jSONArray
    }

    /**
     * 这个工具在「本轮开关 + 当前授权状态」下是否可用。**下发与执行共用这一份判据。**
     *
     * 【三道门】
     *  ① 用户开关：联网 / 记忆写入（关掉就不给用）。执行入口始终保留，
     *    所以「手动整理记忆」之类不依赖它 —— 那是别的入口，不走工具。
     *  ② 授权门：`shell` 要 Shizuku 已授权且服务在跑；没权限还摆给模型，
     *    它只会反复调用、每次拿到「未授权」，白烧上下文。
     *  ③ 存活门：`device`（lamda）只在服务真的活着时下发。
     *
     * 【为什么探活结果要缓存】`ShizukuBridge.isReady()` 与 `LamdaManager.aliveCached()`
     *   都可能在主线程被调用（buildSchema 在点击发送时同步执行），
     *   前者走 2s 缓存、后者带 2s 缓存，所以这里是安全的。
     */
    @JvmStatic
    fun isAllowed(name: String?, webSearch: Boolean, remember: Boolean): Boolean {
        if (name == null) {
            return false
        }
        if (!webSearch && NAME_WEB == name) {
            return false
        }
        if (!remember && NAME_REMEMBER == name) {
            return false
        }
        if (NAME_SHELL == name && !ShizukuBridge.isReady()) {
            return false
        }
        if (NAME_DEVICE == name && !LamdaManager.aliveCached()) {
            return false
        }
        return true
    }
}
