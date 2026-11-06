package com.dollhouse.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备命令风险分级：不可逆操作必须被拒，只读不能被误伤。 */
class ShellRiskTest {

    @Test fun `常见只读查询判为 READ`() {
        assertEquals(ShellRisk.Level.READ, ShellRisk.level("pm list packages"))
        assertEquals(ShellRisk.Level.READ, ShellRisk.level("getprop ro.product.model"))
        assertEquals(ShellRisk.Level.READ, ShellRisk.level("ps -A | grep chrome"))
        assertEquals(ShellRisk.Level.READ, ShellRisk.level("dumpsys battery"))
    }

    @Test fun `改设置与启停应用判为 WRITE`() {
        assertEquals(ShellRisk.Level.WRITE, ShellRisk.level("settings put system screen_off_timeout 60000"))
        assertEquals(ShellRisk.Level.WRITE, ShellRisk.level("am force-stop com.tencent.mm"))
        assertEquals(ShellRisk.Level.WRITE, ShellRisk.level("svc wifi disable"))
    }

    @Test fun `卸载清数据与删除判为 DANGER`() {
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("pm uninstall com.example.app"))
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("pm clear com.example.app"))
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("rm -rf /sdcard/DCIM"))
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("reboot"))
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("settings delete system screen_off_timeout"))
    }

    @Test fun `大小写与多空格不能绕过判定`() {
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("PM   UNINSTALL  com.example"))
        assertEquals(ShellRisk.Level.DANGER, ShellRisk.level("Rm  -Rf   /sdcard/x"))
        assertEquals(ShellRisk.Level.WRITE, ShellRisk.level("SETTINGS\tPUT system x y"))
    }

    @Test fun `空命令按只读处理不误报`() {
        assertEquals(ShellRisk.Level.READ, ShellRisk.level(null))
        assertEquals(ShellRisk.Level.READ, ShellRisk.level("   "))
    }

    @Test fun `拒绝文案不泄漏内部规则且指出建议`() {
        val t = ShellRisk.rejectText("pm uninstall com.x")
        assertTrue(t.contains("拒绝执行"))
        assertTrue(t.contains("只读查询"))
        assertTrue(t.length < 200)
    }

    @Test fun `超长命令在提示里被截断`() {
        // 走公开的 rejectText：它内部会对命令做截断，避免把长脚本回显到对话里。
        val t = ShellRisk.rejectText("pm uninstall " + "a".repeat(300))
        assertTrue(t.length < 200)
        assertTrue(t.contains("…"))
    }
}