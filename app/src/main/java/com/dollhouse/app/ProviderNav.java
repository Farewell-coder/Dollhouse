package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * 【职责】「提供商」模块的页面栈与跨页动作：前进 / 返回 / 关页，以及需要跨页复用的动作
 *        （删除确认、连通性测试）。
 *
 * 【为什么要有栈】规格书里这一块是四级结构：供应商列表 → 供应商详情 → 模型列表 → 模型编辑。
 *        原先只有一个「一级 / 二级」的静态字符串，撑不下四级；用栈才不会一按返回就退到桌面。
 *
 * 【路由】用字符串描述栈内每一层，渲染时按栈顶分派到对应页面。新增一层只加一个分支，
 *        不动已有页面。
 */
final class ProviderNav {

    /** 整页挂在 android.R.id.content 上时用的 tag（沿用旧值，返回链无需改动）。 */
    static final String TAG_PAGE = "feiyu_cfg_page";

    static final String R_LIST = "list";
    static final String R_NEW = "new";
    /** 独立思考行为页：从设置页进入，不与供应商栈混在一起。 */
    static final String R_BEHAVIOR = "behavior";
    /** lamda 设备控制页：从设置页进入，同样是独立子页。 */
    static final String R_LAMDA = "lamda";
    private static final String R_EDIT = "edit:";
    private static final String R_MODELS = "models:";
    private static final String R_MNEW = "mnew:";
    private static final String R_MEDIT = "medit:";
    /** 供应商详情页：detail:<providerId>:<tab>。tab 只有 cfg / mdl 两种。 */
    private static final String R_DETAIL = "detail:";
    /** 详情页的「配置」tab：该供应商的名称 / 密钥 / 地址 / 开关。 */
    static final String TAB_CFG = "cfg";
    /** 详情页的「模型」tab：该供应商已添加的模型列表。 */
    static final String TAB_MDL = "mdl";

    /** 页面栈。栈底永远是 R_LIST。 */
    private static final List<String> STACK = new ArrayList<String>();

    private ProviderNav() {
    }

    /* ------------------------------ 入口 / 出口 ------------------------------ */

    /** 设置页「提供商」入口行的落地动作。 */
    static void open(Context ctx) {
        openAt(ctx, R_LIST);
    }

    /** 从设置页直接进某张子页（例如「对话行为」）。栈底仍是该页，返回一次即关门。 */
    static void openAt(Context ctx, String root) {
        Activity act = ApiPageKit.findActivity(ctx);
        if (act == null) {
            return;
        }
        STACK.clear();
        STACK.add(root == null || root.length() == 0 ? R_LIST : root);
        ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // 已有旧页先摘掉，避免同一 tag 挂两层。
        View old = content.findViewWithTag(TAG_PAGE);
        if (old != null) {
            content.removeView(old);
        }
        UiKit.openPage(content, render(act), TAG_PAGE);
    }

