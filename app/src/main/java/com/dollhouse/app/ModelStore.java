package com.dollhouse.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】模型的数据层：落盘、读取、增删改、级联清理。
 *
 * 【存储】键 pv_models，数组元素是 AiModel JSON；与供应商共用 PetPrefs 的同一份偏好。
 *
 * 【交互】ProviderStore.deleteProvider 会调本类做级联清理；编辑页保存后由本类落盘。
 *
 * 【坑】① 写盘用 commit，理由同 ProviderStore。
 *        ② 删模型时若它正是「当前选中」，必须清掉 pv_cur，否则聊天页会拿着
 *          一个不存在的模型名发请求，服务端直接 400。
 *        ③ 模型的 providerId 是弱关联，找不到归属的模型按孤儿跳过不显示，
 *          但不会被自动删除（用户可能只是临时删了供应商又建回来）。
 */
public final class ModelStore {

    private ModelStore() {
    }

    public static List<AiModel> models(Context ctx) {
        ProviderStore.purgeLegacy(ctx);
        List<AiModel> out = new ArrayList<AiModel>();
        try {
            String raw = ProviderStore.p(ctx).getString(ProviderStore.KEY_MODELS, "");
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(AiModel.fromJson(o));
                }
            }
        } catch (Throwable t) {
            Logs.w("Dollhouse", "models parse failed", t);
        }
        return out;
    }

    static void writeModels(Context ctx, List<AiModel> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(list.get(i).toJson());
        }
        ProviderStore.p(ctx).edit().putString(ProviderStore.KEY_MODELS, arr.toString()).commit();
    }

    /** 某个供应商名下的模型（按 sortOrder / 名称排好）。 */
    public static List<AiModel> modelsOf(Context ctx, String providerId) {
        List<AiModel> all = models(ctx);
        List<AiModel> out = new ArrayList<AiModel>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).providerId.equals(providerId)) {
                out.add(all.get(i));
            }
        }
        Collections.sort(out, new Comparator<AiModel>() {
            @Override
            public int compare(AiModel a, AiModel b) {
                if (a.sortOrder != b.sortOrder) {
                    return a.sortOrder - b.sortOrder;
                }
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        return out;
    }

    public static AiModel findModel(Context ctx, String id) {
        List<AiModel> list = models(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                return list.get(i);
            }
        }
        return null;
    }

    public static AiModel saveModel(Context ctx, AiModel in) {
        if (in == null) {
            return null;
        }
        List<AiModel> list = models(ctx);
        boolean done = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(in.id)) {
                list.set(i, in);
                done = true;
                break;
            }
        }
        if (!done) {
            in.sortOrder = list.size();
            list.add(in);
        }
        writeModels(ctx, list);
        return in;
    }

    public static void setModelEnabled(Context ctx, String id, boolean enabled) {
        List<AiModel> list = models(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.get(i).enabled = enabled;
                break;
            }
        }
        writeModels(ctx, list);
    }

    /**
     * 删除一个模型。
     * 【坑】若删掉的正是当前选中项，必须把 pv_cur 一并置空 —— 否则聊天页会拿着
     *   一个已经不存在的模型名去发请求，服务端回 400，用户看到的是「模型不存在」。
     */
    public static void deleteModel(Context ctx, String id) {
        List<AiModel> list = models(ctx);
        List<AiModel> keep = new ArrayList<AiModel>();
        AiModel gone = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                gone = list.get(i);
            } else {
                keep.add(list.get(i));
            }
        }
        if (gone == null) {
            return;
        }
        writeModels(ctx, keep);
        String cur = ProviderStore.p(ctx).getString(ProviderStore.KEY_CURRENT, "");
        if (ProviderStore.curKey(gone.providerId, gone.id).equals(cur)) {
            ProviderStore.clearCurrent(ctx);
        }
    }

    /** 删除某个供应商名下的全部模型（由 ProviderStore.deleteProvider 调用）。 */
    static void deleteModelsOfProvider(Context ctx, String providerId) {
        List<AiModel> list = models(ctx);
        List<AiModel> keep = new ArrayList<AiModel>();
        for (int i = 0; i < list.size(); i++) {
            if (!providerId.equals(list.get(i).providerId)) {
                keep.add(list.get(i));
            }
        }
        writeModels(ctx, keep);
    }

    /** 批量写模型（拉取模型列表后一次性落盘，避免逐条 IO）。 */
    public static void saveModels(Context ctx, List<AiModel> add) {
        if (add == null || add.isEmpty()) {
            return;
        }
        List<AiModel> all = models(ctx);
        for (int i = 0; i < add.size(); i++) {
            AiModel in = add.get(i);
            boolean done = false;
            for (int k = 0; k < all.size(); k++) {
                if (all.get(k).id.equals(in.id)) {
                    all.set(k, in);
                    done = true;
                    break;
                }
            }
            if (!done) {
                in.sortOrder = all.size();
                all.add(in);
            }
        }
        writeModels(ctx, all);
    }

    /** 同供应商下是否已有同名模型。 */
    public static boolean modelExists(Context ctx, String providerId, String displayName, String selfId) {
        List<AiModel> list = models(ctx);
        String n = displayName == null ? "" : displayName.trim();
        for (int i = 0; i < list.size(); i++) {
            AiModel m = list.get(i);
            if (n.equals(m.displayName) && providerId.equals(m.providerId) && !m.id.equals(selfId)) {
                return true;
            }
        }
        return false;
    }

    /** 该供应商下已存在的模型名集合（拉取模型列表时用来标「已添加」）。 */
    public static List<String> namesOf(Context ctx, String providerId) {
        List<AiModel> list = modelsOf(ctx, providerId);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.get(i).displayName);
        }
        return out;
    }
}