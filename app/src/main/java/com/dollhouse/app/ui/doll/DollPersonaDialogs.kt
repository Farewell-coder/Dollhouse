package com.dollhouse.app.ui.doll

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dollhouse.app.data.InjectPos
import com.dollhouse.app.data.LoreEntry
import com.dollhouse.app.data.OutputRule
import com.dollhouse.app.data.PersonaInject
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit

/**
 * 【职责】「角色设定」高级段的三个编辑弹窗（r13 新增）。
 *
 * 【为什么用弹窗而不是子页】这三项都是「填三四个框」的短表单。走子页的话每次
 *   编辑都要进出一次页面栈（还要处理返回时的重铺），而弹窗就地编辑、关闭即回 ——
 *   与工程既有的「暂离 / 回归时间」弹窗同一形态（见 [DollNumDialog]）。
 *
 * 【保存语义】与工程既有弹窗一致：边打字边落盘，按钮只负责关闭。
 *   这样用户中途返回也不会丢内容，同时按钮仍给出「我改完了」的明确回执。
 */
object DollInjectDialog {

    @JvmStatic
    fun show(act: MainActivity, dollId: String, injectId: String?, onDone: () -> Unit) {
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, if (injectId == null) "新增常驻注入" else "常驻注入") {
            Body(act, dollId, injectId) {
                dlgRef?.dismiss()
                onDone()
            }
        }
    }

    @Composable
    private fun Body(act: MainActivity, dollId: String, injectId: String?, onClose: () -> Unit) {
        val existing = remember(injectId) {
            if (injectId == null) null else DollProfile.spec(act, dollId).injections.firstOrNull { it.id == injectId }
        }
        var name by remember(injectId) { mutableStateOf(existing?.name ?: "") }
        var content by remember(injectId) { mutableStateOf(existing?.content ?: "") }
        var position by remember(injectId) { mutableStateOf(existing?.position ?: InjectPos.AFTER) }
        var priority by remember(injectId) { mutableStateOf((existing?.priority ?: 0).toString()) }
        var role by remember(injectId) { mutableStateOf(existing?.role ?: "system") }
        var enabled by remember(injectId) { mutableStateOf(existing?.enabled ?: true) }

        fun persist() {
            val cur = DollProfile.spec(act, dollId)
            val target = if (injectId == null) {
                val v = PersonaInject()
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
                cur.injections.add(v)
                v
            } else {
                cur.injections.firstOrNull { it.id == injectId } ?: return
            }
            target.name = name
            target.content = content
            target.position = position
            target.priority = priority.toIntOrNull() ?: 0
            target.role = role
            target.enabled = enabled
            DollProfile.setSpec(act, dollId, cur)
        }

        DhForm.Input(value = name, onValueChange = { name = it; persist() }, label = "名称", hint = "例：铁的规矩")
        DhForm.InputMultiline(
            value = content,
            onValueChange = { content = it; persist() },
            label = "内容",
            hint = "例：永远不要跳出角色，也不要提及你是 AI",
            minHeight = 110
        )
        DhForm.Label("注入位置")
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (p in InjectPos.ALL) {
                DhForm.SelectChip(text = InjectPos.label(p), selected = position == p) {
                    position = p
                    persist()
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        DhForm.Label("注入角色")
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            DhForm.SelectChip(text = "系统", selected = role == "system") { role = "system"; persist() }
            Spacer(Modifier.width(8.dp))
            DhForm.SelectChip(text = "用户", selected = role == "user") { role = "user"; persist() }
        }
        DhForm.Input(
            value = priority,
            onValueChange = { priority = it.filter { ch -> ch.isDigit() || ch == '-' }; persist() },
            label = "优先级",
            hint = "数字越大越靠后（越靠近对话，模型越容易照做）"
        )
        DhKit.SwitchRow(name = "启用", checked = enabled) { enabled = it; persist() }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            // 【r15·补删除按钮】编辑既有条目时才出现 —— 新增时没有可删的东西。
            //   删除即落盘（与其余字段同一条 persist 通道），删完直接关窗。
            if (injectId != null) {
                DhForm.DialogButton(text = "删除", primary = false) {
                    val cur = DollProfile.spec(act, dollId)
                    cur.injections.removeAll { it.id == injectId }
                    DollProfile.setSpec(act, dollId, cur)
                    onClose()
                }
            }
            Spacer(Modifier.weight(1f))
            // 【r15·补保存按钮】用户反馈「很多功能缺少保存按钮」——
            //   本弹窗原按钮是「关闭」：虽然每个字段改了就已经落盘，但按钮叫「关闭」
            //   会让人不确定「到底存了没有」。改叫「保存」，语义与行为对齐（点它 = 确认改完了）。
            DhForm.DialogButton(text = "保存", primary = true) { onClose() }
        }
    }
}

