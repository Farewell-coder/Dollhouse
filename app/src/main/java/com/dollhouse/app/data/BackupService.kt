package com.dollhouse.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 【职责】设置页「导出完整备份 / 从备份恢复」的入口。
 *
 * ## 为什么这个类现在只剩一层转发
 * 改造前它是**全部**备份逻辑的所在地（210 行），只导供应商 / 模型 / 人偶记录 /
 * 记忆 / 三个开关，且恢复时明确「不新建人偶」（那时 DollStore 没有可用的插入接口）。
 * 用户换机后拿到的是没有人设、没有聊天、没有封面的空壳 —— 这正是本次要消灭的失败态。
 *
 * 真正的实现搬到了 [AllInOneArchive]（v2 全量存档）。本类**保留原名与原签名**，
 * 原因是它已经是 UI 与备份文件的公共契约：
 * · 设置页调 `export(ctx, appVersion): JSONObject` / `restore(ctx, root): String`；
 * · [BackupEnvelope] / [BackupFile] 与这两步一起构成「组装 → 落盘 → 分享」的链路。
 * 一次性把所有调用点改名，会和正在并行改造的界面任务正面撞车；
 * 留一层薄壳则让「数据层换引擎」这件事对上层完全透明。
 *
 * ## 契约（不要改）
 * · [export]：返回可直接 `toString()` 成文件的 [JSONObject]；
 * · [restore]：返回**一句能直接念给用户听的中文摘要**（设置页把它原样显示），
 *   不是 JSON、不是多段日志。
 *
 * 【线程】与改造前一致：两方法都会读写偏好与图片文件，**调用方必须放在后台线程**。
 *   设置页已有的 `Thread { }` 包裹不需要改动。
 */
object BackupService {

    /**
     * 导出：把当前数据组装成 v2 信封。
     *
     * @param appVersion 写进信封的来源版本，仅用于排查（不参与恢复决策）
     */
    @JvmStatic
    fun export(ctx: Context, appVersion: String): JSONObject {
        return AllInOneArchive.export(ctx, appVersion)
    }

    /**
     * 恢复：把信封写回本机。
     *
     * 【口径】只新增 / 只补齐，不删除目标机现有数据；图片文件会写回私有目录。
     *   旧版（v1）备份按「缺的段为空」照常导入，并在摘要里说明少了什么 ——
     *   让一份老文件打不开，比少恢复几段更糟。
     *
     * @return 一句可直接给用户看的摘要
     */
    @JvmStatic
    fun restore(ctx: Context, root: JSONObject?): String {
        return AllInOneArchive.restore(ctx, root)
    }
}