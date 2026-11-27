package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.InjectPos
import com.dollhouse.app.data.PersonaSpec
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.label
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「角色设定」子页：这只人偶的人设本体 + 注入控制（r13 全面重做）。
 *
 * 【为什么从一个文本框扩成三层】
 *   改造前只有一个「性格描述」多行框 —— 表达力够，但**没有结构**：
 *   世界观、说话风格、称呼、开场白全被用户塞进同一段文字里，模型只能靠猜；
 *   更要命的是没有任何「注入控制」，一段设定永远只能整段进、整段占上下文。
 *   本轮借鉴 Operit 角色卡与 rikkahub 助手的**功能设计**（不是代码），
 *   用 Kotlin 重写为四层折叠 —— 看起来仍是一个页面，但表达力完全不同。
 *
 * 【分层口径】
 *   ① 基础：写完就不用再管的（简介 / 性格 / 世界观 / 说话风格 / 称呼）
 *   ② 对话：开场白 / 示例对话 / 标签
 *   ③ 高级：后置指令 / 常驻注入 / 世界书 / 输出替换
 *   ④ 提示词与角色卡：预览 / 导入 / 导出
 *   四张卡片沿用全局 `DhKit.Card(collapsibleTitle=...)`，折叠状态与设置页共用同一套记忆，
 *   不引入任何新样式 —— 用户要求「不要显得突兀」。
 *
 * 【⚠️ 本页的排版铁律：不要在 Page 的 content 里再套 `weight`】
 *   [DhKit.Page] 已经把 content 放进一个 **wrap-content** 的 Column 里
 *   （Page 内部：外层定高 Column → inner(wrap) → content）。
 *   `Modifier.weight()` 只有在**父级高度确定**时才能分到空间；父级是 wrap-content 时
 *   分配结果为 0，整层高度会塌成 0 —— 症状有两个，且看起来毫不相关：
 *     · 卡片只有标题、内容全不可见；
 *     · 页面触摸区也为 0，点击**穿透**到栈下方的页面（点空白处却触发上一页的按钮）。
 *   所以：**内容直接交给 Page，滚动由 Page 统一提供**（`scrollable` 默认已是 true）。
 *   这与设置页 / 记忆库设置页等既有 Compose 页的写法完全一致。
 *
 * 【落盘时机】全部即时落盘（与其它子页一致）。文本框失焦 / 返回都不影响已写入的内容。
 *
 * 【兼容】原来的「性格描述」自由文本仍是 [DollProfile.persona]，键名不变 ——
 *   老用户升级后那段文字原样出现在第一段，一个字都不丢。
 */
object DollPersonaPage {
    /** 页面 key 前缀：`doll:persona:<dollId>`。 */
    const val PREFIX = "doll:persona:"

