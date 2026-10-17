package com.dollhouse.app.pet.render

/**
 * 【职责】引擎注册表 —— **按 engine 名取渲染器**的唯一入口。
 *
 * 【核心价值：加引擎 = 1 个新文件 + 1 处注册】
 *   改造前加一种渲染方式要改 **7–8 处**（`PetView.onDraw` + `bakeDraw` + `bakeBounds`
 *   + `AnimBaker.timeline` + `DollAnim.Plan` + `DollAnim.all()` + 两个 UI 页），
 *   其中 3 处是几何核心 —— 这也是本次重构要消灭的成本。
 *   现在只需：① 写一个实现 [PetRenderer] 的类；② 在 [factories] 加一行。
 *
 * 【为什么用「工厂函数」而不是「单例实例」】渲染器**有状态**（当前资源、播放进度、
 *   宿主引用），多个窗口必须各持一份。工厂能保证每次 [create] 拿到独立实例。
 *
 * 【为什么用 linkedMapOf】保留注册顺序 —— `supported()` 返回的列表会被 UI 直接用来
 *   渲染选择项，顺序稳定才不会每次进页面都跳一下。
 */
object RendererRegistry {

    private val factories = linkedMapOf<String, () -> PetRenderer>(
        Engine.SPRITE to { SpriteRenderer() },
        Engine.LOTTIE to { LottieRenderer() },
        Engine.ANIMIMAGE to { AnimatedImageRenderer() }
    )

    /**
     * 创建一个渲染器实例。
     *
     * @return 未知引擎返回 **null**（调用方据此提示「引擎不支持」，而不是悄悄回落 ——
     *   静默回落会让用户以为「选 Lottie 但画出来是位图」是正常行为）。
     */
    @JvmStatic
    fun create(engine: String?): PetRenderer? {
        val f = factories[engine] ?: return null
        return f.invoke()
    }

    /** 已登记的引擎名（顺序稳定，供 UI 列举）。 */
    @JvmStatic
    fun supported(): List<String> = factories.keys.toList()

    /**
     * 第三方扩展口 —— 注册自定义引擎。
     *
     * 【为什么要公开这个口子】任务书 §1.3 第 8 条要求「为第三方扩展预留能力」。
     *   有它，外部代码不用改本工程源码就能接入新引擎。
     */
    @JvmStatic
    fun register(engine: String, factory: () -> PetRenderer) {
        if (engine.isEmpty()) {
            return
        }
        factories[engine] = factory
    }

    /** 引擎是否已登记。 */
    @JvmStatic
    fun has(engine: String?): Boolean = engine != null && factories.containsKey(engine)
}