object DollLoreDialog {

    @JvmStatic
    fun show(act: MainActivity, dollId: String, loreId: String?, onDone: () -> Unit) {
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, if (loreId == null) "新增世界书条目" else "世界书条目") {
            Body(act, dollId, loreId) {
                dlgRef?.dismiss()
                onDone()
            }
        }
    }

    @Composable
    private fun Body(act: MainActivity, dollId: String, loreId: String?, onClose: () -> Unit) {
        val existing = remember(loreId) {
            if (loreId == null) null else DollProfile.spec(act, dollId).lore.firstOrNull { it.id == loreId }
        }
        var name by remember(loreId) { mutableStateOf(existing?.name ?: "") }
        var content by remember(loreId) { mutableStateOf(existing?.content ?: "") }
        // 关键词用逗号分隔的文本编辑，落盘时拆成数组 —— 让用户一次输入多个。
        var keys by remember(loreId) { mutableStateOf(existing?.keywords?.joinToString("，") ?: "") }
        var constant by remember(loreId) { mutableStateOf(existing?.constantActive ?: false) }
        var useRegex by remember(loreId) { mutableStateOf(existing?.useRegex ?: false) }
        var enabled by remember(loreId) { mutableStateOf(existing?.enabled ?: true) }
        var depth by remember(loreId) { mutableStateOf((existing?.scanDepth ?: 4).toString()) }

        fun persist() {
            val cur = DollProfile.spec(act, dollId)
            val target = if (loreId == null) {
                val v = LoreEntry()
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
                cur.lore.add(v)
                v
            } else {
                cur.lore.firstOrNull { it.id == loreId } ?: return
            }
            target.name = name
            target.content = content
            target.keywords.clear()
            for (k in keys.split(',', '，')) {
                val v = k.trim()
                if (v.isNotEmpty()) {
                    target.keywords.add(v)
                }
            }
            target.constantActive = constant
            target.useRegex = useRegex
            target.enabled = enabled
            target.scanDepth = depth.toIntOrNull() ?: 4
            DollProfile.setSpec(act, dollId, cur)
        }

        DhForm.Input(value = name, onValueChange = { name = it; persist() }, label = "名称", hint = "例：学校")
        DhForm.InputMultiline(
            value = content,
            onValueChange = { content = it; persist() },
            label = "内容",
            hint = "聊到时会塞给模型的设定",
            minHeight = 110
        )
        DhForm.Input(
            value = keys,
            onValueChange = { keys = it; persist() },
            label = "关键词",
            hint = "用逗号分隔，例：学校，教室，上课"
        )
        DhKit.SwitchRow(name = "常驻激活（不用匹配）", checked = constant) { constant = it; persist() }
        DhKit.SwitchRow(name = "关键词按正则解释", checked = useRegex) { useRegex = it; persist() }
        DhForm.Input(
            value = depth,
            onValueChange = { depth = it.filter { ch -> ch.isDigit() }; persist() },
            label = "扫描最近几条消息",
            hint = "默认 4"
        )
        DhKit.SwitchRow(name = "启用", checked = enabled) { enabled = it; persist() }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            // 【r15·补删除按钮】编辑既有条目时才出现 —— 新增时没有可删的东西。
            //   删除即落盘（与其余字段同一条 persist 通道），删完直接关窗。
            if (loreId != null) {
                DhForm.DialogButton(text = "删除", primary = false) {
                    val cur = DollProfile.spec(act, dollId)
                    cur.lore.removeAll { it.id == loreId }
                    DollProfile.setSpec(act, dollId, cur)
                    onClose()
                }
            }
            Spacer(Modifier.weight(1f))
            // 【r15·补保存按钮】用户反馈「很多功能缺少保存按钮」——
            //   本弹窗原按钮是「关闭」：虽然每个字段改了就已经落盘，但按钮叫「关闭」
            //   会让人不确定「到底存了没有」。改叫「保存」，语义与行为对齐（点它 = 确认改完了）。
            DhForm.DialogButton(text = "保存", primary = true) { onClose() }
        }
    }
}