    @JvmStatic
    fun open(act: MainActivity, id: String) {
        // 【离开时让上层刷新】人格页的「角色设定」入口带「已设置 / 还没填」状态，
        //   它在编辑页的 Composition 里读一次就定了。本页改完设定后退回，
        //   若没人通知，入口会一直显示旧状态。广播一次「人偶数据已变」即可（编辑页监听它）。
        PageStack.onLeave(PREFIX + id) { DollState.bump() }
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        // 【重读信号】DollProfile 不是 Compose 状态：改了之后必须手动触发重组。
        //   与 DollEditScreen 的 tick 同款做法，保持全页一致。
        var tick by remember(id) { mutableStateOf(0) }
        // 【为什么声明在条件之外】Compose 的 `remember` 必须无条件调用 ——
        //   放进 `if (greeting.isNotEmpty())` 里会让 slot 表随输入长度变化，
        //   输入框一变化就错位（表现为状态乱跳）。这里提到外面，条件只控制「用不用」。
        var greetingReplayed by remember(id) { mutableStateOf(false) }
        val name = remember(id, tick) { DollStore.byId(act, id)?.name ?: "" }
        val persona = remember(id, tick) { DollProfile.persona(act, id) }

        // 【为什么用局部副本】输入框要即时响应（逐字），而写盘走 apply；
        //   若每次输入都从 prefs 重读，光标位置会被重组打断。
        //   【命名】不用 `init` 之类软关键字当变量名，避免阅读时与构造函数块混淆。
        val snapshot = remember(id) { DollProfile.spec(act, id) }
        var brief by remember(id) { mutableStateOf(snapshot.brief) }
        var scenario by remember(id) { mutableStateOf(snapshot.scenario) }
        var style by remember(id) { mutableStateOf(snapshot.style) }
        var callsYou by remember(id) { mutableStateOf(snapshot.callsYou) }
        var youCall by remember(id) { mutableStateOf(snapshot.youCall) }
        var greeting by remember(id) { mutableStateOf(snapshot.greeting) }
        var examples by remember(id) { mutableStateOf(snapshot.examples) }
        var tags by remember(id) { mutableStateOf(snapshot.tags) }
        var postscript by remember(id) { mutableStateOf(snapshot.postscript) }
        var personaText by remember(id) { mutableStateOf(persona) }

        /**
         * 统一落盘入口：改一段写一次，并 tick 一次让列表分区同步。
         *
         * 【为什么每次重新读 spec 再改一个字段】`snapshot` 是进入页面时的快照，
         *   直接改它会绕过「读盘」这一层，一旦有并发写（比如另一个分区同时保存）就会互相覆盖。
         *   每次都从盘上取最新的一份、只改目标字段、再整体写回，是最简单也最安全的做法。
         */
        fun save(block: (PersonaSpec) -> Unit) {
            val cur = DollProfile.spec(act, id)
            block(cur)
            DollProfile.setSpec(act, id, cur)
            tick++
        }

        // 【状态必须在组合期读】QzxList 的 DSL lambda 不是 @Composable ——
        //   在里面调 remember 会编译不过，所以三个集合先在这里算好。
        val injs = remember(id, tick) { DollProfile.spec(act, id).injections }
        val lores = remember(id, tick) { DollProfile.spec(act, id).lore }
        val rules = remember(id, tick) { DollProfile.spec(act, id).rules }

        QzxPage(
            title = "角色设定",
            subtitle = if (name.isNotEmpty()) name else "这只人偶的人设",
            onBack = { PageStack.popIfTopStartsWith(act, PREFIX) },
            backIconRes = Icons.IC_ARROW_LEFT,
        ) {
            QzxList {
                /* ==================== ① 基础 ==================== */
                section("basic", title = "基础", titleIconRes = Icons.IC_BOOK_HEART, collapsible = true) {
                    note("这几段合起来构成「她是谁」，会作为人设整段交给模型。")
                    input("一句话简介", brief)
                        .hint("例：住在手机屏幕上的鲸鱼娘")
                        .onValueChange { brief = it; save { s -> s.brief = it } }
                    input("性格描述", personaText)
                        .hint("写这只人偶的性格、语气、说话习惯等")
                        .multiline(160)
                        .onValueChange {
                            personaText = it
                            // 性格是长文本，用户可能写一大段后直接按返回键；
                            // 即时落盘比「忘记点保存」丢内容安全得多。
                            DollProfile.setPersona(act, id, it)
                        }
                    note("当前 " + personaText.length + " 字。")
                    input("世界观 / 场景", scenario)
                        .hint("例：故事发生在一个虚构的 GalGame 世界里，与现实无关")
                        .multiline(90)
                        .onValueChange { scenario = it; save { s -> s.scenario = it } }
                    input("说话风格", style)
                        .hint("例：说话简短，爱用反问，句尾常带「呢」")
                        .multiline(90)
                        .onValueChange { style = it; save { s -> s.style = it } }
                    // 【原来是并排两列】QzxList 没有并排行型 —— 拆成两行，功能不变。
                    input("她怎么叫你", callsYou)
                        .hint("主人")
                        .onValueChange { callsYou = it; save { s -> s.callsYou = it } }
                    input("你怎么叫她", youCall)
                        .hint("小肥鱼")
                        .onValueChange { youCall = it; save { s -> s.youCall = it } }
                }

                /* ==================== ② 对话 ==================== */
                section("talk", title = "对话", titleIconRes = Icons.IC_MESSAGE_SQUARE, collapsible = true) {
                    note("影响「一开口」的样子，不改变她是谁。")
                    input("开场白", greeting)
                        .hint("新建对话时她说的第一句；留空则不自动开口")
                        .multiline(90)
                        .onValueChange { greeting = it; save { s -> s.greeting = it } }
                    if (greeting.trim().isNotEmpty()) {
                        item(
                            "在聊天里重放开场白",
                            value = if (greetingReplayed) "已重放" else null,
                            iconRes = Icons.IC_CHAT,
                        ).arrow().onClick {
                            if (DollGreeting.insert(act, id)) {
                                greetingReplayed = true
                            }
                        }
                    }
                    input("示例对话", examples)
                        .hint("写两三句示范语气，模型会照着这个调子说话")
                        .multiline(120)
                        .onValueChange { examples = it; save { s -> s.examples = it } }
                    input("标签", tags)
                        .hint("用逗号分隔，例：日常，治愈，傲娇")
                        .onValueChange { tags = it; save { s -> s.tags = it } }
                }

                /* ==================== ③ 高级 ==================== */
                section("advanced", title = "高级", titleIconRes = Icons.IC_SLIDERS_HORIZONTAL, collapsible = true) {
                    note("这几项控制设定「怎么进请求」，看不懂可以完全不填。")
                    input("后置指令", postscript)
                        .hint("例：永远不要跳出角色，也不要提及你是 AI")
                        .multiline(80)
                        .onValueChange { postscript = it; save { s -> s.postscript = it } }
                    note("后置指令放在请求最末尾，权重最高 —— 适合写「铁的规矩」。")

                    label("常驻注入", iconRes = Icons.IC_PLUG)
                    note("不看对话内容，每轮都塞进去。适合放铁的规则。")
                    for (inj in injs) {
                        item(
                            (if (inj.name.isEmpty()) "未命名注入" else inj.name)
                                    + "（" + InjectPos.label(inj.position) + "）",
                            iconRes = Icons.IC_PLUG,
                        ).arrow().onClick {
                            DollInjectDialog.show(act, id, inj.id) { tick++ }
                        }
                    }
                    item("新增常驻注入", iconRes = Icons.IC_PLUS).arrow().onClick {
                        DollInjectDialog.show(act, id, null) { tick++ }
                    }

                    label("世界书", iconRes = Icons.IC_BOOK_OPEN)
                    note("关键词命中才注入 —— 不聊到就不占上下文。适合放支线设定。")
                    for (e in lores) {
                        val label = if (e.name.isEmpty()) "未命名条目" else e.name
                        val hit = if (e.constantActive) "常驻" else (e.keywords.size.toString() + " 个关键词")
                        item(label, value = hit, iconRes = Icons.IC_BOOK_OPEN).arrow().onClick {
                            DollLoreDialog.show(act, id, e.id) { tick++ }
                        }
                    }
                    item("新增世界书条目", iconRes = Icons.IC_PLUS).arrow().onClick {
                        DollLoreDialog.show(act, id, null) { tick++ }
                    }

                    label("输出替换", iconRes = Icons.IC_REFRESH)
                    note("按正则清洗对话文本，用于去掉多余的旁白、动作描写等。")
                    for (r in rules) {
                        item(
                            if (r.name.isEmpty()) "未命名规则" else r.name,
                            value = if (r.enabled) "启用" else "已停用",
                            iconRes = Icons.IC_REFRESH,
                        ).arrow().onClick {
                            DollRuleDialog.show(act, id, r.id) { tick++ }
                        }
                    }
                    item("新增替换规则", iconRes = Icons.IC_PLUS).arrow().onClick {
                        DollRuleDialog.show(act, id, null) { tick++ }
                    }
                }

                /* ==================== ④ 提示词与角色卡 ==================== */
                section("prompt", title = "提示词与角色卡", titleIconRes = Icons.IC_QUOTE, collapsible = true) {
                    item("查看实际提示词", iconRes = Icons.IC_EYE).arrow().onClick {
                        DollPromptPreview.show(act, id)
                    }
                    note("会拼出这一轮真正发给模型的人设，包含命中的世界书。")
                    item("导入角色卡", iconRes = Icons.IC_DOWNLOAD).arrow().onClick {
                        DollCardIO.pickImport(act, id) { tick++ }
                    }
                    note(
                        "支持酒馆（Tavern）角色卡与 Dollhouse 导出的角色卡。\n" +
                            "想把这只人偶完整带走（含聊天与图片），请用设置页的「一键全量备份」。"
                    )
                }
            }
        }
    }
}