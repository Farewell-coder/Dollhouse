package com.dollhouse.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第三方模型包的来源与兼容判定：版本不够要拦，来源不明只提示。 */
class ModelPackageCheckTest {

    private fun check(
        spec: Int = 1,
        minFramework: String? = null,
        author: String? = "someone",
        license: String? = "MIT",
        framework: String = "0.2.6"
    ) = ModelPackageCheck.check(spec, minFramework, author, "1.0", license, framework)

    @Test fun `规范版本更高时拒绝导入`() {
        val r = check(spec = 2)
        assertFalse(r.ok())
        assertTrue(r.errors.first().contains("v2"))
    }

    @Test fun `缺规范版本时拒绝`() {
        assertFalse(check(spec = 0).ok())
    }

    @Test fun `要求更高框架时拒绝并说明当前版本`() {
        val r = check(minFramework = "0.3.0")
        assertFalse(r.ok())
        assertTrue(r.errors.first().contains("0.3.0"))
        assertTrue(r.errors.first().contains("0.2.6"))
    }

    @Test fun `要求更低框架时放行`() {
        assertTrue(check(minFramework = "0.1.0").ok())
        assertTrue(check(minFramework = "0.2.6").ok())
    }

    @Test fun `无法比较的框架版本只提示不拦截`() {
        val r = check(minFramework = "next")
        assertTrue(r.ok())
        assertTrue(r.warnings.any { it.contains("无法比较") })
    }

    @Test fun `缺作者与许可只提示不拦截`() {
        val r = check(author = null, license = null)
        assertTrue(r.ok())
        assertTrue(r.warnings.any { it.contains("没有写作者") })
        assertTrue(r.warnings.any { it.contains("没有声明许可") })
    }

    @Test fun `来源描述拼出作者版本与许可`() {
        val r = check()
        assertTrue(r.origin.contains("作者 someone"))
        assertTrue(r.origin.contains("版本 1.0"))
        assertTrue(r.origin.contains("许可 MIT"))
    }

    @Test fun `版本比较覆盖分段与位数差异`() {
        assertEquals(0, ModelPackageCheck.compareVersion("1.0.0", "1.0"))
        assertTrue(ModelPackageCheck.compareVersion("1.0", "1.0.1")!! < 0)
        assertTrue(ModelPackageCheck.compareVersion("2.0", "1.9.9")!! > 0)
        assertNull(ModelPackageCheck.compareVersion("abc", "1.0"))
    }
}