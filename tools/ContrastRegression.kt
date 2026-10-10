package com.dollhouse.app.ui.theme

/*
 * 【用途】ContrastCore 的纯 JVM 回归脚本（不依赖 Android、不跑 Gradle）。
 *
 * 【怎么跑】用 kotlinc 直接编译本文件 + ContrastCore.kt（不经过 Gradle）：
 *   kotlinc tools/ContrastRegression.kt \
 *           app/src/main/java/com/dollhouse/app/ui/theme/ContrastCore.kt \
 *           -include-runtime -d /tmp/contrast.jar
 *   java -cp /tmp/contrast.jar com.dollhouse.app.ui.theme.ContrastRegressionKt
 * 退出码 0 = 全部通过；非 0 = 有回归。
 */

private var failed = 0
private var total = 0

private fun check(name: String, cond: Boolean) {
    total++
    if (!cond) {
        failed++
        println("FAIL: $name")
    }
}

fun main(args: Array<String>) {
    val cc = ContrastCore

    // 1) 中灰底：必须选对比度更高的黑白方向并达标（旧实现按 bg 亮度 0.5 判定会选错方向）。
    var midGrayOk = true
    for (lum in 0..255 step 15) {
        val bg = 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum
        val out = cc.ensureContrast(0xFF808080.toInt(), bg, cc.AA_NORMAL)
        if (cc.contrastRatio(out, bg) < cc.AA_NORMAL - 1e-6) midGrayOk = false
    }
    check("mid-gray bg picks the higher-contrast pole and meets AA", midGrayOk)

    // 2) 半透明前景：推到极点仍不够时必须提高不透明度来达标。
    val bgDark = 0xFF202020.toInt()
    val fixed = cc.ensureContrast(cc.withAlpha(0xFFFFFFFF.toInt(), 40), bgDark, cc.AA_NORMAL)
    check("translucent fg meets AA", cc.contrastRatio(fixed, bgDark) >= cc.AA_NORMAL - 1e-6)
    check("translucent fg ends more opaque", cc.alphaOf(fixed) > 40)

    // 3) 任意底色 + 不透明前景：AA 一定可达（黑白其中一侧必然 >= 4.58）。
    var reachable = true
    for (r in 0..255 step 51) {
        for (g in 0..255 step 51) {
            for (b in 0..255 step 51) {
                val bg = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                val out = cc.ensureContrast(0xFF4C6FDE.toInt(), bg, cc.AA_NORMAL)
                if (cc.contrastRatio(out, bg) < cc.AA_NORMAL - 1e-6) reachable = false
            }
        }
    }
    check("opaque fg always reachable", reachable)

    // 4) 遮罩：对极亮 / 极暗 / 中灰图片区域都要能达标（不依赖平均色）。
    val text = 0xFF22315B.toInt()
    val overlay = 0xFFF4F5F9.toInt()
    for (img in intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF7F7F7F.toInt())) {
        val a = cc.minOverlayAlpha(img, overlay, text, cc.AA_NORMAL)
        val composed = cc.composite(cc.withAlpha(overlay, a), img)
        check("overlay meets AA for img=0x${Integer.toHexString(img)}",
                cc.contrastRatio(text, composed) >= cc.AA_NORMAL - 1e-6)
    }

    println("ContrastRegression: ${total - failed}/$total passed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