    /** 本页是否开着。 */
    static boolean isOpen(Context ctx) {
        try {
            Activity act = ApiPageKit.findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            return content != null && content.findViewWithTag(TAG_PAGE) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 返回键：栈里还有上一层就回退，到底了就关门。永远返回 true（本页开着）。 */
    static boolean handleBack(Context ctx) {
        try {
            Activity act = ApiPageKit.findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            if (STACK.size() > 1) {
                STACK.remove(STACK.size() - 1);
                UiKit.swapPage(content, render(act), TAG_PAGE);
            } else {
                STACK.clear();
                UiKit.closePage(old);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 无论在第几层都直接关门（退出设置页 / 页面重挂时用）。 */
    static boolean closeIfOpen(Context ctx) {
        try {
            Activity act = ApiPageKit.findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            STACK.clear();
            UiKit.closePage(old);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------ 栈操作 ------------------------------ */

    /** 前进一层：新页自右侧滑入。 */
    static void push(Activity act, String route) {
        if (act == null || route == null || route.length() == 0) {
            return;
        }
        STACK.add(route);
        ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        UiKit.openPage(content, render(act), TAG_PAGE);
    }

    /** 原地重建当前层（保存 / 启停 / 删除之后刷新画面）。 */
    static void refresh(Activity act) {
        if (act == null) {
            return;
        }
        ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        UiKit.swapPage(content, render(act), TAG_PAGE);
    }

    /** 子页保存 / 删除完成后的「返回上一层 + 刷新」：一步到位，避免中间态闪一下。 */
    static void back(Activity act) {
        if (act == null) {
            return;
        }
        ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        if (STACK.size() > 1) {
            STACK.remove(STACK.size() - 1);
        }
        UiKit.swapPage(content, render(act), TAG_PAGE);
    }

    /* ------------------------------ 渲染分派 ------------------------------ */

    private static View render(Activity act) {
        String top = STACK.isEmpty() ? R_LIST : STACK.get(STACK.size() - 1);
        if (top.startsWith(R_EDIT)) {
            return ProviderEditPage.build(act, idAfter(top, R_EDIT));
        }
        if (top.startsWith(R_DETAIL)) {
            return ProviderDetailPage.build(act, detailId(top), detailTabOf(top));
        }
        if (top.startsWith(R_MODELS)) {
            return ModelListPage.build(act, idAfter(top, R_MODELS));
        }
        if (top.startsWith(R_MNEW)) {
            return ModelEditPage.buildNew(act, idAfter(top, R_MNEW));
        }
        if (top.startsWith(R_MEDIT)) {
            return ModelEditPage.buildEdit(act, idAfter(top, R_MEDIT));
        }
        if (R_NEW.equals(top)) {
            return ProviderEditPage.build(act, null);
        }
        if (R_BEHAVIOR.equals(top)) {
            return BehaviorPage.build(act);
        }
        if (R_LAMDA.equals(top)) {
            return LamdaPage.build(act);
        }
        return ProviderListPage.build(act);
    }

    private static String idAfter(String route, String prefix) {
        return route.length() > prefix.length() ? route.substring(prefix.length()) : "";
    }

    /* ------------------------------ 路由构造 ------------------------------ */

    static void openProvider(Activity act, String providerId) {
        push(act, R_EDIT + providerId);
    }

    /** 供应商列表点某一行的落地动作：进该供应商的详情页（默认停在「配置」tab）。 */
    static void openDetail(Activity act, String providerId) {
        push(act, R_DETAIL + providerId + ":" + TAB_CFG);
    }

    /**
     * 只改栈顶详情页的 tab。
     * 【为什么不走 push/refresh】两者都会重建整页，重建会把胶囊指示器的滑动动画
     *   一起清掉（页面被换掉，动画没了宿主），看上去就是「颜色啪一下变了」。
     *   这里只改路由字符串，画面由详情页自己就地切内容 + 滑指示器。
     */
    static void setDetailTab(String providerId, String tab) {
        if (STACK.isEmpty()) {
            return;
        }
        STACK.set(STACK.size() - 1, R_DETAIL + providerId + ":" + tabOf(tab));
    }

    /** 详情页路由里的供应商 id。 */
    static String detailId(String route) {
        if (route == null || !route.startsWith(R_DETAIL)) {
            return "";
        }
        String rest = route.substring(R_DETAIL.length());
        int cut = rest.lastIndexOf(':');
        return cut < 0 ? rest : rest.substring(0, cut);
    }

    /** 详情页路由里的 tab。非法值一律回落成「配置」，绝不把页面渲染成空白。 */
    static String detailTabOf(String route) {
        if (route == null || !route.startsWith(R_DETAIL)) {
            return TAB_CFG;
        }
        String rest = route.substring(R_DETAIL.length());
        int cut = rest.lastIndexOf(':');
        return cut < 0 ? TAB_CFG : tabOf(rest.substring(cut + 1));
    }

    private static String tabOf(String t) {
        return TAB_MDL.equals(t) ? TAB_MDL : TAB_CFG;
    }

    /** 栈顶详情页当前的 tab（不在详情页时返回「配置」）。点同 tab 时不重复切。 */
    static String currentDetailTab() {
        return STACK.isEmpty() ? TAB_CFG : detailTabOf(STACK.get(STACK.size() - 1));
    }

    static void openNewProvider(Activity act) {
        push(act, R_NEW);
    }

    static void openModels(Activity act, String providerId) {
        push(act, R_MODELS + providerId);
    }

    static void openNewModel(Activity act, String providerId) {
        push(act, R_MNEW + providerId);
    }

    static void openModel(Activity act, String modelId) {
        push(act, R_MEDIT + modelId);
    }

    /* ------------------------------ 跨页动作：连通性测试 ------------------------------ */

    /** 连通性测试回调。 */
    interface TestCallback {
        void onDone(boolean ok, String detail);
    }

    /**
     * 保存供应商后自动跑一次连通性测试。
     * 【口径】请求 {baseUrl}/models，2xx 视为通过；失败显示状态码与响应体前 200 字。
     * 【为什么放这里】保存页与「测试连接」按钮都要用，放页里会重复一份网络代码。
     * 【隐私】密钥只在内存里流转，不进日志、不进提示文案。
     */
    static void testConnection(final Context ctx, final Provider pv, final TestCallback cb) {
        if (ctx == null || pv == null) {
            return;
        }
        final String key = ProviderStore.keyOf(ctx, pv);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String detail;
                String url = ApiClient.modelsUrl(pv);
                if (url == null || url.isEmpty()) {
                    detail = "还没填 API Base Url";
                } else if (key.isEmpty()) {
                    // 【修·必须】本地没有可用密钥时根本不发请求。
                    //   老版本照发，服务端回 401 Invalid token，用户只看到「密钥不对」，
                    //   却查不出问题在本地（这正是不兼容别的软件能用的头号原因）。
                    detail = KeyVault.problem(pv.apiKey) + "\n请求地址：" + url;
                } else {
                    HttpURLConnection conn = null;
                    try {
                        conn = ApiClient.open(url, pv, key, "GET", false);
                        int code = conn.getResponseCode();
                        String body = ApiClient.readBody(conn, code);
                        if (code >= 200 && code < 300) {
                            ok = true;
                            detail = "连接正常（HTTP " + code + "）\n请求地址：" + url;
                        } else {
                            detail = ApiEndpoint.explainHttpError(code, body, url);
                        }
                    } catch (Throwable t) {
                        detail = ApiEndpoint.friendlyError(t, url);
                    } finally {
                        if (conn != null) {
                            try {
                                conn.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                final boolean fok = ok;
                final String fdetail = detail;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fok, fdetail);
                    }
                });
            }
        }).start();
    }

    /* ------------------------------ 跨页动作：拉取模型清单 ------------------------------ */

    /** 模型清单回调。 */
    interface ModelsCallback {
        void onDone(List<String> models, String error);
    }

    /**
     * 拉取供应商的可用模型清单（GET {baseUrl}/models）。
     * 【口径】地址与鉴权全走 ApiClient / ModelRules，业务层不写协议分支。
     * 【为什么放这里】模型列表页与编辑页的「从供应商拉取」都要用，放页里会重复一份网络代码。
     */
    static void fetchModels(final Context ctx, final Provider pv, final ModelsCallback cb) {
        if (ctx == null || pv == null || cb == null) {
            return;
        }
        final String key = ProviderStore.keyOf(ctx, pv);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> list = null;
                String err = null;
                String url = ApiClient.modelsUrl(pv);
                if (url == null || url.isEmpty()) {
                    err = "还没填 API Base Url";
                } else if (key.isEmpty()) {
                    // 同 testConnection：没密钥就不发无效请求，先把原因说清楚。
                    err = KeyVault.problem(pv.apiKey) + "\n请求地址：" + url;
                } else {
                    HttpURLConnection conn = null;
                    try {
                        conn = ApiClient.open(url, pv, key, "GET", false);
                        int code = conn.getResponseCode();
                        String body = ApiClient.readBody(conn, code);
                        if (code >= 200 && code < 300) {
                            list = ModelRules.parseModels(body);
                            if (list.isEmpty()) {
                                err = "接口没返回模型列表";
                            }
                        } else {
                            err = ApiEndpoint.explainHttpError(code, body, url);
                        }
                    } catch (Throwable t) {
                        err = ApiEndpoint.friendlyError(t, url);
                    } finally {
                        if (conn != null) {
                            try {
                                conn.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                final List<String> fl = list;
                final String fe = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fl, fe);
                    }
                });
            }
        }).start();
    }
}