object DollRuleDialog {

    @JvmStatic
    fun show(act: MainActivity, dollId: String, ruleId: String?, onDone: () -> Unit) {
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, if (ruleId == null) "新增替换规则" else "替换规则") {
            Body(act, dollId, ruleId) {
                dlgRef?.dismiss()
                onDone()
            }
        }
    }

    @Composable
    private fun Body(act: MainActivity, dollId: String, ruleId: String?, onClose: () -> Unit) {
        val existing = remember(ruleId) {
            if (ruleId == null) null else DollProfile.spec(act, dollId).rules.firstOrNull { it.id == ruleId }
        }
        var name by remember(ruleId) { mutableStateOf(existing?.name ?: "") }
        var find by remember(ruleId) { mutableStateOf(existing?.find ?: "") }
        var replace by remember(ruleId) { mutableStateOf(existing?.replace ?: "") }
        var visual by remember(ruleId) { mutableStateOf(existing?.visualOnly ?: true) }
        var forHer by remember(ruleId) { mutableStateOf(existing?.scopeHer ?: true) }
        var forMe by remember(ruleId) { mutableStateOf(existing?.scopeMe ?: false) }
        var enabled by remember(ruleId) { mutableStateOf(existing?.enabled ?: true) }

        // 【正则校验】写错了要当场说 —— 否则用户只会看到「规则没生效」而查不出为什么。
        val badRegex = remember(find) {
            if (find.isEmpty()) false else try {
                Regex(find)
                false
            } catch (ignored: Throwable) {
                true
            }
        }

        fun persist() {
            val cur = DollProfile.spec(act, dollId)
            val target = if (ruleId == null) {
                val v = OutputRule()
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
                cur.rules.add(v)
                v
            } else {
                cur.rules.firstOrNull { it.id == ruleId } ?: return
            }
            target.name = name
            target.find = find
            target.replace = replace
            target.visualOnly = visual
            target.scopeHer = forHer
            target.scopeMe = forMe
            target.enabled = enabled
            DollProfile.setSpec(act, dollId, cur)
        }

        DhForm.Input(value = name, onValueChange = { name = it; persist() }, label = "名称", hint = "例：去掉括号旁白")
        DhForm.Input(
            value = find,
            onValueChange = { find = it; persist() },
            label = "查找（正则）",
            hint = "例：（[^）]*）"
        )
        if (badRegex) {
            DhForm.Note("这个正则写得不对，规则不会生效。")
        }
        DhForm.Input(
            value = replace,
            onValueChange = { replace = it; persist() },
            label = "替换为",
            hint = "留空 = 删掉匹配到的内容"
        )
        DhKit.SwitchRow(name = "只影响显示（不改历史）", checked = visual) { visual = it; persist() }
        DhKit.SwitchRow(name = "作用于她说的话", checked = forHer) { forHer = it; persist() }
        DhKit.SwitchRow(name = "作用于你说的话", checked = forMe) { forMe = it; persist() }
        DhKit.SwitchRow(name = "启用", checked = enabled) { enabled = it; persist() }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            // 【r15·补删除按钮】编辑既有条目时才出现 —— 新增时没有可删的东西。
            //   删除即落盘（与其余字段同一条 persist 通道），删完直接关窗。
            if (ruleId != null) {
                DhForm.DialogButton(text = "删除", primary = false) {
                    val cur = DollProfile.spec(act, dollId)
                    cur.rules.removeAll { it.id == ruleId }
                    DollProfile.setSpec(act, dollId, cur)
                    onClose()
                }
            }
            Spacer(Modifier.weight(1f))
            // 【r15·补保存按钮】用户反馈「很多功能缺少保存按钮」——
            //   本弹窗原按钮是「关闭」：虽然每个字段改了就已经落盘，但按钮叫「关闭」
            //   会让人不确定「到底存了没有」。改叫「保存」，语义与行为对齐（点它 = 确认改完了）。
            DhForm.DialogButton(text = "保存", primary = true) { onClose() }
        }
    }
}