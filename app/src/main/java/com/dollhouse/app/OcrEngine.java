package com.dollhouse.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】把应用内的图片扫成文字（本地离线 OCR），替掉「把原图丢给视觉模型」这条链路。
 *
 * 【为什么要换】不少中转 / 小模型根本不支持 image_url，原链路一碰上这类模型整条请求就失败；
 *   改成本地扫成文字、当正文发出去后，任何纯文本模型都能看懂图里写了什么。
 *
 * 【交互】组装请求体时由 ChatHistoryStore 读缓存文本；发请求前由 recognizePending 把
 *   还没扫过的图统一补扫一遍。识别全程在后台单线程里跑，界面上只会看到「正在看图…」。
 *
 * 【坑】识别结果（包括「没读出文字」的空串）一律落缓存：不然同一张图会在每轮请求里重扫，
 *   既费电，又把「正在看图」的等待越拖越长。
 *
 * 【坑】识别器要加载模型、是重量级对象，全局只建一个；不要每张图 new 一个。
 */
final class OcrEngine {
    private static final String TAG = "DollhouseOcr";
    /** 缓存落在独立 prefs，不挤进 feiyu_pet 的键空间。 */
    private static final String PREFS = "feiyu_ocr";
    private static final String KEY_PREFIX = "t_";
    /** 单张图的识别上限：超时按「没读出文字」收尾，不能让人偶永远停在思考中。 */
    private static final long TIMEOUT_SEC = 30L;
    /** 送识别的最大边长：原图动辄几千像素，缩到 1600 已足够看清文字，还能省内存。 */
    private static final int MAX_DIM = 1600;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dollhouse-ocr");
            t.setDaemon(true);
            return t;
        }
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile TextRecognizer recognizer;

    interface Callback {
        /** 识别结束（可能一个字的没读出来，此时 text 为空串）；始终在主线程回调。 */
        void onDone(String text);
    }

    private OcrEngine() {
    }

    /** 这张图是否还没扫过。没扫过的图要先补扫，再发请求。 */
    static boolean isPending(Context ctx, String path) {
        return ctx != null && path != null && !path.isEmpty()
                && !prefs(ctx).contains(KEY_PREFIX + path);
    }

    /** 已缓存的识别文本；返回 null 表示这张图还没扫过。 */
    static String text(Context ctx, String path) {
        if (ctx == null || path == null || path.isEmpty()) {
            return null;
        }
        SharedPreferences p = prefs(ctx);
        String key = KEY_PREFIX + path;
        return p.contains(key) ? p.getString(key, "") : null;
    }

    /**
     * 把一段历史里所有「有图但还没扫」的图片依次扫掉，全部结束后在主线程回调 done。
     * 【为什么串行】识别器是单例、模型只算一份，串行既不抢内存也不会互相干扰；
     *   请求窗口最多十来条，串行耗时可接受。
     */
    static void recognizePending(Context ctx, JSONArray history, Runnable done) {
        Context app = (ctx == null) ? null : ctx.getApplicationContext();
        List<String> todo = new ArrayList<String>();
        if (app != null && history != null) {
            for (int i = 0; i < history.length(); i++) {
                JSONObject o = history.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String path = o.optString("image", null);
                if (path == null || path.isEmpty() || !ImageStore.exists(app, path)) {
                    continue;
                }
                if (isPending(app, path) && !todo.contains(path)) {
                    todo.add(path);
                }
            }
        }
        if (todo.isEmpty()) {
            if (done != null) {
                UI.post(done);
            }
            return;
        }
        runNext(app, todo, 0, done);
    }

    private static void runNext(final Context app, final List<String> todo, final int idx, final Runnable done) {
        if (idx >= todo.size()) {
            if (done != null) {
                UI.post(done);
            }
            return;
        }
        final String path = todo.get(idx);
        recognize(app, path, new Callback() {
            @Override
            public void onDone(String t) {
                put(app, path, t);
                runNext(app, todo, idx + 1, done);
            }
        });
    }

    /** 扫一张图；结果在主线程回调（失败同样回空串，不会把调用方卡住）。 */
    static void recognize(final Context ctx, final String path, final Callback cb) {
        final Context app = (ctx == null) ? null : ctx.getApplicationContext();
        if (app == null || path == null || path.isEmpty()) {
            post(cb, "");
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String out = "";
                Bitmap bmp = null;
                try {
                    bmp = decode(app, path);
                    if (bmp != null) {
                        Task<Text> task = recognizer().process(InputImage.fromBitmap(bmp, 0));
                        out = join(Tasks.await(task, TIMEOUT_SEC, TimeUnit.SECONDS));
                    }
                } catch (Throwable t) {
                    Logs.w(TAG, "识别失败，按「没读出文字」处理：" + path, t);
                } finally {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                }
                post(cb, out);
            }
        });
    }

    private static void put(Context ctx, String path, String text) {
        try {
            prefs(ctx).edit().putString(KEY_PREFIX + path, text == null ? "" : text).apply();
        } catch (Throwable unused) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    private static void post(final Callback cb, final String text) {
        if (cb == null) {
            return;
        }
        UI.post(new Runnable() {
            @Override
            public void run() {
                cb.onDone(text);
            }
        });
    }

    private static TextRecognizer recognizer() {
        TextRecognizer r = recognizer;
        if (r == null) {
            synchronized (OcrEngine.class) {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                }
                r = recognizer;
            }
        }
        return r;
    }

    /** 按行拼接识别结果：行间留换行，空行丢掉。 */
    private static String join(Text text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText();
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }

    /** 解码本地图片并按 MAX_DIM 降采样：先只读尺寸，再按采样率解码，避免整张大图进内存。 */
    private static Bitmap decode(Context ctx, String path) {
        File f = ImageStore.fileFor(ctx, path);
        if (!f.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }
}
