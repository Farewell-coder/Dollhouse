package com.dollhouse.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * 【附录D-8 回归】`ImageStore.fileFor` 的路径越界收口。
 *
 * 【为什么需要这组用例】原实现是裸的 `File(filesDir, rel)`，对 `..` 不做任何拦截；
 *   而 `exists` / `delete` 全部走它 —— 「删一张图」就足以删掉 `filesDir` 之外的
 *   数据库或配置。这属于安全收口，必须有回归保护，否则后来的人重构一次又漏回去。
 *
 * 【为什么测 `isInsideBase` 而不是 `fileFor`】`fileFor` 需要 `Context`，
 *   纯 JVM 单测里造不出来；`isInsideBase` 是同一条判定的纯函数版本，
 *   逻辑一一对应（`fileFor` 只是把结果翻译成文件对象）。
 */
class ImageStorePathTest {

    /** 用一个临时目录充当 filesDir，避免依赖 Android 环境。 */
    private fun base(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "dh_imgstore_test")
        d.mkdirs()
        return d
    }

    @Test
    fun normalRelativePathIsAccepted() {
        val b = base()
        // 真实记账格式：`chat_img/<文件名>`。
        assertTrue(ImageStore.isInsideBase(b, "chat_img/a.jpg"))
        assertTrue(ImageStore.isInsideBase(b, "chat_img/sub/b.png"))
        // 单层文件也允许（早期版本直接写文件名）。
        assertTrue(ImageStore.isInsideBase(b, "a.jpg"))
    }

    @Test
    fun traversalIsRejected() {
        val b = base()
        // 每一行都是「逃出 filesDir」的形态，必须一律拒绝。
        assertFalse(ImageStore.isInsideBase(b, "../databases/app.db"))
        assertFalse(ImageStore.isInsideBase(b, "../../shared_prefs/cfg.xml"))
        assertFalse(ImageStore.isInsideBase(b, "chat_img/../../databases/app.db"))
        assertFalse(ImageStore.isInsideBase(b, ".."))
        assertFalse(ImageStore.isInsideBase(b, "./../x"))
    }

    @Test
    fun absolutePathIsRejected() {
        val b = base()
        assertFalse(ImageStore.isInsideBase(b, "/data/data/com.dollhouse.app/databases/app.db"))
        assertFalse(ImageStore.isInsideBase(b, "/sdcard/DCIM/a.jpg"))
    }

    @Test
    fun emptyAndBaseItselfAreRejected() {
        val b = base()
        // 空串不是一张图；恰好等于根也不是。
        assertFalse(ImageStore.isInsideBase(b, ""))
        assertFalse(ImageStore.isInsideBase(b, "."))
        assertFalse(ImageStore.isInsideBase(b, "./"))
    }

    @Test
    fun siblingDirectoryWithSamePrefixIsRejected() {
        val b = base()
        // 【关键用例】前缀相同但不是同一个目录：`/x/files_evil` 不能因为
        //   字符串以 `/x/files` 开头就被放行 —— 实现里比较的是
        //   `basePath + File.separatorChar`，所以这里必须为 false。
        val evil = b.name + "_evil/secret.txt"
        assertFalse(ImageStore.isInsideBase(b, "../$evil"))
    }
}
