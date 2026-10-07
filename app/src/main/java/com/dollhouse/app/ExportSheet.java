package com.dollhouse.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 【职责】把全部供应商与模型导出成一个 JSON 文件。
 *
 * 【口径（规格书需求 14 = C）】API Key 默认打码，用户主动勾「包含密钥明文」才写全文；
 *        默认不勾，避免随手导出就把密钥落到文件里。
 *
 * 【落点】Android 10+ 走 MediaStore 写「下载」目录（无需存储权限）；
 *        更低版本退回本 App 的外部私有目录（minSdk 24 兜底，同样无需权限）。
 *
 * 【为什么没有 Toast】UiKit 硬约束：全 App 禁止 Toast / Snackbar / 自实现浮层短提示。
 *        结果一律用自绘弹窗说明（路径 + 条数），保证「点了就有反馈」。
 */
final class ExportSheet {

    /** 文件信封标识：将来做导入时靠它认领自家文件。 */
    private static final String KIND = "dollhouse.providers";
    private static final int VER = 1;

    private ExportSheet() {
    }

    /** 入口：先让用户选择是否包含明文密钥。 */
    static void show(final Activity act) {
        if (act == null) {
            return;
        }
        final Context ctx = act;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(ctx);
        msg.setText("导出全部供应商与模型（含禁用项）为 JSON 文件，写入「下载」目录。");
        msg.setTextSize(UiKit.FS_BTN);
        msg.setTextColor(UiKit.SUB);
        msg.setLineSpacing(UiKit.dp(ctx, 3), 1.0f);
        box.addView(msg);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(ctx, 14), 0, 0);
        TextView label = new TextView(ctx);
        label.setText("包含密钥明文");
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final UiKit.Switch withKeys = new UiKit.Switch(ctx);
        withKeys.setOn(false);
        withKeys.setClickable(true);
        withKeys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withKeys.setOn(!withKeys.isOn(), true);
            }
        });
        row.addView(withKeys);
        box.addView(row);
        TextView warn = new TextView(ctx);
        warn.setText("默认不导出密钥，导出的 Key 只保留首尾各 4 位。开启后文件里是可用的完整密钥，请自行妥善保管。");
        warn.setTextSize(UiKit.FS_TINY);
        warn.setTextColor(UiKit.ERR);
        warn.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        warn.setLineSpacing(UiKit.dp(ctx, 3), 1.0f);
        box.addView(warn);

        UiKit.showDialog(act, "导出供应商数据", box, "导出", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(act, withKeys.isOn());
            }
        }, "取消", null);
    }

    /** 后台组包 + 落盘，结果回主线程弹窗。 */
    private static void run(final Activity act, final boolean withKeys) {
        final Context ctx = act.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String path;
                String error = null;
                try {
                    String json = buildJson(ctx, withKeys);
                    path = write(ctx, json, withKeys);
                } catch (Throwable t) {
                    path = null;
                    error = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final String fpath = path;
                final String ferr = error;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (act.isFinishing()) {
                            return;
                        }
                        if (ferr != null) {
                            UiKit.showDialog(act, "导出失败", plain(act, ferr), "好", null, null, null);
                        } else {
                            UiKit.showDialog(act, "导出完成", plain(act, fpath), "好", null, null, null);
                        }
                    }
                });
            }
        }).start();
    }

    /** 组装导出内容：信封 + 全量供应商（可打码）+ 全量模型。 */
    static String buildJson(Context ctx, boolean withKeys) throws Exception {
        List<Provider> providers = ProviderStore.providers(ctx);
        List<AiModel> models = ModelStore.models(ctx);
        JSONObject root = new JSONObject();
        root.put("kind", KIND);
        root.put("ver", VER);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("includePlainKeys", withKeys);
        JSONArray pvArr = new JSONArray();
        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            JSONObject o = p.toJson();
            String plain = ProviderStore.keyOf(ctx, p);
            // 打码走 KeyVault.mask：短密钥整段打码，不留半个可用片段。
            o.put("apiKey", withKeys ? plain : KeyVault.mask(plain));
            pvArr.put(o);
        }
        root.put("providers", pvArr);
        JSONArray mArr = new JSONArray();
        for (int i = 0; i < models.size(); i++) {
            mArr.put(models.get(i).toJson());
        }
        root.put("models", mArr);
        return root.toString(2);
    }

    /** 写入文件，返回可读路径。 */
    private static String write(Context ctx, String json, boolean withKeys) throws Exception {
        String name = "Dollhouse-导出-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + (withKeys ? "-含密钥" : "") + ".json";
        byte[] data = json.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item = ctx.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (item == null) {
                throw new IllegalStateException("系统没给可写的位置");
            }
            OutputStream os = ctx.getContentResolver().openOutputStream(item);
            if (os == null) {
                throw new IllegalStateException("打不开导出文件");
            }
            try {
                os.write(data);
            } finally {
                os.close();
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(item, cv, null, null);
            return "下载/" + name + "\n共 " + data.length + " 字节";
        }
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File f = new File(dir, name);
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        return f.getAbsolutePath() + "\n共 " + data.length + " 字节";
    }

    /** 弹窗正文：一句纯文字说明。 */
    private static TextView plain(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text == null ? "" : text);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.SUB);
        t.setLineSpacing(UiKit.dp(ctx, 3), 1.0f);
        t.setTextIsSelectable(true);
        return t;
    }
}