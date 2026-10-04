package com.dollhouse.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 【职责】桌宠手势动作注册表：手势 id → 具体响应。
 *
 * 【入口】PetService 判定出手势后调 perform(id, this)。
 *
 * 【交互】只做查表与派发，不持有状态；具体行为在 PetAction 实现里。
 *
 * 【扩展】新增手势 = 在 ACTIONS 加一行（先注册的优先）。
 *
 * 【坑】未知 id 静默忽略（与拆前 if/else 分支漏掉时行为一致），不要抛异常打断触摸流程。
 */
final class PetActionRegistry {
    /** 单击：原地跳一下。 */
    private static final String ID_TAP = "tap";
    /** 双击：无动作（原「开合聊天窗口」已取消，双击不再进全屏聊天页）。 */
    private static final String ID_DOUBLE_TAP = "double_tap";
    /** 三击：召唤迷你输入框（人偶趴到框顶说话）。 */
    private static final String ID_TRIPLE_TAP = "triple_tap";
    private static final List<PetAction> ACTIONS = new ArrayList<PetAction>();
    static {
        ACTIONS.add(new PetAction() {
            @Override
            public String id() {
                return ID_TAP;
            }
            @Override
            public String label() {
                return "点一下：跳一下，并随机说一句";
            }
            @Override
            public void run(PetService host) {
                host.petJump();
                // 【补回 v2.5】v2.4 删 chatter 时只剩 petJump()，单击再也不出词
                //   （用户报「点击没有消息弹出来」）。这里补回「跳一下 + 随机说一句」。
                String[] lines = PetService.LINES_TAP;
                if (lines != null && lines.length > 0) {
                    host.say(lines[host.random.nextInt(lines.length)], 2400L);
                }
            }
        });
        ACTIONS.add(new PetAction() {
            @Override
            public String id() {
                return ID_DOUBLE_TAP;
            }
            @Override
            public String label() {
                return "双击：无动作";
            }
            @Override
            public void run(PetService host) {
                // 【改动】双击不再开聊天（改由三击召唤迷你输入框）；
                //   入口保留为空实现，方便以后重新启用时不至于找不到接线点。
            }
        });
        ACTIONS.add(new PetAction() {
            @Override
            public String id() {
                return ID_TRIPLE_TAP;
            }
            @Override
            public String label() {
                return "三击：迷你聊天";
            }
            @Override
            public void run(PetService host) {
                host.openMiniTalk();
            }
        });
    }
    private PetActionRegistry() {
    }
    /** 按手势 id 派发；未知 id 直接忽略。 */
    static void perform(String id, PetService host) {
        for (int i = 0; i < ACTIONS.size(); i++) {
            if (ACTIONS.get(i).id().equals(id)) {
                ACTIONS.get(i).run(host);
                return;
            }
        }
    }
}
